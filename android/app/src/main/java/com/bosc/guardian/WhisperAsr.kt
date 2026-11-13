package com.bosc.guardian

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One continuous 16 kHz mono PCM16 audio stream turned into utterances. */
interface AsrStream {
    fun feed(pcm: ByteArray, len: Int)
    fun close()
}

/**
 * Whisper on the phone (sherpa-onnx), the same model family the web app and the backend's
 * ASR-noise pipeline use (tiny.en), so the detector sees the kind of transcripts it was trained on.
 *
 * Silero VAD cuts each audio stream into utterances (~0.4 s of silence ends one, 8 s max);
 * each utterance is decoded by Whisper on a single shared worker thread. Nothing leaves the phone.
 *
 * Model lookup: <external files>/models/whisper-<name>/ if present (push base.en there with adb to
 * try it without rebuilding), otherwise the tiny.en model bundled in assets/whisper-tiny.en/.
 */
object WhisperAsr {
    private const val TAG = "BoscWhisper"
    private val JUNK = Regex("""^\s*(\[.*]|\(.*\)|\*.*\*|thank you\.?|thanks for watching!?|you|bye\.?|\.+)\s*$""", RegexOption.IGNORE_CASE)

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loading = false
    private lateinit var appContext: Context
    private var vadFromAssets = true
    private var vadPath = "vad/silero_vad.onnx"
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "bosc-whisper") }

    private val _status = MutableStateFlow("Loading Whisper")
    val status: StateFlow<String> = _status.asStateFlow()
    @Volatile var modelName = ""
        private set
    val ready get() = recognizer != null

    /** Recent decode speed: seconds of compute per second of audio (lower is faster; < 1 keeps up live). */
    @Volatile var lastRtf = 0f
        private set

    fun load(context: Context) {
        appContext = context.applicationContext
        if (recognizer != null || loading) return
        loading = true
        decoder.execute {
            try {
                val t0 = System.currentTimeMillis()
                val ext = File(appContext.getExternalFilesDir(null), "models")
                val pushed = listOf("base.en", "tiny.en").map { it to File(ext, "whisper-$it") }
                    .firstOrNull { (n, d) -> File(d, "$n-encoder.int8.onnx").exists() && File(d, "$n-decoder.int8.onnx").exists() }
                val (name, dir, assets) = if (pushed != null) Triple(pushed.first, pushed.second.absolutePath, null as AssetManager?)
                    else Triple("tiny.en", "whisper-tiny.en", appContext.assets)
                val whisper = OfflineWhisperModelConfig(
                    encoder = "$dir/$name-encoder.int8.onnx",
                    decoder = "$dir/$name-decoder.int8.onnx",
                    language = "en",
                    task = "transcribe",
                    tailPaddings = -1,
                )
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(whisper = whisper, tokens = "$dir/$name-tokens.txt", numThreads = 4, debug = false, provider = "cpu", modelType = "whisper"),
                )
                recognizer = OfflineRecognizer(assets, config)
                modelName = name
                _status.value = "ready"
                Log.i(TAG, "loaded whisper $name from ${if (assets == null) dir else "assets"} in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Throwable) {
                _status.value = "Whisper failed to load: ${e.message}"
                Log.e(TAG, "load failed", e)
            } finally { loading = false }
        }
    }

    /** Decode one utterance (16 kHz float samples). Runs on the shared Whisper thread; returns "" for junk. */
    /** Bring each utterance to a steady loudness (RMS about -20 dBFS, gain capped at 12x, no clipping). */
    fun normalise(samples: FloatArray): FloatArray {
        var sum = 0.0; var peak = 0f
        for (v in samples) { sum += v * v; peak = maxOf(peak, kotlin.math.abs(v)) }
        val rms = kotlin.math.sqrt(sum / samples.size.coerceAtLeast(1)).toFloat()
        if (rms < 1e-5f) return samples
        val gain = minOf(0.1f / rms, 12f, 0.95f / peak.coerceAtLeast(1e-5f))
        return if (gain in 0.9f..1.1f) samples else FloatArray(samples.size) { samples[it] * gain }
    }

    fun transcribe(raw: FloatArray): String {
        val rec = recognizer ?: return ""
        val samples = normalise(raw)
        val t0 = System.nanoTime()
        val stream = rec.createStream()
        val text = try {
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally { stream.release() }
        val ms = (System.nanoTime() - t0) / 1e6
        val secs = samples.size / 16000.0
        lastRtf = (ms / 1000.0 / secs).toFloat()
        Log.i(TAG, "decoded %.1fs of audio in %.0f ms (RTF %.2f): %s".format(secs, ms, lastRtf, text))
        return if (JUNK.matches(text)) "" else text
    }

    private fun newVad(): Vad = Vad(
        if (vadFromAssets) appContext.assets else null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath, threshold = 0.5f, minSilenceDuration = 0.4f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 8f,
            ),
            sampleRate = 16000, numThreads = 1, provider = "cpu",
        ),
    )

    /** VAD + Whisper for one live audio stream. */
    class Stream(
        private val label: String,
        private val onSpeaking: (Boolean) -> Unit,
        private val onFinal: (String) -> Unit,
    ) : AsrStream {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var running = true
        private val thread = Thread({ loop() }, "bosc-vad-$label").apply { start() }

        override fun feed(pcm: ByteArray, len: Int) { if (running) queue.offer(pcm.copyOf(len)) }
        override fun close() { running = false; thread.interrupt() }

        private fun loop() {
            val vad = runCatching { newVad() }.getOrElse { Log.e(TAG, "VAD failed", it); return }
            val window = FloatArray(512)
            var filled = 0
            var speaking = false
            // Automatic gain before the VAD: faint call audio (e.g. a phone held away from the mouth) must still
            // be detected as speech. Follows the recent speech level (fast attack, ~3 s release), up to 16x.
            var level = 0.02f
            fun drain() {
                while (!vad.empty()) {
                    val seg = vad.front().samples
                    vad.pop()
                    if (seg.size < 16000 * 0.3) continue
                    decoder.execute { transcribe(seg).takeIf { it.isNotBlank() }?.let(onFinal) }
                }
            }
            try {
                while (running) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    var i = 0
                    while (i + 1 < chunk.size) {
                        val s = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort()
                        window[filled++] = s / 32768f
                        i += 2
                        if (filled == window.size) {
                            var sum = 0f
                            for (v in window) sum += v * v
                            val rms = kotlin.math.sqrt(sum / window.size)
                            level = if (rms > level) rms else level * 0.99f + rms * 0.01f
                            val gain = (0.1f / level.coerceAtLeast(0.006f)).coerceIn(1f, 16f)
                            vad.acceptWaveform(FloatArray(window.size) { (window[it] * gain).coerceIn(-1f, 1f) })
                            filled = 0
                            val now = vad.isSpeechDetected()
                            if (now != speaking) { speaking = now; onSpeaking(now) }
                            drain()
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                runCatching { vad.flush(); drain() }
                if (speaking) onSpeaking(false)
                vad.release()
            }
        }
    }

    /**
     * Offline benchmark on a WAV file (PCM16 mono, any rate; resampled to 16 kHz). Uses the same VAD
     * segmentation as live calls. Returns the joined transcript and the decode time.
     */
    fun transcribeFile(file: File): Pair<String, Long> {
        val pcm16k = Wav.read16k(file)
        val vad = newVad()
        val parts = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        var i = 0
        while (i + 512 <= pcm16k.size) { vad.acceptWaveform(pcm16k.copyOfRange(i, i + 512)); i += 512
            while (!vad.empty()) { val seg = vad.front().samples; vad.pop(); transcribe(seg).takeIf { it.isNotBlank() }?.let { parts += it } } }
        vad.flush()
        while (!vad.empty()) { val seg = vad.front().samples; vad.pop(); transcribe(seg).takeIf { it.isNotBlank() }?.let { parts += it } }
        vad.release()
        return parts.joinToString(" ") to (System.currentTimeMillis() - t0)
    }
}

/** Minimal WAV reader for the benchmark: PCM16, mono or stereo (first channel), linear resample to 16 kHz. */
object Wav {
    fun read16k(file: File): FloatArray {
        val b = file.readBytes()
        fun le32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        fun le16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        var p = 12; var rate = 16000; var ch = 1; var dataOff = -1; var dataLen = 0
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = le32(p + 4)
            if (id == "fmt ") { ch = le16(p + 10); rate = le32(p + 12) }
            if (id == "data") { dataOff = p + 8; dataLen = minOf(len, b.size - p - 8); break }
            p += 8 + len + (len and 1)
        }
        require(dataOff > 0) { "no data chunk" }
        val n = dataLen / (2 * ch)
        val src = FloatArray(n) { i -> le16(dataOff + i * 2 * ch).toShort() / 32768f }
        if (rate == 16000) return src
        val outN = (n.toLong() * 16000 / rate).toInt()
        return FloatArray(outN) { i ->
            val x = i.toDouble() * rate / 16000; val j = x.toInt(); val f = (x - j).toFloat()
            if (j + 1 < n) src[j] * (1 - f) + src[j + 1] * f else src[n - 1]
        }
    }

    fun to16kPcmBytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> val s = (v.coerceIn(-1f, 1f) * 32767).toInt(); out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
        return out
    }
}
