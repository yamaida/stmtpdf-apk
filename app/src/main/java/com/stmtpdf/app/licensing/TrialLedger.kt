package com.stmtpdf.app.licensing

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 试用计数：未激活时累计 10 页，本地 HMAC 封存，篡改视为已用完。
 */
object TrialLedger {

    const val TRIAL_PAGES = 10
    private const val PREFS = "stmtpdf_trial_v2"
    private const val KEY_USED = "used"
    private const val KEY_MAC = "mac"

    private fun macKey(context: Context): ByteArray {
        val material = MachineCode.raw(context) + "|stmtpdf-trial-v1"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    }

    private fun sign(context: Context, used: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey(context), "HmacSHA256"))
        val bytes = mac.doFinal("trial:$used".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun used(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 首次安装尚无记录：正常返回 0，不能当作篡改
        if (!prefs.contains(KEY_MAC)) {
            return 0
        }
        val used = prefs.getInt(KEY_USED, 0)
        val mac = prefs.getString(KEY_MAC, "") ?: ""
        if (mac != sign(context, used)) {
            // 校验失败：判定试用已用完
            return TRIAL_PAGES
        }
        return used.coerceIn(0, TRIAL_PAGES)
    }

    fun remaining(context: Context): Int = maxOf(0, TRIAL_PAGES - used(context))

    fun consume(context: Context, pages: Int): Int {
        val current = used(context)
        val next = (current + pages).coerceAtMost(TRIAL_PAGES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_USED, next)
            .putString(KEY_MAC, sign(context, next))
            .apply()
        return maxOf(0, TRIAL_PAGES - next)
    }

    /** 激活成功后作废试用额度。 */
    fun void(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_USED, TRIAL_PAGES)
            .putString(KEY_MAC, sign(context, TRIAL_PAGES))
            .apply()
    }
}
