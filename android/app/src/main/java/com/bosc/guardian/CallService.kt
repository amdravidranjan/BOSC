package com.bosc.guardian

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps this phone reachable for Bosc calls on the Wi-Fi: runs the call server and the
 * mDNS advertisement. A microphone foreground service, started while Bosc is on screen,
 * so an incoming call can use the mic even if Bosc is in the background by then.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Nearby.stop(); CallManager.stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, BoscApp.CH_GUARD)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Bosc calls are on")
            .setContentText("Other phones with Bosc on this Wi-Fi can call you.")
            .setOngoing(true).setContentIntent(open).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.w("BoscCallService", "could not start in foreground", e)
            stopSelf(); return START_NOT_STICKY
        }
        CallManager.init(this)
        if (Prefs(this).asrEngine == "vosk") VoskAsr.load(this) else WhisperAsr.load(this)
        val port = CallManager.startServer()
        val prefs = Prefs(this)
        if (intent?.action == ACTION_REFRESH) Nearby.refresh(this, prefs.myName, port) else Nearby.start(this, prefs.myName, port)
        return START_STICKY
    }

    override fun onDestroy() { Nearby.stop(); CallManager.stopServer(); super.onDestroy() }

    companion object {
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.bosc.guardian.CALLS_START"
        const val ACTION_REFRESH = "com.bosc.guardian.CALLS_REFRESH"
        const val ACTION_STOP = "com.bosc.guardian.CALLS_STOP"

        /** Call only while Bosc is on screen (Android rule for microphone services). */
        fun start(context: Context, action: String = ACTION_START) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(action)) }
        }
    }
}
