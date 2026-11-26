package com.heygobi.app.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Vosk offline speech recognition.
 *
 * One small English model (vosk-model-small-en-us-0.15, ~40 MB, Apache-2.0)
 * is downloaded once on first run and stored in the app's private files.
 * Everything after that is offline: wake-word spotting and command
 * recognition never touch the network.
 *
 * Wake-word detection runs in grammar mode: the recognizer only listens
 * for "hey gobi" (plus [unk] for everything else). That is the fast,
 * low-false-alarm path. If "gobi" turns out not to be in the small model's
 * lexicon, the settings screen offers full-listening mode instead — the
 * recognizer then hears everything and the app looks for the phrase in the
 * partial results. Both modes are real Vosk recognition; neither is faked.
 *
 * NOTE on Telugu: a small Telugu Vosk model exists, but its published word
 * error rate is ~88% — effectively unusable. So speech input is English /
 * Tanglish through the English model, and the BRAIN answers in Telugu Roman.
 * The user speaks Tanglish, which is how he talks.
 */
class VoskManager(private val context: Context) {

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        const val MIN_MODEL_BYTES = 30_000_000L // the zip is ~40 MB; anything far smaller is a broken download

        /** Grammar-mode wake phrase. [unk] is required or the search stalls. */
        const val WAKE_GRAMMAR = "[\"hey gobi\", \"[unk]\"]"
        const val WAKE_PHRASE = "hey gobi"

        /** Common mis-hearings of the wake phrase that are accepted as well. */
        val WAKE_ALIASES = listOf("hey goby", "hey go be", "hey gobi ")
    }

    val modelDir: File get() = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(): Boolean {
        val dir = modelDir
        return dir.isDirectory && File(dir, "am").exists() && File(dir, "conf").exists()
    }

    /**
     * Downloads and unpacks the Vosk model. Reports progress 0.0-1.0.
     * Returns false on any failure (network, short download, bad zip) and
     * leaves no half-unpacked model behind.
     */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(context.cacheDir, "vosk-model.zip")
            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (zipFile.length() < MIN_MODEL_BYTES) {
                zipFile.delete()
                return@withContext false
            }
            // Unpack into a temp dir first, then move into place.
            val tmpDir = File(context.filesDir, "$MODEL_DIR_NAME.tmp")
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(tmpDir, entry.name).canonicalFile
                    require(outFile.path.startsWith(tmpDir.canonicalPath)) { "bad zip entry" }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zip.copyTo(out) }
                    }
                    entry = zip.nextEntry
                }
            }
            zipFile.delete()
            val unpacked = File(tmpDir, MODEL_DIR_NAME)
            val target = modelDir
            if (!unpacked.isDirectory) {
                tmpDir.deleteRecursively()
                return@withContext false
            }
            target.deleteRecursively()
            if (!unpacked.renameTo(target)) {
                tmpDir.deleteRecursively()
                return@withContext false
            }
            tmpDir.deleteRecursively()
            onProgress(1f)
            isModelReady()
        } catch (_: Exception) {
            false
        }
    }

    fun createModel(): Model? {
        if (!isModelReady()) return null
        return try {
            Model(modelDir.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /** Creates a recognizer. [grammar] is a JSON array string, or null for the full language model. */
    fun createRecognizer(model: Model, grammar: String? = null): Recognizer =
        if (grammar != null) Recognizer(model, AudioCapture.SAMPLE_RATE.toFloat(), grammar)
        else Recognizer(model, AudioCapture.SAMPLE_RATE.toFloat())

    /** Feeds PCM to the recognizer. Returns true when a final result is ready. */
    fun feed(recognizer: Recognizer, chunk: ShortArray, length: Int): Boolean =
        recognizer.acceptWaveForm(chunk, length)

    fun partialText(recognizer: Recognizer): String =
        runCatching { JSONObject(recognizer.partialResult).optString("partial", "") }.getOrDefault("")

    fun finalText(recognizer: Recognizer): String =
        runCatching { JSONObject(recognizer.result).optString("text", "") }.getOrDefault("")

    fun finalResult(recognizer: Recognizer): String = recognizer.result

    /** Normalises recognised text and checks for the wake phrase. */
    fun containsWakeWord(text: String): Boolean {
        val norm = " " + text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ") + " "
        if (norm.contains(" $WAKE_PHRASE ")) return true
        return WAKE_ALIASES.any { norm.contains(" $it ") }
    }
}
