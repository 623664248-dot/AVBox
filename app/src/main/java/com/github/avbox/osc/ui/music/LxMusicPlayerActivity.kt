package com.github.avbox.osc.ui.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxPlaybackService
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.avbox.osc.util.PermissionHelper
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Standalone music player for the music tab. The actual ExoPlayer lives in
 * [LxPlaybackService] (a MediaSessionService) so playback continues in the
 * background / on the lock screen with a system media notification; this
 * Activity only connects a [MediaController] to it. Play URLs are still
 * resolved per track through the LX source script (LxBridge) here in the
 * UI layer, then handed to the controller as a MediaItem carrying title /
 * artist / artwork metadata for the notification. It deliberately does not
 * go through the VOD Detail/PlayLoader pipeline (the existing
 * ui.MusicPlayerActivity is bound to a VOD playback session and cannot
 * take an external queue).
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
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingPlay = false
    private var resolveJob: Job? = null
    private var lyricJob: Job? = null
    private var tickerJob: Job? = null

    private val playerListener = object : Player.Listener {
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
    }

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
        PermissionHelper.requestNotificationIfNeeded(this)
        connectController()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    LxMusicPlayerScreen(
                        state = state,
                        onBack = { finish() },
                        onTogglePlay = { togglePlay() },
                        onNext = { playAt(state.index + 1) },
                        onPrev = { playAt(state.index - 1) },
                        onSeek = { ms -> controller?.seekTo(ms) },
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
                val media = controller
                if (media != null) {
                    state.positionMs = media.currentPosition.coerceAtLeast(0L)
                    val duration = media.duration
                    if (duration > 0) state.durationMs = duration
                }
                delay(400)
            }
        }
        playAt(state.index)
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, LxPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val media = try {
                    future.get()
                } catch (e: Exception) {
                    null
                } ?: return@addListener
                controller = media
                media.addListener(playerListener)
                state.playing = media.isPlaying
                state.buffering = media.playbackState == Player.STATE_BUFFERING
                if (pendingPlay) {
                    pendingPlay = false
                    playAt(state.index)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
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
        lyricJob?.cancel()
        lyricJob = lifecycleScope.launch {
            val raw = MusicApi.lyric(track)
            if (state.index != index) return@launch
            if (!raw.isNullOrBlank()) {
                state.lyricLines = MusicLrc.parse(raw)
            }
        }
        val media = controller
        if (media == null) {
            // Controller not connected yet; connectController() replays this.
            pendingPlay = true
            return
        }
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            try {
                val url = LxBridge.getMusicUrl(track)
                if (state.index != index) return@launch
                val active = controller ?: return@launch
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.singer)
                    .setAlbumTitle(track.albumName)
                    .setArtworkUri(track.img?.let { Uri.parse(it) })
                    .build()
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaId("lx_" + index)
                    .setMediaMetadata(metadata)
                    .build()
                active.setMediaItem(item)
                active.prepare()
                active.play()
            } catch (e: Exception) {
                state.buffering = false
                val message = e.message ?: getString(R.string.music_resolve_failed)
                state.errorText = message
                Toast.makeText(this@LxMusicPlayerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlay() {
        val media = controller ?: return
        when {
            media.isPlaying -> media.pause()
            media.playbackState == Player.STATE_ENDED -> playAt(state.index)
            else -> media.play()
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        resolveJob?.cancel()
        lyricJob?.cancel()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        // The service keeps playing in the background on purpose.
        super.onDestroy()
    }
}
