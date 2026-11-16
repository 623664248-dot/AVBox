package com.github.avbox.osc.music

import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Host for LX Music custom-source scripts on the repo's QuickJS engine.
 *
 * Mirrors how drpy spiders are run (see catvod JsSpider/Global): one
 * dedicated thread owns the QuickJS context, every JS call happens on it,
 * and network callbacks are posted back onto that thread before touching JS.
 *
 * The bridge injects globalThis.lx (version 2.2.0, env mobile):
 * send/on/request are native, utils (buffer + crypto) is a JS prelude on
 * top of two small native helpers (md5, aes). The app searches natively
 * (MusicApi); scripts only resolve play URLs through the request handler.
 */
object LxBridge {

    class LxException(message: String) : Exception(message)

    /**
     * A loadable source script. Since v1.15.0 only user-imported scripts
     * exist ([filePath], absolute path in the app's private storage); the
     * bundled lx_sources assets are no longer registered anywhere.
     * [version] comes from the import metadata in [LxUserScripts].
     * [assetPath] is kept for the loader's asset branch but no current
     * def uses it.
     */
    data class ScriptDef(
        val id: String,
        val assetPath: String?,
        val fallbackName: String,
        val filePath: String? = null,
        val version: String = "",
    )

    data class ScriptInfo(
        val name: String,
        val version: String,
        val description: String,
        val author: String,
    )

    data class SourceInfo(
        val code: String,
        val name: String,
        val actions: List<String>,
        val qualitys: List<String>,
    )

    private const val LX_VERSION = "2.2.0"
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lx-bridge").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var ctx: QuickJSContext? = null

    @Volatile
    private var requestHandler: JSFunction? = null

    @Volatile
    private var initedDeferred: CompletableDeferred<Map<String, SourceInfo>>? = null

    @Volatile
    var loadedScriptId: String? = null
        private set

    @Volatile
    var scriptInfo: ScriptInfo? = null
        private set

    @Volatile
    var sources: Map<String, SourceInfo> = emptyMap()
        private set

    private val timers = ConcurrentHashMap<Int, Pair<ScheduledFuture<*>, JSFunction>>()
    private val timerSeq = AtomicInteger(1)

    // ------------------------------------------------------------- lifecycle

