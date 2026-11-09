package com.stmtpdf.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stmtpdf.app.i18n.AppLang
import com.stmtpdf.app.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val PDF_RENDER_WIDTH = 1080
private const val SPLIT_MIN = 0.25f
private const val SPLIT_MAX = 0.80f

/** PdfRenderer 渲染结果；bitmap 为空表示该页无法渲染。 */
private data class PdfRender(val bitmap: Bitmap?, val loading: Boolean)

/** 打开 PDF 渲染指定页；失败（文件被清理 / 加密）返回 null。 */
private fun renderPdfPage(path: String, index: Int): Bitmap? {
    if (path.isBlank()) return null
    val file = File(path)
    if (!file.exists()) return null
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null
    return try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
        if (renderer.pageCount <= 0) return null
        page = renderer.openPage(index.coerceIn(0, renderer.pageCount - 1))
        val scale = (PDF_RENDER_WIDTH.toFloat() / page.width).coerceAtMost(2.5f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    } catch (e: Throwable) {
        null
    } finally {
        runCatching { page?.close() }
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}

@Composable
private fun PdfPageView(pdfPath: String, page: Int, lang: AppLang, modifier: Modifier) {
    val state by produceState(PdfRender(null, true), pdfPath, page) {
        val bitmap = withContext(Dispatchers.IO) { renderPdfPage(pdfPath, page) }
        value = PdfRender(bitmap, false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = state.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                contentScale = ContentScale.Fit
            )
            state.loading -> CircularProgressIndicator(Modifier.size(20.dp), color = Pal.Teal, strokeWidth = 1.5.dp)
            else -> Text(
                I18n.t(lang, "pdf_unavailable"),
                color = Pal.Faint, fontSize = 10.5.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/**
 * 全屏对比页：上方 PDF 原页、下方识别行表，中间可拖动分隔；
 * 顶部文件名 + 文件筛选胶囊，不做左右滑动切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompareScreen(
    docs: List<ReviewDoc>,
    activeId: Long,
    lang: AppLang,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit
) {
    val doc = docs.firstOrNull { it.id == activeId } ?: return
    var pageIndex by remember(doc.id) { mutableStateOf(0) }
    var split by remember { mutableStateOf(0.58f) }
    var showAll by remember { mutableStateOf(false) }
    var selIndex by remember(doc.id) { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<ReviewRow?>(null) }
    val pageCount = maxOf(doc.totalPages, doc.pages, 1)
    val currentPage = pageIndex.coerceIn(0, pageCount - 1)
    val density = LocalDensity.current

    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        CompareHeader(doc, lang, onClose, onExport)
        CompareFileChips(docs, doc.id, lang, onSelect) { showAll = true }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val boxHeight = maxHeight
            val heightPx = with(density) { boxHeight.toPx() }.coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(boxHeight * split)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Pal.Tile)
                        .border(1.dp, Pal.Line, RoundedCornerShape(16.dp))
                ) {
                    PdfPageView(doc.pdfPath, currentPage, lang, Modifier.fillMaxSize())
                    if (selIndex >= 0) {
                        Box(
                            Modifier.align(Alignment.TopStart).padding(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Pal.Teal.copy(alpha = 0.18f))
                                .border(1.dp, Pal.Teal.copy(alpha = 0.65f), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "#${selIndex + 1}", color = Pal.Teal,
                                fontSize = 9.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (pageCount > 1) {
                        PageBar(
                            page = currentPage,
                            count = pageCount,
                            onPrev = { if (currentPage > 0) pageIndex = currentPage - 1 },
                            onNext = { if (currentPage < pageCount - 1) pageIndex = currentPage + 1 },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        )
                    }
                }

                SplitHandle { delta ->
                    split = (split + delta / heightPx).coerceIn(SPLIT_MIN, SPLIT_MAX)
                }

                RowsPanel(
                    doc, lang, selIndex,
                    onSelect = { index ->
                        selIndex = index
                        doc.rows.getOrNull(index)?.page?.let { page ->
                            pageIndex = (page - 1).coerceIn(0, pageCount - 1)
                        }
                    },
                    onEdit = { index ->
                        selIndex = index
                        editing = doc.rows.getOrNull(index)
                    },
                    onToggle = { index ->
                        doc.rows.getOrNull(index)?.let { row ->
                            row.verified = !row.verified
                            recomputeIssues(doc)
                        }
                    },
                    Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                )
            }
        }
    }

    if (showAll) {
        FilePickerSheet(
            docs = docs,
            activeId = activeId,
            lang = lang,
            onPick = {
                showAll = false
                onSelect(it)
            },
            onDismiss = { showAll = false }
        )
    }

    editing?.let { row ->
        ReviewEditDialog(
            row = row,
            lang = lang,
            onDismiss = { editing = null },
            onSave = { date, desc, amount, balance ->
                row.date = date
                row.desc = desc
                amount.replace(",", "").toDoubleOrNull()?.let { row.amount = it }
                row.balance = balance.replace(",", "").toDoubleOrNull()
                row.verified = true
                recomputeIssues(doc)
                editing = null
            }
        )
    }
}

@Composable
private fun CompareHeader(
    doc: ReviewDoc,
    lang: AppLang,
    onClose: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Pal.Panel)
                .border(1.dp, Pal.Line2, CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = Pal.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                doc.name, color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                I18n.f(lang, "review_title", doc.pages, doc.totalPages) + " · " +
                    I18n.f(lang, "review_rows", doc.rows.size),
                color = Pal.Faint, fontSize = 9.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Pal.Teal.copy(alpha = 0.10f))
                .border(1.dp, Pal.Teal.copy(alpha = 0.55f), RoundedCornerShape(50))
                .clickable(onClick = onExport)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                I18n.t(lang, "export"), color = Pal.Teal, fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 文件切换：横向滚动的筛选胶囊 + 文件过多时的「全部 N 个文件」入口。 */
@Composable
private fun CompareFileChips(
    docs: List<ReviewDoc>,
    activeId: Long,
    lang: AppLang,
    onSelect: (Long) -> Unit,
    onOpenAll: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        docs.forEach { item ->
            DocChip(item.name, item.id == activeId) { onSelect(item.id) }
        }
        if (docs.size > 5) {
            DocChip(I18n.f(lang, "all_files", docs.size), false, onClick = onOpenAll)
        }
    }
}

@Composable
private fun DocChip(name: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) Pal.Teal.copy(alpha = 0.12f) else Pal.Panel)
            .border(
                1.dp,
                if (active) Pal.Teal.copy(alpha = 0.55f) else Pal.Line,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            name, color = if (active) Pal.Text else Pal.Muted, fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp)
        )
    }
}

