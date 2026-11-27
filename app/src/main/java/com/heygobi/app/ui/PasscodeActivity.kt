package com.heygobi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.voice.SessionState
import kotlinx.coroutines.launch

/**
 * The passcode gate, shown over the lock screen after "The Structure" wakes
 * the phone and the voice lock passes.
 *
 * Enter the 4–8 digit passcode on the keypad, or say it aloud ("one two
 * three four"). Wrong code → the service is told FAILED and nothing opens.
 * After 5 wrong tries the passcode locks for 60 seconds.
 */
class PasscodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HeyGobiApp
        setContent {
            HeyGobiTheme {
                var entered by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var listening by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                fun finishWith(result: SessionState.PasscodeResult) {
                    SessionState.setPasscodeResult(result)
                    finish()
                }

                fun verify(code: String) {
                    if (app.passcodeManager.isLockedOut()) {
                        val secs = (app.passcodeManager.lockoutRemainingMs() / 1000) + 1
                        error = "Too many wrong tries. Wait $secs seconds."
                        return
                    }
                    if (app.passcodeManager.verify(code)) {
                        finishWith(SessionState.PasscodeResult.OK)
                    } else {
                        error = "Passcode thappu, malli try cheyyandi"
                        entered = ""
                        if (app.passcodeManager.isLockedOut()) {
                            error = "Too many wrong tries. Wait a minute."
                        }
                    }
                }

                BackHandler { finishWith(SessionState.PasscodeResult.FAILED) }

                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("The Structure", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Passcode cheppandi",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "•".repeat(entered.length).ifEmpty { "— — — —" },
                        fontSize = 28.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 12.dp))
                    }

                    // Keypad: 1-9, then mic / 0 / backspace.
                    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                    for (row in 0 until 3) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            for (col in 0 until 3) {
                                val digit = keys[row * 3 + col]
                                KeypadButton(digit) {
                                    if (entered.length < 8) {
                                        entered += digit
                                        error = null
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (listening) return@IconButton
                                listening = true
                                scope.launch {
                                    val heard = VoiceInputHelper(app).listenOnce(10_000)
                                    listening = false
                                    val digits = heard?.let { VoiceInputHelper.parseDigits(it) } ?: ""
                                    if (digits.length in 4..8) {
                                        verify(digits)
                                    } else if (digits.isNotEmpty()) {
                                        error = "Passcode 4–8 digits undali"
                                    } else {
                                        error = "Vinipinchaledhu, malli cheppandi"
                                    }
                                }
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = "Speak passcode")
                        }
                        KeypadButton("0") {
                            if (entered.length < 8) {
                                entered += "0"
                                error = null
                            }
                        }
                        IconButton(
                            onClick = { entered = entered.dropLast(1) },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Filled.Backspace, contentDescription = "Delete")
                        }
                    }
                    if (listening) {
                        Text("Listening… say your passcode", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = { verify(entered) },
                        enabled = entered.length >= 4,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Unlock") }
                    OutlinedButton(
                        onClick = { finishWith(SessionState.PasscodeResult.FAILED) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(64.dp)
    ) {
        Text(label, fontSize = 22.sp)
    }
}
