package com.stmtpdf.app.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class UpdateInfo(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** 低于 min_supported 或后台勾了 force 时为 true。 */
    val force: Boolean
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

object UpdateChecker {

    /** 官网后台维护的版本清单（/admin/releases.php 保存后即时生效）。 */
    const val ENDPOINT = "https://www.stmtpdf.com/app/latest.json"

    const val ERR_NO_SPACE = "no_space"

    private const val TIMEOUT_MS = 10_000
    private const val CHUNK = 64 * 1024
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    /** HEAD 取远端字节数：-1 = 服务器说文件不存在，0 = 网络问题无法判断。 */
    private fun remoteSize(url: String): Long = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "StmtPDF-Android")
        }
        try {
            if (conn.responseCode in 200..299) conn.contentLengthLong else -1L
        } finally {
            conn.disconnect()
        }
    } catch (e: Throwable) {
        0L
    }

    /** 检查新版；网络/解析失败静默返回 null，不影响启动。 */
    fun check(currentVersion: String): UpdateInfo? =
        (checkDetailed(currentVersion) as? UpdateCheckResult.Available)?.info

    /** 区分「有新版 / 已是最新 / 检查失败」，供手动检查显示结果。 */
    fun checkDetailed(currentVersion: String): UpdateCheckResult {
        return try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "StmtPDF-Android")
            }
            try {
                if (conn.responseCode != 200) return UpdateCheckResult.Failed
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val version = json.optString("version")
                val url = json.optString("url")
                if (version.isEmpty() || url.isEmpty()) return UpdateCheckResult.Failed
                if (compareVersions(version, currentVersion) <= 0) return UpdateCheckResult.UpToDate

                val minSupported = json.optString("min_supported").trim()
                val forcedByFloor = minSupported.isNotEmpty() &&
                    compareVersions(currentVersion, minSupported) < 0
                val sizeBytes = json.optLong("size_bytes", 0L)
                // 清单已指向新版但安装包还没上传（404 / 体积不符）：按检查失败处理，
                // 避免把用户带进「下载完必然校验失败」的死路。
                if (sizeBytes > 0) {
                    val remote = remoteSize(url)
                    if (remote == -1L) return UpdateCheckResult.Failed
                    if (remote > 0 && remote != sizeBytes) return UpdateCheckResult.Failed
                }
                UpdateCheckResult.Available(
                    UpdateInfo(
                        version = version,
                        url = url,
                        sha256 = json.optString("sha256"),
                        sizeBytes = sizeBytes,
                        force = json.optBoolean("force", false) || forcedByFloor
                    )
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            UpdateCheckResult.Failed
        }
    }

    /**
     * 下载 APK 到 cacheDir/updates/，支持断点续传。
     * 取消时删除临时文件并抛出 CancellationException；SHA-256 不匹配抛异常并删除。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (percent: Int, received: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "StmtPDF-${info.version}.apk")
        val temp = File(dir, "StmtPDF-${info.version}.apk.part")

        // 已下完（用户上次在安装器点了取消）：体积与哈希都对得上才复用，否则重下
        if (target.isFile && target.length() > 0) {
            val expected = info.sha256.trim().lowercase(Locale.US)
            val sizeOk = info.sizeBytes <= 0 || target.length() == info.sizeBytes
            val hashOk = expected.isEmpty() || sha256(target) == expected
            if (sizeOk && hashOk) {
                onProgress(100, target.length(), target.length())
                return target
            }
            target.delete()
        }

        var existing = if (temp.isFile) temp.length() else 0L
        if (info.sizeBytes > 0 && existing > info.sizeBytes) {
            temp.delete()
            existing = 0
        }
        if (info.sizeBytes > 0) {
            val needed = (info.sizeBytes - existing).coerceAtLeast(0)
            if (context.cacheDir.usableSpace < needed + 1024 * 1024) {
                throw IOException(ERR_NO_SPACE)
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "StmtPDF-Android")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                // 本地 .part 与服务端文件对不上，丢掉重下
                temp.delete()
                throw IOException("HTTP $code")
            }
            if (existing > 0 && code == HttpURLConnection.HTTP_OK) {
                // 服务端不支持 Range，从头下载
                temp.delete()
                existing = 0
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }

            val total = when {
                code == HttpURLConnection.HTTP_PARTIAL -> existing + conn.contentLengthLong
                else -> conn.contentLengthLong
            }
            var received = existing
            var lastPercent = -1
            val out = FileOutputStream(temp, existing > 0)
            conn.inputStream.use { input ->
                out.use { output ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        if (isCancelled()) throw CancellationException("update cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent, received, total)
                            }
                        }
                    }
                }
            }
            if (received <= 0) throw IOException("empty package")

            val expected = info.sha256.trim().lowercase(Locale.US)
            if (expected.isNotEmpty()) {
                val actual = sha256(temp)
                if (actual != expected) {
                    temp.delete()
                    throw IOException("SHA-256 mismatch")
                }
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100, received, if (total > 0) total else received)
            return target
        } catch (t: Throwable) {
            // 取消：按交互约定清掉临时文件；网络中断保留 .part，下次续传
            if (t is CancellationException) temp.delete()
            throw t
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 语义化版本比较：a > b 返回正数。 */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
