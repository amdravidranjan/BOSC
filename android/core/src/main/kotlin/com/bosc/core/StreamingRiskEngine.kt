package com.bosc.core

/**
 * Stage 3: the call-level streaming layer (plan section 5).
 *
 * A call is judged across time, not one window. For every new utterance we:
 *  1. score the window (last 4 utterances) with the [WindowScorer],
 *  2. update history features (max per tactic, distinct tactics, tactic order),
 *  3. compute a raw call risk with a hand-weighted logistic (the plan's LightGBM slot),
 *  4. smooth it with an asymmetric EMA (rise fast, fall slowly),
 *  5. map it to LOW / WARN / PAUSE with hysteresis.
 *
 * Not thread-safe: call from one thread (the app uses a single coroutine).
 */
class StreamingRiskEngine(
    val scorer: WindowScorer = RuleWindowScorer(),
    val config: RiskConfig = RiskConfig(),
) : CallDetector {
    override val name get() = scorer.name
    override val warnThreshold get() = config.warnThreshold
    override val pauseThreshold get() = config.pauseThreshold

    data class RiskConfig(
        val warnThreshold: Float = 0.45f,
        val pauseThreshold: Float = 0.70f,
        /** WARN drops back to LOW only below warnThreshold - this. */
        val warnHysteresis: Float = 0.12f,
        val emaUp: Float = 0.8f,
        val emaDown: Float = 0.15f,
        val windowSize: Int = 4,
    )

    private val utterances = mutableListOf<Utterance>()
    private val tacticMax = FloatArray(Tactic.entries.size)
    private val firstSeen = IntArray(Tactic.entries.size) { -1 }
    private var maxWindow = 0f
    private var genuineMax = 0f
    private var trusted = false

    var risk = 0f
        private set
    override var level = RiskLevel.LOW
        private set
    override val events = mutableListOf<RiskEvent>()

    override fun reset() {
        utterances.clear(); events.clear()
        tacticMax.fill(0f); firstSeen.fill(-1)
        maxWindow = 0f; genuineMax = 0f; risk = 0f; level = RiskLevel.LOW; trusted = false
    }

    /** The user tapped "I trust this call". Scores keep updating, but the level is capped at WARN. */
    override fun markTrusted() { trusted = true; if (level == RiskLevel.PAUSE) level = RiskLevel.WARN }

    override fun onUtterance(u: Utterance): RiskEvent {
        utterances += u
        val idx = utterances.size - 1
        val w = scorer.score(utterances.takeLast(config.windowSize))

        for (tactic in Tactic.entries) {
            val p = w.tactics[tactic] ?: 0f
            tacticMax[tactic.ordinal] = maxOf(tacticMax[tactic.ordinal], p)
            if (p >= 0.5f && firstSeen[tactic.ordinal] < 0) firstSeen[tactic.ordinal] = idx
        }
        maxWindow = maxOf(maxWindow, w.scamProbability)
        genuineMax = maxOf(genuineMax, w.genuineCue)

        val raw = sigmoid(callLogit(w.scamProbability))
        risk += (if (raw > risk) config.emaUp else config.emaDown) * (raw - risk)
        level = nextLevel()

        val event = RiskEvent(idx, u, w.tactics, w.scamProbability, risk, level, w.spans)
        events += event
        return event
    }

    private fun seen(t: Tactic) = firstSeen[t.ordinal] >= 0
    private fun before(a: Tactic, b: Tactic) = seen(a) && seen(b) && firstSeen[a.ordinal] <= firstSeen[b.ordinal]
    private fun mx(t: Tactic) = tacticMax[t.ordinal]

    private fun callLogit(current: Float): Float {
        val distinct = tacticMax.count { it >= 0.5f }
        val authThreat = if (before(Tactic.AUTHORITY_CLAIM, Tactic.THREAT)) 1f else 0f
        val threatPay = if (before(Tactic.THREAT, Tactic.PAYMENT_INSTRUCTION) || before(Tactic.THREAT, Tactic.CREDENTIAL_REQUEST)) 1f else 0f
        val hardTactics = maxOf(mx(Tactic.REMOTE_ACCESS), mx(Tactic.SECRECY_ISOLATION), mx(Tactic.STAY_ON_LINE), mx(Tactic.CREDENTIAL_REQUEST))
        val threatLevel = mx(Tactic.THREAT)
        return C.bias +
            C.current * current +
            C.maxSoFar * maxWindow +
            C.distinct * distinct +
            C.authThreat * authThreat +
            C.threatPay * threatPay +
            C.hardTactics * hardTactics -
            C.genuine * genuineMax * (1f - threatLevel)
    }

    private fun nextLevel(): RiskLevel {
        val c = config
        val proposed = when {
            risk >= c.pauseThreshold -> RiskLevel.PAUSE
            risk >= c.warnThreshold -> RiskLevel.WARN
            else -> RiskLevel.LOW
        }
        val sticky = when (level) {
            // PAUSE latches for the rest of the call: once a call has shown full scam pressure,
            // the payment moment stays protected even if the caller turns friendly.
            RiskLevel.PAUSE -> RiskLevel.PAUSE
            RiskLevel.WARN -> if (proposed == RiskLevel.LOW && risk >= c.warnThreshold - c.warnHysteresis) RiskLevel.WARN else proposed
            RiskLevel.LOW -> proposed
        }
        return if (trusted && sticky == RiskLevel.PAUSE) RiskLevel.WARN else sticky
    }

    /** Hand-set call-level weights (the plan's LightGBM + isotonic calibration slot). */
    object C {
        const val bias = -4.2f
        const val current = 2.5f
        const val maxSoFar = 1.5f
        const val distinct = 0.6f
        const val authThreat = 1.0f
        const val threatPay = 1.0f
        const val hardTactics = 2.0f
        const val genuine = 2.0f
    }

    /** Top tactics seen so far in this call, strongest first. */
    override fun topTactics(min: Float): List<Tactic> =
        Tactic.entries.filter { mx(it) >= min }.sortedByDescending { mx(it) }
}
