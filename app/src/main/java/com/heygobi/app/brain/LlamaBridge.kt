package com.heygobi.app.brain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * JNI bridge to llama.cpp (libheygobi.so).
 *
 * One instance per app (created in HeyGobiApp). The native side holds a
 * global reference to this instance and calls back into onTokenGenerated /
 * onLoadProgress / onGenerationFinished from the generation thread.
 * Those callbacks only append to flows, which are thread-safe.
 *
 * Generation runs on Dispatchers.Default and blocks that thread; tokens
 * stream back through [tokens]. Call [stop] to cancel a running generation.
 */
class LlamaBridge(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("heygobi")
        }
    }

    private val _tokens = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val tokens: SharedFlow<String> = _tokens.asSharedFlow()

    private val _loadProgress = MutableStateFlow(0f)
    val loadProgress: StateFlow<Float> = _loadProgress.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    init {
        nativeInit()
    }

    fun isLoaded(): Boolean = nativeIsLoaded()

    /**
     * Loads the GGUF model at [path]. Runs on a background thread; progress
     * (0.0-1.0) is reported through [loadProgress].
     * Returns false if the file cannot be loaded.
     * Loading twice is a no-op that returns true: a second native load of
     * the same 2.8 GB model would double the RAM use and can kill the app.
     */
    suspend fun loadModel(
        path: String,
        ctxSize: Int = SystemPrompt.CONTEXT_SIZE,
        threads: Int = SystemPrompt.THREADS
    ): Boolean = withContext(Dispatchers.Default) {
        if (isLoaded()) return@withContext true
        _loadProgress.value = 0f
        val ok = nativeLoadModel(path, ctxSize, threads)
        if (ok) _loadProgress.value = 1f
        ok
    }

    /**
     * Generates one assistant reply for the given chat history.
     * [roles] and [contents] are parallel arrays, e.g. ("system", "user",
     * "assistant", "user"). The model's own chat template is applied in
     * native code. Returns the number of generated tokens, or a negative
     * error code from nativeGenerate (-1 not loaded, -2 prompt too long,
     * -3 decode error).
     */
    suspend fun generate(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int = SystemPrompt.MAX_TOKENS,
        temperature: Float = SystemPrompt.TEMPERATURE,
        topP: Float = SystemPrompt.TOP_P
    ): Int = withContext(Dispatchers.Default) {
        _generating.value = true
        try {
            nativeGenerate(roles, contents, maxTokens, temperature, topP)
        } finally {
            _generating.value = false
        }
    }

    fun stop() {
        nativeStop()
    }

    fun free() {
        nativeFree()
    }

    fun tokenCount(text: String): Int =
        if (isLoaded()) nativeTokenCount(text) else -1

    // Called from native code (llama_jni.cpp) on the generation thread.
    fun onTokenGenerated(token: String) {
        _tokens.tryEmit(token)
    }

    // Called from native code during model load.
    fun onLoadProgress(progress: Float) {
        _loadProgress.value = progress.coerceIn(0f, 1f)
    }

    // Called from native code when generation ends (finished, stopped or EOG).
    fun onGenerationFinished() {
        _generating.value = false
    }

    private external fun nativeInit()
    private external fun nativeLoadModel(path: String, ctxSize: Int, threads: Int): Boolean
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeGenerate(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): Int
    private external fun nativeStop()
    private external fun nativeFree()
    private external fun nativeTokenCount(text: String): Int
}
