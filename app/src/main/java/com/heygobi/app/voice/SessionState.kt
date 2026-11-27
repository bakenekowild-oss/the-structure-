package com.heygobi.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process session state shared by the WakeWordService and the
 * PasscodeActivity (same process, so a plain object is enough).
 * The passcode result is consumed once by the service.
 *
 * v1.4: the voice-command screen (VoiceCommandActivity) also lives here.
 * The service publishes the command session (listening / heard text /
 * result) and the activity renders it — so after the passcode the user
 * SEES the app listening instead of staring at a Setup screen.
 */
object SessionState {

    enum class PasscodeResult { OK, FAILED, TIMEOUT }

    private val _passcodeResult = MutableStateFlow<PasscodeResult?>(null)
    val passcodeResult: StateFlow<PasscodeResult?> = _passcodeResult.asStateFlow()

    fun setPasscodeResult(result: PasscodeResult) {
        _passcodeResult.value = result
    }

    fun resetPasscodeResult() {
        _passcodeResult.value = null
    }

    // --- Voice-command screen (v1.4) ---

    private val _commandListening = MutableStateFlow(false)
    val commandListening: StateFlow<Boolean> = _commandListening.asStateFlow()

    private val _commandHeard = MutableStateFlow<String?>(null)
    val commandHeard: StateFlow<String?> = _commandHeard.asStateFlow()

    private val _commandResult = MutableStateFlow<String?>(null)
    val commandResult: StateFlow<String?> = _commandResult.asStateFlow()

    fun setCommandListening(listening: Boolean) {
        _commandListening.value = listening
    }

    fun setCommandHeard(text: String?) {
        _commandHeard.value = text
    }

    fun setCommandResult(text: String?) {
        _commandResult.value = text
    }

    fun resetCommandUi() {
        _commandHeard.value = null
        _commandResult.value = null
    }
}
