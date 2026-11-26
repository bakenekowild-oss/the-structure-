package com.heygobi.app.control

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.heygobi.app.security.SecureStore

/**
 * Opens the screen lock with the credential the user stored in the app.
 *
 * The credential is the phone's own screen-lock PIN / password / pattern,
 * entered by the user in the app during setup and stored encrypted
 * on-device only. It is never sent anywhere, never logged, and never
 * appears in code. Only the user's verified voice + app passcode can
 * trigger this — a stranger's voice never reaches this code.
 *
 * How it works: the accessibility service finds the lock-screen controls
 * and enters the credential exactly like a finger would —
 * PIN digits are tapped, a password is set into the password field,
 * a pattern is drawn as one swipe through the dots.
 *
 * Honest limits: this works on most devices for PIN and password. Pattern
 * unlock depends on the device exposing the pattern view's bounds; if the
 * device blocks it, unlock returns false and the user unlocks by hand.
 * The app never pretends an unlock succeeded when it did not — success is
 * checked against the keyguard state afterwards.
 */
object ScreenLockUnlocker {

    /**
     * Attempts to unlock. Returns true only if the keyguard reports
     * unlocked afterwards.
     */
    fun unlock(service: GobiAccessibilityService, store: SecureStore): Boolean {
        val kind = store.getString(SecureStore.KEY_LOCK_KIND) ?: return false
        val credential = store.getString(SecureStore.KEY_LOCK_CREDENTIAL) ?: return false
        if (credential.isEmpty()) return false
        val entered = when (kind) {
            "pin" -> enterPin(service, credential)
            "password" -> enterPassword(service, credential)
            "pattern" -> enterPattern(service, credential)
            else -> false
        }
        if (!entered) return false
        Thread.sleep(800)
        val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return !keyguard.isKeyguardLocked
    }

    private fun enterPin(service: GobiAccessibilityService, pin: String): Boolean {
        for (ch in pin) {
            val digit = ch.toString()
            val node = service.findNodeByText(digit)
                ?: service.findNode {
                    it.contentDescription?.toString() == digit
                }
                ?: return false
            if (!service.clickNode(node)) return false
            Thread.sleep(150)
        }
        return true
    }

    private fun enterPassword(service: GobiAccessibilityService, password: String): Boolean {
        val field = service.findNode {
            it.isEditable && it.className?.toString()?.contains("EditText") == true
        } ?: service.focusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                password
            )
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        Thread.sleep(300)
        // Press Enter on the keyboard, or tap the visible OK/confirm button.
        val ok = service.findNodeByText("OK")
            ?: service.findNodeByText("Done")
            ?: service.findNodeByText("Enter")
        if (ok != null) {
            service.clickNode(ok)
        }
        return true
    }

    /**
     * Pattern: the credential is stored as digits 1-9 (the 3x3 grid,
     * 1 = top-left, 9 = bottom-right), e.g. "14789".
     * The pattern view's bounds are measured and a single swipe is drawn
     * through the matching grid points.
     */
    private fun enterPattern(service: GobiAccessibilityService, pattern: String): Boolean {
        val digits = pattern.filter { it in '1'..'9' }
        if (digits.length < 4) return false
        val view = service.findNode {
            val cls = it.className?.toString() ?: ""
            cls.contains("LockPatternView") || cls.contains("PatternView")
        } ?: return false
        val bounds = service.nodeBounds(view) ?: return false
        val cellW = bounds.width() / 3f
        val cellH = bounds.height() / 3f
        val points = digits.map { d ->
            val n = d - '1'
            val col = n % 3
            val row = n / 3
            bounds.left + cellW * (col + 0.5f) to bounds.top + cellH * (row + 0.5f)
        }
        return service.swipeThrough(points, durationMs = 700L)
    }
}
