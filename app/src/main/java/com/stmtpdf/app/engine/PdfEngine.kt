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
    @Volatile private var parseDeferred: CompletableDeferred<JSONObject>? = null
    private var exportDeferred: CompletableDeferred<String>? = null
    // 每次 parse 的请求号：取消后旧任务迟到的回调不会污染下一次解析
    @Volatile private var requestSeq = 0
    @Volatile private var activeParseId = 0

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
        val id = ++requestSeq
        activeParseId = id
        progressCallback = onProgress
        val token = UUID.randomUUID().toString().replace("-", "") + ".pdf"
        files[token] = file
        val deferred = CompletableDeferred<JSONObject>()
        parseDeferred = deferred
        try {
            // evaluateJavascript 也可能在挂起期间被取消，必须放在 try 里，保证 finally 一定清理
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    "StmtEngine.parse('https://appassets.androidplatform.net/local/$token', $maxPages, $id)",
                    null
                )
            }
            return withTimeout(120_000) { deferred.await() }
        } finally {
            files.remove(token)
            if (activeParseId == id) {
                activeParseId = 0
                parseDeferred = null
                progressCallback = null
            }
        }
    }

    suspend fun export(kind: String, rowsJson: String, baseName: String, preset: String = ""): String {
        awaitReady()
        val deferred = CompletableDeferred<String>()
        exportDeferred = deferred
        val script = "StmtEngine.exportFile(" +
            JSONObject.quote(kind) + "," +
            JSONObject.quote(rowsJson) + "," +
            JSONObject.quote(baseName) + "," +
            JSONObject.quote(preset) + ")"
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

        /** 回调都带请求号：取消后旧任务迟到的进度/结果直接丢弃，不会串到下一次解析。 */
        @JavascriptInterface
        fun onProgress(reqId: Int, no: Int, total: Int) {
            if (reqId != activeParseId) return
            progressCallback?.invoke(no, total)
        }

        @JavascriptInterface
        fun onResult(reqId: Int, json: String) {
            if (reqId != activeParseId) return
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
        fun onError(reqId: Int, message: String) {
            val err = IllegalStateException(message.ifBlank { "engine_error" })
            // reqId = 0 表示导出链路（与解析请求号隔离）
            if (reqId == 0) {
                exportDeferred?.let { if (!it.isCompleted) it.completeExceptionally(err) }
                return
            }
            if (reqId != activeParseId) return
            parseDeferred?.let { if (!it.isCompleted) it.completeExceptionally(err) }
        }
    }
}
