package com.bosc.sdk

enum class RiskLevel { LOW, MEDIUM, HIGH }

/** PHONE = SIM call (including carrier Wi-Fi calling). INTERNET = WhatsApp, Telegram, Signal, Skype, Meet and similar. */
enum class CallType { NONE, PHONE, INTERNET }

data class BoscConfig(
    val mediumThreshold: Float = 0.35f,
    val highThreshold: Float = 0.65f,
    /** A call-pressure score older than this (after the call ended) is ignored. */
    val callPressureMaxAgeMs: Long = 10 * 60_000L,
    val keyAlias: String = "bosc_risk_token_key",
)

data class PaymentContext(
    val amount: Double,
    val payee: String,
    /** Leave null to let the SDK decide from its own payment history. */
    val isNewPayee: Boolean? = null,
)

data class CallPressure(
    val risk: Float,
    /** LOW, WARN or PAUSE, as published by the guardian app. */
    val level: String,
    val tactics: List<String>,
    val updatedAt: Long,
    val fresh: Boolean,
    /** The user's usual payment size from the guardian's first-run setup (0 = not set). */
    val usualPayment: Double = 0.0,
)

data class Signals(
    val onCall: Boolean,
    val callType: CallType,
    val speakerOn: Boolean,
    val remoteAccessAppsInstalled: List<String>,
    val accessibilityServices: List<String>,
    /** Production: Play Integrity app-access-risk verdict (CAPTURING / CONTROLLING), decoded server-side. Mocked here. */
    val screenCaptureOrControl: Boolean,
    val amountZScore: Float,
    /** amount / the user's usual payment from the guardian app; null if not set. */
    val usualRatio: Float? = null,
    val isNewPayee: Boolean,
    val payeeKeyword: String?,
    val nightHour: Boolean,
    val recentPaymentsCount: Int,
    /** Null when the BOSC guardian app is not installed, not armed, or not permitted. */
    val callPressure: CallPressure?,
)

data class RiskDecision(
    val level: RiskLevel,
    val score: Float,
    /** Plain-language reasons, most important first. Safe to show to the user. */
    val reasons: List<String>,
    /** HMAC-SHA256 token over {score, level, nonce, timestamp}. Production: verify server-side with Play Integrity. */
    val signedToken: String,
    val signals: Signals,
)

/** Demo-only switches (plan: mock what needs Play Console or a real scam call). */
class DemoSignals {
    @Volatile var forceOnCall: Boolean? = null
    @Volatile var forceSpeaker: Boolean? = null
    @Volatile var simulateScreenControl: Boolean = false
    @Volatile var forceCallPressure: Float? = null
}
