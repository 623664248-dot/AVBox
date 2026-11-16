package com.github.avbox.osc.music

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback service for the LX music player: it owns the
 * ExoPlayer so playback continues when the player Activity is backgrounded
 * or destroyed, and exposes it through a [MediaSession]. The session
 * service's default media notification (DefaultMediaNotification.Provider)
 * shows the app icon plus the title/artist/artwork taken from each
 * MediaItem's MediaMetadata, with working play/pause from the system
 * media controls (lock screen / notification shade).
 *
 * URL resolution stays in the UI layer (LxMusicPlayerActivity resolves a
 * direct link through LxBridge first, then hands a ready MediaItem to the
 * controller); this service only plays what it is given.
 */
class LxPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
