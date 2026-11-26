package com.heygobi.app.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.brain.SystemPrompt
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Imports the Gemma brain model into the app's private storage.
 *
 * The app cannot read Termux's private folder, so first copy the model
 * to Downloads in Termux:
 *
 *   cp ~/ai/models/gemma-3-4b-it-abliterated-v2.q5_k_m.gguf ~/storage/downloads/
 *
 * Then pick it here. The file is copied (not moved) with progress, and
 * the size is checked — the Q5 model is 2,829,697,856 bytes; any .gguf
 * of 2 GB or more is accepted. After import, "Load brain" loads it once
 * to prove the model works.
 */
class ModelImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HeyGobiApp
        setContent {
            HeyGobiTheme {
                var pickedName by remember { mutableStateOf<String?>(null) }
                var pickedSize by remember { mutableStateOf<Long?>(null) }
                var progress by remember { mutableFloatStateOf(0f) }
                var importing by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf<String?>(null) }
                var importedPath by remember { mutableStateOf<String?>(null) }
                var loading by remember { mutableStateOf(false) }
                var loadDone by remember { mutableStateOf(false) }
                val loadProgress by app.llamaBridge.loadProgress.collectAsState()
                val scope = rememberCoroutineScope()

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        importing = true
                        status = null
                        importedPath = null
                        progress = 0f
                        val result = withContext(Dispatchers.IO) {
                            importFromUri(app, uri) { p -> progress = p }
                        }
                        importing = false
                        result.onSuccess { (file, size) ->
                            pickedName = file.name
                            pickedSize = size
                            importedPath = file.absolutePath
                            app.secureStore.putString(SecureStore.KEY_BRAIN_MODEL_PATH, file.absolutePath)
                            status = "Imported ${file.name} (${"%.2f".format(size / 1_073_741_824.0)} GB)."
                        }.onFailure { e ->
                            status = "Import failed: ${e.message}"
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Import brain model", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "1. In Termux, copy the model to Downloads:\n" +
                        "cp ~/ai/models/${SystemPrompt.MODEL_FILE_NAME} ~/storage/downloads/\n\n" +
                        "2. Tap \"Choose model file\" below and pick it from Downloads.\n\n" +
                        "The model stays on your phone only — it is never uploaded.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !importing
                    ) { Text("Choose model file") }

                    if (importing) {
                        CircularProgressIndicator()
                        Text("Copying… ${(progress * 100).toInt()}%")
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    pickedName?.let {
                        Text("File: $it")
                    }
                    pickedSize?.let {
                        Text("Size: ${"%.2f".format(it / 1_073_741_824.0)} GB")
                        if (it != SystemPrompt.MODEL_SIZE_BYTES) {
                            Text(
                                "Note: the expected Q5 model is " +
                                "${"%.2f".format(SystemPrompt.MODEL_SIZE_BYTES / 1_073_741_824.0)} GB. " +
                                "A different size still works if it is a valid .gguf, but " +
                                "check you picked the right file.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    status?.let { Text(it) }

                    if (importedPath != null) {
                        Button(
                            onClick = {
                                loading = true
                                loadDone = false
                                scope.launch {
                                    val ok = app.llamaBridge.loadModel(importedPath!!)
                                    loading = false
                                    loadDone = ok
                                    status = if (ok) "Brain loaded and ready! Go to the Chat tab."
                                    else "Brain failed to load — the file may be corrupt. Re-copy it in Termux and try again."
                                }
                            },
                            enabled = !loading
                        ) { Text("Load brain (test it)") }
                        if (loading) {
                            Text("Loading brain… ${(loadProgress * 100).toInt()}%")
                            LinearProgressIndicator(
                                progress = { loadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (loadDone) {
                            Text("✓ Brain ready", color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    OutlinedButton(onClick = { finish() }) { Text("Done") }
                }
            }
        }
    }

    private fun importFromUri(
        app: HeyGobiApp,
        uri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Pair<File, Long>> {
        return try {
            val resolver = contentResolver
            var displayName = "model.gguf"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            if (!displayName.lowercase().endsWith(".gguf")) {
                return Result.failure(IllegalArgumentException("That is not a .gguf model file."))
            }
            if (size in 1..1_999_999_999L) {
                return Result.failure(
                    IllegalArgumentException("File is too small (${size} bytes) — the Q5 model is 2.8 GB.")
                )
            }
            val dir = File(app.filesDir, "models").apply { mkdirs() }
            val dest = File(dir, displayName)
            val input = resolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("Could not read the selected file."))
            var copied = 0L
            input.use { inStream ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val read = inStream.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        copied += read
                        if (size > 0) onProgress(copied.toFloat() / size)
                    }
                }
            }
            if (copied < 2_000_000_000L) {
                dest.delete()
                return Result.failure(
                    IllegalArgumentException("Copied file is too small (${copied} bytes) — incomplete copy.")
                )
            }
            Result.success(dest to copied)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
