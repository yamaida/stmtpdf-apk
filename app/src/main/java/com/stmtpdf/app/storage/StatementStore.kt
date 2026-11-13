package com.stmtpdf.app.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 本地保存的一次解析结果：原 PDF 副本 + 解析 JSON。 */
data class StatementRecord(
    val name: String,
    val pdfPath: String,
    val json: JSONObject
)

/** 校对草稿的元信息：首页「最近记录」角标用。 */
data class DraftMeta(
    val updatedAt: Long,
    val unverified: Int,
    val pending: Int
)

/**
 * 解析结果落盘：filesDir/statements/<id>.pdf 与 <id>.json，
 * 供「最近记录」直接打开对比页，无需重新导入解析。
 *
 * 用户的修改与核对标记另存 <id>.draft.json（增量，只记改过的行），
 * 保留 7 天；解析原始 JSON 不被覆盖，重解析仍能拿到干净结果。
 */
object StatementStore {

    private const val DIR = "statements"

    /** 草稿保留期：与网页端「最近记录」保持一致，7 天。 */
    const val DRAFT_TTL = 7L * 24 * 60 * 60 * 1000

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun draftFile(context: Context, id: Long): File = File(dir(context), "$id.draft.json")

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

    /* ---------------------------------------------------------------- 草稿 */

    /** 写入草稿：rows 为增量行（只含被修改或已核对的行）。 */
    fun saveDraft(
        context: Context,
        id: Long,
        rows: JSONArray,
        unverified: Int,
        pending: Int
    ) {
        if (id <= 0L) return
        val wrapper = JSONObject()
            .put("v", 1)
            .put("updatedAt", System.currentTimeMillis())
            .put("unverified", unverified)
            .put("pending", pending)
            .put("rows", rows)
        runCatching { draftFile(context, id).writeText(wrapper.toString()) }
    }

    fun loadDraft(context: Context, id: Long): JSONObject? = runCatching {
        val file = draftFile(context, id)
        if (!file.exists()) return@runCatching null
        JSONObject(file.readText())
    }.getOrNull()

    /** 所有有效草稿的元信息（按文件 id），过期草稿顺带清掉。 */
    fun draftMetas(context: Context): Map<Long, DraftMeta> {
        val files = dir(context).listFiles()?.filter { it.name.endsWith(".draft.json") } ?: return emptyMap()
        val now = System.currentTimeMillis()
        val out = HashMap<Long, DraftMeta>()
        files.forEach { file ->
            val id = file.name.removeSuffix(".draft.json").toLongOrNull()
            val meta = runCatching {
                val o = JSONObject(file.readText())
                DraftMeta(
                    updatedAt = o.optLong("updatedAt", file.lastModified()),
                    unverified = o.optInt("unverified", 0),
                    pending = o.optInt("pending", 0)
                )
            }.getOrNull()
            when {
                meta == null -> runCatching { file.delete() }
                now - meta.updatedAt > DRAFT_TTL -> runCatching { file.delete() }
                id == null -> runCatching { file.delete() }
                else -> out[id] = meta
            }
        }
        return out
    }

    fun deleteDraft(context: Context, id: Long) {
        runCatching { draftFile(context, id).delete() }
    }

    fun delete(context: Context, id: Long) {
        File(dir(context), "$id.json").delete()
        File(dir(context), "$id.pdf").delete()
        draftFile(context, id).delete()
    }

    /** 清除记录：删除落盘的解析结果与解析时产生的 PDF 缓存副本。 */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "pdf").listFiles()?.forEach { it.delete() }
    }
}
