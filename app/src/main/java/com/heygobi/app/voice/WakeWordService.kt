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
 * Always-on "Hey Gobi" listening.
 *
 * Flow (all on-device, all offline):
 *   1. Vosk listens in grammar mode for "Hey Gobi" (tiny model, low CPU).
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
     * looks for the phrase in the results. Used when "gobi" is not in the
     * small model's lexicon.
     */
    private fun wakeGrammar(): String? {
        val mode = app.secureStore.getString(SecureStore.KEY_WAKE_MODE, "grammar")
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
                    lastCommandText = vosk.finalText(rec).trim()
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

    private suspend fun handleWake() {
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
            Log.w(TAG, "Voice verification unavailable (score=$score)")
            backToListening()
            return
        }
        if (!verifier.isMatch(score)) {
            // Stranger: say nothing, open nothing. Log locally only.
            Log.w(TAG, "Voice rejected (score=$score)")
            app.database.commandLogDao().insert(
                CommandLogEntity(
                    command = "wake",
                    action = "voice_rejected",
                    timestamp = System.currentTimeMillis(),
                    success = false
                )
            )
            backToListening()
            return
        }
        Log.i(TAG, "Voice verified (score=$score)")
        state = State.WAITING_PASSCODE
        SessionState.resetPasscodeResult()
        speak("Passcode cheppandi")
        val passIntent = Intent(this, PasscodeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(passIntent)
        val result = withTimeoutOrNull(PASSCODE_TIMEOUT_MS) {
            SessionState.passcodeResult.first { it != null }
        }
        when (result) {
            SessionState.PasscodeResult.OK -> onPasscodeOk()
            SessionState.PasscodeResult.FAILED -> {
                speak("Passcode thappu")
                backToListening()
            }
            else -> backToListening() // timeout or null
        }
    }

    private suspend fun onPasscodeOk() {
        // Open the screen lock with the stored credential, if locked.
        val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (keyguard.isKeyguardLocked) {
            val acc = GobiAccessibilityService.instance
            if (acc == null) {
                speak("Accessibility service off ga undhi. Settings lo Hey Gobi on cheyyandi.")
                backToListening()
                return
            }
            val unlocked = withContext(Dispatchers.Default) {
                ScreenLockUnlocker.unlock(acc, app.secureStore)
            }
            if (!unlocked) {
                speak("Screen lock open avvaledhu. Meere unlock cheyyandi.")
                backToListening()
                return
            }
        }
        state = State.COMMAND
        speak("Cheppandi")
        val text = listen(COMMAND_TIMEOUT_MS)
        if (text.isNullOrBlank()) {
            backToListening()
            return
        }
        withContext(Dispatchers.Default) {
            commandEngine.handle(text)
        }
        backToListening()
    }

    private fun backToListening() {
        state = State.LISTENING
        lastCommandText = null
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
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = lastCommandText
            if (!text.isNullOrBlank()) {
                lastCommandText = null
                return text
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
                "Hey Gobi listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while Hey Gobi listens for the wake word"
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
            .setContentTitle("Hey Gobi is listening")
            .setContentText("Say \"Hey Gobi\" from anywhere — even the lock screen")
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Open", openIntent)
            .addAction(R.drawable.ic_mic, "Stop listening", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
