package com.heygobi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heygobi.app.voice.SessionState

/**
 * The voice-command screen (v1.4).
 *
 * After "The Structure" wakes the phone and the passcode passes, the
 * service opens this screen over the lock screen so the user SEES the
 * app listening, what it heard, and the result — instead of staring at
 * the Setup screen wondering whether anything is happening. The screen
 * closes itself when the service goes back to listening.
 */
class VoiceCommandActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeyGobiTheme {
                val listening by SessionState.commandListening.collectAsState()
                val heard by SessionState.commandHeard.collectAsState()
                val result by SessionState.commandResult.collectAsState()

                // The service owns the session: this screen closes itself
                // only after it has SEEN the session listening — never on
                // the first frame, or a launch race would close it at once.
                var seenListening by remember { mutableStateOf(false) }
                LaunchedEffect(listening) {
                    if (listening) seenListening = true
                    else if (seenListening) finish()
                }

                BackHandler { finish() }

                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Listening",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(96.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("The Structure", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    when {
                        result != null -> {
                            Text(
                                result!!,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                        heard != null -> {
                            Text(
                                "Vinna: \"$heard\"",
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Chesthunna…",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> {
                            Text(
                                "Vinipisthunnanu…",
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Cheppandi, Guruvu garu",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(onClick = { finish() }) {
                        Text("Aapu", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
