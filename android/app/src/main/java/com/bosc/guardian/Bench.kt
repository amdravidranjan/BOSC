package com.bosc.guardian

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Speech-to-text benchmark on the phone (developer tool).
 *
 * Put WAV files in <external files>/bench/ with a reference transcript beside each one
 * (name.wav + name.txt), then run:
 *   adb shell am start -n com.bosc.guardian/.MainActivity --ez bench true
 * Each engine transcribes each file; word error rate (WER) and real-time factor go to logcat
 * (tag BoscBench) and to bench/results.txt.
 */
object Bench {
    private const val TAG = "BoscBench"

    fun runAsync(context: Context) {
        val app = context.applicationContext
        Thread({
            try { run(app) } catch (e: Throwable) { Log.e(TAG, "bench failed", e) }
        }, "bosc-bench").start()
    }

    private fun run(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "bench")
        val wavs = dir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()
        if (wavs.isEmpty()) { Log.w(TAG, "no wav files in $dir"); return }
        WhisperAsr.load(context); VoskAsr.load(context)
        while (!WhisperAsr.ready || !VoskAsr.ready) Thread.sleep(200)

        val out = StringBuilder("device=${android.os.Build.MODEL} whisper=${WhisperAsr.modelName}\n")
        val totals = mutableMapOf<String, IntArray>() // engine -> [errors, refWords]
        for (wav in wavs) {
            val ref = File(dir, wav.nameWithoutExtension + ".txt").takeIf { it.exists() }?.readText().orEmpty()
            val audioSecs = Wav.read16k(wav).size / 16000.0
            for ((engine, fn) in listOf<Pair<String, (File) -> Pair<String, Long>>>("whisper" to WhisperAsr::transcribeFile, "vosk" to VoskAsr::transcribeFile)) {
                val (hyp, ms) = fn(wav)
                val (errs, n) = wordErrors(ref, hyp)
                totals.getOrPut(engine) { IntArray(2) }.let { it[0] += errs; it[1] += n }
                val line = "%s %-8s WER %5.1f%% (%d/%d)  RTF %.2f  (%.1fs audio, %d ms)\n    hyp: %s".format(
                    wav.name, engine, 100.0 * errs / n.coerceAtLeast(1), errs, n, ms / 1000.0 / audioSecs, audioSecs, ms, hyp)
                Log.i(TAG, line); out.appendLine(line)
            }
        }
        for ((engine, t) in totals) {
            val line = "TOTAL %-8s WER %.1f%% (%d errors / %d words)".format(engine, 100.0 * t[0] / t[1].coerceAtLeast(1), t[0], t[1])
            Log.i(TAG, line); out.appendLine(line)
        }
        File(dir, "results.txt").writeText(out.toString())
        Log.i(TAG, "BENCH DONE")
    }

    /**
     * Detector speed on this phone: every demo call through each detector, ms per caller utterance.
     *   adb shell am start -n com.bosc.guardian/.MainActivity --ez detbench true   (logcat tag BoscBench)
     */
    fun detectorAsync() = Thread({
        try {
            val t0 = System.nanoTime(); com.bosc.core.StudentBundle.model.probs("warm up")
            Log.i(TAG, "student load+first inference ${(System.nanoTime() - t0) / 1_000_000} ms")
            for (kind in com.bosc.core.DetectorKind.entries) {
                val ms = ArrayList<Double>(); var paused = 0; var scams = 0; var genuinePaused = 0
                for (c in com.bosc.core.DemoCalls.all) {
                    val d = kind.create(); var p = false
                    c.turns.filter { it.first == com.bosc.core.Speaker.CALLER }.forEachIndexed { i, (_, text) ->
                        val a = System.nanoTime()
                        val e = d.onUtterance(com.bosc.core.Utterance(i * 3000L, com.bosc.core.Speaker.CALLER, text))
                        ms += (System.nanoTime() - a) / 1e6
                        if (e.level == com.bosc.core.RiskLevel.PAUSE) p = true
                    }
                    if (c.isScam) { scams++; if (p) paused++ } else if (p) genuinePaused++
                }
                ms.sort()
                Log.i(TAG, "DETECTOR %-9s median %.1f ms  p95 %.1f ms  max %.1f ms  | demo calls: %d/%d scams paused, %d genuine paused".format(
                    kind.name, ms[ms.size / 2], ms[(ms.size * 95) / 100], ms.last(), paused, scams, genuinePaused))
            }
            Log.i(TAG, "DETBENCH DONE")
        } catch (e: Throwable) { Log.e(TAG, "detector bench failed", e) }
    }, "bosc-detbench").start()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Word-level edit distance (substitutions + insertions + deletions) and reference length. */
    fun wordErrors(ref: String, hyp: String): Pair<Int, Int> {
        val r = norm(ref); val h = norm(hyp)
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
        }
        return d[r.size][h.size] to r.size
    }
}
