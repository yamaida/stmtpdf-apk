package com.stmtpdf.app.licensing

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Period(
    val start: String,
    val end: String,
    val pages: Int,
    var used: Int
) {
    fun remaining(): Int = maxOf(0, pages - used)
    fun activeOn(day: String): Boolean = day >= start && day <= end
    fun toJson(): JSONObject = JSONObject()
        .put("start", start).put("end", end).put("pages", pages).put("used", used)
}

data class LicenceState(
    val plan: String,
    val periods: MutableList<Period>,
    val order: String = "",
    val issued: String = ""
) {
    fun expiry(): String = periods.maxOfOrNull { it.end } ?: ""
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun current(): Period? {
        val day = today()
        return periods.firstOrNull { it.activeOn(day) }
    }

    fun remaining(): Int {
        val day = today()
        val current = periods.firstOrNull { it.activeOn(day) }
        if (current != null) return current.remaining()
        // 今天早于下一周期开始：先用上一周期剩余
        val upcoming = periods.filter { it.start > day }.minByOrNull { it.start }
        if (upcoming != null) {
            val last = periods.filter { it.end < day }.maxByOrNull { it.end }
            return last?.remaining() ?: 0
        }
        return 0
    }

    fun status(): String {
        val day = today()
        if (periods.isEmpty()) return "未激活"
        if (periods.any { it.activeOn(day) }) return "有效"
        val expiry = expiry()
        return if (expiry < day) "已过期" else "等待生效"
    }
}

/**
 * 授权状态合并（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * - 同一 order 重复应用（重放支付回跳）：保留已用额度，只刷新 pages/expires。
 * - 不同 order（新购/续费）：保留历史周期，新周期从 max(issued, 旧到期日) 开始。
 */
object LicenceMerge {

    const val MAX_PERIODS = 6

    fun merge(local: LicenceState?, incoming: LicenceState): LicenceState {
        if (local == null || local.periods.isEmpty()) return normalize(incoming)
        return if (incoming.order.isNotEmpty() && incoming.order == local.order) {
            normalize(replay(local, incoming))
        } else {
            normalize(renew(local, incoming))
        }
    }

    /** 同一笔授权被重复应用：used 取 max，绝不归零。 */
    private fun replay(local: LicenceState, incoming: LicenceState): LicenceState {
        val periods = local.periods.map { Period(it.start, it.end, it.pages, it.used) }.toMutableList()
        val incomingPeriod = incoming.periods.maxByOrNull { it.end }
        val index = periods.indices.maxByOrNull { periods[it].end } ?: return normalize(incoming)
        val target = periods[index]
        val pages = incomingPeriod?.pages?.takeIf { it > 0 } ?: target.pages
        val end = incomingPeriod?.end?.takeIf { it.length >= 10 } ?: target.end
        val used = maxOf(target.used, incomingPeriod?.used ?: 0)
        periods[index] = Period(target.start, maxOf(target.end, end), pages, used)
        return LicenceState(
            incoming.plan.ifEmpty { local.plan },
            periods,
            order = local.order.ifEmpty { incoming.order },
            issued = local.issued.ifEmpty { incoming.issued }
        )
    }

    /** 新购/续费：旧周期保留，新周期按套餐周期从旧到期日顺延。 */
    private fun renew(local: LicenceState, incoming: LicenceState): LicenceState {
        val newPeriod = incoming.periods.maxByOrNull { it.end } ?: return normalize(local)
        val oldExpiry = local.expiry()
        val issued = incoming.issued.takeIf { it.length >= 10 } ?: newPeriod.start
        val start = if (oldExpiry.length >= 10 && oldExpiry > issued) oldExpiry else issued
        val plan = incoming.plan.ifEmpty { local.plan }
        // 服务端每次下单都从"当天"起算，这里按套餐周期在旧到期日之后顺延
        val computed = addCycle(start, plan)
        val serverEnd = newPeriod.end
        val end = if (serverEnd.length >= 10 && serverEnd > computed) serverEnd else computed
        val periods = local.periods.map { Period(it.start, it.end, it.pages, it.used) }.toMutableList()
        periods.add(Period(start, end, newPeriod.pages, 0))
        while (periods.size > MAX_PERIODS) periods.removeAt(0)
        return LicenceState(
            plan,
            periods,
            order = incoming.order.ifEmpty { local.order },
            issued = incoming.issued.ifEmpty { local.issued }
        )
    }

