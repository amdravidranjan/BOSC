package com.bosc.core

/** The 8 turn-level tactics from the label schema (plan section 4.3). */
enum class Tactic(val id: String, val label: String) {
    URGENCY("urgency", "Urgency"),
    THREAT("threat", "Threat"),
    SECRECY_ISOLATION("secrecy_isolation", "Secrecy"),
    AUTHORITY_CLAIM("authority_claim", "Claims authority"),
    CREDENTIAL_REQUEST("credential_request", "Asks for OTP/PIN"),
    REMOTE_ACCESS("remote_access", "Remote-access app"),
    PAYMENT_INSTRUCTION("payment_instruction", "Payment instruction"),
    STAY_ON_LINE("stay_on_line", "Keeps you on the line");
}

enum class Speaker(val tag: String, val id: String) {
    CALLER("[CALLER]", "caller"),
    YOU("[YOU]", "you"),
    /** Live speech-to-text cannot tell speakers apart, so live turns use this. */
    UNKNOWN("[SPEAKER]", "unknown");
}

data class Utterance(val tMillis: Long, val speaker: Speaker, val text: String)

/** Character span in an utterance's text that triggered a tactic (for UI highlighting). */
data class Span(val start: Int, val end: Int, val tactic: Tactic)

data class WindowScore(
    /** Probability per tactic for the current window. */
    val tactics: Map<Tactic, Float>,
    /** Probability that the current window is part of a scam. */
    val scamProbability: Float,
    /** Strength of genuine-call cues (delivery context, "bank never asks for OTP", ...). */
    val genuineCue: Float,
    /** Spans in the newest utterance, for highlighting. Empty if the scorer cannot explain itself. */
    val spans: List<Span> = emptyList(),
)

/**
 * Scores the last few utterances of a call.
 * The rule scorer implements this today; the distilled ONNX student (plan Stage 4) should
 * implement the same interface so the streaming layer and the UI do not change.
 */
interface WindowScorer {
    val name: String
    fun score(window: List<Utterance>): WindowScore
}

enum class RiskLevel { LOW, WARN, PAUSE }

/** A whole-call detector: one event per utterance, a level, and the tactics seen so far. */
interface CallDetector {
    val name: String
    val warnThreshold: Float
    val pauseThreshold: Float
    val level: RiskLevel
    val events: List<RiskEvent>
    fun reset()
    fun onUtterance(u: Utterance): RiskEvent
    /** The user tapped "I trust this caller": keep scoring, cap the level at WARN. */
    fun markTrusted()
    fun topTactics(min: Float = 0.5f): List<Tactic>
}

/** The detectors the app can run. */
enum class DetectorKind(val label: String) {
    COMBINED("Trained model + rules"),
    STAGE0("Trained model only (team Stage 0)"),
    RULES("Keyword rules only");

    fun create(): CallDetector = when (this) {
        COMBINED -> CombinedDetector(Stage0Detector(), StreamingRiskEngine())
        STAGE0 -> Stage0Detector()
        RULES -> StreamingRiskEngine()
    }
}

/**
 * Runs two detectors on every utterance and reports the more worried one, on a common scale.
 * Each detector's risk is mapped so its own WARN -> 0.45 and PAUSE -> 0.70 (piecewise linear),
 * then the maximum is taken. The level is the higher of the two levels.
 * Why: the trained model is English-only but reads context; the rules cover Hinglish/Hindi/Tamil.
 */
class CombinedDetector(private val a: CallDetector, private val b: CallDetector) : CallDetector {
    override val name get() = "${a.name} + ${b.name}"
    override val warnThreshold = 0.45f
    override val pauseThreshold = 0.70f
    override var level = RiskLevel.LOW
        private set
    override val events = mutableListOf<RiskEvent>()

    private fun normalise(r: Float, d: CallDetector): Float {
        val w = d.warnThreshold; val p = d.pauseThreshold
        return when {
            r < w -> r / w * warnThreshold
            r < p -> warnThreshold + (r - w) / (p - w) * (pauseThreshold - warnThreshold)
            else -> pauseThreshold + (r - p) / (1f - p).coerceAtLeast(1e-6f) * (1f - pauseThreshold)
        }.coerceIn(0f, 1f)
    }

    override fun reset() { a.reset(); b.reset(); events.clear(); level = RiskLevel.LOW }
    override fun markTrusted() { a.markTrusted(); b.markTrusted(); if (level == RiskLevel.PAUSE) level = RiskLevel.WARN }

    override fun onUtterance(u: Utterance): RiskEvent {
        val ea = a.onUtterance(u); val eb = b.onUtterance(u)
        level = maxOf(ea.level, eb.level)
        val tactics = (ea.tactics.keys + eb.tactics.keys).associateWith { t -> maxOf(ea.tactics[t] ?: 0f, eb.tactics[t] ?: 0f) }
        val spans = (ea.spans + eb.spans).sortedBy { it.start }
        val e = RiskEvent(events.size, u, tactics, maxOf(ea.windowRisk, eb.windowRisk),
            maxOf(normalise(ea.callRisk, a), normalise(eb.callRisk, b)), level, spans)
        events += e
        return e
    }

    override fun topTactics(min: Float): List<Tactic> = (a.topTactics(min) + b.topTactics(min)).distinct()
}

/** One event per utterance. Field names match the shared JSON event in plan section 12. */
data class RiskEvent(
    val turnIndex: Int,
    val utterance: Utterance,
    val tactics: Map<Tactic, Float>,
    val windowRisk: Float,
    val callRisk: Float,
    val level: RiskLevel,
    val spans: List<Span>,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"t\":").append(utterance.tMillis).append(',')
        append("\"speaker\":").append(quote(utterance.speaker.id)).append(',')
        append("\"text\":").append(quote(utterance.text)).append(',')
        append("\"tactics\":{")
        append(Tactic.entries.joinToString(",") { "${quote(it.id)}:${fmt(tactics[it] ?: 0f)}" })
        append("},")
        append("\"window_risk\":").append(fmt(windowRisk)).append(',')
        append("\"call_risk\":").append(fmt(callRisk)).append(',')
        append("\"level\":").append(quote(level.name))
        append('}')
    }

    private fun fmt(f: Float) = String.format(java.util.Locale.US, "%.4f", f)

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
