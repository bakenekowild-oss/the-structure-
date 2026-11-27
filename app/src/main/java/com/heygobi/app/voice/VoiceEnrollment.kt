package com.heygobi.app.voice

import com.heygobi.app.audio.AudioCapture
import kotlinx.coroutines.delay

/**
 * Voiceprint enrolment flow: the user says "The Structure" three times.
 * Each sample is a 2.5 s capture from the mic. The recordings are used
 * only to build the embedding centroid and are never stored.
 */
class VoiceEnrollment(
    private val capture: AudioCapture,
    private val verifier: SpeakerVerifier
) {
    data class SampleProgress(val done: Int, val total: Int)

    /**
     * Records one sample. The caller shows a prompt ("Say 'The Structure' now")
     * before calling this. Captures [seconds] of audio after a short
     * lead-in so the whole phrase is caught.
     */
    suspend fun recordOneSample(seconds: Float = SpeakerVerifier.VERIFY_SECONDS): ShortArray {
        // Lead-in: let the user start speaking, then take the last window.
        delay(600)
        delay((seconds * 1000).toLong())
        return capture.recentAudio(seconds)
    }

    /** Enrols from the given samples. Returns enrolment quality, or negative on failure. */
    fun finish(samples: List<ShortArray>): Float = verifier.enroll(samples)
}
