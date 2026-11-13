package com.bosc.core

import kotlin.math.exp

/**
 * Stage 0 detector: multilingual pattern rules plus hand-set context logic.
 *
 * Window = last [windowSize] utterances. Each turn is matched against the lexicon; tactic
 * probabilities are a noisy-OR over pattern hits, combined across turns with a max.
 * Caller turns count fully, the user's own turns count half (the victim repeating "OTP"
 * is not the caller asking for it). Live ASR turns are UNKNOWN and count fully.
 *
 * The window scam probability is a hand-weighted logistic over tactics, pairwise
 * interactions and genuine cues. All weights are listed here so they can go on a slide.
 */
class RuleWindowScorer(private val windowSize: Int = 4) : WindowScorer {

    override val name = "A. Keyword rules (multilingual, context-aware)"

    private class TurnHits(val tactics: FloatArray, val spans: List<Span>, val genuine: Float, val delivery: Boolean, val credentialKinds: Int)

    private fun analyze(u: Utterance): TurnHits {
        val text = u.text.lowercase()
        val strength = FloatArray(Tactic.entries.size)
        val spans = mutableListOf<Span>()
        var credentialKinds = 0
        for ((tactic, pats) in Lexicon.tactics) {
            var notHit = 1f
            for (pat in pats) {
                val m = pat.regex.find(text) ?: continue
                notHit *= (1f - pat.weight)
                if (tactic == Tactic.CREDENTIAL_REQUEST && pat.weight >= 0.9f) credentialKinds++
                spans += Span(m.range.first, m.range.last + 1, tactic)
            }
            strength[tactic.ordinal] = 1f - notHit
        }
        var genuineNot = 1f
        val cancelled = Lexicon.cueCancel.any { it.regex.containsMatchIn(text) }
        if (!cancelled) for (pat in Lexicon.genuineCues) if (pat.regex.containsMatchIn(text)) genuineNot *= (1f - pat.weight)
        val genuine = 1f - genuineNot
        val delivery = Lexicon.deliveryContext.any { it.regex.containsMatchIn(text) }

        // "Never share your OTP" is a warning, not a credential request.
        if (genuine >= 0.85f) { strength[Tactic.CREDENTIAL_REQUEST.ordinal] *= 0.1f; credentialKinds = 0 }

        val speakerWeight = if (u.speaker == Speaker.YOU) 0.5f else 1f
        for (i in strength.indices) strength[i] *= speakerWeight
        return TurnHits(strength, spans, genuine, delivery, if (u.speaker == Speaker.YOU) 0 else credentialKinds)
    }

    override fun score(window: List<Utterance>): WindowScore {
        val turns = window.takeLast(windowSize).map(::analyze)
        if (turns.isEmpty()) return WindowScore(emptyMap(), 0f, 0f)

        val t = FloatArray(Tactic.entries.size)
        for (turn in turns) for (i in t.indices) t[i] = maxOf(t[i], turn.tactics[i])
        val genuine = turns.maxOf { it.genuine }
        val delivery = turns.any { it.delivery }
        // Asking for two or more different secrets (card number + expiry, OTP + PIN, ...) is credential harvesting.
        val multiCredential = if (turns.sumOf { it.credentialKinds } >= 2 && !delivery) 1f else 0f

        fun v(x: Tactic) = t[x.ordinal]

        // A delivery agent asking for the delivery OTP, with no threat, authority or payment push.
        if (delivery && v(Tactic.THREAT) < 0.5f && v(Tactic.AUTHORITY_CLAIM) < 0.7f &&
            v(Tactic.PAYMENT_INSTRUCTION) < 0.7f && v(Tactic.REMOTE_ACCESS) < 0.5f
        ) {
            t[Tactic.CREDENTIAL_REQUEST.ordinal] *= 0.25f
            t[Tactic.URGENCY.ordinal] *= 0.5f
        }

        val z = W.bias +
            W.urgency * v(Tactic.URGENCY) +
            W.threat * v(Tactic.THREAT) +
            W.secrecy * v(Tactic.SECRECY_ISOLATION) +
            W.authority * v(Tactic.AUTHORITY_CLAIM) +
            W.credential * v(Tactic.CREDENTIAL_REQUEST) +
            W.remote * v(Tactic.REMOTE_ACCESS) +
            W.payment * v(Tactic.PAYMENT_INSTRUCTION) +
            W.stay * v(Tactic.STAY_ON_LINE) +
            W.authXthreat * v(Tactic.AUTHORITY_CLAIM) * v(Tactic.THREAT) +
            W.threatXpay * v(Tactic.THREAT) * maxOf(v(Tactic.PAYMENT_INSTRUCTION), v(Tactic.CREDENTIAL_REQUEST)) +
            W.credXurg * v(Tactic.CREDENTIAL_REQUEST) * v(Tactic.URGENCY) +
            W.remoteXpay * v(Tactic.REMOTE_ACCESS) * maxOf(v(Tactic.PAYMENT_INSTRUCTION), v(Tactic.CREDENTIAL_REQUEST)) +
            W.authXpay * v(Tactic.AUTHORITY_CLAIM) * v(Tactic.PAYMENT_INSTRUCTION) +
            W.secrecyXpay * v(Tactic.SECRECY_ISOLATION) * v(Tactic.PAYMENT_INSTRUCTION) +
            W.multiCredential * multiCredential -
            W.genuine * genuine

        val tactics = Tactic.entries.associateWith { t[it.ordinal] }
        return WindowScore(tactics, sigmoid(z), genuine, turns.last().spans)
    }

    /** Hand-set weights. Documented openly; a learned model replaces them. */
    object W {
        const val bias = -3.2f
        const val urgency = 1.2f
        const val threat = 1.4f
        const val secrecy = 1.8f
        const val authority = 0.6f
        const val credential = 2.0f
        const val remote = 3.0f
        const val payment = 1.1f
        const val stay = 1.8f
        const val authXthreat = 1.5f
        const val threatXpay = 1.2f
        const val credXurg = 1.0f
        const val remoteXpay = 1.5f
        const val authXpay = 1.0f
        const val secrecyXpay = 1.5f
        const val genuine = 2.5f
        const val multiCredential = 1.5f
    }
}

internal fun sigmoid(z: Float): Float = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
