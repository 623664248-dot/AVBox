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
    private const val KEY_QUALITY = "lx_quality"
    private const val KEY_REMEMBER_PROGRESS = "lx_remember_progress"
    private const val KEY_AUDIO_FOCUS_PAUSE = "lx_audio_focus_pause"
    private const val KEY_RESUME_KEY = "lx_resume_key"
    private const val KEY_RESUME_POS = "lx_resume_pos_ms"

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

    /**
     * Preferred quality for LX tracks (v1.23.0, from the reference
     * player's settings): 128k / 320k / flac / flac24bit. The
     * bridge still falls back to lower qualities when the script
     * cannot serve the preferred one. Py spider tracks ignore it —
     * their quality lines are the spider's own.
     */
    var quality: String
        get() = KV.get(KEY_QUALITY, "").ifEmpty { "320k" }
        set(value) {
            KV.put(KEY_QUALITY, value)
        }

    /** Resume a re-played song where it left off (v1.23.0). */
    var rememberProgress: Boolean
        get() = KV.get(KEY_REMEMBER_PROGRESS, "") == "1"
        set(value) {
            KV.put(KEY_REMEMBER_PROGRESS, if (value) "1" else "0")
        }

    /**
     * Pause when other apps make sound (audio focus), default ON —
     * the behaviour AVBox has always had. OFF lets the players be
     * built without focus handling so music plays over other apps.
     * Read when a player is built, so a change applies from the
     * next app start.
     */
    var audioFocusPause: Boolean
        get() = KV.get(KEY_AUDIO_FOCUS_PAUSE, "") != "0"
        set(value) {
            KV.put(KEY_AUDIO_FOCUS_PAUSE, if (value) "1" else "0")
        }

    /** Stores the resume point of the song playing now. */
    fun saveResume(trackKey: String, positionMs: Long) {
        KV.put(KEY_RESUME_KEY, trackKey)
        KV.put(KEY_RESUME_POS, positionMs.toString())
    }

    /** The stored resume point for [trackKey], or 0 when none. */
    fun resumePositionFor(trackKey: String): Long {
        if (KV.get(KEY_RESUME_KEY, "") != trackKey) return 0L
        return KV.get(KEY_RESUME_POS, "").toLongOrNull() ?: 0L
    }

    fun clearResume() {
        KV.put(KEY_RESUME_KEY, "")
        KV.put(KEY_RESUME_POS, "0")
    }
}
