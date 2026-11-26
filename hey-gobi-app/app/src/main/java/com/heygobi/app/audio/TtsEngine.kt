package com.heygobi.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Spoken replies, using the phone's built-in text-to-speech (fully offline
 * for installed voices — no network, no API, no cost).
 *
 * Honest note: this is the system TTS voice, not the user's cloned voice.
 * The brain's words are Telugu Roman (Tanglish); the system reads them with
 * the best matching installed voice (Telugu if present, else Indian
 * English). A cloned-voice TTS is out of scope for v1.
 */
class TtsEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    var onUtteranceDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = this.tts ?: return@TextToSpeech
                // Prefer Telugu; fall back to Indian English, then default.
                val te = tts.setLanguage(Locale("te", "IN"))
                if (te == TextToSpeech.LANG_MISSING_DATA || te == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val enIn = tts.setLanguage(Locale("en", "IN"))
                    if (enIn == TextToSpeech.LANG_MISSING_DATA || enIn == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.language = Locale.getDefault()
                    }
                }
                tts.setSpeechRate(1.0f)
                tts.setPitch(1.05f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onUtteranceDone?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onUtteranceDone?.invoke()
                    }
                })
                _ready.value = true
            }
        }
    }

    /** Speaks immediately, interrupting anything currently spoken. */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /** Queues speech after the current utterance. */
    fun speakQueued(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        _ready.value = false
    }
}
