package com.stmtpdf.app.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 任务里的单个文件（一次解析可以有多份）。 */
data class HistoryFile(
    val id: Long,
    val name: String,
    val pages: Int,
    val rows: Int,
    val format: String = "PDF"
)

/** 一次解析 = 一个任务；最近记录按任务展示。 */
data class HistoryTask(
    val no: Int,
    val at: Long,
    val files: List<HistoryFile>
) {
    val pages: Int get() = files.sumOf { it.pages }
    val rows: Int get() = files.sumOf { it.rows }
    /** 任务内格式一致时显示该格式，混合格式显示 PDF。 */
    val format: String get() = files.map { it.format }.distinct().singleOrNull() ?: "PDF"
}

/**
 * 本地统计：最近任务与按月用量，仅存本机，不上传。
 */
object LocalStats {

    private const val PREFS = "stmtpdf_stats"
    private const val KEY_HISTORY = "history"
    private const val KEY_TASKS = "tasks"
    private const val KEY_SEQ = "task_seq"
    private const val KEY_USAGE = "usage"
    private const val KEY_TODAY = "usage_today"
    private const val KEY_TODAY_DATE = "usage_today_date"
    private const val KEY_ASK_SAVE = "ask_save_location"
    private const val KEY_EXPORT_DIR = "export_dir"
    private const val TASK_LIMIT = 10

    /** 最近任务，按时间倒序，最多 10 条。 */
    fun tasks(context: Context): List<HistoryTask> {
        val raw = prefs(context).getString(KEY_TASKS, null) ?: return migrate(context)
        return try {
            parseTasks(raw).sortedByDescending { it.at }.take(TASK_LIMIT)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** 分配新的任务号：只增不减，清空记录也不回退。 */
    fun nextTaskNo(context: Context): Int {
        val prefs = prefs(context)
        val seq = maxOf(prefs.getInt(KEY_SEQ, 0), tasks(context).maxOfOrNull { it.no } ?: 0) + 1
        prefs.edit().putInt(KEY_SEQ, seq).apply()
        return seq
    }

    /** 解析成功后把文件写入对应任务（按文件 id 去重；任务不存在则新建）。 */
    fun addTaskFile(
        context: Context,
        taskNo: Int,
        at: Long,
        id: Long,
        name: String,
        pages: Int,
        rows: Int
    ) {
        if (taskNo <= 0 || id <= 0L || name.isBlank()) return
        val file = HistoryFile(id, name, pages, rows)
        val cleaned = tasks(context)
            .map { task -> task.copy(files = task.files.filterNot { it.id == id }) }
            .filter { it.files.isNotEmpty() }
        val existing = cleaned.firstOrNull { it.no == taskNo }
        val updated = if (existing != null) {
            cleaned.map { task ->
                if (task.no == taskNo) task.copy(files = task.files + file) else task
            }
        } else {
            cleaned + HistoryTask(taskNo, at, listOf(file))
        }
        saveTasks(context, updated.sortedByDescending { it.at }.take(TASK_LIMIT))
    }

    /** 导出后更新该文件的格式标签（按文件 id 定位）。 */
    fun markExported(context: Context, id: Long, kind: String) {
        if (id <= 0L) return
        val list = tasks(context)
        if (list.isEmpty()) return
        val format = when (kind.lowercase(Locale.US)) {
            "qbo" -> "QBO"
            "qif" -> "QIF"
            "qfx" -> "QFX"
            "ofx" -> "OFX"
            "xlsx" -> "XLS"
            else -> "CSV"
        }
        saveTasks(
            context,
            list.map { task ->
                task.copy(files = task.files.map { if (it.id == id) it.copy(format = format) else it })
            }
        )
    }

    /** 删除单个文件；任务空了就一并移除。 */
    fun removeFile(context: Context, id: Long) {
        if (id <= 0L) return
        val list = tasks(context)
            .map { task -> task.copy(files = task.files.filterNot { it.id == id }) }
            .filter { it.files.isNotEmpty() }
        saveTasks(context, list)
    }

    /** 删除整个任务（落盘文件由 StatementStore 负责删除）。 */
    fun removeTask(context: Context, no: Int) {
        saveTasks(context, tasks(context).filterNot { it.no == no })
    }

    /** 清除全部最近记录（落盘文件由 StatementStore 负责删除）。 */
    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_TASKS).remove(KEY_HISTORY).apply()
    }

