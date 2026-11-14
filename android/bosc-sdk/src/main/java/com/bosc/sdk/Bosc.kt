package com.bosc.sdk

import android.content.Context
import android.util.Log
import kotlin.math.exp

/**
 * BOSC SDK: the library a payment app embeds (plan section 7.12).
 *
 * The SDK never touches call audio. It reads device signals at the payment moment and,
 * when the BOSC guardian app is installed and armed, a recent call-pressure score.
 *
 * ```
 * Bosc.init(context, BoscConfig())
 * val decision = Bosc.evaluate(PaymentContext(amount = 48000.0, payee = "RBI Safe Account", isNewPayee = true))
 * when (decision.level) { RiskLevel.LOW -> pay(); RiskLevel.MEDIUM -> delay(); RiskLevel.HIGH -> pause() }
 * ```
 */
object Bosc {
    private const val TAG = "BoscSdk"

    private lateinit var appContext: Context
    private lateinit var config: BoscConfig
    private lateinit var signals: SignalReader
    private lateinit var history: PaymentHistory
    private lateinit var signer: RiskTokenSigner

    /** Demo switches, for stage demos when no real call or remote-control session is available. */
    val demo = DemoSignals()

    val isInitialized get() = ::appContext.isInitialized

    fun init(context: Context, config: BoscConfig = BoscConfig()) {
        appContext = context.applicationContext
        this.config = config
        signals = SignalReader(appContext)
        history = PaymentHistory(appContext)
        signer = RiskTokenSigner(config.keyAlias)
    }

