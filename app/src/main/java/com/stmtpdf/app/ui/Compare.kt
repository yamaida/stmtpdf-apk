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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stmtpdf.app.i18n.AppLang
import com.stmtpdf.app.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val PDF_RENDER_WIDTH = 1080
private const val SPLIT_MIN = 0.25f
private const val SPLIT_MAX = 0.80f

/** PdfRenderer 渲染结果；bitmap 为空表示该页无法渲染。宽高为 PDF 点（1/72 英寸）。 */
private data class PdfRender(
    val bitmap: Bitmap?,
    val loading: Boolean,
    val pageWidth: Float = 0f,
    val pageHeight: Float = 0f
)

/** 一页的渲染几何：用于把识别行的 bbox 换算成屏幕坐标。 */
private data class PdfGeom(
    val page: Int,
    val pageWidth: Float,
    val pageHeight: Float,
    val bitmapWidth: Int,
    val bitmapHeight: Int
)

/** 打开 PDF 渲染指定页；失败（文件被清理 / 加密）返回空 bitmap。 */
private fun renderPdfPage(path: String, index: Int): PdfRender {
    if (path.isBlank()) return PdfRender(null, false)
    val file = File(path)
    if (!file.exists()) return PdfRender(null, false)
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null
    return try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
        if (renderer.pageCount <= 0) return PdfRender(null, false)
        page = renderer.openPage(index.coerceIn(0, renderer.pageCount - 1))
        val scale = (PDF_RENDER_WIDTH.toFloat() / page.width).coerceAtMost(2.5f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        PdfRender(bitmap, false, page.width.toFloat(), page.height.toFloat())
    } catch (e: Throwable) {
        PdfRender(null, false)
    } finally {
        runCatching { page?.close() }
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}

/** 把行 bbox（PDF 点、原点左下）换算成容器内像素矩形，与 ContentScale.Fit 的显示区域对齐。 */
private fun rowRect(bbox: List<Double>?, geom: PdfGeom, box: IntSize, padPx: Float): Rect? {
    if (bbox == null || bbox.size != 4) return null
    if (geom.pageWidth <= 0f || geom.pageHeight <= 0f) return null
    if (geom.bitmapWidth <= 0 || geom.bitmapHeight <= 0) return null
    val availW = box.width - padPx * 2
    val availH = box.height - padPx * 2
    if (availW <= 0f || availH <= 0f) return null
    val fit = minOf(
        availW / geom.bitmapWidth.toFloat(),
        availH / geom.bitmapHeight.toFloat()
    )
    val drawW = geom.bitmapWidth * fit
    val drawH = geom.bitmapHeight * fit
    val left = padPx + (availW - drawW) / 2f
    val top = padPx + (availH - drawH) / 2f
    val sx = drawW / geom.pageWidth
    val sy = drawH / geom.pageHeight
    val x1 = left + bbox[0].toFloat() * sx
    val x2 = left + bbox[2].toFloat() * sx
    val y1 = top + (geom.pageHeight - bbox[3].toFloat()) * sy
    val y2 = top + (geom.pageHeight - bbox[1].toFloat()) * sy
    val w = (x2 - x1).coerceAtLeast(6f)
    val h = (y2 - y1).coerceAtLeast(6f)
    return Rect(x1, y1, x1 + w, y1 + h)
}

@Composable
private fun PdfPageView(
    pdfPath: String,
    page: Int,
    lang: AppLang,
    modifier: Modifier,
    onGeom: (PdfGeom) -> Unit = {}
) {
    val geomCb by rememberUpdatedState(onGeom)
    val state by produceState(PdfRender(null, true), pdfPath, page) {
        val render = withContext(Dispatchers.IO) { renderPdfPage(pdfPath, page) }
        value = render
        val bitmap = render.bitmap
        if (bitmap != null && render.pageWidth > 0f && render.pageHeight > 0f) {
            geomCb(
                PdfGeom(page + 1, render.pageWidth, render.pageHeight, bitmap.width, bitmap.height)
            )
        }
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
    val doc = docs.firstOrNull { it.id == activeId }
    if (doc == null) {
        // 文档已被清除或 id 失配：主动退出，避免父层 compareId 卡在校对状态
        LaunchedEffect(activeId) { onClose() }
        return
    }
    var pageIndex by remember(doc.id) { mutableStateOf(0) }
    var split by remember { mutableStateOf(0.58f) }
    var showAll by remember { mutableStateOf(false) }
    var selIndex by remember(doc.id) { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<ReviewRow?>(null) }
    var pdfScale by remember(doc.id) { mutableStateOf(1f) }
    var pdfOffset by remember(doc.id) { mutableStateOf(Offset.Zero) }
    var pdfBox by remember { mutableStateOf(IntSize.Zero) }
    var pdfGeom by remember(doc.id) { mutableStateOf<PdfGeom?>(null) }
    var locateTick by remember(doc.id) { mutableStateOf(0) }
    var focusedTick by remember(doc.id) { mutableStateOf(-1) }
    val pageCount = maxOf(doc.totalPages, doc.pages, 1)
    val currentPage = pageIndex.coerceIn(0, pageCount - 1)
    val density = LocalDensity.current
    val pdfPadPx = with(density) { 6.dp.toPx() }

    // 手动翻页时回到居中，避免仍停留在上一页的偏移
    LaunchedEffect(currentPage) {
        pdfOffset = Offset.Zero
    }

    // 点击交易行：把原文窗口缩放到该行位置并居中，等效网页端的滚动定位
    LaunchedEffect(locateTick, pdfGeom, pdfBox) {
        if (locateTick <= 0 || focusedTick == locateTick) return@LaunchedEffect
        val geom = pdfGeom ?: return@LaunchedEffect
        if (geom.page != currentPage + 1) return@LaunchedEffect
        if (pdfBox.width <= 0 || pdfBox.height <= 0) return@LaunchedEffect
        val rect = rowRect(doc.rows.getOrNull(selIndex)?.bbox, geom, pdfBox, pdfPadPx)
            ?: return@LaunchedEffect
        val target = maxOf(pdfScale, 1.8f)
        val maxX = pdfBox.width * (target - 1f) / 2f
        val maxY = pdfBox.height * (target - 1f) / 2f
        pdfScale = target
        pdfOffset = Offset(
            (pdfBox.width / 2f - rect.center.x * target).coerceIn(-maxX, maxX),
            (pdfBox.height / 2f - rect.center.y * target).coerceIn(-maxY, maxY)
        )
        focusedTick = locateTick
    }

    // 当前选中行在原文中的高亮框（随缩放/平移一起变换）
    val highlight = remember(selIndex, pdfGeom, pdfBox, currentPage, density) {
        val geom = pdfGeom
        if (geom == null || geom.page != currentPage + 1) {
            null
        } else {
            rowRect(doc.rows.getOrNull(selIndex)?.bbox, geom, pdfBox, pdfPadPx)
        }
    }

    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        val docNumber = docs.indexOfFirst { it.id == doc.id } + 1
        CompareHeader(doc, docNumber.coerceAtLeast(1), lang, onClose)
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
                    Box(
                        Modifier.fillMaxSize()
                            .onSizeChanged { pdfBox = it }
                            .pointerInput(currentPage) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val next = (pdfScale * zoom).coerceIn(1f, 4f)
                                    val maxX = pdfBox.width * (next - 1f) / 2f
                                    val maxY = pdfBox.height * (next - 1f) / 2f
                                    pdfOffset = Offset(
                                        (pdfOffset.x + pan.x).coerceIn(-maxX, maxX),
                                        (pdfOffset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                    pdfScale = next
                                }
                            }
                    ) {
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                scaleX = pdfScale
                                scaleY = pdfScale
                                translationX = pdfOffset.x
                                translationY = pdfOffset.y
                            }
                        ) {
                            PdfPageView(doc.pdfPath, currentPage, lang, Modifier.fillMaxSize()) {
                                pdfGeom = it
                            }
                            if (highlight != null) {
                                // 细线画在行下沿之外，避免遮住原文数值
                                Box(
                                    Modifier.offset {
                                        IntOffset(
                                            highlight.left.roundToInt(),
                                            (highlight.bottom + with(density) { 2.dp.toPx() })
                                                .roundToInt()
                                        )
                                    }
                                        .size(
                                            with(density) { highlight.width.toDp() },
                                            1.dp
                                        )
                                        .background(Pal.Teal.copy(alpha = 0.9f))
                                )
                            }
                        }
                    }
                    if (pdfScale > 1.02f) {
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Pal.Panel.copy(alpha = 0.9f))
                                .border(1.dp, Pal.Line2, RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(
                                String.format(java.util.Locale.US, "%.1fx", pdfScale),
                                color = Pal.Muted, fontSize = 9.sp
                            )
                        }
                    }
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

                // 行列表区支持右滑返回解析页（PDF 预览区留给缩放/平移手势）
                Box(
                    Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 2.dp)
                        .pointerInput(Unit) {
                            var drag = 0f
                            var fromEdge = false
                            val threshold = 72.dp.toPx()
                            val edge = 48.dp.toPx()
                            detectHorizontalDragGestures(
                                // 只认从左边缘起手的右滑，避免长按后横向拖动误退出
                                onDragStart = { offset ->
                                    drag = 0f
                                    fromEdge = offset.x <= edge
                                },
                                onDragCancel = { drag = 0f },
                                onHorizontalDrag = { _, amount -> drag += amount },
                                onDragEnd = {
                                    if (fromEdge && drag > threshold) onClose()
                                    drag = 0f
                                }
                            )
                        }
                ) {
                    RowsPanel(
                        doc, lang, selIndex,
                        onSelect = { index ->
                            selIndex = index
                            doc.rows.getOrNull(index)?.page?.let { page ->
                                pageIndex = (page - 1).coerceIn(0, pageCount - 1)
                            }
                            locateTick += 1
                            // 移动端无 hover：单击行即弹编辑框（双击仍保留）
                            editing = doc.rows.getOrNull(index)
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
                        Modifier.fillMaxSize()
                    )
                }
            }
        }

        CompareFooter(
            doc = doc,
            lang = lang,
            selIndex = selIndex,
            onJump = { index ->
                selIndex = index
                doc.rows.getOrNull(index)?.page?.let { p ->
                    pageIndex = (p - 1).coerceIn(0, pageCount - 1)
                }
                locateTick += 1
            },
            onExport = onExport
        )
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
                // 保存即视为已修改并自动核对：绿点 + 杏黄标记并存
                row.editedMark = true
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
    number: Int,
    lang: AppLang,
    onClose: () -> Unit
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
            Text("‹", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // 文件名带序号，多份对账单时方便对照
            Text(
                String.format(java.util.Locale.US, "%02d · %s", number, doc.name),
                color = Pal.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                I18n.f(
                    lang, "review_meta_diff",
                    doc.totalPages.coerceAtLeast(doc.pages), doc.rows.size, doc.diffCount
                ),
                color = Pal.Faint, fontSize = 9.sp
            )
        }
    }
}