    /** 记录当月消耗页数。 */
    fun recordUsage(context: Context, pages: Int) {
        if (pages <= 0) return
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val obj = usageMap(context)
        obj.put(month, obj.optInt(month, 0) + pages)
        val today = dayKey()
        val prefs = prefs(context)
        val lastDay = prefs.getString(KEY_TODAY_DATE, "")
        val todayUsed = if (lastDay == today) prefs.getInt(KEY_TODAY, 0) else 0
        prefs.edit()
            .putString(KEY_USAGE, obj.toString())
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY, todayUsed + pages)
            .apply()
    }

    /** 今日已用页数（跨天自动归零）。 */
    fun todayUsage(context: Context): Int {
        val prefs = prefs(context)
        return if (prefs.getString(KEY_TODAY_DATE, "") == dayKey()) {
            prefs.getInt(KEY_TODAY, 0)
        } else {
            0
        }
    }

    private fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

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

    /** 旧版按文件存的记录：按时间顺序补任务号后迁移到任务结构。 */
    private fun migrate(context: Context): List<HistoryTask> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val tasks = runCatching {
            val arr = JSONArray(raw)
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryFile(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    pages = o.optInt("pages"),
                    rows = o.optInt("rows"),
                    format = o.optString("format", "PDF")
                ) to o.optLong("at")
            }.filter { it.first.id > 0L && it.first.name.isNotBlank() }
                .sortedBy { it.second }
            items.mapIndexed { index, pair ->
                HistoryTask(index + 1, pair.second, listOf(pair.first))
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
        if (tasks.isNotEmpty()) {
            saveTasks(context, tasks)
            prefs.edit().putInt(KEY_SEQ, tasks.maxOf { it.no }).apply()
        }
        prefs.edit().remove(KEY_HISTORY).apply()
        return tasks
    }

    private fun parseTasks(raw: String): List<HistoryTask> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val filesArr = o.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArr.length()).mapNotNull { j ->
                val f = filesArr.optJSONObject(j) ?: return@mapNotNull null
                HistoryFile(
                    id = f.optLong("id"),
                    name = f.optString("name"),
                    pages = f.optInt("pages"),
                    rows = f.optInt("rows"),
                    format = f.optString("format", "PDF")
                )
            }.filter { it.id > 0L && it.name.isNotBlank() }
            if (files.isEmpty()) return@mapNotNull null
            HistoryTask(o.optInt("no"), o.optLong("at"), files)
        }.filter { it.no > 0 }
    }

    private fun saveTasks(context: Context, list: List<HistoryTask>) {
        val arr = JSONArray()
        list.forEach { task ->
            val files = JSONArray()
            task.files.forEach {
                files.put(
                    JSONObject().put("id", it.id).put("name", it.name)
                        .put("pages", it.pages).put("rows", it.rows).put("format", it.format)
                )
            }
            arr.put(
                JSONObject().put("no", task.no).put("at", task.at).put("files", files)
            )
        }
        prefs(context).edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    /** 导出时是否每次让用户选择保存位置（默认关，自动存 下载/StmtPDF）。 */
    fun askSaveLocation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASK_SAVE, false)

    fun setAskSaveLocation(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASK_SAVE, value).apply()
    }

    /** 用户指定的导出目录（SAF tree URI 字符串），为空表示用默认「下载/StmtPDF」。 */
    fun exportDir(context: Context): String? =
        prefs(context).getString(KEY_EXPORT_DIR, null)

    fun setExportDir(context: Context, value: String?) {
        val editor = prefs(context).edit()
        if (value.isNullOrBlank()) editor.remove(KEY_EXPORT_DIR) else editor.putString(KEY_EXPORT_DIR, value)
        editor.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
