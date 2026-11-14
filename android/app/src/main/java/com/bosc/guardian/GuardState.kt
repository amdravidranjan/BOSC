package com.bosc.guardian

import android.content.Context
import android.util.Log
import com.bosc.core.RiskLevel
import com.bosc.core.Span
import com.bosc.core.Speaker
import com.bosc.core.CallDetector
import com.bosc.core.DetectorKind
import com.bosc.core.Tactic
import com.bosc.core.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TurnView(
    val index: Int,
    val speaker: Speaker,
    val text: String,
    val spans: List<Span>,
    val tactics: Map<Tactic, Float>,
    val windowRisk: Float,
    val callRisk: Float,
    val level: RiskLevel,
    /** Milliseconds since the session started. */
    val tMillis: Long = 0,
)

/** Two transcripts of the same speech: at least half of the shorter one's words appear in the other. */
internal fun similar(a: String, b: String): Boolean {
    fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
    val x = words(a); val y = words(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return x.intersect(y).size >= 0.5 * minOf(x.size, y.size)
}

private const val ECHO_MS = 8000L

/** The user tapped Pay in a payment app during the call; [turn] = number of transcript turns at that moment. */
data class PayMark(val turn: Int, val level: String, val amount: Double, val tMillis: Long)

data class SessionState(
    val active: Boolean = false,
    /** "call", "manual" (speaker demo without a call) or "simulation: <title>". */
    val source: String = "",
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val turns: List<TurnView> = emptyList(),
    val risk: Float = 0f,
    val level: RiskLevel = RiskLevel.LOW,
    val topTactics: List<Tactic> = emptyList(),
    val partial: String = "",
    val asrStatus: String = "",
    val micRmsDb: Float? = null,
    val speakerOn: Boolean? = null,
    val trusted: Boolean = false,
    val logFile: String? = null,
    val detectorName: String = "",
    val warnThreshold: Float = 0.45f,
    val pauseThreshold: Float = 0.70f,
    val payMarks: List<PayMark> = emptyList(),
)

/**
 * Single source of truth for the current call session. The service, the simulator, the UI and
 * the ContentProvider (read by bosc-sdk) all go through here.
 */
object GuardState {
    private const val TAG = "BoscGuard"

    private lateinit var app: Context
    private var engine: CallDetector = DetectorKind.COMBINED.create()
    private var logFile: File? = null

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    /** The most recent finished session, for the after-call summary. */
    private val _last = MutableStateFlow<SessionState?>(null)
    val lastSession: StateFlow<SessionState?> = _last.asStateFlow()

    val armed = MutableStateFlow(false)

    /** ASR results are ignored until this time (BOSC's own spoken warning must not be scored). */
    @Volatile var muteUntil = 0L

    fun init(context: Context) { app = context.applicationContext }

    @Synchronized
    fun start(source: String) {
        if (_session.value.active) end()
        engine = Prefs(app).detector.create()
        val now = System.currentTimeMillis()
        logFile = openLog(now)
        _session.value = SessionState(active = true, source = source, startedAt = now, logFile = logFile?.absolutePath,
            detectorName = engine.name, warnThreshold = engine.warnThreshold, pauseThreshold = engine.pauseThreshold)
        Log.i(TAG, "session start: $source")
    }

    @Synchronized
    /**
     * @param score false = show in the transcript but don't feed the detector (the receiver's own words
     *   in a Bosc call: the trained model is caller-only, and the receiver's mic can pick up room echo).
     */
    fun addUtterance(speaker: Speaker, text: String, score: Boolean = true) {
        val s = _session.value
        if (!s.active || text.isBlank()) return
        val now = System.currentTimeMillis()
        // Only this phone's own microphone can hear Bosc's spoken warning, so only mic-side speech is
        // muted while it plays. The caller's network stream is never dropped.
        if (now < muteUntil && speaker != Speaker.CALLER && !s.source.startsWith("simulation")) {
            Log.d(TAG, "ignored (own warning playing): $text"); return
        }
        val clean = text.trim()
        val t = now - s.startedAt
        // Room echo: in a same-room test the receiver's mic also hears the caller. Drop a receiver line that
        // repeats a recent caller line, and when a caller line arrives, remove the receiver echo shown just before.
        if (speaker == Speaker.YOU && s.turns.any { it.speaker == Speaker.CALLER && t - it.tMillis < ECHO_MS && similar(it.text, clean) }) {
            Log.d(TAG, "dropped echo of caller line: $clean"); return
        }
        val before = s.level
        val view = if (score) {
            val e = engine.onUtterance(Utterance(t, speaker, clean))
            logFile?.let { f -> runCatching { f.appendText(e.toJson() + "\n") } }
            TurnView(e.turnIndex, speaker, clean, e.spans, e.tactics, e.windowRisk, e.callRisk, e.level, t)
        } else {
            // Logged too (marked by window_risk 0 and the unchanged call risk), so evaluation sees the full call.
            logFile?.let { f -> runCatching { f.appendText(com.bosc.core.RiskEvent(-1, Utterance(t, speaker, clean), emptyMap(), 0f, s.risk, s.level, emptyList()).toJson() + "\n") } }
            TurnView(-1, speaker, clean, emptyList(), emptyMap(), 0f, s.risk, s.level, t)
        }
        _session.update { cur ->
            val kept = if (speaker == Speaker.CALLER) cur.turns.filterNot { it.speaker == Speaker.YOU && t - it.tMillis < ECHO_MS && similar(it.text, clean) } else cur.turns
            cur.copy(turns = kept + view, risk = view.callRisk, level = view.level, topTactics = engine.topTactics(), partial = "")
        }
        if (!score) return
        Log.i(TAG, "turn ${view.index} ${speaker.id} risk=${"%.2f".format(view.callRisk)} level=${view.level} :: $clean")
        if (view.level != before) Alerts.onLevelChange(app, before, view.level, _session.value)
    }

    @Synchronized
    fun payAttempt(level: String, amount: Double) {
        val s = _session.value
        if (!s.active) return
        val t = System.currentTimeMillis() - s.startedAt
        _session.update { it.copy(payMarks = it.payMarks + PayMark(it.turns.size, level, amount, t)) }
        logFile?.let { f -> runCatching { f.appendText("{\"t\":$t,\"event\":\"pay_attempt\",\"level\":\"$level\",\"amount\":$amount,\"turn\":${s.turns.size}}\n") } }
        Log.i(TAG, "pay attempt during call: $level ₹$amount at turn ${s.turns.size}")
    }

    fun setPartial(text: String) = _session.update { it.copy(partial = text) }
    fun setAsrStatus(text: String) = _session.update { it.copy(asrStatus = text) }
    fun setMicLevel(db: Float) = _session.update { it.copy(micRmsDb = db) }
    fun setSpeakerOn(on: Boolean?) = _session.update { it.copy(speakerOn = on) }

    @Synchronized
    fun markTrusted() {
        engine.markTrusted()
        _session.update { it.copy(trusted = true, level = engine.level) }
        Alerts.cancelAlert(app)
        logFile?.let { f -> runCatching { f.appendText("{\"t\":${System.currentTimeMillis() - _session.value.startedAt},\"event\":\"user_trusted_call\"}\n") } }
    }

    @Synchronized
    fun end() {
        val s = _session.value
        if (!s.active) return
        val done = s.copy(active = false, endedAt = System.currentTimeMillis(), partial = "")
        _session.value = SessionState()
        if (done.turns.isNotEmpty() || done.micRmsDb != null) _last.value = done
        Alerts.cancelAlert(app)
        Log.i(TAG, "session end: ${done.source}, ${done.turns.size} turns, max risk ${done.turns.maxOfOrNull { it.callRisk }}")
    }

    private fun openLog(start: Long): File? = runCatching {
        val dir = File(app.getExternalFilesDir(null), "logs").apply { mkdirs() }
        File(dir, "call_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(start)) + ".jsonl")
    }.getOrNull()
}
