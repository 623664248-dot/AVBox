package com.github.avbox.osc.music

import android.content.Context
import com.github.avbox.osc.util.KV
import org.json.JSONArray
import org.json.JSONObject

/**
 * User playlists for the LX music player, most recently created
 * first, persisted as a JSON array in KV — the same storage
 * approach as [LxPlayHistory], so a playlist carries the full
 * [MusicTrack] list and can be played back as a queue directly.
 * All KV reads pass a "" default: without one, unregistered keys
 * decode to null (the v1.9.0 music-import bug).
 *
 * The [Context] parameters mirror [LxPlayHistory]'s shape; KV
 * itself is process-wide and needs no context.
 */
object LxPlaylists {

    data class Playlist(val name: String, val tracks: List<MusicTrack>)

    private const val KV_KEY = "lx_playlists"

    fun all(context: Context): List<Playlist> {
        val raw: String = KV.get(KV_KEY, "")
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Playlist>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isEmpty()) continue
                val tracks = MusicTrack.listFromJson(
                    obj.optJSONArray("tracks")?.toString(),
                )
                out.add(Playlist(name, tracks))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Creates a playlist, replacing any existing one of the same name. */
    fun create(context: Context, name: String, tracks: List<MusicTrack>) {
        val list = all(context).toMutableList()
        list.removeAll { it.name == name }
        list.add(0, Playlist(name, tracks))
        save(list)
    }

    fun delete(context: Context, name: String) {
        val list = all(context).toMutableList()
        if (list.removeAll { it.name == name }) save(list)
    }

    private fun save(list: List<Playlist>) {
        val array = JSONArray()
        for (playlist in list) {
            val obj = JSONObject()
            obj.put("name", playlist.name)
            obj.put(
                "tracks",
                JSONArray(MusicTrack.listToJson(playlist.tracks)),
            )
            array.put(obj)
        }
        KV.put(KV_KEY, array.toString())
    }
}
