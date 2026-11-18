package com.github.avbox.osc.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * One song from a platform search (kg / kw / tx / wy / mg), modelled on the
 * musicInfo object LX Music builds from its own musicSdk search results, so
 * that LX custom-source scripts can consume it unchanged:
 *
 *  - kg: songmid = Audioid, hash = FileHash (per-quality hashes in types),
 *        albumId = AlbumID
 *  - kw: songmid = MUSICRID without the "MUSIC_" prefix, albumId = ALBUMID
 *  - tx: songmid = mid, albumMid = album.mid, strMediaMid = file.media_mid,
 *        albumId = album.mid
 *  - wy: songmid = id, albumId = album.id
 *
 * Field evidence: LX musicSdk musicSearch.js of each platform plus the
 * readable source scripts (IKUN uses musicInfo.hash ?? musicInfo.songmid,
 * GD uses musicInfo.songmid || musicInfo.id, 星海 uses
 * hash ?? songmid ?? id and name/singer for its backup API).
 */
data class MusicTrack(
    val source: String,
    val name: String,
    val singer: String,
    val albumName: String,
    val albumId: String,
    val img: String?,
    val durationSec: Int,
    val songmid: String,
    val hash: String = "",
    val albumMid: String = "",
    val strMediaMid: String = "",
    /** Per-quality hashes (kg only): quality type -> hash. */
    val typeHashes: Map<String, String> = emptyMap(),
    /**
     * Py-spider playback coordinates (only for tracks whose [source] is
     * "py:<sourceId>"): the vod_play_from flag and the episode id inside
     * vod_play_url that playerContent expects. Empty for plain list rows:
     * those resolve lazily through detailContent at play time, and always
     * empty for platform (LX) tracks.
     */
    val pyFlag: String = "",
    val pyEpId: String = "",
) {
    /** True when this track belongs to a py music spider source. */
    val isPy: Boolean get() = source.startsWith(PY_SOURCE_PREFIX)

    /** The py source id (slug) without the "py:" prefix; "" for LX tracks. */
    val pySourceId: String get() = source.removePrefix(PY_SOURCE_PREFIX)
    val intervalText: String
        get() {
            if (durationSec <= 0) return ""
            val m = durationSec / 60
            val s = durationSec % 60
            return m.toString() + ":" + s.toString().padStart(2, '0')
        }

    /** The LX musicInfo object handed to source scripts. */
    fun toMusicInfo(): JSONObject {
        val info = JSONObject()
        info.put("source", source)
        info.put("name", name)
        info.put("singer", singer)
        info.put("albumName", albumName)
        info.put("albumId", albumId)
        info.put("interval", intervalText)
        info.put("img", img ?: JSONObject.NULL)
        // LX keeps songmid numeric for kg/kw/wy and a string for tx.
        val numericId = songmid.toLongOrNull()
        if (numericId != null && source != "tx") {
            info.put("songmid", numericId)
        } else {
            info.put("songmid", songmid)
        }
        if (hash.isNotEmpty()) info.put("hash", hash)
        if (albumMid.isNotEmpty()) info.put("albumMid", albumMid)
        if (strMediaMid.isNotEmpty()) info.put("strMediaMid", strMediaMid)
        val types = JSONArray()
        for ((type, h) in typeHashes) {
            val item = JSONObject()
            item.put("type", type)
            if (h.isNotEmpty()) item.put("hash", h)
            types.put(item)
        }
        info.put("types", types)
        info.put("typeUrl", JSONObject())
        return info
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("source", source)
        obj.put("name", name)
        obj.put("singer", singer)
        obj.put("albumName", albumName)
        obj.put("albumId", albumId)
        obj.put("img", img ?: JSONObject.NULL)
        obj.put("durationSec", durationSec)
        obj.put("songmid", songmid)
        obj.put("hash", hash)
        obj.put("albumMid", albumMid)
        obj.put("strMediaMid", strMediaMid)
        val types = JSONObject()
        for ((type, h) in typeHashes) types.put(type, h)
        obj.put("typeHashes", types)
        obj.put("pyFlag", pyFlag)
        obj.put("pyEpId", pyEpId)
        return obj
    }

    companion object {
        /** Source value prefix marking a track as belonging to a py spider. */
        const val PY_SOURCE_PREFIX = "py:"

        /**
         * HD rewrite of a cover URL (v1.24.0): the platform CDNs
         * serve any size, but searches and spiders often hand out
         * thumbnails (Kugou 240px, QQ 500px) that smear across the
         * player page. Known patterns are upgraded to screen-sized
         * variants (both verified live: Kugou stdmusic/1080 is a
         * true 1080x1080, QQ R1200x1200 a true 1200x1200);
         * anything else passes through untouched. Kuwo's
         * albumcover path already returns the original image
         * (1477x1477 measured) and NetEase picUrl is the original
         * too, so neither is rewritten.
         */
        fun hiResCover(url: String?): String? {
            if (url.isNullOrEmpty()) return url
            var out = url
            out = out.replace(
                Regex("imge\\.kugou\\.com/stdmusic/\\d+/"),
                "imge.kugou.com/stdmusic/1080/",
            )
            out = out.replace(
                Regex("T002R\\d+x\\d+M000"),
                "T002R1200x1200M000",
            )
            return out
        }

        fun fromJson(obj: JSONObject): MusicTrack {
            val types = LinkedHashMap<String, String>()
            val typeObj = obj.optJSONObject("typeHashes")
            if (typeObj != null) {
                val keys = typeObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    types[key] = typeObj.optString(key)
                }
            }
            return MusicTrack(
                source = obj.optString("source"),
                name = obj.optString("name"),
                singer = obj.optString("singer"),
                albumName = obj.optString("albumName"),
                albumId = obj.optString("albumId"),
                img = obj.optString("img").takeIf { it.isNotEmpty() && it != "null" },
                durationSec = obj.optInt("durationSec"),
                songmid = obj.optString("songmid"),
                hash = obj.optString("hash"),
                albumMid = obj.optString("albumMid"),
                strMediaMid = obj.optString("strMediaMid"),
                typeHashes = types,
                pyFlag = obj.optString("pyFlag"),
                pyEpId = obj.optString("pyEpId"),
            )
        }

        fun listToJson(tracks: List<MusicTrack>): String {
            val array = JSONArray()
            for (track in tracks) array.put(track.toJson())
            return array.toString()
        }

        fun listFromJson(text: String?): List<MusicTrack> {
            if (text.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(text)
                val list = ArrayList<MusicTrack>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    list.add(fromJson(obj))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
