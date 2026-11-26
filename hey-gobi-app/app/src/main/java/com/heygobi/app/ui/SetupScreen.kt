package com.heygobi.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.audio.AudioCapture
import com.heygobi.app.security.SecureStore
import com.heygobi.app.voice.VoiceEnrollment
import com.heygobi.app.voice.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-run setup checklist. Every step shows its real status; nothing is
 * marked done unless it is actually done. Steps marked Required must be
 * finished before "Start listening" works.
 */
@Composable
fun SetupScreen(app: HeyGobiApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    var downloading by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var showEnrollment by remember { mutableStateOf(false) }
    var showPasscodeDialog by remember { mutableStateOf(false) }
    var showLockDialog by remember { mutableStateOf(false) }
    var showEmergencyDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    // ---- live status (recomputed every resume) ----
    @Suppress("UNUSED_VARIABLE")
    val unused = tick
    val permsOk = hasAllPermissions(context)
    val batteryOk = isBatteryUnrestricted(context)
    val a11yOk = isAccessibilityEnabled(context)
    val modelPath = app.secureStore.getString(SecureStore.KEY_BRAIN_MODEL_PATH)
    val modelOk = !modelPath.isNullOrBlank() && File(modelPath).exists()
    val voskOk = app.voskManager.isModelReady()
    val speakerOk = app.speakerVerifier.isModelReady()
    val enrolled = app.speakerVerifier.isEnrolled()
    val passcodeOk = app.passcodeManager.hasPasscode()
    val lockOk = !app.secureStore.getString(SecureStore.KEY_LOCK_CREDENTIAL).isNullOrBlank()
    val emergencyOk = !app.secureStore.getString(SecureStore.KEY_EMERGENCY_NUMBER).isNullOrBlank()
    val listeningOk = WakeWordService.isRunning
    val readyToListen = permsOk && batteryOk && a11yOk && voskOk && speakerOk && enrolled && passcodeOk

    if (showEnrollment) {
        EnrollmentScreen(app, onDone = { showEnrollment = false; tick++ })
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Hey Gobi — Setup", style = MaterialTheme.typography.headlineSmall)
            Text(
                "One-time setup. Do each step once; the app remembers it forever.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        item {
            SetupStep(
                title = "1. Permissions (Required)",
                description = "Microphone, notifications, contacts, calls, SMS, location.",
                done = permsOk,
                actionLabel = if (permsOk) "Granted ✓" else "Grant permissions",
                onAction = {
                    permissionLauncher.launch(REQUIRED_PERMISSIONS)
                }
            )
        }
        item {
            SetupStep(
                title = "2. Battery — Unrestricted (Required)",
                description = "Without this, Android kills the listener and the brain " +
                    "dies exactly like the Termux server did.",
                done = batteryOk,
                actionLabel = if (batteryOk) "Unrestricted ✓" else "Allow unrestricted",
                onAction = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            )
        }
        item {
            SetupStep(
                title = "3. Accessibility service (Required)",
                description = "Lets Hey Gobi tap, scroll, type and unlock the screen " +
                    "on your voice. Settings → Accessibility → Hey Gobi control → On.",
                done = a11yOk,
                actionLabel = if (a11yOk) "Enabled ✓" else "Open Accessibility settings",
                onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
        }
        item {
            SetupStep(
                title = "4. Brain model (Required for chat)",
                description = "Import gemma-3-4b-it-abliterated-v2.q5_k_m.gguf from " +
                    "Downloads. First copy it in Termux: " +
                    "cp ~/ai/models/gemma-3-4b-it-abliterated-v2.q5_k_m.gguf ~/storage/downloads/",
                done = modelOk,
                actionLabel = if (modelOk) "Imported ✓" else "Import model",
                onAction = {
                    context.startActivity(Intent(context, ModelImportActivity::class.java))
                }
            )
        }
        item {
            SetupStep(
                title = "5. Voice spotter model (Required)",
                description = "Vosk small English model (~40 MB). One-time download, " +
                    "then fully offline. Used to hear \"Hey Gobi\".",
                done = voskOk,
                actionLabel = if (voskOk) "Downloaded ✓" else "Download Vosk model",
                onAction = {
                    scope.launch {
                        downloading = "Vosk model"
                        downloadError = null
                        val ok = withContext(Dispatchers.IO) {
                            app.voskManager.downloadModel { p -> downloadProgress = p }
                        }
                        downloading = ""
                        if (!ok) downloadError = "Vosk download failed — check internet and retry."
                        tick++
                    }
                }
            )
        }
        item {
            SetupStep(
                title = "6. Voiceprint model (Required)",
                description = "WeSpeaker ECAPA model (~25 MB, SHA-256 verified). " +
                    "One-time download, then fully offline. This is what " +
                    "recognises YOUR voice.",
                done = speakerOk,
                actionLabel = if (speakerOk) "Downloaded ✓" else "Download voiceprint model",
                onAction = {
                    scope.launch {
                        downloading = "Voiceprint model"
                        downloadError = null
                        val ok = withContext(Dispatchers.IO) {
                            app.speakerVerifier.downloadModel { p -> downloadProgress = p }
                        }
                        downloading = ""
                        if (!ok) downloadError = "Voiceprint download failed — check internet and retry."
                        tick++
                    }
                }
            )
        }
        item {
            SetupStep(
                title = "7. Voice enrollment (Required)",
                description = "Say \"Hey Gobi\" 3 times so the app learns your voice. " +
                    "Only your voice will wake it afterwards.",
                done = enrolled,
                actionLabel = if (enrolled) "Enrolled ✓" else "Enroll my voice",
                onAction = { showEnrollment = true }
            )
        }
        item {
            SetupStep(
                title = "8. App passcode (Required)",
                description = "4–8 digit passcode. After waking, Hey Gobi asks for " +
                    "this before taking phone access.",
                done = passcodeOk,
                actionLabel = if (passcodeOk) "Set ✓" else "Set passcode",
                onAction = { showPasscodeDialog = true }
            )
        }
        item {
            SetupStep(
                title = "9. Screen-lock credential (Optional, recommended)",
                description = "Your phone's own PIN / password / pattern, so Hey Gobi " +
                    "can unlock the lock screen for you. Stored encrypted " +
                    "on-device only, never sent anywhere. Without it, " +
                    "unlock the phone by hand when it wakes.",
                done = lockOk,
                actionLabel = if (lockOk) "Saved ✓" else "Save lock credential",
                onAction = { showLockDialog = true }
            )
        }
        item {
            SetupStep(
                title = "10. Emergency contact (Optional)",
                description = "Saying \"Help\" calls this person at once, no confirmation.",
                done = emergencyOk,
                actionLabel = if (emergencyOk) "Saved ✓" else "Set emergency contact",
                onAction = { showEmergencyDialog = true }
            )
        }
        item {
            if (downloading.isNotEmpty()) {
                Column {
                    Text("Downloading $downloading… ${(downloadProgress * 100).toInt()}%")
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
            downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (readyToListen) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (listeningOk) "Listening is ON — say \"Hey Gobi\""
                        else if (readyToListen) "All required steps done — start listening"
                        else "Finish the required steps above to start listening",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (listeningOk) WakeWordService.stop(context)
                                else WakeWordService.start(context)
                                tick++
                            },
                            enabled = listeningOk || readyToListen
                        ) {
                            Text(if (listeningOk) "Stop listening" else "Start listening")
                        }
                        if (listeningOk || readyToListen) {
                            OutlinedButton(onClick = {
                                app.secureStore.putBoolean(SecureStore.KEY_SETUP_DONE, true)
                                tick++
                            }) {
                                Text("Mark setup done")
                            }
                        }
                    }
                    Text(
                        "Setup done = the listener restarts itself after a reboot. " +
                        "You can change anything later in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showPasscodeDialog) {
        PasscodeSetDialog(app, onDismiss = { showPasscodeDialog = false; tick++ })
    }
    if (showLockDialog) {
        LockCredentialDialog(app, onDismiss = { showLockDialog = false; tick++ })
    }
    if (showEmergencyDialog) {
        EmergencyContactDialog(app, onDismiss = { showEmergencyDialog = false; tick++ })
    }
}

private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.SEND_SMS,
    Manifest.permission.ACCESS_FINE_LOCATION
).let {
    if (Build.VERSION.SDK_INT >= 33) it + Manifest.permission.POST_NOTIFICATIONS else it
}

private fun hasAllPermissions(context: Context): Boolean {
    return REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains(context.packageName)
}

@Composable
private fun SetupStep(
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (done) "✓ " else "○ ",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp))
            Button(
                onClick = onAction,
                enabled = !done || actionLabel.contains("Import") || actionLabel.contains("Enroll"),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

// ------------------------------------------------------------------
// Enrollment screen
// ------------------------------------------------------------------

@Composable
private fun EnrollmentScreen(app: HeyGobiApp, onDone: () -> Unit) {
    var phase by remember { mutableStateOf(0) } // 0 = intro, 1..3 = recording, 4 = result
    var quality by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Voice enrollment", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Say \"Hey Gobi\" clearly, 3 times. Hold the phone normally, " +
            "in a quiet room. This is what keeps anyone else's voice out.",
            style = MaterialTheme.typography.bodyMedium
        )
        when (phase) {
            0 -> {
                Button(onClick = {
                    phase = 1
                    scope.launch {
                        // The listener must release the mic before enrolling.
                        val ctx = app.applicationContext
                        WakeWordService.stop(ctx)
                        val capture = AudioCapture(app)
                        val enrollment = VoiceEnrollment(capture, app.speakerVerifier)
                        val samples = mutableListOf<ShortArray>()
                        try {
                            capture.start { _, _ -> }
                            for (i in 1..3) {
                                phase = i
                                samples.add(enrollment.recordOneSample())
                            }
                            val q = enrollment.finish(samples)
                            quality = if (q >= 0f) q else null
                            error = if (q < 0f) "Enrollment failed — speak clearly and retry." else null
                        } catch (e: Exception) {
                            quality = null
                            error = "Enrollment error: ${e.message}"
                        } finally {
                            try { capture.stop() } catch (_: Exception) {}
                        }
                        phase = 4
                    }
                }) { Text("Start — say \"Hey Gobi\" 3 times") }
                OutlinedButton(onClick = onDone) { Text("Back") }
            }
            in 1..3 -> {
                CircularProgressIndicator()
                Text("Listening… say \"Hey Gobi\" (sample $phase of 3)")
            }
            4 -> {
                if (quality != null) {
                    Text(
                        "Voice enrolled! Quality ${(quality!! * 100).toInt()}% — " +
                        "only your voice will wake Hey Gobi now.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = {
                        app.secureStore.putBoolean(SecureStore.KEY_SETUP_DONE, true)
                        onDone()
                    }) { Text("Done") }
                } else {
                    Text(error ?: "Enrollment failed.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { phase = 0 }) { Text("Try again") }
                    OutlinedButton(onClick = onDone) { Text("Back") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Dialogs
// ------------------------------------------------------------------

@Composable
private fun PasscodeSetDialog(app: HeyGobiApp, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set app passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4–8 digits. Hey Gobi asks for this after waking, before taking phone access.")
                OutlinedTextField(
                    value = first, onValueChange = { first = it.filter(Char::isDigit).take(8) },
                    label = { Text("Passcode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = second, onValueChange = { second = it.filter(Char::isDigit).take(8) },
                    label = { Text("Confirm passcode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    first.length < 4 -> error = "At least 4 digits."
                    first != second -> error = "Passcodes do not match."
                    else -> {
                        app.passcodeManager.setPasscode(first)
                        onDismiss()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LockCredentialDialog(app: HeyGobiApp, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf("pin") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Screen-lock credential") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your phone's own lock credential. Stored encrypted on-device only. " +
                    "Hey Gobi uses it to unlock the lock screen after your voice + passcode. " +
                    "It is never sent anywhere."
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == "pin", onClick = { kind = "pin"; value = "" })
                    Text("PIN")
                    RadioButton(selected = kind == "password", onClick = { kind = "password"; value = "" })
                    Text("Password")
                    RadioButton(selected = kind == "pattern", onClick = { kind = "pattern"; value = "" })
                    Text("Pattern")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = when (kind) {
                            "pin" -> it.filter(Char::isDigit).take(16)
                            "pattern" -> it.filter { c -> c in '1'..'9' }.take(9)
                            else -> it.take(64)
                        }
                    },
                    label = {
                        Text(when (kind) {
                            "pin" -> "PIN digits"
                            "pattern" -> "Pattern digits 1-9 (e.g. 14789)"
                            else -> "Password"
                        })
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (kind == "password") KeyboardType.Password else KeyboardType.Number
                    ),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (kind == "pattern") {
                    Text(
                        "Pattern = the 3×3 grid, 1 = top-left, 9 = bottom-right. " +
                        "Enter the digits in the order you draw them (min 4). " +
                        "Pattern unlock is device-dependent — if it fails, unlock by hand.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val valid = when (kind) {
                    "pin" -> value.length >= 4
                    "pattern" -> value.length >= 4 && value.toSet().size == value.length
                    else -> value.length >= 4
                }
                if (!valid) {
                    error = when (kind) {
                        "pattern" -> "Pattern needs 4–9 different digits (1-9)."
                        else -> "At least 4 characters."
                    }
                    return@TextButton
                }
                app.secureStore.putString(SecureStore.KEY_LOCK_KIND, kind)
                app.secureStore.putString(SecureStore.KEY_LOCK_CREDENTIAL, value)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmergencyContactDialog(app: HeyGobiApp, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(
        app.secureStore.getString(SecureStore.KEY_EMERGENCY_NAME) ?: ""
    ) }
    var number by remember { mutableStateOf(
        app.secureStore.getString(SecureStore.KEY_EMERGENCY_NUMBER) ?: ""
    ) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saying \"Help\" calls this person immediately, with no confirmation.")
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text("Phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (number.length < 10) {
                    error = "Enter a valid phone number."
                    return@TextButton
                }
                app.secureStore.putString(SecureStore.KEY_EMERGENCY_NAME, name.ifBlank { "Emergency" })
                app.secureStore.putString(SecureStore.KEY_EMERGENCY_NUMBER, number)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
