package com.github.avbox.osc.ui.music

import android.content.Context
import android.content.Intent
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

    override fun onDestroy() {
        tickerJob?.cancel()
        resolveJob?.cancel()
        lyricJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }
}
