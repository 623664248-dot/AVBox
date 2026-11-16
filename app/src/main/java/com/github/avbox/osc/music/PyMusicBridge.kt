package com.github.avbox.osc.music

import android.content.Context
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.SourceBean
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.util.BoundedCall
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Drives py music spiders through the SAME runtime the video side uses
 * for py sites — no second Python engine:
 *
 *  - A minimal [SourceBean] (type 3, key "pymusic_<id>", ext "") whose
 *    api is the spider file's absolute path as a file:// URL is handed to
 *    [ApiConfig.getCSP], which dispatches api strings containing ".py"
 *    to the pyramid/Chaquopy pyLoader (SpiderLoader -> pyLoader ->
 *    PythonLoader -> PythonSpider). pyramid's downloadPlugin uses
 *    file:// paths directly, so no config file or download is involved.
 *  - Every spider call runs on Dispatchers.IO inside [BoundedCall] with
 *    [SpiderReaper.track], exactly like DetailLoader/PlayLoader do.
 *  - Results are the standard TVBox JSON shapes (class/list for home and
 *    category, vod_play_from/vod_play_url for detail, parse/jx/url/header
 *    for player), mapped onto [MusicTrack].
 *
 * The pyLoader caches spider instances by key, so repeat calls for one
 * source reuse the loaded module; a video-config switch clears that
 * cache (pyLoader.clear), after which the next call simply re-creates
 * the spider through getCSP again.
 *
 * Every entry point is serialised through one bridge-wide Mutex
 * ([serialized]): the music page and the player resolve through the
 * SAME cached spider instance, and the shared Chaquopy interpreter
 * wedges when several threads enter it at once. v1.13.0 field tests
 * matched that failure mode exactly — home/search/detail (issued one
 * at a time) answered, while category and player calls overlapped
 * with other calls and hung until their timeout.
 */
object PyMusicBridge {

    /** Generic spider failure (load error, empty/garbled answer, timeout). */
    class PyException(message: String) : Exception(message)

    /** playerContent asked for an external parser (parse=1 / jx=1). */
    class PyParseRequiredException : Exception("parse required")

    data class PyClass(val id: String, val name: String)

    data class HomeResult(val classes: List<PyClass>, val items: List<MusicTrack>)

    data class ListResult(val items: List<MusicTrack>, val pageCount: Int)

    data class Resolved(val url: String, val headers: Map<String, String>)

    // The first call also pays the Python cold start (pyramid allows the
    // spider init up to 30 s internally). List/detail calls get 45 s and
    // playerContent 60 s: resolver spiders poll several fallback APIs in
    // sequence before answering (netease_music rotates through half a
    // dozen), which regularly overran the old flat 25 s budget (v1.13.1).
    private const val TIMEOUT_FIRST_MS = 45_000L
    private const val TIMEOUT_MS = 45_000L
    private const val TIMEOUT_PLAYER_MS = 60_000L

    /** One spider call at a time across the whole bridge; see class doc. */
    private val callMutex = Mutex()

    private suspend fun <T> serialized(block: suspend () -> T): T =
        callMutex.withLock { block() }

    // ------------------------------------------------------------- queries

    suspend fun home(context: Context, def: PyMusicSources.PyDef): HomeResult =
        serialized { homeLocked(context, def) }

    private suspend fun homeLocked(context: Context, def: PyMusicSources.PyDef): HomeResult =
        withContext(Dispatchers.IO) {
            val raw = callSpider(context, def, TIMEOUT_FIRST_MS, "pymusic-home") {
                it.homeContent(false)
            }
            val json = parseObject(raw)
            val classes = ArrayList<PyClass>()
            val classArray = json.optJSONArray("class")
            if (classArray != null) {
                for (i in 0 until classArray.length()) {
                    val item = classArray.optJSONObject(i) ?: continue
                    val typeId = item.optString("type_id")
                    val typeName = item.optString("type_name")
                    if (typeId.isEmpty()) continue
                    classes.add(PyClass(typeId, typeName.ifEmpty { typeId }))
                }
            }
            HomeResult(classes, parseVodList(def, json.optJSONArray("list")))
        }

    suspend fun category(
        context: Context,
        def: PyMusicSources.PyDef,
        typeId: String,
        page: Int,
    ): ListResult = serialized { categoryLocked(context, def, typeId, page) }

