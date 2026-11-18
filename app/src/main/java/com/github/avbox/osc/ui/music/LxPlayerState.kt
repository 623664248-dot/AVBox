package com.github.avbox.osc.ui.music

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.github.avbox.osc.R
import com.github.avbox.osc.music.MusicTrack

/** Queue advance behaviour of the LX player, cycled by its loop button. */
enum class LxLoopMode(@StringRes val labelRes: Int) {
    LIST(R.string.music_mode_list),
    SINGLE(R.string.music_mode_single),
    SHUFFLE(R.string.music_mode_shuffle),
    ORDER(R.string.music_mode_order);

    fun next(): LxLoopMode = when (this) {
        LIST -> SINGLE
        SINGLE -> SHUFFLE
        SHUFFLE -> ORDER
        ORDER -> LIST
    }

    companion object {
        fun fromName(name: String?): LxLoopMode =
            entries.firstOrNull { it.name == name } ?: LIST
    }
}

/** Download progress of the current track, driven by the Activity. */
enum class LxDownloadStatus { IDLE, DOWNLOADING, DONE }

/**
 * UI state for the standalone LX music player (same state-container form as
 * MusicPlayerState): the Activity mutates it, the screen only reads it.
 */
class LxPlayerState {
    var queue by mutableStateOf<List<MusicTrack>>(emptyList())
    var index by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var lyricLines by mutableStateOf<List<LyricLine>>(emptyList())
    var errorText by mutableStateOf<String?>(null)
    var showQueue by mutableStateOf(false)

    /** Device EQ preset names enumerated by the Activity (empty = no audiofx). */
    var eqPresets by mutableStateOf<List<String>>(emptyList())

    /** Equalizer band count published by the Activity (0 = no equalizer). */
    var eqBandCount by mutableIntStateOf(0)

    /** Equalizer band level range in millibels, published by the Activity. */
    var eqBandMinMb by mutableIntStateOf(0)
    var eqBandMaxMb by mutableIntStateOf(0)

    /**
     * Per-band levels (millibels) of the "custom" EQ mode, mirrored here
     * for the appearance sheet sliders; persisted in KV by MusicAppearance.
     */
    var eqCustomLevels by mutableStateOf<List<Int>>(emptyList())

    /** Queue advance behaviour; persisted by the Activity in KV. */
    var loopMode by mutableStateOf(LxLoopMode.LIST)

    /** Whether the current track is in the user's favourites. */
    var isFavorite by mutableStateOf(false)

    /** Player-page mute toggle (v1.26.0); volume lives on the players. */
    var muted by mutableStateOf(false)

    /** Resolved direct URL of the current track (null while resolving). */
    var currentUrl by mutableStateOf<String?>(null)

    /** Display-ready quality of the resolved URL ("320K"), "" when unknown. */
    var qualityText by mutableStateOf("")

    /**
     * Display-ready name of the current track's source for the tag chip:
     * the platform name for LX tracks, the py registry's display name
     * for spider tracks. Computed by the Activity when a track becomes
     * current ("" until then); the raw MusicTrack.source id — notably
     * "py:<slug>" — is never rendered.
     */
    var sourceLabel by mutableStateOf("")

    /**
     * Page background colour sampled from the current cover (already
     * darkened); null until extraction succeeds, the screen then falls
     * back to a deep blue-grey.
     */
    var bgColor by mutableStateOf<Color?>(null)

    /**
     * Sleep timer deadline as a wall-clock timestamp (ms); 0 = off.
     * Owned by the Activity: set from the sleep sheet, counted down by
     * its ticker, survives track changes, dies with the player page.
     */
    var sleepTimerEndAtMs by mutableLongStateOf(0L)

    /** Whole minutes left on the sleep timer, refreshed by the ticker. */
    var sleepMinutesLeft by mutableIntStateOf(0)

    /**
     * Rhythm-visualizer bar levels (0f..1f) for the player page
     * (v1.20.0), published by the activity's Visualizer at capture
     * rate. Empty until the first FFT frame lands; replaced (never
     * mutated) per frame so Compose sees every update.
     */
    var vizLevels by mutableStateOf<FloatArray>(FloatArray(0))

    var downloadStatus by mutableStateOf(LxDownloadStatus.IDLE)

    /** 0..100 while downloading; -1 when the total size is unknown. */
    var downloadPercent by mutableIntStateOf(-1)

    /** Batch queue download progress (v1.32.0); total 0 = idle. */
    var queueDlTotal by mutableIntStateOf(0)
    var queueDlDone by mutableIntStateOf(0)
    var queueDlFailed by mutableIntStateOf(0)

    val current: MusicTrack?
        get() = queue.getOrNull(index)
}