    /** Evaluate the payment the user is about to make. Fast (no network); safe to call on the main thread. */
    fun evaluate(payment: PaymentContext): RiskDecision {
        check(isInitialized) { "Call Bosc.init() first" }
        val s = collectSignals(payment)
        val (score, reasons) = fuse(s)
        var level = when {
            score >= config.highThreshold -> RiskLevel.HIGH
            score >= config.mediumThreshold -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        // The guardian app saw full scam pressure on the live call: never less than HIGH.
        if (s.callPressure?.level == "PAUSE") level = RiskLevel.HIGH
        else if (s.callPressure?.level == "WARN" && level == RiskLevel.LOW) level = RiskLevel.MEDIUM

        if (s.onCall || s.callPressure?.fresh == true) signals.reportPayAttempt(level.name, payment.amount)
        val token = signer.sign(score, level)
        Log.i(TAG, "evaluate amount=${payment.amount} level=$level score=${"%.2f".format(score)} reasons=$reasons")
        return RiskDecision(level, score, reasons, token, s)
    }

    /** Call after a payment completes so the SDK can learn the user's normal amounts and payees. */
    fun recordPayment(payment: PaymentContext) = history.record(payment.amount, payment.payee)

    /** Log when the user overrides a pause ("I trust this call"). Fintechs will ask for this audit trail. */
    fun recordOverride(decision: RiskDecision, reason: String) = history.logOverride(decision.level.name, decision.score, reason)

    fun overrideLog(): List<String> = history.overrides()

    fun isKnownPayee(payee: String) = history.isKnownPayee(payee)

    internal fun collectSignals(p: PaymentContext): Signals {
        val detected = signals.callType()
        val callType = when (demo.forceOnCall) { true -> if (detected == CallType.NONE) CallType.PHONE else detected; false -> CallType.NONE; null -> detected }
        val onCall = callType != CallType.NONE
        val speaker = demo.forceSpeaker ?: (onCall && signals.isSpeakerOn())
        val remoteApps = signals.installedRemoteAccessApps()
        val accessibility = signals.enabledAccessibilityServices()
        val pressure = demo.forceCallPressure?.let { CallPressure(it, if (it >= 0.7f) "PAUSE" else if (it >= 0.45f) "WARN" else "LOW", emptyList(), System.currentTimeMillis(), true) }
            ?: signals.callPressure(config.callPressureMaxAgeMs)
        return Signals(
            onCall = onCall,
            callType = callType,
            speakerOn = speaker,
            remoteAccessAppsInstalled = remoteApps,
            accessibilityServices = accessibility,
            screenCaptureOrControl = demo.simulateScreenControl,
            amountZScore = history.amountZScore(p.amount),
            usualRatio = pressure?.usualPayment?.takeIf { it > 0 }?.let { (p.amount / it).toFloat() },
            isNewPayee = p.isNewPayee ?: !history.isKnownPayee(p.payee),
            payeeKeyword = PaymentHistory.riskyPayeeKeyword(p.payee),
            nightHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 0..5,
            recentPaymentsCount = history.paymentsInLast(10 * 60_000L),
            callPressure = pressure,
        )
    }

    /**
     * Hand-set logistic fusion. Weights are documented openly; a production model needs real
     * fraud outcomes from a partner to learn them.
     */
    internal fun fuse(s: Signals): Pair<Float, List<String>> {
        val reasons = mutableListOf<String>()
        var z = W.BIAS
        when (s.callType) {
            CallType.PHONE -> { z += W.ON_CALL; reasons += "You are on a phone call right now" }
            CallType.INTERNET -> { z += W.ON_CALL + W.INTERNET_CALL; reasons += "You are on a WhatsApp or internet call right now" }
            CallType.NONE -> Unit
        }
        if (s.onCall && s.speakerOn) { z += W.SPEAKER; reasons += "The call is on speaker" }
        if (s.remoteAccessAppsInstalled.isNotEmpty()) { z += W.REMOTE_APP; reasons += "A screen-sharing app is installed (${s.remoteAccessAppsInstalled.joinToString()})" }
        if (s.screenCaptureOrControl) { z += W.SCREEN_CONTROL; reasons += "Your screen is being shared or controlled" }
        if (s.accessibilityServices.any { it.substringBefore('/') in SignalReader.REMOTE_PACKAGES }) {
            z += W.ACCESSIBILITY_REMOTE; reasons += "A remote-control app has accessibility access"
        } else if (s.accessibilityServices.isNotEmpty()) z += W.ACCESSIBILITY
        if (s.isNewPayee) { z += W.NEW_PAYEE; reasons += "First payment to this payee" }
        if (s.onCall && s.isNewPayee) z += W.CALL_X_NEW_PAYEE
        if (s.amountZScore > 2f) { z += W.AMOUNT_Z * s.amountZScore.coerceAtMost(4f); reasons += "Much larger than your usual payments" }
        else s.usualRatio?.takeIf { it >= 5f }?.let { r ->
            // Same form as the web app's shield: 0.9 * log10(ratio), capped at 3.
            z += 0.9f * kotlin.math.log10(r).coerceAtMost(3f); reasons += "This is ${r.toInt()}× your usual payment"
        }
        s.payeeKeyword?.let { z += W.PAYEE_KEYWORD; reasons += "Payee name looks like \"$it\", which real officials never collect" }
        if (s.nightHour) z += W.NIGHT
        if (s.recentPaymentsCount >= 3) { z += W.VELOCITY; reasons += "Several payments in the last 10 minutes" }
        s.callPressure?.takeIf { it.fresh }?.let { p ->
            z += W.CALL_PRESSURE * p.risk
            if (p.risk >= 0.45f) reasons.add(0, "BOSC heard pressure on your call: ${p.tactics.take(3).joinToString().ifEmpty { "scam patterns" }}")
        }
        return (1f / (1f + exp(-z))) to reasons
    }

    /** Fusion weights (logit scale). */
    object W {
        const val BIAS = -3.0f
        const val ON_CALL = 1.2f
        /** Digital-arrest scams often move victims to WhatsApp or Skype video calls. */
        const val INTERNET_CALL = 0.3f
        const val SPEAKER = 0.6f
        const val REMOTE_APP = 1.0f
        const val SCREEN_CONTROL = 2.5f
        const val ACCESSIBILITY = 0.3f
        const val ACCESSIBILITY_REMOTE = 1.5f
        const val NEW_PAYEE = 0.8f
        const val CALL_X_NEW_PAYEE = 1.0f
        const val AMOUNT_Z = 0.3f
        const val PAYEE_KEYWORD = 1.0f
        const val NIGHT = 0.4f
        const val VELOCITY = 0.6f
        const val CALL_PRESSURE = 4.0f
    }
}
