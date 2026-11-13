package com.stmtpdf.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stmtpdf.app.i18n.AppLang
import com.stmtpdf.app.i18n.I18n
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/* ---------------------------------------------------------------- 数据模型 */

/** 低于该置信度视为「低置信」。 */
internal const val REVIEW_LOW_CONF = 60

internal const val ISSUE_DIFF = "diff"
internal const val ISSUE_LOW = "low"

enum class DocStatus { BAD, IDLE, DONE }

/** 单条交易行：金额/余额/摘要可被用户修正；改动与 verified 会写成草稿落盘。 */
class ReviewRow(
    val page: Int?,
    val rawText: String,
    val bbox: List<Double>?,
    val conf: Int,
    val account: String,
    date: String,
    desc: String,
    amount: Double,
    balance: Double?
) {
    // 解析原值：用于判断这一行是否被用户改过（草稿只存改动的行）
    private val origDate = date
    private val origDesc = desc
    private val origAmount = amount
    private val origBalance = balance

    var date by mutableStateOf(date)
    var desc by mutableStateOf(desc)
    var amount by mutableStateOf(amount)
    var balance by mutableStateOf(balance)
    var issue by mutableStateOf<String?>(null)
    var verified by mutableStateOf(false)

    val hasIssue: Boolean get() = issue != null

    /** 是否被手动改过。 */
    val edited: Boolean
        get() = date != origDate || desc != origDesc ||
            amount != origAmount || balance != origBalance

    /** 是否需要进草稿：改过或已核对。 */
    val dirty: Boolean get() = edited || verified
}

/** 一次解析得到的文件，含原文按页文本用于抽屉上下文。 */
class ReviewDoc(
    val id: Long,
    val name: String,
    val baseName: String,
    val pages: Int,
    val totalPages: Int,
    val pageLines: List<List<String>>,
    val rows: List<ReviewRow>,
    val pdfPath: String = ""
) {
    val pending: Int get() = rows.count { it.issue != null && !it.verified }
    val unverified: Int get() = rows.count { !it.verified }
    val diffCount: Int get() = rows.count { it.issue == ISSUE_DIFF && !it.verified }
    val lowCount: Int get() = rows.count { it.issue == ISSUE_LOW && !it.verified }

    /** 有改动 / 有核对标记：退出前需要落草稿。 */
    val hasDraft: Boolean get() = rows.any { it.dirty }

    val status: DocStatus get() = when {
        pending > 0 -> DocStatus.BAD
        rows.isNotEmpty() && rows.all { it.verified } -> DocStatus.DONE
        else -> DocStatus.IDLE
    }
}

/** 差异判定与解析内核的勾稽口径保持一致（升序 / 降序两种都对）。 */
internal fun recomputeIssues(doc: ReviewDoc) {
    var previous: ReviewRow? = null
    doc.rows.forEach { row ->
        // 进入新账户段时不跨账户勾稽余额，避免多账户账单误报差异。
        if (previous != null && previous!!.account != row.account) { previous = null }
        val balance = row.balance
        val prev = previous
        var diff = false
        if (balance != null && prev?.balance != null) {
            val asc = prev.balance!! + row.amount
            val desc = prev.balance!! - prev.amount
            diff = abs(asc - balance) > 0.02 && abs(desc - balance) > 0.02
        }
        row.issue = when {
            diff -> ISSUE_DIFF
            row.conf < REVIEW_LOW_CONF -> ISSUE_LOW
            else -> null
        }
        previous = row
    }
}

