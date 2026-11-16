package com.github.avbox.osc.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background playback service for the LX music player (rewritten in
 * v1.13.3; the v1.9.0 original was unregistered in v1.9.2 after an
 * undiagnosed tap-to-play crash and is superseded by this version).
 *
 * The service owns an ExoPlayer and exposes it through a [MediaSession];
 * MediaSessionService's default media notification
 * (DefaultMediaNotification.Provider) shows the title/artist/artwork
 * from each MediaItem's MediaMetadata with working play/pause from the
 * system media controls (lock screen / notification shade). The queue
 * stays in the player Activity, which hands over one resolved MediaItem
 * at a time; the notification therefore offers play/pause only, no
 * previous/next.
 *
 * URL resolution stays in the UI layer (LxMusicPlayerActivity resolves a
 * direct link through LxBridge / PyMusicBridge first). Two custom
 * session commands support that hand-off:
 *  - [ACTION_SET_HEADERS]: the Activity pushes the resolved track's
 *    HTTP headers; they are applied to this service's shared
 *    [DefaultHttpDataSource.Factory] default request properties — the
 *    same mechanism the Activity's local player uses (v1.13.0).
 *  - [ACTION_GET_AUDIO_SESSION_ID]: returns the service player's audio
 *    session id so the Activity can attach its AudioFX EQ to the
 *    service's output (MediaController exposes no session id).
 *
 * If connecting to this service fails for any reason, the Activity
 * degrades to its in-Activity player for the rest of the session, so
 * the worst case is the pre-v1.13.3 behaviour.
 */
class LxPlaybackService : MediaSessionService() {

    companion object {
        /** Custom command: push the current track's HTTP headers. */
        const val ACTION_SET_HEADERS = "com.github.avbox.osc.music.SET_HEADERS"

        /** Custom command: read the service player's audio session id. */
        const val ACTION_GET_AUDIO_SESSION_ID =
            "com.github.avbox.osc.music.GET_AUDIO_SESSION_ID"

        /** Bundle key (in [ACTION_SET_HEADERS] args): the header names. */
        const val EXTRA_HEADER_KEYS = "headerKeys"

        /** Bundle key prefix (in [ACTION_SET_HEADERS] args): value of one header. */
        const val EXTRA_HEADER_VALUE_PREFIX = "hv:"

        /** Bundle key (in [ACTION_GET_AUDIO_SESSION_ID] result): the session id. */
        const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"
    }

    /**
     * Shared HTTP factory for the service player: py spiders may answer
     * playerContent with required headers, pushed per track through
     * [ACTION_SET_HEADERS] into the factory's default request properties.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    /**
     * Mirrors this player's state into [NowPlayingState] so the music
     * page's mini bar stays truthful after the player Activity is gone.
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
                    isPlaying = player?.isPlaying == true,
                    canNext = existing?.canNext ?: false,
                    serviceBacked = true,
                    queueJson = existing?.queueJson ?: "",
                    queueIndex = existing?.queueIndex ?: 0,
                ),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED &&
                NowPlayingState.current()?.serviceBacked == true
            ) {
                NowPlayingState.updatePlaying(false)
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_SET_HEADERS -> {
                    applyHeaders(args)
                    Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS),
                    )
                }

                ACTION_GET_AUDIO_SESSION_ID -> {
                    val extras = Bundle()
                    extras.putInt(
                        EXTRA_AUDIO_SESSION_ID,
                        player?.audioSessionId ?: 0,
                    )
                    Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, extras),
                    )
                }

                else -> Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
                )
            }
        }
    }

    /** Applies a pushed header set to the shared HTTP factory. */
    private fun applyHeaders(args: Bundle) {
        val keys = args.getStringArrayList(EXTRA_HEADER_KEYS) ?: return
        val headers = LinkedHashMap<String, String>()
        for (key in keys) {
            val value = args.getString(EXTRA_HEADER_VALUE_PREFIX + key)
            if (value != null) {
                headers[key] = value
            }
        }
        httpDataSourceFactory.setDefaultRequestProperties(headers)
    }

    override fun onCreate() {
        super.onCreate()
        MusicCrashLog.install(this)
        MusicCrashLog.log(this, "svc: service onCreate")
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        exo.addListener(nowPlayingListener)
        player = exo
        // Notification / lock-screen tap returns to the player page
        // through the session activity (media3's default mechanism:
        // DefaultMediaNotification uses it as the content intent). The
        // Activity is referenced by component name, not by class: this
        // file lives in the music package and must not import ui.*
        // (no new package cycles, see AGENTS.md).
        val sessionActivity = PendingIntent.getActivity(
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
        mediaSession = MediaSession.Builder(this, exo)
            .setCallback(sessionCallback)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? {
        MusicCrashLog.log(this, "svc: onGetSession")
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val sessionPlayer = mediaSession?.player
        if (sessionPlayer != null && !sessionPlayer.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The service dying ends service playback: its mini-bar record
        // must not outlive it (a local record belongs to the Activity).
        if (NowPlayingState.current()?.serviceBacked == true) {
            NowPlayingState.clear()
        }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
