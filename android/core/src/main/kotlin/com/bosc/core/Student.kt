package com.bosc.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.exp

/**
 * BERT uncased WordPiece, as in the student's tokenizer.json (HF tokenizers):
 * BertNormalizer(clean_text, handle_chinese_chars, strip_accents = lowercase = true),
 * BertPreTokenizer (split on whitespace, isolate punctuation), WordPiece("##", [UNK], 100 chars).
 * Parity-tested against Python on ~1,900 windows (StudentParityTest).
 */
class BertWordPiece(vocab: List<String>) {
    private val ids = HashMap<String, Int>(vocab.size * 2).apply { vocab.forEachIndexed { i, w -> put(w, i) } }
    val cls = ids.getValue("[CLS]"); val sep = ids.getValue("[SEP]"); val pad = ids.getValue("[PAD]"); private val unk = ids.getValue("[UNK]")

    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in preTokenize(normalize(text))) wordPiece(word, out)
        return out
    }

    internal fun normalize(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i); i += Character.charCount(c)
            when {
                c == 0 || c == 0xFFFD || isControl(c) -> Unit
                isBertWhitespace(c) -> sb.append(' ')
                isChinese(c) -> sb.append(' ').appendCodePoint(c).append(' ')
                else -> sb.appendCodePoint(c)
            }
        }
        val nfd = Normalizer.normalize(sb, Normalizer.Form.NFD)
        val stripped = StringBuilder(nfd.length)
        var j = 0
        while (j < nfd.length) {
            val c = nfd.codePointAt(j); j += Character.charCount(c)
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) stripped.appendCodePoint(c)
        }
        return lower(stripped.toString())
    }

    /** Rust char::to_lowercase, one code point at a time (no locale or context rules). */
    private fun lower(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i); i += Character.charCount(c)
            if (c == 0x130) sb.append("i̇") else sb.appendCodePoint(Character.toLowerCase(c))
        }
        return sb.toString()
    }

    internal fun preTokenize(s: String): List<IntArray> {
        val words = ArrayList<IntArray>()
        val cur = ArrayList<Int>()
        fun flush() { if (cur.isNotEmpty()) { words += cur.toIntArray(); cur.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i); i += Character.charCount(c)
            when {
                Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85 -> flush()
                isPunctuation(c) -> { flush(); words += intArrayOf(c) }
                else -> cur += c
            }
        }
        flush()
        return words
    }

    private fun wordPiece(word: IntArray, out: MutableList<Int>) {
        if (word.size > 100) { out += unk; return }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.size) {
            var end = word.size
            var found = -1
            while (start < end) {
                val sub = String(word, start, end - start)
                val id = ids[if (start > 0) "##$sub" else sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) { out += unk; return }
            pieces += found; start = end
        }
        out += pieces
    }

    private fun isBertWhitespace(c: Int) = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code ||
        Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(c: Int): Boolean {
        if (c == '\t'.code || c == '\n'.code || c == '\r'.code) return false
        return when (Character.getType(c)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt() -> true
            else -> false
        }
    }

    private fun isPunctuation(c: Int): Boolean {
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        return when (Character.getType(c)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChinese(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F ||
        c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F
}

/** The int8 MiniLM student: 9 sigmoid outputs (scam + 8 tactics). Window = last 4 caller utterances joined with " | ". */
class StudentModel(onnx: ByteArray, vocab: List<String>, threads: Int = 4, val maxLen: Int = 192) : AutoCloseable {
    val tokenizer = BertWordPiece(vocab)
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(onnx, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })

    /** [CLS] + newest (maxLen - 2) WordPieces + [SEP], padded: Python's truncation_side='left'. */
    fun encode(text: String): Pair<LongArray, LongArray> {
        val content = tokenizer.tokenize(text).let { if (it.size > maxLen - 2) it.subList(it.size - (maxLen - 2), it.size) else it }
        val ids = LongArray(maxLen); val mask = LongArray(maxLen)
        ids[0] = tokenizer.cls.toLong(); mask[0] = 1
        content.forEachIndexed { i, v -> ids[i + 1] = v.toLong(); mask[i + 1] = 1 }
        ids[content.size + 1] = tokenizer.sep.toLong(); mask[content.size + 1] = 1
        for (i in content.size + 2 until maxLen) ids[i] = tokenizer.pad.toLong()
        return ids to mask
    }

    fun logits(text: String): FloatArray {
        val (ids, mask) = encode(text)
        val shape = longArrayOf(1, maxLen.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { a ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { b ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(maxLen)), shape).use { c ->
                    session.run(mapOf("input_ids" to a, "attention_mask" to b, "token_type_ids" to c)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        return (r.get("logits").get().value as Array<FloatArray>)[0]
                    }
                }
            }
        }
    }

    fun probs(text: String): DoubleArray = logits(text).map { 1.0 / (1.0 + exp(-it.toDouble())) }.toDoubleArray()

    override fun close() { session.close() }
}

