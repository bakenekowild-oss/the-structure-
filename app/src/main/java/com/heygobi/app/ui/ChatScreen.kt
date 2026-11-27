package com.heygobi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.brain.ChatRepository
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chat with the brain (v1: button-tap + mic chat).
 * The brain loads automatically when the model has been imported.
 * Since v1.2, a saved Groq key ("Jarvis mode") answers first — in seconds —
 * and the on-device brain is the fallback; without a key the local brain
 * answers alone. Local replies stream in token by token; the local brain
 * is slow on this phone (a few seconds per reply).
 */
@Composable
fun ChatScreen(app: HeyGobiApp, modifier: Modifier = Modifier) {
    val messages by app.chatRepository.messages.collectAsState()
    val isReplying by app.chatRepository.isReplying.collectAsState()
    val loadProgress by app.llamaBridge.loadProgress.collectAsState()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var brainState by remember { mutableStateOf(BrainState.CHECKING) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Chat is allowed when the local brain is ready, or when a Groq key is
    // saved (Jarvis mode) — Groq answers without the local model loaded.
    val canChat = brainState == BrainState.READY || app.chatRepository.hasGroqKey()

    LaunchedEffect(Unit) {
        val path = app.secureStore.getString(SecureStore.KEY_BRAIN_MODEL_PATH)
        if (path.isNullOrBlank() || !File(path).exists()) {
            brainState = BrainState.NOT_LOADED
            return@LaunchedEffect
        }
        if (app.llamaBridge.isLoaded()) {
            brainState = BrainState.READY
            return@LaunchedEffect
        }
        brainState = BrainState.LOADING
        val ok = app.llamaBridge.loadModel(path)
        brainState = if (ok) BrainState.READY else BrainState.FAILED
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || !canChat) return
        input = ""
        scope.launch { app.chatRepository.send(clean) }
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        when (brainState) {
            BrainState.CHECKING, BrainState.LOADING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Brain loading… ${(loadProgress * 100).toInt()}%")
                }
                LinearProgressIndicator(
                    progress = { loadProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            BrainState.NOT_LOADED, BrainState.FAILED -> {
                if (canChat) {
                    Text(
                        "Brain ready — Jarvis mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (brainState == BrainState.NOT_LOADED) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Brain is not loaded. Import the model in the Setup tab first " +
                            "(copy it from Termux to Downloads, then import it here).",
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Brain failed to load. Check the model file in Setup and try again.",
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            BrainState.READY -> {
                Text(
                    "Brain ready — Gemma 3 4B (on-device)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
            if (isReplying) {
                item {
                    Text("Gobi is typing…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") },
                enabled = canChat && !isReplying,
                singleLine = false,
                maxLines = 4
            )
            IconButton(
                onClick = {
                    if (listening || !canChat) return@IconButton
                    listening = true
                    scope.launch {
                        val heard = VoiceInputHelper(app).listenOnce(12_000)
                        listening = false
                        if (!heard.isNullOrBlank()) send(heard)
                    }
                },
                enabled = canChat && !isReplying
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "Speak")
            }
            IconButton(
                onClick = { send(input) },
                enabled = canChat && !isReplying && input.isNotBlank()
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
        if (listening) {
            Text("Listening… speak now", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private enum class BrainState { CHECKING, LOADING, READY, NOT_LOADED, FAILED }

@Composable
private fun MessageBubble(msg: ChatRepository.Message) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = msg.content,
                modifier = Modifier.padding(10.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