/** 底部统计 + 「下一处差异 / 导出」两个等宽按钮；导出入库从右上角移到这里。 */
@Composable
private fun CompareFooter(
    doc: ReviewDoc,
    lang: AppLang,
    selIndex: Int,
    onJump: (Int) -> Unit,
    onExport: () -> Unit
) {
    val pending = doc.rows.indices.filter {
        val row = doc.rows[it]
        row.hasIssue && !row.verified
    }
    val next = pending.firstOrNull { it > selIndex } ?: pending.firstOrNull()
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
    ) {
        Text(
            I18n.f(
                lang, "review_stats_line", doc.rows.size,
                doc.rows.count { it.verified }, doc.diffCount, doc.lowCount
            ),
            color = Pal.Faint, fontSize = 9.5.sp, maxLines = 1
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(Pal.Panel)
                    .border(
                        1.dp,
                        if (next != null) Pal.Teal.copy(alpha = 0.75f) else Pal.Line2,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = next != null) { next?.let(onJump) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    I18n.t(lang, "review_next_diff"),
                    color = if (next != null) Pal.Teal else Pal.Muted,
                    fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(Pal.accentGradient)
                    .clickable(onClick = onExport),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    I18n.t(lang, "export"), color = Pal.Dark,
                    fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold
                )
            }
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
        docs.forEachIndexed { index, item ->
            DocChip(index + 1, item.name, item.id == activeId) { onSelect(item.id) }
        }
        if (docs.size > 5) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Pal.Panel)
                    .border(1.dp, Pal.Line, RoundedCornerShape(50))
                    .clickable(onClick = onOpenAll)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    I18n.f(lang, "all_files", docs.size), color = Pal.Muted,
                    fontSize = 10.sp, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DocChip(number: Int, name: String, active: Boolean, onClick: () -> Unit) {
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
            String.format(java.util.Locale.US, "%02d  %s", number, name),
            color = if (active) Pal.Text else Pal.Muted, fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp)
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
                I18n.t(lang, "review_rows_label"), color = Pal.Muted, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                I18n.f(lang, "review_diff_low", doc.diffCount, doc.lowCount),
                color = if (doc.diffCount > 0) Pal.Danger else Pal.Faint,
                fontSize = 9.sp, maxLines = 1
            )
        }
        // 图例与网页端一致：绿=已核对、黄=已修改（只做圆点，不出现「已改」文字标签）
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(Pal.Teal, I18n.t(lang, "legend_verified"))
            LegendDot(EditedYellow, I18n.t(lang, "legend_edited"))
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

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Pal.Faint, fontSize = 9.sp, maxLines = 1)
    }
}

/** 行：单击定位原文并弹编辑框，双击同样编辑，长按切换已核对。 */
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
    val selectedTint = Color(0xFFF7C948)
    val fill = when {
        selected -> selectedTint.copy(alpha = 0.16f)
        row.edited -> EditedYellow.copy(alpha = 0.16f)
        row.verified -> Pal.Teal.copy(alpha = 0.04f)
        row.issue == ISSUE_DIFF -> Pal.Danger.copy(alpha = 0.08f)
        row.issue == ISSUE_LOW -> Pal.Warm.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp)).background(fill)
            .then(
                if (selected) {
                    Modifier.border(1.dp, selectedTint.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .pointerInput(row) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 行首黄点=已修改；状态点保留绿（已核对）/红（差异）/琥珀（低置信）
            if (row.edited) {
                Box(Modifier.size(6.dp).background(EditedYellow, CircleShape))
                Spacer(Modifier.width(5.dp))
            }
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
        // 左侧 3px 杏黄竖条（网页端 is-edited 同款）
        if (row.edited) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight()
                        .background(EditedYellow)
                )
            }
        }
    }
}
