package com.heygobi.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Base64
import com.heygobi.app.audio.MelFeatures
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Voice lock — speaker verification.
 *
 * Uses the WeSpeaker ECAPA-TDNN model (wespeaker-ecapa-tdnn512-LM,
 * voxceleb_ECAPA512_LM.onnx, 24.9 MB, CC-BY-4.0), run on-device with ONNX
 * Runtime. The model is downloaded once on first run from Hugging Face and
 * its SHA-256 is verified before use; a bad file is deleted, never used.
 *
 * How it works:
 * - Enrolment: the user says "Hey Gobi" 3 times. Each recording becomes a
 *   192-dim embedding (80-dim log-mel -> ECAPA-TDNN). The embeddings are
 *   averaged and L2-normalised into one centroid, stored encrypted.
 * - Verification: the wake utterance itself ("Hey Gobi") is embedded and
 *   compared with the centroid by cosine similarity. A score at or above
 *   the threshold (default 0.65) means "this is him".
 *
 * This is a similarity check, not secure authentication: it makes it very
 * hard for another voice to pass, but no voice system is perfect. That is
 * why the passcode gate sits behind it — two independent gates, voice AND
 * passcode, before the phone is controlled.
 */
class SpeakerVerifier(
    private val context: Context,
    private val store: SecureStore
) {
    companion object {
        const val MODEL_URL =
            "https://huggingface.co/Wespeaker/wespeaker-ecapa-tdnn512-LM/resolve/main/voxceleb_ECAPA512_LM.onnx"
        const val MODEL_FILE_NAME = "voxceleb_ECAPA512_LM.onnx"
        const val MODEL_SHA256 = "d71b85d9b48058ef68004f04f1b78acebefb9dfcf542e19b976a12a5ad1f10b0"
        const val MODEL_BYTES = 24_900_000L // approximate; the SHA-256 is the real check
        const val DEFAULT_THRESHOLD = 0.65f
        const val EMBEDDING_DIM = 192
        const val ENROLLMENT_SAMPLES = 3
        const val VERIFY_SECONDS = 2.5f
    }

    private val modelFile: File get() = File(File(context.filesDir, "speaker"), MODEL_FILE_NAME)

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var outputName: String? = null

    fun isModelReady(): Boolean =
        modelFile.exists() && modelFile.length() > MODEL_BYTES / 2

    fun isEnrolled(): Boolean = store.getBoolean(SecureStore.KEY_VOICE_ENROLLED, false)

    fun threshold(): Float = store.getFloat(SecureStore.KEY_VOICE_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(value: Float) {
        store.putFloat(SecureStore.KEY_VOICE_THRESHOLD, value.coerceIn(0.5f, 0.85f))
    }

    /**
     * Downloads the speaker model and verifies its SHA-256.
     * Returns false on any failure and leaves no bad file behind.
     */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = modelFile.parentFile!!
            dir.mkdirs()
            val tmp = File(dir, "$MODEL_FILE_NAME.tmp")
            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
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
            if (sha256(tmp) != MODEL_SHA256) {
                tmp.delete()
                return@withContext false
            }
            if (modelFile.exists()) modelFile.delete()
            if (!tmp.renameTo(modelFile)) {
                tmp.delete()
                return@withContext false
            }
            onProgress(1f)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) {
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadSession(): Boolean {
        if (session != null) return true
        if (!isModelReady()) return false
        return try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val opts = OrtSession.SessionOptions()
            val sess = env.createSession(modelFile.absolutePath, opts)
            // Read the real tensor names from the model instead of assuming.
            inputName = sess.inputInfo.keys.firstOrNull()
            outputName = sess.outputInfo.keys.firstOrNull()
            if (inputName == null || outputName == null) {
                sess.close()
                return false
            }
            session = sess
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Embeds 16 kHz PCM into a 192-dim L2-normalised speaker embedding.
     * Returns null if the model is not ready or inference fails.
     */
    fun embed(pcm: ShortArray): FloatArray? {
        if (!loadSession()) return null
        val session = this.session ?: return null
        val inputName = this.inputName ?: return null
        val outputName = this.outputName ?: return null
        return try {
            val feats = MelFeatures.computeLogMel(pcm)
            if (feats.isEmpty()) return null
            val frames = feats.size
            val flat = FloatArray(frames * MelFeatures.MEL_BINS)
            for (f in feats.indices) {
                System.arraycopy(feats[f], 0, flat, f * MelFeatures.MEL_BINS, MelFeatures.MEL_BINS)
            }
            val buffer = FloatBuffer.wrap(flat)
            val input = OnnxTensor.createTensor(
                ortEnv,
                buffer,
                longArrayOf(1, frames.toLong(), MelFeatures.MEL_BINS.toLong())
            )
            input.use {
                val outputs = session.run(mapOf(inputName to it))
                outputs.use { result ->
                    val out = result[outputName]?.value as? Array<*>
                    val row = out?.get(0) as? FloatArray ?: return null
                    MelFeatures.l2Normalize(row)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Enrols the voice from [samples] (usually 3 recordings of "Hey Gobi").
     * Returns the mean intra-sample cosine similarity (enrolment quality,
     * 0.0-1.0), or a negative value on failure. The centroid is stored
     * encrypted; the raw recordings are never stored.
     */
    fun enroll(samples: List<ShortArray>): Float {
        if (samples.size < 2) return -1f
        val embeddings = samples.mapNotNull { embed(it) }
        if (embeddings.size < 2) return -1f
        var quality = 0f
        var pairs = 0
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                quality += MelFeatures.cosineSimilarity(embeddings[i], embeddings[j])
                pairs++
            }
        }
        quality = if (pairs > 0) quality / pairs else 0f
        val centroid = FloatArray(EMBEDDING_DIM)
        for (emb in embeddings) {
            for (i in centroid.indices) centroid[i] += emb[i]
        }
        val normalised = MelFeatures.l2Normalize(centroid)
        val encoded = Base64.encodeToString(
            normalised.fold(ByteArray(0)) { acc, f ->
                val bits = java.lang.Float.floatToIntBits(f)
                acc + byteArrayOf(
                    (bits shr 24).toByte(), (bits shr 16).toByte(),
                    (bits shr 8).toByte(), bits.toByte()
                )
            },
            Base64.NO_WRAP
        )
        store.putString(SecureStore.KEY_VOICE_CENTROID, encoded)
        store.putBoolean(SecureStore.KEY_VOICE_ENROLLED, true)
        return quality
    }

    /**
     * Verifies 16 kHz PCM against the enrolled voice.
     * Returns the cosine similarity (0.0-1.0), or a negative value if
     * verification is impossible (not enrolled, model missing, failure).
     */
    fun verify(pcm: ShortArray): Float {
        if (!isEnrolled()) return -1f
        val centroid = loadCentroid() ?: return -1f
        val embedding = embed(pcm) ?: return -1f
        return MelFeatures.cosineSimilarity(embedding, centroid)
    }

    fun isMatch(score: Float): Boolean = score >= threshold()

    fun clearEnrollment() {
        store.remove(SecureStore.KEY_VOICE_CENTROID)
        store.putBoolean(SecureStore.KEY_VOICE_ENROLLED, false)
    }

    private fun loadCentroid(): FloatArray? {
        val encoded = store.getString(SecureStore.KEY_VOICE_CENTROID) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size != EMBEDDING_DIM * 4) return null
            FloatArray(EMBEDDING_DIM) { i ->
                val bits = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i * 4 + 3].toInt() and 0xFF)
                java.lang.Float.intBitsToFloat(bits)
            }
        } catch (_: Exception) {
            null
        }
    }
}
