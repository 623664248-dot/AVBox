package com.github.avbox.osc.ui.music

import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        /** AudioFX strength scale is 0..1000; 700 is a clearly audible boost. */
        private const val EQ_EFFECT_STRENGTH: Short = 700

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
        buildPlayer()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    LxMusicPlayerScreen(
                        state = state,
                        onBack = { finish() },
                        onTogglePlay = { togglePlay() },
                        onNext = { playAt(state.index + 1) },
                        onPrev = { playAt(state.index - 1) },
                        onSeek = { ms -> player?.seekTo(ms) },
                        onToggleQueue = { state.showQueue = !state.showQueue },
                        onSelectQueueItem = { i ->
                            state.showQueue = false
                            playAt(i)
                        },
                        onEqModeSelected = { ensureEffects() },
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

    private fun buildPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    ensureEffects()
                }
                if (playbackState == Player.STATE_ENDED) {
                    playAt(state.index + 1)
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
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            try {
                val url = LxBridge.getMusicUrl(track)
                val exo = player ?: return@launch
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                ensureEffects()
                exo.play()
            } catch (e: Exception) {
                state.buffering = false
                val message = e.message ?: getString(R.string.music_resolve_failed)
                state.errorText = message
                Toast.makeText(this@LxMusicPlayerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
        lyricJob?.cancel()
        lyricJob = lifecycleScope.launch {
            val raw = MusicApi.lyric(track)
            if (state.index != index) return@launch
            if (!raw.isNullOrBlank()) {
                state.lyricLines = MusicLrc.parse(raw)
            }
        }
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
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
                        boost.strength = EQ_EFFECT_STRENGTH
                        boost.enabled = true
                    }
                }

                mode == MusicAppearance.EQ_SURROUND -> {
                    virtualizer?.let { virt ->
                        if (virt.strengthSupported) {
                            virt.strength = EQ_EFFECT_STRENGTH
                        }
                        virt.enabled = true
                    }
                }

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
        releaseEffects()
        player?.release()
        player = null
        super.onDestroy()
    }
}
