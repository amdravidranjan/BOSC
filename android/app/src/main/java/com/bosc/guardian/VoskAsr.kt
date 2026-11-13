package com.bosc.guardian

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Offline streaming speech recognition (Vosk, Indian English small model, ~36 MB in the APK).
 *
 * Unlike Android's SpeechRecognizer it accepts raw PCM, so it can transcribe the other side
 * of a Bosc call straight from the network stream. Nothing is sent anywhere.
 */
object VoskAsr {
    private const val TAG = "BoscVosk"

    @Volatile private var model: Model? = null
    @Volatile private var loading = false
    private val _status = MutableStateFlow("Loading speech model")
    val status: StateFlow<String> = _status.asStateFlow()
    val ready get() = model != null

    fun load(context: Context) {
        if (model != null || loading) return
        loading = true
        StorageService.unpack(context.applicationContext, "model-en-in", "model",
            { m -> model = m; loading = false; _status.value = "ready"; Log.i(TAG, "model ready") },
            { e -> loading = false; _status.value = "Speech model failed to load: ${e.message}"; Log.e(TAG, "unpack failed", e) })
    }

    /** Benchmark: transcribe a WAV file with Vosk. Returns the transcript and the time taken. */
    fun transcribeFile(file: java.io.File): Pair<String, Long> {
        val m = model ?: return "" to 0L
        val pcm = Wav.to16kPcmBytes(Wav.read16k(file))
        val rec = Recognizer(m, 16000f)
        val parts = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(3200, pcm.size - i)
            if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) JSONObject(rec.result).optString("text").trim().takeIf { it.isNotEmpty() }?.let { parts += it }
            i += n
        }
        JSONObject(rec.finalResult).optString("text").trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        rec.close()
        return parts.joinToString(" ") to (System.currentTimeMillis() - t0)
    }

    /** One continuous audio stream (16 kHz mono PCM16) → utterances. Feed from any thread. */
    class Stream(
        private val label: String,
        private val onPartial: (String) -> Unit,
        private val onFinal: (String) -> Unit,
    ) : AsrStream {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var running = true
        private val thread = Thread({ loop() }, "bosc-asr-$label").apply { start() }

        override fun feed(pcm: ByteArray, len: Int) { if (running) queue.offer(pcm.copyOf(len)) }

        override fun close() { running = false; thread.interrupt() }

        private fun loop() {
            var m = model
            while (m == null && running) { Thread.sleep(200); m = model }
            if (m == null) return
            val rec = Recognizer(m, 16000f)
            var lastPartial = ""
            try {
                while (running) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (rec.acceptWaveForm(chunk, chunk.size)) {
                        val text = JSONObject(rec.result).optString("text").trim()
                        lastPartial = ""
                        onPartial("")
                        if (text.isNotEmpty()) onFinal(text)
                    } else {
                        val p = JSONObject(rec.partialResult).optString("partial").trim()
                        if (p != lastPartial) { lastPartial = p; onPartial(p) }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "$label stream failed", e)
            } finally {
                runCatching { JSONObject(rec.finalResult).optString("text").trim() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let(onFinal)
                rec.close()
            }
        }
    }
}
