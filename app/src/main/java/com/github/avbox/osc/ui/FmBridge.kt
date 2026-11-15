package com.github.avbox.osc.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.sourcedata.PushUrlParser
import com.github.avbox.osc.ui.page.jumpToDetail
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.HeaderGuard
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * FongMi WebHome native bridge (`window.fm`) for the shared HTML WebView.
 *
 * WebHome single-page apps (e.g. the AV.html combo page) detect the host via
 * `window.fm && window.fm.req`, wait for an `fmsdk` event (2.5s timeout), then
 * route all data through the bridge:
 *
 *  - `fm.req(url, opt)`   -> real HTTP via the app OkHttp stack, so the page is
 *                            not bound by WebView CORS and can set Referer/UA.
 *  - `fm.res(url, opt)`   -> synchronous; returns a URL the WebView can load.
 *  - `fm.cache.get/set`   -> SharedPreferences-backed page storage.
 *  - `fm.play(url, t, o)` -> opens the native player (push_agent direct play).
 *  - `fm.vodInline(obj)`  -> same, after consulting the page-registered
 *                            `window.__fmWebHomeInlineResolver` for episodes
 *                            flagged `resolve: true`.
 *  - `fm.pan.play(obj)`   -> sniffing playback; not supported by this host,
 *                            always rejects so the page falls back to fm.play.
 *
 * The JS shim is injected from `WebViewClient.onPageStarted` (plus a
 * `onPageFinished` retry) because the project has no androidx.webkit
 * dependency for `addDocumentStartJavaScript`. The shim wraps the synchronous
 * [FmNativeInterface] in a Promise registry keyed by a self-incrementing id;
 * native completions call back through `window.__fmResolve`.
 *
 * This file is deliberately free of Composables: all host logic lives here so
 * the UI layer only attaches/detaches the bridge on the shared WebView.
 */
object FmBridge {

    const val INTERFACE_NAME = "__fmNative"

    private val attached = WeakHashMap<WebView, FmNativeInterface>()

    /** Attaches the bridge to [webView]; returns the interface for symmetry. */
    fun attach(webView: WebView): FmNativeInterface {
        detach(webView)
        val iface = FmNativeInterface(webView)
        attached[webView] = iface
        webView.addJavascriptInterface(iface, INTERFACE_NAME)
        return iface
    }

    /** Marks the bridge dead and unregisters it; call before destroying the WebView. */
    fun detach(webView: WebView) {
        val iface = attached.remove(webView) ?: return
        iface.destroy()
        try {
            webView.removeJavascriptInterface(INTERFACE_NAME)
        } catch (th: Throwable) {
            AppLog.e("FmBridge", th)
        }
    }

    /**
     * JS shim defining `window.fm`. Idempotent: pages that already received a
     * shim (or a real FongMi host) keep theirs. Dispatches `fmsdk` at the end.
     */
    val SHIM_JS: String = """
(function () {
  if (window.fm) return;
  var native = window.__fmNative;
  if (!native) return;
  var seq = 0;
  var pending = {};
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        native.invoke(id, method, JSON.stringify(args || []));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }
  window.__fmResolve = function (id, json) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    var r = null;
    try { r = JSON.parse(json); } catch (e) {}
    if (r && r.__error) p.reject(new Error(String(r.__error)));
    else p.resolve(r ? r.value : undefined);
  };
  window.fm = {
    __avbox: true,
    req: function (url, opt) { return call('req', [url, opt || {}]); },
    res: function (url, opt) {
      try { return native.res(url || '', JSON.stringify(opt || {})) || url; }
      catch (e) { return url; }
    },
    cache: {
      get: function (key, ns) { return call('cacheGet', [String(key), ns || '']); },
      set: function (key, value, ns) { return call('cacheSet', [String(key), String(value), ns || '']); }
    },
    play: function (url, title, opt) { return call('play', [url, title || '', opt || {}]); },
    vodInline: function (obj) { return call('vodInline', [obj || {}]); },
    pan: {
      play: function (obj) { return call('panPlay', [obj || {}]); }
    }
  };
  try { window.dispatchEvent(new Event('fmsdk')); } catch (e) {}
})();
""".trimIndent()
}

