package com.bosc.guardian

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.widget.Toast
import com.bosc.core.DemoCall
import com.bosc.core.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object Actions {

    /** Ends the current call through TelecomManager (needs ANSWER_PHONE_CALLS). */
    fun hangUp(context: Context) {
        if (CallManager.ui.value.phase != CallPhase.IDLE) { CallManager.hangUp(); return }
        val ok = if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            @Suppress("DEPRECATION")
            runCatching { context.getSystemService(TelecomManager::class.java).endCall() }.getOrDefault(false)
        } else false
        if (!ok) Toast.makeText(context, "Press the red button in your phone app to hang up.", Toast.LENGTH_LONG).show()
    }

    fun callTrusted(context: Context) {
        val prefs = Prefs(context)
        if (prefs.trustedNumber.isBlank()) {
            Toast.makeText(context, "Add someone you trust in Settings first.", Toast.LENGTH_LONG).show(); return
        }
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + prefs.trustedNumber)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** National cybercrime helpline 1930. */
    fun dialHelpline(context: Context) =
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openReportingPortal(context: Context) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun stopListening(context: Context) {
        val src = GuardState.session.value.source
        when {
            src.startsWith("simulation") -> Simulator.stop()
            src == "manual" -> GuardService.send(context, GuardService.ACTION_STOP_LISTEN)
        }
    }
}

/** Preview: plays a scripted call silently through the detector on this phone. */
object Simulator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun play(call: DemoCall, secondsPerTurn: Float = 2.2f) {
        stop()
        GuardState.start("simulation: ${call.title}")
        job = scope.launch {
            delay(800)
            for ((speaker, text) in call.turns) {
                GuardState.addUtterance(speaker, text)
                delay((secondsPerTurn * 1000).toLong())
            }
            delay(1500)
            GuardState.end()
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (GuardState.session.value.source.startsWith("simulation")) GuardState.end()
    }
}

data class CallerState(
    val call: DemoCall? = null,
    val index: Int = -1,
    /** The presenter should say the victim's line out loud, then tap. */
    val waitingForYou: Boolean = false,
    val startedAt: Long = 0,
    val finished: Boolean = false,
)

/**
 * The second phone in the two-phone demo. It plays the caller's side out loud, like a call on
 * speaker, so the listening phone hears real audio through its microphone.
 * Caller and victim get different voice pitches so the room can tell them apart.
 */
object CallerPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CallerState())
    val state: StateFlow<CallerState> = _state.asStateFlow()
    private var presenterSaysOwnLines = false

    fun start(context: Context, call: DemoCall, presenterLines: Boolean) {
        Voice.init(context)
        presenterSaysOwnLines = presenterLines
        _state.value = CallerState(call = call, startedAt = System.currentTimeMillis())
        scope.launch { delay(1800); next() }
    }

    fun next() {
        val s = _state.value
        val call = s.call ?: return
        val i = s.index + 1
        if (i >= call.turns.size) { _state.value = s.copy(index = i, waitingForYou = false, finished = true); return }
        val (speaker, text) = call.turns[i]
        _state.value = s.copy(index = i, waitingForYou = false)
        if (speaker == Speaker.YOU && presenterSaysOwnLines) { _state.value = _state.value.copy(waitingForYou = true); return }
        val lang = if (call.language == "Hindi") "hi-IN" else "en-IN"
        val pitch = if (speaker == Speaker.CALLER) 0.85f else 1.2f
        Voice.speak(text, lang, pitch = pitch, rate = 0.95f) {
            scope.launch {
                delay(650)
                val now = _state.value
                if (now.call == call && now.index == i && !now.finished) next()
            }
        }
    }

    fun saidIt() { if (_state.value.waitingForYou) next() }

    fun end() { Voice.stop(); _state.value = CallerState() }
}
