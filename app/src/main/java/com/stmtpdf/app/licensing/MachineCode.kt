package com.stmtpdf.app.licensing

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * 机器码：SHA-256(SSAID + 应用签名证书哈希 + pepper) 取前 16 个十六进制字符。
 * 输出格式与官网一致：XXXX-XXXX-XXXX-XXXX。
 */
object MachineCode {

    fun raw(context: Context): String {
        val ssaid = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Throwable) {
            ""
        }
        val sig = signingHash(context)
        val pepper = "stmtpdf-android-v1"
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$ssaid|$sig|$pepper".toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val hex = buildString {
            for (i in 0 until 8) append(String.format("%02X", hash[i]))
        }
        return hex
    }

    fun formatted(context: Context): String {
        val clean = raw(context)
        return clean.chunked(4).joinToString("-")
    }

    private fun signingHash(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val cert = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            } ?: ByteArray(0)
            val md = MessageDigest.getInstance("SHA-256")
            val h = md.digest(cert)
            buildString { for (b in h) append(String.format("%02x", b)) }
        } catch (e: Throwable) {
            "nosig"
        }
    }
}