class FmNativeInterface internal constructor(webView: WebView) {

    private val webViewRef = WeakReference(webView)
    private val appContext: Context = webView.context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false

    fun destroy() {
        destroyed = true
    }

    // ------------------------------------------------------------------ res

    /**
     * Synchronous resource URL mapping. The local server only proxies spider
     * traffic (`/proxy?do=...`), there is no generic header-carrying resource
     * proxy endpoint, so the URL is returned unchanged and the WebView loads
     * it itself (same behaviour the page has on hosts without fm.res).
     */
    @JavascriptInterface
    fun res(url: String?, @Suppress("UNUSED_PARAMETER") optJson: String?): String {
        return url.orEmpty()
    }

    // ---------------------------------------------------------------- invoke

    @JavascriptInterface
    fun invoke(id: Int, method: String?, argsJson: String?) {
        if (destroyed) return
        try {
            val args = JSONArray(argsJson ?: "[]")
            when (method) {
                "req" -> doReq(id, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject())
                "cacheGet" -> resolve(id, cacheGet(args.optString(0, ""), args.optString(1, "")))
                "cacheSet" -> {
                    cacheSet(args.optString(0, ""), args.optString(1, ""), args.optString(2, ""))
                    resolve(id, null)
                }
                "play" -> doPlay(id, args.optString(0, ""), args.optString(1, ""), args.optJSONObject(2) ?: JSONObject())
                "vodInline" -> doVodInline(id, args.optJSONObject(0) ?: JSONObject())
                "panPlay" -> reject(id, "sniff playback is not supported by this host")
                else -> reject(id, "unknown fm method: $method")
            }
        } catch (e: Exception) {
            AppLog.e("FmBridge", e)
            reject(id, e.message ?: "bridge error")
        }
    }

    // ------------------------------------------------------------------ req

