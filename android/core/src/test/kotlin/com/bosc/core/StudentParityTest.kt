package com.bosc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Kotlin student == Python (backend/v2_runtime.py, onnxruntime 1.19.2) for the round-1 MiniLM student:
 * WordPiece ids, int8 ONNX logits, the streaming model, and the Stage 0 + student ensemble end to end.
 */
class StudentParityTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/student/$name")!!.bufferedReader().use { it.readText() }
    private fun arr(name: String) = Json.parseToJsonElement(res(name)).jsonArray

    @Test fun tokenizerIdsMatchPython() {
        val m = StudentBundle.model
        var bad = 0; var n = 0
        for (c in arr("tokenizer_cases.json")) {
            val o = c.jsonObject; val text = o["text"]!!.jsonPrimitive.content
            val want = o["input_ids"]!!.jsonArray.map { it.jsonPrimitive.int.toLong() }
            val got = m.encode(text).first.toList()
            n++
            if (got != want) { bad++; if (bad <= 5) println("MISMATCH: ${text.take(120)}\n  want ${want.take(40)}\n  got  ${got.take(40)}") }
        }
        println("tokenizer cases $n, mismatches = $bad")
        assertEquals(0, bad)
    }

    @Test fun onnxLogitsMatchPython() {
        val m = StudentBundle.model
        var maxErr = 0.0; var idsBad = 0
        val cases = arr("onnx_parity_cases.json")
        for (c in cases) {
            val o = c.jsonObject; val text = o["text"]!!.jsonPrimitive.content
            if (m.encode(text).first.toList() != o["input_ids"]!!.jsonArray.map { it.jsonPrimitive.int.toLong() }) idsBad++
            val want = o["logits"]!!.jsonArray.map { it.jsonPrimitive.double }
            val got = m.logits(text)
            for (i in want.indices) maxErr = maxOf(maxErr, abs(got[i] - want[i]))
        }
        println("onnx windows ${cases.size}, token mismatches = $idsBad, max |logit diff| = $maxErr")
        assertEquals(0, idsBad)
        assertTrue("logit error $maxErr", maxErr < 1e-4)
    }

    @Test fun streamModelMatchesPython() {
        val s = StreamModel(StudentBundle.streamJson)
        var err = 0.0; var steps = 0
        val calls = arr("stream_parity_cases.json")
        for (call in calls) {
            s.reset()
            for (row in call.jsonArray) {
                val o = row.jsonObject
                val f = o["features"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
                val st = s.push(f, f.copyOfRange(0, 9))
                err = maxOf(err, abs(st.risk - o["risk"]!!.jsonPrimitive.double), abs(st.raw - o["raw"]!!.jsonPrimitive.double),
                    abs(st.calibrated - o["calibrated"]!!.jsonPrimitive.double))
                steps++
            }
        }
        println("stream calls ${calls.size}, steps $steps, max |diff| (risk/raw/calibrated) = $err")
        assertTrue(err < 1e-9)
    }

    @Test fun fullStudentStreamMatchesPython() {
        val sc = StudentScorer(StudentBundle.model, StreamModel(StudentBundle.streamJson), Stage0.bundled.detector)
        var err = 0.0; var turns = 0
        for (call in arr("stream_parity_cases.json").take(10)) {
            sc.reset()
            for (row in call.jsonArray) {
                val o = row.jsonObject
                err = maxOf(err, abs(sc.push(o["text"]!!.jsonPrimitive.content).second - o["risk"]!!.jsonPrimitive.double)); turns++
            }
        }
        println("full student stream: 10 calls, $turns turns, max |risk diff| = $err")
        assertTrue(err < 1e-5)
    }

    @Test fun ensembleMatchesPythonEndToEnd() {
        val d = EnsembleDetector()
        var e0 = 0.0; var e1 = 0.0; var e2 = 0.0; var turns = 0; var levelBad = 0
        val t = StudentBundle.ensemble
        val calls = arr("ensemble_cases.json")
        for (c in calls) {
            val o = c.jsonObject
            d.reset()
            val utts = o["utterances"]!!.jsonArray.map { it.jsonPrimitive.content }
            val a = o["stage0"]!!.jsonArray.map { it.jsonPrimitive.double }
            val b = o["student"]!!.jsonArray.map { it.jsonPrimitive.double }
            val e = o["ensemble"]!!.jsonArray.map { it.jsonPrimitive.double }
            var peakWant = 0.0
            utts.forEachIndexed { i, u ->
                val ev = d.onUtterance(Utterance(i * 1000L, Speaker.CALLER, u))
                e0 = maxOf(e0, abs(d.lastStage0Risk - a[i])); e1 = maxOf(e1, abs(d.lastStudentRisk - b[i])); e2 = maxOf(e2, abs(d.lastScore - e[i]))
                peakWant = maxOf(peakWant, e[i])
                val want = if (peakWant >= t.pause) RiskLevel.PAUSE else if (e[i] >= t.warn) RiskLevel.WARN else RiskLevel.LOW
                if (ev.level != want) levelBad++
                turns++
            }
        }
        println("ensemble: ${calls.size} calls, $turns turns, max |diff| stage0 $e0, student $e1, ensemble $e2, level mismatches $levelBad")
        assertTrue(e0 < 1e-9); assertTrue(e1 < 1e-5); assertTrue(e2 < 1e-9); assertEquals(0, levelBad)
    }
}
