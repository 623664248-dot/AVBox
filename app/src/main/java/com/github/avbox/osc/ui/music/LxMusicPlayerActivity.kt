package com.github.avbox.osc.ui.music

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxFavorites
import com.github.avbox.osc.music.LxPlaybackService
import com.github.avbox.osc.music.LyricMatcher
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicCrashLog
import com.github.avbox.osc.music.MusicServiceConnection
import com.github.avbox.osc.music.NowPlayingState
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.music.PyMusicBridge
import com.github.avbox.osc.music.PyMusicSources
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.PermissionHelper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Standalone music player for the music tab: plays the search-result queue
 * with its own ExoPlayer instance. Play URLs are resolved per track through
 * the LX source script (LxBridge); it deliberately does not go through the
 * VOD Detail/PlayLoader pipeline (the existing ui.MusicPlayerActivity is
 * bound to a VOD playback session and cannot take an external queue).
 *
 * Playback target (v1.13.3): the player first tries the background
 * [LxPlaybackService] through a MediaController; if connecting fails,
 * times out or drops, the session degrades to the in-Activity
 * ExoPlayer (the pre-v1.13.3 behaviour), so the worst case is exactly
 * the old player. All queue/loop/progress logic talks to the [Player]
 * interface, which the controller also implements.
 *
 * Connection hardening (v1.15.1): the controller is the process-wide
 * shared one from MusicServiceConnection (the music page warms it up
 * before the user even taps a song), the connect budget is 10 s, and
 * a session that degraded keeps retrying in the background — a late
 * success takes over from the next track instead of interrupting the
 * song the local player is playing.
 */
class LxMusicPlayerActivity : BaseActivity() {

    /** Which playback target this session locked onto at its first start. */
    private enum class PlaybackMode { UNKNOWN, SERVICE, LOCAL }

    companion object {
        private const val EXTRA_QUEUE = "lx_queue"
        private const val EXTRA_INDEX = "lx_index"

        /** Set by [startAttached]: bind to the live service session. */
        private const val EXTRA_ATTACH = "lx_attach"

        /** KV key for the persisted loop mode (LxLoopMode name). */
        private const val KV_LOOP_MODE = "lx_loop_mode"

        /**
         * How long building the MediaController may take before
         * degrading. 10 s since v1.15.1: the old 3 s budget lost the
         * cold-start race on the test device (QuickJS/Python init
         * competing), which is why the service never engaged there.
         */
        private const val SERVICE_CONNECT_TIMEOUT_MS = 10000L

        /** How long the first playAt waits for the connection attempt. */
        private const val SERVICE_CONNECT_WAIT_MS = 10000L

        /** Delay between in-session service connection retries. */
        private const val SERVICE_RETRY_INTERVAL_MS = 5000L

        /** Max in-session retries after a connection-failure degrade. */
        private const val SERVICE_RETRY_MAX_ATTEMPTS = 6

        /** Timeout for one custom command round-trip to the service. */
        private const val SERVICE_COMMAND_TIMEOUT_MS = 2000L

        /**
         * Last queue handed to [start] in this process, remembered so a
         * queue-less relaunch (notification / session-activity tap after
         * the previous instance was destroyed) can restore the page
         * instead of finishing instantly.
         */
        @Volatile
        private var lastQueueJson: String? = null

        @Volatile
        private var lastIndex: Int = 0

        /** AudioFX strength scale is 0..1000; v1.12.0 uses the maximum. */
        private const val EQ_EFFECT_STRENGTH: Short = 1000

        /**
         * Anchor weights (-1..1) of the built-in EQ curves, defined at 5
         * points and linearly interpolated onto the device's band count.
         * A weight is multiplied by the positive end of the equalizer's
         * band level range, so 1.0 means "this band fully up".
         */
        private val CURVE_BASS_ANCHORS = floatArrayOf(1.0f, 0.75f, 0.25f, -0.1f, -0.25f)
        private val CURVE_VOCAL_ANCHORS = floatArrayOf(-0.35f, 0.25f, 1.0f, 0.7f, 0.0f)
        private val CURVE_BRIGHT_ANCHORS = floatArrayOf(-0.25f, -0.05f, 0.2f, 0.65f, 1.0f)

        fun start(context: Context, queueJson: String, index: Int) {
            lastQueueJson = queueJson
            lastIndex = index
            context.startActivity(
                Intent(context, LxMusicPlayerActivity::class.java)
                    .putExtra(EXTRA_QUEUE, queueJson)
                    .putExtra(EXTRA_INDEX, index),
            )
        }

        /**
         * Opens the player page attached to the session the service is
         * already playing (the music page mini bar's body tap): no queue
         * extra, and the remembered queue is left untouched — init
         * restores it and binds the UI to the live session instead of
         * restarting the song.
         */
        fun startAttached(context: Context) {
            context.startActivity(
                Intent(context, LxMusicPlayerActivity::class.java)
                    .putExtra(EXTRA_ATTACH, true),
            )
        }
    }

    private val state = LxPlayerState()
    private var player: ExoPlayer? = null
    private var resolveJob: Job? = null
    private var lyricJob: Job? = null
    private var tickerJob: Job? = null
    private var colorJob: Job? = null
    private var downloadJob: Job? = null

    // Background-service playback (v1.13.3). The controller, once built,
    // is just another Player; playbackMode locks at the first start and
    // moves SERVICE -> LOCAL on degrade. Since v1.15.1 it can also move
    // back LOCAL -> SERVICE, but only at a track start after a retry
    // reconnected (see serviceReadyForNext), never mid-song.
    private var controller: MediaController? = null
    private var connectJob: Job? = null
    private var attachJob: Job? = null
    private var playbackMode = PlaybackMode.UNKNOWN
    private var serviceDegraded = false

    /**
     * True when [controller] is the process-wide shared one from
     * MusicServiceConnection: this Activity removes its listener on
     * destroy/degrade but never releases it. (A controller this
     * Activity owned outright would be released instead.)
     */
    private var controllerIsShared = false

