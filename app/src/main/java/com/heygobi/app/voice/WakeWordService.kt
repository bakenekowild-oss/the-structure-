package com.heygobi.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.R
import com.heygobi.app.audio.AudioCapture
import com.heygobi.app.control.GobiAccessibilityService
import com.heygobi.app.control.ScreenLockUnlocker
import com.heygobi.app.data.CommandLogEntity
import com.heygobi.app.security.SecureStore
import com.heygobi.app.ui.MainActivity
import com.heygobi.app.ui.PasscodeActivity
import com.heygobi.app.ui.VoiceCommandActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Always-on "The Structure" listening.
 *
 * Flow (all on-device, all offline):
 *   1. Vosk listens in grammar mode for "The Structure" (tiny model, low CPU).
 *      "the" is a weak word that Vosk often drops or reduces to "da"/"de",
 *      so the grammar also accepts "da structure" and the bare word
 *      "structure". Full listening is the recommended mode for the same
 *      reason: the recognizer then hears everything and the app looks for
 *      the phrase in the partial results. "structure" is a common word,
 *      so false wakes are more likely than with a made-up word — the
 *      voiceprint and passcode gates still protect the phone.
 *   2. On wake: the exact wake utterance is taken from the audio ring
 *      buffer and checked against the enrolled voiceprint (ECAPA-TDNN).
 *      A stranger saying the same words is silently ignored — nothing is
 *      spoken, nothing opens.
 *   3. On voice match: the passcode screen appears over the lock screen
 *      and asks for the app passcode.
 *   4. On passcode OK: the screen lock is opened with the stored lock
 *      credential (accessibility service), and one voice command is
 *      listened for and executed on the fast local path — never waiting
 *      on the big brain.
 *   5. Back to listening.
 *
 * Battery: the brain stays unloaded while listening; only the small Vosk
 * spotter runs. A partial wake lock is held while the service runs so the
 * phone can wake from screen-off listening — that is the honest cost of
 * "wake from anywhere". Turn the service off from the notification or the
 * app to stop listening.
 */
class WakeWordService : Service(), VoiceIO {

