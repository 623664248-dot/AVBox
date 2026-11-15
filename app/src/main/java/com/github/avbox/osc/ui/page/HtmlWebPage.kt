package com.github.avbox.osc.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.avbox.osc.R
import com.github.avbox.osc.util.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HTML_WEB_PREFS = "html_web"
private const val KEY_LAST_URL = "last_url"
private const val HTML_CACHE_DIR = "html"

/** Holds the live WebView outside of composition state so back/toolbar actions can reach it. */
private class HtmlWebHolder {
    var webView: WebView? = null
}

@Composable
fun HtmlWebScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences(HTML_WEB_PREFS, Context.MODE_PRIVATE)
    }
    var urlText by rememberSaveable { mutableStateOf(prefs.getString(KEY_LAST_URL, "") ?: "") }
    var browsingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val holder = remember { HtmlWebHolder() }

    val invalidUrlMsg = stringResource(R.string.html_web_invalid_url)
    val copyFailedMsg = stringResource(R.string.html_web_copy_failed)

    fun openLink(raw: String) {
        val url = normalizeHtmlUrl(raw)
        if (url == null) {
            Toast.makeText(context, invalidUrlMsg, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString(KEY_LAST_URL, raw.trim()).apply()
        browsingUrl = url
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) { copyHtmlToCache(context, uri) }
            if (file != null) {
                browsingUrl = Uri.fromFile(file).toString()
            } else {
                Toast.makeText(context, copyFailedMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun webBack() {
        val webView = holder.webView
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            browsingUrl = null
        }
    }

    BackHandler(enabled = browsingUrl != null) { webBack() }

    val target = browsingUrl
    if (target == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
                Text(
                    text = stringResource(R.string.html_web_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text(stringResource(R.string.html_web_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { openLink(urlText) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.html_web_open_link))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("text/html", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.html_web_pick_file))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.html_web_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            key(target) {
                HtmlWebView(
                    url = target,
                    holder = holder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HtmlWebView(
    url: String,
    holder: HtmlWebHolder,
    modifier: Modifier = Modifier,
) {
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            },
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createHtmlWebView(ctx) { callback, params ->
                fileCallback = callback
                try {
                    chooserLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    AppLog.e("HtmlWeb", e)
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }.also { webView ->
                holder.webView = webView
                webView.loadUrl(url)
            }
        },
        onRelease = { webView ->
            if (holder.webView === webView) holder.webView = null
            webView.stopLoading()
            webView.destroy()
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createHtmlWebView(
    context: Context,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
): WebView {
    val webView = WebView(context)
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    settings.allowFileAccessFromFileURLs = true
    settings.allowUniversalAccessFromFileURLs = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.loadsImagesAutomatically = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.defaultTextEncodingName = "utf-8"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    // Keep the system default (full Chrome mobile) UA; only repair it if it is blank.
    if (settings.userAgentString.isNullOrBlank()) {
        settings.userAgentString = WebSettings.getDefaultUserAgent(context)
    }
    // Plain client: in-page navigation stays inside this WebView.
    webView.webViewClient = WebViewClient()
    webView.webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = onShowFileChooser(filePathCallback, fileChooserParams)

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult,
        ): Boolean {
            result.confirm()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: JsResult,
        ): Boolean {
            result.confirm()
            return true
        }
    }
    return webView
}

private fun normalizeHtmlUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return when {
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ||
            text.startsWith("file://", ignoreCase = true) ||
            text.startsWith("content://", ignoreCase = true) -> text
        text.startsWith("/") -> "file://$text"
        else -> "https://$text"
    }
}

private fun copyHtmlToCache(context: Context, uri: Uri): File? {
    return try {
        val rawName = queryDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: "page.html"
        val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dir = File(context.cacheDir, HTML_CACHE_DIR).apply { mkdirs() }
        val out = File(dir, safeName)
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            out.outputStream().use { output -> stream.copyTo(output) }
        }
        out
    } catch (e: Exception) {
        AppLog.e("HtmlWeb", e)
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }
}
