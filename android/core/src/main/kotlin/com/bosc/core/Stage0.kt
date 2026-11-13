package com.bosc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Stage 0 detector from the team repo (github.com/amdravidranjan/BOSC, backend/bosc_core.py),
 * ported line by line. It must give the same numbers as the Python reference and
 * frontend/js/bosc-core.js; Stage0ParityTest checks that against backend/parity_cases.json.
 *
 * Per caller utterance:
 *   window (last W caller utterances, " "-joined) -> tokens -> TF-IDF (sublinear, l2) -> LR p
 *   raw  = (1 - tw) * p + tw * min(1, distinct pressure tactics seen / 4)
 *   risk = rise-fast / fall-slow smoothing of raw -> SAFE / WARN / PAUSE
 *
 * Model files live in resources/bosc/stage0/ (model.json, tactics.json) so a retrained
 * model from the Backend team drops in by replacing those two files.
 */
object Stage0 {
    private val ACRO = Regex("""\b([a-z])\.([a-z])\.([a-z])\.?""")
    private val NUM = Regex("""[0-9][0-9,.]*""")
    private val TOK = Regex("""[a-z]{2,}""")

    fun tokenize(text: String): List<String> {
        var t = text.lowercase().replace("’", "").replace("'", "")
        t = ACRO.replace(t, "$1$2$3")
        t = NUM.replace(t, " num ")
        return TOK.findAll(t).map { it.value }.toList()
    }

    fun analyzer(text: String): List<String> {
        val toks = tokenize(text)
        return toks + (0 until toks.size - 1).map { toks[it] + " " + toks[it + 1] }
    }