/** stream_model.json (bosc-stream-v1): 27 streaming features, LightGBM trees + isotonic, EMA/latch risk. Port of v2_runtime.Stream. */
class StreamModel(private val cfg: JsonObject) {
    constructor(json: String) : this(Json.parseToJsonElement(json).jsonObject)
    private val trees = cfg["trees"]!!.jsonArray.map { it.jsonObject["tree_structure"]!!.jsonObject }
    private val isoX = cfg["isotonic"]!!.jsonObject["x"]!!.jsonArray.map { it.jsonPrimitive.double }
    private val isoY = cfg["isotonic"]!!.jsonObject["y"]!!.jsonArray.map { it.jsonPrimitive.double }
    private val ema = cfg["mode"]!!.jsonPrimitive.content == "ema"
    private val alphaUp = cfg["alpha_up"]!!.jsonPrimitive.double
    private val alphaDown = cfg["alpha_down"]!!.jsonPrimitive.double
    val warn = cfg["warn"]!!.jsonPrimitive.double
    val pause = cfg["pause"]!!.jsonPrimitive.double

    private val rows = ArrayList<DoubleArray>()
    private val first = HashMap<Int, Int>()
    private var emaP = 0.0
    private val regex = HashSet<String>()
    var risk = 0.0; private set
    var latched = false; private set

    fun reset() { rows.clear(); first.clear(); emaP = 0.0; regex.clear(); risk = 0.0; latched = false }

    fun features(p: DoubleArray, tactics: Collection<String>): DoubleArray {
        val t = rows.size; rows += p
        for (k in 1..8) if (p[k] > .5 && k !in first) first[k] = t
        regex += tactics
        emaP = .5 * p[0] + .5 * emaP
        val mx = DoubleArray(9) { k -> rows.maxOf { it[k] } }
        val a = first[4]; val th = first[2]
        val ab = if (a != null && th != null && a < th) 1.0 else 0.0
        val tb = if (th != null && intArrayOf(5, 6, 7).any { k -> first[k]?.let { th < it } == true }) 1.0 else 0.0
        val firstT = if (first.isEmpty()) t else first.values.min()
        val f = ArrayList<Double>(27)
        p.forEach { f += it }
        f += mx[0]; f += rows.sumOf { it[0] } / rows.size
        for (k in 1..8) f += mx[k]
        f += (1..8).count { mx[it] > .5 }.toDouble()
        f += rows.count { it[0] > .5 }.toDouble()
        f += emaP; f += ab; f += tb; f += (t - firstT).toDouble(); f += t.toDouble(); f += regex.size.toDouble()
        return f.toDoubleArray()
    }

    private fun treeValue(root: JsonObject, f: DoubleArray): Double {
        var node = root
        while ("leaf_value" !in node) {
            require(node["decision_type"]!!.jsonPrimitive.content == "<=") { "Unsupported tree decision" }
            val go = f[node["split_feature"]!!.jsonPrimitive.int] <= node["threshold"]!!.jsonPrimitive.double
            node = node[if (go) "left_child" else "right_child"]!!.jsonObject
        }
        return node["leaf_value"]!!.jsonPrimitive.double
    }

    data class Step(val raw: Double, val calibrated: Double, val risk: Double)

    fun push(f: DoubleArray, p: DoubleArray): Step {
        val raw = 1.0 / (1.0 + exp(-trees.sumOf { treeValue(it, f) }))
        val cal = if (raw <= isoX[0]) isoY[0] else {
            var v = isoY.last()
            for (i in 1 until isoX.size) if (raw <= isoX[i]) { v = isoY[i - 1] + (raw - isoX[i - 1]) / (isoX[i] - isoX[i - 1]) * (isoY[i] - isoY[i - 1]); break }
            v
        }
        val q = if (ema) p[0] else cal
        risk += (if (q > risk) alphaUp else alphaDown) * (q - risk)
        if (risk >= pause) latched = true
        if (latched) risk = maxOf(risk, pause)
        return Step(raw, cal, risk)
    }
}

/** The student as a streaming caller-only scorer (v2_runtime.NeuralScorer). */
class StudentScorer(private val model: StudentModel, private val stream: StreamModel, private val tactics: Stage0.TacticDetector) {
    private val utts = ArrayList<String>()
    fun reset() { utts.clear(); stream.reset() }
    /** Returns (scam probability of the window, streamed risk). */
    fun push(text: String): Pair<Double, Double> {
        utts += text
        val p = model.probs(utts.takeLast(4).joinToString(" | "))
        val st = stream.push(stream.features(p, tactics.detect(text).keys), p)
        return p[0] to st.risk
    }
}

/**
 * ensemble.json (bosc-ensemble-max-cdf-v1): each model's risk becomes "the fraction of genuine dev calls whose
 * peak risk is at most this", and the ensemble takes the max. Thresholds: PAUSE where 2%, WARN where 5% of
 * genuine dev calls cross. Python: v2_android_export.py.
 */
