package com.heygobi.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Log-mel filterbank features, exactly as the WeSpeaker ECAPA-TDNN speaker
 * model expects them: 80 mel bins, 25 ms frame, 10 ms hop, 16 kHz,
 * per-utterance mean normalisation.
 *
 * Implemented from scratch (no extra dependency): pre-emphasis, Hamming
 * window, radix-2 FFT, triangular mel filters, log, mean norm.
 */
object MelFeatures {

    const val SAMPLE_RATE = 16000
    const val MEL_BINS = 80
    const val FRAME_MS = 25
    const val HOP_MS = 10
    const val F_MIN_HZ = 20f
    const val F_MAX_HZ = 8000f

    private val frameLen = SAMPLE_RATE * FRAME_MS / 1000 // 400
    private val hopLen = SAMPLE_RATE * HOP_MS / 1000 // 160
    private const val FFT_SIZE = 512
    private val hamming = FloatArray(frameLen) { i ->
        (0.54f - 0.46f * cos(2.0 * PI * i / (frameLen - 1)).toFloat())
    }
    private val melFilters: Array<FloatArray> = buildMelFilters()

    private fun hzToMel(hz: Float): Float = 2595f * kotlin.math.log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    private fun buildMelFilters(): Array<FloatArray> {
        val nBins = FFT_SIZE / 2 + 1
        val melMin = hzToMel(F_MIN_HZ)
        val melMax = hzToMel(F_MAX_HZ)
        val melPoints = FloatArray(MEL_BINS + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (MEL_BINS + 1))
        }
        val hzPerBin = SAMPLE_RATE.toFloat() / FFT_SIZE
        val filters = Array(MEL_BINS) { FloatArray(nBins) }
        for (m in 0 until MEL_BINS) {
            val fLeft = melPoints[m]
            val fCenter = melPoints[m + 1]
            val fRight = melPoints[m + 2]
            for (k in 0 until nBins) {
                val f = k * hzPerBin
                val w = when {
                    f in fLeft..fCenter -> (f - fLeft) / (fCenter - fLeft)
                    f in fCenter..fRight -> (fRight - f) / (fRight - fCenter)
                    else -> 0f
                }
                filters[m][k] = w
            }
        }
        return filters
    }

    /** In-place iterative radix-2 FFT. [re] and [im] must have length FFT_SIZE. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wpr = 1f
                var wpi = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wpr - im[i + k + len / 2] * wpi
                    val vi = re[i + k + len / 2] * wpi + im[i + k + len / 2] * wpr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = wpr * wr - wpi * wi
                    wpi = wpr * wi + wpi * wr
                    wpr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Computes log-mel features for 16 kHz PCM.
     * Returns an array of frames; each frame is [MEL_BINS] floats.
     * Frames shorter than the full audio are produced; at least one frame
     * is returned for audio shorter than a frame (zero-padded).
     */
    fun computeLogMel(pcm: ShortArray): Array<FloatArray> {
        val nFrames = if (pcm.size <= frameLen) 1 else (pcm.size - frameLen) / hopLen + 1
        val feats = Array(nFrames) { FloatArray(MEL_BINS) }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (f in 0 until nFrames) {
            val start = f * hopLen
            var prev = 0f
            for (i in 0 until frameLen) {
                val idx = start + i
                val sample = if (idx < pcm.size) pcm[idx] / 32768f else 0f
                // Pre-emphasis (0.97) + Hamming window.
                val emphasised = sample - 0.97f * prev
                prev = sample
                re[i] = emphasised * hamming[i]
                im[i] = 0f
            }
            for (i in frameLen until FFT_SIZE) {
                re[i] = 0f
                im[i] = 0f
            }
            fft(re, im)
            val nBins = FFT_SIZE / 2 + 1
            for (m in 0 until MEL_BINS) {
                var energy = 0f
                val filter = melFilters[m]
                for (k in 0 until nBins) {
                    val power = re[k] * re[k] + im[k] * im[k]
                    energy += filter[k] * power
                }
                feats[f][m] = ln(energy.coerceAtLeast(1e-10f))
            }
        }
        // Per-utterance mean normalisation (per mel bin, over time).
        for (m in 0 until MEL_BINS) {
            var mean = 0f
            for (f in 0 until nFrames) mean += feats[f][m]
            mean /= nFrames
            for (f in 0 until nFrames) feats[f][m] -= mean
        }
        return feats
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "embedding size mismatch" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }
}