    /** Retry loop of a degraded session (v1.15.1); see degradeToLocal. */
    private var serviceRetryJob: Job? = null

    /**
     * Set when a retry reconnected the service after the session had
     * degraded: the next track start switches playback to the service
     * (awaitActivePlayer), the current local song plays on untouched.
     */
    private var serviceReadyForNext = false

    /** startForegroundService is issued once per Activity (v1.15.1). */
    private var foregroundServiceStarted = false

    /** Audio session id of the service player, fetched by custom command. */
    private var serviceSessionId = 0
    private var sessionIdFetchJob: Job? = null

    /**
     * Playback events shared by the local player and the service
     * controller: only the mode-locked target ever has media, so the
     * idle side stays silent.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            state.buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                ensureEffects()
            }
            if (playbackState == Player.STATE_ENDED) {
                advanceAuto()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state.playing = isPlaying
            // Keep the mini bar's playing flag in sync, but only for
            // the record of the song this page is showing.
            val current = state.current
            val info = NowPlayingState.current()
            if (current != null && info != null &&
                info.trackKey == NowPlayingState.keyOf(current)
            ) {
                NowPlayingState.updatePlaying(isPlaying)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            state.buffering = false
            state.errorText = error.message ?: getString(R.string.music_resolve_failed)
        }
    }

    /**
     * Mini-bar controls for the local (degraded) mode, registered in
     * [NowPlayingState] while this Activity owns local playback. The
     * bar's service-mode buttons go through its own MediaController
     * instead; these only ever run on the main thread (click handlers).
     */
    private val localControlsImpl = object : NowPlayingState.LocalControls {
        override fun playPause() {
            togglePlay()
        }

        override fun next() {
            playNextManual()
        }

        override fun stop() {
            resolveJob?.cancel()
            try {
                player?.pause()
                player?.stop()
            } catch (t: Throwable) {
                // already stopped; the record is cleared either way
            }
            state.playing = false
            state.buffering = false
            NowPlayingState.clear()
        }
    }

    // Sound effects (AudioFX) attached to the player's audio session. Any
    // of them may be null on devices without audiofx support; every use is
    // guarded so a missing effect can never break playback.
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var effectsSessionId: Int = 0

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {}

