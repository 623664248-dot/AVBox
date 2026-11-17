package com.github.avbox.osc.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.target.Target
import coil3.toBitmap
import com.github.avbox.osc.R
import com.github.avbox.osc.util.AppForeground

/**
 * Background playback service for the LX music player (v1.16.0 redesign).
 *
 * Why this no longer uses media3's MediaSessionService / MediaController:
 * on the test device (Honor / MagicOS) every MediaController connect
 * failed in 1-2 ms with an IllegalArgumentException out of
 * SessionToken's constructor — its queryIntentServices(
 * MediaSessionService action, own package) lookup returns empty for
 * this app's own service even though the merged manifest is complete,
 * and the v1.15.4 <queries> visibility declarations did not change
 * that. The media3 session route is therefore dead on that device.
 *
 * This service is a plain same-process [Service] instead:
 *  - It owns one ExoPlayer and hands it out through a [LocalBinder]
 *    (direct object reference, no IPC, no SessionToken, no package
 *    manager query). MusicServiceConnection owns the binding.
 *  - Lock-screen / control-center integration comes from a framework
 *    [MediaSession] (android.media.session), whose state and metadata
 *    are kept in sync with the player.
 *  - The foreground notification is built manually with a
 *    Notification.MediaStyle bound to the framework session token,
 *    with previous / play-pause / next actions delivered back to this
 *    service as explicit intents.
 *
 * The queue still lives in the player Activity (or the music page's
 * mini bar): the service plays one resolved MediaItem at a time, and
 * next/previous — from the notification, the lock screen or the
 * framework session — are forwarded to the registered
 * [NextPrevHandler]. With no handler registered they are no-ops.
 *
 * URL resolution stays in the UI layer (LxMusicPlayerActivity resolves
 * a direct link through LxBridge / PyMusicBridge first); the resolved
 * track's HTTP headers are applied through [setHeaders], the same
 * DefaultHttpDataSource.Factory mechanism the local player uses.
 */
class LxPlaybackService : Service() {

    /** Queue owner callbacks for notification / lock-screen next/prev. */
    interface NextPrevHandler {
        fun onNext()
        fun onPrevious()
    }

    inner class LocalBinder : Binder() {
        fun getService(): LxPlaybackService = this@LxPlaybackService
    }

    companion object {
        private const val CHANNEL_ID = "avbox_music_playback"
        private const val NOTIFICATION_ID = 2001

        private const val ACTION_CMD_PLAY_PAUSE =
            "com.github.avbox.osc.music.CMD_PLAY_PAUSE"
        private const val ACTION_CMD_PLAY = "com.github.avbox.osc.music.CMD_PLAY"
        private const val ACTION_CMD_PAUSE = "com.github.avbox.osc.music.CMD_PAUSE"
        private const val ACTION_CMD_PREV = "com.github.avbox.osc.music.CMD_PREV"
        private const val ACTION_CMD_NEXT = "com.github.avbox.osc.music.CMD_NEXT"
        private const val ACTION_CMD_STOP = "com.github.avbox.osc.music.CMD_STOP"

        private const val REQ_PLAY_PAUSE = 11
        private const val REQ_PREV = 12
        private const val REQ_NEXT = 13
    }

    /** Registered by the queue owner (player Activity / mini bar). */
    @Volatile
    var nextPrevHandler: NextPrevHandler? = null

    private val binder = LocalBinder()

