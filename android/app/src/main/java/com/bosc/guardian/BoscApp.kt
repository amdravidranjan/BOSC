package com.bosc.guardian

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class BoscApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "BOSC is guarding", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Incoming Bosc calls", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Scam warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Warnings when a call shows scam pressure"
        })
        GuardState.init(this)
    }

    companion object {
        const val CH_GUARD = "guard"
        const val CH_ALERT = "alert"
        const val CH_CALL = "call"
    }
}

/** User settings, stored on the device only. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("bosc", Context.MODE_PRIVATE)

    var trustedName: String
        get() = p.getString("trusted_name", "") ?: ""
        set(v) = p.edit().putString("trusted_name", v).apply()
    var trustedNumber: String
        get() = p.getString("trusted_number", "") ?: ""
        set(v) = p.edit().putString("trusted_number", v).apply()
    /** BCP-47 tag for speech recognition and spoken warnings: en-IN, hi-IN or ta-IN. */
    var language: String
        get() = p.getString("language", "en-IN") ?: "en-IN"
        set(v) = p.edit().putString("language", v).apply()
    /** Day-1 test: log microphone level during calls instead of transcribing. */
    var micTestMode: Boolean
        get() = p.getBoolean("mic_test", false)
        set(v) = p.edit().putBoolean("mic_test", v).apply()
    /** Off by default: online recognition sends audio to the recogniser's server. */
    var allowOnlineAsr: Boolean
        get() = p.getBoolean("online_asr", false)
        set(v) = p.edit().putBoolean("online_asr", v).apply()
    var speakWarnings: Boolean
        get() = p.getBoolean("speak_warnings", true)
        set(v) = p.edit().putBoolean("speak_warnings", v).apply()

    /** Name other phones see for this phone in Bosc calls. */
    var myName: String
        get() = p.getString("my_name", null) ?: defaultName
        set(v) = p.edit().putString("my_name", v.trim().take(40)).apply()
    /** Scam listening mode for Bosc calls: transcribe and score the other side, on this phone. */
    var scamListening: Boolean
        get() = p.getBoolean("scam_listening", true)
        set(v) = p.edit().putBoolean("scam_listening", v).apply()

    private val defaultName = (android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        ?: android.os.Build.MODEL).take(40)

    /** Which detector scores calls. Default: the team's trained Stage 0 model plus the multilingual rules. */
    var detector: com.bosc.core.DetectorKind
        get() = runCatching { com.bosc.core.DetectorKind.valueOf(p.getString("detector", "COMBINED")!!) }.getOrDefault(com.bosc.core.DetectorKind.COMBINED)
        set(v) = p.edit().putString("detector", v.name).apply()

    /** Speech-to-text for Bosc calls: "whisper" (default, same model family as the web app) or "vosk" (faster, less accurate). */
    var asrEngine: String
        get() = p.getString("asr_engine", "whisper") ?: "whisper"
        set(v) = p.edit().putString("asr_engine", v).apply()

    /**
     * Microphone mode for the audio this phone SENDS in a Bosc call.
     * "call": VOICE_COMMUNICATION (the phone's full call processing). "clean": VOICE_RECOGNITION with only
     * echo cancelling, less processing so the other phone's speech-to-text hears clearer audio.
     */
    var callMicMode: String
        get() = p.getString("call_mic_mode", "call") ?: "call"
        set(v) = p.edit().putString("call_mic_mode", v).apply()
    /** Debug: save the audio received in Bosc calls to WAV files (own test calls only; off by default). */
    var debugCaptureAudio: Boolean
        get() = p.getBoolean("debug_capture", false)
        set(v) = p.edit().putBoolean("debug_capture", v).apply()

    val trustedLabel get() = trustedName.ifBlank { "my trusted contact" }
}
