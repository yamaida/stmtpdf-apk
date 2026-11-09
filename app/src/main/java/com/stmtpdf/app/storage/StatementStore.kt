package com.stmtpdf.app.storage

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 本地保存的一次解析结果：原 PDF 副本 + 解析 JSON。 */
data class StatementRecord(
    val name: String,
    val pdfPath: String,
    val json: JSONObject
)

/**
 * 解析结果落盘：filesDir/statements/<id>.pdf 与 <id>.json，
 * 供「最近记录」直接打开对比页，无需重新导入解析。
 */
object StatementStore {

    private const val DIR = "statements"

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun save(context: Context, id: Long, name: String, sourcePdf: File, json: JSONObject): String {
        val target = File(dir(context), "$id.pdf")
        runCatching { if (sourcePdf.exists()) sourcePdf.copyTo(target, overwrite = true) }
        val wrapper = JSONObject()
            .put("name", name)
            .put("pdf", target.absolutePath)
            .put("data", json)
        runCatching { File(dir(context), "$id.json").writeText(wrapper.toString()) }
        return target.absolutePath
    }

    fun load(context: Context, id: Long): StatementRecord? = runCatching {
        val file = File(dir(context), "$id.json")
        if (!file.exists()) return null
        val wrapper = JSONObject(file.readText())
        val data = wrapper.optJSONObject("data") ?: return null
        val pdf = wrapper.optString("pdf")
        if (pdf.isBlank() || !File(pdf).exists()) return null
        StatementRecord(
            name = wrapper.optString("name").ifBlank { "statement.pdf" },
            pdfPath = pdf,
            json = data
        )
    }.getOrNull()

    fun delete(context: Context, id: Long) {
        File(dir(context), "$id.json").delete()
        File(dir(context), "$id.pdf").delete()
    }

    /** 清除记录：删除落盘的解析结果与解析时产生的 PDF 缓存副本。 */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "pdf").listFiles()?.forEach { it.delete() }
    }
}
