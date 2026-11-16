package com.github.avbox.osc.ui.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.avbox.osc.music.MusicTrack

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

    val current: MusicTrack?
        get() = queue.getOrNull(index)
}
