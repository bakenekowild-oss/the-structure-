package com.heygobi.app.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Structure app passcode.
 *
 * After "The Structure" wakes the phone and the voice is verified, the app asks
 * for this passcode before it takes control of the phone. The passcode is
 * 4-8 digits, chosen by the user in the app. It is stored only as a salted
 * SHA-256 hash — the passcode itself is never stored anywhere.
 *
 * The passcode never expires. After 5 wrong tries in a row the gate locks
 * for 60 seconds; the lockout is local to the phone.
 */
class PasscodeManager(private val store: SecureStore) {

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 60_000L
    }

    fun hasPasscode(): Boolean =
        store.getString(SecureStore.KEY_PASSCODE_HASH) != null

    /** Returns false if the passcode is not 4-8 digits. */
    fun setPasscode(passcode: String): Boolean {
        if (!isValidFormat(passcode)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.putString(
            SecureStore.KEY_PASSCODE_SALT,
            Base64.encodeToString(salt, Base64.NO_WRAP)
        )
        store.putString(SecureStore.KEY_PASSCODE_HASH, hash(passcode, salt))
        store.putInt(SecureStore.KEY_PASSCODE_FAILURES, 0)
        store.putLong(SecureStore.KEY_PASSCODE_LOCKOUT_UNTIL, 0L)
        return true
    }

    fun isValidFormat(passcode: String): Boolean =
        passcode.length in MIN_LENGTH..MAX_LENGTH && passcode.all { it.isDigit() }

    fun isLockedOut(): Boolean =
        System.currentTimeMillis() < store.getLong(SecureStore.KEY_PASSCODE_LOCKOUT_UNTIL, 0L)

    fun lockoutRemainingMs(): Long {
        val until = store.getLong(SecureStore.KEY_PASSCODE_LOCKOUT_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Returns true only on an exact match. Records failures and lockout. */
    fun verify(passcode: String): Boolean {
        if (isLockedOut()) return false
        val saltB64 = store.getString(SecureStore.KEY_PASSCODE_SALT) ?: return false
        val expected = store.getString(SecureStore.KEY_PASSCODE_HASH) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(
            hash(passcode, salt).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
        if (ok) {
            store.putInt(SecureStore.KEY_PASSCODE_FAILURES, 0)
            store.putLong(SecureStore.KEY_PASSCODE_LOCKOUT_UNTIL, 0L)
        } else {
            val failures = store.getInt(SecureStore.KEY_PASSCODE_FAILURES, 0) + 1
            store.putInt(SecureStore.KEY_PASSCODE_FAILURES, failures)
            if (failures >= MAX_FAILURES) {
                store.putLong(
                    SecureStore.KEY_PASSCODE_LOCKOUT_UNTIL,
                    System.currentTimeMillis() + LOCKOUT_MS
                )
                store.putInt(SecureStore.KEY_PASSCODE_FAILURES, 0)
            }
        }
        return ok
    }

    private fun hash(passcode: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(passcode.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
}
