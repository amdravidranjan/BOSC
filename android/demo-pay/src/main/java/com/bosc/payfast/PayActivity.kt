package com.bosc.payfast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.bosc.sdk.Bosc
import com.bosc.sdk.BoscConfig
import com.bosc.sdk.PaymentContext
import com.bosc.sdk.RiskDecision
import com.bosc.sdk.RiskLevel
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/**
 * PayFast: a mock payment app that embeds bosc-sdk (plan 7.14). No real UPI, no real money.
 * Friction ladder (plan 7.13): LOW pays, MEDIUM asks for a 10-second pause, HIGH stops with a calm full screen.
 */
class PayActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bosc.init(this, BoscConfig())
        tts = TextToSpeech(this) {}
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.READ_PHONE_STATE)
        setContent { PayTheme { PayFlow(::speak) } }
    }

    private fun speak(text: String) {
        tts?.language = Locale.forLanguageTag("en-IN")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "payfast")
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

private object C {
    val Paper = Color(0xFFF4F1EA)
    val Raised = Color(0xFFFBF9F4)
    val Line = Color(0xFFE2DCD0)
    val Ink = Color(0xFF1C1B19)
    val Muted = Color(0xFF6B655C)
    val Faint = Color(0xFFA19A8E)
    val Safe = Color(0xFF2F5A4C)
    val Caution = Color(0xFFA8722A)
    val Danger = Color(0xFF9B3A2B)
    /** PayFast's own brand colour, so it reads as a different company from Bosc. */
    val Brand = Color(0xFF23415F)
}

private val Serif = FontFamily.Serif
private val rupees: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"))

@Composable
private fun PayTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        @Suppress("DEPRECATION") run { w.statusBarColor = C.Paper.toArgb(); w.navigationBarColor = C.Paper.toArgb() }
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = C.Ink, background = C.Paper, surface = C.Paper, onSurface = C.Ink), content = content)
}

@Composable
private fun Label(text: String, color: Color = C.Muted) =
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, color = color)

@Composable
private fun Solid(text: String, onClick: () -> Unit, color: Color = C.Ink, enabled: Boolean = true, modifier: Modifier = Modifier, height: Dp = 58.dp) =
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), elevation = null,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = C.Paper, disabledContainerColor = C.Line, disabledContentColor = C.Faint),
        modifier = modifier.fillMaxWidth().height(height)) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun Quiet(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) =
    OutlinedButton(onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, C.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Ink), modifier = modifier.fillMaxWidth().height(52.dp)) { Text(text, fontSize = 15.sp) }

@Composable
private fun Link(text: String, onClick: () -> Unit, color: Color = C.Muted) =
    Text(text, color = color, fontSize = 14.sp, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp))

private sealed interface Stage {
    data object Form : Stage
    data class Medium(val d: RiskDecision) : Stage
    data class High(val d: RiskDecision) : Stage
    data class Done(val amount: Double, val payee: String) : Stage
    data object Cancelled : Stage
}

