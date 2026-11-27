package com.heygobi.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/**
 * Single-owner microphone capture at 16 kHz mono PCM, shared by every
 * listener in the app: the Vosk recognizer and the speaker verifier both
 * read from this one stream, so the mic is never opened twice.
 *
 * A ring buffer keeps the last [RING_SECONDS] of audio, so when "The Structure"
 * is detected the exact utterance that woke the phone is available for
 * speaker verification — no re-recording is needed.
 */
class AudioCapture(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1600 // 100 ms per read
        const val RING_SECONDS = 4
        private val RING_SIZE = SAMPLE_RATE * RING_SECONDS
    }

    fun interface Listener {
        fun onChunk(samples: ShortArray, length: Int)
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val ring = ShortArray(RING_SIZE)
    private var ringWrite = 0
    private var ringTotal: Long = 0
    private val ringLock = Any()

    /** Starts capture. Returns false if the mic permission is missing or the recorder fails to start. */
    fun start(listener: Listener): Boolean {
        if (isRecording) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(CHUNK_SAMPLES * 4))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        this.record = record
        isRecording = true
        synchronized(ringLock) {
            ringWrite = 0
            ringTotal = 0
        }
        thread = Thread({
            val chunk = ShortArray(CHUNK_SAMPLES)
            record.startRecording()
            while (isRecording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(ringLock) {
                        for (i in 0 until read) {
                            ring[ringWrite] = chunk[i]
                            ringWrite = (ringWrite + 1) % RING_SIZE
                        }
                        ringTotal += read
                    }
                    listener.onChunk(chunk, read)
                } else if (read < 0) {
                    break
                }
            }
        }, "heygobi-audio").also { it.start() }
        return true
    }

    fun stop() {
        isRecording = false
        thread?.interrupt()
        thread = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
    }

    /** A copy of the last [seconds] of captured audio (fewer if not enough yet). */
    fun recentAudio(seconds: Float): ShortArray {
        val wanted = (SAMPLE_RATE * seconds).toInt().coerceAtMost(RING_SIZE)
        synchronized(ringLock) {
            val available = ringTotal.coerceAtMost(RING_SIZE.toLong()).toInt()
            val n = wanted.coerceAtMost(available)
            val out = ShortArray(n)
            var readPos = (ringWrite - n + RING_SIZE) % RING_SIZE
            for (i in 0 until n) {
                out[i] = ring[readPos]
                readPos = (readPos + 1) % RING_SIZE
            }
            return out
        }
    }

    /** RMS level of the last chunk window, 0.0-1.0 — used for the mic meter. */
    fun recentLevel(): Float {
        val audio = recentAudio(0.2f)
        if (audio.isEmpty()) return 0f
        var sum = 0.0
        for (s in audio) sum += (s / 32768.0) * (s / 32768.0)
        return kotlin.math.sqrt(sum / audio.size).toFloat().coerceIn(0f, 1f)
    }
}
