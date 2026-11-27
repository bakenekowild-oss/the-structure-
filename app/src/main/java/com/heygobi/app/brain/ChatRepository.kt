package com.heygobi.app.brain

import com.heygobi.app.data.ChatDao
import com.heygobi.app.data.ChatMessageEntity
import com.heygobi.app.security.SecureStore
import com.heygobi.app.voice.CommandEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chat with the brain.
 *
 * Keeps the conversation in Room, trims history so the prompt always fits
 * the context window, and streams the local reply token by token into the UI.
 * Since v1.2 ("Jarvis mode") a saved Groq key is tried first: Groq answers
 * in seconds, and any Groq failure falls back silently to the on-device
 * Gemma brain. The local brain is slow on this phone (a few seconds per
 * reply) — that is an honest limit, and it only affects chat answers:
 * voice commands never wait on the brain (see CommandEngine).
 *
 * Since v1.3 the local brain is loaded LAZILY: with a Groq key saved it
 * is not loaded when chat opens — loading the 2.8 GB model on every open
 * ran the phone out of memory and killed the app. It is loaded here, on
 * demand, only when a Groq request fails (or no key is saved at all).
 *
 * Since v1.4 typed chat runs through the CommandEngine first: a typed
 * command ("open youtube") executes on the phone instead of asking the
 * brain for a link. Only plain chat goes to the brain.
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val bridge: LlamaBridge,
    private val groqClient: GroqClient,
    private val secureStore: SecureStore,
    private val commandEngineProvider: (() -> CommandEngine?)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class Message(val role: String, val content: String)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isReplying = MutableStateFlow(false)
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    init {
        scope.launch {
            chatDao.getAll().collect { entities ->
                _messages.value = entities.map { Message(it.role, it.content) }
            }
        }
    }

    /** True when a Groq key is saved — chat can then run without the local brain loaded. */
    fun hasGroqKey(): Boolean = groqClient.hasKey()

    /**
     * Loads the local brain on demand (lazy). True when it is ready.
     * Called only when Groq failed or no key is saved — the 2.8 GB model
     * is never loaded eagerly, so opening chat cannot run the phone out
     * of memory. A missing or deleted model file is a clean false, never
     * a crash.
     */
    private suspend fun ensureLocalLoaded(): Boolean {
        if (bridge.isLoaded()) return true
        val path = secureStore.getString(SecureStore.KEY_BRAIN_MODEL_PATH)
        if (path.isNullOrBlank() || !File(path).exists()) return false
        return try {
            bridge.loadModel(path)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sends a user message and gets the brain's reply. Returns false if no
     * brain is available (no Groq key and the local brain not loaded).
     *
     * Jarvis mode (v1.2): when a Groq key is saved, Groq answers first —
     * seconds instead of the local model's slow generation. Any Groq
     * failure (offline, timeout, 429) falls back silently to the local
     * Gemma brain. Without a key, the local brain is used exactly as
     * before. The local reply still streams token by token.
     */
    suspend fun send(userText: String): Boolean {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return false
        chatDao.insert(ChatMessageEntity(role = "user", content = trimmed, timestamp = System.currentTimeMillis()))

        // v1.4: typed commands run on the phone first — "open youtube"
        // opens YouTube instead of asking the brain for a link. Commands
        // never need a brain, so this runs before the brain check below.
        val commandResult = try {
            commandEngineProvider?.invoke()?.tryCommand(trimmed)
        } catch (_: Exception) {
            null
        }
        if (commandResult != null) {
            val replyText = humanizeCommandResult(commandResult)
            _messages.value = _messages.value + Message("assistant", replyText)
            chatDao.insert(
                ChatMessageEntity(role = "assistant", content = replyText, timestamp = System.currentTimeMillis())
            )
            return true
        }

        if (!groqClient.hasKey() && !bridge.isLoaded()) return false

        _isReplying.value = true
        // Show an empty assistant bubble immediately so the UI feels alive.
        _messages.value = _messages.value + Message("assistant", "")

        // Jarvis mode: try Groq first. A null reply means "fall back".
        val groqReply = groqClient.chat(buildHistory(trimmed, SystemPrompt.JARVIS_TEXT))
        if (groqReply != null) {
            val finalText = groqReply.trim()
            if (finalText.isNotEmpty()) {
                // Drop the empty bubble only if it is still the last
                // message — the Room collector may have re-synced the list
                // from the database while Groq was answering.
                val current = _messages.value
                val withoutBubble = if (current.isNotEmpty() && current.last().role == "assistant") {
                    current.dropLast(1)
                } else {
                    current
                }
                _messages.value = withoutBubble + Message("assistant", finalText)
                chatDao.insert(
                    ChatMessageEntity(role = "assistant", content = finalText, timestamp = System.currentTimeMillis())
                )
                _isReplying.value = false
                return true
            }
            // Groq answered blank: fall through to the local brain.
        }

        if (!ensureLocalLoaded()) {
            // Groq failed (or answered blank) and the local brain could
            // not be loaded either: say so plainly instead of leaving a
            // blank bubble.
            val notice = "Network ledu, on-device brain kuda ready ga ledu — " +
                "konchem aagi malli try cheyyandi, Guruvu garu."
            val current = _messages.value
            val withoutBubble = if (current.isNotEmpty() && current.last().role == "assistant") {
                current.dropLast(1)
            } else {
                current
            }
            _messages.value = withoutBubble + Message("assistant", notice)
            chatDao.insert(
                ChatMessageEntity(role = "assistant", content = notice, timestamp = System.currentTimeMillis())
            )
            _isReplying.value = false
            return false
        }

        val history = buildHistory(trimmed, SystemPrompt.TEXT)
        val roles = history.map { it.role }.toTypedArray()
        val contents = history.map { it.content }.toTypedArray()

        val reply = StringBuilder()
        val job = scope.launch {
            bridge.tokens.collect { token ->
                reply.append(token)
                val current = _messages.value
                val updated = if (current.isNotEmpty() && current.last().role == "assistant") {
                    current.dropLast(1) + Message("assistant", reply.toString())
                } else {
                    current + Message("assistant", reply.toString())
                }
                _messages.value = updated
            }
        }
        val result = bridge.generate(roles, contents)
        job.cancel()
        _isReplying.value = false

        val finalText = reply.toString().trim()
        if (result >= 0 && finalText.isNotEmpty()) {
            chatDao.insert(
                ChatMessageEntity(role = "assistant", content = finalText, timestamp = System.currentTimeMillis())
            )
        } else if (finalText.isEmpty()) {
            // Generation produced nothing (e.g. prompt too long): drop the
            // empty bubble so the chat does not show a blank reply.
            _messages.value = _messages.value.dropLast(1)
        }
        return result >= 0
    }

    fun stopReply() {
        bridge.stop()
    }

    /**
     * v1.4: turns a CommandEngine action string ("open:com.foo",
     * "fail:app_not_found", "stop", ...) into one honest chat line.
     * The engine already speaks the full confirmation aloud.
     */
    private fun humanizeCommandResult(action: String): String = when {
        action.startsWith("open:") -> "Done — app open chesa."
        action == "stop" -> "Sare."
        action.startsWith("fail:") -> "Sorry, aa pani cheyyalekapoyanu."
        action.startsWith("screen:") -> "Screen chusa."
        else -> "Done."
    }

    suspend fun clearHistory() {
        chatDao.clearAll()
    }

    /**
     * Builds the message list for this turn: system prompt + trimmed history
     * + the new user message. History is trimmed from the oldest turn until
     * the estimated prompt fits the context with room for the reply.
     * [systemText] is the Jarvis prompt for Groq, the partner prompt for
     * the local brain.
     */
    private fun buildHistory(newUserText: String, systemText: String): List<Message> {
        val budgetChars =
            ((SystemPrompt.CONTEXT_SIZE - SystemPrompt.MAX_TOKENS - 64) * SystemPrompt.CHARS_PER_TOKEN).toInt()
        val all = _messages.value.filter { it.content.isNotEmpty() }
        val kept = ArrayDeque<Message>()
        var used = systemText.length + newUserText.length
        for (msg in all.asReversed()) {
            if (used + msg.content.length > budgetChars) break
            kept.addFirst(msg)
            used += msg.content.length
        }
        val out = mutableListOf(Message("system", systemText))
        out.addAll(kept)
        out.add(Message("user", newUserText))
        return out
    }
}
