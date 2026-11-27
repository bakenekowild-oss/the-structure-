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
import com.heygobi.app.voice.SpeakerVerifier

/**
 * Hey Gobi — application entry point.
 *
 * Singletons are created here once and shared by the UI, the wake-word
 * service and the accessibility service. The Groq client is created here
 * too; it only touches the network when the user chats with a key saved.
 * The brain (llama.cpp) is loaded lazily on first chat, never at startup,
 * so the app opens fast and the big model only uses RAM while it is needed.
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

    override fun onCreate() {
        super.onCreate()
        secureStore = SecureStore(this)
        passcodeManager = PasscodeManager(secureStore)
        database = AppDatabase.get(this)
        llamaBridge = LlamaBridge(this)
        groqClient = GroqClient(this, secureStore)
        chatRepository = ChatRepository(database.chatDao(), llamaBridge, groqClient)
        voskManager = VoskManager(this)
        speakerVerifier = SpeakerVerifier(this, secureStore)
        ttsEngine = TtsEngine(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsEngine.shutdown()
    }
}
