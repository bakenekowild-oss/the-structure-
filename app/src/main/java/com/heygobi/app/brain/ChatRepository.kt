package com.heygobi.app.brain

import com.heygobi.app.data.ChatDao
import com.heygobi.app.data.ChatMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat with the local brain.
 *
 * Keeps the conversation in Room, trims history so the prompt always fits
 * the context window, and streams the reply token by token into the UI.
 * The brain is slow on this phone (a few seconds per reply) — that is an
 * honest limit, and it only affects chat answers: voice commands never
 * wait on the brain (see CommandEngine).
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val bridge: LlamaBridge
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

    /** Sends a user message and streams the brain's reply. Returns false if the brain is not loaded. */
    suspend fun send(userText: String): Boolean {
        if (!bridge.isLoaded()) return false
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return false
        chatDao.insert(ChatMessageEntity(role = "user", content = trimmed, timestamp = System.currentTimeMillis()))

        val history = buildHistory(trimmed)
        val roles = history.map { it.role }.toTypedArray()
        val contents = history.map { it.content }.toTypedArray()

        _isReplying.value = true
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
        // Show an empty assistant bubble immediately so the UI feels alive.
        _messages.value = _messages.value + Message("assistant", "")
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

    suspend fun clearHistory() {
        chatDao.clearAll()
    }

    /**
     * Builds the message list for this turn: system prompt + trimmed history
     * + the new user message. History is trimmed from the oldest turn until
     * the estimated prompt fits the context with room for the reply.
     */
    private fun buildHistory(newUserText: String): List<Message> {
        val budgetChars =
            ((SystemPrompt.CONTEXT_SIZE - SystemPrompt.MAX_TOKENS - 64) * SystemPrompt.CHARS_PER_TOKEN).toInt()
        val all = _messages.value.filter { it.content.isNotEmpty() }
        val kept = ArrayDeque<Message>()
        var used = SystemPrompt.TEXT.length + newUserText.length
        for (msg in all.asReversed()) {
            if (used + msg.content.length > budgetChars) break
            kept.addFirst(msg)
            used += msg.content.length
        }
        val out = mutableListOf(Message("system", SystemPrompt.TEXT))
        out.addAll(kept)
        out.add(Message("user", newUserText))
        return out
    }
}