    private suspend fun categoryLocked(
        context: Context,
        def: PyMusicSources.PyDef,
        typeId: String,
        page: Int,
    ): ListResult = withContext(Dispatchers.IO) {
        val raw = callSpider(context, def, TIMEOUT_MS, "pymusic-category") {
            it.categoryContent(typeId, page.toString(), false, null)
        }
        parseListResult(def, raw)
    }

    suspend fun search(
        context: Context,
        def: PyMusicSources.PyDef,
        keyword: String,
        page: Int,
    ): ListResult = serialized { searchLocked(context, def, keyword, page) }

    private suspend fun searchLocked(
        context: Context,
        def: PyMusicSources.PyDef,
        keyword: String,
        page: Int,
    ): ListResult = withContext(Dispatchers.IO) {
        val raw = callSpider(context, def, TIMEOUT_MS, "pymusic-search") {
            it.searchContent(keyword, false, page.toString())
        }
        parseListResult(def, raw)
    }

    /**
     * All playable episodes of [track]'s vod, across every play flag, as
     * queue-ready tracks carrying pyFlag/pyEpId. A single-song vod yields
     * exactly one episode.
     */
    suspend fun detailEpisodes(
        context: Context,
        def: PyMusicSources.PyDef,
        track: MusicTrack,
    ): List<MusicTrack> = serialized { detailEpisodesLocked(context, def, track) }

    private suspend fun detailEpisodesLocked(
        context: Context,
        def: PyMusicSources.PyDef,
        track: MusicTrack,
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val raw = callSpider(context, def, TIMEOUT_MS, "pymusic-detail") {
            it.detailContent(listOf(track.songmid))
        }
        val json = parseObject(raw)
        val list = json.optJSONArray("list")
        val vod = if (list != null && list.length() > 0) list.optJSONObject(0) else null
        if (vod == null) throw PyException("详情为空")
        val vodName = vod.optString("vod_name").ifEmpty { track.name }
        val vodPic = vod.optString("vod_pic").ifEmpty { track.img ?: "" }
        val remarks = vod.optString("vod_remarks").ifEmpty { track.singer }
        val flags = vod.optString("vod_play_from").split("$$$")
        val urlGroups = vod.optString("vod_play_url").split("$$$")
        val episodes = ArrayList<MusicTrack>()
        for (groupIndex in urlGroups.indices) {
            val flag = flags.getOrNull(groupIndex)?.trim().orEmpty()
            val group = urlGroups[groupIndex]
            if (group.isBlank()) continue
            for (episode in group.split("#")) {
                if (episode.isBlank()) continue
                val splitAt = episode.indexOf('$')
                val epName = if (splitAt >= 0) episode.substring(0, splitAt).trim() else ""
                val epId = if (splitAt >= 0) episode.substring(splitAt + 1).trim() else episode.trim()
                if (epId.isEmpty()) continue
                episodes.add(
                    track.copy(
                        name = epName.ifEmpty { vodName },
                        singer = if (epName.isNotEmpty() && epName != vodName) vodName else remarks,
                        img = vodPic.ifEmpty { null },
                        pyFlag = flag,
                        pyEpId = epId,
                    ),
                )
            }
        }
        if (episodes.isEmpty()) throw PyException("没有可播放的剧集")
        episodes
    }

    /**
     * Resolves the direct play URL for [track]. Episode tracks (pyEpId
     * set) go straight to playerContent; plain list rows first take the
     * detail's first episode (lazy per-track resolution). Only parse=0
     * direct links are returned; parse/jx answers raise
     * [PyParseRequiredException] instead of being force-played.
     */
    suspend fun resolve(
        context: Context,
        def: PyMusicSources.PyDef,
        track: MusicTrack,
    ): Resolved = serialized { resolveLocked(context, def, track) }

