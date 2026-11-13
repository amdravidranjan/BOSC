package com.bosc.guardian

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bosc.core.RiskLevel
import com.bosc.core.Tactic

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        CallManager.init(this)
        if (Prefs(this).asrEngine == "vosk") VoskAsr.load(this) else WhisperAsr.load(this)
        // Developer benchmark: adb shell am start -n com.bosc.guardian/.MainActivity --ez bench true
        if (intent?.getBooleanExtra("bench", false) == true) Bench.runAsync(this)
        setContent { BoscRoot() }
    }

    override fun onResume() {
        super.onResume()
        // Become reachable for Bosc calls while on screen (microphone services must start from the foreground).
        CallService.start(this)
    }
}

enum class Screen { Home, Settings, Demo, Caller }

@Composable
fun BoscRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val caller by CallerPlayer.state.collectAsState()
    val session by GuardState.session.collectAsState()
    val call by CallManager.ui.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(session.level) {
        if (session.active && session.level == RiskLevel.PAUSE && !session.trusted) {
            context.startActivity(Intent(context, PauseActivity::class.java))
        }
    }
    LaunchedEffect(session.active) { if (session.active) screen = Screen.Home }
    LaunchedEffect(caller.call) { if (caller.call != null) screen = Screen.Caller }

    BackHandler(enabled = screen != Screen.Home) {
        if (screen == Screen.Caller) CallerPlayer.end()
        screen = if (screen == Screen.Caller) Screen.Demo else Screen.Home
    }

    if (call.phase != CallPhase.IDLE) {
        BackHandler { }
        BoscTheme { InCallScreen() }
        return
    }

    when (screen) {
        Screen.Caller -> CallerScreen(onEnd = { CallerPlayer.end(); screen = Screen.Demo })
        else -> BoscTheme {
            Column(Modifier.fillMaxSize().background(Ink.Paper).statusBarsPadding()) {
                when (screen) {
                    Screen.Home -> HomeScreen(onSettings = { screen = Screen.Settings }, onDemo = { screen = Screen.Demo })
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                    Screen.Demo -> DemoScreen(onBack = { screen = Screen.Home }, onPreviewStarted = { screen = Screen.Home })
                    Screen.Caller -> Unit
                }
            }
        }
    }
}

fun neededPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun HomeScreen(onSettings: () -> Unit, onDemo: () -> Unit) {
    val context = LocalContext.current
    val s by GuardState.session.collectAsState()
    val armed by GuardState.armed.collectAsState()
    val last by GuardState.lastSession.collectAsState()
    val prefs = remember { Prefs(context) }

    var pendingStart by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val mic = res[Manifest.permission.RECORD_AUDIO] ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (mic && pendingStart) { prefs.micTestMode = false; GuardService.send(context, GuardService.ACTION_LISTEN_NOW) }
        pendingStart = false
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun startListening() {
        val missing = neededPermissions().filter { !has(it) }
        if (missing.isEmpty()) { prefs.micTestMode = false; GuardService.send(context, GuardService.ACTION_LISTEN_NOW) }
        else { pendingStart = true; ask.launch(missing.toTypedArray()) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            if (!s.active) TextLink("Settings", onSettings, color = Ink.Text)
        }

        if (s.active) {
            ListeningView(s, prefs)
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Label(if (armed) "Watching for calls" else "Off")
            Spacer(Modifier.height(10.dp))
            Headline("Not listening")
            Spacer(Modifier.height(12.dp))
            Text(
                "When a call feels wrong, put it on speaker near this phone and tap start. " +
                    "Bosc listens for pressure tactics and warns you before you pay. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodyLarge, color = Ink.Muted,
            )
            Spacer(Modifier.height(28.dp))
            PrimaryButton("Start listening", ::startListening)
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                "Watch for phone calls",
                "Start automatically when a call begins. Some phones mute other apps during calls.",
                armed,
            ) { on ->
                if (on) {
                    val missing = neededPermissions().filter { !has(it) }
                    if (missing.isEmpty()) GuardService.send(context, GuardService.ACTION_ARM) else ask.launch(missing.toTypedArray())
                } else GuardService.send(context, GuardService.ACTION_DISARM)
            }

            ContactsSection()
            last?.let { LastCall(it) }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            TextLink("Demo tools", onDemo, color = Ink.Faint)
        }
    }
}

