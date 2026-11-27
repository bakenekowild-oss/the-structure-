package com.heygobi.app.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq cloud brain ("Jarvis mode").
 *
 * The local Gemma 3 4B is too slow on this phone (a few tokens a second),
 * so when the user saves his own free Groq key, chat tries Groq first:
 * openai/gpt-oss-20b answers in seconds. Any Groq failure (no network,
 * timeout, 429, bad response) returns null and the chat falls back
 * silently to the on-device brain — chat never blocks on the network.
 *
 * The key is the user's own free key from console.groq.com. It is stored
 * encrypted in SecureStore, never logged, and sent only to api.groq.com
 * in the Authorization header. No key is ever hardcoded here.
 *
 * Uses plain HttpURLConnection + org.json, the same as the model downloads
 * (VoskManager) and the weather call (CommandEngine) — no new dependency.
 */
class GroqClient(private val context: Context, private val secureStore: SecureStore) {

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        /** Free-tier model: fast (~1000 tokens/sec). */
        const val MODEL = "openai/gpt-oss-20b"

        /**
         * Retried once if the main model fails for a model-side reason
         * (429, 5xx, empty reply). Never retried on a bad key (401) or a
         * network error — retrying those cannot help.
         */
        const val FALLBACK_MODEL = "openai/gpt-oss-120b"

        /** Short replies: fast, and Jarvis-like. */
        const val MAX_TOKENS = 256
        const val TEMPERATURE = 0.7

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
    }

    enum class TestResult { OK, NO_KEY, NO_NETWORK, BAD_KEY, FAILED }

    /** One HTTP attempt: the reply text (null on failure) and the HTTP code (-1 = no response). */
    private data class Attempt(val text: String?, val httpCode: Int)

    fun hasKey(): Boolean = savedKey() != null

    fun savedKey(): String? =
        secureStore.getString(SecureStore.KEY_GROQ_API_KEY)?.takeIf { it.isNotBlank() }

    fun saveKey(key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) {
            secureStore.remove(SecureStore.KEY_GROQ_API_KEY)
        } else {
            secureStore.putString(SecureStore.KEY_GROQ_API_KEY, clean)
        }
    }

    fun clearKey() {
        secureStore.remove(SecureStore.KEY_GROQ_API_KEY)
    }

    /**
     * Asks Groq for one reply to [messages] (system + history + user).
     * Returns the reply text, or null on any failure — no key, offline,
     * timeout, 429, bad response. The caller falls back to the local brain.
     */
    suspend fun chat(messages: List<ChatRepository.Message>): String? {
        val key = savedKey() ?: return null
        if (!isOnline()) return null
        val first = postOnce(key, messages, MODEL)
        if (first.text != null) return first.text
        if (first.httpCode > 0 && first.httpCode != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return postOnce(key, messages, FALLBACK_MODEL).text
        }
        return null
    }

    /**
     * Probes a key (the field value, or the saved key) with one tiny call.
     * Used by the Test button in Settings. Returns whether the key works.
     */
    suspend fun test(key: String): TestResult {
        val clean = key.trim()
        if (clean.isEmpty()) return TestResult.NO_KEY
        if (!isOnline()) return TestResult.NO_NETWORK
        val probe = listOf(
            ChatRepository.Message("system", SystemPrompt.JARVIS_TEXT),
            ChatRepository.Message("user", "Say OK.")
        )
        val attempt = postOnce(clean, probe, MODEL)
        return when {
            attempt.text != null -> TestResult.OK
            attempt.httpCode == HttpURLConnection.HTTP_UNAUTHORIZED -> TestResult.BAD_KEY
            else -> TestResult.FAILED
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** One POST to the Groq chat-completions endpoint. Never throws except on cancellation. */
    private suspend fun postOnce(
        key: String,
        messages: List<ChatRepository.Message>,
        model: String
    ): Attempt = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("model", model)
                val array = JSONArray()
                for (msg in messages) {
                    array.put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
                put("messages", array)
                put("max_tokens", MAX_TOKENS)
                put("temperature", TEMPERATURE)
            }.toString()
            conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@withContext Attempt(null, code)
            val response = conn.inputStream.bufferedReader().readText()
            val text = JSONObject(response)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
            if (text.isNullOrEmpty()) Attempt(null, code) else Attempt(text, code)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Attempt(null, -1)
        } finally {
            conn?.disconnect()
        }
    }
}
