package com.bosc.guardian

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.bosc.core.RiskLevel
import com.bosc.core.Tactic
import java.util.Locale

/** Calm, short, plain-language copy. Hindi and Tamil should be checked by a native speaker before the demo. */
object Warnings {

    /** Team copy rules (frontend README §9): calm, one idea, at most 15 words. Same order as the web app's advice(). */
    fun oneLine(tactics: List<Tactic>): String {
        val t = tactics.toSet()
        return when {
            Tactic.STAY_ON_LINE in t || (Tactic.AUTHORITY_CLAIM in t && Tactic.THREAT in t) ->
                "Real police never arrest anyone over a phone or video call. They never ask for money."
            Tactic.REMOTE_ACCESS in t -> "Don't install any app a caller asks for. Screen sharing lets them see your OTPs."
            Tactic.CREDENTIAL_REQUEST in t -> "Never share an OTP, PIN or CVV with a caller. Banks never ask for it."
            Tactic.PAYMENT_INSTRUCTION in t -> "Don't send money to an account or UPI ID a caller gives you."
            Tactic.SECRECY_ISOLATION in t -> "Scammers ask you to keep it secret. Talk to someone you trust first."
            Tactic.THREAT in t || Tactic.URGENCY in t -> "Pressure to act fast is a warning sign. You can hang up and call an official number."
            else -> "Don't share OTPs or send money because of this call."
        }
    }

    fun spokenPause(lang: String, trusted: String): String = when (lang) {
        "hi-IN" -> "रुकिए। इस कॉल में धोखाधड़ी के संकेत हैं। असली पुलिस, बैंक या अधिकारी फोन पर कभी पैसे या ओटीपी नहीं मांगते। फोन काटिए और $trusted को कॉल कीजिए।"
        "ta-IN" -> "நில்லுங்கள். இந்த அழைப்பில் மோசடி அறிகுறிகள் உள்ளன. உண்மையான காவல்துறை, வங்கி அல்லது அதிகாரிகள் தொலைபேசியில் பணமோ OTP யோ கேட்க மாட்டார்கள். அழைப்பைத் துண்டித்து $trusted அவர்களை அழையுங்கள்."
        else -> "Careful. This call has signs of a scam. Do not share an O T P or send money. You can hang up and call $trusted."
    }

    fun spokenWarn(lang: String): String = when (lang) {
        "hi-IN" -> "सावधान। यह कॉल संदिग्ध लग रही है। कोई पैसे या ओटीपी न दें।"
        "ta-IN" -> "கவனம். இந்த அழைப்பு சந்தேகத்திற்குரியதாக உள்ளது. பணமோ OTP யோ கொடுக்காதீர்கள்."
        else -> "Be careful with this call."
    }
}

/** Text-to-speech for spoken warnings (matters for seniors) and for the caller phone in the demo. Mutes ASR while speaking. */
object Voice {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { GuardState.muteUntil = Long.MAX_VALUE }
                override fun onDone(id: String?) = finished(id, 1500)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = finished(id, 500)
            })
        }
    }

    private fun finished(id: String?, muteMs: Long) {
        GuardState.muteUntil = System.currentTimeMillis() + muteMs
        id?.let { callbacks.remove(it) }?.let { main.post(it) }
    }

    fun speak(text: String, langTag: String, pitch: Float = 1f, rate: Float = 1f, onDone: (() -> Unit)? = null) {
        val t = tts
        if (t == null || !ready) {
            onDone?.let { main.postDelayed(it, (text.length * 60L).coerceAtLeast(1500)) }
            return
        }
        t.language = Locale.forLanguageTag(langTag)
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        val id = "bosc-${System.nanoTime()}"
        onDone?.let { callbacks[id] = it }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { callbacks.clear(); tts?.stop() }
}

/**
 * Haptics and tones, as in the web app: WARN = two short buzzes and a rising two-note tone,
 * PAUSE = three long buzzes and a falling three-note tone. Different enough to tell apart in a pocket.
 */
object Cues {
    fun buzz(context: Context, level: RiskLevel) {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31)
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
        val pattern = if (level == RiskLevel.PAUSE) longArrayOf(0, 600, 200, 600, 200, 600) else longArrayOf(0, 200, 120, 200)
        runCatching { v?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** Returns the tone length in ms (the caller of this mutes this phone's mic-side transcription for that long). */
    fun tone(level: RiskLevel): Long {
        val notes = if (level == RiskLevel.PAUSE) doubleArrayOf(880.0, 698.46, 587.33) else doubleArrayOf(659.25, 880.0)
        val step = if (level == RiskLevel.PAUSE) 0.3 else 0.2
        val rate = 22050
        val total = (notes.size * step * rate).toInt()
        val pcm = ShortArray(total)
        notes.forEachIndexed { i, f ->
            val n0 = (i * step * rate).toInt(); val len = (step * 0.9 * rate).toInt()
            for (k in 0 until len) {
                val env = minOf(1.0, k / (0.02 * rate)) * Math.exp(-4.0 * k / len)
                pcm[n0 + k] = (0.2 * env * Math.sin(2 * Math.PI * f * k / rate) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        Thread({
            runCatching {
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(total * 2).build()
                track.write(pcm, 0, total); track.play()
                Thread.sleep((notes.size * step * 1000).toLong() + 200)
                track.release()
            }
        }, "bosc-tone").start()
        return (notes.size * step * 1000).toLong()
    }
}

object Alerts {
    private const val ALERT_ID = 2001

    fun onLevelChange(context: Context, from: RiskLevel, to: RiskLevel, s: SessionState) {
        if (to.ordinal <= from.ordinal) return // only escalate; the meter shows de-escalation
        val prefs = Prefs(context)
        val line = Warnings.oneLine(s.topTactics)
        Cues.buzz(context, to)
        val toneMs = if (prefs.playTone) Cues.tone(to) else 0L
        if (toneMs > 0) GuardState.muteUntil = maxOf(GuardState.muteUntil, System.currentTimeMillis() + toneMs + 600)
        fun say(text: String) {
            if (!prefs.speakWarnings) return
            if (toneMs > 0) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Voice.speak(text, prefs.language) }, toneMs + 200)
            else Voice.speak(text, prefs.language)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, BoscApp.CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(openApp)
            .setAutoCancel(true)

        if (to == RiskLevel.PAUSE) {
            val pause = PendingIntent.getActivity(context, 1, Intent(context, PauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            b.setContentTitle("Stop. This looks like a scam call.")
                .setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$line\nDo not pay or share any code. Hang up and call ${prefs.trustedLabel}."))
                .setFullScreenIntent(pause, true)
                .setContentIntent(pause)
            say(Warnings.spokenPause(prefs.language, prefs.trustedLabel))
        } else {
            b.setContentTitle("Careful: this call shows scam signs").setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            say(Warnings.spokenWarn(prefs.language))
        }
        runCatching { nm.notify(ALERT_ID, b.build()) }
    }

    fun cancelAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }
}