/** 由 WebView 返回的 JSON 构建可校对文件；缺失 page/bbox 时自动降级。 */
internal fun buildReviewDoc(
    json: JSONObject,
    id: Long,
    displayName: String,
    pdfPath: String = "",
    draft: JSONObject? = null
): ReviewDoc {
    val pages = json.optInt("pages", 0)
    val totalPages = json.optInt("totalPages", pages)
    val arr = json.optJSONArray("rows") ?: JSONArray()
    val rows = ArrayList<ReviewRow>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val page = if (o.has("page") && !o.isNull("page")) o.optInt("page") else null
        val bboxArr = o.optJSONArray("bbox")
        val bbox = if (bboxArr != null && bboxArr.length() == 4) {
            (0 until 4).map { bboxArr.optDouble(it, 0.0) }
        } else {
            null
        }
        rows.add(
            ReviewRow(
                page = page,
                rawText = o.optString("raw_text"),
                bbox = bbox,
                conf = o.optInt("conf", 0),
                account = o.optString("account"),
                date = o.optString("date"),
                desc = o.optString("desc"),
                amount = o.optDouble("amount", 0.0),
                balance = if (o.isNull("balance")) null else o.optDouble("balance", 0.0)
            )
        )
    }
    val pageArr = json.optJSONArray("pageLines")
    val pageLines = if (pageArr == null) {
        emptyList()
    } else {
        (0 until pageArr.length()).map { index ->
            val lines = pageArr.optJSONArray(index) ?: return@map emptyList()
            (0 until lines.length()).map { lines.optString(it) }
        }
    }
    val doc = ReviewDoc(
        id = id,
        name = displayName,
        baseName = displayName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "statement" },
        pages = pages,
        totalPages = totalPages,
        pageLines = pageLines,
        rows = rows,
        pdfPath = pdfPath
    )
    // 有草稿时先套上用户的修改与核对标记，再按新值重算差异
    applyDraft(doc, draft)
    recomputeIssues(doc)
    return doc
}

/** 把草稿里的增量行套回文档（老草稿没有的字段保持解析原值）。 */
internal fun applyDraft(doc: ReviewDoc, draft: JSONObject?) {
    val arr = draft?.optJSONArray("rows") ?: return
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val row = doc.rows.getOrNull(o.optInt("i", -1)) ?: continue
        if (o.has("date")) row.date = o.optString("date", row.date)
        if (o.has("desc")) row.desc = o.optString("desc", row.desc)
        if (o.has("amount")) row.amount = o.optDouble("amount", row.amount)
        if (o.has("balance")) {
            row.balance = if (o.isNull("balance")) null else o.optDouble("balance", 0.0)
        }
        row.verified = o.optBoolean("verified", false)
    }
}

/** 落盘用：只导出被改过或已核对的行，附带行号。 */
internal fun ReviewDoc.draftRows(): JSONArray {
    val arr = JSONArray()
    rows.forEachIndexed { index, row ->
        if (!row.dirty) return@forEachIndexed
        arr.put(
            JSONObject().apply {
                put("i", index)
                put("date", row.date)
                put("desc", row.desc)
                put("amount", row.amount)
                put("balance", row.balance ?: JSONObject.NULL)
                put("verified", row.verified)
            }
        )
    }
    return arr
}

/** 导出用：只输出内核需要的字段，保持原有 QBO / CSV / XLSX 口径。 */
internal fun ReviewDoc.rowsJson(): String {
    val arr = JSONArray()
    rows.forEach { row ->
        arr.put(
            JSONObject().apply {
                put("date", row.date)
                put("desc", row.desc)
                put("amount", row.amount)
                put("balance", row.balance ?: JSONObject.NULL)
                put("conf", row.conf)
                put("account", row.account)
            }
        )
    }
    return arr.toString()
}

/** 从系统文件选择器 Uri 读取展示文件名。 */
internal fun displayNameOf(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: "statement.pdf"
}

internal fun reviewMoney(value: Double): String =
    (if (value < 0) "-" else "") + "$" + String.format(Locale.US, "%.2f", abs(value))

internal fun statusColor(status: DocStatus): Color = when (status) {
    DocStatus.BAD -> Pal.Danger
    DocStatus.DONE -> Pal.Teal
    DocStatus.IDLE -> Pal.Muted
}

internal fun statusLabel(lang: AppLang, status: DocStatus): String = I18n.t(
    lang,
    when (status) {
        DocStatus.BAD -> "doc_status_bad"
        DocStatus.DONE -> "doc_status_done"
        DocStatus.IDLE -> "doc_status_idle"
    }
)

/* ---------------------------------------------------------------- 文件切换 */

