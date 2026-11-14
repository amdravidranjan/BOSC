package com.bosc.guardian

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.bosc.core.RiskLevel
import com.bosc.core.Span
import com.bosc.core.Speaker
import com.bosc.core.Tactic

/** One restrained palette: paper, ink, and three muted state colours. */
object Ink {
    val Paper = Color(0xFF0F1216)
    val Raised = Color(0xFF181C21)
    val Line = Color(0xFF343941)
    val Text = Color(0xFFF5F5F6)
    val Muted = Color(0xFFC0C3CB)
    val Faint = Color(0xFF9EA3AB)
    val Safe = Color(0xFF9EC8B5)
    val Caution = Color(0xFFFFCC80)
    val Danger = Color(0xFFFF7D73)

    fun of(level: RiskLevel) = when (level) { RiskLevel.LOW -> Safe; RiskLevel.WARN -> Caution; RiskLevel.PAUSE -> Danger }
}

val Serif = FontFamily.SansSerif

@Composable
fun BoscTheme(statusBar: Color = Ink.Paper, lightIcons: Boolean = true, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        @Suppress("DEPRECATION")
        window.statusBarColor = statusBar.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = statusBar.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Text, onPrimary = Ink.Paper, background = Ink.Paper, onBackground = Ink.Text,
            surface = Ink.Paper, onSurface = Ink.Text, surfaceVariant = Ink.Raised, onSurfaceVariant = Ink.Muted,
            outline = Ink.Line, secondary = Ink.Muted, error = Ink.Danger,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = Ink.Muted),
        ),
        content = {
            // Large text (Settings): +15% on every sp size, like the web app's html.large.
            val d = androidx.compose.ui.platform.LocalDensity.current
            val scaled = if (UiPrefs.largeText) androidx.compose.ui.unit.Density(d.density, d.fontScale * 1.15f) else d
            // Default text colour for the dark theme. Without this, Text() with no colour falls back to black
            // (LocalContentColor is only set inside a Surface) and is invisible on the dark background.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides scaled,
                androidx.compose.material3.LocalContentColor provides Ink.Text,
                content = content,
            )
        },
    )
}

/** UI settings that must recompose the app when changed. */
object UiPrefs {
    var largeText by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun Wordmark(text: String = "BOSC", size: Int = 26) =
    Text(text, fontFamily = Serif, fontSize = size.sp, fontWeight = FontWeight.Medium, color = Ink.Text, letterSpacing = (-0.5).sp)

@Composable
fun Label(text: String, color: Color = Ink.Muted, modifier: Modifier = Modifier) =
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, color = color, modifier = modifier)

@Composable
fun Headline(text: String, color: Color = Ink.Text, size: Int = 34) =
    Text(text, fontFamily = Serif, fontSize = size.sp, lineHeight = (size * 1.15).sp, color = color, letterSpacing = (-0.4).sp)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Text, enabled: Boolean = true, height: Dp = 56.dp) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink.Paper, disabledContainerColor = Ink.Line, disabledContentColor = Ink.Faint),
        elevation = null, modifier = modifier.fillMaxWidth().height(height),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 52.dp) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Ink.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.Text), modifier = modifier.fillMaxWidth().height(height),
    ) { Text(text, fontSize = 15.sp) }
}

@Composable
fun TextLink(text: String, onClick: () -> Unit, color: Color = Ink.Muted, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 8.dp))

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Line))

@Composable
fun SettingSwitch(title: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Ink.Paper, checkedTrackColor = Ink.Text, checkedBorderColor = Ink.Text,
            uncheckedThumbColor = Ink.Faint, uncheckedTrackColor = Ink.Raised, uncheckedBorderColor = Ink.Line,
        ))
    }
}

/** Ten quiet segments; the filled ones take the colour of the current level. */
@Composable
fun Meter(risk: Float, level: RiskLevel, modifier: Modifier = Modifier) {
    val lit = (risk * 10).toInt().coerceIn(0, 10).let { if (risk > 0.03f && it == 0) 1 else it }
    Row(modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(10) { i ->
            Box(Modifier.weight(1f).height(10.dp).background(if (i < lit) Ink.of(level) else Ink.Line, RoundedCornerShape(2.dp)))
        }
    }
}

fun levelHeadline(level: RiskLevel) = when (level) {
    RiskLevel.LOW -> "All calm"
    RiskLevel.WARN -> "Be careful"
    RiskLevel.PAUSE -> "This looks like a scam"
}

fun tacticLine(tactics: List<Tactic>) = tactics.joinToString("  ·  ") { it.label }

/** A transcript line. Pressure phrases are underlined, not painted. */
@Composable
fun TranscriptLine(speaker: Speaker, text: String, spans: List<Span>, showSpeaker: Boolean) {
    val merged = spans.sortedBy { it.start }.fold(mutableListOf<Span>()) { acc, s -> if (acc.isEmpty() || s.start >= acc.last().end) acc.add(s); acc }
    val annotated = buildAnnotatedString {
        var i = 0
        for (s in merged) {
            if (s.start > text.length) break
            append(text.substring(i, s.start))
            withStyle(SpanStyle(color = Ink.Danger, textDecoration = TextDecoration.Underline)) {
                append(text.substring(s.start, s.end.coerceAtMost(text.length)))
            }
            i = s.end.coerceAtMost(text.length)
        }
        if (i < text.length) append(text.substring(i))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (showSpeaker && speaker != Speaker.UNKNOWN) Label(if (speaker == Speaker.CALLER) "Caller" else "You", color = Ink.Faint)
        Text(annotated, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Risk over time: one ink line, faint dashed thresholds. */
@Composable
fun RiskGraph(points: List<Float>, warn: Float, pause: Float, modifier: Modifier = Modifier, marks: List<Int> = emptyList()) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun y(v: Float) = h - v * h
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        drawLine(Ink.Caution.copy(alpha = 0.6f), Offset(0f, y(warn)), Offset(w, y(warn)), 2f, pathEffect = dash)
        drawLine(Ink.Danger.copy(alpha = 0.6f), Offset(0f, y(pause)), Offset(w, y(pause)), 2f, pathEffect = dash)
        drawLine(Ink.Line, Offset(0f, h), Offset(w, h), 2f)
        if (points.isEmpty()) return@Canvas
        val step = if (points.size > 1) w / (points.size - 1) else 0f
        for (i in 1 until points.size) {
            drawLine(Ink.Text, Offset((i - 1) * step, y(points[i - 1])), Offset(i * step, y(points[i])), 4f, cap = StrokeCap.Round)
        }
        // "Pay tapped" markers (web app item 9): dashed vertical line at the turn when Pay was tapped.
        marks.forEach { m ->
            val x = ((m - 1).coerceIn(0, points.size - 1)) * step
            drawLine(Ink.Caution, Offset(x, 0f), Offset(x, h), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val last = points.last()
        drawCircle(if (last >= pause) Ink.Danger else if (last >= warn) Ink.Caution else Ink.Safe, 7f, Offset((points.size - 1) * step, y(last)))
    }
}
