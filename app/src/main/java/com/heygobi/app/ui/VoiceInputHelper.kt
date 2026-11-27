package com.heygobi.app.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.audio.AudioCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.coroutines.resume

/**
 * One-shot voice input for the UI (chat mic button, spoken passcode).
 * Records until a final result arrives or the timeout hits, then
 * returns the recognised text (or null). The mic is released afterwards.
 *
 * v1.4.4: Google's Indian-English recognizer first — it hears his accent
 * far better than the tiny on-device Vosk model (which gave "tied around
 * on" / "kira a loud mouth" for his words). Vosk stays as the offline
 * fallback, and its word-confidence is now checked so mumbled noise is
 * not returned as if it were words.
 */
class VoiceInputHelper(private val app: HeyGobiApp) {

    suspend fun listenOnce(timeoutMs: Long = 10_000): String? {
        val google = try {
            withTimeoutOrNull(timeoutMs) { listenOnceGoogle() }
        } catch (_: Exception) {
            null
        }
        if (!google.isNullOrBlank()) return google
        return listenOnceVosk(timeoutMs)
    }

    /**
     * Google's cloud recognizer, Indian English ("en-IN") — the model
     * that actually understands his accent. Returns the best match, or
     * null when the service is missing, offline, or heard nothing.
     */
    private suspend fun listenOnceGoogle(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return@withContext null
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
            var finished = false
            fun finish(value: String?) {
                if (finished) return
                finished = true
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume(value)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finish(matches?.firstOrNull()?.trim()?.ifEmpty { null })
                }

                override fun onError(error: Int) = finish(null)
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            try {
                recognizer.startListening(intent)
            } catch (_: Exception) {
                finish(null)
            }
            cont.invokeOnCancellation { finish(null) }
        }
    }

    private suspend fun listenOnceVosk(timeoutMs: Long): String? {
        val vosk = app.voskManager
        if (!vosk.isModelReady()) return null
        val model: Model = vosk.createModel() ?: return null
        val recognizer: Recognizer = vosk.createRecognizer(model, null)
        val capture = AudioCapture(app)
        var result: String? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                val started = capture.start { chunk, length ->
                    if (result != null) return@start
                    val finalReady = try {
                        vosk.feed(recognizer, chunk, length)
                    } catch (_: Exception) {
                        false
                    }
                    if (finalReady) {
                        val parsed = vosk.parseFinalResult(vosk.finalResult(recognizer))
                        // v1.4.4: word-level confidence — mumbled noise
                        // is not words, so keep listening instead of
                        // returning it.
                        if (parsed.avgConfidence >= MIN_VOSK_CONFIDENCE) {
                            result = parsed.text.trim().ifEmpty { null }
                        }
                    }
                }
                if (!started) return@withTimeoutOrNull
                while (result == null) delay(100)
            }
        } finally {
            try {
                capture.stop()
            } catch (_: Exception) {
            }
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
            try {
                model.close()
            } catch (_: Exception) {
            }
        }
        return result
    }

    companion object {
        private const val MIN_VOSK_CONFIDENCE = 0.30f

        private val DIGIT_WORDS = mapOf(
            "zero" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
            "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9',
            "oh" to '0'
        )

        /** Extracts digits from spoken text ("one two three" or "1 2 3"). */
        fun parseDigits(text: String): String {
            val out = StringBuilder()
            val lower = text.lowercase()
            for (ch in lower) {
                if (ch.isDigit()) out.append(ch)
            }
            if (out.isNotEmpty()) return out.toString()
            for (word in lower.split(Regex("[^a-z]+"))) {
                DIGIT_WORDS[word]?.let { out.append(it) }
            }
            return out.toString()
        }
    }
}