    private suspend fun resolveLocked(
        context: Context,
        def: PyMusicSources.PyDef,
        track: MusicTrack,
    ): Resolved = withContext(Dispatchers.IO) {
        val flag: String
        val epId: String
        if (track.pyEpId.isNotEmpty()) {
            flag = track.pyFlag
            epId = track.pyEpId
        } else {
            // Already inside the mutex: the Locked variant must be used
            // here, the public one would deadlock re-entering it.
            val first = detailEpisodesLocked(context, def, track).first()
            flag = first.pyFlag
            epId = first.pyEpId
        }
        val raw = callSpider(context, def, TIMEOUT_PLAYER_MS, "pymusic-player") {
            it.playerContent(flag, epId, ApiConfig.get().vipParseFlags)
        }
        parsePlayerResult(raw)
    }

    // ------------------------------------------------------------- internals

    /**
     * Builds the minimal SourceBean the shared runtime needs: getCSP only
     * reads key/api/ext for py dispatch. The spider instance itself is
     * cached by the pyLoader under this key.
     */
    private fun beanFor(context: Context, def: PyMusicSources.PyDef): SourceBean {
        val file = PyMusicSources.resolveFile(context, def)
        val bean = SourceBean()
        bean.key = "pymusic_" + def.id.replace(Regex("[^A-Za-z0-9_]"), "_")
        bean.name = def.name
        bean.api = "file://" + file.absolutePath
        bean.type = 3
        bean.ext = ""
        return bean
    }

    /**
     * One bounded spider call, video-side style. BoundedCall swallows
     * failures into null and a failed py load surfaces as a SpiderNull
     * whose methods return "", so null/blank both mean failure here.
     */
    private fun callSpider(
        context: Context,
        def: PyMusicSources.PyDef,
        timeoutMs: Long,
        tag: String,
        block: (Spider) -> String?,
    ): String {
        val raw = BoundedCall.call(
            Callable<String?> {
                val spider = ApiConfig.get().getCSP(beanFor(context, def))
                SpiderReaper.track(spider) { block(spider) }
            },
            timeoutMs,
            tag,
        )
        if (raw.isNullOrBlank()) {
            throw PyException("爬虫加载失败或无响应")
        }
        return raw
    }

    private fun parseObject(raw: String): JSONObject {
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw PyException("爬虫返回格式异常")
        }
    }

    private fun parseListResult(def: PyMusicSources.PyDef, raw: String): ListResult {
        val json = parseObject(raw)
        return ListResult(
            items = parseVodList(def, json.optJSONArray("list")),
            pageCount = json.optInt("pagecount", 0),
        )
    }

    private fun parseVodList(
        def: PyMusicSources.PyDef,
        array: JSONArray?,
    ): List<MusicTrack> {
        if (array == null) return emptyList()
        val list = ArrayList<MusicTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val vodId = item.optString("vod_id")
            val vodName = item.optString("vod_name")
            if (vodId.isEmpty() && vodName.isEmpty()) continue
            list.add(
                MusicTrack(
                    source = MusicTrack.PY_SOURCE_PREFIX + def.id,
                    name = vodName,
                    singer = item.optString("vod_remarks"),
                    albumName = "",
                    albumId = "",
                    img = item.optString("vod_pic").ifEmpty { null },
                    durationSec = 0,
                    songmid = vodId,
                ),
            )
        }
        return list
    }

    private fun parsePlayerResult(raw: String): Resolved {
        val json = parseObject(raw)
        var url = ""
        when (val urlValue = json.opt("url")) {
            is String -> url = urlValue
            is JSONArray -> {
                // TVBox array form alternates display names and URLs;
                // take the first entry that looks like a URL.
                for (i in 0 until urlValue.length()) {
                    val candidate = urlValue.optString(i)
                    if (candidate.contains("://")) {
                        url = candidate
                        break
                    }
                }
                if (url.isEmpty() && urlValue.length() > 0) {
                    url = urlValue.optString(0)
                }
            }
        }
        if (json.optInt("parse", 0) == 1 || json.optInt("jx", 0) == 1 ||
            url.startsWith("video://")
        ) {
            throw PyParseRequiredException()
        }
        if (url.startsWith("proxy://")) {
            throw PyException("暂不支持 proxy 播放地址")
        }
        if (url.isEmpty()) {
            throw PyException("爬虫未返回播放地址")
        }
        val headers = LinkedHashMap<String, String>()
        val headerObj = json.optJSONObject("header")
        if (headerObj != null) {
            val keys = headerObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headerObj.optString(key)
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    headers[key] = value
                }
            }
        }
        return Resolved(url, headers)
    }
}
