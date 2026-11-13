package com.bosc.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log

/** Reads device signals at the payment moment. No audio, no network. */
internal class SignalReader(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    /**
     * Audio mode tells the two kinds of call apart: MODE_IN_COMMUNICATION is an app call (WhatsApp, etc.),
     * MODE_IN_CALL is a SIM call. Checked first because Android may also report app calls as OFFHOOK.
     */
    fun callType(): CallType {
        when (audio.mode) {
            AudioManager.MODE_IN_COMMUNICATION -> return CallType.INTERNET
            AudioManager.MODE_IN_CALL -> return CallType.PHONE
        }
        return if (isOnCall()) CallType.PHONE else CallType.NONE
    }

    fun isOnCall(): Boolean {
        val hasPhoneState = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (hasPhoneState) {
            try {
                @Suppress("DEPRECATION")
                if (telephony.callState == TelephonyManager.CALL_STATE_OFFHOOK) return true
            } catch (_: SecurityException) { }
        }
        // Fallback without permission: covers SIM calls and VoIP/WhatsApp calls too.
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    fun isSpeakerOn(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.let { return it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        @Suppress("DEPRECATION")
        return audio.isSpeakerphoneOn
    }

    fun installedRemoteAccessApps(): List<String> = REMOTE_PACKAGES.filter { pkg ->
        try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }.map { REMOTE_APP_NAMES[it] ?: it }

    /** Enabled accessibility services as "package/class" strings. Remote-control tools and banking trojans abuse this. */
    fun enabledAccessibilityServices(): List<String> {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return emptyList()
        return raw.split(':').filter { it.isNotBlank() }
    }

    /** Reads the guardian app's current call-pressure score through its ContentProvider. */
    fun callPressure(maxAgeMs: Long): CallPressure? = try {
        context.contentResolver.query(PRESSURE_URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val active = c.getInt(c.getColumnIndexOrThrow("active")) == 1
            val updated = c.getLong(c.getColumnIndexOrThrow("updated_at"))
            CallPressure(
                risk = c.getFloat(c.getColumnIndexOrThrow("risk")),
                level = c.getString(c.getColumnIndexOrThrow("level")),
                tactics = c.getString(c.getColumnIndexOrThrow("tactics")).split('|').filter { it.isNotBlank() },
                updatedAt = updated,
                fresh = active || System.currentTimeMillis() - updated <= maxAgeMs,
            )
        }
    } catch (e: Exception) {
        // Guardian not installed, not armed, or permission missing (install BOSC before PayFast).
        Log.d("BoscSdk", "call pressure unavailable: ${e.message}")
        null
    }

    companion object {
        val PRESSURE_URI: Uri = Uri.parse("content://com.bosc.guardian.callpressure/current")

        val REMOTE_APP_NAMES = mapOf(
            "com.anydesk.anydeskandroid" to "AnyDesk",
            "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
            "com.teamviewer.host.market" to "TeamViewer Host",
            "com.rustdesk.rustdesk" to "RustDesk",
            "com.carriez.flutter_hbb" to "RustDesk",
            "com.sand.airdroid" to "AirDroid",
            "com.sand.airdroidbiz" to "AirDroid Business",
            "com.sand.airmirror" to "AirMirror",
        )
        val REMOTE_PACKAGES = REMOTE_APP_NAMES.keys
    }
}
