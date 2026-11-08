package com.stmtpdf.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stmtpdf.app.R
import com.stmtpdf.app.i18n.AppLang
import com.stmtpdf.app.i18n.I18n

/** 与 Pixso 定稿一致的深空科技风色板。 */
object Pal {
    val Bg = Color(0xFF05070A)
    val Panel = Color(0xFF0B1118)
    val Tile = Color(0xFF0A0F1C)
    val Line = Color(0xFF1B2730)
    val Line2 = Color(0xFF22333C)
    val Text = Color(0xFFEAF2F3)
    val Muted = Color(0xFF8697A0)
    val Faint = Color(0xFF5C6A70)
    val Teal = Color(0xFF4FE3CE)
    val Cyan = Color(0xFF35C7F0)
    val Warm = Color(0xFFFFB073)
    val Dark = Color(0xFF04110F)
    val Dim = Color(0xFF6E7F86)
    val Danger = Color(0xFFFF8A7A)

    val accentGradient: Brush get() = Brush.horizontalGradient(listOf(Teal, Cyan))
}

/** 背景极光光晕（左上青绿、右下暖橙）。 */
@Composable
fun GlowBackground() {
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Box(
            Modifier.size(330.dp).offset(x = (-110).dp, y = (-90).dp).background(
                Brush.radialGradient(listOf(Pal.Teal.copy(alpha = 0.16f), Color.Transparent))
            )
        )
        Box(
            Modifier.size(280.dp).offset(x = 210.dp, y = 520.dp).background(
                Brush.radialGradient(listOf(Pal.Warm.copy(alpha = 0.10f), Color.Transparent))
            )
        )
    }
}

/** 品牌行：S 图标 + 名称 + 可选语言切换 + 右上状态胶囊。 */
@Composable
fun BrandRow(
    chip: String,
    accent: Color = Pal.Teal,
    lang: AppLang? = null,
    onLangPick: ((AppLang) -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.logo_icon),
            contentDescription = "StmtPDF",
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
        )
        Spacer(Modifier.size(8.dp))
        Text("StmtPDF", color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (lang != null && onLangPick != null) {
            LangSwitch(lang, onLangPick)
            Spacer(Modifier.width(8.dp))
        }
        InfoChip(chip, accent)
    }
}

/** 语言切换胶囊（仅首页顶部展示）。 */
@Composable
fun LangSwitch(current: AppLang, onPick: (AppLang) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Pal.Line, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(current.short, color = Pal.Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(3.dp))
            Text("▾", color = Pal.Faint, fontSize = 8.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppLang.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            item.label,
                            color = if (item == current) Pal.Teal else Pal.Text,
                            fontSize = 12.sp
                        )
                    },
                    onClick = {
                        onPick(item)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
fun InfoChip(text: String, accent: Color = Pal.Teal, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = accent, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp, maxLines = 1
        )
    }
}

/** 渐变主按钮。 */
@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    radius: Dp = 16.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius))
            .background(if (enabled) Pal.accentGradient else Brush.horizontalGradient(listOf(Pal.Line2, Pal.Line2)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Pal.Dark else Pal.Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 次级面板按钮。 */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Pal.Panel)
            .border(1.dp, Pal.Teal.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Pal.Teal, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 悬浮胶囊三页签。 */
@Composable
fun PillTabBar(current: Int, lang: AppLang, onSelect: (Int) -> Unit) {
    val labels = listOf(
        I18n.t(lang, "tab_convert"),
        I18n.t(lang, "tab_activate"),
        I18n.t(lang, "tab_quota")
    )
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 21.dp, end = 21.dp, top = 12.dp, bottom = 21.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(31.dp))
                .background(Pal.Panel).border(1.dp, Color(0xFF1C2932), RoundedCornerShape(31.dp))
                .padding(4.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val active = index == current
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(27.dp))
                        .then(if (active) Modifier.background(Pal.accentGradient) else Modifier)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TabIcon(index, if (active) Pal.Dark else Pal.Dim)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            color = if (active) Pal.Dark else Pal.Dim,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.6.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(kind: Int, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        when (kind) {
            0 -> {
                val bar = 1.8.dp.toPx()
                drawRoundRect(color, Offset(w * 0.18f, h * 0.30f), Size(w * 0.64f, bar), androidx.compose.ui.geometry.CornerRadius(bar / 2))
                drawRoundRect(color, Offset(w * 0.18f, h * 0.48f), Size(w * 0.64f, bar), androidx.compose.ui.geometry.CornerRadius(bar / 2))
                drawRoundRect(color, Offset(w * 0.18f, h * 0.66f), Size(w * 0.36f, bar), androidx.compose.ui.geometry.CornerRadius(bar / 2))
            }
            1 -> {
                drawCircle(
                    color, radius = w * 0.20f, center = Offset(w * 0.42f, h * 0.34f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawRoundRect(color, Offset(w * 0.46f, h * 0.46f), Size(w * 0.09f, h * 0.34f), androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
                drawRoundRect(color, Offset(w * 0.52f, h * 0.66f), Size(w * 0.22f, h * 0.09f), androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
            }
            else -> {
                val barW = w * 0.14f
                drawRoundRect(color, Offset(w * 0.20f, h * 0.46f), Size(barW, h * 0.32f), androidx.compose.ui.geometry.CornerRadius(barW / 2))
                drawRoundRect(color, Offset(w * 0.44f, h * 0.26f), Size(barW, h * 0.52f), androidx.compose.ui.geometry.CornerRadius(barW / 2))
                drawRoundRect(color, Offset(w * 0.68f, h * 0.38f), Size(barW, h * 0.40f), androidx.compose.ui.geometry.CornerRadius(barW / 2))
            }
        }
    }
}

/** 小标题（带字距）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier, color = Pal.Faint, fontSize = 9.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 2.sp
    )
}

/** 信息卡（面板 + 描边）。 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    border: Color = Pal.Line,
    fill: Color = Pal.Panel,
    content: @Composable () -> Unit
) {
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(fill)
            .border(1.dp, border, RoundedCornerShape(radius))
    ) { content() }
}

/** 环形额度。 */
@Composable
fun QuotaRing(remaining: Int, total: Int, label: String) {
    Box(Modifier.fillMaxWidth().height(232.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(240.dp).background(
                Brush.radialGradient(listOf(Pal.Teal.copy(alpha = 0.10f), Color.Transparent))
            )
        )
        Canvas(Modifier.size(200.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color(0xFF16232B), startAngle = -90f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(stroke)
            )
            val ratio = if (total > 0) (remaining.toFloat() / total).coerceIn(0f, 1f) else 0f
            if (ratio > 0f) {
                drawArc(
                    brush = Brush.linearGradient(listOf(Pal.Teal, Pal.Cyan)),
                    startAngle = -90f, sweepAngle = 360f * ratio, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatCount(remaining), color = Pal.Text, fontSize = 32.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp
            )
            Text("pages left", color = Pal.Muted, fontSize = 10.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(label, color = Pal.Faint, fontSize = 8.5.sp, letterSpacing = 1.sp)
        }
    }
}

/** 千分位。 */
fun formatCount(value: Int): String = String.format(java.util.Locale.US, "%,d", value)

@Composable
fun CenterNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.fillMaxWidth(), color = Pal.Faint, fontSize = 8.5.sp,
        textAlign = TextAlign.Center, lineHeight = 13.sp
    )
}
