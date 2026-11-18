package com.github.avbox.osc.ui.music

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.TransactionTooLargeException
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxFavorites
import com.github.avbox.osc.music.LxPlayHistory
import com.github.avbox.osc.music.LxPlaybackService
import com.github.avbox.osc.music.LyricMatcher
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicCrashLog
import com.github.avbox.osc.music.MusicPlayPrefs
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
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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
 * Playback target: the player first tries the background
 * [LxPlaybackService]; if binding fails, times out or drops, the
 * session degrades to the in-Activity ExoPlayer (the pre-v1.13.3
 * behaviour), so the worst case is exactly the old player. All
 * queue/loop/progress logic talks to the [Player] interface, which
 * the service's ExoPlayer also implements.
 *
 * Connection (v1.16.0): the service is reached through the
 * process-wide binding in MusicServiceConnection (the music page
 * warms it up before the user even taps a song) — a same-process
 * LocalBinder, because the media3 session route proved unusable
 * on the test device (its token resolution query returns nothing
 * for this app's own service; see LxPlaybackService). The bind
 * budget is 10 s, and a session
 * that degraded keeps retrying in the background — a late success
 * takes over from the next track instead of interrupting the song
 * the local player is playing.
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
         * How long binding the playback service may take before
         * degrading. 10 s since v1.15.1: the old 3 s budget lost the
         * cold-start race on the test device (QuickJS/Python init
         * competing), which is why the service never engaged there.
         */
        private const val SERVICE_CONNECT_TIMEOUT_MS = 10000L

        /** How long the first playAt waits for the connection attempt. */
        private const val SERVICE_CONNECT_WAIT_MS = 10000L

        /** Overall budget of one cross-source fallback hunt. */
        private const val FALLBACK_DEADLINE_MS = 40000L

        /** Rhythm-visualizer bar count (v1.20.0). */
        private const val VIZ_BARS = 28

        /** Delay between in-session service connection retries. */
        private const val SERVICE_RETRY_INTERVAL_MS = 5000L

        /** Max in-session retries after a connection-failure degrade. */
        private const val SERVICE_RETRY_MAX_ATTEMPTS = 6

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

        /** Largest queue JSON allowed to ride the start Intent. */
        private const val MAX_INTENT_QUEUE_CHARS = 200_000

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
            val intent = Intent(context, LxMusicPlayerActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
            // Giant queues (audiobook spiders with thousands of
            // episodes — 2,280 seen in the wild) overflow the ~1 MB
            // Binder transaction when the queue JSON rides the
            // Intent, and startActivity dies with
            // TransactionTooLargeException: the app just vanishes
            // on the tap (Torres, 悦听 audiobooks). The queue is
            // already remembered in lastQueueJson above and init
            // restores from it when the extra is absent, so only
            // small queues ride the Intent.
            if (queueJson.length <= MAX_INTENT_QUEUE_CHARS) {
                intent.putExtra(EXTRA_QUEUE, queueJson)
            }
            try {
                context.startActivity(intent)
            } catch (e: TransactionTooLargeException) {
                // Belt and braces: retry bare; init restores the
                // queue from lastQueueJson.
                intent.removeExtra(EXTRA_QUEUE)
                context.startActivity(intent)
            }
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

    /**
     * Cross-source fallback bookkeeping (v1.22.0): the sources
     * already tried for the song currently being rescued, keyed by
     * that song's name|singer so a fresh song starts with a clean
     * slate while retries of the same song keep hopping.
     */
    private val fallbackTried = mutableSetOf<String>()
    private var fallbackSongKey: String = ""

    /** Resume point to seek to once the current start is READY. */
    private var pendingResumeMs: Long = 0L
    private var lyricJob: Job? = null
    private var tickerJob: Job? = null
    private var colorJob: Job? = null
    private var downloadJob: Job? = null

    // Background-service playback. The bound service's player is just
    // another Player; playbackMode locks at the first start and moves
    // SERVICE -> LOCAL on degrade. Since v1.15.1 it can also move back
    // LOCAL -> SERVICE, but only at a track start after a retry
    // reconnected (see serviceReadyForNext), never mid-song.
    // The binding is the process-wide shared one owned by
    // MusicServiceConnection: this Activity adds/removes its listener
    // on the service's player but never unbinds or releases it.
    private var boundService: LxPlaybackService? = null
    private var servicePlayer: Player? = null
    private var connectJob: Job? = null
    private var attachJob: Job? = null
    private var playbackMode = PlaybackMode.UNKNOWN
    private var serviceDegraded = false

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

    /**
     * Notification / lock-screen next/previous for the service: the
     * queue lives here, so the service forwards those commands to
     * this handler while this Activity is the queue owner.
     */
    private val serviceNextPrevHandler = object : LxPlaybackService.NextPrevHandler {
        override fun onNext() {
            playNextManual()
        }

        override fun onPrevious() {
            playAt(state.index - 1)
        }
    }

    /**
     * Playback events shared by the local player and the service's
     * player: only the mode-locked target ever has media, so the
     * idle side stays silent.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            state.buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                ensureEffects()
                if (pendingResumeMs > 0L) {
                    val resumeAt = pendingResumeMs
                    pendingResumeMs = 0L
                    try {
                        activePlayer()?.seekTo(resumeAt)
                    } catch (t: Throwable) {
                        // Player went away mid-seek; the song just
                        // plays from the top.
                    }
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                MusicPlayPrefs.clearResume()
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
            // A stream that dies right after a successful resolve
            // gets the same cross-source rescue as a resolve failure
            // (v1.22.0); a successful rescue clears the error again.
            // Skipped while a fresh resolve is in flight: that job
            // owns the page (and runs its own rescue on failure),
            // and a stale error from the previous item must not
            // hijack the new song.
            if (resolveJob?.isActive == true) return
            val idx = state.index
            val cur = state.current
            if (cur != null) {
                lifecycleScope.launch {
                    startFallback(idx, cur)
                }
            }
        }
    }

    /**
     * Mini-bar controls for the local (degraded) mode, registered in
     * [NowPlayingState] while this Activity owns local playback. The
     * bar's service-mode buttons drive the bound service's player
     * directly instead; these only ever run on the main thread
     * (click handlers).
     */
    private val localControlsImpl = object : NowPlayingState.LocalControls {
        override fun playPause() {
            togglePlay()
        }

        override fun next() {
            playNextManual()
        }

        override fun prev() {
            if (state.index > 0) playAt(state.index - 1)
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

    // Rhythm visualizer (v1.20.0): an audiofx Visualizer on the
    // device output mix (session 0), feeding FFT bar levels into
    // the page state. It used to ride the playing player's own
    // audio session like the EQ, but on Torres's Honor that
    // session's capture stayed silent for songs started from a
    // list (v1.21.1/v1.21.2: bars rendered, always flat) while the
    // output mix carries whatever is actually audible, whichever
    // player or mode produced it. Purely decorative — every step
    // is guarded, and a device without visualizer support (or
    // without the microphone permission the Visualizer API
    // demands) simply shows no bars.
    private var audioVisualizer: Visualizer? = null
    private val vizBars = FloatArray(VIZ_BARS)

    private val vizPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensureVisualizer()
        }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {}

    override fun init() {
        MusicCrashLog.install(this)
        enableTransparentEdgeToEdge()
        PermissionHelper.requestNotificationIfNeeded(this)
        // The rhythm visualizer needs RECORD_AUDIO (the Visualizer
        // API requires it), so ask once when the player opens; the
        // same grant also covers the music page's voice search and
        // 听歌识曲. Denied → no bars, and nothing else changes.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            vizPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        var queue = MusicTrack.listFromJson(intent?.getStringExtra(EXTRA_QUEUE))
        var startIndex = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0
        // A launch with no queue extra (mini bar, notification /
        // 灵动岛 session activity, launcher relaunch) can only mean
        // "show me what's playing": init tries to attach it to the
        // live service session below instead of restarting the song.
        val queueLessLaunch = queue.isEmpty()
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
                        onRemoveQueueItem = { i -> removeQueueItem(i) },
                        onMoveQueueItem = { from, to -> moveQueueItem(from, to) },
                        onClearQueue = { clearQueue() },
                        onToggleFavorite = { toggleFavorite() },
                        onDownload = { downloadCurrent() },
                        onMuteToggle = { toggleMute() },
                        onSleepTimerSelected = { minutes -> applySleepTimer(minutes) },
                    )
                }
            }
        }
        tickerJob = lifecycleScope.launch {
            var resumeTick = 0
            while (true) {
                val target = activePlayer()
                if (target != null) {
                    state.positionMs = target.currentPosition.coerceAtLeast(0L)
                    val duration = target.duration
                    if (duration > 0) state.durationMs = duration
                }
                // The visualizer's setup calls race the audio
                // session: a song started from a list hands over to
                // the service player whose session id may not exist
                // yet at playAt time (or the playback target switches
                // afterwards), and nothing retried — the bars stayed
                // flat for the whole song (Torres, v1.21.1). The
                // check is idempotent and cheap, so the ticker keeps
                // it healed.
                ensureVisualizer()
                checkSleepTimer()
                // Resume-point saving (v1.23.0): throttled to every
                // ~5 s of beats, only while mid-song, so re-playing
                // the same song later can pick up where it stopped.
                if (MusicPlayPrefs.rememberProgress && state.playing) {
                    resumeTick++
                    if (resumeTick >= 12) {
                        resumeTick = 0
                        val cur = state.current
                        val pos = state.positionMs
                        if (cur != null && pos > 3_000L &&
                            (state.durationMs <= 0L || pos < state.durationMs - 5_000L)
                        ) {
                            MusicPlayPrefs.saveResume(NowPlayingState.keyOf(cur), pos)
                        }
                    }
                }
                delay(400)
            }
        }
        if (!tryAttach(queueLessLaunch)) {
            playAt(state.index)
        }
    }

    /**
     * Attach entry (mini bar body tap, or any queue-less launch):
     * when the shared now-playing record names a service-backed song
     * that is part of the restored queue, set the page up for that
     * song WITHOUT starting playback and bind to the live session
     * instead. Any mismatch — no record, a local record, the song is
     * not in the queue — returns false and init falls back to the
     * normal fresh start, so a stale entry can never produce a dead
     * page.
     *
     * The queue-less gate matters: the notification / 灵动岛
     * session-activity intent carries no extras at all, so it used
     * to miss the EXTRA_ATTACH check and fall into a fresh playAt
     * that raced the live service session — its resolve job was
     * cancelled by the session adoption and the page stranded on a
     * dead local player (00:00 / 00:00, inert buttons, a stray
     * "Job was cancelled" toast) while the service played on
     * (Torres's recording, 2026-11-17 22:25).
     */
    private fun tryAttach(queueLessLaunch: Boolean): Boolean {
        val explicit = intent?.getBooleanExtra(EXTRA_ATTACH, false) == true
        if (!explicit && !queueLessLaunch) return false
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
     * Waits (bounded) for the service binding, locks the session to
     * SERVICE and adopts the service player's live state — position
     * and duration follow through the ticker, which reads the same
     * player. If the binding failed, or the service is not actually
     * playing the record's song (checked by MediaItem mediaId), the
     * attach is abandoned and the song starts fresh through the
     * normal path.
     */
    private suspend fun bindAttachedSession(np: NowPlayingState.Info) {
        val job = connectJob
        if (job != null && !serviceDegraded) {
            withTimeoutOrNull(SERVICE_CONNECT_WAIT_MS) { job.join() }
        }
        val sp = servicePlayer
        if (serviceDegraded || sp == null) {
            playbackMode = PlaybackMode.LOCAL
            NowPlayingState.localControls = localControlsImpl
            playAt(state.index)
            return
        }
        playbackMode = PlaybackMode.SERVICE
        NowPlayingState.localControls = null
        val currentItem = try {
            sp.currentMediaItem
        } catch (t: Throwable) {
            null
        }
        if (currentItem == null || currentItem.mediaId != np.trackKey) {
            playAt(state.index)
            return
        }
        sp.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        state.playing = sp.isPlaying
        state.buffering = sp.playbackState == Player.STATE_BUFFERING
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
            MusicPlayPrefs.audioFocusPause,
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
        val songKey = track.name + "|" + track.singer
        if (songKey != fallbackSongKey) {
            fallbackSongKey = songKey
            fallbackTried.clear()
        }
        pendingResumeMs = if (MusicPlayPrefs.rememberProgress) {
            MusicPlayPrefs.resumePositionFor(NowPlayingState.keyOf(track))
        } else {
            0L
        }
        // Play history (v1.18.0): every real start through playAt is
        // recorded — fresh starts, prev/next and auto-advance alike.
        // The attach path (tryAttach) binds without calling playAt,
        // so re-opening the player never double-records.
        LxPlayHistory.record(this, track)
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
                val url = resolveUrl(track, index)
                if (url.isBlank()) {
                    throw IllegalStateException(getString(R.string.music_resolve_failed))
                }
                startPlayback(track, url)
                ensureEffects()
            } catch (e: PyMusicBridge.PyParseRequiredException) {
                if (!startFallback(index, track)) {
                    state.buffering = false
                    val message = getString(R.string.music_py_parse_unsupported)
                    state.errorText = message
                    Toast.makeText(this@LxMusicPlayerActivity, message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                // Superseded by a newer play / attach / destroy: the
                // cancellation IS the control flow, not a failure.
                // The superseding flow owns the page state, so just
                // rethrow — this catch used to be the generic one
                // below, which toasted "Job was cancelled" and left
                // the page pointing at a player that never started.
                throw e
            } catch (e: Exception) {
                if (!startFallback(index, track)) {
                    state.buffering = false
                    val message = e.message ?: getString(R.string.music_resolve_failed)
                    state.errorText = message
                    Toast.makeText(this@LxMusicPlayerActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        loadLyrics(track, index)
    }

    /**
     * Resolves [track]'s play URL through its own source: py spider
     * tracks go through the shared spider runtime (detail +
     * playerContent, lazily per track) and carry the spider's
     * headers into [currentHeaders]; LX tracks go through the
     * active script. Throws on failure. Shared by [playAt] and the
     * cross-source fallback.
     */
    private suspend fun resolveUrl(track: MusicTrack, index: Int): String {
        if (track.isPy) {
            val resolved = PyMusicBridge.resolve(
                this,
                pyDefFor(track),
                track,
            )
            currentHeaders = resolved.headers
            if (state.index == index) {
                state.currentUrl = resolved.url
                state.qualityText = ""
            }
            return resolved.url
        }
        currentHeaders = emptyMap()
        val url = LxBridge.getMusicUrl(track, MusicPlayPrefs.quality)
        if (state.index == index) {
            state.currentUrl = url
            state.qualityText = LxBridge.lastResolvedQuality()?.uppercase() ?: ""
        }
        return url
    }

    // ------------------------------------------------- cross-source fallback

    /**
     * Cross-source rescue (v1.22.0, Torres: 不要在一棵树上吊死）:
     * the song's own source failed to resolve or play, so the same
     * song is hunted on the OTHER imported sources — the other py
     * spiders and the other LX platforms — and the first version
     * that resolves takes the failed track's place in the queue, so
     * the page, history and next/prev follow the new source.
     * Sources already tried for this song are skipped, so repeated
     * failures keep hopping instead of looping. Returns true when a
     * replacement is playing.
     */
    private suspend fun startFallback(index: Int, failed: MusicTrack): Boolean {
        if (state.index != index) return false
        fallbackTried += failed.source
        state.buffering = true
        val deadline = SystemClock.elapsedRealtime() + FALLBACK_DEADLINE_MS
        val keyword = (failed.name + " " + failed.singer).trim()
        for (step in fallbackSteps(failed)) {
            if (state.index != index) return false
            if (SystemClock.elapsedRealtime() > deadline) break
            val candidate = try {
                step(keyword)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            if (candidate.source in fallbackTried) continue
            fallbackTried += candidate.source
            try {
                val url = resolveUrl(candidate, index)
                if (url.isBlank()) continue
                if (index !in state.queue.indices) return false
                state.queue = state.queue.toMutableList().also { it[index] = candidate }
                state.sourceLabel = sourceLabelFor(candidate)
                state.errorText = null
                updateCoverColor(candidate)
                LxPlayHistory.record(this, candidate)
                startPlayback(candidate, url)
                ensureEffects()
                loadLyrics(candidate, index)
                Toast.makeText(
                    this,
                    getString(
                        R.string.music_fallback_switched,
                        sourceLabelFor(failed),
                        sourceLabelFor(candidate),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                MusicCrashLog.log(
                    this,
                    "fallback: ${failed.source} -> ${candidate.source} for ${failed.name}",
                )
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This candidate's source failed too; keep hopping.
                continue
            }
        }
        state.buffering = false
        return false
    }

    /**
     * The fallback hops for [failed], best prospects first: a py
     * track tries the other py spiders before the LX platforms, an
     * LX track the other platforms before the spiders. Each hop
     * searches one source and yields its best same-song match.
     */
    private fun fallbackSteps(
        failed: MusicTrack,
    ): List<suspend (String) -> MusicTrack?> {
        val lxSteps = listOf("kg", "kw", "wy", "tx")
            .filter { it != failed.source }
            .map { platform ->
                suspend { kw: String ->
                    bestMatch(failed, MusicApi.search(platform, kw, 1))
                }
            }
        val pySteps = PyMusicSources.loadDefs(this)
            .filter { def -> MusicTrack.PY_SOURCE_PREFIX + def.id != failed.source }
            .map { def ->
                suspend { kw: String ->
                    bestMatch(failed, PyMusicBridge.search(this, def, kw, 1).items)
                }
            }
        return if (failed.isPy) pySteps + lxSteps else lxSteps + pySteps
    }

    /** The result in [results] that is the same song as [want], or null. */
    private fun bestMatch(want: MusicTrack, results: List<MusicTrack>): MusicTrack? {
        var best: MusicTrack? = null
        var bestScore = -1
        for (got in results) {
            val score = matchScore(want, got) ?: continue
            if (score > bestScore) {
                bestScore = score
                best = got
            }
        }
        return best
    }

    private fun matchScore(want: MusicTrack, got: MusicTrack): Int? {
        val wantName = coreName(want.name)
        val gotName = coreName(got.name)
        if (wantName.isEmpty() || gotName.isEmpty()) return null
        val nameScore = when {
            wantName == gotName -> 3
            wantName.contains(gotName) || gotName.contains(wantName) -> 2
            else -> return null
        }
        if (!singerMatches(want.singer, got.singer)) return null
        var score = nameScore * 10
        if (want.durationSec > 0 && got.durationSec > 0 &&
            Math.abs(want.durationSec - got.durationSec) <= 20
        ) {
            score += 2
        }
        return score
    }

    /** Song title stripped of brackets and qualifiers for comparison. */
    private fun coreName(raw: String): String {
        var s = raw.lowercase()
        s = s.replace(Regex("[\\(\\[（【][^\\)\\]）】]*[\\)\\]）】]"), "")
        return s.filter { !it.isWhitespace() && it !in "《》〈〉\"'“”‘’·-_" }
    }

    private fun singerMatches(want: String, got: String): Boolean {
        if (want.isBlank() || got.isBlank()) return true
        val wantParts = singerParts(want)
        val gotParts = singerParts(got)
        if (wantParts.isEmpty() || gotParts.isEmpty()) {
            return normalizeSinger(want) == normalizeSinger(got)
        }
        return wantParts.intersect(gotParts).isNotEmpty()
    }

    private fun singerParts(raw: String): Set<String> =
        raw.split('/', '、', ',', '&', '·')
            .map { normalizeSinger(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun normalizeSinger(raw: String): String =
        raw.lowercase().filter { !it.isWhitespace() }

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
            // A cross-source fallback may have swapped this slot's
            // track while the lyric was in flight; drop stale text.
            if (state.current != track) return@launch
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
     * Adopts the shared binding to [LxPlaybackService] from
     * MusicServiceConnection (the music page warms the binding up;
     * this call starts it when nobody has). Any failure — exception
     * or the bind timeout — degrades the session to the local player
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
            if (servicePlayer != null) {
                if (isRetry) {
                    serviceReadyForNext = true
                }
                return
            }
            MusicCrashLog.log(this, "svc2: player connect start (retry=$isRetry)")
            val svc = MusicServiceConnection.connectedService()
                ?: run {
                    MusicServiceConnection.ensureConnecting(this)
                    MusicServiceConnection.awaitConnected(
                        SERVICE_CONNECT_TIMEOUT_MS,
                    )
                }
            val sp = svc?.player
            if (svc == null || sp == null) {
                MusicCrashLog.log(
                    this,
                    "svc2: player connect timeout after " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms " +
                        "(retry=$isRetry)",
                )
                if (!isRetry) {
                    degradeToLocal("connect-timeout")
                }
                return
            }
            boundService = svc
            servicePlayer = sp
            sp.addListener(playerListener)
            sp.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            svc.nextPrevHandler = serviceNextPrevHandler
            MusicCrashLog.log(
                this,
                "svc2: player connect ok in " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "(retry=$isRetry)",
            )
            if (isRetry) {
                serviceReadyForNext = true
            }
        } catch (t: Throwable) {
            MusicCrashLog.log(
                this,
                "svc2: player connect exception ${t.javaClass.simpleName}: " +
                    "${t.message} (retry=$isRetry)",
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
        MusicCrashLog.log(this, "svc2: player degrade to local ($reason)")
        serviceDegraded = true
        if (playbackMode == PlaybackMode.SERVICE) {
            playbackMode = PlaybackMode.LOCAL
        }
        val sp = servicePlayer
        val svc = boundService
        servicePlayer = null
        boundService = null
        if (sp != null) {
            try {
                sp.removeListener(playerListener)
            } catch (t: Throwable) {
                // already gone; the local player is unaffected
            }
        }
        // The shared service/player is never released or unbound here:
        // it belongs to MusicServiceConnection (and the retry loop
        // below may adopt it again). Only this Activity's queue-owner
        // handler is handed back.
        if (svc != null && svc.nextPrevHandler === serviceNextPrevHandler) {
            svc.nextPrevHandler = null
        }
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
                    "svc2: player connect retry $attempts/$SERVICE_RETRY_MAX_ATTEMPTS",
                )
                connectService(isRetry = true)
                if (serviceReadyForNext) {
                    MusicCrashLog.log(
                        this@LxMusicPlayerActivity,
                        "svc2: player connect retry ok, service from next track",
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
     * away. Failures are logged (inside the helper) and ignored.
     */
    private fun startServiceForegroundOnce() {
        if (foregroundServiceStarted) return
        foregroundServiceStarted = true
        MusicServiceConnection.startForegroundService(this)
    }

    /**
     * The player the UI controls right now: the service's player once
     * the session locked onto it (and the binding is still held),
     * otherwise the local player.
     */
    private fun activePlayer(): Player? {
        if (playbackMode == PlaybackMode.SERVICE) {
            val sp = servicePlayer
            if (sp != null) {
                return sp
            }
        }
        return player
    }

    /**
     * Resolves the playback target for a start, locking the session mode
     * on the first call: waits (bounded) for the pending connection
     * attempt, then picks the service when it connected, the local
     * player otherwise. Later calls re-check the binding and
     * degrade if it dropped.
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
            val sp = servicePlayer
            if (sp != null) {
                playbackMode = PlaybackMode.SERVICE
                serviceDegraded = false
                NowPlayingState.localControls = null
                sp.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                MusicCrashLog.log(
                    this,
                    "svc2: player lock SERVICE (retry, from next track)",
                )
                return sp
            }
        }
        when (playbackMode) {
            PlaybackMode.SERVICE -> {
                val sp = servicePlayer
                if (sp != null) {
                    return sp
                }
                degradeToLocal("service-dropped")
                return player
            }

            PlaybackMode.LOCAL -> return player
            PlaybackMode.UNKNOWN -> Unit
        }
        val job = connectJob
        if (job != null && !serviceDegraded) {
            withTimeoutOrNull(SERVICE_CONNECT_WAIT_MS) { job.join() }
        }
        val sp = servicePlayer
        playbackMode = if (!serviceDegraded && sp != null) {
            // Apply the loop mode again: a toggle that happened while
            // the connection was still pending only reached the local
            // player (connectService applied the connect-time value).
            sp.repeatMode = if (state.loopMode == LxLoopMode.SINGLE) {
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
        MusicCrashLog.log(this, "svc2: player mode locked $playbackMode")
        return if (playbackMode == PlaybackMode.SERVICE) sp else player
    }

    /**
     * Hands one resolved track to the locked playback target. The
     * MediaItem (NowPlayingState.mediaItemFor) carries the song key as
     * mediaId plus title/artist/artwork metadata, so the service's
     * media notification and lock screen show the song, not just a URL.
     * In service mode the track's HTTP headers are applied to the
     * service first (direct call on the bound service); if the player
     * call itself fails, the session degrades and the local player
     * takes this very track over.
     */
    private suspend fun startPlayback(track: MusicTrack, url: String) {
        val item = NowPlayingState.mediaItemFor(track, url)
        applyMute()
        val target = awaitActivePlayer()
        val serviceTarget = target != null && target === servicePlayer
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
                canPrev = canPrevFromHere(),
                serviceBacked = serviceTarget,
                queueJson = MusicTrack.listToJson(state.queue),
                queueIndex = state.index,
            ),
        )
        if (serviceTarget && target != null) {
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
            boundService?.setHeaders(currentHeaders)
            try {
                target.setMediaItem(item)
                target.prepare()
                target.play()
                return
            } catch (t: Throwable) {
                degradeToLocal("service-call-failed")
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

    /** Manual-previous availability of the current queue position. */
    private fun canPrevFromHere(): Boolean {
        val size = state.queue.size
        if (size <= 1) return false
        return !(state.loopMode == LxLoopMode.ORDER && state.index <= 0)
    }

    // ---------------------------------------------------------- mute

    /** Player-page mute toggle (v1.26.0, Torres's request). */
    private fun toggleMute() {
        state.muted = !state.muted
        applyMute()
    }

    /**
     * Applies [LxPlayerState.muted] to both players. Volume is a
     * player-level setting, so it survives track changes; it is
     * re-applied at every startPlayback because the service player
     * can be (re)created by a rebind after the toggle.
     */
    private fun applyMute() {
        val volume = if (state.muted) 0f else 1f
        try {
            player?.volume = volume
        } catch (t: Throwable) {
            // player gone; the next startPlayback re-applies
        }
        try {
            servicePlayer?.volume = volume
        } catch (t: Throwable) {
            // service gone; the next startPlayback re-applies
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

    // ------------------------------------------------------- queue editing

    /**
     * Queue edits from the queue dialog (v1.30.0): this Activity
     * is the queue owner, so every mutation lands here — the list,
     * the current index, the remembered queue JSON and the
     * mini-bar record are updated together.
     */
    private fun removeQueueItem(target: Int) {
        val queue = state.queue
        if (target !in queue.indices || queue.size <= 1) return
        val wasCurrent = target == state.index
        state.queue = queue.toMutableList().also { it.removeAt(target) }
        if (target < state.index) {
            state.index -= 1
            lastIndex = state.index
        }
        lastQueueJson = MusicTrack.listToJson(state.queue)
        if (wasCurrent) {
            // Play whatever slid into the removed slot (the new
            // last track when the tail was removed).
            playAt(state.index.coerceAtMost(state.queue.size - 1))
        } else {
            publishQueueEdited()
        }
    }

    private fun moveQueueItem(from: Int, to: Int) {
        val queue = state.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val reordered = queue.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        state.queue = reordered
        val current = state.index
        state.index = when {
            current == from -> to
            from < to && current in (from + 1)..to -> current - 1
            from > to && current in to until from -> current + 1
            else -> current
        }
        lastIndex = state.index
        lastQueueJson = MusicTrack.listToJson(reordered)
        publishQueueEdited()
    }

    /** Clears the queue except the current track, which keeps playing. */
    private fun clearQueue() {
        val current = state.current ?: return
        if (state.queue.size <= 1) return
        state.queue = listOf(current)
        state.index = 0
        lastIndex = 0
        lastQueueJson = MusicTrack.listToJson(state.queue)
        publishQueueEdited()
    }

    /**
     * Re-publishes the mini-bar record after a queue edit that did
     * not change the current track, so the record's queue copy (and
     * its can-next / can-prev flags) stays truthful.
     */
    private fun publishQueueEdited() {
        val current = state.current ?: return
        val info = NowPlayingState.current() ?: return
        if (info.trackKey != NowPlayingState.keyOf(current)) return
        NowPlayingState.publish(
            info.copy(
                queueJson = MusicTrack.listToJson(state.queue),
                queueIndex = state.index,
                canNext = canNextFromHere(),
                canPrev = canPrevFromHere(),
            ),
        )
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
                        url = cachedUrl ?: LxBridge.getMusicUrl(track, MusicPlayPrefs.quality)
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
     * ExoPlayer's own id; service mode reads the bound service's
     * player id directly (same process since v1.16.0, no command
     * round-trip). AudioFX sessions are system-wide, so effects
     * created here in the Activity process do process the service
     * player's output.
     */
    private fun currentAudioSessionId(): Int {
        if (playbackMode == PlaybackMode.SERVICE) {
            return boundService?.audioSessionId ?: 0
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
        ensureVisualizer()
    }

    /**
     * Creates the rhythm visualizer on the output mix (session 0)
     * and starts FFT capture into [LxPlayerState.vizLevels]. The
     * mix needs no session bookkeeping, so once created it stays
     * valid across track and mode changes; the page ticker calls
     * this every 400 ms so a creation that raced device state is
     * retried until it sticks. Requires RECORD_AUDIO; without the
     * grant it stays off and the permission callback retries after
     * a grant.
     */
    private fun ensureVisualizer() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (audioVisualizer != null) return
        try {
            val viz = Visualizer(0)
            val captureSize = Visualizer.getCaptureSizeRange()[1]
            viz.captureSize = captureSize
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        data: ByteArray?,
                        rate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        data: ByteArray?,
                        rate: Int,
                    ) {
                        if (data != null) processFft(data)
                    }
                },
                Visualizer.getMaxCaptureRate(),
                false,
                true,
            )
            viz.enabled = true
            audioVisualizer = viz
            MusicCrashLog.log(this, "viz: output-mix visualizer on")
        } catch (t: Throwable) {
            audioVisualizer = null
            MusicCrashLog.log(
                this,
                "viz: create failed ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /**
     * Folds one FFT frame into the smoothed bar levels and publishes
     * them. FFT bytes come as signed (real, imaginary) pairs per
     * bin; bins are grouped logarithmically so the bass bars get the
     * narrow slices they need. Attack is instant, decay is gradual —
     * that asymmetry is what makes the bars bounce instead of jitter.
     */
    private fun processFft(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < VIZ_BARS + 1) return
        val logMax = Math.log(bins.toDouble())
        for (bar in 0 until VIZ_BARS) {
            val lo = Math.exp(logMax * bar / VIZ_BARS).toInt().coerceAtLeast(1)
            val hi = Math.exp(logMax * (bar + 1) / VIZ_BARS).toInt()
                .coerceAtLeast(lo + 1)
                .coerceAtMost(bins)
            var maxMag = 0f
            for (k in lo until hi) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                val mag = Math.sqrt((re * re + im * im).toDouble()).toFloat()
                if (mag > maxMag) maxMag = mag
            }
            val target = (maxMag / 140f).coerceIn(0f, 1f)
            vizBars[bar] = if (target > vizBars[bar]) {
                target
            } else {
                maxOf(target, vizBars[bar] * 0.78f)
            }
        }
        state.vizLevels = vizBars.copyOf()
    }

    private fun releaseVisualizer() {
        try {
            audioVisualizer?.enabled = false
        } catch (t: Throwable) {
        }
        try {
            audioVisualizer?.release()
        } catch (t: Throwable) {
        }
        audioVisualizer = null
        if (state.vizLevels.isNotEmpty()) {
            state.vizLevels = FloatArray(0)
        }
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
        releaseVisualizer()
        // Detach from the service: the service (and its playback)
        // outlives the page, which is the point of the service mode.
        // The shared binding is only un-listened and the queue-owner
        // handler handed back — unbinding or releasing the service's
        // player would kill the process-wide warm connection for
        // everyone. The local player is ours and is released.
        val sp = servicePlayer
        val svc = boundService
        servicePlayer = null
        boundService = null
        if (sp != null) {
            try {
                sp.removeListener(playerListener)
            } catch (t: Throwable) {
                // already gone
            }
        }
        if (svc != null && svc.nextPrevHandler === serviceNextPrevHandler) {
            svc.nextPrevHandler = null
        }
        player?.release()
        player = null
        super.onDestroy()
    }
}
