package com.heygobi.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import com.heygobi.app.HeyGobiApp
import com.heygobi.app.brain.ChatRepository
import com.heygobi.app.brain.SystemPrompt
import com.heygobi.app.control.AppLauncher
import com.heygobi.app.control.GobiAccessibilityService
import com.heygobi.app.data.CommandLogEntity
import com.heygobi.app.data.RoutineEntity
import com.heygobi.app.notifications.GobiNotificationListenerService
import com.heygobi.app.security.SecureStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The fast local command path.
 *
 * Voice commands (open app, type, call, message, navigate, volume, ...) are
 * handled here, on the phone, immediately — they NEVER wait on the big
 * Gemma brain. Only open questions ("idi enti?", anything that is not a
 * command) go to the brain, and the user is told it will take a few seconds.
 *
 * Safety: before any irreversible action (call, message, delete, payment)
 * the engine reads the action back and waits for his voice "yes". Nothing
 * sends without his word. Emergency "Help" is the one exception: it calls
 * at once.
 *
 * Every command is logged (for learning frequent commands) and every
 * failure is spoken aloud, never silently swallowed.
 */
class CommandEngine(
    private val voice: VoiceIO,
    private val app: HeyGobiApp
) {
    private val context: Context get() = app.applicationContext
    private val launcher = AppLauncher(context)
    private val suggestions = SuggestionEngine(app)

    companion object {
        private const val TAG = "HeyGobiCmd"
        private val YES_WORDS = listOf("yes", "avunu", "sare", "ok", "cheyyi", "pampu", "correct")
        private val NO_WORDS = listOf("no", "vaddu", "kadu", "odhu", "cancel", "aapu")
    }

    suspend fun handle(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val n = normalize(text)
        Log.i(TAG, "command: $n")
        try {
            val action = dispatch(n, text)
            app.database.commandLogDao().insert(
                CommandLogEntity(
                    command = text,
                    action = action,
                    timestamp = System.currentTimeMillis(),
                    success = !action.startsWith("fail")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "command failed", e)
            voice.speak("Sorry, aa pani cheyyalekapoyanu")
            app.database.commandLogDao().insert(
                CommandLogEntity(
                    command = text,
                    action = "fail:exception",
                    timestamp = System.currentTimeMillis(),
                    success = false
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private suspend fun dispatch(n: String, raw: String): String {
        return when {
            n == "help" || n.contains("help") && n.length < 12 -> emergency()
            n.startsWith("idi enti") || n.startsWith("what is this") || n.startsWith("screen lo enti") -> screenSummary()
            n.startsWith("routine create") || n == "routine create cheyyi" -> createRoutineByVoice()
            n.startsWith("routine ") -> runRoutine(n.removePrefix("routine ").trim())
            n == "good morning" || n == "good morning cheyyi" -> runRoutine("good morning")
            n.startsWith("open ") || n.endsWith(" open cheyyi") || n.endsWith(" open") -> openApp(n, raw)
            n.startsWith("type ") -> typeText(raw.substringAfter("type ", "").trim())
            n.startsWith("call ") -> callContact(n.removePrefix("call ").trim())
            n.startsWith("notifications chaduvu") || n.startsWith("notification chaduvu") ||
                n.startsWith("notifications enti") || n.startsWith("notification enti") ||
                n == "ye messages vachayi" || n == "messages vachayi" ||
                n == "notifications summary" || n.startsWith("notifications summary") -> readNotifications()
            n.contains("reply") -> replyToNotification(n)
            n.startsWith("whatsapp ") || n.startsWith("message ") -> messageContact(n, raw)
            n == "back" || n == "go back" -> globalAction("back")
            n == "home" || n == "go home" -> globalAction("home")
            n == "recents" || n == "recent apps" -> globalAction("recents")
            n == "notifications" -> globalAction("notifications")
            n == "lock" || n == "lock phone" || n == "phone lock cheyyi" -> globalAction("lock")
            n == "scroll down" || n == "scroll" -> scroll(true)
            n == "scroll up" -> scroll(false)
            n == "volume up" -> volume(true)
            n == "volume down" -> volume(false)
            n == "mute" || n == "silent" || n == "silent mode" -> silentMode()
            n == "brightness up" || n == "brightness down" -> brightness(n.endsWith("up"))
            n.startsWith("wifi") -> wifiPanel()
            n.startsWith("time") || n == "time enti" -> speakTime()
            n.startsWith("date") || n == "date enti" -> speakDate()
            n.startsWith("weather") || n == "weather enti" -> weather()
            n.startsWith("search ") -> search(n.removePrefix("search ").trim())
            n == "stop" || n == "cancel" -> {
                voice.speak("Sare")
                "stop"
            }
            else -> askBrain(raw)
        }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ENGLISH)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ------------------------------------------------------------------
    // Confirmation
    // ------------------------------------------------------------------

    /** Asks the question aloud and waits for a voice yes/no. */
    private suspend fun confirm(question: String): Boolean {
        voice.speak(question)
        delay(estimateSpeechMs(question))
        val answer = voice.listen(WakeWordService.CONFIRM_TIMEOUT_MS) ?: return false
        val n = normalize(answer)
        if (NO_WORDS.any { n.contains(it) }) return false
        return YES_WORDS.any { n.contains(it) }
    }

    /** Rough wait for the spoken prompt to finish before listening. */
    private fun estimateSpeechMs(text: String): Long =
        (text.length * 90L).coerceIn(900L, 6000L)

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private suspend fun emergency(): String {
        val number = app.secureStore.getString(SecureStore.KEY_EMERGENCY_NUMBER)
        val name = app.secureStore.getString(SecureStore.KEY_EMERGENCY_NAME, "emergency contact")
        if (number.isNullOrBlank()) {
            voice.speak("Emergency contact set cheyyaledhu. App lo setup lo pettandi.")
            return "fail:no_emergency_contact"
        }
        voice.speak("$name ki call chesthunna")
        placeCall(number)
        return "emergency_call:$number"
    }

    /**
     * Loads the local brain on demand (lazy). True when it is ready.
     * Called only when Groq failed or no key is saved — the 2.8 GB model
     * is never loaded eagerly, so a voice question cannot run the phone
     * out of memory either.
     */
    private suspend fun ensureLocalBrain(): Boolean {
        val bridge = app.llamaBridge
        if (bridge.isLoaded()) return true
        val path = app.secureStore.getString(SecureStore.KEY_BRAIN_MODEL_PATH)
        if (path.isNullOrBlank() || !java.io.File(path).exists()) return false
        return try {
            bridge.loadModel(path)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun screenSummary(): String {
        val acc = GobiAccessibilityService.instance
        val screenText = acc?.screenTextSummary() ?: ""
        if (screenText.isBlank()) {
            voice.speak("Screen lo emi kanipinchaledhu")
            return "screen:empty"
        }
        voice.speak("Chusthunna, koncham aagandi")
        // Jarvis mode (v1.3): try Groq first for the summary — fast, no
        // local load. Only if Groq fails do we load the local brain
        // (lazily); if that fails too, read the text aloud.
        val groqReply = app.groqClient.chat(
            listOf(
                ChatRepository.Message("system", SystemPrompt.JARVIS_TEXT),
                ChatRepository.Message(
                    "user",
                    "Screen lo ee text undhi: ${screenText.take(2000)}. Idi enti, short ga cheppu."
                )
            )
        )
        if (!groqReply.isNullOrBlank()) {
            voice.speak(groqReply.trim())
            return "screen:summary_groq"
        }
        if (!ensureLocalBrain()) {
            voice.speak(screenText.take(300))
            return "screen:read_aloud"
        }
        val bridge = app.llamaBridge
        val roles = arrayOf("system", "user")
        val contents = arrayOf(
            SystemPrompt.TEXT_SHORT,
            "Screen lo ee text undhi: $screenText. Idi enti, short ga cheppu."
        )
        val reply = StringBuilder()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            bridge.tokens.collect { reply.append(it) }
        }
        bridge.generate(roles, contents, maxTokens = 128)
        job.cancel()
        val text = reply.toString().trim()
        if (text.isNotEmpty()) voice.speak(text) else voice.speak(screenText.take(300))
        return "screen:summary"
    }

    private suspend fun openApp(n: String, raw: String): String {
        val name = n.removePrefix("open ").removeSuffix(" open cheyyi").removeSuffix(" open").trim()
        if (name.isEmpty()) {
            voice.speak("Ye app open cheyyali?")
            return "fail:no_app_name"
        }
        val found = launcher.findApp(name)
        if (found == null) {
            voice.speak("$name ane app dorakaledhu")
            return "fail:app_not_found"
        }
        if (launcher.launch(found)) {
            voice.speak("${found.label} open chesthunna")
            return "open:${found.packageName}"
        }
        voice.speak("${found.label} open avvaledhu")
        return "fail:app_launch"
    }

    private suspend fun typeText(text: String): String {
        if (text.isBlank()) {
            voice.speak("Emi type cheyyali?")
            return "fail:no_text"
        }
        val acc = GobiAccessibilityService.instance
        if (acc == null) {
            voice.speak("Accessibility service off ga undhi. Settings lo The Structure on cheyyandi.")
            return "fail:no_accessibility"
        }
        if (acc.setTextOnFocusedNode(text)) {
            suggestions.noteTyped(text)
            voice.speak(text)
            val lastWord = text.trim().split(Regex("\\s+")).lastOrNull() ?: ""
            val suggestion = suggestions.suggestCompletions(lastWord, 1).firstOrNull()
            if (suggestion != null && suggestion != lastWord) {
                voice.speak("Suggestion: $suggestion")
            }
            return "type:${text.length}"
        }
        voice.speak("Type cheyyadaniki ye field select cheyyaledhu. Field meedha tap chesi malli cheppandi.")
        return "fail:no_field"
    }

    private suspend fun callContact(name: String): String {
        if (name.isBlank()) {
            voice.speak("Evariki call cheyyali?")
            return "fail:no_name"
        }
        val contact = findContact(name) ?: run {
            voice.speak("$name peru tho contact dorakaledhu")
            return "fail:contact_not_found"
        }
        val number = contact.second
        if (!confirm("${contact.first} ki call cheyyana?")) {
            voice.speak("Sare, call cheyyanu")
            return "call:cancelled"
        }
        placeCall(number)
        return "call:$number"
    }

    private suspend fun messageContact(n: String, raw: String): String {
        val isWhatsApp = n.startsWith("whatsapp")
        val rest = n.removePrefix("whatsapp ").removePrefix("message ").trim()
        val (name, message) = splitNameAndMessage(rest)
        if (name.isBlank() || message.isBlank()) {
            voice.speak("Evariki, emi message pampali? 'Message Raju ki hello ani' la cheppandi.")
            return "fail:bad_message"
        }
        val contact = findContact(name) ?: run {
            voice.speak("$name peru tho contact dorakaledhu")
            return "fail:contact_not_found"
        }
        if (!confirm("${contact.first} ki ila pampana: $message")) {
            voice.speak("Sare, pampanu")
            return "message:cancelled"
        }
        if (isWhatsApp) {
            val ok = sendWhatsApp(contact.second, message)
            if (ok) voice.speak("WhatsApp lo pampesanu") else voice.speak("WhatsApp open chesanu, send button meere tap cheyyandi")
            return "whatsapp:${contact.second}"
        }
        return try {
            SmsManager.getDefault().sendTextMessage(contact.second, null, message, null, null)
            voice.speak("Message pampesanu")
            "sms:${contact.second}"
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            voice.speak("Message pampadam kudarledhu")
            "fail:sms"
        }
    }

    private fun splitNameAndMessage(rest: String): Pair<String, String> {
        // Try the longest prefix that matches a contact (1-3 words for the name).
        val words = rest.split(" ")
        for (take in 3 downTo 1) {
            if (words.size > take) {
                val name = words.take(take).joinToString(" ")
                if (findContactSync(name) != null) {
                    return name to words.drop(take).joinToString(" ")
                }
            }
        }
        val first = words.firstOrNull() ?: ""
        return first to words.drop(1).joinToString(" ")
    }

    private fun findContact(name: String): Pair<String, String>? = findContactSync(name)

    private fun findContactSync(name: String): Pair<String, String>? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")
        return try {
            resolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0) ?: name
                    val number = cursor.getString(1) ?: return null
                    displayName to number
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun placeCall(number: String) {
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.parse("tel:$number"), null)
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private suspend fun sendWhatsApp(number: String, message: String): Boolean {
        return try {
            val clean = number.replace(Regex("[^0-9+]"), "")
            val withCountry = if (clean.startsWith("+")) clean else "+91$clean"
            val url = "https://wa.me/${withCountry.removePrefix("+")}?text=" +
                URLEncoder.encode(message, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // Let the chat open, then tap the send button via accessibility.
            delay(1800)
            val acc = GobiAccessibilityService.instance ?: return false
            acc.tapNodeByDescription("Send") || acc.tapNodeByText("Send")
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp send failed", e)
            false
        }
    }

    private fun readNotifications(): String {
        if (!GobiNotificationListenerService.isEnabled(context)) {
            voice.speak(
                "Notification access off ga undhi. Setup lo Notification access " +
                "on cheyyandi, appudu notifications chaduvuthanu."
            )
            return "fail:no_notification_access"
        }
        voice.speak(GobiNotificationListenerService.summaryText())
        return "notifications:read"
    }

    private fun replyToNotification(n: String): String {
        if (!GobiNotificationListenerService.isEnabled(context)) {
            voice.speak(
                "Notification access off ga undhi. Setup lo Notification access " +
                "on cheyyandi."
            )
            return "fail:no_notification_access"
        }
        val parsed = parseReply(n) ?: run {
            voice.speak(
                "Ye notification ki reply ivvalo cheppandi. " +
                "'WhatsApp lo Raju ki reply hello' la cheppandi."
            )
            return "fail:bad_reply"
        }
        val (appHint, senderHint, replyText) = parsed
        if (replyText.isBlank()) {
            voice.speak("Reply lo emi rayali?")
            return "fail:bad_reply"
        }
        val target = GobiNotificationListenerService.latestReplyable(appHint, senderHint)
        if (target == null) {
            val who = when {
                senderHint != null -> "$senderHint nunchi"
                appHint != null -> "$appHint lo"
                else -> ""
            }
            voice.speak("Reply ivvadaniki $who ye notification dorakaledhu")
            return "fail:no_replyable_notification"
        }
        val ok = GobiNotificationListenerService.replyTo(target.key, replyText)
        if (ok) {
            val who = if (target.title.isNotBlank()) target.title else target.appLabel
            voice.speak("${target.appLabel} lo $who ki reply pampesanu")
            return "notification_reply:${target.appLabel}"
        }
        voice.speak("Reply pampadam kudarledhu — notification expire ayi undochu")
        return "fail:reply_send"
    }

    /**
     * Parses a reply command into (app hint, sender hint, reply text).
     * Understood forms (after normalisation):
     * - "whatsapp lo raju ki reply hello" -> ("whatsapp", "raju", "hello")
     * - "whatsapp lo reply hello" -> ("whatsapp", null, "hello")
     * - "reply raju ki hello" -> (null, "raju", "hello")
     * - "reply hello" -> (null, null, "hello")
     */
    private fun parseReply(n: String): Triple<String?, String?, String>? {
        val appAndSender = Regex("""^(\w+)\s+lo\s+(.+?)\s+ki\s+reply\s+(.+)$""").find(n)
        if (appAndSender != null) {
            return Triple(appAndSender.groupValues[1], appAndSender.groupValues[2], appAndSender.groupValues[3])
        }
        val appOnly = Regex("""^(\w+)\s+lo\s+reply\s+(.+)$""").find(n)
        if (appOnly != null) {
            return Triple(appOnly.groupValues[1], null, appOnly.groupValues[2])
        }
        val senderOnly = Regex("""^reply\s+(.+?)\s+ki\s+(.+)$""").find(n)
        if (senderOnly != null) {
            return Triple(null, senderOnly.groupValues[1], senderOnly.groupValues[2])
        }
        val bare = Regex("""^reply\s+(.+)$""").find(n)
        if (bare != null) {
            return Triple(null, null, bare.groupValues[1])
        }
        return null
    }

    private fun globalAction(which: String): String {
        val acc = GobiAccessibilityService.instance
        if (acc == null) {
            voice.speak("Accessibility service off ga undhi. Settings lo The Structure on cheyyandi.")
            return "fail:no_accessibility"
        }
        val action = when (which) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "lock" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return "fail:unknown_action"
        }
        return if (acc.performGlobalAction(action)) which else "fail:global_action"
    }

    private fun scroll(down: Boolean): String {
        val acc = GobiAccessibilityService.instance
        if (acc == null) {
            voice.speak("Accessibility service off ga undhi.")
            return "fail:no_accessibility"
        }
        return if (acc.scroll(down)) "scroll" else "fail:scroll"
    }

    private fun volume(up: Boolean): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustVolume(
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        return "volume"
    }

    private fun silentMode(): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            voice.speak("Silent mode kosam Do Not Disturb permission kavali. Settings lo ivvandi.")
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "fail:no_dnd_permission"
        }
        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
        voice.speak("Silent mode on chesanu")
        return "silent"
    }

    private fun brightness(up: Boolean): String {
        if (!Settings.System.canWrite(context)) {
            voice.speak("Brightness kosam settings permission kavali.")
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "fail:no_write_settings"
        }
        val current = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
        )
        val next = (current + if (up) 40 else -40).coerceIn(10, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return "brightness"
    }

    private fun wifiPanel(): String {
        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        voice.speak("WiFi settings open chesanu")
        return "wifi_panel"
    }

    private fun speakTime(): String {
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
        voice.speak("Ippudu time $time")
        return "time"
    }

    private fun speakDate(): String {
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH).format(Date())
        voice.speak("Ee roju $date")
        return "date"
    }

    private suspend fun weather(): String {
        // Open-Meteo: free, no key. Chittoor, Andhra Pradesh.
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=13.63&longitude=79.42" +
                "&current=temperature_2m,relative_humidity_2m,weather_code&timezone=Asia%2FKolkata"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().readText()
            val current = JSONObject(body).getJSONObject("current")
            val temp = current.getDouble("temperature_2m")
            val humidity = current.getInt("relative_humidity_2m")
            val code = current.getInt("weather_code")
            val condition = when (code) {
                0 -> "clear sky"
                1, 2 -> "partly cloudy"
                3 -> "cloudy"
                45, 48 -> "foggy"
                51, 53, 55 -> "drizzling"
                61, 63, 65 -> "raining"
                71, 73, 75 -> "snowing"
                80, 81, 82 -> "rain showers"
                95 -> "thunderstorm"
                else -> "cloudy"
            }
            voice.speak("Chittoor lo ippudu $temp degrees, $condition, humidity $humidity percent")
            "weather"
        } catch (_: Exception) {
            voice.speak("Internet ledu, weather cheppalenu")
            "fail:weather_offline"
        }
    }

    private fun search(query: String): String {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return "search"
    }

    // ------------------------------------------------------------------
    // Routines
    // ------------------------------------------------------------------

    private suspend fun runRoutine(name: String): String {
        val routine = app.database.routineDao().getByName(name)
        if (routine == null) {
            voice.speak("$name ane routine ledhu. App lo create cheyyandi.")
            return "fail:no_routine"
        }
        val steps = parseSteps(routine.stepsJson)
        for (step in steps) {
            executeStep(step)
            delay(400)
        }
        voice.speak("Routine $name complete ayyindhi")
        return "routine:$name"
    }

    private suspend fun createRoutineByVoice(): String {
        voice.speak("Routine peru cheppandi")
        delay(1500)
        val name = voice.listen(WakeWordService.COMMAND_TIMEOUT_MS)?.trim() ?: return "fail:routine_no_name"
        if (name.isBlank()) return "fail:routine_no_name"
        voice.speak("Steps okkoti okkoti cheppandi. Aipoyaka 'done' ani cheppandi.")
        delay(1500)
        val steps = mutableListOf<RoutineStep>()
        while (true) {
            val stepText = voice.listen(WakeWordService.COMMAND_TIMEOUT_MS) ?: break
            if (normalize(stepText) == "done") break
            val step = parseSpokenStep(stepText) ?: continue
            steps.add(step)
            voice.speak("Step add chesanu. Inko step leka done ani cheppandi.")
            delay(1200)
        }
        if (steps.isEmpty()) {
            voice.speak("Ye steps levu, routine create cheyyaledhu")
            return "fail:routine_no_steps"
        }
        app.database.routineDao().insert(
            RoutineEntity(name = name, stepsJson = stepsToJson(steps), createdAt = System.currentTimeMillis())
        )
        voice.speak("Routine $name create chesanu, ${steps.size} steps tho")
        return "routine_created:$name"
    }

    private fun parseSpokenStep(text: String): RoutineStep? {
        val n = normalize(text)
        return when {
            n.startsWith("open ") -> RoutineStep("open_app", mapOf("app" to n.removePrefix("open ").trim()))
            n.startsWith("call ") -> RoutineStep("call", mapOf("name" to n.removePrefix("call ").trim()))
            n.startsWith("weather") -> RoutineStep("weather", emptyMap())
            n.startsWith("speak ") || n.startsWith("say ") ->
                RoutineStep("speak", mapOf("text" to text.substringAfter(" ").trim()))
            else -> null
        }
    }

    private suspend fun executeStep(step: RoutineStep) {
        when (step.type) {
            "open_app" -> {
                val appName = step.params["app"] ?: return
                launcher.findApp(appName)?.let { launcher.launch(it) }
            }
            "call" -> {
                val name = step.params["name"] ?: return
                findContact(name)?.let { placeCall(it.second) }
            }
            "speak" -> voice.speak(step.params["text"] ?: "")
            "weather" -> weather()
            "time" -> speakTime()
        }
    }

    data class RoutineStep(val type: String, val params: Map<String, String>)

    private fun parseSteps(json: String): List<RoutineStep> {
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                val params = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "type") params[k] = obj.optString(k, "")
                }
                RoutineStep(obj.getString("type"), params)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun stepsToJson(steps: List<RoutineStep>): String {
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("type", step.type)
            for ((k, v) in step.params) obj.put(k, v)
            arr.put(obj)
        }
        return arr.toString()
    }

    // ------------------------------------------------------------------
    // Brain fallback
    // ------------------------------------------------------------------

    private suspend fun askBrain(raw: String): String {
        voice.speak("Alochisthunna, koncham aagandi")
        // Jarvis mode (v1.3): try Groq first — fast, and it never touches
        // the 2.8 GB local model. Only if Groq fails do we load the local
        // brain (lazily) and ask it instead. One-off question: do not
        // pollute the chat history.
        val groqReply = app.groqClient.chat(
            listOf(
                ChatRepository.Message("system", SystemPrompt.JARVIS_TEXT),
                ChatRepository.Message("user", raw)
            )
        )
        if (!groqReply.isNullOrBlank()) {
            voice.speak(groqReply.trim())
            return "brain:groq"
        }
        if (!ensureLocalBrain()) {
            voice.speak("Brain load avvaledhu. App lo model import chesi malli try cheyyandi.")
            return "fail:brain_not_loaded"
        }
        val bridge = app.llamaBridge
        // One-off question: do not pollute the chat history.
        val history = listOf(
            "system" to SystemPrompt.TEXT,
            "user" to raw
        )
        val reply = StringBuilder()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            bridge.tokens.collect { reply.append(it) }
        }
        bridge.generate(
            roles = history.map { it.first }.toTypedArray(),
            contents = history.map { it.second }.toTypedArray(),
            maxTokens = 192
        )
        job.cancel()
        val text = reply.toString().trim()
        if (text.isNotEmpty()) {
            voice.speak(text)
            return "brain"
        }
        voice.speak("Sorry, answer raaledhu")
        return "fail:brain_empty"
    }
}