    class Model(json: JsonObject) {
        private val vocab: Map<String, Int> = json["vocab"]!!.jsonArray.mapIndexed { i, e -> e.jsonPrimitive.content to i }.toMap()
        private val idf = json["idf"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        private val coef = json["coef"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        private val intercept = json["intercept"]!!.jsonPrimitive.double

        fun proba(text: String): Double {
            val counts = LinkedHashMap<Int, Int>()
            for (g in analyzer(text)) vocab[g]?.let { counts[it] = (counts[it] ?: 0) + 1 }
            val vals = counts.mapValues { (j, c) -> (1.0 + ln(c.toDouble())) * idf[j] }
            val norm = sqrt(vals.values.sumOf { it * it }).let { if (it == 0.0) 1.0 else it }
            val z = intercept + vals.entries.sumOf { (j, v) -> coef[j] * v / norm }
            return 1.0 / (1.0 + exp(-z))
        }
    }

    class TacticDetector(json: JsonObject) {
        val tactics: Map<String, List<Regex>> = json["tactics"]!!.jsonObject.mapValues { (_, t) ->
            t.jsonObject["patterns"]!!.jsonArray.map { Regex(it.jsonPrimitive.content, RegexOption.IGNORE_CASE) }
        }

        /** Tactic name -> character spans, for every tactic that matches. */
        fun detect(text: String): Map<String, List<IntRange>> {
            val found = LinkedHashMap<String, List<IntRange>>()
            for ((name, pats) in tactics) {
                val spans = pats.flatMap { p -> p.findAll(text).filter { it.range.last + 1 > it.range.first }.map { it.range }.toList() }
                if (spans.isNotEmpty()) found[name] = spans
            }
            return found
        }

        companion object {
            /** Tactics that count towards pressure (authority_claim alone does not). */
            val PRESSURE = setOf("threat", "urgency", "secrecy_isolation", "credential_request", "remote_access", "payment_instruction", "stay_on_line")
        }
    }

    data class Config(val window: Int, val tacticWeight: Double, val alphaUp: Double, val alphaDown: Double, val warn: Double, val pause: Double)

    data class Result(val p: Double, val raw: Double, val risk: Double, val tactics: Map<String, List<IntRange>>, val level: String)

    /** Exact port of bosc_core.StreamingScorer. Caller utterances only. */
    class StreamingScorer(private val model: Model, private val detector: TacticDetector?, val cfg: Config) {
        private val utts = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        var risk = 0.0
            private set

        fun reset() { utts.clear(); seen.clear(); risk = 0.0 }

        fun level(r: Double = risk) = if (r >= cfg.pause) "PAUSE" else if (r >= cfg.warn) "WARN" else "SAFE"

        fun push(text: String): Result {
            utts += text
            val win = utts.takeLast(cfg.window).joinToString(" ")
            val p = model.proba(win)
            val tactics = detector?.detect(text) ?: emptyMap()
            seen += tactics.keys
            val n = seen.count { it in TacticDetector.PRESSURE }
            val raw = (1 - cfg.tacticWeight) * p + cfg.tacticWeight * minOf(1.0, n / 4.0)
            val a = if (raw > risk) cfg.alphaUp else cfg.alphaDown
            risk += a * (raw - risk)
            return Result(p, raw, risk, tactics, level())
        }
    }

    data class Bundle(val model: Model, val detector: TacticDetector, val config: Config, val modelHash: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(modelJson: String, tacticsJson: String): Bundle {
        val mj = json.parseToJsonElement(modelJson).jsonObject
        val s = mj["scorer"]!!.jsonObject
        val cfg = Config(
            window = s["window"]!!.jsonPrimitive.int,
            tacticWeight = s["tactic_weight"]!!.jsonPrimitive.double,
            alphaUp = s["alpha_up"]!!.jsonPrimitive.double,
            alphaDown = s["alpha_down"]!!.jsonPrimitive.double,
            warn = s["warn"]!!.jsonPrimitive.double,
            pause = s["pause"]!!.jsonPrimitive.double,
        )
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(modelJson.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        return Bundle(Model(mj), TacticDetector(json.parseToJsonElement(tacticsJson).jsonObject), cfg, hash)
    }

    /** Loads the bundled model from resources/bosc/stage0/. Cached: the model is ~180 KB of JSON. */
    val bundled: Bundle by lazy {
        fun res(name: String) = Stage0::class.java.getResourceAsStream("/bosc/stage0/$name")!!.bufferedReader().use { it.readText() }
        load(res("model.json"), res("tactics.json"))
    }
}

/**
 * The Stage 0 scorer as a [CallDetector]: caller-only input (the model was trained on caller
 * utterances only), PAUSE latched for the rest of the call, and the "I trust this caller" cap.
 */
class Stage0Detector(private val bundle: Stage0.Bundle = Stage0.bundled) : CallDetector {
    private val scorer = Stage0.StreamingScorer(bundle.model, bundle.detector, bundle.config)
    private var latched = false
    private var trusted = false
    private var lastP = 0f
    private val tacticById = Tactic.entries.associateBy { it.id }

    override val name = "B. TF-IDF + LR (team Stage 0, model ${bundle.modelHash})"
    override val warnThreshold get() = bundle.config.warn.toFloat()
    override val pauseThreshold get() = bundle.config.pause.toFloat()
    override var level = RiskLevel.LOW
        private set
    override val events = mutableListOf<RiskEvent>()

    override fun reset() { scorer.reset(); events.clear(); latched = false; trusted = false; lastP = 0f; level = RiskLevel.LOW }

    override fun markTrusted() { trusted = true; if (level == RiskLevel.PAUSE) level = RiskLevel.WARN }

    override fun onUtterance(u: Utterance): RiskEvent {
        val idx = events.size
        if (u.speaker == Speaker.YOU) {
            // The receiver's own words are shown but never scored (caller-only model).
            return RiskEvent(idx, u, emptyMap(), lastP, scorer.risk.toFloat(), level, emptyList()).also { events += it }
        }
        val r = scorer.push(u.text)
        lastP = r.p.toFloat()
        if (r.level == "PAUSE") latched = true
        level = when {
            latched -> RiskLevel.PAUSE
            r.level == "WARN" -> RiskLevel.WARN
            else -> RiskLevel.LOW
        }
        if (trusted && level == RiskLevel.PAUSE) level = RiskLevel.WARN
        val tactics = r.tactics.keys.mapNotNull { tacticById[it] }.associateWith { 1f }
        val spans = r.tactics.flatMap { (name, ranges) -> tacticById[name]?.let { t -> ranges.map { Span(it.first, it.last + 1, t) } } ?: emptyList() }
        return RiskEvent(idx, u, tactics, r.p.toFloat(), r.risk.toFloat(), level, spans).also { events += it }
    }

    override fun topTactics(min: Float): List<Tactic> = Tactic.entries.filter { it.id in scorer.seen }
}
