package com.heygobi.app.ui

import com.heygobi.app.HeyGobiApp
import com.heygobi.app.audio.AudioCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.Model
import org.vosk.Recognizer

/**
 * One-shot voice input for the UI (chat mic button, spoken passcode).
 * Records until a final Vosk result arrives or the timeout hits, then
 * returns the recognised text (or null). The mic is released afterwards.
 */
class VoiceInputHelper(private val app: HeyGobiApp) {

    suspend fun listenOnce(timeoutMs: Long = 10_000): String? {
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
                        result = vosk.finalText(recognizer).trim().ifEmpty { null }
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