@Composable
internal fun FileSwitcher(
    docs: List<ReviewDoc>,
    activeId: Long,
    lang: AppLang,
    onSelect: (Long) -> Unit,
    onOpenPicker: () -> Unit
) {
    if (docs.size <= 3) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            docs.forEach { doc ->
                FilePill(doc, lang, doc.id == activeId) { onSelect(doc.id) }
            }
        }
    } else {
        val active = docs.firstOrNull { it.id == activeId } ?: docs.first()
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Pal.Panel)
                .border(1.dp, Pal.Line2, RoundedCornerShape(50))
                .clickable(onClick = onOpenPicker)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(statusColor(active.status), 7.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                active.name, color = Pal.Text, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                I18n.f(lang, "pending_label", active.pending),
                color = if (active.pending > 0) Pal.Danger else Pal.Muted,
                fontSize = 9.5.sp, fontWeight = FontWeight.Medium, maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            Text("▾", color = Pal.Faint, fontSize = 9.sp)
        }
    }
}

@Composable
private fun FilePill(doc: ReviewDoc, lang: AppLang, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) Pal.Teal.copy(alpha = 0.10f) else Pal.Panel)
            .border(
                1.dp,
                if (active) Pal.Teal.copy(alpha = 0.55f) else Pal.Line,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(statusColor(doc.status), 7.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            doc.name, color = if (active) Pal.Text else Pal.Muted, fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp)
        )
        if (doc.pending > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Pal.Danger.copy(alpha = 0.16f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text("${doc.pending}", color = Pal.Danger, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/* ---------------------------------------------------------------- 校对列表 */

@Composable
internal fun ReviewList(doc: ReviewDoc, lang: AppLang, onRowClick: (ReviewRow) -> Unit) {
    PanelCard {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 430.dp).padding(vertical = 4.dp)
        ) {
            itemsIndexed(doc.rows) { index, row ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF121B22)))
                }
                ReviewRowItem(row, lang) { onRowClick(row) }
            }
        }
    }
}

@Composable
internal fun ReviewRowItem(row: ReviewRow, lang: AppLang, onClick: () -> Unit = {}) {
    val accent = when {
        row.verified -> Pal.Teal
        row.issue == ISSUE_DIFF -> Pal.Danger
        row.issue == ISSUE_LOW -> Pal.Warm
        else -> Pal.Faint
    }
    val fill = when {
        row.verified -> Pal.Teal.copy(alpha = 0.04f)
        row.issue == ISSUE_DIFF -> Pal.Danger.copy(alpha = 0.08f)
        row.issue == ISSUE_LOW -> Pal.Warm.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp)).background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(accent, 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.date, color = Pal.Faint, fontSize = 9.5.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                row.desc.ifBlank { "—" }, color = Pal.Text, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                reviewMoney(row.amount),
                color = if (row.amount < 0) Pal.Danger else Pal.Text,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                I18n.f(lang, "balance_line", row.balance?.let { reviewMoney(it) } ?: "—"),
                color = Pal.Muted, fontSize = 9.5.sp
            )
        }
    }
}

@Composable
internal fun ReviewStats(doc: ReviewDoc, lang: AppLang) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatText(I18n.f(lang, "stat_total", doc.rows.size), Pal.Muted)
        StatText(
            I18n.f(lang, "stat_diff", doc.diffCount),
            if (doc.diffCount > 0) Pal.Danger else Pal.Faint
        )
        StatText(
            I18n.f(lang, "stat_low", doc.lowCount),
            if (doc.lowCount > 0) Pal.Warm else Pal.Faint
        )
        Spacer(Modifier.weight(1f))
        StatText(
            I18n.f(lang, "stat_unverified", doc.unverified),
            if (doc.unverified > 0) Pal.Teal else Pal.Faint
        )
    }
}

