package com.bosc.guardian

import android.content.Context
import org.json.JSONObject

/** The team's caller scripts (frontend/demo/scripts.json): teleprompter lines for the phone playing the caller. */
data class Script(val id: String, val title: String, val scam: Boolean, val lines: List<String>)

object Scripts {
    @Volatile private var cache: List<Script>? = null

    fun all(context: Context): List<Script> = cache ?: runCatching {
        val root = JSONObject(context.assets.open("scripts.json").bufferedReader().readText())
        val arr = root.getJSONArray("scripts")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val l = o.getJSONArray("lines")
            Script(o.getString("id"), o.getString("title"), o.optString("kind") == "scam", (0 until l.length()).map { l.getString(it) })
        }
    }.getOrDefault(emptyList()).also { cache = it }
}
