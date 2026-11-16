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
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.music.PyMusicBridge
import com.github.avbox.osc.music.PyMusicSources
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.avbox.osc.util.KV
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Standalone music player for the music tab: plays the search-result queue
 * with its own ExoPlayer instance. Play URLs are resolved per track through
 * the LX source script (LxBridge); it deliberately does not go through the
 * VOD Detail/PlayLoader pipeline (the existing ui.MusicPlayerActivity is
 * bound to a VOD playback session and cannot take an external queue).
 */
class LxMusicPlayerActivity : BaseActivity() {

    companion object {
        private const val EXTRA_QUEUE = "lx_queue"
        private const val EXTRA_INDEX = "lx_index"

        /** KV key for the persisted loop mode (LxLoopMode name). */
        private const val KV_LOOP_MODE = "lx_loop_mode"

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
            context.startActivity(
                Intent(context, LxMusicPlayerActivity::class.java)
                    .putExtra(EXTRA_QUEUE, queueJson)
                    .putExtra(EXTRA_INDEX, index),
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
        enableTransparentEdgeToEdge()
        val queue = MusicTrack.listFromJson(intent?.getStringExtra(EXTRA_QUEUE))
        if (queue.isEmpty()) {
            finish()
            return
        }
        state.queue = queue
        state.index = (intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0).coerceIn(0, queue.size - 1)
        // The "" default matters: KV returns null for unregistered keys
        // when no default is given (the v1.9.0 music-import bug).
        val savedLoopMode: String = KV.get(KV_LOOP_MODE, "")
        state.loopMode = LxLoopMode.fromName(savedLoopMode)
        buildPlayer()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    LxMusicPlayerScreen(
                        state = state,
                        onBack = { finish() },
                        onTogglePlay = { togglePlay() },
                        onNext = { playNextManual() },
                        onPrev = { playAt(state.index - 1) },
                        onSeek = { ms -> player?.seekTo(ms) },
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
                    )
                }
            }
        }
        tickerJob = lifecycleScope.launch {
            while (true) {
                val exo = player
                if (exo != null) {
                    state.positionMs = exo.currentPosition.coerceAtLeast(0L)
                    val duration = exo.duration
                    if (duration > 0) state.durationMs = duration
                }
                delay(400)
            }
        }
        playAt(state.index)
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
        exo.addListener(object : Player.Listener {
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
            }

            override fun onPlayerError(error: PlaybackException) {
                state.buffering = false
                state.errorText = error.message ?: getString(R.string.music_resolve_failed)
            }
        })
        player = exo
    }

    private fun playAt(target: Int) {
        val queue = state.queue
        if (queue.isEmpty()) return
        val index = ((target % queue.size) + queue.size) % queue.size
        state.index = index
        val track = queue[index]
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
                val exo = player ?: return@launch
                httpDataSourceFactory.setDefaultRequestProperties(currentHeaders)
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                ensureEffects()
                exo.play()
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
        lyricJob?.cancel()
        // Py spiders provide no lyrics: leave the lyric area empty (the
        // screen shows its no-lyrics text) instead of force-matching a
        // platform lyric onto a spider song.
        if (!track.isPy) {
            lyricJob = lifecycleScope.launch {
                val raw = MusicApi.lyric(track)
                if (state.index != index) return@launch
                if (!raw.isNullOrBlank()) {
                    state.lyricLines = MusicLrc.parse(raw)
                }
            }
        }
    }

    /** The registered py source definition a py track belongs to. */
    private fun pyDefFor(track: MusicTrack): PyMusicSources.PyDef {
        return PyMusicSources.findDef(this, track.pySourceId)
            ?: throw IllegalStateException(getString(R.string.music_py_source_missing))
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
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
        player?.repeatMode = if (mode == LxLoopMode.SINGLE) {
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
     * Creates the AudioFX effects for the player's current audio session
     * when they are missing or the session changed, then applies the
     * persisted EQ mode. The session id is only assigned once the player
     * has prepared an audio track, so this is called again from the
     * playback callbacks; it is cheap and idempotent. Every step is
     * guarded: devices without audiofx simply keep playing without
     * effects.
     */
    private fun ensureEffects() {
        val exo = player ?: return
        val sessionId = try {
            exo.audioSessionId
        } catch (t: Throwable) {
            0
        }
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
        releaseEffects()
        player?.release()
        player = null
        super.onDestroy()
    }
}
