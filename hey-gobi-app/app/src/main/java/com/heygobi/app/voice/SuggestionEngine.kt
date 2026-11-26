package com.heygobi.app.voice

import android.content.Context
import android.content.SharedPreferences
import com.heygobi.app.HeyGobiApp
import org.json.JSONArray

/**
 * Auto-suggestions while voice typing, and proactive suggestions from
 * learned habits.
 *
 * - Word completion: a small built-in Tanglish word list (how he talks)
 *   plus words he has typed before, matched by prefix.
 * - Proactive: if a frequent command usually runs around the current time
 *   (within ±20 minutes, last run ~24 h ago), the service can offer it
 *   ("Roju 9 ki WhatsApp open chesthav, open cheyyana?").
 */
class SuggestionEngine(app: HeyGobiApp) {

    companion object {
        private const val PREFS = "heygobi_suggest"
        private const val KEY_RECENT = "recent_phrases"
        private const val MAX_RECENT = 50

        /** Common Tanglish / English words he uses. */
        private val WORDS = listOf(
            "nenu", "naaku", "neeku", "inka", "ipudu", "repu", "ninnna", "roju",
            "cheppu", "cheyyi", "cheppandi", "cheyandi", "vundhi", "ledhu",
            "bagundhi", "super", "sare", "avunu", "kadhu", "vaddu", "ayyindhi",
            "chesthunna", "vasthunna", "velthunna", "chusthunna", "tintunna",
            "whatsapp", "call", "message", "open", "type", "search", "help",
            "good", "morning", "evening", "night", "today", "tomorrow",
            "raju", "neha", "amma", "nanna", "friend", "work", "office",
            "video", "photo", "song", "cinema", "cricket", "news", "weather"
        )
    }

    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Words completing [prefix], best first. */
    fun suggestCompletions(prefix: String, limit: Int = 3): List<String> {
        val p = prefix.lowercase().trim()
        if (p.length < 2) return emptyList()
        val recent = recentPhrases()
        val out = LinkedHashSet<String>()
        for (w in recent) {
            if (w.lowercase().startsWith(p) && w.lowercase() != p) out.add(w)
            if (out.size >= limit) return out.toList()
        }
        for (w in WORDS) {
            if (w.startsWith(p) && w != p) out.add(w)
            if (out.size >= limit) return out.toList()
        }
        return out.toList()
    }

    /** Remembers a typed phrase for future suggestions. */
    fun noteTyped(text: String) {
        val words = text.split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return
        val current = recentPhrases().toMutableList()
        for (w in words) {
            current.remove(w)
            current.add(0, w)
        }
        val trimmed = current.take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, JSONArray(trimmed).toString()).apply()
    }

    private fun recentPhrases(): List<String> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * A proactive suggestion for right now, or null.
     * A frequent command counts if its last run was 20-28 hours ago and
     * within ±20 minutes of the current time of day.
     */
    suspend fun proactiveSuggestion(app: HeyGobiApp): String? {
        val frequent = app.database.commandLogDao().getFrequentCommands(10)
        val now = System.currentTimeMillis()
        val nowMinutes = ((now / 60000) % (24 * 60)).toInt()
        for (entry in frequent) {
            val last = app.database.commandLogDao().getLastForCommand(entry.command) ?: continue
            val ageHours = (now - last.timestamp) / 3_600_000.0
            if (ageHours !in 20.0..28.0) continue
            val lastMinutes = ((last.timestamp / 60000) % (24 * 60)).toInt()
            val diff = kotlin.math.abs(nowMinutes - lastMinutes)
            val wrapped = minOf(diff, 24 * 60 - diff)
            if (wrapped <= 20) {
                return "Roju ee time ki '${entry.command}' chesthav, ippudu cheyyana?"
            }
        }
        return null
    }
}