    override fun init() {
        MusicCrashLog.install(this)
        enableTransparentEdgeToEdge()
        PermissionHelper.requestNotificationIfNeeded(this)
        var queue = MusicTrack.listFromJson(intent?.getStringExtra(EXTRA_QUEUE))
        var startIndex = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0
        if (queue.isEmpty()) {
            // Queue-less launch (notification / session-activity tap
            // after the previous instance was destroyed): restore the
            // queue remembered from the last start() in this process
            // instead of finishing instantly.
            val remembered = lastQueueJson
            if (!remembered.isNullOrEmpty()) {
                queue = MusicTrack.listFromJson(remembered)
                startIndex = lastIndex
            }
        }
        if (queue.isEmpty()) {
            finish()
            return
        }
        lastQueueJson = MusicTrack.listToJson(queue)
        state.queue = queue
        state.index = startIndex.coerceIn(0, queue.size - 1)
        // The "" default matters: KV returns null for unregistered keys
        // when no default is given (the v1.9.0 music-import bug).
        val savedLoopMode: String = KV.get(KV_LOOP_MODE, "")
        state.loopMode = LxLoopMode.fromName(savedLoopMode)
        buildPlayer()
        // Try the background service in parallel; the first playAt
        // awaits the outcome (bounded) before locking the mode in.
        connectJob = lifecycleScope.launch { connectService() }
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    LxMusicPlayerScreen(
                        state = state,
                        onBack = { finish() },
                        onTogglePlay = { togglePlay() },
                        onNext = { playNextManual() },
                        onPrev = { playAt(state.index - 1) },
                        onSeek = { ms -> activePlayer()?.seekTo(ms) },
                        onToggleQueue = { state.showQueue = !state.showQueue },
                        onSelectQueueItem = { i ->
                            state.showQueue = false
                            playAt(i)
                        },
                        onEqModeSelected = { ensureEffects() },
                        onEqCustomLevelsChanged = { levels ->
                            state.eqCustomLevels = levels
                            ensureEffects()
                        },
                        onToggleLoopMode = { toggleLoopMode() },
                        onToggleFavorite = { toggleFavorite() },
                        onDownload = { downloadCurrent() },
                        onSleepTimerSelected = { minutes -> applySleepTimer(minutes) },
                    )
                }
            }
        }
        tickerJob = lifecycleScope.launch {
            while (true) {
                val target = activePlayer()
                if (target != null) {
                    state.positionMs = target.currentPosition.coerceAtLeast(0L)
                    val duration = target.duration
                    if (duration > 0) state.durationMs = duration
                }
                checkSleepTimer()
                delay(400)
            }
        }
        if (!tryAttach()) {
            playAt(state.index)
        }
    }

    /**
     * Attach entry (mini bar body tap): when the shared now-playing
     * record names a service-backed song that is part of the restored
     * queue, set the page up for that song WITHOUT starting playback
     * and bind to the live session instead. Any mismatch — no record,
     * a local record, the song is not in the queue — returns false and
     * init falls back to the normal fresh start, so a stale bar can
     * never produce a dead page.
     */
    private fun tryAttach(): Boolean {
        if (intent?.getBooleanExtra(EXTRA_ATTACH, false) != true) return false
        val np = NowPlayingState.current() ?: return false
        if (!np.serviceBacked) return false
        val idx = state.queue.indexOfFirst {
            NowPlayingState.keyOf(it) == np.trackKey
        }
        if (idx < 0) return false
        val track = state.queue[idx]
        state.index = idx
        lastIndex = idx
        state.sourceLabel = sourceLabelFor(track)
        state.errorText = null
        state.lyricLines = emptyList()
        state.playing = np.isPlaying
        state.buffering = false
        state.isFavorite = LxFavorites.isFavorite(track)
        updateCoverColor(track)
        loadLyrics(track, idx)
        attachJob = lifecycleScope.launch { bindAttachedSession(np) }
        return true
    }

    /**
     * Waits (bounded) for the service connection, locks the session to
     * SERVICE and adopts the controller's live state — position and
     * duration follow through the ticker, which reads the same
     * controller. If the connection failed, or the service is not
     * actually playing the record's song (checked by MediaItem
     * mediaId), the attach is abandoned and the song starts fresh
     * through the normal path.
     */
    private suspend fun bindAttachedSession(np: NowPlayingState.Info) {
        val job = connectJob
        if (job != null && !serviceDegraded) {
            withTimeoutOrNull(SERVICE_CONNECT_WAIT_MS) { job.join() }
        }
        val mc = controller
        if (serviceDegraded || mc == null || !mc.isConnected()) {
            playbackMode = PlaybackMode.LOCAL
            NowPlayingState.localControls = localControlsImpl
            playAt(state.index)
            return
        }
        playbackMode = PlaybackMode.SERVICE
        NowPlayingState.localControls = null
        val currentItem = try {
            mc.currentMediaItem
        } catch (t: Throwable) {
            null
        }
        if (currentItem == null || currentItem.mediaId != np.trackKey) {
            playAt(state.index)
            return
        }
        mc.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        state.playing = mc.isPlaying
        state.buffering = mc.playbackState == Player.STATE_BUFFERING
        ensureEffects()
    }

    /**
     * Shared HTTP data source factory: py spiders may answer
     * playerContent with required headers (Referer/User-Agent), which
     * are applied per track through the factory's default request
     * properties before each setMediaItem. This is the same factory
     * ExoPlayer would build internally, so LX tracks are unaffected.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)

    /** Headers of the currently resolved py track; empty for LX tracks. */
    private var currentHeaders: Map<String, String> = emptyMap()

    private fun buildPlayer() {
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
        exo.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        exo.addListener(playerListener)
        player = exo
    }

    private fun playAt(target: Int) {
        val queue = state.queue
        if (queue.isEmpty()) return
        val index = ((target % queue.size) + queue.size) % queue.size
        state.index = index
        lastIndex = index
        val track = queue[index]
        state.sourceLabel = sourceLabelFor(track)
        state.errorText = null
        state.lyricLines = emptyList()
        state.positionMs = 0L
        state.durationMs = 0L
        state.buffering = true
        state.currentUrl = null
        state.qualityText = ""
        state.downloadStatus = LxDownloadStatus.IDLE
        state.downloadPercent = -1
        state.isFavorite = LxFavorites.isFavorite(track)
        updateCoverColor(track)
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            try {
                val url: String
                if (track.isPy) {
                    // Py spider track: resolve through the shared spider
                    // runtime (detail+playerContent, lazily per track) and
                    // carry the spider's headers into the HTTP factory.
                    val resolved = PyMusicBridge.resolve(
                        this@LxMusicPlayerActivity,
                        pyDefFor(track),
                        track,
                    )
                    url = resolved.url
                    currentHeaders = resolved.headers
                    if (state.index == index) {
                        state.currentUrl = url
                        state.qualityText = ""
                    }
                } else {
                    currentHeaders = emptyMap()
                    url = LxBridge.getMusicUrl(track)
                    if (state.index == index) {
                        state.currentUrl = url
                        state.qualityText = LxBridge.lastResolvedQuality()?.uppercase() ?: ""
                    }
                }
                startPlayback(track, url)
                ensureEffects()
            } catch (e: PyMusicBridge.PyParseRequiredException) {
                state.buffering = false
                val message = getString(R.string.music_py_parse_unsupported)
                state.errorText = message
                Toast.makeText(this@LxMusicPlayerActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                state.buffering = false
                val message = e.message ?: getString(R.string.music_resolve_failed)
                state.errorText = message
                Toast.makeText(this@LxMusicPlayerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
        loadLyrics(track, index)
    }

    /**
     * Lyrics load in parallel with playback and fill in when ready.
     * Platform tracks use their own platform lyric; py spider tracks
     * provide none, so LyricMatcher looks the song up on NetEase/QQ
     * and reuses those lyric endpoints (v1.13.3). A miss keeps the
     * screen's no-lyrics text and never affects playback. Shared by
     * the fresh-start path and the mini-bar attach path.
     */
    private fun loadLyrics(track: MusicTrack, index: Int) {
        lyricJob?.cancel()
        lyricJob = lifecycleScope.launch {
            val raw = if (track.isPy) {
                LyricMatcher.matchLyric(track)
            } else {
                MusicApi.lyric(track)
            }
            if (state.index != index) return@launch
            if (!raw.isNullOrBlank()) {
                state.lyricLines = MusicLrc.parse(raw)
            }
        }
    }

    /**
     * Display name of [track]'s source for the player page chip: the py
     * registry's display name for spider tracks (never the raw
     * "py:<slug>" id; the generic py label when the source was deleted),
     * the platform name for LX tracks.
     */
    private fun sourceLabelFor(track: MusicTrack): String {
        if (track.isPy) {
            return PyMusicSources.findDef(this, track.pySourceId)?.name
                ?: getString(R.string.music_py_fallback)
        }
        return when (track.source) {
            "kg" -> getString(R.string.music_source_kg)
            "kw" -> getString(R.string.music_source_kw)
            "tx" -> getString(R.string.music_source_tx)
            "wy" -> getString(R.string.music_source_wy)
            "mg" -> getString(R.string.music_source_mg)
            else -> track.source
        }
    }

    /** The registered py source definition a py track belongs to. */
    private fun pyDefFor(track: MusicTrack): PyMusicSources.PyDef {
        return PyMusicSources.findDef(this, track.pySourceId)
            ?: throw IllegalStateException(getString(R.string.music_py_source_missing))
    }

    // ------------------------------------------------- service playback

    /**
     * Adopts the shared MediaController to [LxPlaybackService] from
     * MusicServiceConnection (the music page warms the connection up;
     * this call starts it when nobody has). Any failure — exception or
     * the connect timeout — degrades the session to the local player
     * via [degradeToLocal]; this function never throws.
     *
     * A retry attempt ([isRetry], from the degraded session's retry
     * loop) never degrades again on failure — it only logs — and on
     * success it does not switch playback mid-song: it sets
     * [serviceReadyForNext] and the next track start takes the service
     * over in awaitActivePlayer.
     */
    private suspend fun connectService(isRetry: Boolean = false) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val current = controller
            if (current != null && current.isConnected()) {
                if (isRetry) {
                    serviceReadyForNext = true
                }
                return
            }
            MusicCrashLog.log(this, "player: connect start (retry=$isRetry)")
            val mc = MusicServiceConnection.connectedController()
                ?: run {
                    MusicServiceConnection.ensureConnecting(this)
                    MusicServiceConnection.awaitConnected(
                        SERVICE_CONNECT_TIMEOUT_MS,
                    )
                }
            if (mc == null) {
                MusicCrashLog.log(
                    this,
                    "player: connect timeout after " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms " +
                        "(retry=$isRetry)",
                )
                if (!isRetry) {
                    degradeToLocal("connect-timeout")
                }
                return
            }
            controller = mc
            controllerIsShared = true
            mc.addListener(playerListener)
            mc.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            MusicCrashLog.log(
                this,
                "player: connect ok in " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "(retry=$isRetry)",
            )
            if (isRetry) {
                serviceReadyForNext = true
            }
        } catch (t: Throwable) {
            MusicCrashLog.log(
                this,
                "player: connect exception ${t.javaClass.simpleName} " +
                    "(retry=$isRetry)",
            )
            if (!isRetry) {
                degradeToLocal("connect-exception")
            }
        }
    }

    /**
     * Falls back to the in-Activity player: the mini bar reaches it
     * through the local controls, and a service-backed record of the
     * song this page is showing becomes a local one (that conversion
     * self-guards on the record still being service-backed, so a
     * repeated degrade does not republish it). Since v1.15.1 the
     * fallback is not permanent: [startServiceRetryLoop] keeps trying
     * to reconnect while the page lives, and a success takes over
     * from the next track. [reason] lands in the diagnostic log.
     */
    private fun degradeToLocal(reason: String) {
        MusicCrashLog.log(this, "player: degrade to local ($reason)")
        serviceDegraded = true
        if (playbackMode == PlaybackMode.SERVICE) {
            playbackMode = PlaybackMode.LOCAL
        }
        val mc = controller
        controller = null
        serviceSessionId = 0
        if (mc != null) {
            try {
                mc.removeListener(playerListener)
            } catch (t: Throwable) {
                // already gone; the local player is unaffected
            }
            // A shared controller is never released here: it belongs
            // to MusicServiceConnection (and the retry loop below may
            // adopt it again). An Activity-owned one would be.
            if (!controllerIsShared) {
                try {
                    mc.release()
                } catch (t: Throwable) {
                    // already gone; the local player is unaffected
                }
            }
        }
        controllerIsShared = false
        // The session is local from here on: the mini bar reaches it
        // through the local controls, and a service-backed record of
        // the song this page is showing becomes a local one.
        NowPlayingState.localControls = localControlsImpl
        val current = state.current
        val info = NowPlayingState.current()
        if (current != null && info != null && info.serviceBacked &&
            info.trackKey == NowPlayingState.keyOf(current)
        ) {
            NowPlayingState.publish(
                info.copy(serviceBacked = false, isPlaying = state.playing),
            )
        }
        startServiceRetryLoop()
    }

    /**
     * Bounded in-session reconnection (v1.15.1): every
     * [SERVICE_RETRY_INTERVAL_MS], at most [SERVICE_RETRY_MAX_ATTEMPTS]
     * times, while the page lives and the session has not locked onto
     * the service. A success only sets [serviceReadyForNext]; the loop
     * ends on success, on the attempt cap, or with the Activity.
     */
    private fun startServiceRetryLoop() {
        if (serviceRetryJob?.isActive == true) return
        serviceRetryJob = lifecycleScope.launch {
            var attempts = 0
            while (attempts < SERVICE_RETRY_MAX_ATTEMPTS &&
                playbackMode != PlaybackMode.SERVICE
            ) {
                delay(SERVICE_RETRY_INTERVAL_MS)
                if (playbackMode == PlaybackMode.SERVICE) break
                attempts++
                MusicCrashLog.log(
                    this@LxMusicPlayerActivity,
                    "player: connect retry $attempts/$SERVICE_RETRY_MAX_ATTEMPTS",
                )
                connectService(isRetry = true)
                if (serviceReadyForNext) {
                    MusicCrashLog.log(
                        this@LxMusicPlayerActivity,
                        "player: connect retry ok, service from next track",
                    )
                    break
                }
            }
        }
    }

    /**
     * Explicitly starts [LxPlaybackService] as a foreground service
     * (v1.15.1), once per Activity, on the first hand-off to the
     * service: on Honor/MagicOS a started foreground service is much
     * harder for the system to throttle than a merely bound one. Only
     * called when the service is about to receive a track, so the
     * service can promote itself with its media notification right
     * away. Failures are logged and ignored.
     */
    private fun startServiceForegroundOnce() {
        if (foregroundServiceStarted) return
        foregroundServiceStarted = true
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, LxPlaybackService::class.java),
            )
            MusicCrashLog.log(this, "player: startForegroundService ok")
        } catch (t: Throwable) {
            MusicCrashLog.log(
                this,
                "player: startForegroundService failed " +
                    t.javaClass.simpleName,
            )
        }
    }

    /**
     * The player the UI controls right now: the service controller once
     * the session locked onto it (and it is still connected), otherwise
     * the local player.
     */
    private fun activePlayer(): Player? {
        if (playbackMode == PlaybackMode.SERVICE) {
            val mc = controller
            if (mc != null && mc.isConnected()) {
                return mc
            }
        }
        return player
    }

    /**
     * Resolves the playback target for a start, locking the session mode
     * on the first call: waits (bounded) for the pending connection
     * attempt, then picks the service when it connected, the local
     * player otherwise. Later calls re-check the controller's
     * connection and degrade if it dropped.
     */
    private suspend fun awaitActivePlayer(): Player? {
        // v1.15.1: a retry reconnected the service after this session
        // had degraded to local. The song the local player is playing
        // is not interrupted — this function only runs at a track
        // start — but from this start on the service takes over, and
        // the record startPlayback is about to publish goes out as
        // service-backed again through the normal publish path.
        if (serviceReadyForNext) {
            serviceReadyForNext = false
            val mc = controller
            if (mc != null && mc.isConnected()) {
                playbackMode = PlaybackMode.SERVICE
                serviceDegraded = false
                NowPlayingState.localControls = null
                mc.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                MusicCrashLog.log(
                    this,
                    "player: lock SERVICE (retry, from next track)",
                )
                return mc
            }
        }
        when (playbackMode) {
            PlaybackMode.SERVICE -> {
                val mc = controller
                if (mc != null && mc.isConnected()) {
                    return mc
                }
                degradeToLocal("controller-dropped")
                return player
            }

            PlaybackMode.LOCAL -> return player
            PlaybackMode.UNKNOWN -> Unit
        }
        val job = connectJob
        if (job != null && !serviceDegraded) {
            withTimeoutOrNull(SERVICE_CONNECT_WAIT_MS) { job.join() }
        }
        val mc = controller
        playbackMode = if (!serviceDegraded && mc != null && mc.isConnected()) {
            // Apply the loop mode again: a toggle that happened while
            // the connection was still pending only reached the local
            // player (connectService applied the connect-time value).
            mc.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            NowPlayingState.localControls = null
            PlaybackMode.SERVICE
        } else {
            NowPlayingState.localControls = localControlsImpl
            PlaybackMode.LOCAL
        }
        MusicCrashLog.log(this, "player: mode locked $playbackMode")
        return if (playbackMode == PlaybackMode.SERVICE) mc else player
    }

    /**
     * Hands one resolved track to the locked playback target. The
     * MediaItem (NowPlayingState.mediaItemFor) carries the song key as
     * mediaId plus title/artist/artwork metadata, so the service's
     * media notification and lock screen show the song, not just a URL.
     * In service mode the track's HTTP headers are pushed first (best
     * effort); if the controller call itself fails, the session
     * degrades and the local player takes this very track over.
     */
    private suspend fun startPlayback(track: MusicTrack, url: String) {
        val item = NowPlayingState.mediaItemFor(track, url)
        val target = awaitActivePlayer()
        // Publish the mini-bar record before the hand-off: a service
        // media transition fires inside setMediaItem and keeps these
        // enrichment fields (queue, label, canNext).
        NowPlayingState.publish(
            NowPlayingState.Info(
                trackKey = NowPlayingState.keyOf(track),
                title = track.name,
                singer = track.singer,
                img = track.img,
                sourceLabel = state.sourceLabel,
                isPlaying = true,
                canNext = canNextFromHere(),
                serviceBacked = target is MediaController,
                queueJson = MusicTrack.listToJson(state.queue),
                queueIndex = state.index,
            ),
        )
        if (target is MediaController) {
            // A session that switched to the service after a retry
            // (v1.15.1) may still have the previous song on the local
            // player: stop it so the two never overlap. In a session
            // that was on the service from the start the local player
            // is idle and this is a no-op.
            try {
                player?.stop()
            } catch (t: Throwable) {
                // already stopped; the hand-off continues either way
            }
            startServiceForegroundOnce()
            sendHeadersToService(target, currentHeaders)
            try {
                target.setMediaItem(item)
                target.prepare()
                target.play()
                return
            } catch (t: Throwable) {
                degradeToLocal("controller-call-failed")
            }
        }
        val exo = player ?: return
        httpDataSourceFactory.setDefaultRequestProperties(currentHeaders)
        exo.setMediaItem(item)
        exo.prepare()
        exo.play()
    }

    /** Manual-next availability of the current queue position. */
    private fun canNextFromHere(): Boolean {
        val size = state.queue.size
        if (size <= 1) return false
        return !(state.loopMode == LxLoopMode.ORDER && state.index >= size - 1)
    }

    /**
     * Pushes the current track's HTTP headers to the playback service
     * (see LxPlaybackService.ACTION_SET_HEADERS). Bounded and best
     * effort: a failed hand-off never blocks playback.
     */
    private suspend fun sendHeadersToService(
        mc: MediaController,
        headers: Map<String, String>,
    ) {
        try {
            val args = Bundle()
            args.putStringArrayList(
                LxPlaybackService.EXTRA_HEADER_KEYS,
                ArrayList(headers.keys),
            )
            for ((key, value) in headers) {
                args.putString(LxPlaybackService.EXTRA_HEADER_VALUE_PREFIX + key, value)
            }
            val future = mc.sendCustomCommand(
                SessionCommand(LxPlaybackService.ACTION_SET_HEADERS, Bundle()),
                args,
            )
            withContext(Dispatchers.IO) {
                future.get(SERVICE_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // best effort only
        }
    }

    /**
     * Fetches the service player's audio session id by custom command
     * (MediaController exposes none) and re-runs [ensureEffects] once
     * it lands. Failure leaves the EQ silently off in service mode.
     */
    private fun fetchServiceSessionId() {
        val mc = controller ?: return
        if (sessionIdFetchJob?.isActive == true) return
        sessionIdFetchJob = lifecycleScope.launch {
            try {
                val future = mc.sendCustomCommand(
                    SessionCommand(
                        LxPlaybackService.ACTION_GET_AUDIO_SESSION_ID,
                        Bundle(),
                    ),
                    Bundle(),
                )
                val result = withContext(Dispatchers.IO) {
                    future.get(SERVICE_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                val id = result.extras.getInt(
                    LxPlaybackService.EXTRA_AUDIO_SESSION_ID,
                    0,
                )
                if (id > 0 && id != serviceSessionId) {
                    serviceSessionId = id
                    ensureEffects()
                }
            } catch (t: Throwable) {
                // EQ stays off in service mode; playback is unaffected.
            }
        }
    }

    // ------------------------------------------------------ sleep timer

    /** Arms (minutes > 0) or cancels (minutes <= 0) the sleep timer. */
    private fun applySleepTimer(minutes: Int) {
        if (minutes <= 0) {
            state.sleepTimerEndAtMs = 0L
            state.sleepMinutesLeft = 0
        } else {
            state.sleepTimerEndAtMs = System.currentTimeMillis() + minutes * 60_000L
            state.sleepMinutesLeft = minutes
        }
    }

    /**
     * Ticker hook: refreshes the remaining-minutes display and, at the
     * deadline, pauses whichever player is active and says so. The
     * deadline lives in state, so it survives track changes; the ticker
     * dies with the Activity, which cancels the timer with the page.
     */
    private fun checkSleepTimer() {
        val endAt = state.sleepTimerEndAtMs
        if (endAt <= 0L) return
        val remainingMs = endAt - System.currentTimeMillis()
        if (remainingMs > 0L) {
            state.sleepMinutesLeft = ((remainingMs + 59_999L) / 60_000L).toInt()
            return
        }
        state.sleepTimerEndAtMs = 0L
        state.sleepMinutesLeft = 0
        try {
            activePlayer()?.pause()
        } catch (t: Throwable) {
            // the deadline still counts as fired
        }
        Toast.makeText(this, R.string.music_sleep_fired, Toast.LENGTH_LONG).show()
    }

    private fun togglePlay() {
        val target = activePlayer() ?: return
        if (target.isPlaying) {
            target.pause()
        } else {
            target.play()
        }
    }

    // ------------------------------------------------------------ loop mode

    /**
     * Automatic advance when a track ends. Single-loop never lands here
     * (the player's REPEAT_MODE_ONE loops in place); sequential playback
     * stops at the end of the queue instead of wrapping.
     */
    private fun advanceAuto() {
        when (state.loopMode) {
            LxLoopMode.SINGLE -> playAt(state.index)
            LxLoopMode.ORDER -> {
                if (state.index < state.queue.size - 1) {
                    playAt(state.index + 1)
                }
            }

            LxLoopMode.SHUFFLE -> playAt(randomIndex())
            LxLoopMode.LIST -> playAt(state.index + 1)
        }
    }

    /** Manual "next" button: random pick in shuffle mode, else next track. */
    private fun playNextManual() {
        if (state.loopMode == LxLoopMode.SHUFFLE) {
            playAt(randomIndex())
        } else {
            playAt(state.index + 1)
        }
    }

    private fun randomIndex(): Int {
        val size = state.queue.size
        if (size <= 1) return state.index
        var next = Random.nextInt(size)
        while (next == state.index) {
            next = Random.nextInt(size)
        }
        return next
    }

    private fun toggleLoopMode() {
        val mode = state.loopMode.next()
        state.loopMode = mode
        KV.put(KV_LOOP_MODE, mode.name)
        activePlayer()?.repeatMode = if (mode == LxLoopMode.SINGLE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        Toast.makeText(this, getString(mode.labelRes), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ favorites

    private fun toggleFavorite() {
        val track = state.current ?: return
        state.isFavorite = LxFavorites.toggle(track)
    }

    // ------------------------------------------------------------ cover color

    /**
     * Samples the current cover's average colour (darkened) as the page
     * background. Coil fetches a tiny copy, so the pixel walk is trivial;
     * any failure leaves state.bgColor null and the screen uses its
     * fallback colour.
     */
    private fun updateCoverColor(track: MusicTrack) {
        colorJob?.cancel()
        val url = track.img
        if (url.isNullOrBlank()) {
            state.bgColor = null
            return
        }
        colorJob = lifecycleScope.launch {
            val color = extractAverageColor(url)
            if (state.current == track) {
                state.bgColor = color
            }
        }
    }

    private suspend fun extractAverageColor(url: String): Color? {
        return try {
            val request = ImageRequest.Builder(this)
                .data(url)
                .size(48, 48)
                .build()
            val result = SingletonImageLoader.get(this).execute(request)
            val raw = (result as? SuccessResult)?.image?.toBitmap() ?: return null
            // Coil 3 has no allowHardware(false) (that was Coil 2). A
            // HARDWARE-config bitmap cannot be read back with getPixels,
            // so copy it into a software config first.
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                raw.config == Bitmap.Config.HARDWARE
            ) {
                raw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                raw
            }
            averageDarkenedColor(bitmap)
        } catch (t: Throwable) {
            null
        }
    }

    private fun averageDarkenedColor(bitmap: Bitmap): Color? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (pixel in pixels) {
            if (android.graphics.Color.alpha(pixel) < 16) continue
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
        if (count == 0L) return null
        // Halving the channels lands most covers in a deep, readable range
        // for white text while keeping the cover's hue recognisable.
        val factor = 0.5f
        return Color(
            red = (red / count * factor).toInt().coerceIn(0, 255),
            green = (green / count * factor).toInt().coerceIn(0, 255),
            blue = (blue / count * factor).toInt().coerceIn(0, 255),
        )
    }

    // ------------------------------------------------------------ download

    /**
     * Downloads the current track's audio file into the device music
     * library. Uses the already-resolved URL when available; otherwise
     * resolves first through the same LX flow as playback.
     */
    private fun downloadCurrent() {
        val track = state.current ?: return
        if (state.downloadStatus == LxDownloadStatus.DOWNLOADING) return
        state.downloadStatus = LxDownloadStatus.DOWNLOADING
        state.downloadPercent = -1
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            try {
                val cachedUrl = state.currentUrl
                val url: String
                val headers: Map<String, String>
                when {
                    track.isPy && cachedUrl != null -> {
                        url = cachedUrl
                        headers = currentHeaders
                    }

                    track.isPy -> {
                        val resolved = PyMusicBridge.resolve(
                            this@LxMusicPlayerActivity,
                            pyDefFor(track),
                            track,
                        )
                        url = resolved.url
                        headers = resolved.headers
                    }

                    else -> {
                        url = cachedUrl ?: LxBridge.getMusicUrl(track)
                        headers = emptyMap()
                    }
                }
                val savedTo = withContext(Dispatchers.IO) {
                    downloadToLibrary(track, url, headers) { percent ->
                        state.downloadPercent = percent
                    }
                }
                if (state.current == track) {
                    state.downloadStatus = LxDownloadStatus.DONE
                    state.downloadPercent = 100
                }
                Toast.makeText(
                    this@LxMusicPlayerActivity,
                    getString(R.string.music_download_done_toast, savedTo),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: PyMusicBridge.PyParseRequiredException) {
                if (state.current == track) {
                    state.downloadStatus = LxDownloadStatus.IDLE
                    state.downloadPercent = -1
                }
                Toast.makeText(
                    this@LxMusicPlayerActivity,
                    getString(R.string.music_py_parse_unsupported),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                if (state.current == track) {
                    state.downloadStatus = LxDownloadStatus.IDLE
                    state.downloadPercent = -1
                }
                Toast.makeText(
                    this@LxMusicPlayerActivity,
                    getString(R.string.music_download_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /**
     * Streams [url] to the music library and returns a display path.
     * API 29+ goes through MediaStore (Music/AVBox, no permission needed);
     * older versions write into the app's external Music dir. Blocking IO:
     * call on a background dispatcher.
     */
    private fun downloadToLibrary(
        track: MusicTrack,
        url: String,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit,
    ): String {
        val extension = extensionOf(url)
        val fileName = cleanFileName(track, extension)
        val client = HttpClients.defaultClient ?: OkHttpClient()
        val requestBuilder = Request.Builder().url(url)
        for ((key, value) in headers) {
            requestBuilder.header(key, value)
        }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP " + response.code)
            }
            val body = response.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            val sink: OutputStream
            val displayPath: String
            var mediaUri: Uri? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeTypeOf(extension))
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AVBox")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: throw IllegalStateException("MediaStore insert failed")
                mediaUri = uri
                sink = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("MediaStore open failed")
                displayPath = "Music/AVBox/" + fileName
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                sink = FileOutputStream(file)
                displayPath = file.absolutePath
            }
            try {
                sink.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var readTotal = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            if (total > 0) {
                                onProgress((readTotal * 100 / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                }
                val uri = mediaUri
                if (uri != null) {
                    val done = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, done, null, null)
                }
            } catch (e: Exception) {
                val uri = mediaUri
                if (uri != null) {
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (t: Throwable) {
                        // best effort cleanup of the pending entry
                    }
                }
                throw e
            }
            return displayPath
        }
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        val known = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
        return if (ext in known) ext else "mp3"
    }

    private fun mimeTypeOf(extension: String): String {
        return when (extension) {
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/x-wav"
            else -> "audio/mpeg"
        }
    }

    private fun cleanFileName(track: MusicTrack, extension: String): String {
        val raw = (track.singer + " - " + track.name).trim()
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\x00-\\x1F]"), "_")
            .trim()
            .take(120)
        val base = cleaned.ifEmpty { "music" }
        return base + "." + extension
    }

    /**
     * The audio session the EQ should attach to. Local mode reads the
     * ExoPlayer's own id; service mode uses the id fetched from the
     * playback service by custom command (kicking the fetch off when
     * it is still unknown and reporting 0 until it lands, so
     * [ensureEffects] simply retries on the next callback). AudioFX
     * sessions are system-wide, so effects created here in the Activity
     * process do process the service player's output.
     */
    private fun currentAudioSessionId(): Int {
        if (playbackMode == PlaybackMode.SERVICE) {
            if (serviceSessionId <= 0) {
                fetchServiceSessionId()
            }
            return serviceSessionId
        }
        val exo = player ?: return 0
        return try {
            exo.audioSessionId
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Creates the AudioFX effects for the player's current audio session
     * when they are missing or the session changed, then applies the
     * persisted EQ mode. The session id is only assigned once the player
     * has prepared an audio track, so this is called again from the
     * playback callbacks; it is cheap and idempotent. Every step is
     * guarded: devices without audiofx simply keep playing without
     * effects.
     */
    private fun ensureEffects() {
        val sessionId = currentAudioSessionId()
        if (sessionId <= 0) return
        if (sessionId != effectsSessionId) {
            releaseEffects()
            effectsSessionId = sessionId
            try {
                val eq = Equalizer(0, sessionId)
                equalizer = eq
                val presetCount = eq.numberOfPresets.toInt()
                state.eqPresets = (0 until presetCount).map { index ->
                    try {
                        eq.getPresetName(index.toShort())
                            .ifBlank { getString(R.string.music_eq_preset_name, index + 1) }
                    } catch (t: Throwable) {
                        getString(R.string.music_eq_preset_name, index + 1)
                    }
                }
                // Publish the band layout for the appearance sheet's
                // custom EQ sliders, plus the persisted custom levels.
                val bandRange = eq.bandLevelRange
                state.eqBandCount = eq.numberOfBands.toInt()
                state.eqBandMinMb = bandRange[0].toInt()
                state.eqBandMaxMb = bandRange[1].toInt()
                state.eqCustomLevels = MusicAppearance.readEqCustomLevels()
            } catch (t: Throwable) {
                equalizer = null
            }
            try {
                bassBoost = BassBoost(0, sessionId)
            } catch (t: Throwable) {
                bassBoost = null
            }
            try {
                virtualizer = Virtualizer(0, sessionId)
            } catch (t: Throwable) {
                virtualizer = null
            }
        }
        applyEqMode()
    }

    /** Applies MusicAppearance.eqMode to the live effects, disabling the rest. */
    private fun applyEqMode() {
        try {
            equalizer?.enabled = false
        } catch (t: Throwable) {
        }
        try {
            bassBoost?.enabled = false
        } catch (t: Throwable) {
        }
        try {
            virtualizer?.enabled = false
        } catch (t: Throwable) {
        }
        val mode = MusicAppearance.eqMode
        try {
            when {
                mode == MusicAppearance.EQ_BASS -> {
                    bassBoost?.let { boost ->
                        boost.setStrength(EQ_EFFECT_STRENGTH)
                        boost.enabled = true
                    }
                }

                mode == MusicAppearance.EQ_SURROUND -> {
                    virtualizer?.let { virt ->
                        if (virt.strengthSupported) {
                            virt.setStrength(EQ_EFFECT_STRENGTH)
                        }
                        virt.enabled = true
                    }
                }

                mode == MusicAppearance.EQ_CUSTOM -> applyCustomLevels()

                mode.startsWith(MusicAppearance.EQ_CURVE_PREFIX) -> applyAnchorCurve(mode)

                else -> {
                    val presetIndex = MusicAppearance.eqPresetIndex(mode)
                    val eq = equalizer
                    if (presetIndex != null && eq != null &&
                        presetIndex in 0 until eq.numberOfPresets.toInt()
                    ) {
                        eq.usePreset(presetIndex.toShort())
                        eq.enabled = true
                    }
                }
            }
        } catch (t: Throwable) {
            // audiofx rejected the change: stay silent, playback continues.
        }
    }

    /** Applies one of the built-in anchor curves (curve:bass/vocal/bright). */
    private fun applyAnchorCurve(mode: String) {
        val eq = equalizer ?: return
        val anchors = when (mode) {
            MusicAppearance.EQ_CURVE_BASS -> CURVE_BASS_ANCHORS
            MusicAppearance.EQ_CURVE_VOCAL -> CURVE_VOCAL_ANCHORS
            MusicAppearance.EQ_CURVE_BRIGHT -> CURVE_BRIGHT_ANCHORS
            else -> return
        }
        applyBandWeights(eq, anchors)
    }

    /**
     * Interpolates the 5-point [anchors] onto the equalizer's bands and
     * pushes each level (weight × the range's positive end, clamped to
     * the band level range). Every band is set in its own try/catch: a
     * rejected band never blocks the others.
     */
    private fun applyBandWeights(eq: Equalizer, anchors: FloatArray) {
        try {
            val bandCount = eq.numberOfBands.toInt()
            if (bandCount <= 0) return
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            for (band in 0 until bandCount) {
                val weight = anchorWeight(anchors, band, bandCount)
                val level = (weight * maxMb).toInt().coerceIn(minMb, maxMb)
                try {
                    eq.setBandLevel(band.toShort(), level.toShort())
                } catch (t: Throwable) {
                    // keep applying the remaining bands
                }
            }
            eq.enabled = true
        } catch (t: Throwable) {
            // no usable equalizer: silent, playback continues.
        }
    }

    /** Linear interpolation of the anchor curve at [band] of [bandCount]. */
    private fun anchorWeight(anchors: FloatArray, band: Int, bandCount: Int): Float {
        if (anchors.isEmpty()) return 0f
        if (bandCount <= 1 || anchors.size == 1) return anchors[0]
        val pos = band.toFloat() * (anchors.size - 1) / (bandCount - 1)
        val low = pos.toInt().coerceIn(0, anchors.size - 1)
        val high = (low + 1).coerceAtMost(anchors.size - 1)
        val frac = pos - low
        return anchors[low] + (anchors[high] - anchors[low]) * frac
    }

    /** Applies the user's stored per-band levels (custom EQ mode). */
    private fun applyCustomLevels() {
        val eq = equalizer ?: return
        try {
            val bandCount = eq.numberOfBands.toInt()
            if (bandCount <= 0) return
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            val stored = MusicAppearance.readEqCustomLevels()
            for (band in 0 until bandCount) {
                // Bands beyond the stored list stay flat.
                val level = (stored.getOrNull(band) ?: 0).coerceIn(minMb, maxMb)
                try {
                    eq.setBandLevel(band.toShort(), level.toShort())
                } catch (t: Throwable) {
                    // keep applying the remaining bands
                }
            }
            eq.enabled = true
        } catch (t: Throwable) {
            // no usable equalizer: silent, playback continues.
        }
    }

    private fun releaseEffects() {
        try {
            equalizer?.release()
        } catch (t: Throwable) {
        }
        try {
            bassBoost?.release()
        } catch (t: Throwable) {
        }
        try {
            virtualizer?.release()
        } catch (t: Throwable) {
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        effectsSessionId = 0
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        resolveJob?.cancel()
        lyricJob?.cancel()
        colorJob?.cancel()
        downloadJob?.cancel()
        connectJob?.cancel()
        attachJob?.cancel()
        sessionIdFetchJob?.cancel()
        serviceRetryJob?.cancel()
        // Mini bar bookkeeping: unregister this instance's local
        // controls, and clear the record when it was local-backed —
        // local playback dies with this page. A service-backed record
        // stays: the service keeps playing and maintains it.
        if (NowPlayingState.localControls === localControlsImpl) {
            NowPlayingState.localControls = null
        }
        val npInfo = NowPlayingState.current()
        if (npInfo != null && !npInfo.serviceBacked) {
            NowPlayingState.clear()
        }
        // The sleep timer is bound to this page: the ticker that fires
        // it is gone, so the deadline can never trigger after destroy.
        state.sleepTimerEndAtMs = 0L
        state.sleepMinutesLeft = 0
        releaseEffects()
        // Detach from the controller: the service (and its playback)
        // outlives the page, which is the point of the service mode.
        // The shared controller is only un-listened — releasing it
        // would kill the process-wide warm connection for everyone;
        // an Activity-owned controller would be released instead.
        // The local player is ours and is released.
        val mc = controller
        controller = null
        if (mc != null) {
            try {
                mc.removeListener(playerListener)
            } catch (t: Throwable) {
                // already gone
            }
            if (!controllerIsShared) {
                try {
                    mc.release()
                } catch (t: Throwable) {
                    // already gone
                }
            }
        }
        controllerIsShared = false
        player?.release()
        player = null
        super.onDestroy()
    }
}
