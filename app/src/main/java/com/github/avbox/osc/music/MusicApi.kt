package com.github.avbox.osc.music

import android.util.Base64
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.net.Http
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** One leaderboard of a platform (see [MusicApi.charts]). */
data class MusicChart(
    val id: String,
    val name: String,
    val cover: String?,
    val updateTip: String,
)

/**
 * Platform search / hot words / lyrics / charts over the platforms' public
 * web APIs (the same endpoints LX Music's musicSdk uses). The app searches
 * natively; LX source scripts only resolve play URLs afterwards.
 */
object MusicApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val PAGE_SIZE = 30

    /** Charts load a single page of this many songs (no pagination). */
    const val CHART_PAGE_SIZE = 50

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

    /** Leaderboards of one platform; kw has none (see chartsKw). */
    suspend fun charts(source: String): List<MusicChart> {
        return try {
            when (source) {
                "kg" -> chartsKg()
                "wy" -> chartsWy()
                "tx" -> chartsTx()
                else -> emptyList()
            }
        } catch (e: Exception) {
            AppLog.e("MusicApi", e)
            emptyList()
        }
    }

    /** First page (up to [CHART_PAGE_SIZE]) of one chart's songs. */
    suspend fun chartSongs(source: String, chartId: String, page: Int): List<MusicTrack> {
        return try {
            when (source) {
                "kg" -> chartSongsKg(chartId, page)
                "wy" -> chartSongsWy(chartId)
                "tx" -> chartSongsTx(chartId)
                else -> emptyList()
            }
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
            parseKgItem(item)?.let { list.add(it) }
        }
        return list
    }

    private fun parseKgItem(item: JSONObject): MusicTrack? {
        val hash = item.optString("hash")
        if (hash.isEmpty()) return null
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
        // Rank items carry no singername; the singer is in authors[] or in
        // the "singer - song" filename prefix there.
        var singer = item.optString("singername")
        if (singer.isEmpty()) {
            val authors = item.optJSONArray("authors")
            if (authors != null) {
                val names = ArrayList<String>()
                for (j in 0 until authors.length()) {
                    val author = authors.optJSONObject(j)?.optString("author_name").orEmpty()
                    if (author.isNotEmpty()) names.add(author)
                }
                singer = names.joinToString("/")
            }
        }
        if (singer.isEmpty() && filename.contains(" - ")) {
            singer = filename.substringBefore(" - ").trim()
        }
        return MusicTrack(
            source = "kg",
            name = decodeEntities(name),
            singer = decodeEntities(singer),
            albumName = decodeEntities(item.optString("album_name")),
            albumId = item.optString("album_id"),
            img = img.replace("{size}", "240").takeIf { it.isNotEmpty() },
            durationSec = item.optInt("duration"),
            songmid = item.optString("audio_id"),
            hash = hash,
            typeHashes = hashes,
        )
    }

    private suspend fun chartsKg(): List<MusicChart> {
        val text = Http.once(
            "http://mobilecdn.kugou.com/api/v3/rank/list?format=json&plat=0",
        ) { headers("User-Agent", UA) }
        val info = JSONObject(text).optJSONObject("data")?.optJSONArray("info")
            ?: return emptyList()
        val list = ArrayList<MusicChart>()
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            val rankId = item.optInt("rankid")
            if (rankId == 0) continue
            val cover = item.optString("imgurl")
                .ifEmpty { item.optString("img_cover") }
                .replace("{size}", "240")
            list.add(
                MusicChart(
                    id = rankId.toString(),
                    name = decodeEntities(item.optString("rankname")),
                    cover = cover.takeIf { it.isNotEmpty() },
                    updateTip = item.optString("update_frequency"),
                ),
            )
        }
        return list
    }

    private suspend fun chartSongsKg(chartId: String, page: Int): List<MusicTrack> {
        val url = "http://mobilecdn.kugou.com/api/v3/rank/song?format=json" +
            "&rankid=" + chartId + "&page=" + page + "&pagesize=" + CHART_PAGE_SIZE + "&showtype=1"
        val text = Http.once(url) { headers("User-Agent", UA) }
        val info = JSONObject(text).optJSONObject("data")?.optJSONArray("info")
            ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until info.length()) {
            val item = info.optJSONObject(i) ?: continue
            parseKgItem(item)?.let { list.add(it) }
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

    // No kw charts: www.kuwo.cn's bang APIs reject unsigned requests
    // ("The request is illegal!", csrf flow included), and the legacy
    // kbangserver host has no chart-list endpoint — probing its content
    // endpoint by bang id returns contradictory chart names across
    // requests, so no reliable chart catalog can be built. charts("kw")
    // stays empty and the page shows its "no charts" empty state.

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
            parseWyItem(item)?.let { list.add(it) }
        }
        return list
    }

    private fun parseWyItem(item: JSONObject): MusicTrack? {
        val id = item.optLong("id")
        if (id == 0L) return null
        val album = item.optJSONObject("album")
        return MusicTrack(
            source = "wy",
            name = item.optString("name"),
            singer = joinNames(item.optJSONArray("artists")),
            albumName = album?.optString("name").orEmpty(),
            albumId = album?.optLong("id")?.toString().orEmpty(),
            img = album?.optString("picUrl")?.takeIf { it.isNotEmpty() },
            durationSec = (item.optLong("duration") / 1000).toInt(),
            songmid = id.toString(),
        )
    }

    private suspend fun chartsWy(): List<MusicChart> {
        val text = Http.once("https://music.163.com/api/toplist") {
            headers("User-Agent", UA)
            headers("Referer", "https://music.163.com/")
        }
        // Some egress IPs get an encrypted {"result":"<hex>"} payload
        // instead of the list; optJSONArray then yields nothing and the
        // page shows its empty state instead of crashing.
        val array = JSONObject(text).optJSONArray("list") ?: return emptyList()
        val list = ArrayList<MusicChart>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            if (id == 0L) continue
            list.add(
                MusicChart(
                    id = id.toString(),
                    name = item.optString("name"),
                    cover = item.optString("coverImgUrl").takeIf { it.isNotEmpty() },
                    updateTip = item.optString("updateFrequency"),
                ),
            )
        }
        return list
    }

    private suspend fun chartSongsWy(chartId: String): List<MusicTrack> {
        val text = Http.once("https://music.163.com/api/playlist/detail?id=" + chartId) {
            headers("User-Agent", UA)
            headers("Referer", "https://music.163.com/")
        }
        // Playlist tracks use the same artists/album/duration shape as
        // search songs (not the ar/al/dt shape of the newer song APIs).
        val tracks = JSONObject(text).optJSONObject("result")?.optJSONArray("tracks")
            ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until tracks.length()) {
            val item = tracks.optJSONObject(i) ?: continue
            parseWyItem(item)?.let { list.add(it) }
            if (list.size >= CHART_PAGE_SIZE) break
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
            parseTxItem(item)?.let { list.add(it) }
        }
        return list
    }

    private fun parseTxItem(item: JSONObject): MusicTrack? {
        val mid = item.optString("mid").ifEmpty { item.optString("songmid") }
        if (mid.isEmpty()) return null
        val album = item.optJSONObject("album")
        val albumMid = album?.optString("mid").orEmpty()
            .ifEmpty { item.optString("albummid") }
        val mediaMid = item.optJSONObject("file")?.optString("media_mid").orEmpty()
            .ifEmpty { item.optString("media_mid") }
            .ifEmpty { item.optString("strMediaMid") }
            .ifEmpty { item.optString("midurlinfo") }
        return MusicTrack(
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
        )
    }

    private suspend fun chartsTx(): List<MusicChart> {
        val text = Http.once(
            "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?format=json",
        ) {
            headers("User-Agent", UA)
            headers("Referer", "https://y.qq.com/")
        }
        val array = JSONObject(text).optJSONObject("data")?.optJSONArray("topList")
            ?: return emptyList()
        val list = ArrayList<MusicChart>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            if (id == 0) continue
            list.add(
                MusicChart(
                    id = id.toString(),
                    name = item.optString("topTitle"),
                    cover = item.optString("picUrl").takeIf { it.isNotEmpty() },
                    updateTip = "",
                ),
            )
        }
        return list
    }

    private suspend fun chartSongsTx(chartId: String): List<MusicTrack> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg" +
            "?topid=" + chartId + "&type=top&format=json"
        val text = Http.once(url) {
            headers("User-Agent", UA)
            headers("Referer", "https://y.qq.com/")
        }
        val array = JSONObject(text).optJSONArray("songlist") ?: return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until array.length()) {
            val data = array.optJSONObject(i)?.optJSONObject("data") ?: continue
            val track = parseTxItem(data) ?: continue
            // Toplist items ship no file.media_mid; source scripts key the
            // media mid off strMediaMid, so fall back to the song mid.
            list.add(
                if (track.strMediaMid.isEmpty()) {
                    track.copy(strMediaMid = track.songmid)
                } else {
                    track
                },
            )
            if (list.size >= CHART_PAGE_SIZE) break
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