@Composable
private fun SplitHandle(onDrag: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(22.dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(46.dp).height(4.dp).clip(CircleShape).background(Pal.Line2)
        )
    }
}

@Composable
private fun PageBar(
    page: Int,
    count: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xE60B1118))
            .border(1.dp, Pal.Line2, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "‹", color = if (page > 0) Pal.Teal else Pal.Faint, fontSize = 15.sp,
            modifier = Modifier.clip(CircleShape).clickable(enabled = page > 0, onClick = onPrev)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
        Text(
            "${page + 1} / $count", color = Pal.Text,
            fontSize = 10.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "›", color = if (page < count - 1) Pal.Teal else Pal.Faint, fontSize = 15.sp,
            modifier = Modifier.clip(CircleShape).clickable(enabled = page < count - 1, onClick = onNext)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RowsPanel(
    doc: ReviewDoc,
    lang: AppLang,
    selIndex: Int,
    onSelect: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Pal.Panel)
            .border(1.dp, Pal.Line, RoundedCornerShape(16.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                I18n.t(lang, "field_date") + " · " + I18n.t(lang, "field_desc"),
                color = Pal.Faint, fontSize = 8.5.sp, letterSpacing = 1.sp, maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                I18n.t(lang, "field_amount") + " · " + I18n.t(lang, "field_balance"),
                color = Pal.Faint, fontSize = 8.5.sp, letterSpacing = 1.sp, maxLines = 1
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF121B22)))
        if (doc.rows.isEmpty()) {
            Text(
                "—", color = Pal.Faint, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
                itemsIndexed(doc.rows) { index, row ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF121B22)))
                    }
                    CompareRow(
                        row = row,
                        lang = lang,
                        selected = index == selIndex,
                        onTap = { onSelect(index) },
                        onDoubleTap = { onEdit(index) },
                        onLongPress = { onToggle(index) }
                    )
                }
            }
        }
    }
}

/** 行：单击定位原文并选中，双击编辑，长按切换已核对。 */
@Composable
private fun CompareRow(
    row: ReviewRow,
    lang: AppLang,
    selected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val accent = when {
        row.verified -> Pal.Teal
        row.issue == ISSUE_DIFF -> Pal.Danger
        row.issue == ISSUE_LOW -> Pal.Warm
        else -> Pal.Faint
    }
    val fill = when {
        selected -> Pal.Teal.copy(alpha = 0.10f)
        row.verified -> Pal.Teal.copy(alpha = 0.04f)
        row.issue == ISSUE_DIFF -> Pal.Danger.copy(alpha = 0.08f)
        row.issue == ISSUE_LOW -> Pal.Warm.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp)).background(fill)
            .pointerInput(row) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
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
