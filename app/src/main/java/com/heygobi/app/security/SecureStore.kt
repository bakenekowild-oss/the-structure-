package com.heygobi.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * On-device encrypted storage for everything sensitive:
 * app passcode hash, screen-lock credential, voiceprint centroid,
 * emergency contact, setup flags.
 *
 * The data never leaves the phone. It is never logged and never put in
 * any message, file or network request. The encryption key lives in the
 * Android Keystore and is bound to this app.
 */
class SecureStore(context: Context) {

    companion object {
        const val KEY_PASSCODE_HASH = "passcode_hash"
        const val KEY_PASSCODE_SALT = "passcode_salt"
        const val KEY_PASSCODE_FAILURES = "passcode_failures"
        const val KEY_PASSCODE_LOCKOUT_UNTIL = "passcode_lockout_until"
        const val KEY_LOCK_CREDENTIAL = "lock_credential"
        const val KEY_LOCK_KIND = "lock_kind" // "pin", "password" or "pattern"
        const val KEY_VOICE_CENTROID = "voice_centroid"
        const val KEY_VOICE_THRESHOLD = "voice_threshold"
        const val KEY_VOICE_ENROLLED = "voice_enrolled"
        const val KEY_EMERGENCY_NAME = "emergency_name"
        const val KEY_EMERGENCY_NUMBER = "emergency_number"
        const val KEY_BRAIN_MODEL_PATH = "brain_model_path"
        const val KEY_GROQ_API_KEY = "groq_api_key" // user's own free Groq key (Jarvis mode, v1.2)
        const val KEY_VOSK_READY = "vosk_ready"
        const val KEY_SPEAKER_MODEL_READY = "speaker_model_ready"
        const val KEY_WAKE_MODE = "wake_mode" // "grammar" or "full"
        const val KEY_SETUP_DONE = "setup_done"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "heygobi_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, default: String? = null): String? =
        prefs.getString(key, default)

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun getFloat(key: String, default: Float): Float =
        prefs.getFloat(key, default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long =
        prefs.getLong(key, default)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int =
        prefs.getInt(key, default)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
