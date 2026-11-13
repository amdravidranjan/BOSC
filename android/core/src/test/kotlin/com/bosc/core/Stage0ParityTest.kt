package com.bosc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Kotlin Stage 0 == Python backend/bosc_core.py, using the team's own backend/parity_cases.json
 * (204 probability cases, 204 tactic cases, one streamed call). Same pass bar as parity_test.mjs.
 */
class Stage0ParityTest {
    private val cases = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/stage0_parity_cases.json")!!.bufferedReader().readText()
    ).jsonObject
    private val b = Stage0.bundled

    @Test
    fun probabilitiesMatchPython() {
        var maxErr = 0.0
        for (c in cases["proba"]!!.jsonArray) {
            val o = c.jsonObject
            maxErr = maxOf(maxErr, abs(b.model.proba(o["text"]!!.jsonPrimitive.content) - o["p"]!!.jsonPrimitive.double))
        }
        println("proba cases ${cases["proba"]!!.jsonArray.size}, max |diff| = $maxErr")
        assertTrue("max diff $maxErr", maxErr < 1e-9)
    }

    @Test
    fun tacticsMatchPython() {
        var mismatches = 0
        for (c in cases["tactics"]!!.jsonArray) {
            val o = c.jsonObject
            val text = o["text"]!!.jsonPrimitive.content
            val want = o["found"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted().joinToString(",")
            val got = b.detector.detect(text).keys.sorted().joinToString(",")
            if (got != want) { mismatches++; println("tactic mismatch: \"$text\" got=$got want=$want") }
        }
        println("tactic cases ${cases["tactics"]!!.jsonArray.size}, mismatches = $mismatches")
        assertEquals(0, mismatches)
    }

    @Test
    fun streamMatchesPython() {
        val s = cases["stream"]!!.jsonObject
        val utts = s["utterances"]!!.jsonArray.map { it.jsonPrimitive.content }
        val risk = s["risk"]!!.jsonArray.map { it.jsonPrimitive.double }
        val sc = Stage0.StreamingScorer(b.model, b.detector, b.config)
        var maxErr = 0.0
        utts.forEachIndexed { i, u -> maxErr = maxOf(maxErr, abs(sc.push(u).risk - risk[i])) }
        println("stream turns ${utts.size}, max |diff| = $maxErr")
        assertTrue(maxErr < 1e-9)
    }

    @Test
    fun compareDetectorsOnDemoCalls() {
        for (kind in DetectorKind.entries) {
            println("\n=== ${kind.create().name}")
            println(Evaluator.runAll { kind.create() }.table())
        }
    }
}
