package com.github.avbox.osc.music

import com.github.avbox.osc.util.KV

/**
 * User's favourite songs for the LX music player, persisted as a JSON
 * array of [MusicTrack] in KV (same serialization as the player queue,
 * so a favourite carries every field playback needs). All KV reads pass
 * a "" default: without one, unregistered keys decode to null (the
 * v1.9.0 music-import bug).
 */
object LxFavorites {

    private const val KV_KEY = "lx_favorites"

    fun load(): List<MusicTrack> {
        val raw: String = KV.get(KV_KEY, "")
        return MusicTrack.listFromJson(raw)
    }

    fun isFavorite(track: MusicTrack): Boolean {
        val key = keyOf(track)
        return load().any { keyOf(it) == key }
    }

    /** Adds or removes [track]; returns the new favourite state. */
    fun toggle(track: MusicTrack): Boolean {
        val key = keyOf(track)
        val list = load().toMutableList()
        val existing = list.indexOfFirst { keyOf(it) == key }
        return if (existing >= 0) {
            list.removeAt(existing)
            save(list)
            false
        } else {
            list.add(0, track)
            save(list)
            true
        }
    }

    private fun save(list: List<MusicTrack>) {
        KV.put(KV_KEY, MusicTrack.listToJson(list))
    }

    /**
     * Identity of a song across sessions: platform + songmid (the LX id).
     * Falls back to name/singer for the rare track without an id. Py
     * episode tracks share the vod's songmid, so the episode id joins the
     * key to keep episodes of one album/playlist distinct.
     */
    private fun keyOf(track: MusicTrack): String {
        val base = if (track.songmid.isNotEmpty()) {
            track.source + "|" + track.songmid
        } else {
            track.source + "|" + track.name + "|" + track.singer
        }
        return if (track.pyEpId.isNotEmpty()) base + "|" + track.pyEpId else base
    }
}
