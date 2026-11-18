package com.github.avbox.osc.music

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "now playing" state behind the music page's mini player
 * bar (v1.13.3). One shared record, three writers:
 *
 *  - LxMusicPlayerActivity publishes the full record on every track
 *    start (it owns the queue, the source label and the loop mode) and
 *    flips the playing flag from its playback listener.
 *  - LxPlaybackService keeps the record fresh while the player page is
 *    gone: its player listener mirrors isPlaying and, on media item
 *    transitions, republishes from the item's metadata while keeping
 *    the enrichment fields (queue, label, canNext) of the record the
 *    Activity or the mini bar published just before the hand-off.
 *  - The mini bar itself republishes when it advances the queue in
 *    service mode (the player Activity is usually destroyed by then).
 *
 * [Info.serviceBacked] tells the two playback worlds apart: service
 * records are controllable from anywhere through the bound
 * LxPlaybackService's player (MusicServiceConnection, v1.16.0);
 * local records (the Activity's degraded in-process player) are
 * controllable only through [localControls], which the owning Activity
 * registers while it lives and unregisters on destroy — local playback
 * ends with that Activity, which also clears the record.
 */
object NowPlayingState {

    data class Info(
        /** Stable song identity ([keyOf]); also the MediaItem mediaId. */
        val trackKey: String,
        val title: String,
        val singer: String,
        val img: String?,
        /** Display name of the source (platform or py registry name). */
        val sourceLabel: String,
        val isPlaying: Boolean,
        /** Whether a manual "next" from the mini bar can advance. */
        val canNext: Boolean,
        /** Whether a manual "previous" from the mini bar can go back. */
        val canPrev: Boolean = true,
        /** True: LxPlaybackService owns playback. False: local player. */
        val serviceBacked: Boolean,
        /** The queue the current track belongs to (MusicTrack JSON). */
        val queueJson: String,
        val queueIndex: Int,
    )

    /**
     * Controls of the Activity-owned local player, registered by
     * LxMusicPlayerActivity while it lives. Invoked from the mini bar's
     * click handlers (main thread).
     */
    interface LocalControls {
        fun playPause()
        fun next()
        fun prev()
        fun stop()
    }

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    /** Live local-player controls; null unless a local session runs. */
    @Volatile
    var localControls: LocalControls? = null

    fun current(): Info? = _info.value

    fun publish(info: Info) {
        _info.value = info
    }

    /** Flips only the playing flag of the current record, if any. */
    fun updatePlaying(playing: Boolean) {
        val cur = _info.value ?: return
        if (cur.isPlaying != playing) {
            _info.value = cur.copy(isPlaying = playing)
        }
    }

    fun clear() {
        _info.value = null
    }

    /**
     * Identity of a song, the same formula as LxFavorites.keyOf:
     * platform + songmid (name/singer fallback), with the py episode id
     * appended for episode tracks.
     */
    fun keyOf(track: MusicTrack): String {
        val base = if (track.songmid.isNotEmpty()) {
            track.source + "|" + track.songmid
        } else {
            track.source + "|" + track.name + "|" + track.singer
        }
        return if (track.pyEpId.isNotEmpty()) base + "|" + track.pyEpId else base
    }

    /**
     * The MediaItem handed to whichever player runs a track: the song
     * key as mediaId (the player page's attach flow compares it against
     * the shared record) and title/artist/artwork metadata so the
     * service notification and lock screen show the song, not a URL.
     */
    fun mediaItemFor(track: MusicTrack, url: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(keyOf(track))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.singer)
                    .setAlbumTitle(track.albumName)
                    .setArtworkUri(track.img?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
    }
}