class EnsembleTable(json: String) {
    private val o = Json.parseToJsonElement(json).jsonObject
    private fun table(name: String) = o["models"]!!.jsonObject[name]!!.jsonObject["genuine_dev_peaks"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
    val stage0 = table("stage0"); val student = table("student")
    val warn = o["warn"]!!.jsonPrimitive.double
    val pause = o["pause"]!!.jsonPrimitive.double

    /** numpy searchsorted(side='right') / n. */
    fun cdf(t: DoubleArray, x: Double): Double {
        var lo = 0; var hi = t.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (t[m] <= x) lo = m + 1 else hi = m }
        return lo.toDouble() / t.size
    }

    fun score(stage0Risk: Double, studentRisk: Double) = maxOf(cdf(stage0, stage0Risk), cdf(student, studentRisk))
}

/** Bundled student files (core resources /bosc/student/). Loaded once; ~23 MB model. */
object StudentBundle {
    private fun bytes(name: String) = StudentBundle::class.java.getResourceAsStream("/bosc/student/$name")!!.use { it.readBytes() }
    private fun text(name: String) = String(bytes(name), Charsets.UTF_8)
    val vocab: List<String> by lazy { text("vocab.txt").split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() } }
    val model: StudentModel by lazy { StudentModel(bytes("model_quantized.onnx"), vocab) }
    val streamJson: String by lazy { text("stream_model.json") }
    /** Parsed once; each call gets its own [StreamModel] state over this shared config. */
    val streamConfig: JsonObject by lazy { Json.parseToJsonElement(streamJson).jsonObject }
    val ensemble: EnsembleTable by lazy { EnsembleTable(text("ensemble.json")) }

    /** Load everything and run one inference (call off the main thread at app start). */
    fun preload() { model.probs("warm up"); streamConfig; ensemble }
}

/**
 * Stage 0 v2 + MiniLM student, combined exactly as evaluated (max of calibrated scores; 97.8% PAUSE recall at
 * 2.3% false alarms on Whisper test calls). Caller-only. PAUSE latched. Tactics/highlights come from Stage 0's patterns.
 */
class EnsembleDetector(
    private val s0: Stage0Detector = Stage0Detector(),
    private val student: StudentScorer = StudentScorer(StudentBundle.model, StreamModel(StudentBundle.streamConfig), Stage0.bundled.detector),
    private val table: EnsembleTable = StudentBundle.ensemble,
    /** Multilingual keyword rules: used ONLY for tactic labels, highlights and advice copy, never for the score. */
    private val rules: CallDetector = StreamingRiskEngine(),
) : CallDetector {
    override val name = "Stage 0 v2 + MiniLM student (ensemble)"
    /** Display scale shared with the other detectors: WARN at 0.45, PAUSE at 0.70. */
    override val warnThreshold = 0.45f
    override val pauseThreshold = 0.70f
    override var level = RiskLevel.LOW
        private set
    override val events = mutableListOf<RiskEvent>()
    private var latched = false
    private var trusted = false
    private var score = 0.0
    var lastStudentRisk = 0.0; private set

    override fun reset() { s0.reset(); student.reset(); rules.reset(); events.clear(); level = RiskLevel.LOW; latched = false; trusted = false; score = 0.0 }
    override fun markTrusted() { trusted = true; s0.markTrusted(); if (level == RiskLevel.PAUSE) level = RiskLevel.WARN }

    /** Ensemble score (0..1, CDF scale) -> display scale. */
    private fun display(e: Double): Float {
        val w = table.warn; val p = table.pause
        return when {
            e < w -> e / w * warnThreshold
            e < p -> warnThreshold + (e - w) / (p - w) * (pauseThreshold - warnThreshold)
            else -> pauseThreshold + (e - p) / (1.0 - p).coerceAtLeast(1e-6) * (1f - pauseThreshold)
        }.toFloat().coerceIn(0f, 1f)
    }

    override fun onUtterance(u: Utterance): RiskEvent {
        val e0 = s0.onUtterance(u)
        if (u.speaker == Speaker.YOU) {
            return RiskEvent(events.size, u, emptyMap(), e0.windowRisk, display(score), level, emptyList()).also { events += it }
        }
        val er = rules.onUtterance(u)
        val (p, r) = student.push(u.text)
        lastStudentRisk = r
        lastStage0Risk = s0.rawRisk
        score = table.score(lastStage0Risk, r)
        if (score >= table.pause) latched = true
        level = when { latched -> RiskLevel.PAUSE; score >= table.warn -> RiskLevel.WARN; else -> RiskLevel.LOW }
        if (trusted && level == RiskLevel.PAUSE) level = RiskLevel.WARN
        val tactics = (e0.tactics.keys + er.tactics.keys).associateWith { t -> maxOf(e0.tactics[t] ?: 0f, er.tactics[t] ?: 0f) }
        return RiskEvent(events.size, u, tactics, maxOf(e0.windowRisk, p.toFloat()), display(score), level, (e0.spans + er.spans).sortedBy { it.start }).also { events += it }
    }

    /** Raw ensemble score of the last caller turn (for parity tests). */
    val lastScore get() = score
    var lastStage0Risk = 0.0; private set

    override fun topTactics(min: Float): List<Tactic> = (s0.topTactics(min) + rules.topTactics(min)).distinct()
}