@Composable
private fun ColumnScope.ListeningView(s: SessionState, prefs: Prefs) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(s.turns.size) { if (s.turns.isNotEmpty()) listState.animateScrollToItem(s.turns.size - 1) }
    val isCall = s.source == "call"
    val isPreview = s.source.startsWith("simulation")

    Label(when { isCall -> "On a call"; isPreview -> "Preview"; else -> "Listening" }, color = Ink.of(s.level))
    Spacer(Modifier.height(10.dp))
    Headline(levelHeadline(s.level), color = if (s.level == RiskLevel.LOW) Ink.Text else Ink.of(s.level))
    Spacer(Modifier.height(18.dp))
    Meter(s.risk, s.level)
    Spacer(Modifier.height(14.dp))
    Text(
        if (s.level == RiskLevel.LOW) "Listening for pressure tactics." else Warnings.oneLine(s.topTactics),
        fontFamily = if (s.level == RiskLevel.LOW) null else Serif,
        fontSize = if (s.level == RiskLevel.LOW) 15.sp else 20.sp,
        lineHeight = if (s.level == RiskLevel.LOW) 22.sp else 27.sp,
        color = if (s.level == RiskLevel.LOW) Ink.Muted else Ink.Text,
    )
    if (s.topTactics.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(tacticLine(s.topTactics), fontSize = 13.sp, color = Ink.Muted)
    }
    Spacer(Modifier.height(20.dp))
    if (isCall) {
        PrimaryButton("Hang up", { Actions.hangUp(context) }, color = Ink.Danger)
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton("Call ${prefs.trustedLabel}", { Actions.callTrusted(context) }, Modifier.weight(1f))
        if (!isCall) QuietButton("Stop", { Actions.stopListening(context) }, Modifier.weight(0.6f))
    }
    if (s.level != RiskLevel.LOW && !s.trusted) TextLink("I trust this caller", { GuardState.markTrusted() })

    val status = s.asrStatus.takeIf { it.isNotBlank() && !it.startsWith("Listening (") }
    status?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, fontSize = 13.sp, color = Ink.Danger, modifier = Modifier.background(Ink.Raised, RoundedCornerShape(10.dp)).padding(12.dp))
    }
    s.micRmsDb?.let { Text("Mic level ${"%.0f".format(it)} dBFS", fontSize = 13.sp, color = Ink.Muted) }

    Spacer(Modifier.height(22.dp))
    Hairline()
    Spacer(Modifier.height(6.dp))
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (s.turns.isEmpty() && s.partial.isBlank()) item {
            Text("Words will appear here as they are heard.", color = Ink.Faint, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        }
        items(s.turns) { t -> TranscriptLine(t.speaker, t.text, t.spans, showSpeaker = isPreview) }
        if (s.partial.isNotBlank()) item { Text(s.partial, color = Ink.Faint, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
private fun LastCall(s: SessionState) {
    val context = LocalContext.current
    var showTranscript by remember { mutableStateOf(false) }
    val peak = s.turns.maxOfOrNull { it.callRisk } ?: 0f
    val worst = s.turns.maxOfOrNull { it.level } ?: RiskLevel.LOW
    val secs = ((s.endedAt ?: s.startedAt) - s.startedAt) / 1000

    Spacer(Modifier.height(36.dp))
    Label("Last call")
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().background(Ink.Raised, RoundedCornerShape(16.dp)).padding(18.dp)) {
        Headline(
            when (worst) { RiskLevel.PAUSE -> "Had the signs of a scam"; RiskLevel.WARN -> "Had some warning signs"; RiskLevel.LOW -> "Looked normal" },
            color = Ink.of(worst), size = 22,
        )
        Spacer(Modifier.height(4.dp))
        Text("Peak ${(peak * 100).toInt()}%  ·  ${secs / 60}m ${secs % 60}s" + if (s.trusted) "  ·  you trusted it" else "", fontSize = 13.sp, color = Ink.Muted)
        Spacer(Modifier.height(16.dp))
        RiskGraph(s.turns.map { it.callRisk }, s.warnThreshold, s.pauseThreshold, Modifier.fillMaxWidth().height(90.dp))
        val seen = Tactic.entries.filter { t -> s.turns.any { (it.tactics[t] ?: 0f) >= 0.5f } }
        if (seen.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(tacticLine(seen), fontSize = 13.sp, color = Ink.Muted) }
        if (worst != RiskLevel.LOW) {
            Spacer(Modifier.height(16.dp))
            Text("If you sent money or shared a code, report it now. The first hours matter.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Call 1930", { Actions.dialHelpline(context) }, Modifier.weight(1f), color = Ink.Danger, height = 48.dp)
                QuietButton("Report online", { Actions.openReportingPortal(context) }, Modifier.weight(1f), height = 48.dp)
            }
        }
        if (s.turns.isNotEmpty()) TextLink(if (showTranscript) "Hide transcript" else "Show transcript", { showTranscript = !showTranscript })
        if (showTranscript) s.turns.forEach { TranscriptLine(it.speaker, it.text, it.spans, showSpeaker = s.source.startsWith("simulation")) }
    }
}