    /**
     * Shared HTTP factory for the service player: py spiders may answer
     * playerContent with required headers, applied per track through
     * [setHeaders] into the factory's default request properties.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)

    private var exo: ExoPlayer? = null
    private var frameworkSession: MediaSession? = null
    private var foregroundStarted = false

    /**
     * App foreground/background transitions (v1.20.0): the
     * background-play pref and the floating mini player both key
     * off "the app has left the screen entirely".
     */
    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        if (foreground) {
            FloatingMiniPlayer.sync(this)
        } else {
            onAppBackgrounded()
        }
    }

    private fun onAppBackgrounded() {
        if (!MusicPlayPrefs.backgroundPlay) {
            try {
                exo?.pause()
            } catch (t: Throwable) {
                // Already paused/stopped.
            }
            try {
                val np = NowPlayingState.current()
                if (np != null && !np.serviceBacked && np.isPlaying) {
                    NowPlayingState.localControls?.playPause()
                }
            } catch (t: Throwable) {
                // Local controls are best-effort here.
            }
        }
        FloatingMiniPlayer.sync(this)
    }

    /** Cover of the current track: the URL it was loaded from and the bitmap itself. */
    private var artworkUrl: String? = null
    private var artworkBitmap: Bitmap? = null

    /** The player this service owns; null only before onCreate / after onDestroy. */
    val player: ExoPlayer?
        get() = exo

    /** Audio session id of the service player (for the Activity's AudioFX EQ). */
    val audioSessionId: Int
        get() = try {
            exo?.audioSessionId ?: 0
        } catch (t: Throwable) {
            0
        }

    /** Applies a resolved track's HTTP headers to the shared HTTP factory. */
    fun setHeaders(headers: Map<String, String>) {
        try {
            httpDataSourceFactory.setDefaultRequestProperties(headers)
        } catch (t: Throwable) {
            // Headers are best effort; playback continues without them.
        }
    }

    /**
     * Mirrors this player's state into [NowPlayingState] so the music
     * page's mini bar stays truthful after the player Activity is gone,
     * and keeps the framework session + notification in sync.
     * Transitions republish from the item's metadata (its mediaId is
     * the song key) and keep the enrichment fields — queue, source
     * label, canNext — of the record published just before the
     * hand-off; the playing flag is only ever flipped on records this
     * service backs.
     */
    private val nowPlayingListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (NowPlayingState.current()?.serviceBacked == true) {
                NowPlayingState.updatePlaying(isPlaying)
            }
            syncSessionAndNotification()
            FloatingMiniPlayer.sync(this@LxPlaybackService)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val item = mediaItem ?: return
            val metadata = item.mediaMetadata
            val existing = NowPlayingState.current()
            NowPlayingState.publish(
                NowPlayingState.Info(
                    trackKey = item.mediaId.takeIf { it.isNotEmpty() }
                        ?: existing?.trackKey ?: "",
                    title = metadata.title?.toString()
                        ?: existing?.title ?: "",
                    singer = metadata.artist?.toString()
                        ?: existing?.singer ?: "",
                    img = metadata.artworkUri?.toString() ?: existing?.img,
                    sourceLabel = existing?.sourceLabel ?: "",
                    isPlaying = exo?.isPlaying == true,
                    canNext = existing?.canNext ?: false,
                    serviceBacked = true,
                    queueJson = existing?.queueJson ?: "",
                    queueIndex = existing?.queueIndex ?: 0,
                ),
            )
            syncSessionAndNotification()
            FloatingMiniPlayer.sync(this@LxPlaybackService)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED &&
                NowPlayingState.current()?.serviceBacked == true
            ) {
                NowPlayingState.updatePlaying(false)
            }
            syncSessionAndNotification()
        }
    }

    /** Framework transport controls: playback goes to the player, queue moves to the handler. */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            exo?.play()
        }

        override fun onPause() {
            exo?.pause()
        }

        override fun onSkipToNext() {
            nextPrevHandler?.onNext()
        }

        override fun onSkipToPrevious() {
            nextPrevHandler?.onPrevious()
        }

        override fun onSeekTo(pos: Long) {
            try {
                exo?.seekTo(pos)
            } catch (t: Throwable) {
                // no seekable media loaded
            }
        }

        override fun onStop() {
            exo?.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicCrashLog.install(this)
        MusicCrashLog.log(this, "svc2: service onCreate")
        createNotificationChannel()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        player.addListener(nowPlayingListener)
        exo = player
        // Framework media session for lock-screen / control-center
        // integration. The session activity returns notification taps
        // to the player page; the Activity is referenced by component
        // name, not by class: this file lives in the music package and
        // must not import ui.* (no new package cycles, see AGENTS.md).
        val session = MediaSession(this, "AVBoxMusic")
        session.setCallback(sessionCallback, Handler(Looper.getMainLooper()))
        session.setSessionActivity(sessionActivityIntent())
        session.isActive = true
        frameworkSession = session
        AppForeground.addListener(foregroundListener)
        FloatingMiniPlayer.sync(this)
        syncSessionAndNotification()
        // Promote to foreground right away with the initial minimal
        // notification; later updates go through notify once this has
        // succeeded at least once (see updateNotification).
        ensureForeground()
    }

    override fun onBind(intent: Intent?): IBinder {
        MusicCrashLog.log(this, "svc2: bound")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        MusicCrashLog.log(this, "svc2: unbound")
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CMD_PLAY_PAUSE -> {
                val player = exo
                if (player != null) {
                    if (player.isPlaying) player.pause() else player.play()
                }
            }

            ACTION_CMD_PLAY -> exo?.play()
            ACTION_CMD_PAUSE -> exo?.pause()
            ACTION_CMD_NEXT -> nextPrevHandler?.onNext()
            ACTION_CMD_PREV -> nextPrevHandler?.onPrevious()
            ACTION_CMD_STOP -> {
                try {
                    exo?.pause()
                    exo?.stop()
                } catch (t: Throwable) {
                    // already stopped
                }
                stopForeground(true)
                foregroundStarted = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureForeground()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exo
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        MusicCrashLog.log(this, "svc2: service onDestroy")
        AppForeground.removeListener(foregroundListener)
        FloatingMiniPlayer.hide(this)
        // The service dying ends service playback: its mini-bar record
        // must not outlive it (a local record belongs to the Activity).
        if (NowPlayingState.current()?.serviceBacked == true) {
            NowPlayingState.clear()
        }
        nextPrevHandler = null
        try {
            frameworkSession?.isActive = false
            frameworkSession?.release()
        } catch (t: Throwable) {
            // already released
        }
        frameworkSession = null
        try {
            exo?.removeListener(nowPlayingListener)
            exo?.release()
        } catch (t: Throwable) {
            // already released
        }
        exo = null
        stopForeground(true)
        foregroundStarted = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ session

    /** Pushes the player's current metadata + playback state into the framework session. */
    private fun syncSessionAndNotification() {
        val session = frameworkSession ?: return
        val player = exo ?: return
        try {
            val item = player.currentMediaItem
            val metadata = item?.mediaMetadata
            val builder = MediaMetadata.Builder()
                .putString(
                    MediaMetadata.METADATA_KEY_TITLE,
                    metadata?.title?.toString() ?: "",
                )
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    metadata?.artist?.toString() ?: "",
                )
                .putString(
                    MediaMetadata.METADATA_KEY_ALBUM,
                    metadata?.albumTitle?.toString() ?: "",
                )
            val artwork = metadata?.artworkUri?.toString()
            if (!artwork.isNullOrEmpty()) {
                builder.putString(MediaMetadata.METADATA_KEY_ART_URI, artwork)
            }
            // Cover bitmap for the media card / lock screen / 灵动岛:
            // only when the loaded bitmap belongs to the current item.
            val cover = artworkBitmap
            if (!artwork.isNullOrEmpty() && cover != null && artworkUrl == artwork) {
                builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
                builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, cover)
            }
            if (artwork.isNullOrEmpty()) {
                artworkUrl = null
                artworkBitmap = null
            } else if (artworkUrl != artwork) {
                artworkUrl = artwork
                artworkBitmap = null
                loadArtwork(artwork)
            }
            val duration = player.duration
            if (duration > 0) {
                builder.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            }
            session.setMetadata(builder.build())
            val state = when (player.playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
                Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
                Player.STATE_READY ->
                    if (player.isPlaying) {
                        PlaybackState.STATE_PLAYING
                    } else {
                        PlaybackState.STATE_PAUSED
                    }

                else -> PlaybackState.STATE_NONE
            }
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        state,
                        player.currentPosition.coerceAtLeast(0L),
                        if (player.isPlaying) 1f else 0f,
                    )
                    .build(),
            )
        } catch (t: Throwable) {
            // Session sync is best effort; playback is unaffected.
        }
        updateNotification()
    }

    /**
     * Loads the current track's cover asynchronously with Coil (the
     * same pattern as the video PlaybackService), then re-syncs the
     * session + notification so the media card, lock screen and
     * notification show the cover instead of the app icon. The
     * [artworkUrl] equality guard drops stale loads for a previous
     * track, and the re-sync cannot retrigger a load because
     * artworkUrl already equals the current item's artwork by then.
     * Any failure is swallowed: artwork is cosmetic and must never
     * affect playback or the text notification.
     */
    private fun loadArtwork(url: String) {
        try {
            val request = ImageRequest.Builder(this)
                .data(url)
                .size(512, 512)
                .target(object : Target {
                    override fun onSuccess(result: Image) {
                        if (artworkUrl != url) return
                        artworkBitmap = result.toBitmap()
                        syncSessionAndNotification()
                    }
                })
                .build()
            SingletonImageLoader.get(this).enqueue(request)
        } catch (t: Throwable) {
            // artwork load is best effort
        }
    }

    // ------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        } catch (t: Throwable) {
            // channel creation is best effort
        }
    }

    private fun sessionActivityIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent().setComponent(
                ComponentName(
                    this,
                    "com.github.avbox.osc.ui.music.LxMusicPlayerActivity",
                ),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LxPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Media notification (title/artist + prev/play-pause/next) with
     * the current track's cover as the large icon once [loadArtwork]
     * has fetched it. Until the cover arrives, or if the fetch fails,
     * the notification falls back to the app icon — the pre-v1.16.1
     * behaviour — so the notification itself never depends on the
     * artwork fetch succeeding.
     */
    private fun buildNotification(): Notification {
        val player = exo
        val metadata = player?.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString()?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.music_default_name)
        val artist = metadata?.artist?.toString() ?: ""
        val playing = player?.isPlaying == true
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(sessionActivityIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(frameworkSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(
                R.drawable.exo_icon_previous,
                getString(R.string.music_previous),
                actionPendingIntent(ACTION_CMD_PREV, REQ_PREV),
            )
            .addAction(
                if (playing) R.drawable.exo_icon_pause else R.drawable.exo_icon_play,
                getString(
                    if (playing) R.string.common_pause else R.string.common_play,
                ),
                actionPendingIntent(ACTION_CMD_PLAY_PAUSE, REQ_PLAY_PAUSE),
            )
            .addAction(
                R.drawable.exo_icon_next,
                getString(R.string.music_next),
                actionPendingIntent(ACTION_CMD_NEXT, REQ_NEXT),
            )
        val cover = artworkBitmap
        if (cover != null && artworkUrl == metadata?.artworkUri?.toString()) {
            builder.setLargeIcon(cover)
        }
        return builder.build()
    }

    /** Calls startForeground with the current notification; failures are logged, never thrown. */
    private fun ensureForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            if (!foregroundStarted) {
                foregroundStarted = true
                MusicCrashLog.log(this, "svc2: startForeground ok")
            }
        } catch (t: Throwable) {
            MusicCrashLog.log(
                this,
                "svc2: startForeground failed ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /** Refreshes the notification once the service is foreground; promotes it otherwise. */
    private fun updateNotification() {
        if (!foregroundStarted) {
            ensureForeground()
            return
        }
        try {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            // refresh is best effort
        }
    }
}
