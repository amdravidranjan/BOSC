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

    fun oneLine(tactics: List<Tactic>): String {
        val t = tactics.toSet()
        return when {
            Tactic.REMOTE_ACCESS in t -> "Do not install any app a caller asks for. It lets them control your phone."
            Tactic.AUTHORITY_CLAIM in t && Tactic.THREAT in t -> "Real police, CBI or customs never arrest anyone on a phone call or ask for money."
            Tactic.CREDENTIAL_REQUEST in t -> "No bank or official will ever ask for your OTP, PIN or card details."
            Tactic.SECRECY_ISOLATION in t -> "Scammers tell you to keep it secret. Talk to your family now."
            Tactic.PAYMENT_INSTRUCTION in t -> "Do not send money while someone on a call is telling you to."
            Tactic.STAY_ON_LINE in t -> "You can hang up. A real official will not mind."
            Tactic.THREAT in t -> "Threats and deadlines on a call are a common scam trick. Slow down."
            else -> "This call shows signs of pressure. Take your time."
        }
    }

    fun spokenPause(lang: String, trusted: String): String = when (lang) {
        "hi-IN" -> "रुकिए। इस कॉल में धोखाधड़ी के संकेत हैं। असली पुलिस, बैंक या अधिकारी फोन पर कभी पैसे या ओटीपी नहीं मांगते। फोन काटिए और $trusted को कॉल कीजिए।"
        "ta-IN" -> "நில்லுங்கள். இந்த அழைப்பில் மோசடி அறிகுறிகள் உள்ளன. உண்மையான காவல்துறை, வங்கி அல்லது அதிகாரிகள் தொலைபேசியில் பணமோ OTP யோ கேட்க மாட்டார்கள். அழைப்பைத் துண்டித்து $trusted அவர்களை அழையுங்கள்."
        else -> "Please wait. This call shows signs of a scam. Real police, banks and officials never ask you to pay or share an OTP on a call. Hang up and call $trusted."
    }

    fun spokenWarn(lang: String): String = when (lang) {
        "hi-IN" -> "सावधान। यह कॉल संदिग्ध लग रही है। कोई पैसे या ओटीपी न दें।"
        "ta-IN" -> "கவனம். இந்த அழைப்பு சந்தேகத்திற்குரியதாக உள்ளது. பணமோ OTP யோ கொடுக்காதீர்கள்."
        else -> "Careful. This call looks suspicious. Do not share money or an OTP."
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

object Alerts {
    private const val ALERT_ID = 2001

    fun onLevelChange(context: Context, from: RiskLevel, to: RiskLevel, s: SessionState) {
        if (to.ordinal <= from.ordinal) return // only escalate; the meter shows de-escalation
        val prefs = Prefs(context)
        val line = Warnings.oneLine(s.topTactics)
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
            if (prefs.speakWarnings) Voice.speak(Warnings.spokenPause(prefs.language, prefs.trustedLabel), prefs.language)
        } else {
            b.setContentTitle("Careful: this call shows scam signs").setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            if (prefs.speakWarnings) Voice.speak(Warnings.spokenWarn(prefs.language), prefs.language)
        }
        runCatching { nm.notify(ALERT_ID, b.build()) }
    }

    fun cancelAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }
}
