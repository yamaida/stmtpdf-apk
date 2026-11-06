package com.stmtpdf.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 不可见的 WebView 解析引擎：
 * 通过 WebViewAssetLoader 以 https 同源方式加载 assets 内的 pdf.js 与解析内核，
 * 本地 PDF 通过 /local/<token> 虚拟路径提供，避免 file:// 的跨域与 worker 限制。
 */
class PdfEngine(context: Context) {

    private val appContext = context.applicationContext
    private val files = ConcurrentHashMap<String, File>()

    private val localHandler = WebViewAssetLoader.PathHandler { path ->
        val key = path.removePrefix("/")
        val file = files[key]
        if (file == null || !file.exists()) {
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        } else {
            WebResourceResponse("application/pdf", "UTF-8", FileInputStream(file))
        }
    }

    private var readyDeferred = CompletableDeferred<Unit>()
    private var progressCallback: ((Int, Int) -> Unit)? = null
    private var parseDeferred: CompletableDeferred<JSONObject>? = null
    private var exportDeferred: CompletableDeferred<String>? = null

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        overScrollMode = WebView.OVER_SCROLL_NEVER
    }

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/local/", localHandler)
        .build()

    init {
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}
            override fun onPageFinished(view: WebView?, url: String?) {}
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/engine/index.html")
    }

    suspend fun awaitReady() {
        withTimeout(20_000) { readyDeferred.await() }
    }

    suspend fun parse(file: File, maxPages: Int, onProgress: (Int, Int) -> Unit): JSONObject {
        awaitReady()
        progressCallback = onProgress
        val token = UUID.randomUUID().toString().replace("-", "") + ".pdf"
        files[token] = file
        val deferred = CompletableDeferred<JSONObject>()
        parseDeferred = deferred
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                "StmtEngine.parse('https://appassets.androidplatform.net/local/$token', $maxPages)",
                null
            )
        }
        try {
            return withTimeout(120_000) { deferred.await() }
        } finally {
            files.remove(token)
            parseDeferred = null
            progressCallback = null
        }
    }

    suspend fun export(kind: String, rowsJson: String, baseName: String): String {
        awaitReady()
        val deferred = CompletableDeferred<String>()
        exportDeferred = deferred
        val script = "StmtEngine.exportFile(" +
            JSONObject.quote(kind) + "," +
            JSONObject.quote(rowsJson) + "," +
            JSONObject.quote(baseName) + ")"
        withContext(Dispatchers.Main) { webView.evaluateJavascript(script, null) }
        try {
            return withTimeout(60_000) { deferred.await() }
        } finally {
            exportDeferred = null
        }
    }

    fun destroy() {
        try {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (e: Throwable) {
            // ignore
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
        }

        @JavascriptInterface
        fun onProgress(no: Int, total: Int) {
            progressCallback?.invoke(no, total)
        }

        @JavascriptInterface
        fun onResult(json: String) {
            val deferred = parseDeferred ?: return
            runCatching { JSONObject(json) }
                .onSuccess { if (!deferred.isCompleted) deferred.complete(it) }
                .onFailure { if (!deferred.isCompleted) deferred.completeExceptionally(it) }
        }

        @JavascriptInterface
        fun onExport(kind: String, base64: String) {
            exportDeferred?.let { if (!it.isCompleted) it.complete(base64) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            val err = IllegalStateException(message.ifBlank { "engine_error" })
            parseDeferred?.let { if (!it.isCompleted) it.completeExceptionally(err) }
            exportDeferred?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        }
    }
}
