package com.bosc.core

/**
 * Runs scripted calls through the streaming engine and reports the plan's call-level metrics
 * (section 2): recall at PAUSE by the payment turn, false-alarm rate at PAUSE and WARN,
 * and detection earliness in caller turns.
 *
 * Use it on the lockbox / real-audio transcripts once they exist. On [DemoCalls] it is only a
 * smoke test, because the demo calls were written by the same person as the rules.
 */
object Evaluator {

    data class CallResult(
        val call: DemoCall,
        val firstWarn: Int,
        val firstPause: Int,
        val maxRisk: Float,
        val riskTrace: List<Float>,
    ) {
        /** Scam caught: PAUSE reached at or before the turn that asks for the money/OTP/app. */
        val caught get() = call.isScam && firstPause in 0..call.paymentTurn
        val falsePause get() = !call.isScam && firstPause >= 0
        val falseWarn get() = !call.isScam && firstWarn >= 0
        /** Caller turns between the gold suspicious turn and the first WARN (null if never warned). */
        val earliness: Int?
            get() {
                if (!call.isScam || firstWarn < 0) return null
                val (from, to) = call.firstSuspiciousTurn to firstWarn
                if (to <= from) return 0
                return (from + 1..to).count { call.turns[it].first != Speaker.YOU }
            }
    }

    data class Report(val results: List<CallResult>) {
        private val scams = results.filter { it.call.isScam }
        private val genuine = results.filter { !it.call.isScam }
        val recall = scams.count { it.caught }.toFloat() / scams.size.coerceAtLeast(1)
        val falsePauseRate = genuine.count { it.falsePause }.toFloat() / genuine.size.coerceAtLeast(1)
        val falseWarnRate = genuine.count { it.falseWarn }.toFloat() / genuine.size.coerceAtLeast(1)
        val medianEarliness: Double? = scams.mapNotNull { it.earliness }.sorted().let {
            if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2].toDouble() else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
        }

        fun table(): String = buildString {
            appendLine(String.format("%-28s %-6s %5s %5s %6s %s", "call", "type", "warn", "pause", "max", "result"))
            for (r in results) {
                val verdict = when {
                    r.call.isScam && r.caught -> "caught (earliness ${r.earliness})"
                    r.call.isScam -> "MISSED (payment turn ${r.call.paymentTurn})"
                    r.falsePause -> "FALSE PAUSE"
                    r.falseWarn -> "false warn"
                    else -> "ok, stayed low"
                }
                appendLine(String.format("%-28s %-6s %5d %5d %6.2f %s", r.call.id.take(28), r.call.typeId, r.firstWarn, r.firstPause, r.maxRisk, verdict))
            }
            appendLine(String.format("recall@PAUSE=%.2f  falsePause=%.2f  falseWarn=%.2f  medianEarliness=%s  (n=%d scam, %d genuine)",
                recall, falsePauseRate, falseWarnRate, medianEarliness?.toString() ?: "-", scams.size, genuine.size))
        }
    }

    fun run(call: DemoCall, engine: CallDetector = StreamingRiskEngine()): CallResult {
        engine.reset()
        var warn = -1; var pause = -1
        val trace = call.turns.mapIndexed { i, (speaker, text) ->
            val e = engine.onUtterance(Utterance(i * 4000L, speaker, text))
            if (e.level >= RiskLevel.WARN && warn < 0) warn = i
            if (e.level == RiskLevel.PAUSE && pause < 0) pause = i
            e.callRisk
        }
        return CallResult(call, warn, pause, trace.max(), trace)
    }

    fun runAll(calls: List<DemoCall> = DemoCalls.all, engineFactory: () -> CallDetector = { StreamingRiskEngine() }) =
        Report(calls.map { run(it, engineFactory()) })
}
