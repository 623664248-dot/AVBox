package com.github.avbox.osc.music

import com.github.avbox.osc.util.KV

/**
 * Playback-behaviour preferences (v1.20.0), mirroring the toggles
 * of the reference player Torres compares against:
 *
 * - [backgroundPlay] — default ON, the behaviour AVBox has had
 *   since the playback service landed: leaving the app keeps the
 *   music playing (with the player's built-in audio-focus manners:
 *   calls and voice messages pause it, and it resumes after).
 *   OFF means leaving the app stops playback.
 * - [floatingMiniPlayer] — default OFF (it needs the system
 *   overlay permission, so it is opt-in): a round floating mini
 *   player over other apps while music plays.
 *
 * Stored as "1"/"0" strings in KV; the settings sheet keeps its own
 * switch state and the playback service reads these live.
 */
object MusicPlayPrefs {

    private const val KEY_BACKGROUND_PLAY = "lx_background_play"
    private const val KEY_FLOATING_MINI = "lx_floating_mini"

    var backgroundPlay: Boolean
        get() = KV.get(KEY_BACKGROUND_PLAY, "") != "0"
        set(value) {
            KV.put(KEY_BACKGROUND_PLAY, if (value) "1" else "0")
        }

    var floatingMiniPlayer: Boolean
        get() = KV.get(KEY_FLOATING_MINI, "") == "1"
        set(value) {
            KV.put(KEY_FLOATING_MINI, if (value) "1" else "0")
        }
}
