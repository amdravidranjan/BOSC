package com.bosc.guardian

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bosc.core.RiskLevel
import kotlinx.coroutines.delay

/** Home-screen section: phones with Bosc on the same Wi-Fi, ready to call. */
@Composable
fun ContactsSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val found by Nearby.peers.collectAsState()
    LaunchedEffect(Unit) { Contacts.load(context) }
    val saved by Contacts.saved.collectAsState()
    // Saved contacts first; a phone seen live on the network updates its saved address.
    val peers = (saved.map { sv -> found.firstOrNull { it.name == sv.name } ?: sv } +
        found.filter { f -> saved.none { it.name == f.name } }).distinctBy { it.name }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    val myAddress by Nearby.myAddress.collectAsState()
    var listening by remember { mutableStateOf(prefs.scamListening) }
    var name by remember { mutableStateOf(prefs.myName) }
    var showAs by remember { mutableStateOf(prefs.showAs) }
    var editingName by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var micOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micOk = ok; if (ok) CallService.start(context) }

    Spacer(Modifier.height(36.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Label("Connections on this Wi-Fi", modifier = Modifier.weight(1f))
        if (micOk) TextLink("Search again", { CallService.start(context, CallService.ACTION_REFRESH) }, color = Ink.Faint)
    }
    Spacer(Modifier.height(6.dp))

    if (!micOk) {
        Text("Calls need the microphone.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        Spacer(Modifier.height(10.dp))
        QuietButton("Allow microphone", { ask.launch(Manifest.permission.RECORD_AUDIO) })
        return
    }

    SettingSwitch(
        "Scam listening",
        "Transcribe the other person on this phone and warn you if they pressure you. Nothing is recorded or sent.",
        listening,
    ) { listening = it; CallManager.setListening(it) }

    if (peers.isEmpty()) {
        Text("No contacts yet. Open Bosc on the other phone. If it doesn't appear here, add it by address below.",
            fontSize = 15.sp, color = Ink.Faint, modifier = Modifier.padding(vertical = 14.dp))
    } else peers.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Ink.Raised, RoundedCornerShape(10.dp)).clickable { CallManager.call(p) }.padding(horizontal = 14.dp)) {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Ink.Line, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(p.name.take(1).uppercase(), fontFamily = Serif, fontSize = 18.sp, color = Ink.Text)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.name, fontSize = 17.sp, color = Ink.Text)
                    val live = found.any { it.name == p.name }
                    Text(if (live) "On this Wi-Fi now" else "Saved  ·  ${p.host}", fontSize = 13.sp, color = if (live) Ink.Safe else Ink.Faint)
                }
                Text("Call", fontSize = 15.sp, color = Ink.Safe)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    if (editingName) {
        Underlined(name, { name = it }, "Your name")
        Spacer(Modifier.height(6.dp))
        Text("Name shown when you call (optional, for the demo caller)", fontSize = 13.sp, color = Ink.Muted)
        Underlined(showAs, { showAs = it }, "e.g. SBI Customer Care")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextLink("Save", { prefs.myName = name; prefs.showAs = showAs; editingName = false; CallService.start(context, CallService.ACTION_REFRESH) }, color = Ink.Text)
            TextLink("Cancel", { name = prefs.myName; showAs = prefs.showAs; editingName = false })
        }
    } else {
        Text(buildString {
                append("Others see you as ${prefs.myName}")
                if (prefs.showAs.isNotBlank()) append("  ·  calls show \"${prefs.showAs}\"")
                if (myAddress.isNotBlank()) append("  ·  $myAddress") },
            fontSize = 13.sp, color = Ink.Faint)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextLink("Change name", { editingName = true })
            TextLink("Add a phone", { manual = !manual; syncMsg = null })
        }
    }
    if (manual) {
        Text("Type the address shown at the bottom of Bosc on the other phone. Both phones will save each other.", fontSize = 13.sp, color = Ink.Muted)
        Underlined(manualIp, { manualIp = it.trim(); syncMsg = null }, "192.168.1.20", KeyboardType.Uri)
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Add contact", {
            val host = manualIp.substringBefore(':')
            val port = manualIp.substringAfter(':', "").toIntOrNull() ?: CallManager.DEFAULT_PORT
            syncMsg = "Connecting…"
            CallManager.sync(host, port) { peer ->
                syncMsg = if (peer != null) "Added ${peer.name}. They've saved you too." else "Couldn't reach $host. Is Bosc open on that phone?"
                if (peer != null) { manual = false; manualIp = "" }
            }
        }, enabled = manualIp.isNotBlank(), height = 48.dp)
    }
    syncMsg?.let { Text(it, fontSize = 14.sp, color = Ink.Muted, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun Underlined(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType = KeyboardType.Text) {
    BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(Ink.Text),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        textStyle = TextStyle(fontSize = 18.sp, color = Ink.Text),
        decorationBox = { inner -> Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            if (value.isEmpty()) Text(hint, fontSize = 18.sp, color = Ink.Faint); inner() } })
    Hairline()
}

