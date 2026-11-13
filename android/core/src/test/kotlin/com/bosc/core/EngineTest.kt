package com.bosc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    @Test
    fun demoCallsBehaveAsExpected() {
        val report = Evaluator.runAll()
        println(report.table())
        for (r in report.results) {
            if (r.call.isScam && r.call.redTeam) assertTrue("red-team scam not even warned: ${r.call.id}", r.firstWarn in 0..r.call.paymentTurn)
            else if (r.call.isScam) assertTrue("scam not caught: ${r.call.id}", r.caught)
            else assertTrue("genuine call paused: ${r.call.id}", !r.falsePause)
        }
        assertTrue("too many genuine warnings", report.falseWarnRate <= 0.15f)
    }

    @Test
    fun deliveryOtpIsNotACredentialScam() {
        val s = RuleWindowScorer().score(listOf(
            Utterance(0, Speaker.CALLER, "Sir main gate pe khada hoon, delivery ke liye OTP bata dijiye"),
        ))
        assertTrue(s.scamProbability < 0.2f)
    }

    @Test
    fun neverShareOtpIsAGenuineCue() {
        val s = RuleWindowScorer().score(listOf(
            Utterance(0, Speaker.CALLER, "The bank will never ask for your OTP or PIN on a call."),
        ))
        assertTrue(s.genuineCue > 0.8f)
        assertTrue((s.tactics[Tactic.CREDENTIAL_REQUEST] ?: 0f) < 0.2f)
    }

    @Test
    fun pinCodeIsNotAPin() {
        val s = RuleWindowScorer().score(listOf(Utterance(0, Speaker.CALLER, "What is your pin code for delivery?")))
        assertTrue((s.tactics[Tactic.CREDENTIAL_REQUEST] ?: 0f) < 0.3f)
    }

    @Test
    fun trustedOverrideCapsAtWarn() {
        val engine = StreamingRiskEngine()
        engine.markTrusted()
        DemoCalls.byId("scam_digital_arrest_en").turns.forEachIndexed { i, (sp, t) -> engine.onUtterance(Utterance(i.toLong(), sp, t)) }
        assertEquals(RiskLevel.WARN, engine.level)
    }

    @Test
    fun jsonEventHasSharedFields() {
        val e = StreamingRiskEngine().onUtterance(Utterance(1, Speaker.CALLER, "Say \"hi\""))
        val json = e.toJson()
        for (k in listOf("\"t\"", "\"speaker\"", "\"text\"", "\"tactics\"", "\"window_risk\"", "\"call_risk\"", "\"level\"")) assertTrue(json.contains(k))
        assertTrue(json.contains("Say \\\"hi\\\""))
    }
}
