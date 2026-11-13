package com.bosc.guardian

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bosc.core.Speaker

/**
 * The armed guardian (plan 7.4 - 7.7).
 *
 * Started only from the foreground (Android blocks starting a microphone foreground service
 * from the background). Idles without touching the mic until a call goes OFFHOOK, listens
 * during the call, and releases the mic when the call ends.
 */
class GuardService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var asr: SpeechAsr? = null
    private var probe: MicLevelProbe? = null
    private var telephonyCallback: Any? = null
    private var inCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISARM -> { disarm(); return START_NOT_STICKY }
            ACTION_LISTEN_NOW -> { goForeground("Listening now (speaker demo)"); registerCallState(); startListening("manual") }
            ACTION_STOP_LISTEN -> { stopListening(); goForeground(idleText()) }
            else -> { goForeground(idleText()); registerCallState() }
        }
        GuardState.armed.value = true
        return START_STICKY
    }

    private fun idleText() = "BOSC is guarding your calls. Mic is off until a call starts."

    private fun goForeground(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disarm = PendingIntent.getService(this, 1, Intent(this, GuardService::class.java).setAction(ACTION_DISARM), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, BoscApp.CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("BOSC is guarding your calls")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", disarm)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, n)
    }

    private fun registerCallState() {
        if (telephonyCallback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            GuardState.setAsrStatus("Phone-state permission missing: BOSC cannot see calls. Use 'Listen now' or simulation.")
            return
        }
        val tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onCallState(state)
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            telephonyCallback = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            telephonyCallback = l
        }
    }

    private fun onCallState(state: Int) {
        Log.i(TAG, "call state $state")
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!inCall) {
                inCall = true
                goForeground("Call in progress. Listening for scam pressure on the speaker.")
                startListening("call")
            }
            TelephonyManager.CALL_STATE_IDLE -> if (inCall) {
                inCall = false
                stopListening()
                goForeground(idleText())
            }
        }
    }

    private fun startListening(source: String) {
        stopListening(endSession = false)
        GuardState.start(source)
        val prefs = Prefs(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            GuardState.setAsrStatus("Microphone permission missing"); return
        }
        if (prefs.micTestMode) {
            GuardState.setAsrStatus("Mic level test (Day-1 test): logging level once per second")
            probe = MicLevelProbe { db, src ->
                GuardState.setMicLevel(db)
                GuardState.setAsrStatus("Mic level test via $src: ${"%.1f".format(db)} dBFS")
            }.also { it.start() }
        } else {
            asr = SpeechAsr(
                this, prefs.language, prefs.allowOnlineAsr,
                onFinal = { GuardState.addUtterance(Speaker.UNKNOWN, it) },
                onPartial = { GuardState.setPartial(it) },
                onStatus = { GuardState.setAsrStatus(it) },
            ).also { it.start() }
        }
        main.post(speakerPoll)
    }

    private fun stopListening(endSession: Boolean = true) {
        asr?.stop(); asr = null
        probe?.stop(); probe = null
        main.removeCallbacks(speakerPoll)
        if (endSession) GuardState.end()
    }

    /** Speaker detection (plan 7.6). Shown in the UI; verify on the demo phone. */
    private val speakerPoll = object : Runnable {
        override fun run() {
            val am = getSystemService(AudioManager::class.java)
            val on = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.communicationDevice?.type?.let { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else null
            @Suppress("DEPRECATION")
            GuardState.setSpeakerOn(on ?: am.isSpeakerphoneOn)
            main.postDelayed(this, 2000)
        }
    }

    private fun disarm() {
        stopListening()
        unregisterCallState()
        GuardState.armed.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun unregisterCallState() {
        val tm = getSystemService(TelephonyManager::class.java)
        val cb = telephonyCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
        } else if (cb is PhoneStateListener) {
            @Suppress("DEPRECATION") tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        }
        telephonyCallback = null
    }

    override fun onDestroy() {
        stopListening()
        unregisterCallState()
        GuardState.armed.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BoscService"
        private const val NOTIF_ID = 1001
        const val ACTION_ARM = "com.bosc.guardian.ARM"
        const val ACTION_DISARM = "com.bosc.guardian.DISARM"
        const val ACTION_LISTEN_NOW = "com.bosc.guardian.LISTEN_NOW"
        const val ACTION_STOP_LISTEN = "com.bosc.guardian.STOP_LISTEN"

        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java).setAction(action))
        }
    }
}