    private fun doReq(id: Int, url: String, opt: JSONObject) {
        val base = HttpClients.defaultClient
        if (base == null) {
            resolve(id, reqResult(ok = false, status = 0, body = "", error = "http client not ready", url = url))
            return
        }
        if (url.isEmpty()) {
            resolve(id, reqResult(ok = false, status = 0, body = "", error = "empty url", url = url))
            return
        }
        val request = try {
            buildRequest(url, opt)
        } catch (e: Exception) {
            resolve(id, reqResult(ok = false, status = 0, body = "", error = e.message ?: "bad request", url = url))
            return
        }
        val timeoutSec = opt.optDouble("timeout", DEFAULT_TIMEOUT_SEC)
            .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        val client: OkHttpClient = base.newBuilder()
            .callTimeout((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                resolve(id, reqResult(ok = false, status = 0, body = "", error = e.message ?: "network error", url = url))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val bodyText = readBodyText(it)
                        val code = it.code
                        reqResult(
                            ok = code in 200..299,
                            status = code,
                            body = bodyText,
                            error = if (code in 200..299) "" else "HTTP $code",
                            url = it.request.url.toString(),
                        )
                    }
                } catch (e: Exception) {
                    reqResult(ok = false, status = response.code, body = "", error = e.message ?: "read error", url = url)
                }
                resolve(id, result)
            }
        })
    }

    private fun buildRequest(url: String, opt: JSONObject): Request {
        val builder = Request.Builder().url(url)
        val headers = opt.optJSONObject("headers")
        val headerMap = LinkedHashMap<String, String>()
        if (headers != null) {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.optString(key, "")
                if (HeaderGuard.isSendable(key, value)) {
                    headerMap[key] = value
                    builder.header(key, value)
                }
            }
        }
        when (opt.optString("method", "get").lowercase()) {
            "post" -> builder.post(buildPostBody(opt, headerMap))
            "put" -> builder.put(buildPostBody(opt, headerMap))
            "delete" -> builder.delete(buildPostBodyOrNull(opt, headerMap))
            "head" -> builder.head()
            else -> builder.get()
        }
        return builder.build()
    }

    private fun buildPostBody(opt: JSONObject, headerMap: Map<String, String>): RequestBody {
        return buildPostBodyOrNull(opt, headerMap) ?: FormBody.Builder().build()
    }

    private fun buildPostBodyOrNull(opt: JSONObject, headerMap: Map<String, String>): RequestBody? {
        val data = if (opt.has("data") && !opt.isNull("data")) opt.opt("data") else null
        if (data != null) {
            val postType = opt.optString("postType", "form")
            return when {
                data is JSONObject -> {
                    val form = FormBody.Builder()
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        form.add(key, data.optString(key, ""))
                    }
                    form.build()
                }
                postType.equals("json", ignoreCase = true) ->
                    data.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                else ->
                    data.toString().toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull())
            }
        }
        val rawBody = if (opt.has("body") && !opt.isNull("body")) opt.optString("body", "") else null
        if (rawBody != null) {
            val contentType = headerMap.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?: "text/plain; charset=utf-8"
            return rawBody.toRequestBody(contentType.toMediaTypeOrNull())
        }
        return null
    }

    private fun readBodyText(response: Response): String {
        val body = response.body
        val stream: InputStream = body.byteStream()
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BODY_BYTES) {
                    throw IOException("response too large")
                }
                out.write(buffer, 0, read)
            }
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return String(out.toByteArray(), charset)
    }

    private fun reqResult(ok: Boolean, status: Int, body: String, error: String, url: String): JSONObject {
        val json = JSONObject()
        json.put("ok", ok)
        json.put("status", status)
        json.put("body", body)
        json.put("error", error)
        json.put("url", url)
        return json
    }

    // ---------------------------------------------------------------- cache

    private fun cacheGet(key: String, ns: String): String {
        if (key.isEmpty()) return ""
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(cacheKey(key, ns), "") ?: ""
    }

    private fun cacheSet(key: String, value: String, ns: String) {
        if (key.isEmpty()) return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(cacheKey(key, ns), value).apply()
    }

    private fun cacheKey(key: String, ns: String): String {
        return if (ns.isEmpty()) key else "$ns:$key"
    }

    // ----------------------------------------------------------------- play

    private fun doPlay(id: Int, url: String, title: String, opt: JSONObject) {
        if (url.isEmpty()) {
            reject(id, "empty play url")
            return
        }
        val headers = parseHeaders(opt.optJSONObject("headers"))
        val pic = opt.optString("pic", "")
        launchPlayer(id, url, headers, title, pic)
    }

    private fun doVodInline(id: Int, obj: JSONObject) {
        val episodes = obj.optJSONArray("episodes")
        if (episodes == null || episodes.length() == 0) {
            reject(id, "vodInline: no episodes")
            return
        }
        val episode = episodes.optJSONObject(0)
        if (episode == null) {
            reject(id, "vodInline: bad episode")
            return
        }
        var playUrl = episode.optString("url", "")
        val headers = parseHeaders(obj.optJSONObject("headers"))
        if (episode.optBoolean("resolve", false)) {
            val resolved = callInlineResolver(episode)
            if (resolved != null) {
                val resolvedUrl = resolved.optString("url", "")
                if (resolvedUrl.isNotEmpty()) playUrl = resolvedUrl
                // Resolver-declared headers win over the vod-level ones.
                headers.putAll(parseHeaders(resolved.optJSONObject("headers")))
            }
        }
        if (playUrl.isEmpty()) {
            reject(id, "vodInline: empty play url")
            return
        }
        val title = obj.optString("vod_name", "")
        val pic = obj.optString("vod_pic", "")
        launchPlayer(id, playUrl, headers, title, pic)
    }

    /**
     * Asks the page to resolve an episode through the resolver it registered
     * as `window.__fmWebHomeInlineResolver`. Runs the JS on the main thread
     * and waits briefly on the bridge thread; returns null when the page has
     * no resolver or it yields nothing usable.
     */
    private fun callInlineResolver(episode: JSONObject): JSONObject? {
        val webView = webViewRef.get() ?: return null
        if (destroyed) return null
        val latch = CountDownLatch(1)
        var resultText: String? = null
        val script = "(function(){try{" +
            "var f=window.__fmWebHomeInlineResolver;" +
            "if(!f)return '';" +
            "var r=f(" + episode.toString() + ");" +
            "return r?JSON.stringify(r):'';" +
            "}catch(e){return '';}})()"
        mainHandler.post {
            val view = webViewRef.get()
            if (view == null || destroyed) {
                latch.countDown()
                return@post
            }
            try {
                view.evaluateJavascript(script) { value ->
                    resultText = value
                    latch.countDown()
                }
            } catch (th: Throwable) {
                AppLog.e("FmBridge", th)
                latch.countDown()
            }
        }
        try {
            latch.await(RESOLVER_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            return null
        }
        // evaluateJavascript hands back the JS string JSON-encoded once more.
        val outer = resultText ?: return null
        val inner = try {
            org.json.JSONTokener(outer).nextValue() as? String
        } catch (e: Exception) {
            null
        } ?: return null
        if (inner.isEmpty()) return null
        return try {
            JSONObject(inner)
        } catch (e: Exception) {
            null
        }
    }

    private fun launchPlayer(id: Int, url: String, headers: Map<String, String>, title: String, pic: String) {
        mainHandler.post {
            val view = webViewRef.get()
            if (view == null || destroyed) {
                reject(id, "page is gone")
                return@post
            }
            try {
                val playId = if (headers.isEmpty()) {
                    url
                } else {
                    val encoded = URLEncoder.encode(JSONObject(headers).toString(), "UTF-8")
                    url + PushUrlParser.PUSH_HEADERS_MARKER + encoded + "@"
                }
                view.context.jumpToDetail(
                    id = playId,
                    sourceKey = PushUrlParser.PUSH_AGENT,
                    title = title.ifEmpty { null },
                    picture = pic.ifEmpty { null },
                    autoPlay = true,
                )
                resolve(id, true)
            } catch (e: Exception) {
                AppLog.e("FmBridge", e)
                reject(id, e.message ?: "open player failed")
            }
        }
    }

    private fun parseHeaders(json: JSONObject?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (json == null) return out
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key, "")
            if (HeaderGuard.isSendable(key, value)) out[key] = value
        }
        return out
    }

    // -------------------------------------------------------------- plumbing

    private fun resolve(id: Int, value: Any?) {
        val wrapper = JSONObject()
        when (value) {
            null -> wrapper.put("value", JSONObject.NULL)
            is JSONObject -> wrapper.put("value", value)
            is Boolean -> wrapper.put("value", value)
            is Number -> wrapper.put("value", value)
            else -> wrapper.put("value", value.toString())
        }
        deliver(id, wrapper)
    }

    private fun reject(id: Int, message: String) {
        val wrapper = JSONObject()
        wrapper.put("__error", message)
        deliver(id, wrapper)
    }

    private fun deliver(id: Int, wrapper: JSONObject) {
        if (destroyed) return
        val js = "window.__fmResolve && window.__fmResolve(" + id + ", " +
            JSONObject.quote(wrapper.toString()) + ")"
        mainHandler.post {
            if (destroyed) return@post
            val view = webViewRef.get() ?: return@post
            try {
                view.evaluateJavascript(js, null)
            } catch (th: Throwable) {
                AppLog.e("FmBridge", th)
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "fm_webhome_cache"
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val DEFAULT_TIMEOUT_SEC = 20.0
        const val MIN_TIMEOUT_SEC = 3.0
        const val MAX_TIMEOUT_SEC = 120.0
        const val RESOLVER_WAIT_MS = 3000L
    }
}