@Composable
private fun PayFlow(speak: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("payfast", Context.MODE_PRIVATE) }
    var payee by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var trusted by remember { mutableStateOf(prefs.getString("trusted", "") ?: "") }
    var stage by remember { mutableStateOf<Stage>(Stage.Form) }
    var last by remember { mutableStateOf<RiskDecision?>(null) }
    var drawer by remember { mutableStateOf<String?>(null) }

    val value = amount.toDoubleOrNull() ?: 0.0
    fun ctx() = PaymentContext(value, payee.trim())
    fun complete() { Bosc.recordPayment(ctx()); stage = Stage.Done(value, payee.trim()) }
    fun pay() {
        val d = Bosc.evaluate(ctx()); last = d
        stage = when (d.level) {
            RiskLevel.LOW -> { Bosc.recordPayment(ctx()); Stage.Done(value, payee.trim()) }
            RiskLevel.MEDIUM -> Stage.Medium(d)
            RiskLevel.HIGH -> { speak("Payment paused. Is someone on a call telling you to send money? Real police and banks never ask you to pay on a call."); Stage.High(d) }
        }
    }
    fun reset() { stage = Stage.Form; amount = ""; payee = "" }

    (stage as? Stage.High)?.let { s ->
        BackHandler { stage = Stage.Cancelled }
        HighPause(s.d, trusted,
            onCancel = { stage = Stage.Cancelled },
            onWaited = { pay() },
            onCall = { if (trusted.isNotBlank()) context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$trusted"))) },
            onTrust = { Bosc.recordOverride(s.d, "user tapped I trust this, pay anyway"); complete() })
        return
    }

    Column(Modifier.fillMaxSize().background(C.Paper).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 36.dp), verticalAlignment = Alignment.Bottom) {
            Text("PayFast", fontFamily = Serif, fontSize = 26.sp, color = C.Brand, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("demo · no real money", fontSize = 12.sp, color = C.Faint)
        }

        when (val s = stage) {
            is Stage.Done -> {
                Label("Sent", color = C.Safe)
                Spacer(Modifier.height(10.dp))
                Text("₹${rupees.format(s.amount)}", fontFamily = Serif, fontSize = 48.sp, color = C.Ink)
                Text("to ${s.payee}", fontSize = 17.sp, color = C.Muted)
                Spacer(Modifier.height(32.dp))
                Quiet("New payment", ::reset)
            }
            Stage.Cancelled -> {
                Label("Cancelled")
                Spacer(Modifier.height(10.dp))
                Text("Good call.", fontFamily = Serif, fontSize = 40.sp, color = C.Ink)
                Spacer(Modifier.height(10.dp))
                Text("No money moved. If someone pressured you to pay, call the cybercrime helpline on 1930 or report it at cybercrime.gov.in.",
                    fontSize = 16.sp, lineHeight = 24.sp, color = C.Muted)
                Spacer(Modifier.height(28.dp))
                Solid("Call 1930", { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) }, color = C.Danger)
                Spacer(Modifier.height(8.dp))
                Quiet("Back", ::reset)
            }
            else -> {
                Label("Pay to")
                BasicTextField(payee, { payee = it }, singleLine = true, cursorBrush = SolidColor(C.Ink),
                    textStyle = TextStyle(fontSize = 22.sp, color = C.Ink, fontFamily = Serif),
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        if (payee.isEmpty()) Text("Name or UPI ID", fontSize = 22.sp, color = C.Faint, fontFamily = Serif); inner() } })
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                if (payee.isNotBlank()) Text(if (Bosc.isKnownPayee(payee)) "You've paid them before" else "First time paying them",
                    fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp))

                Spacer(Modifier.height(30.dp))
                Label("Amount")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("₹", fontSize = 44.sp, color = if (amount.isEmpty()) C.Faint else C.Ink, fontFamily = Serif, modifier = Modifier.padding(end = 6.dp))
                    BasicTextField(amount, { amount = it.filter { c -> c.isDigit() }.take(9) }, singleLine = true, cursorBrush = SolidColor(C.Ink),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 52.sp, color = C.Ink, fontFamily = Serif),
                        decorationBox = { inner -> Box { if (amount.isEmpty()) Text("0", fontSize = 52.sp, color = C.Faint, fontFamily = Serif); inner() } })
                }
                Spacer(Modifier.height(32.dp))

                val m = stage as? Stage.Medium
                if (m != null) MediumPause(m.d, onPay = ::complete, onCancel = { stage = Stage.Cancelled })
                else Solid(if (value > 0) "Pay ₹${rupees.format(value)}" else "Pay", ::pay, color = C.Brand, enabled = payee.isNotBlank() && value > 0)
            }
        }

        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Link("Trusted contact", { drawer = if (drawer == "trusted") null else "trusted" })
            Link("Demo signals", { drawer = if (drawer == "demo") null else "demo" })
            if (last != null) Link("Why?", { drawer = if (drawer == "why") null else "why" })
        }
        when (drawer) {
            "trusted" -> Column(Modifier.padding(top = 8.dp)) {
                Text("Who should we offer to call when a payment looks risky?", fontSize = 13.sp, color = C.Muted)
                BasicTextField(trusted, { trusted = it; prefs.edit().putString("trusted", it).apply() }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), cursorBrush = SolidColor(C.Ink),
                    textStyle = TextStyle(fontSize = 18.sp, color = C.Ink),
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        if (trusted.isEmpty()) Text("Phone number", fontSize = 18.sp, color = C.Faint); inner() } })
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            }
            "demo" -> DemoSignals()
            "why" -> last?.let { WhyPanel(it) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MediumPause(d: RiskDecision, onPay: () -> Unit, onCancel: () -> Unit) {
    var left by remember(d) { mutableIntStateOf(10) }
    LaunchedEffect(d) { while (left > 0) { delay(1000); left-- } }
    Column(Modifier.fillMaxWidth().background(C.Raised, RoundedCornerShape(16.dp))) {
        Column(Modifier.padding(20.dp)) {
            Label("Take a moment", color = C.Caution)
            Spacer(Modifier.height(8.dp))
            Text("Before you send this, check that nobody is rushing you.", fontFamily = Serif, fontSize = 20.sp, lineHeight = 27.sp, color = C.Ink)
            Spacer(Modifier.height(10.dp))
            d.reasons.take(3).forEach { Text(it, fontSize = 14.sp, color = C.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quiet("Cancel", onCancel, modifier = Modifier.weight(1f))
                Solid(if (left > 0) "Pay in $left" else "Pay now", onPay, color = C.Brand, enabled = left == 0, modifier = Modifier.weight(1f), height = 52.dp)
            }
        }
    }
}

@Composable
private fun HighPause(d: RiskDecision, trusted: String, onCancel: () -> Unit, onWaited: () -> Unit, onCall: () -> Unit, onTrust: () -> Unit) {
    var waiting by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(60) }
    LaunchedEffect(waiting) { if (waiting) { while (left > 0) { delay(1000); left-- }; waiting = false; left = 60; onWaited() } }

    Column(Modifier.fillMaxSize().background(C.Paper).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
        Spacer(Modifier.weight(0.5f))
        Label("Payment paused", color = C.Danger)
        Spacer(Modifier.height(14.dp))
        Text("Is someone on a call telling you to send money?", fontFamily = Serif, fontSize = 32.sp, lineHeight = 38.sp, color = C.Ink)
        Spacer(Modifier.height(14.dp))
        Text("Real police, banks and officials never ask you to pay on a phone call.", fontSize = 17.sp, lineHeight = 25.sp, color = C.Muted)
        Spacer(Modifier.height(26.dp))
        Label("Why we stopped")
        Spacer(Modifier.height(8.dp))
        d.reasons.take(4).forEach { Text(it, fontSize = 15.sp, lineHeight = 22.sp, color = C.Ink, modifier = Modifier.padding(vertical = 3.dp)) }
        Spacer(Modifier.weight(1f))
        Solid("Cancel payment", onCancel, height = 60.dp)
        Spacer(Modifier.height(10.dp))
        Quiet(if (trusted.isNotBlank()) "Call my trusted contact" else "Add a trusted contact first", onCall, enabled = trusted.isNotBlank())
        Spacer(Modifier.height(8.dp))
        Quiet(if (waiting) "Waiting · $left s" else "Wait 60 seconds", { waiting = true }, enabled = !waiting)
        Spacer(Modifier.height(4.dp))
        Link("I trust this, pay anyway", onTrust, color = C.Faint)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DemoSignals() {
    var onCall by remember { mutableStateOf(Bosc.demo.forceOnCall == true) }
    var screen by remember { mutableStateOf(Bosc.demo.simulateScreenControl) }
    var pressure by remember { mutableStateOf(Bosc.demo.forceCallPressure) }
    Column(Modifier.padding(top = 8.dp)) {
        Text("For when no real call is possible. Leave everything off to use the phone's real signals and Bosc.", fontSize = 13.sp, color = C.Muted)
        Toggle("Pretend I'm on a call", onCall) { onCall = it; Bosc.demo.forceOnCall = if (it) true else null }
        Toggle("Pretend my screen is being shared", screen) { screen = it; Bosc.demo.simulateScreenControl = it }
        Spacer(Modifier.height(8.dp))
        Text("Call pressure from Bosc", fontSize = 15.sp, color = C.Ink)
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().background(C.Raised, RoundedCornerShape(12.dp)).padding(4.dp)) {
            listOf(null to "Real", 0.2f to "Low", 0.55f to "Warn", 0.9f to "Scam").forEach { (v, label) ->
                val on = pressure == v
                Box(Modifier.weight(1f).background(if (on) C.Ink else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable { pressure = v; Bosc.demo.forceCallPressure = v }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) C.Paper else C.Ink, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
        Text(if (on) "On" else "Off", fontSize = 14.sp, color = if (on) C.Ink else C.Faint, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun WhyPanel(d: RiskDecision) {
    val s = d.signals
    Column(Modifier.padding(top = 8.dp)) {
        Text("Last check: ${d.level.name.lowercase()} risk, score ${"%.2f".format(d.score)}", fontSize = 15.sp, color = when (d.level) {
            RiskLevel.LOW -> C.Safe; RiskLevel.MEDIUM -> C.Caution; RiskLevel.HIGH -> C.Danger })
        Spacer(Modifier.height(6.dp))
        Text(
            "call        ${s.callType.name.lowercase()}${if (s.speakerOn) ", speaker" else ""}\n" +
                "new payee   ${if (s.isNewPayee) "yes" else "no"}\n" +
                "amount z    ${"%.1f".format(s.amountZScore)}\n" +
                "payee word  ${s.payeeKeyword ?: "none"}\n" +
                "remote apps ${s.remoteAccessAppsInstalled.ifEmpty { listOf("none") }.joinToString()}\n" +
                "screen share ${if (s.screenCaptureOrControl) "yes (mock)" else "no"}\n" +
                "bosc        ${s.callPressure?.let { "${(it.risk * 100).toInt()}% ${it.level.lowercase()}${if (!it.fresh) ", stale" else ""}" } ?: "not available"}\n" +
                "token       ${d.signedToken.take(28)}…",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = C.Muted,
        )
    }
}
