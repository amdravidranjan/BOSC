package com.bosc.guardian

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bosc.core.DemoCall
import com.bosc.core.DemoCalls
import com.bosc.core.Evaluator
import com.bosc.core.Speaker
import kotlinx.coroutines.delay

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("←", fontSize = 24.sp, color = Ink.Text, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp))
        Text(title, fontFamily = Serif, fontSize = 26.sp, color = Ink.Text)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink.Text, unfocusedBorderColor = Ink.Line, focusedLabelColor = Ink.Text, unfocusedLabelColor = Ink.Muted,
    cursorColor = Ink.Text, focusedContainerColor = Ink.Raised, unfocusedContainerColor = Ink.Raised,
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var name by remember { mutableStateOf(prefs.trustedName) }
    var number by remember { mutableStateOf(prefs.trustedNumber) }
    var lang by remember { mutableStateOf(prefs.language) }
    var speak by remember { mutableStateOf(prefs.speakWarnings) }
    var online by remember { mutableStateOf(prefs.allowOnlineAsr) }
    var micTest by remember { mutableStateOf(prefs.micTestMode) }

    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    val askOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: ""; number = c.getString(1) ?: ""
                prefs.trustedName = name; prefs.trustedNumber = number
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        PageHeader("Settings", onBack)

        Label("Someone you trust")
        Text("Bosc offers to call them when a call looks wrong.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        OutlinedTextField(name, { name = it; prefs.trustedName = it }, label = { Text("Name") }, singleLine = true,
            colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(number, { number = it; prefs.trustedNumber = it }, label = { Text("Phone number") }, singleLine = true,
            colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        TextLink("Choose from contacts", { pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) })

        Spacer(Modifier.height(24.dp)); Hairline(); Spacer(Modifier.height(20.dp))
        Label("Language")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().background(Ink.Raised, RoundedCornerShape(12.dp)).padding(4.dp)) {
            listOf("en-IN" to "English", "hi-IN" to "हिन्दी", "ta-IN" to "தமிழ்").forEach { (tag, label) ->
                val on = lang == tag
                Box(
                    Modifier.weight(1f).background(if (on) Ink.Text else Color.Transparent, RoundedCornerShape(9.dp))
                        .clickable { lang = tag; prefs.language = tag }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) Ink.Paper else Ink.Text, fontSize = 15.sp) }
            }
        }
        Text("For listening and for spoken warnings.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(24.dp)); Hairline(); Spacer(Modifier.height(20.dp))
        Label("Detector")
        Spacer(Modifier.height(6.dp))
        var det by remember { mutableStateOf(prefs.detector) }
        com.bosc.core.DetectorKind.entries.forEach { k ->
            Row(Modifier.fillMaxWidth().clickable { det = k; prefs.detector = k }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (det == k) Text("Selected", fontSize = 14.sp, color = Ink.Safe)
            }
        }
        Spacer(Modifier.height(16.dp))
        Label("Speech to text")
        Spacer(Modifier.height(6.dp))
        var engine by remember { mutableStateOf(prefs.asrEngine) }
        listOf("whisper" to "Whisper tiny.en (more accurate)", "vosk" to "Vosk (faster, live captions)").forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { engine = id; prefs.asrEngine = id; if (id == "vosk") VoskAsr.load(context) else WhisperAsr.load(context) }
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (engine == id) Text("Selected", fontSize = 14.sp, color = Ink.Safe)
            }
        }
        Text("Both run on this phone. Applies from the next call.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        Label("Microphone in Bosc calls")
        Spacer(Modifier.height(6.dp))
        var micMode by remember { mutableStateOf(prefs.callMicMode) }
        listOf("call" to "Call (phone's normal call audio)", "clean" to "Clean (less processing; quieter on some phones)").forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { micMode = id; prefs.callMicMode = id }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (micMode == id) Text("Selected", fontSize = 14.sp, color = Ink.Safe)
            }
        }
        Text("Set this on the phone that speaks. It changes the audio the other phone hears.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(24.dp)); Hairline(); Spacer(Modifier.height(8.dp))
        SettingSwitch("Speak warnings out loud", null, speak) { speak = it; prefs.speakWarnings = it }
        SettingSwitch("Use online speech recognition", "Only if this phone has no offline speech pack. Audio is then sent to the recogniser's server.", online) { online = it; prefs.allowOnlineAsr = it }

        Spacer(Modifier.height(16.dp)); Hairline(); Spacer(Modifier.height(20.dp))
        Label("Permissions")
        Spacer(Modifier.height(4.dp))
        androidx.compose.runtime.key(refresh) {
            listOfNotNull(
                Triple(Manifest.permission.RECORD_AUDIO, "Microphone", "Only while listening. Audio is never saved."),
                Triple(Manifest.permission.READ_PHONE_STATE, "Calls", "To know when a call starts and ends."),
                if (Build.VERSION.SDK_INT >= 33) Triple(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "For warnings while you are in another app.") else null,
                Triple(Manifest.permission.ANSWER_PHONE_CALLS, "End calls", "Lets the Hang up button end a call."),
            ).forEach { (perm, title, why) ->
                val ok = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(why, style = MaterialTheme.typography.bodySmall)
                    }
                    if (ok) Text("Allowed", color = Ink.Safe, fontSize = 14.sp)
                    else TextLink("Allow", { askOne.launch(perm) }, color = Ink.Text)
                }
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
                TextLink("Allow full-screen warnings", {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)))
                }, color = Ink.Text)
            }
        }
        TextLink("Battery: set Bosc to Unrestricted", { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })

        Spacer(Modifier.height(16.dp)); Hairline(); Spacer(Modifier.height(8.dp))
        var capture by remember { mutableStateOf(prefs.debugCaptureAudio) }
        SettingSwitch("Save received call audio (debug)",
            "For your own test calls only. Saves what the other phone sends to Android/data/com.bosc.guardian/files/debug_audio. Delete after testing.",
            capture) { capture = it; prefs.debugCaptureAudio = it }
        SettingSwitch("Mic level test", "For testing a new phone: during calls, show the microphone level instead of words.", micTest) { micTest = it; prefs.micTestMode = it }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun DemoScreen(onBack: () -> Unit, onPreviewStarted: () -> Unit) {
    val context = LocalContext.current
    var presenterLines by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    val ordered = DemoCalls.all.sortedWith(compareBy({ it.language != "English" }, { !it.isScam }))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        PageHeader("Demo tools", onBack)

        Label("Be the caller")
        Text("Use this on the second phone. It plays the caller out loud, like a call on speaker. Hold it near the phone that is listening.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp))
        SettingSwitch("I'll say the other person's lines", "Off: this phone speaks both sides in two voices.", presenterLines) { presenterLines = it }
        Spacer(Modifier.height(4.dp))
        ordered.forEach { c -> CallRow(c) { CallerPlayer.start(context, c, presenterLines) } }

        Spacer(Modifier.height(28.dp))
        Label("Preview on this phone")
        Text("Runs a call silently through the detector here. No second phone needed.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        ordered.forEach { c -> CallRow(c) { Simulator.play(c); onPreviewStarted() } }

        Spacer(Modifier.height(28.dp))
        Label("Self-test")
        TextLink("Run all calls through the detector", {
            val kind = Prefs(context).detector
            report = kind.label + "\n" + Evaluator.runAll { kind.create() }.table()
        }, color = Ink.Text)
        report?.let {
            Text("These calls were written alongside the rules, so treat this as a smoke test, not accuracy.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp, color = Ink.Text)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CallRow(c: DemoCall, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, fontSize = 16.sp, color = Ink.Text)
                Text((if (c.isScam) "Scam" else "Genuine") + "  ·  " + c.language, fontSize = 13.sp,
                    color = if (c.isScam) Ink.Danger.copy(alpha = 0.8f) else Ink.Safe)
            }
            Text("Play", fontSize = 14.sp, color = Ink.Muted)
        }
        Hairline()
    }
}

/** The second phone: looks like an ongoing call while it speaks the script. */
@Composable
fun CallerScreen(onEnd: () -> Unit) {
    val st by CallerPlayer.state.collectAsState()
    val call = st.call ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.startedAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val secs = ((now - st.startedAt) / 1000).coerceAtLeast(0)
    val dark = Color(0xFF151413)
    val line = call.turns.getOrNull(st.index)

    BoscTheme(statusBar = dark, lightIcons = true) {
        Column(
            Modifier.fillMaxSize().background(dark).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Text(if (st.finished) "Call ended" else "%02d:%02d".format(secs / 60, secs % 60), color = Color(0xFF9C968C), fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            Text(call.callerName, color = Color(0xFFF4F1EA), fontFamily = Serif, fontSize = 32.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Mobile  ·  on speaker", color = Color(0xFF9C968C), fontSize = 14.sp)

            Spacer(Modifier.weight(1f))
            if (line != null && !st.finished) {
                val (speaker, text) = line
                val mine = speaker == Speaker.YOU
                Text(if (mine) (if (st.waitingForYou) "Your line, say it out loud" else "You") else "Caller",
                    color = if (mine) Color(0xFFD9A55B) else Color(0xFF9C968C), fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(text, color = if (mine) Color(0xFFD9A55B) else Color(0xFFF4F1EA), fontSize = 21.sp, lineHeight = 29.sp, textAlign = TextAlign.Center)
                if (st.waitingForYou) {
                    Spacer(Modifier.height(18.dp))
                    Text("Done, continue", color = Color(0xFF151413), fontSize = 15.sp,
                        modifier = Modifier.background(Color(0xFFD9A55B), RoundedCornerShape(24.dp)).clickable { CallerPlayer.saidIt() }.padding(horizontal = 22.dp, vertical = 12.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Line ${(st.index + 1).coerceIn(0, call.turns.size)} of ${call.turns.size}", color = Color(0xFF5E5A53), fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(72.dp).background(Color(0xFFB0412F), CircleShape).clickable(onClick = onEnd), contentAlignment = Alignment.Center) {
                Text("End", color = Color.White, fontSize = 15.sp)
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}
