package com.bosc.guardian

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous speech-to-text using Android's recogniser, on-device when available.
 *
 * This is the "easiest" ASR row in the plan's recogniser table. whisper.cpp (NDK) is the
 * planned upgrade; it can replace this class behind the same callbacks.
 *
 * Privacy: by default only the on-device recogniser is used. If it is unavailable for the
 * chosen language, BOSC reports that instead of silently sending audio to a server, unless
 * the user has turned on "Allow online speech recognition".
 */
class SpeechAsr(
    private val context: Context,
    private val languageTag: String,
    private val allowOnline: Boolean,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var onDevice = false
    private var consecutiveErrors = 0

    fun start() = main.post {
        if (running) return@post
        running = true
        if (!create()) { running = false; return@post }
        listen()
    }

    fun stop() = main.post {
        running = false
        recognizer?.run { cancel(); destroy() }
        recognizer = null
    }

    private fun create(): Boolean {
        recognizer?.destroy()
        val canOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = when {
            canOnDevice -> { onDevice = true; SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            allowOnline && SpeechRecognizer.isRecognitionAvailable(context) -> { onDevice = false; SpeechRecognizer.createSpeechRecognizer(context) }
            else -> {
                onStatus("On-device speech recognition is not available on this phone. Enable online recognition in settings, or use simulation mode.")
                return false
            }
        }
        recognizer!!.setRecognitionListener(listener)
        onStatus(if (onDevice) "Listening (on-device, $languageTag)" else "Listening (online recogniser, $languageTag)")
        return true
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
    }

    private fun listen() {
        if (!running) return
        try { recognizer?.startListening(intent()) } catch (e: Exception) { Log.w(TAG, "startListening failed", e); restart(500) }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        main.postDelayed({ if (running) listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onFinal)
            restart(50)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { onStatus("Microphone permission missing"); running = false }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    onStatus("Offline speech pack for $languageTag is missing. Download it in system settings (Speech recognition / Offline languages).")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { recognizer?.triggerModelDownload(intent()) }
                    running = false
                }
                else -> {
                    consecutiveErrors++
                    Log.w(TAG, "ASR error $error (x$consecutiveErrors)")
                    if (consecutiveErrors >= 3) {
                        onStatus("Speech recogniser error $error. Retrying. If the phone blocks the mic during calls, use the two-phone speaker demo or the laptop Plan B.")
                        create()
                    }
                    restart(if (consecutiveErrors > 5) 2000 else 400)
                }
            }
        }
    }

    companion object { private const val TAG = "BoscAsr" }
}