/** Full-screen call UI: ringing, calling, and the live call with scam listening. */
@Composable
fun InCallScreen() {
    val c by CallManager.ui.collectAsState()
    val s by GuardState.session.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.startedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val listState = rememberLazyListState()
    LaunchedEffect(s.turns.size) { if (s.turns.isNotEmpty()) listState.animateScrollToItem(s.turns.size - 1) }
    val secs = if (c.startedAt > 0) (now - c.startedAt) / 1000 else 0

    Column(Modifier.fillMaxSize().background(Ink.Paper).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(28.dp))
        Label(when (c.phase) {
            CallPhase.INCOMING -> "Incoming Bosc call"
            CallPhase.OUTGOING -> "Calling"
            CallPhase.ACTIVE -> "%02d:%02d".format(secs / 60, secs % 60)
            CallPhase.ENDED -> c.endedReason
            CallPhase.IDLE -> ""
        }, color = if (c.phase == CallPhase.ENDED) Ink.Muted else Ink.Safe)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { Headline(c.peerName, size = 36) }
            if (c.phase == CallPhase.ACTIVE) SmallToggle(if (c.listening) "Listening" else "Not listening", c.listening) { CallManager.setListening(!c.listening) }
        }
        if (c.nameUnverified && c.phase != CallPhase.ENDED) {
            Text(if (c.realName.isNotBlank()) "Name not verified  ·  saved contact: ${c.realName}" else "Name not verified. Scammers can fake caller names.",
                fontSize = 13.sp, color = Ink.Caution, modifier = Modifier.padding(top = 4.dp))
        }
        var prompter by remember { mutableStateOf(false) }
        if (c.phase == CallPhase.ACTIVE && c.outgoing) {
            TextLink(if (prompter) "Hide script" else "Read a script (demo caller)", { prompter = !prompter })
        }
        if (prompter && c.phase == CallPhase.ACTIVE) {
            Prompter(Modifier.weight(1f))
        } else {

        if (c.phase == CallPhase.ACTIVE && c.listening && s.active) {
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().background(Ink.Raised, RoundedCornerShape(10.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(levelHeadline(s.level), fontFamily = Serif, fontSize = 22.sp, color = if (s.level == RiskLevel.LOW) Ink.Text else Ink.of(s.level), modifier = Modifier.weight(1f))
                    Text("${(s.risk * 100).toInt()}%", fontSize = 14.sp, color = Ink.Muted)
                }
                Spacer(Modifier.height(10.dp))
                Meter(s.risk, s.level)
                if (s.level != RiskLevel.LOW) {
                    Spacer(Modifier.height(12.dp))
                    Text(Warnings.oneLine(s.topTactics), fontSize = 16.sp, lineHeight = 23.sp, color = Ink.Text)
                    if (!s.trusted) TextLink("I trust this caller", { GuardState.markTrusted() })
                }
                if (s.topTactics.isNotEmpty()) Text(tacticLine(s.topTactics), fontSize = 13.sp, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))
            Label("Live transcript")
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (s.turns.isEmpty() && c.partial.isBlank()) item {
                    Text("Scam listening is on. Words appear here as you talk.", color = Ink.Faint, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                }
                items(s.turns) { t -> TranscriptLine(t.speaker, t.text, t.spans, showSpeaker = true) }
                if (c.partial.isNotBlank()) item { Text(c.partial, color = Ink.Faint, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            val vosk by VoskAsr.status.collectAsState()
            val whisper by WhisperAsr.status.collectAsState()
            val prefs = Prefs(LocalContext.current)
            val asr = if (prefs.asrEngine == "vosk") vosk else whisper
            if (asr != "ready") Text(asr, fontSize = 13.sp, color = Ink.Muted)
            Text("Detector: ${prefs.detector.label}  ·  speech: ${if (prefs.asrEngine == "vosk") "Vosk" else "Whisper tiny.en"}, on this phone",
                fontSize = 12.sp, color = Ink.Faint, modifier = Modifier.padding(top = 4.dp))
        } else {
            Spacer(Modifier.weight(1f))
            if (c.phase == CallPhase.ACTIVE) {
                Text("Scam listening is off for this call.", fontSize = 15.sp, color = Ink.Muted)
                TextLink("Turn it on", { CallManager.setListening(true) }, color = Ink.Text)
            }
            if (c.phase == CallPhase.INCOMING) Text("Bosc call on Wi-Fi", fontSize = 15.sp, color = Ink.Muted)
            Spacer(Modifier.weight(1f))
        }
        }

        Spacer(Modifier.height(16.dp))
        when (c.phase) {
            CallPhase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundButton("Decline", Ink.Danger) { CallManager.decline() }
                RoundButton("Accept", Ink.Safe) { CallManager.accept() }
            }
            CallPhase.OUTGOING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                RoundButton("End", Ink.Danger) { CallManager.hangUp() }
            }
            CallPhase.ACTIVE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SmallToggle(if (c.muted) "Unmute" else "Mute", c.muted) { CallManager.toggleMute() }
                RoundButton("End", Ink.Danger) { CallManager.hangUp() }
                SmallToggle("Speaker", c.speaker) { CallManager.toggleSpeaker() }
            }
            else -> Unit
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RoundButton(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).background(color, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label.take(1), color = Ink.Paper, fontSize = 22.sp, fontFamily = Serif)
        }
        Text(label, fontSize = 13.sp, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SmallToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 15.sp, color = if (on) Ink.Paper else Ink.Text,
        modifier = Modifier.background(if (on) Ink.Text else Ink.Raised, RoundedCornerShape(22.dp)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp))
}

/** Teleprompter for the phone playing the caller: the team's demo scripts, one line at a time. */
@Composable
private fun Prompter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scripts = remember { Scripts.all(context) }
    var pick by remember { mutableStateOf(0) }
    var line by remember { mutableStateOf(0) }
    val sc = scripts.getOrNull(pick)
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (sc == null) { Text("No scripts found.", color = Ink.Muted); return@Column }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            scripts.forEachIndexed { i, x -> SmallToggle(x.title, i == pick) { pick = i; line = 0 } }
        }
        Spacer(Modifier.height(18.dp))
        Label("Line ${line + 1} of ${sc.lines.size}")
        Spacer(Modifier.height(10.dp))
        Text(sc.lines[line], fontFamily = Serif, fontSize = 24.sp, lineHeight = 33.sp, color = Ink.Text,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
        Text("Read it naturally, then pause before the next line.", fontSize = 13.sp, color = Ink.Muted)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton("Previous", { if (line > 0) line-- }, Modifier.weight(1f), height = 48.dp)
            PrimaryButton(if (line < sc.lines.lastIndex) "Next line" else "Last line", { if (line < sc.lines.lastIndex) line++ }, Modifier.weight(1f), height = 48.dp)
        }
    }
}