    companion object {
        private const val TAG = "HeyGobiWake"
        const val CHANNEL_ID = "heygobi_listening"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.heygobi.app.STOP_LISTENING"
        const val PASSCODE_TIMEOUT_MS = 30_000L
        const val COMMAND_TIMEOUT_MS = 12_000L
        const val CONFIRM_TIMEOUT_MS = 8_000L
        // v1.4: a wake whose voice is CLOSE to his (score >= this) falls
        // back to the passcode instead of dying silently — a short wake
        // utterance scores lower than the long enrolment samples, so a
        // near-miss is almost always him. Far below this it is noise or
        // a stranger and stays a silent reject. At most one fallback
        // prompt per cooldown so a TV cannot spam passcode screens.
        private const val FALLBACK_MIN_SCORE = 0.40f
        private const val FALLBACK_COOLDOWN_MS = 30_000L
        // v1.4.1: the mic hears the app's own "Cheppandi" prompt — ignore
        // anything recognised in this window after listening starts, or
        // the session ends on its own echo before he can speak.
        private const val ECHO_GRACE_MS = 2_000L
        // v1.4.4: word-level confidence floor for a command final — below
        // this, Vosk mumbled noise, not his words; keep listening.
        private const val MIN_COMMAND_CONFIDENCE = 0.30f

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** True while the service is alive. Read by the setup screen. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private enum class State { LISTENING, VERIFYING, WAITING_PASSCODE, COMMAND, STOPPED }

    private lateinit var app: HeyGobiApp
    private lateinit var capture: AudioCapture
    private lateinit var commandEngine: CommandEngine

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var state = State.LISTENING

    @Volatile
    private var lastCommandText: String? = null

    @Volatile
    private var lastFallbackPromptMs: Long = 0L

    // v1.4.3: the floating voice panel over other apps (null when the
    // full-screen activity is used instead, or no panel is showing).
    private var voiceOverlay: VoiceOverlay? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        app = applicationContext as HeyGobiApp
        capture = AudioCapture(this)
        commandEngine = CommandEngine(this, app)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyGobi:Listen").apply {
            acquire()
        }
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        state = State.STOPPED
        isRunning = false
        try {
            capture.stop()
        } catch (_: Exception) {
        }
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        try {
            model?.close()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Listening
    // ------------------------------------------------------------------

    private fun startListening() {
        val vosk = app.voskManager
        if (!vosk.isModelReady()) {
            Log.e(TAG, "Vosk model not ready; service cannot listen")
            app.ttsEngine.speak("Voice model download cheyyaledhu. App lo setup complete cheyyandi.")
            stopSelf()
            return
        }
        model = vosk.createModel()
        if (model == null) {
            Log.e(TAG, "Vosk model failed to load")
            stopSelf()
            return
        }
        recognizer = vosk.createRecognizer(model!!, wakeGrammar())
        state = State.LISTENING
        val started = capture.start { chunk, length ->
            onAudioChunk(chunk, length)
        }
        if (!started) {
            Log.e(TAG, "Microphone could not be started (permission?)")
            stopSelf()
        }
    }

    /**
     * Grammar for wake listening. Null means the full language model
     * (full-listening mode): the recognizer hears everything and the app
     * looks for the phrase in the results. Full listening is the default
     * since v1.3: "the" is a weak word that Vosk often drops or mumbles,
     * so it is the reliable mode for "The Structure".
     */
    private fun wakeGrammar(): String? {
        val mode = app.secureStore.getString(SecureStore.KEY_WAKE_MODE, "full")
        return if (mode == "full") null else com.heygobi.app.audio.VoskManager.WAKE_GRAMMAR
    }

    private fun onAudioChunk(chunk: ShortArray, length: Int) {
        val rec = recognizer ?: return
        when (state) {
            State.LISTENING -> {
                val vosk = app.voskManager
                val finalReady = try {
                    vosk.feed(rec, chunk, length)
                } catch (_: Exception) {
                    false
                }
                if (finalReady) {
                    val text = vosk.finalText(rec)
                    if (vosk.containsWakeWord(text)) onWakeDetected()
                } else {
                    val partial = vosk.partialText(rec)
                    if (partial.isNotBlank() && vosk.containsWakeWord(partial)) {
                        onWakeDetected()
                    }
                }
            }
            State.COMMAND -> {
                val vosk = app.voskManager
                val finalReady = try {
                    vosk.feed(rec, chunk, length)
                } catch (_: Exception) {
                    false
                }
                if (finalReady) {
                    val parsed = vosk.parseFinalResult(vosk.finalResult(rec))
                    // v1.4.4: word-level confidence — a mumbled fragment
                    // (noise, echo) must never end the session as if it
                    // were his command; keep listening instead.
                    if (parsed.avgConfidence >= MIN_COMMAND_CONFIDENCE) {
                        lastCommandText = parsed.text.trim()
                    }
                }
            }
            else -> {
                // While verifying / waiting for the passcode, keep feeding
                // nothing: the ring buffer still records for later use.
            }
        }
    }

    // ------------------------------------------------------------------
    // Wake -> verify -> passcode -> command
    // ------------------------------------------------------------------

    private fun onWakeDetected() {
        if (state != State.LISTENING) return
        state = State.VERIFYING
        serviceScope.launch {
            handleWake()
        }
    }

    /**
     * Wake -> verify -> passcode -> command. Nothing in here may crash
     * the service: any unexpected failure is logged and the listener
     * goes back to listening instead of killing the app. (v1.3: the app
     * used to close itself on wake.)
     *
     * v1.4: a near-miss voice (close to his, below the threshold) no
     * longer dies silently — it falls back to the passcode, so a
     * finicky voiceprint can never lock him out of his own phone. The
     * score is written into the command log so Settings shows WHY a
     * wake was rejected (e.g. "voice_rejected (0.58/0.55)").
     */
    private suspend fun handleWake() {
        try {
            val verifier = app.speakerVerifier
            if (!verifier.isEnrolled()) {
                speak("Voice enrolment cheyyaledhu. App lo setup complete cheyyandi.")
                backToListening()
                return
            }
            // The wake utterance itself is the verification sample.
            val pcm = withContext(Dispatchers.Default) { capture.recentAudio(SpeakerVerifier.VERIFY_SECONDS) }
            val score = withContext(Dispatchers.Default) { verifier.verify(pcm) }
            if (score < 0) {
                // Verification itself failed (model/audio error): the
                // voice gate cannot judge, so the passcode decides.
                Log.w(TAG, "Voice verification unavailable (score=$score)")
                if (!app.passcodeManager.hasPasscode()) {
                    Log.i(TAG, "No passcode set — skipping passcode gate")
                    onPasscodeOk()
                } else if (offerPasscodeFallback("Voice check avvaledhu — passcode cheppandi")) {
                    onPasscodeOk()
                } else {
                    backToListening()
                }
                return
            }
            if (!verifier.isMatch(score)) {
                // Log the score so the reason is visible in Settings.
                val scoreText = "%.2f".format(score) + "/" + "%.2f".format(verifier.threshold())
                Log.w(TAG, "Voice rejected (score=$score)")
                app.database.commandLogDao().insert(
                    CommandLogEntity(
                        command = "wake",
                        action = "voice_rejected ($scoreText)",
                        timestamp = System.currentTimeMillis(),
                        success = false
                    )
                )
                if (score >= FALLBACK_MIN_SCORE &&
                    offerPasscodeFallback("Voice sarigga match kaledhu — passcode cheppandi")
                ) {
                    onPasscodeOk()
                } else {
                    // Far from his voice: stranger or noise. Say nothing,
                    // open nothing.
                    backToListening()
                }
                return
            }
            Log.i(TAG, "Voice verified (score=$score)")
            // No passcode set: the gate can neither pass nor fail, so
            // skip it cleanly instead of showing a gate that can never
            // open. (v1.3 wake-crash guard.)
            if (!app.passcodeManager.hasPasscode()) {
                Log.i(TAG, "No passcode set — skipping passcode gate")
                onPasscodeOk()
                return
            }
            if (awaitPasscode()) {
                onPasscodeOk()
            } else {
                backToListening()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing in activation may kill the app: log and listen again.
            Log.e(TAG, "Wake handling failed", e)
            backToListening()
        }
    }

    /**
     * Offers the passcode screen as a fallback when the voice gate
     * could not confirm him. Returns true only when he enters the
     * correct passcode in time. At most one prompt per cooldown, so a
     * TV or a noisy room cannot spam passcode screens.
     */
    private suspend fun offerPasscodeFallback(spoken: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFallbackPromptMs < FALLBACK_COOLDOWN_MS) return false
        lastFallbackPromptMs = now
        if (!app.passcodeManager.hasPasscode()) return false
        return awaitPasscode(spoken)
    }

    /**
     * Shows the passcode screen and waits for the result. True only on
     * a correct passcode; false on a wrong one, timeout, or any failure
     * to open the screen.
     */
    private suspend fun awaitPasscode(prompt: String = "Passcode cheppandi"): Boolean {
        state = State.WAITING_PASSCODE
        SessionState.resetPasscodeResult()
        speak(prompt)
        val passIntent = Intent(this, PasscodeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(passIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open passcode screen", e)
            return false
        }
        val result = withTimeoutOrNull(PASSCODE_TIMEOUT_MS) {
            SessionState.passcodeResult.first { it != null }
        }
        return when (result) {
            SessionState.PasscodeResult.OK -> true
            SessionState.PasscodeResult.FAILED -> {
                speak("Passcode thappu")
                false
            }
            else -> false // timeout or null
        }
    }

    private suspend fun onPasscodeOk() {
        try {
            // Open the screen lock with the stored credential, if locked.
            val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (keyguard.isKeyguardLocked) {
                val acc = GobiAccessibilityService.instance
                if (acc == null) {
                    speak("Accessibility service off ga undhi. Settings lo The Structure on cheyyandi.")
                    backToListening()
                    return
                }
                // A missing credential is a clean false, never a crash:
                // the unlocker returns false and the user unlocks by hand.
                val unlocked = withContext(Dispatchers.Default) {
                    try {
                        ScreenLockUnlocker.unlock(acc, app.secureStore)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!unlocked) {
                    speak("Screen lock open avvaledhu. Meere unlock cheyyandi.")
                    backToListening()
                    return
                }
            }
            state = State.COMMAND
            // v1.4: reset the command UI so the panel shows a fresh
            // session, and mark it listening so the panel stays up.
            SessionState.resetCommandUi()
            SessionState.setCommandListening(true)
            // v1.4.3: the voice panel floats OVER whatever app he is using
            // (the overlay permission, Setup step 12) — no full-screen
            // takeover. If the overlay is not allowed, fall back to the
            // full-screen voice-command activity.
            val overlay = VoiceOverlay(this)
            val overlayShown = if (overlay.isAvailable()) {
                overlay.show()
                voiceOverlay = overlay
                true
            } else {
                false
            }
            // v1.4.2: the panel itself says "Cheppandi, Guruvu garu" —
            // speaking it aloud too only feeds the mic its own echo.
            // Speak it only when nothing could be shown at all.
            val screenOpened = overlayShown || try {
                startActivity(Intent(this, VoiceCommandActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.e(TAG, "Could not open voice-command screen", e)
                false
            }
            if (!screenOpened) speak("Cheppandi")
            val text = listen(COMMAND_TIMEOUT_MS)
            if (text.isNullOrBlank()) {
                backToListening()
                return
            }
            SessionState.setCommandHeard(text)
            val resultText = try {
                withContext(Dispatchers.Default) {
                    commandEngine.handleWithResult(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
                "Sorry, aa pani cheyyalekapoyanu."
            }
            // v1.4.3: the spoken command and its result are recorded in
            // Chat — the chat is the record of what the voice assistant did.
            try {
                app.chatRepository.recordVoiceExchange(text, resultText)
            } catch (e: Exception) {
                Log.e(TAG, "Could not record voice exchange in chat", e)
            }
            SessionState.setCommandResult(resultText.take(140))
            // Let him see the result before the panel closes.
            delay(1500)
            backToListening()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Command after passcode failed", e)
            backToListening()
        }
    }

    private fun backToListening() {
        state = State.LISTENING
        lastCommandText = null
        // v1.4.3: hide the floating voice panel, if it is showing.
        voiceOverlay?.hide()
        voiceOverlay = null
        // v1.4: closes the voice-command screen, if it is open.
        SessionState.setCommandListening(false)
        try {
            // "[]" resets the recognizer to its default (full) graph;
            // a grammar string re-arms grammar mode.
            recognizer?.setGrammar(wakeGrammar() ?: "[]")
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // VoiceIO (used by the command engine for confirmations)
    // ------------------------------------------------------------------

    override suspend fun listen(timeoutMs: Long): String? {
        lastCommandText = null
        try {
            // "[]" = default (full) graph: hear everything, return the text.
            recognizer?.setGrammar("[]")
        } catch (_: Exception) {
        }
        // v1.4.1: the mic hears the app's OWN voice — the "Cheppandi"
        // prompt it just spoke. Anything recognised in the first two
        // seconds is that echo, not him: drop it and keep waiting, or
        // the session ends on its own echo before he can speak.
        val graceUntil = SystemClock.elapsedRealtime() + ECHO_GRACE_MS
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = lastCommandText
            if (!text.isNullOrBlank()) {
                lastCommandText = null
                if (SystemClock.elapsedRealtime() >= graceUntil) return text
                // Echo of the prompt: drop it, keep listening.
            }
            delay(100)
        }
        lastCommandText = null
        return null
    }

    override fun speak(text: String) {
        app.ttsEngine.speak(text)
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "The Structure listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while The Structure listens for the wake word"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("The Structure is listening")
            .setContentText("Say \"The Structure\" from anywhere — even the lock screen")
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Open", openIntent)
            .addAction(R.drawable.ic_mic, "Stop listening", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
