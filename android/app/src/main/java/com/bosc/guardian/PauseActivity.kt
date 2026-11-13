package com.bosc.guardian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The calm pause: large type, short copy, clear choices (plan 7.11 / 7.13). */
class PauseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            BoscTheme {
                val s by GuardState.session.collectAsState()
                val isCall = s.source == "call" || s.source.startsWith("bosc call")
                Column(Modifier.fillMaxSize().background(Ink.Paper).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
                    Spacer(Modifier.weight(0.6f))
                    Label("Bosc paused this call", color = Ink.Danger)
                    Spacer(Modifier.height(14.dp))
                    Headline("Take a moment.\nThis call has the signs of a scam.", size = 32)
                    Spacer(Modifier.height(18.dp))
                    Text(Warnings.oneLine(s.topTactics), fontFamily = Serif, fontSize = 21.sp, lineHeight = 29.sp, color = Ink.Text)
                    Spacer(Modifier.height(12.dp))
                    Text("Don't pay and don't share any code. You can hang up. A real official will not mind.", fontSize = 17.sp, lineHeight = 25.sp, color = Ink.Muted)
                    Spacer(Modifier.weight(1f))
                    if (isCall) {
                        PrimaryButton("Hang up", { Actions.hangUp(this@PauseActivity); finish() }, color = Ink.Danger, height = 60.dp)
                        Spacer(Modifier.height(10.dp))
                    }
                    PrimaryButton("Call ${prefs.trustedLabel}", { Actions.callTrusted(this@PauseActivity) }, height = 60.dp)
                    Spacer(Modifier.height(10.dp))
                    QuietButton("Read this to me again", {
                        Voice.speak(Warnings.spokenPause(prefs.language, prefs.trustedLabel), prefs.language)
                    })
                    Spacer(Modifier.height(6.dp))
                    TextLink("I trust this caller", { GuardState.markTrusted(); finish() })
                    TextLink("Close", { finish() }, color = Ink.Faint)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
