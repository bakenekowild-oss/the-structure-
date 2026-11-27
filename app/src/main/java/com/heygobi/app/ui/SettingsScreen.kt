package com.heygobi.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.R
import com.heygobi.app.brain.GroqClient
import com.heygobi.app.data.CommandLogEntity
import com.heygobi.app.notifications.GobiNotificationListenerService
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    @Suppress("UNUSED_VARIABLE")
    val unusedTick = tick
    val notificationOk = GobiNotificationListenerService.isEnabled(context)
    var wakeMode by remember {
        // Default is Full listening (v1.3): "the" is a weak word that
        // Vosk often drops or mumbles, so it is the reliable mode for
        // "The Structure". Nothing stored yet means "full".
        mutableStateOf(app.secureStore.getString(SecureStore.KEY_WAKE_MODE) ?: "full")
    }
    var threshold by remember { mutableStateOf(app.speakerVerifier.threshold()) }
    val routines by app.database.routineDao().getAll().collectAsState(initial = emptyList())
    var recent by remember { mutableStateOf<List<CommandLogEntity>>(emptyList()) }
    var cleared by remember { mutableStateOf(false) }
    var groqKey by remember { mutableStateOf(app.groqClient.savedKey() ?: "") }
    var groqOn by remember { mutableStateOf(app.groqClient.hasKey()) }
    var groqMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        recent = app.database.commandLogDao().getRecent(15)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "The Structure",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wake-word mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Full listening (recommended) hears everything and catches " +
                        "\"The Structure\" reliably. Grammar mode listens only for " +
                        "\"The Structure\" (fast, low battery) — but \"the\" is a weak " +
                        "word: it is often mumbled or dropped in speech, so grammar " +
                        "mode can miss the wake. Note: \"structure\" is a common word, " +
                        "so the phone may wake on other speech too — the voice check " +
                        "and passcode still protect it. Takes effect when the " +
                        "listener restarts.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    WakeModeOption(
                        label = "Grammar",
                        description = "Listens only for “The Structure”. Fast, low " +
                            "battery — but it can miss when “the” is mumbled or dropped.",
                        selected = wakeMode == "grammar",
                        onSelect = {
                            wakeMode = "grammar"
                            app.secureStore.putString(SecureStore.KEY_WAKE_MODE, "grammar")
                        }
                    )
                    WakeModeOption(
                        label = "Full listening (recommended)",
                        description = "Hears everything and catches “The Structure” " +
                            "reliably, including “da structure” and the bare word “structure”.",
                        selected = wakeMode == "full",
                        onSelect = {
                            wakeMode = "full"
                            app.secureStore.putString(SecureStore.KEY_WAKE_MODE, "full")
                        }
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Jarvis mode (Groq API key)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Free key from console.groq.com — stays on your phone, sent only to api.groq.com. " +
                        "Leave empty to use the on-device brain only.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (groqOn) {
                            "On — chat uses Groq first (answers in seconds) and falls back " +
                            "to the on-device brain when offline."
                        } else {
                            "Off — chat uses the on-device brain only (slow, a few seconds per reply)."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = groqKey,
                        onValueChange = { groqKey = it; groqMessage = "" },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("gsk_…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            app.groqClient.saveKey(groqKey)
                            groqOn = app.groqClient.hasKey()
                            groqMessage = if (groqOn) {
                                "Saved — Jarvis mode is on."
                            } else {
                                "Key removed — on-device brain only."
                            }
                        }) { Text("Save key") }
                        OutlinedButton(onClick = {
                            val keyToTest = groqKey.ifBlank { app.groqClient.savedKey() ?: "" }
                            groqMessage = "Testing…"
                            scope.launch {
                                groqMessage = when (app.groqClient.test(keyToTest)) {
                                    GroqClient.TestResult.OK -> "Key works — Jarvis mode is on."
                                    GroqClient.TestResult.NO_KEY -> "Paste a key first."
                                    GroqClient.TestResult.NO_NETWORK -> "No network right now."
                                    GroqClient.TestResult.BAD_KEY -> "Key not accepted — check the key at console.groq.com."
                                    GroqClient.TestResult.FAILED -> "Groq did not answer — try again later."
                                }
                            }
                        }) { Text("Test") }
                        TextButton(onClick = {
                            app.groqClient.clearKey()
                            groqKey = ""
                            groqOn = false
                            groqMessage = "Key removed — on-device brain only."
                        }) { Text("Clear") }
                    }
                    if (groqMessage.isNotEmpty()) {
                        Text(groqMessage, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice-lock strictness", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "How close a voice must match yours to wake the phone. " +
                        "Higher = stricter (fewer false wakes, but your voice " +
                        "must be clear). Default 0.55.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Threshold: ${"%.2f".format(threshold)}")
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { app.speakerVerifier.setThreshold(threshold) },
                        valueRange = 0.40f..0.85f,
                        steps = 8
                    )
                    OutlinedButton(onClick = {
                        app.ttsEngine.speak("The Structure ready. Nenu vinipisthunnanu.")
                    }) { Text("Test voice") }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notification access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notificationOk) {
                            val count = GobiNotificationListenerService.recentNotifications().size
                            "On — The Structure mee notifications chadivi, WhatsApp lanti " +
                            "apps lo reply ivvagalthundhi. Ippudu memory lo $count " +
                            "notifications unnayi."
                        } else {
                            "Off — notifications chadavadaniki, reply ivvadaniki " +
                            "idi on cheyyali. Setup lo kuda ee step untundhi."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text(if (notificationOk) "Notification settings" else "Enable notification access")
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("About & honest limits", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The Structure v1.4.3 — on-device brain, with optional Groq cloud brain " +
                        "(Jarvis mode). No accounts, no subscriptions, never expires. " +
                        "Brain: Gemma 3 4B Abliterated v2 (Q5_K_M) on-device.\n\n" +
                        "• Wake word is \"The Structure\" — say it anywhere to wake the " +
                        "phone. Full listening is recommended: \"the\" is often mumbled " +
                        "or dropped, so the app also answers to \"da structure\" and the " +
                        "bare word \"structure\". \"Structure\" is a common word, so the " +
                        "phone may wake on other speech too — the voice check and " +
                        "passcode still protect it. Your voiceprint does not need " +
                        "re-enrollment: it checks your voice, not the words.\n\n" +
                        "• With a Groq key saved, chat answers come from Groq in seconds; " +
                        "without a key, the on-device brain answers — it is slow on this " +
                        "phone, a few seconds per reply. Voice commands never wait on " +
                        "the brain; only chat does.\n" +
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

/**
 * One wake-mode choice: a tappable card with a gold border and a gold
 * tint when selected, plain outline when not. The whole card is the
 * tap target, not just the radio button.
 */
@Composable
private fun WakeModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
