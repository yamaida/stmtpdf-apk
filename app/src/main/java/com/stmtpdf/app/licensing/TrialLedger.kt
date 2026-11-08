package com.stmtpdf.app.licensing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.stmtpdf.app.net.Api
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 试用计数：未激活时累计 10 页。
 *
 * 本地 HMAC 只负责留痕与离线宽限；额度以服务端台账为准（按机器码记账），
 * 所以重装、清数据都不会再白拿 10 页，只有后台超管重置才恢复。
 */
object TrialLedger {

    const val TRIAL_PAGES = 10
    private const val PREFS = "stmtpdf_trial_v2"
    private const val KEY_USED = "used"
    private const val KEY_MAC = "mac"
    private const val KEY_RESET_SEEN = "reset_seen"

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

    private fun writeUsed(context: Context, value: Int) {
        val next = value.coerceIn(0, TRIAL_PAGES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_USED, next)
            .putString(KEY_MAC, sign(context, next))
            .apply()
    }

    /**
     * 合并服务端台账。
     *
     * - 服务端作废（已激活付费授权）→ 本地直接判满；
     * - 后台重置过（reset_count 变大）→ 采用服务端已用页数；
     * - 其余情况取较小额度（已用页数取较大值），防止清数据恢复额度。
     */
    fun applyServer(context: Context, serverUsed: Int, resetCount: Int, voided: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getInt(KEY_RESET_SEEN, 0)
        val local = used(context)
        val target = when {
            voided -> TRIAL_PAGES
            resetCount > seen -> serverUsed
            else -> maxOf(local, serverUsed)
        }
        writeUsed(context, target)
        if (resetCount != seen) {
            prefs.edit().putInt(KEY_RESET_SEEN, resetCount).apply()
        }
    }

    private fun raiseUsed(context: Context, serverUsed: Int) {
        writeUsed(context, maxOf(used(context), serverUsed))
    }

    fun consume(context: Context, pages: Int): Int {
        val next = (used(context) + pages).coerceAtMost(TRIAL_PAGES)
        writeUsed(context, next)
        report(context.applicationContext, pages)
        return TRIAL_PAGES - next
    }

    /** 激活成功后作废试用额度。 */
    fun void(context: Context, order: String = "") {
        writeUsed(context, TRIAL_PAGES)
        val app = context.applicationContext
        val machine = MachineCode.formatted(app)
        Thread {
            runCatching { Api.trialVoid(machine, order) }
        }.start()
    }

    /** 启动/联网时对账；回调在主线程。 */
    fun refresh(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val machine = MachineCode.formatted(app)
        val version = appVersion(app)
        Thread {
            try {
                val body = Api.trialStatus(machine, version).body
                if (body != null && body.optBoolean("ok")) {
                    applyServer(
                        app,
                        body.optInt("used", 0),
                        body.optInt("reset_count", 0),
                        body.optString("status") != "active"
                    )
                }
            } catch (_: Throwable) {
                // 离线：沿用本地账本（宽限期内仍可试用）
            } finally {
                Handler(Looper.getMainLooper()).post { onDone() }
            }
        }.start()
    }

    /** 上报扣减；离线失败不回滚，等下次 status 对账。 */
    private fun report(app: Context, pages: Int) {
        val machine = MachineCode.formatted(app)
        val version = appVersion(app)
        val reqId = UUID.randomUUID().toString()
        Thread {
            try {
                val result = Api.trialConsume(machine, pages, reqId, version)
                val body = result.body ?: return@Thread
                if (body.optBoolean("ok")) {
                    applyServer(app, body.optInt("used", 0), body.optInt("reset_count", 0), false)
                } else {
                    val error = body.optString("error")
                    when (error) {
                        // 服务端已用完：本地只加不减，避免回吐额度
                        "trial_exceeded" -> raiseUsed(app, body.optInt("used", TRIAL_PAGES))
                        "trial_voided" -> applyServer(app, TRIAL_PAGES, body.optInt("reset_count", 0), true)
                    }
                }
            } catch (_: Throwable) {
                // 离线：本地已扣减，联网后由 status 对账
            }
        }.start()
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }
}
