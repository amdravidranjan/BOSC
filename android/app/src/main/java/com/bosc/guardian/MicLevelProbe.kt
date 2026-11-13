package com.bosc.guardian

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The plan's Day-1 test (section 7.7): during a speakerphone call, log the microphone level
 * once per second. If it stays near the floor for the whole call, this phone silences the
 * mic during calls and the two-device setup is the demo.
 *
 * 16 kHz mono PCM16, VOICE_RECOGNITION first, then MIC. Never VOICE_CALL / VOICE_DOWNLINK.
 * Nothing is stored; only the level is computed.
 */
class MicLevelProbe(private val onLevel: (dbfs: Float, source: String) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked before the service starts
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val rate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var sourceName = "VOICE_RECOGNITION"
            var rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); sourceName = "MIC"
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { onLevel(-120f, "unavailable"); running = false; return@Thread }
            // Turn off gain control and noise suppression where the device allows, so the level is honest.
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(rec.audioSessionId)?.enabled = false }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = false }
            rec.startRecording()
            val buf = ShortArray(rate) // one second
            try {
                while (running) {
                    var read = 0
                    while (read < buf.size && running) {
                        val n = rec.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    var sum = 0.0
                    for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / read.coerceAtLeast(1))
                    val db = (20 * log10((rms / 32768.0).coerceAtLeast(1e-6))).toFloat()
                    Log.i("BoscMicTest", "source=$sourceName rms_dbfs=${"%.1f".format(db)}")
                    onLevel(db, sourceName)
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "bosc-mic-probe").apply { start() }
    }

    fun stop() { running = false; thread = null }
}
