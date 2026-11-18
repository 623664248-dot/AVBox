package com.github.avbox.osc.music

import android.content.Context
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.SourceBean
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.BoundedCall
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    // Process-lifetime result cache (v1.17.0): homeContent per source
    // slug, and categoryContent page 1 per (slug, classId). Spider
    // activation re-ran these calls on every entry and every source
    // switch, and a music spider's homeContent can take seconds (the
    // Python cold start aside, several spiders fetch and parse whole
    // listing pages), which made re-entering the music tab feel slow.
    // Search, detail, playerContent and pages > 1 are never cached.
    // Every read and write happens inside [serialized] — the bridge
    // mutex — so plain HashMaps are safe here; [clearCacheFor] also
    // goes through the mutex.
    private val homeCache = HashMap<String, HomeResult>()
    private val categoryPage1Cache = HashMap<Pair<String, String>, ListResult>()

    // Disk layer under the process cache (v1.28.0): home and
    // category page-1 results also persist under filesDir, so a
    // cold process paints from disk instantly instead of paying
    // the spider — and the Python cold start — on every app
    // launch. Disk data is served as-is; a background refresh (at
    // most once per key per process) re-fetches live and updates
    // memory + disk for the next entry. Book/song catalogues
    // barely change between launches, and the manual long-press
    // refresh (clearCacheFor) still forces live data.
    private const val DISK_DIR = "py_music_cache"
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskRefreshStarted = HashSet<String>()

    /** Drops one source's cached home / page-1 results (file replaced or deleted). */
    suspend fun clearCacheFor(slug: String) {
        serialized {
            homeCache.remove(slug)
            categoryPage1Cache.keys.removeAll { it.first == slug }
            val context = AppContextHolder.context()
            if (context != null) {
                try {
                    val dir = File(context.filesDir, DISK_DIR)
                    dir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("home_" + safeKey(slug)) ||
                            file.name.startsWith("cat_" + safeKey(slug) + "_")
                        ) {
                            file.delete()
                        }
                    }
                } catch (t: Throwable) {
                    // best-effort; stale disk data is served once at most
                }
            }
        }
    }

    // ------------------------------------------------------------- queries

    suspend fun home(context: Context, def: PyMusicSources.PyDef): HomeResult =
        serialized {
            val memory = homeCache[def.id]
            if (memory != null) {
                memory
            } else {
                val disk = readDiskHome(context, def.id)
                if (disk != null) {
                    homeCache[def.id] = disk
                    scheduleHomeRefresh(context, def)
                    disk
                } else {
                    homeLocked(context, def).also {
                        homeCache[def.id] = it
                        writeDiskHome(context, def.id, it)
                    }
                }
            }
        }

    /** One background live re-fetch of a disk-served home per process. */
    private fun scheduleHomeRefresh(context: Context, def: PyMusicSources.PyDef) {
        synchronized(diskRefreshStarted) {
            if (!diskRefreshStarted.add("home:" + def.id)) return
        }
        val appContext = context.applicationContext
        refreshScope.launch {
            try {
                serialized {
                    val fresh = homeLocked(appContext, def)
                    homeCache[def.id] = fresh
                    writeDiskHome(appContext, def.id, fresh)
                }
            } catch (t: Throwable) {
                // keep serving the disk copy
            }
        }
    }

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
    ): ListResult = serialized {
        if (page == 1) {
            val key = def.id to typeId
            val memory = categoryPage1Cache[key]
            if (memory != null) {
                memory
            } else {
                val disk = readDiskCategory(context, def.id, typeId)
                if (disk != null) {
                    categoryPage1Cache[key] = disk
                    scheduleCategoryRefresh(context, def, typeId)
                    disk
                } else {
                    categoryLocked(context, def, typeId, page).also {
                        categoryPage1Cache[key] = it
                        writeDiskCategory(context, def.id, typeId, it)
                    }
                }
            }
        } else {
            categoryLocked(context, def, typeId, page)
        }
    }

    /** One background live re-fetch of a disk-served page 1 per process. */
    private fun scheduleCategoryRefresh(
        context: Context,
        def: PyMusicSources.PyDef,
        typeId: String,
    ) {
        synchronized(diskRefreshStarted) {
            if (!diskRefreshStarted.add("cat:" + def.id + ":" + typeId)) return
        }
        val appContext = context.applicationContext
        refreshScope.launch {
            try {
                serialized {
                    val fresh = categoryLocked(appContext, def, typeId, 1)
                    categoryPage1Cache[def.id to typeId] = fresh
                    writeDiskCategory(appContext, def.id, typeId, fresh)
                }
            } catch (t: Throwable) {
                // keep serving the disk copy
            }
        }
    }

    // ------------------------------------------------------- disk cache

    /** Filename-safe cache key: plain slugs pass through, the rest hash. */
    private fun safeKey(raw: String): String =
        if (raw.isNotEmpty() && raw.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            raw
        } else {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(raw.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        }

    private fun homeDiskFile(context: Context, slug: String): File =
        File(File(context.filesDir, DISK_DIR), "home_" + safeKey(slug) + ".json")

    private fun categoryDiskFile(context: Context, slug: String, typeId: String): File =
        File(
            File(context.filesDir, DISK_DIR),
            "cat_" + safeKey(slug) + "_" + safeKey(typeId) + ".json",
        )

    private fun readDiskHome(context: Context, slug: String): HomeResult? =
        try {
            val file = homeDiskFile(context, slug)
            if (!file.isFile) {
                null
            } else {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val classes = ArrayList<PyClass>()
                val array = json.optJSONArray("classes")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        classes.add(PyClass(item.optString("id"), item.optString("name")))
                    }
                }
                HomeResult(classes, MusicTrack.listFromJson(json.optString("itemsJson")))
            }
        } catch (t: Throwable) {
            null
        }

    private fun writeDiskHome(context: Context, slug: String, result: HomeResult) {
        try {
            val classes = JSONArray()
            result.classes.forEach { pyClass ->
                classes.put(JSONObject().put("id", pyClass.id).put("name", pyClass.name))
            }
            val json = JSONObject()
                .put("classes", classes)
                .put("itemsJson", MusicTrack.listToJson(result.items))
            val file = homeDiskFile(context, slug)
            file.parentFile?.mkdirs()
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // the disk cache is best-effort
        }
    }

    private fun readDiskCategory(
        context: Context,
        slug: String,
        typeId: String,
    ): ListResult? =
        try {
            val file = categoryDiskFile(context, slug, typeId)
            if (!file.isFile) {
                null
            } else {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                ListResult(
                    MusicTrack.listFromJson(json.optString("itemsJson")),
                    json.optInt("pageCount", 1),
                )
            }
        } catch (t: Throwable) {
            null
        }

    private fun writeDiskCategory(
        context: Context,
        slug: String,
        typeId: String,
        result: ListResult,
    ) {
        try {
            val json = JSONObject()
                .put("itemsJson", MusicTrack.listToJson(result.items))
                .put("pageCount", result.pageCount)
            val file = categoryDiskFile(context, slug, typeId)
            file.parentFile?.mkdirs()
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // the disk cache is best-effort
        }
    }

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
