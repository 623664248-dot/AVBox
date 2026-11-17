package com.github.avbox.osc.music

import android.content.Context
import com.github.avbox.osc.util.KV

/**
 * Recently played songs for the LX music player, most recent first,
 * persisted as a JSON array of [MusicTrack] in KV — the same storage
 * approach as [LxFavorites], so a history entry carries every field
 * playback needs and can be replayed straight from the list. All KV
 * reads pass a "" default: without one, unregistered keys decode to
 * null (the v1.9.0 music-import bug).
 *
 * The [Context] parameters mirror the import/storage helpers' shape;
 * KV itself is process-wide and needs no context.
 */
object LxPlayHistory {

    private const val KV_KEY = "lx_play_history"

    /** Oldest entries beyond this cap are dropped on record. */
    private const val MAX_ENTRIES = 100

    fun all(context: Context): List<MusicTrack> {
        val raw: String = KV.get(KV_KEY, "")
        return MusicTrack.listFromJson(raw)
    }

    /**
     * Moves [track] to the front of the history (a replay refreshes
     * its position instead of duplicating it), then trims the tail
     * to [MAX_ENTRIES].
     */
    fun record(context: Context, track: MusicTrack) {
        val key = keyOf(track)
        val list = all(context).toMutableList()
        list.removeAll { keyOf(it) == key }
        list.add(0, track)
        KV.put(KV_KEY, MusicTrack.listToJson(list.take(MAX_ENTRIES)))
    }

    fun clear(context: Context) {
        KV.put(KV_KEY, "")
    }

    /** Same identity rule as [LxFavorites]: platform + songmid (+ episode). */
    private fun keyOf(track: MusicTrack): String {
        val base = if (track.songmid.isNotEmpty()) {
            track.source + "|" + track.songmid
        } else {
            track.source + "|" + track.name + "|" + track.singer
        }
        return if (track.pyEpId.isNotEmpty()) base + "|" + track.pyEpId else base
    }
}