    /**
     * (Re)loads a source script (built-in asset or user-imported file) in a
     * fresh QuickJS context. Returns once the script text has been
     * evaluated; the script's send('inited') may arrive later (some scripts
     * check their servers first) — use [awaitInited] for the source
     * registry.
     */
    suspend fun loadScript(def: ScriptDef): ScriptInfo {
        val context = AppContextHolder.context()
            ?: throw LxException("app context not ready")
        val raw = try {
            val filePath = def.filePath
            if (filePath != null) {
                java.io.File(filePath).readText(Charsets.UTF_8)
            } else {
                val assetPath = def.assetPath ?: throw LxException("script path missing")
                context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: LxException) {
            throw e
        } catch (e: Exception) {
            throw LxException("cannot read script: " + (e.message ?: ""))
        }
        val scriptText = raw.removePrefix("\uFEFF")
        val info = parseScriptInfo(scriptText, def.fallbackName)
        withContext(dispatcher) {
            teardownLocked()
            val created = QuickJSContext.create()
            ctx = created
            try {
                installLocked(created, info)
                created.evaluate(scriptText)
            } catch (e: Throwable) {
                AppLog.e("LxBridge", e)
                teardownLocked()
                throw LxException(e.message ?: "script load failed")
            }
            loadedScriptId = def.id
            scriptInfo = info
        }
        return info
    }

    /** Waits for the loaded script's send('inited') source registry. */
    suspend fun awaitInited(timeoutMs: Long = 30_000L): Map<String, SourceInfo> {
        val deferred = initedDeferred ?: throw LxException("no script loaded")
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: throw LxException("音源脚本初始化超时")
        sources = result
        return result
    }

    /**
     * Resolves a play URL through the script's request handler:
     * handler({source, action: 'musicUrl', info: {type, musicInfo}}).
     * Tries [quality] first, then falls back to 128k.
     */
    suspend fun getMusicUrl(track: MusicTrack, quality: String = "320k"): String {
        var lastError: String? = null
        val first = tryMusicUrl(track, quality) { lastError = it }
        if (first != null) {
            resolvedQuality = quality
            return first
        }
        if (quality != "128k") {
            val fallback = tryMusicUrl(track, "128k") { lastError = it }
            if (fallback != null) {
                resolvedQuality = "128k"
                return fallback
            }
        }
        throw LxException(lastError ?: "获取播放链接失败")
    }

    @Volatile
    private var resolvedQuality: String? = null

    /** Quality that produced the most recent successful [getMusicUrl]. */
    fun lastResolvedQuality(): String? = resolvedQuality

    private suspend fun tryMusicUrl(
        track: MusicTrack,
        quality: String,
        onError: (String) -> Unit,
    ): String? {
        val deferred = CompletableDeferred<String>()
        withContext(dispatcher) {
            val context = ctx ?: run {
                deferred.completeExceptionally(LxException("音源脚本未加载"))
                return@withContext
            }
            val handler = requestHandler ?: run {
                deferred.completeExceptionally(LxException("音源脚本未就绪"))
                return@withContext
            }
            try {
                val info = context.createNewJSObject()
                context.setProperty(info, "type", quality)
                context.setProperty(info, "musicInfo", jsonToJs(context, track.toMusicInfo()))
                val data = context.createNewJSObject()
                context.setProperty(data, "source", track.source)
                context.setProperty(data, "action", "musicUrl")
                context.setProperty(data, "info", info)
                val result = handler.call(data)
                when (result) {
                    is String -> deferred.complete(result)
                    is JSObject -> attachPromise(result, deferred)
                    null -> deferred.completeExceptionally(LxException("音源无响应"))
                    else -> deferred.complete(result.toString())
                }
            } catch (e: Throwable) {
                deferred.completeExceptionally(LxException(e.message ?: "音源调用失败"))
            }
        }
        return try {
            val url = withTimeoutOrNull(30_000L) { deferred.await() }
            if (url == null) onError("音源解析超时")
            url
        } catch (e: LxException) {
            AppLog.i("LxBridge musicUrl failed: " + e.message)
            onError(e.message ?: "音源调用失败")
            null
        }
    }

    private fun attachPromise(promise: JSObject, deferred: CompletableDeferred<String>) {
        val success = object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                val value = args.getOrNull(0)
                if (value is String && value.isNotEmpty()) {
                    deferred.complete(value)
                } else if (value != null) {
                    deferred.complete(value.toString())
                } else {
                    deferred.completeExceptionally(LxException("音源返回为空"))
                }
                return null
            }
        }
        val failure = object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                deferred.completeExceptionally(LxException(jsErrorText(args.getOrNull(0))))
                return null
            }
        }
        try {
            val thenFn = promise.getJSFunction("then")
            if (thenFn == null) {
                deferred.completeExceptionally(LxException("音源返回异常"))
                return
            }
            thenFn.call(success)
            thenFn.release()
            val catchFn = promise.getJSFunction("catch")
            catchFn?.call(failure)
            catchFn?.release()
        } catch (e: Throwable) {
            deferred.completeExceptionally(LxException(e.message ?: "音源调用失败"))
        }
    }

    // ------------------------------------------------------------- JS install

    private fun teardownLocked() {
        for ((_, pair) in timers) {
            pair.first.cancel(false)
            try {
                pair.second.release()
            } catch (ignored: Throwable) {
            }
        }
        timers.clear()
        try {
            requestHandler?.release()
        } catch (ignored: Throwable) {
        }
        requestHandler = null
        try {
            ctx?.destroy()
        } catch (ignored: Throwable) {
        }
        ctx = null
        initedDeferred = null
        sources = emptyMap()
    }

    private fun installLocked(context: QuickJSContext, info: ScriptInfo) {
        context.setConsole(object : QuickJSContext.Console {
            override fun log(s: String?) {
                AppLog.i("echo-lx " + s)
            }

            override fun info(s: String?) {
                AppLog.i("echo-lx " + s)
            }

            override fun warn(s: String?) {
                AppLog.i("echo-lx-warn " + s)
            }

            override fun error(s: String?) {
                AppLog.i("echo-lx-error " + s)
            }
        })

        val lx = context.createNewJSObject()
        context.setProperty(context.globalObject, "lx", lx)
        context.setProperty(lx, "version", LX_VERSION)
        context.setProperty(lx, "env", "mobile")

        val eventNames = context.createNewJSObject()
        context.setProperty(eventNames, "inited", "inited")
        context.setProperty(eventNames, "request", "request")
        context.setProperty(eventNames, "updateAlert", "updateAlert")
        context.setProperty(lx, "EVENT_NAMES", eventNames)

        context.setProperty(lx, "send", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                handleSend(args.getOrNull(0) as? String, args.getOrNull(1))
                return null
            }
        })
        context.setProperty(lx, "on", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                val name = args.getOrNull(0) as? String
                val fn = args.getOrNull(1) as? JSFunction
                if (name == "request" && fn != null) {
                    try {
                        requestHandler?.release()
                    } catch (ignored: Throwable) {
                    }
                    fn.hold()
                    requestHandler = fn
                }
                return null
            }
        })
        context.setProperty(lx, "request", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                handleRequest(args)
                return null
            }
        })
        context.setProperty(lx, "__md5", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                val text = args.getOrNull(0)?.toString() ?: return ""
                return md5Hex(text)
            }
        })
        context.setProperty(lx, "__aes", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                val json = args.getOrNull(0) as? String ?: return ""
                return aesJson(json)
            }
        })

        context.setProperty(context.globalObject, "setTimeout", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                return scheduleTimer(args, repeat = false)
            }
        })
        context.setProperty(context.globalObject, "setInterval", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                return scheduleTimer(args, repeat = true)
            }
        })
        context.setProperty(context.globalObject, "clearTimeout", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                clearTimer(args.getOrNull(0))
                return null
            }
        })
        context.setProperty(context.globalObject, "clearInterval", object : JSCallFunction {
            override fun call(vararg args: Any?): Any? {
                clearTimer(args.getOrNull(0))
                return null
            }
        })

        initedDeferred = CompletableDeferred()
        context.evaluate(PRELUDE)

        val infoObj = context.createNewJSObject()
        context.setProperty(infoObj, "name", info.name)
        context.setProperty(infoObj, "description", info.description)
        context.setProperty(infoObj, "version", info.version)
        context.setProperty(infoObj, "author", info.author)
        context.setProperty(infoObj, "homepage", "")
        context.setProperty(lx, "currentScriptInfo", infoObj)
    }

    private fun handleSend(name: String?, data: Any?) {
        if (name != "inited") {
            if (name == "updateAlert") AppLog.i("echo-lx updateAlert ignored")
            return
        }
        val deferred = initedDeferred ?: return
        if (deferred.isCompleted) return
        val map = LinkedHashMap<String, SourceInfo>()
        try {
            val obj = data as? JSObject
            val json = if (obj != null) JSONObject(obj.stringify()) else JSONObject()
            val sourcesJson = json.optJSONObject("sources") ?: JSONObject()
            val keys = sourcesJson.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val entry = sourcesJson.optJSONObject(code) ?: continue
                val actions = ArrayList<String>()
                val actionArray = entry.optJSONArray("actions")
                if (actionArray != null) {
                    for (i in 0 until actionArray.length()) actions.add(actionArray.optString(i))
                }
                val qualitys = ArrayList<String>()
                val qualityArray = entry.optJSONArray("qualitys")
                if (qualityArray != null) {
                    for (i in 0 until qualityArray.length()) qualitys.add(qualityArray.optString(i))
                }
                map[code] = SourceInfo(
                    code = code,
                    name = entry.optString("name").ifEmpty { code },
                    actions = actions,
                    qualitys = qualitys,
                )
            }
        } catch (e: Throwable) {
            AppLog.e("LxBridge", e)
        }
        deferred.complete(map)
    }

    // ------------------------------------------------------------- lx.request

    private fun handleRequest(args: Array<out Any?>) {
        val url = args.getOrNull(0) as? String
        val maybeOptions = args.getOrNull(1)
        val callback = (args.getOrNull(2) as? JSFunction)
            ?: (maybeOptions as? JSFunction)
        if (url.isNullOrEmpty() || callback == null) return
        callback.hold()

        val options = (maybeOptions as? JSObject)?.let {
            try {
                JSONObject(it.stringify())
            } catch (e: Throwable) {
                null
            }
        } ?: JSONObject()

        val owner = ctx
        val request = try {
            buildRequest(url, options)
        } catch (e: Throwable) {
            deliverError(owner, callback, e.message ?: "bad request")
            return
        }
        val timeoutMs = options.optLong("timeout", 15_000L).coerceIn(1_000L, 60_000L)
        val base = HttpClients.defaultClient ?: OkHttpClient()
        val client = base.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                deliverError(owner, callback, e.message ?: "network error")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val bytes = it.body?.bytes() ?: ByteArray(0)
                        if (bytes.size > MAX_BODY_BYTES) {
                            deliverError(owner, callback, "response too large")
                            return
                        }
                        val text = String(bytes, Charsets.UTF_8)
                        val headers = JSONObject()
                        for ((name, value) in it.headers) {
                            headers.put(name, value)
                            headers.put(name.lowercase(), value)
                        }
                        deliverSuccess(owner, callback, it.code, headers, text)
                    }
                } catch (e: Throwable) {
                    deliverError(owner, callback, e.message ?: "read error")
                }
            }
        })
    }

    private fun buildRequest(url: String, options: JSONObject): Request {
        val builder = Request.Builder().url(url)
        val headersJson = options.optJSONObject("headers")
        var contentType = "application/json; charset=utf-8"
        if (headersJson != null) {
            val keys = headersJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headersJson.optString(key)
                builder.header(key, value)
                if (key.equals("Content-Type", ignoreCase = true)) contentType = value
            }
        }
        val method = options.optString("method", "GET").uppercase()
        val bodyText = when (val body = options.opt("body")) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                if (contentType.contains("x-www-form-urlencoded")) {
                    formEncode(body)
                } else {
                    body.toString()
                }
            }
            is JSONArray -> body.toString()
            else -> body.toString()
        }
        if (method == "GET" || method == "HEAD") {
            builder.method(method, null)
        } else {
            val media = contentType.toMediaTypeOrNull()
            builder.method(method, (bodyText ?: "").toRequestBody(media))
        }
        return builder.build()
    }

    private fun formEncode(body: JSONObject): String {
        val parts = ArrayList<String>()
        val keys = body.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = body.optString(key)
            parts.add(
                java.net.URLEncoder.encode(key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(value, "UTF-8"),
            )
        }
        return parts.joinToString("&")
    }

    private fun deliverSuccess(
        owner: QuickJSContext?,
        callback: JSFunction,
        code: Int,
        headers: JSONObject,
        text: String,
    ) {
        executor.execute {
            // The script may have been reloaded (context destroyed) while the
            // request was in flight; never touch JS of a dead context.
            if (owner == null || ctx !== owner) {
                try {
                    callback.release()
                } catch (ignored: Throwable) {
                }
                return@execute
            }
            try {
                val bodyValue: Any = parseBody(owner, text)
                val resp = owner.createNewJSObject()
                owner.setProperty(resp, "statusCode", code)
                owner.setProperty(resp, "headers", jsonToJs(owner, headers))
                owner.setProperty(resp, "body", bodyValue)
                callback.call(null, resp, bodyValue)
            } catch (e: Throwable) {
                AppLog.e("LxBridge", e)
            } finally {
                try {
                    callback.release()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun deliverError(owner: QuickJSContext?, callback: JSFunction, message: String) {
        executor.execute {
            if (owner == null || ctx !== owner) {
                try {
                    callback.release()
                } catch (ignored: Throwable) {
                }
                return@execute
            }
            try {
                val error = owner.createNewJSObject()
                owner.setProperty(error, "message", message)
                callback.call(error, null, null)
            } catch (e: Throwable) {
                AppLog.e("LxBridge", e)
            } finally {
                try {
                    callback.release()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun parseBody(context: QuickJSContext, text: String): Any {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val parsed: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                // jsonToJs is nullable-typed, but a JSONObject/JSONArray input
                // never converts to null; fall back to the raw text if it did.
                return jsonToJs(context, parsed) ?: text
            } catch (ignored: Throwable) {
                // fall through to raw text
            }
        }
        return text
    }

    // ------------------------------------------------------------- timers

    private fun scheduleTimer(args: Array<out Any?>, repeat: Boolean): Int {
        val fn = args.getOrNull(0) as? JSFunction ?: return 0
        val delay = (args.getOrNull(1) as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
        fn.hold()
        val id = timerSeq.getAndIncrement()
        val task = Runnable {
            try {
                fn.call()
            } catch (e: Throwable) {
                AppLog.e("LxBridge", e)
            }
            if (!repeat) {
                timers.remove(id)
                try {
                    fn.release()
                } catch (ignored: Throwable) {
                }
            }
        }
        val future = if (repeat) {
            executor.scheduleWithFixedDelay(task, delay, delay.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } else {
            executor.schedule(task, delay, TimeUnit.MILLISECONDS)
        }
        timers[id] = future to fn
        return id
    }

    private fun clearTimer(idValue: Any?) {
        val id = (idValue as? Number)?.toInt() ?: return
        val pair = timers.remove(id) ?: return
        if (pair.first.cancel(false)) {
            try {
                pair.second.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------- conversion

    private fun jsonToJs(context: QuickJSContext, value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val obj = context.createNewJSObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    context.setProperty(obj, key, jsonToJs(context, value.opt(key)))
                }
                obj
            }
            is JSONArray -> {
                val array = context.createNewJSArray()
                for (i in 0 until value.length()) {
                    array.set(jsonToJs(context, value.opt(i)), i)
                }
                array
            }
            else -> value
        }
    }

    private fun jsErrorText(value: Any?): String {
        return when (value) {
            null -> "音源调用失败"
            is String -> value.ifEmpty { "音源调用失败" }
            is JSObject -> {
                try {
                    val message = ctx?.getProperty(value, "message")?.toString()
                    if (!message.isNullOrEmpty() && message != "null") {
                        message
                    } else {
                        value.stringify().take(200)
                    }
                } catch (e: Throwable) {
                    "音源调用失败"
                }
            }
            else -> value.toString()
        }
    }

    // ------------------------------------------------------------- crypto

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    /** JSON in/out AES helper for the prelude: {op, mode, data[], key[], iv[]}. */
    private fun aesJson(json: String): String {
        return try {
            val obj = JSONObject(json)
            val modeParts = obj.optString("mode", "aes-128-cbc").split("-")
            val blockMode = if (modeParts.getOrNull(2)?.equals("ecb", true) == true) "ECB" else "CBC"
            val key = jsonArrayToBytes(obj.optJSONArray("key"))
            val iv = jsonArrayToBytes(obj.optJSONArray("iv"))
            val data = jsonArrayToBytes(obj.optJSONArray("data"))
            val cipher = Cipher.getInstance("AES/" + blockMode + "/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            val encrypt = obj.optString("op") == "encrypt"
            if (blockMode == "CBC") {
                cipher.init(
                    if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    keySpec,
                    IvParameterSpec(iv),
                )
            } else {
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
            }
            val out = cipher.doFinal(data)
            val array = JSONArray()
            for (b in out) array.put(b.toInt() and 0xFF)
            array.toString()
        } catch (e: Throwable) {
            AppLog.e("LxBridge", e)
            ""
        }
    }

    private fun jsonArrayToBytes(array: JSONArray?): ByteArray {
        if (array == null) return ByteArray(0)
        val bytes = ByteArray(array.length())
        for (i in 0 until array.length()) {
            bytes[i] = (array.optInt(i) and 0xFF).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------- header parse

    /** Header parse shared with the user-script importer (LxUserScripts). */
    fun parseScriptInfoOf(scriptText: String, fallbackName: String): ScriptInfo =
        parseScriptInfo(scriptText.removePrefix("\uFEFF"), fallbackName)

    private fun parseScriptInfo(script: String, fallbackName: String): ScriptInfo {
        val head = script.substring(0, minOf(script.length, 4096))
        fun field(key: String): String {
            val match = Regex("""@""" + key + """\s+(.+)""").find(head) ?: return ""
            return match.groupValues[1].trim().trimEnd('*').trim()
        }
        return ScriptInfo(
            name = field("name").ifEmpty { fallbackName },
            version = field("version"),
            description = field("description"),
            author = field("author"),
        )
    }

    // ------------------------------------------------------------- prelude

    private const val PRELUDE = """
(function () {
  var lx = globalThis.lx;
  if (!lx || lx.utils) return;

  function strToBytes(str) {
    var bytes = [];
    for (var i = 0; i < str.length; i++) {
      var code = str.charCodeAt(i);
      if (code < 0x80) {
        bytes.push(code);
      } else if (code < 0x800) {
        bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
        var next = str.charCodeAt(++i);
        var cp = 0x10000 + ((code - 0xd800) * 0x400) + (next - 0xdc00);
        bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      } else {
        bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      }
    }
    return bytes;
  }
  function bytesToStr(bytes) {
    var out = '';
    var i = 0;
    while (i < bytes.length) {
      var b = bytes[i];
      if (b < 0x80) {
        out += String.fromCharCode(b);
        i += 1;
      } else if (b < 0xe0) {
        out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
        i += 2;
      } else if (b < 0xf0) {
        out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
        i += 3;
      } else {
        var cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
        cp -= 0x10000;
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
        i += 4;
      }
    }
    return out;
  }
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function bytesToBase64(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i];
      var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
      var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
      out += B64.charAt(b0 >> 2);
      out += B64.charAt(((b0 & 3) << 4) | (b1 >> 4));
      out += i + 1 < bytes.length ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
      out += i + 2 < bytes.length ? B64.charAt(b2 & 63) : '=';
    }
    return out;
  }
  function base64ToBytes(text) {
    var clean = String(text).replace(/[^A-Za-z0-9+/=]/g, '');
    var bytes = [];
    for (var i = 0; i < clean.length; i += 4) {
      var c0 = B64.indexOf(clean.charAt(i));
      var c1 = B64.indexOf(clean.charAt(i + 1));
      var c2 = clean.charAt(i + 2) === '=' ? 0 : B64.indexOf(clean.charAt(i + 2));
      var c3 = clean.charAt(i + 3) === '=' ? 0 : B64.indexOf(clean.charAt(i + 3));
      bytes.push((c0 << 2) | (c1 >> 4));
      if (clean.charAt(i + 2) !== '=') bytes.push(((c1 & 15) << 4) | (c2 >> 2));
      if (clean.charAt(i + 3) !== '=') bytes.push(((c2 & 3) << 6) | c3);
    }
    return bytes;
  }
  function bytesToHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      var h = (bytes[i] & 0xff).toString(16);
      out += h.length === 1 ? '0' + h : h;
    }
    return out;
  }
  function hexToBytes(hex) {
    var bytes = [];
    var clean = String(hex).replace(/[^0-9a-fA-F]/g, '');
    for (var i = 0; i + 1 < clean.length; i += 2) {
      bytes.push(parseInt(clean.substr(i, 2), 16));
    }
    return bytes;
  }
  function toBytes(input, encoding) {
    if (input == null) return [];
    if (typeof input === 'string') {
      if (encoding === 'hex') return hexToBytes(input);
      if (encoding === 'base64') return base64ToBytes(input);
      return strToBytes(input);
    }
    if (Array.isArray(input)) return input.slice();
    if (typeof input === 'object' && Array.isArray(input.data)) return input.data.slice();
    return [];
  }
  function bufToString(buf, encoding) {
    var bytes = toBytes(buf);
    if (encoding === 'hex') return bytesToHex(bytes);
    if (encoding === 'base64') return bytesToBase64(bytes);
    return bytesToStr(bytes);
  }
  function makeBuffer(bytes) {
    var arr = bytes.slice();
    arr.toString = function (encoding) { return bufToString(arr, encoding); };
    return arr;
  }

  var buffer = {
    from: function (input, encoding) { return makeBuffer(toBytes(input, encoding)); },
    alloc: function (size) {
      var arr = [];
      for (var i = 0; i < size; i++) arr.push(0);
      return makeBuffer(arr);
    },
    concat: function (list) {
      var out = [];
      for (var i = 0; i < list.length; i++) {
        var part = toBytes(list[i]);
        for (var j = 0; j < part.length; j++) out.push(part[j]);
      }
      return makeBuffer(out);
    },
    isBuffer: function (value) { return Array.isArray(value); },
    bufToString: bufToString,
    strToBuf: function (str, encoding) { return makeBuffer(toBytes(str, encoding)); },
    bufToHex: function (buf) { return bytesToHex(toBytes(buf)); },
    hexToBuf: function (hex) { return makeBuffer(hexToBytes(hex)); }
  };

  function aesCall(op, data, mode, key, iv) {
    var payload = JSON.stringify({
      op: op,
      mode: String(mode || 'aes-128-cbc'),
      data: toBytes(data),
      key: toBytes(key),
      iv: toBytes(iv)
    });
    var out = lx.__aes(payload);
    if (!out) throw new Error('aes operation failed');
    return makeBuffer(JSON.parse(out));
  }

  var crypto = {
    md5: function (data) {
      var text = typeof data === 'string' ? data : bufToString(data, 'utf8');
      return lx.__md5(text);
    },
    randomBytes: function (size) {
      var arr = [];
      for (var i = 0; i < size; i++) arr.push(Math.floor(Math.random() * 256));
      return makeBuffer(arr);
    },
    aesEncrypt: function (data, mode, key, iv) { return aesCall('encrypt', data, mode, key, iv); },
    aesDecrypt: function (data, mode, key, iv) { return aesCall('decrypt', data, mode, key, iv); }
  };

  lx.utils = { buffer: buffer, crypto: crypto };
})();
"""
}
