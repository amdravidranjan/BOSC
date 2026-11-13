package com.bosc.sdk

import android.content.Context
import kotlin.math.sqrt

/** On-device payment history for the "unusual payment" features. Amounts and payee names never leave the phone. */
internal class PaymentHistory(context: Context) {
    private val prefs = context.getSharedPreferences("bosc_sdk_history", Context.MODE_PRIVATE)

    private fun entries(): List<Triple<Long, Double, String>> =
        prefs.getString("payments", "")!!.lines().filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split('\t')
            if (parts.size < 3) null else Triple(parts[0].toLong(), parts[1].toDouble(), parts[2])
        }

    @Synchronized
    fun record(amount: Double, payee: String) {
        val kept = entries().takeLast(199) + Triple(System.currentTimeMillis(), amount, normalise(payee))
        prefs.edit().putString("payments", kept.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }).apply()
    }

    fun isKnownPayee(payee: String) = entries().any { it.third == normalise(payee) }

    fun paymentsInLast(ms: Long): Int = System.currentTimeMillis().let { now -> entries().count { now - it.first <= ms } }

    /** z-score of the amount against the user's past payments. Needs at least 5 past payments. */
    fun amountZScore(amount: Double): Float {
        val xs = entries().map { it.second }
        if (xs.size < 5) return if (amount >= 25_000) 2.5f else 0f // cold start: large amounts still count
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size).coerceAtLeast(1.0)
        return ((amount - mean) / sd).toFloat()
    }

    @Synchronized
    fun logOverride(level: String, score: Float, reason: String) {
        val line = "${System.currentTimeMillis()}\t$level\t${"%.2f".format(score)}\t$reason"
        val kept = overrides().takeLast(99) + line
        prefs.edit().putString("overrides", kept.joinToString("\n")).apply()
    }

    fun overrides(): List<String> = prefs.getString("overrides", "")!!.lines().filter { it.isNotBlank() }

    private fun normalise(p: String) = p.trim().lowercase().replace(Regex("\\s+"), " ").replace('\t', ' ')

    companion object {
        private val RISKY = listOf(
            "rbi", "reserve bank", "safe account", "secure account", "customs", "police", "cbi", "court", "cyber",
            "kyc", "refund", "verification", "penalty", "fine", "income tax", "trai", "narcotics", "escrow",
        )

        fun riskyPayeeKeyword(payee: String): String? {
            val p = payee.lowercase()
            return RISKY.firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(p) }
        }
    }
}