    /** 在 ``start`` 之后追加一个套餐周期（月付 +1 月，年付 +12 月）。 */
    private fun addCycle(start: String, plan: String): String {
        if (start.length < 10) return start
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = fmt.parse(start) ?: return start
            val cal = Calendar.getInstance()
            cal.time = date
            val cycle = plan.substringAfterLast('_', "monthly")
            cal.add(Calendar.MONTH, if (cycle == "annual") 12 else 1)
            fmt.format(cal.time)
        } catch (e: Throwable) {
            start
        }
    }

    private fun normalize(state: LicenceState): LicenceState {
        val periods = state.periods.takeLast(MAX_PERIODS)
            .map { Period(it.start, it.end, it.pages, it.used) }
            .toMutableList()
        return LicenceState(state.plan, periods, state.order, state.issued)
    }
}

object LicenceVerifier {

    fun verify(context: Context, code: String): LicenceState? {
        val text = code.trim().removePrefix("STMTPDF1-")
        val blob = base32Decode(text)
        val idx = blob.indexOf(0.toByte())
        if (idx <= 0) return null
        val json = blob.copyOfRange(0, idx)
        val sig = blob.copyOfRange(idx + 1, blob.size)

        val pem = context.assets.open("keys/licence_public.pem").readBytes()
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pemToDer(pem)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(json)
        if (!verifier.verify(sig)) return null

        val payload = JSONObject(String(json, Charsets.UTF_8))
        if (payload.optInt("v", 0) != 1) return null
        val machine = payload.optString("machine", "")
        if (machine.isNotEmpty() && machine.uppercase() != MachineCode.formatted(context)) return null

        val plan = payload.optString("plan", "")
        val pages = payload.optInt("pages", 0)
        val expires = payload.optString("expires", "")
        if (pages <= 0 || expires.length < 10) return null

        val order = payload.optString("order", "")
        val rawIssued = payload.optString("issued", "")
        val issued = rawIssued.takeIf { it.length >= 10 && it[4] == '-' && it[7] == '-' }?.take(10)
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return LicenceState(
            plan,
            mutableListOf(Period(issued, expires, pages, 0)),
            order = order,
            issued = issued
        )
    }

    private fun pemToDer(pem: ByteArray): ByteArray {
        val text = String(pem, Charsets.UTF_8)
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(text, Base64.DEFAULT)
    }

    private fun base32Decode(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = text.uppercase().filter { alphabet.indexOf(it) >= 0 }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            buffer = (buffer shl 5) or alphabet.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
                // 清掉已消费的位，避免 Int 移位移出有效范围
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}

object LicenceStore {

    private const val PREFS = "stmtpdf_licence"
    private const val KEY_STATE = "state"
    private const val KEY_PENDING = "pending_order"

    fun load(context: Context): LicenceState? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("periods") ?: JSONArray()
            val periods = mutableListOf<Period>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                periods.add(
                    Period(
                        p.optString("start"),
                        p.optString("end"),
                        p.optInt("pages"),
                        p.optInt("used")
                    )
                )
            }
            // 旧数据没有 order/issued，读取时补空串
            LicenceState(
                json.optString("plan"),
                periods,
                json.optString("order", ""),
                json.optString("issued", "")
            )
        } catch (e: Throwable) {
            null
        }
    }

    fun save(context: Context, state: LicenceState) {
        val arr = JSONArray()
        state.periods.forEach { arr.put(it.toJson()) }
        val json = JSONObject().put("plan", state.plan).put("periods", arr)
            .put("order", state.order).put("issued", state.issued)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, json.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 待激活订单：支付回跳开始激活时写入，成功后才清除。 */
    fun pendingOrder(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, "") ?: ""

    fun savePendingOrder(context: Context, order: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING, order).apply()
    }

    fun clearPendingOrder(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING).apply()
    }

    /** 续费：新周期从上一周期结束日开始，页数不叠加（与 [LicenceMerge] 同一套规则）。 */
    fun appendRenewal(context: Context, state: LicenceState, pages: Int, end: String) {
        val start = state.expiry().ifEmpty {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }
        val incoming = LicenceState(
            state.plan,
            mutableListOf(Period(start, end, pages, 0)),
            order = "",
            issued = start
        )
        save(context, LicenceMerge.merge(state, incoming))
    }
}
