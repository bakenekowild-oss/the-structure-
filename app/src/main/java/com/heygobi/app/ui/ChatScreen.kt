package com.heygobi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.R
import com.heygobi.app.brain.ChatRepository
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chat with the brain (v1: button-tap + mic chat).
 * Since v1.2, a saved Groq key ("Jarvis mode") answers first — in seconds —
 * and the on-device brain is the fallback; without a key the local brain
 * answers alone. Local replies stream in token by token; the local brain
 * is slow on this phone (a few seconds per reply).
 * Since v1.3, with a Groq key saved the local model is NOT loaded when
 * chat opens: loading the 2.8 GB model on every open ran the phone out of
 * memory and killed the app. It loads lazily only if a Groq request fails
 * (see ChatRepository).
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

    // The Groq key is read live on every composition, so the header always
    // shows which brain is actually answering: Jarvis mode when a key is
    // saved, the on-device Gemma line only when the local brain is the
    // active brain (no key saved).
    val hasGroqKey = app.chatRepository.hasGroqKey()

    // Chat is allowed when the local brain is ready, or when a Groq key is
    // saved (Jarvis mode) — Groq answers without the local model loaded.
    val canChat = brainState == BrainState.READY || brainState == BrainState.JARVIS || hasGroqKey

    LaunchedEffect(Unit) {
        // Jarvis mode (v1.3): Groq answers chat, so the 2.8 GB local model
        // is NOT loaded here at all. It loads lazily only if a Groq
        // request fails (see ChatRepository.ensureLocalLoaded).
        if (app.chatRepository.hasGroqKey()) {
            brainState = BrainState.JARVIS
            return@LaunchedEffect
        }
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

    // One status line for the header, derived from the same state as before.
    val statusText = when (brainState) {
        BrainState.CHECKING, BrainState.LOADING ->
            "Brain loading… ${(loadProgress * 100).toInt()}%"
        BrainState.NOT_LOADED, BrainState.FAILED ->
            if (canChat) "Brain ready — Jarvis mode"
            else if (brainState == BrainState.NOT_LOADED) "Brain is not loaded"
            else "Brain failed to load"
        BrainState.READY ->
            if (hasGroqKey) "Brain ready — Jarvis mode"
            else "Brain ready — Gemma 3 4B (on-device)"
        BrainState.JARVIS ->
            if (hasGroqKey) "Brain ready — Jarvis mode"
            else "Brain is not loaded"
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Brand header: the Structure mark + name + live brain status.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "The Structure",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(30.dp)
                )
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("The Structure", style = MaterialTheme.typography.titleLarge)
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (brainState == BrainState.CHECKING || brainState == BrainState.LOADING) {
            LinearProgressIndicator(
                progress = { loadProgress },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
        if ((brainState == BrainState.NOT_LOADED || brainState == BrainState.FAILED) && !canChat) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    if (brainState == BrainState.NOT_LOADED) {
                        "Brain is not loaded. Import the model in the Setup tab first " +
                        "(copy it from Termux to Downloads, then import it here)."
                    } else {
                        "Brain failed to load. Check the model file in Setup and try again."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
            if (isReplying) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "The Structure is typing…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") },
                enabled = canChat && !isReplying,
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
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
            FilledIconButton(
                onClick = { send(input) },
                enabled = canChat && !isReplying && input.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
        if (listening) {
            Text(
                "Listening… speak now",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private enum class BrainState { CHECKING, LOADING, READY, NOT_LOADED, FAILED, JARVIS }

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
                else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 6.dp,
                topEnd = if (isUser) 6.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
