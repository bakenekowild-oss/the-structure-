package com.heygobi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.data.CommandLogEntity
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings: wake mode, voice-lock strictness, voice test, routines,
 * recent commands, chat history, and the honest limits of the app.
 */
@Composable
fun SettingsScreen(app: HeyGobiApp, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var wakeMode by remember {
        mutableStateOf(app.secureStore.getString(SecureStore.KEY_WAKE_MODE) ?: "grammar")
    }
    var threshold by remember { mutableStateOf(app.speakerVerifier.threshold()) }
    val routines by app.database.routineDao().getAll().collectAsState(initial = emptyList())
    var recent by remember { mutableStateOf<List<CommandLogEntity>>(emptyList()) }
    var cleared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        recent = app.database.commandLogDao().getRecent(15)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Wake-word mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Grammar mode listens only for \"Hey Gobi\" (fast, low battery). " +
                        "Full listening hears everything and catches the wake word " +
                        "even if \"gobi\" is missing from the speech model's dictionary. " +
                        "Takes effect when the listener restarts.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = wakeMode == "grammar",
                            onClick = {
                                wakeMode = "grammar"
                                app.secureStore.putString(SecureStore.KEY_WAKE_MODE, "grammar")
                            }
                        )
                        Text("Grammar (default)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = wakeMode == "full",
                            onClick = {
                                wakeMode = "full"
                                app.secureStore.putString(SecureStore.KEY_WAKE_MODE, "full")
                            }
                        )
                        Text("Full listening")
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Voice-lock strictness", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "How close a voice must match yours to wake the phone. " +
                        "Higher = stricter (fewer false wakes, but your voice " +
                        "must be clear). Default 0.65.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Threshold: ${"%.2f".format(threshold)}")
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { app.speakerVerifier.setThreshold(threshold) },
                        valueRange = 0.50f..0.85f,
                        steps = 6
                    )
                    OutlinedButton(onClick = {
                        app.ttsEngine.speak("Hey Gobi ready. Nenu vinipisthunnanu.")
                    }) { Text("Test voice") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Routines", style = MaterialTheme.typography.titleMedium)
                    if (routines.isEmpty()) {
                        Text("No routines yet. Say \"good morning\" to use the built-in one, " +
                            "or say \"routine create cheyyi\" to make your own by voice.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    for (routine in routines) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(routine.name, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = {
                                scope.launch {
                                    app.database.routineDao().deleteById(routine.id)
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Recent voice commands", style = MaterialTheme.typography.titleMedium)
                    if (recent.isEmpty()) {
                        Text("Nothing yet — wake the phone and give a command.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    for (log in recent) {
                        Text(
                            "${formatTime(log.timestamp)} — \"${log.command}\" → ${log.action} " +
                            if (log.success) "✓" else "✗",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Chat history", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        scope.launch {
                            app.chatRepository.clearHistory()
                            cleared = true
                        }
                    }) { Text("Clear chat history") }
                    if (cleared) Text("History cleared.")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About & honest limits", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hey Gobi v1.0 — fully offline, no accounts, no subscriptions, " +
                        "never expires. Brain: Gemma 3 4B Abliterated v2 (Q5_K_M) on-device.\n\n" +
                        "• The brain is slow on this phone — a few seconds per reply. " +
                        "Voice commands never wait on it; only chat does.\n" +
                        "• Speech recognition is English (Tanglish). A Telugu speech " +
                        "model exists but is too inaccurate to use.\n" +
                        "• Voice verification is similarity matching, not a security " +
                        "proof — the passcode gate is the real lock.\n" +
                        "• Pattern unlock depends on the device and may not work; " +
                        "PIN and password are the reliable paths.\n" +
                        "• Screen-off listening needs the service running and battery " +
                        "set to Unrestricted. Android may still kill it under " +
                        "heavy memory pressure — reopen the app to restart it.\n" +
                        "• The voice you hear is the system TTS voice, not your " +
                        "cloned voice.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
