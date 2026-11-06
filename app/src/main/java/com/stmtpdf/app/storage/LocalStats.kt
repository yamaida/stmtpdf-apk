package com.stmtpdf.app.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val name: String,
    val pages: Int,
    val rows: Int,
    val format: String,
    val at: Long
)

/**
 * 本地统计：最近转换记录与按月用量，仅存本机，不上传。
 */
object LocalStats {

    private const val PREFS = "stmtpdf_stats"
    private const val KEY_HISTORY = "history"
    private const val KEY_USAGE = "usage"
    private const val HISTORY_LIMIT = 8

    fun history(context: Context): List<HistoryItem> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryItem(
                    name = o.optString("name"),
                    pages = o.optInt("pages"),
                    rows = o.optInt("rows"),
                    format = o.optString("format", "PDF"),
                    at = o.optLong("at")
                )
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** 解析成功后写入最近记录（同名文件去重后置顶）。 */
    fun addConversion(context: Context, name: String, pages: Int, rows: Int) {
        if (name.isBlank()) return
        val list = history(context).filterNot { it.name == name }.toMutableList()
        list.add(
            0,
            HistoryItem(name, pages, rows, "PDF", System.currentTimeMillis())
        )
        saveHistory(context, list.take(HISTORY_LIMIT))
    }

    /** 导出后更新该文件的格式标签。 */
    fun markExported(context: Context, name: String, kind: String) {
        val list = history(context)
        if (list.isEmpty()) return
        val format = when (kind.lowercase(Locale.US)) {
            "qbo" -> "QBO"
            "xlsx" -> "XLS"
            else -> "CSV"
        }
        val updated = list.map { if (it.name == name) it.copy(format = format) else it }
        saveHistory(context, updated)
    }

    /** 记录当月消耗页数。 */
    fun recordUsage(context: Context, pages: Int) {
        if (pages <= 0) return
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val obj = usageMap(context)
        obj.put(month, obj.optInt(month, 0) + pages)
        prefs(context).edit().putString(KEY_USAGE, obj.toString()).apply()
    }

    /** 最近 N 个月用量，按时间升序，返回 (MM, pages)。 */
    fun monthlyUsage(context: Context, months: Int = 6): List<Pair<String, Int>> {
        val obj = usageMap(context)
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -(months - 1))
        return (0 until months).map {
            val key = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
            val label = SimpleDateFormat("MM", Locale.US).format(cal.time)
            cal.add(Calendar.MONTH, 1)
            label to obj.optInt(key, 0)
        }
    }

    private fun usageMap(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_USAGE, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Throwable) {
            JSONObject()
        }
    }

    private fun saveHistory(context: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("pages", it.pages)
                    .put("rows", it.rows).put("format", it.format).put("at", it.at)
            )
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
