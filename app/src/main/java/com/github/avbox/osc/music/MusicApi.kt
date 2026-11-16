package com.github.avbox.osc.music

import android.util.Base64
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.net.Http
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Platform search / hot words / lyrics over the platforms' public web APIs
 * (the same endpoints LX Music's musicSdk uses). The app searches natively;
 * LX source scripts only resolve play URLs afterwards.
 */
object MusicApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val PAGE_SIZE = 30

    suspend fun search(source: String, keyword: String, page: Int): List<MusicTrack> {
        return try {
            when (source) {
                "kg" -> searchKg(keyword, page)
                "kw" -> searchKw(keyword, page)
                "wy" -> searchWy(keyword, page)
                "tx" -> searchTx(keyword, page)
                else -> emptyList()
            }
        } catch (e: Exception) {
            AppLog.e("MusicApi", e)
            emptyList()
        }
    }

    suspend fun hotWords(): List<String> {
        return try {
            val text = Http.once(
                "http://mobilecdn.kugou.com/api/v3/search/hot?format=json&plat=0&count=30",
            ) { headers("User-Agent", UA) }
            val info = JSONObject(text).optJSONObject("data")?.optJSONArray("info")
                ?: return emptyList()
            val words = ArrayList<String>()
            for (i in 0 until info.length()) {
                val word = info.optJSONObject(i)?.optString("keyword").orEmpty().trim()
                if (word.isNotEmpty()) words.add(word)
            }
            words
        } catch (e: Exception) {
            AppLog.e("MusicApi", e)
            emptyList()
        }
    }

    /** Raw LRC text for the track, or null when the platform has none. */
    suspend fun lyric(track: MusicTrack): String? {
        return try {
            when (track.source) {
                "wy" -> lyricWy(track)
                "tx" -> lyricTx(track)
                "kg" -> lyricKg(track)
                "kw" -> lyricKw(track)
                else -> null
            }
        } catch (e: Exception) {
            AppLog.e("MusicApi", e)
            null
        }
    }

    // ------------------------------------------------------------------ kg

    private suspend fun searchKg(keyword: String, page: Int): List<MusicTrack> {
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json" +
            "&keyword=" + enc(keyword) + "&page=" + page + "&pagesize=" + PAGE_SIZE + "&showtype=1"
        val text = Http.once(url) { headers("User-Agent", UA) }
        val info = JSONObject(text).optJSONObject("data")?.optJSONArray("info")
            ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val hash = item.optString("hash")
            if (hash.isEmpty()) continue
            var name = item.optString("songname")
            val filename = item.optString("filename")
            if (name.isEmpty() && filename.contains(" - ")) {
                name = filename.substringAfter(" - ").trim()
            }
            if (name.isEmpty()) name = filename
            val hashes = LinkedHashMap<String, String>()
            hashes["128k"] = hash
            val hash320 = item.optString("320hash").ifEmpty { item.optString("hqhash") }
            if (hash320.isNotEmpty()) hashes["320k"] = hash320
            val hashFlac = item.optString("sqhash")
            if (hashFlac.isNotEmpty()) hashes["flac"] = hashFlac
            var img = item.optString("img")
                .ifEmpty { item.optString("image") }
                .ifEmpty { item.optString("album_img") }
            if (img.isEmpty()) {
                img = item.optJSONObject("trans_param")?.optString("union_cover").orEmpty()
            }
            list.add(
                MusicTrack(
                    source = "kg",
                    name = decodeEntities(name),
                    singer = decodeEntities(item.optString("singername")),
                    albumName = decodeEntities(item.optString("album_name")),
                    albumId = item.optString("album_id"),
                    img = img.replace("{size}", "240").takeIf { it.isNotEmpty() },
                    durationSec = item.optInt("duration"),
                    songmid = item.optString("audio_id"),
                    hash = hash,
                    typeHashes = hashes,
                ),
            )
        }
        return list
    }

    private suspend fun lyricKg(track: MusicTrack): String? {
        if (track.hash.isEmpty()) return null
        val keyword = enc(track.name + " - " + track.singer)
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=" + keyword + "&hash=" + track.hash
        val searchText = Http.once(searchUrl) { headers("User-Agent", UA) }
        val candidate = JSONObject(searchText).optJSONArray("candidates")?.optJSONObject(0)
            ?: return null
        val id = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (id.isEmpty() || accessKey.isEmpty()) return null
        val downloadUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=" + id +
            "&accesskey=" + accessKey + "&fmt=lrc&charset=utf8"
        val downloadText = Http.once(downloadUrl) { headers("User-Agent", UA) }
        val content = JSONObject(downloadText).optString("content")
        if (content.isEmpty()) return null
        return decodeBase64(content)
    }

    // ------------------------------------------------------------------ kw

    private suspend fun searchKw(keyword: String, page: Int): List<MusicTrack> {
        val url = "http://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword) +
            "&pn=" + (page - 1) + "&rn=" + PAGE_SIZE +
            "&ft=music&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&vipver=1"
        val text = Http.once(url) { headers("User-Agent", UA) }
        val abslist = JSONObject(text).optJSONArray("abslist") ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until abslist.length()) {
            val item = abslist.optJSONObject(i) ?: continue
            val rid = item.optString("MUSICRID").removePrefix("MUSIC_")
            if (rid.isEmpty()) continue
            val picShort = item.optString("web_albumpic_short")
            list.add(
                MusicTrack(
                    source = "kw",
                    name = decodeEntities(item.optString("NAME")),
                    singer = decodeEntities(item.optString("ARTIST")),
                    albumName = decodeEntities(item.optString("ALBUM")),
                    albumId = item.optString("ALBUMID"),
                    img = if (picShort.isNotEmpty()) {
                        "https://img4.kuwo.cn/star/albumcover/500" + picShort
                    } else {
                        null
                    },
                    durationSec = item.optString("DURATION").toIntOrNull() ?: 0,
                    songmid = rid,
                ),
            )
        }
        return list
    }

    private suspend fun lyricKw(track: MusicTrack): String? {
        val text = Http.once("http://newlyric.kuwo.cn/newlyric.lrc?" + track.songmid) {
            headers("User-Agent", UA)
        }
        return text.takeIf { it.contains("[") && it.contains("]") && it.length > 20 }
    }

    // ------------------------------------------------------------------ wy

    private suspend fun searchWy(keyword: String, page: Int): List<MusicTrack> {
        val offset = (page - 1) * PAGE_SIZE
        val url = "https://music.163.com/api/search/get/web?s=" + enc(keyword) +
            "&type=1&offset=" + offset + "&limit=" + PAGE_SIZE + "&total=true"
        val text = Http.once(url) {
            headers("User-Agent", UA)
            headers("Referer", "https://music.163.com/")
        }
        val songs = JSONObject(text).optJSONObject("result")?.optJSONArray("songs")
            ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            if (id == 0L) continue
            val album = item.optJSONObject("album")
            list.add(
                MusicTrack(
                    source = "wy",
                    name = item.optString("name"),
                    singer = joinNames(item.optJSONArray("artists")),
                    albumName = album?.optString("name").orEmpty(),
                    albumId = album?.optLong("id")?.toString().orEmpty(),
                    img = album?.optString("picUrl")?.takeIf { it.isNotEmpty() },
                    durationSec = (item.optLong("duration") / 1000).toInt(),
                    songmid = id.toString(),
                ),
            )
        }
        return list
    }

    private suspend fun lyricWy(track: MusicTrack): String? {
        val url = "https://music.163.com/api/song/lyric?id=" + track.songmid + "&lv=1&kv=1&tv=-1"
        val text = Http.once(url) {
            headers("User-Agent", UA)
            headers("Referer", "https://music.163.com/")
        }
        val lyric = JSONObject(text).optJSONObject("lrc")?.optString("lyric").orEmpty()
        return lyric.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ tx

    private suspend fun searchTx(keyword: String, page: Int): List<MusicTrack> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&new_json=1" +
            "&w=" + enc(keyword) + "&p=" + page + "&n=" + PAGE_SIZE +
            "&catZhida=0&lossless=0&flag_qc=0&cr=1"
        val text = Http.once(url) {
            headers("User-Agent", UA)
            headers("Referer", "https://y.qq.com/")
        }
        val songs = JSONObject(text).optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val mid = item.optString("mid").ifEmpty { item.optString("songmid") }
            if (mid.isEmpty()) continue
            val album = item.optJSONObject("album")
            val albumMid = album?.optString("mid").orEmpty()
                .ifEmpty { item.optString("albummid") }
            val mediaMid = item.optJSONObject("file")?.optString("media_mid").orEmpty()
                .ifEmpty { item.optString("media_mid") }
                .ifEmpty { item.optString("strMediaMid") }
            list.add(
                MusicTrack(
                    source = "tx",
                    name = item.optString("name")
                        .ifEmpty { item.optString("title") }
                        .ifEmpty { item.optString("songname") },
                    singer = joinNames(item.optJSONArray("singer")),
                    albumName = album?.optString("name").orEmpty()
                        .ifEmpty { item.optString("albumname") },
                    albumId = albumMid,
                    img = if (albumMid.isNotEmpty()) {
                        "https://y.gtimg.cn/music/photo_new/T002R500x500M000" + albumMid + ".jpg"
                    } else {
                        null
                    },
                    durationSec = item.optInt("interval"),
                    songmid = mid,
                    albumMid = albumMid,
                    strMediaMid = mediaMid,
                ),
            )
        }
        return list
    }

    private suspend fun lyricTx(track: MusicTrack): String? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" +
            track.songmid + "&format=json&nobase64=0&g_tk=5381"
        val text = Http.once(url) {
            headers("User-Agent", UA)
            headers("Referer", "https://y.qq.com/portal/player.html")
        }
        val encoded = JSONObject(text).optString("lyric")
        if (encoded.isEmpty()) return null
        return decodeBase64(encoded)
    }

    // ------------------------------------------------------------------ misc

    private fun joinNames(array: JSONArray?): String {
        if (array == null) return ""
        val names = ArrayList<String>()
        for (i in 0 until array.length()) {
            val name = array.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty()) names.add(name)
        }
        return names.joinToString("/")
    }

    private fun decodeEntities(text: String): String {
        if (!text.contains("&")) return text
        return text
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .trim()
    }

    private fun decodeBase64(content: String): String? {
        return try {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
