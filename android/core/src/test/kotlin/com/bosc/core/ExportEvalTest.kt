package com.bosc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Evaluation helpers (no assertions):
 *  - exports the demo calls to build/democalls.json,
 *  - if BOSC_EVAL_IN points to a JSON list of {id, scam, utterances[]} (caller-only), scores each call with
 *    rules, Stage 0 and the combined detector and writes BOSC_EVAL_OUT.
 */
class ExportEvalTest {
    @Test
    fun exportDemoCalls() {
        val arr = JsonArray(DemoCalls.all.map { c ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(c.id), "title" to JsonPrimitive(c.title), "type" to JsonPrimitive(c.typeId),
                "scam" to JsonPrimitive(c.isScam), "language" to JsonPrimitive(c.language), "red_team" to JsonPrimitive(c.redTeam),
                "turns" to JsonArray(c.turns.map { (s, t) -> JsonObject(mapOf("speaker" to JsonPrimitive(s.id), "text" to JsonPrimitive(t))) }),
            ))
        })
        File("build/democalls.json").writeText(arr.toString())
    }

    @Test
    fun scoreEvalSet() {
        val inPath = System.getenv("BOSC_EVAL_IN") ?: return
        val calls = Json.parseToJsonElement(File(inPath).readText()).jsonArray
        val out = JsonArray(calls.map { c ->
            val o = c.jsonObject
            val utts = o["utterances"]!!.jsonArray.map { it.jsonPrimitive.content }
            val results = DetectorKind.entries.associate { kind ->
                val d = kind.create()
                var firstWarn = -1; var firstPause = -1; var maxRisk = 0f
                utts.forEachIndexed { i, u ->
                    val e = d.onUtterance(Utterance(i * 3000L, Speaker.CALLER, u))
                    if (e.level >= RiskLevel.WARN && firstWarn < 0) firstWarn = i
                    if (e.level == RiskLevel.PAUSE && firstPause < 0) firstPause = i
                    maxRisk = maxOf(maxRisk, e.callRisk)
                }
                kind.name to JsonObject(mapOf("first_warn" to JsonPrimitive(firstWarn), "first_pause" to JsonPrimitive(firstPause), "max_risk" to JsonPrimitive(maxRisk)))
            }
            JsonObject(mapOf("id" to o["id"]!!, "results" to JsonObject(results)))
        })
        File(System.getenv("BOSC_EVAL_OUT")!!).writeText(out.toString())
    }
}
