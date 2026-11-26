package com.heygobi.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process session state shared by the WakeWordService and the
 * PasscodeActivity (same process, so a plain object is enough).
 * The passcode result is consumed once by the service.
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
}
