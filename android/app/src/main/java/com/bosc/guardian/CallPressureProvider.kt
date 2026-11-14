package com.bosc.guardian

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Publishes ONLY the current call-pressure score to payment apps that embed bosc-sdk.
 * No transcript, no audio, no phone number. Guarded by com.bosc.permission.READ_CALL_PRESSURE.
 *
 * content://com.bosc.guardian.callpressure/current
 * columns: active, risk, level, tactics ("|"-separated labels), updated_at
 */
class CallPressureProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf("active", "risk", "level", "tactics", "updated_at", "usual_payment"))
        val usual = context?.let { Prefs(it).usualPayment.toDouble() } ?: 0.0
        val live = GuardState.session.value
        val s = if (live.active) live else GuardState.lastSession.value
        if (s != null) {
            val updated = if (s.active) System.currentTimeMillis() else (s.endedAt ?: s.startedAt)
            val level = if (s.trusted && s.level.name == "PAUSE") "WARN" else s.level.name
            c.addRow(arrayOf(if (s.active) 1 else 0, s.risk, level, s.topTactics.joinToString("|") { it.label }, updated, usual))
        } else {
            c.addRow(arrayOf(0, 0f, "LOW", "", 0L, usual))
        }
        return c
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.bosc.callpressure"
    /** content://com.bosc.guardian.callpressure/pay_attempt {level, amount}: the user tapped Pay in a payment app. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.lastPathSegment != "pay_attempt") return null
        GuardState.payAttempt(values?.getAsString("level") ?: "", values?.getAsDouble("amount") ?: 0.0)
        return uri
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
