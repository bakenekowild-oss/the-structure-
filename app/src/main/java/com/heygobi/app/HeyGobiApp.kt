package com.heygobi.app

import android.app.Application
import com.heygobi.app.audio.TtsEngine
import com.heygobi.app.audio.VoskManager
import com.heygobi.app.brain.ChatRepository
import com.heygobi.app.brain.GroqClient
import com.heygobi.app.brain.LlamaBridge
import com.heygobi.app.data.AppDatabase
import com.heygobi.app.security.PasscodeManager
import com.heygobi.app.security.SecureStore
import com.heygobi.app.voice.CommandEngine
import com.heygobi.app.voice.SpeakerVerifier
import com.heygobi.app.voice.VoiceIO

/**
 * The Structure — application entry point.
 *
 * Singletons are created here once and shared by the UI, the wake-word
 * service and the accessibility service. The Groq client is created here
 * too; it only touches the network when the user chats with a key saved.
 * The brain (llama.cpp) is loaded lazily: with a Groq key saved it is not
 * loaded at all until a Groq request fails; without a key it loads on
 * first chat. It is never loaded at startup, so the app opens fast and
 * the big model only uses RAM while it is needed.
 */
class HeyGobiApp : Application() {

    lateinit var secureStore: SecureStore
        private set
    lateinit var passcodeManager: PasscodeManager
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var llamaBridge: LlamaBridge
        private set
    lateinit var groqClient: GroqClient
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var voskManager: VoskManager
        private set
    lateinit var speakerVerifier: SpeakerVerifier
        private set
    lateinit var ttsEngine: TtsEngine
        private set

    /**
     * v1.4: the command engine for TYPED chat. Same engine as the voice
     * path, but with a chat VoiceIO: confirmations speak aloud via TTS
     * (like voice), while listen() always returns null — so a
     * confirmation that needs his spoken "yes" safely declines instead
     * of hanging. Created lazily on the first typed command.
     */
    val chatCommandEngine: CommandEngine by lazy { CommandEngine(ChatVoiceIO(this), this) }

    override fun onCreate() {
        super.onCreate()
        secureStore = SecureStore(this)
        passcodeManager = PasscodeManager(secureStore)
        database = AppDatabase.get(this)
        llamaBridge = LlamaBridge(this)
        groqClient = GroqClient(this, secureStore)
        chatRepository = ChatRepository(database.chatDao(), llamaBridge, groqClient, secureStore) { chatCommandEngine }
        voskManager = VoskManager(this)
        speakerVerifier = SpeakerVerifier(this, secureStore)
        ttsEngine = TtsEngine(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsEngine.shutdown()
    }
}

/**
 * v1.4: VoiceIO for typed chat. Confirmations speak aloud via TTS, just
 * like the voice path; listen() always returns null, so any confirmation
 * that needs his spoken "yes" safely declines instead of hanging.
 */
private class ChatVoiceIO(private val app: HeyGobiApp) : VoiceIO {
    override suspend fun listen(timeoutMs: Long): String? = null
    override fun speak(text: String) {
        app.ttsEngine.speak(text)
    }
}