@Composable
private fun StatText(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/* ---------------------------------------------------------------- 底部抽屉 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewSheet(
    doc: ReviewDoc,
    row: ReviewRow,
    lang: AppLang,
    canGoPrev: Boolean,
    hasOtherDocs: Boolean,
    onDismiss: () -> Unit,
    onPrev: () -> Unit,
    onEdit: () -> Unit,
    onVerify: () -> Unit,
    onNextFile: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Pal.Panel,
        contentColor = Pal.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Pal.Line2) }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .navigationBarsPadding().padding(bottom = 18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.page != null) I18n.f(lang, "source_title", row.page)
                    else I18n.t(lang, "source_title_plain"),
                    color = Pal.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (hasOtherDocs) {
                    Text(
                        I18n.t(lang, "next_file"), color = Pal.Teal, fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .border(1.dp, Pal.Teal.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .clickable(onClick = onNextFile)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SourceContext(doc, row, lang)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldTile(I18n.t(lang, "field_date"), row.date, Modifier.weight(1f))
                FieldTile(I18n.t(lang, "field_amount"), reviewMoney(row.amount), Modifier.weight(1f))
                FieldTile(
                    I18n.t(lang, "field_balance"),
                    row.balance?.let { reviewMoney(it) } ?: "—",
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton(
                    I18n.t(lang, "prev_row"),
                    Modifier.weight(1f).height(44.dp)
                ) { if (canGoPrev) onPrev() }
                OutlineButton(
                    I18n.t(lang, "fix_row"),
                    Modifier.weight(1f).height(44.dp)
                ) { onEdit() }
                Box(
                    Modifier.weight(1.15f).height(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (row.verified) Pal.Teal.copy(alpha = 0.35f) else Pal.Teal)
                        .clickable(enabled = !row.verified, onClick = onVerify),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        I18n.t(lang, if (row.verified) "row_verified" else "confirm_row"),
                        color = Pal.Dark,
                        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceContext(doc: ReviewDoc, row: ReviewRow, lang: AppLang) {
    val window = remember(doc.id, row.page, row.rawText) { sourceWindow(doc, row) }
    val lines = window.first
    val current = window.second
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Pal.Tile).border(1.dp, Pal.Line, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp)
    ) {
        if (lines.isEmpty()) {
            Text(
                "—", color = Pal.Faint, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        } else {
            lines.forEachIndexed { index, line ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (index == current) Pal.Teal.copy(alpha = 0.10f) else Color.Transparent
                        )
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(2.dp).height(15.dp)
                            .background(if (index == current) Pal.Teal else Color.Transparent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        line, color = if (index == current) Pal.Text else Pal.Muted,
                        fontSize = 10.5.sp, lineHeight = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
            }
        }
        if (row.page == null) {
            Text(
                I18n.t(lang, "source_missing"), color = Pal.Faint, fontSize = 8.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun FieldTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Pal.Tile)
            .border(1.dp, Pal.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = Pal.Faint, fontSize = 8.5.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            value, color = Pal.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 原文窗口：当前行居中，最多三行；定位失败时只显示 raw_text。 */
internal fun sourceWindow(doc: ReviewDoc, row: ReviewRow): Pair<List<String>, Int> {
    val page = row.page
    val lines = if (page != null) doc.pageLines.getOrNull(page - 1) else null
    if (lines.isNullOrEmpty()) {
        val raw = row.rawText.trim()
        return if (raw.isEmpty()) emptyList<String>() to -1 else listOf(raw) to 0
    }
    val target = normalizeLine(row.rawText)
    var index = lines.indexOfFirst { normalizeLine(it) == target }
    if (index < 0 && target.isNotEmpty()) {
        val probe = target.take(16)
        index = lines.indexOfFirst { normalizeLine(it).contains(probe) }
    }
    if (index < 0) {
        val raw = row.rawText.trim()
        return if (raw.isEmpty()) emptyList<String>() to -1 else listOf(raw) to 0
    }
    val start = (index - 1).coerceAtLeast(0)
        .coerceAtMost((lines.size - 3).coerceAtLeast(0))
    val window = lines.subList(start, (start + 3).coerceAtMost(lines.size))
    return window.toList() to (index - start)
}

private fun normalizeLine(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

/* ---------------------------------------------------------------- 修正弹窗 */

@Composable
internal fun ReviewEditDialog(
    row: ReviewRow,
    lang: AppLang,
    onDismiss: () -> Unit,
    onSave: (date: String, desc: String, amount: String, balance: String) -> Unit
) {
    var date by remember { mutableStateOf(row.date) }
    var desc by remember { mutableStateOf(row.desc) }
    var amount by remember { mutableStateOf(String.format(Locale.US, "%.2f", row.amount)) }
    var balance by remember { mutableStateOf(row.balance?.let { String.format(Locale.US, "%.2f", it) } ?: "") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Panel,
        titleContentColor = Pal.Text,
        textContentColor = Pal.Muted,
        title = {
            Text(I18n.t(lang, "edit_title"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                EditField(I18n.t(lang, "field_date"), date) { date = it }
                Spacer(Modifier.height(8.dp))
                EditField(I18n.t(lang, "field_desc"), desc) { desc = it }
                Spacer(Modifier.height(8.dp))
                EditField(I18n.t(lang, "field_amount"), amount) { amount = it }
                Spacer(Modifier.height(8.dp))
                EditField(I18n.t(lang, "field_balance"), balance) { balance = it }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(date.trim(), desc.trim(), amount.trim(), balance.trim()) }
            ) {
                Text(I18n.t(lang, "save"), color = Pal.Teal, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(I18n.t(lang, "cancel"), color = Pal.Muted)
            }
        }
    )
}

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Pal.Faint, fontSize = 8.5.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp))
                .background(Pal.Tile).border(1.dp, Pal.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Pal.Text, fontSize = 12.sp),
                cursorBrush = SolidColor(Pal.Teal),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* ---------------------------------------------------------------- 文件选择弹窗 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePickerSheet(
    docs: List<ReviewDoc>,
    activeId: Long,
    lang: AppLang,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sorted = remember(docs, query) {
        docs.asSequence()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedWith(
                compareBy<ReviewDoc> {
                    when (it.status) {
                        DocStatus.BAD -> 0
                        DocStatus.IDLE -> 1
                        DocStatus.DONE -> 2
                    }
                }.thenBy { it.id }
            )
            .toList()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Pal.Panel,
        contentColor = Pal.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Pal.Line2) }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)
                .navigationBarsPadding().padding(bottom = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    I18n.t(lang, "file_switch"), color = Pal.Text,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    I18n.f(lang, "file_count", docs.size),
                    color = Pal.Muted, fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "✕", color = Pal.Faint, fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss)
                        .padding(8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(Pal.Tile).border(1.dp, Pal.Line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                I18n.t(lang, "file_search"),
                                color = Pal.Faint, fontSize = 11.sp
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Pal.Text, fontSize = 11.5.sp),
                            cursorBrush = SolidColor(Pal.Teal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) {
                        Text(
                            "✕", color = Pal.Faint, fontSize = 10.sp,
                            modifier = Modifier.clickable { query = "" }.padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    I18n.t(lang, "file_sort_issues"), color = Pal.Muted,
                    fontSize = 9.5.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(6.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(sorted, key = { it.id }) { doc ->
                    PickerRow(doc, lang, doc.id == activeId) { onPick(doc.id) }
                }
            }
            Text(
                I18n.t(lang, "file_picker_hint"), color = Pal.Faint, fontSize = 8.5.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun PickerRow(doc: ReviewDoc, lang: AppLang, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Pal.Teal.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(statusColor(doc.status), 7.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                doc.name, color = if (active) Pal.Text else Pal.Text, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            val meta = if (doc.pending > 0) I18n.f(lang, "pending_label", doc.pending)
            else statusLabel(lang, doc.status)
            Text(
                I18n.f(lang, "file_meta", doc.pages, meta),
                color = Pal.Muted, fontSize = 9.5.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(doc.status, lang)
    }
}

@Composable
private fun StatusPill(status: DocStatus, lang: AppLang) {
    val color = statusColor(status)
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            statusLabel(lang, status), color = color, fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium, maxLines = 1
        )
    }
}

@Composable
private fun StatusDot(color: Color, size: Dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}
