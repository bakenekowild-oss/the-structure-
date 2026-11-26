package com.heygobi.app.voice

/**
 * Voice input/output used by the command engine.
 * Implemented by WakeWordService: [listen] waits for one spoken utterance
 * (final Vosk result) and returns the recognised text, or null on timeout.
 */
interface VoiceIO {
    suspend fun listen(timeoutMs: Long): String?
    fun speak(text: String)
}
