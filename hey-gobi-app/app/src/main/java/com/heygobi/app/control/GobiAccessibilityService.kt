package com.heygobi.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hey Gobi's hands on the screen.
 *
 * The user enables this once in Settings → Accessibility → Hey Gobi.
 * Through it the app can tap, scroll, type into fields, press back/home,
 * and enter the screen-lock PIN/password/pattern — all driven by his voice
 * only, after the voice lock and the passcode gate.
 *
 * The service never reads the screen for any other purpose and never
 * uploads anything. All work is on-device.
 */
class GobiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GobiAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No passive listening: the service only acts when the command
        // engine asks it to. Window state is read on demand.
    }

    override fun onInterrupt() {}

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    /** Single tap at screen coordinates. */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Swipe from (x1,y1) to (x2,y2) over [durationMs]. Used for scroll and pattern unlock. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Swipe through a list of points in one continuous gesture (pattern unlock). */
    fun swipeThrough(points: List<Pair<Float, Float>>, durationMs: Long = 600L): Boolean {
        if (points.size < 2) return false
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Scroll the current screen up or down. */
    fun scroll(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val top = metrics.heightPixels * 0.3f
        val bottom = metrics.heightPixels * 0.75f
        return if (down) swipe(cx, bottom, cx, top) else swipe(cx, top, cx, bottom)
    }

    // ------------------------------------------------------------------
    // Node search and actions
    // ------------------------------------------------------------------

    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    /** Depth-first search for the first node matching [predicate]. */
    fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = root() ?: return null
        return findNodeRecursive(root, predicate)
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, predicate)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = root() ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val match = nodes.firstOrNull()
        nodes.forEach { if (it != match) it.recycle() }
        return match ?: findNode { it.text?.toString()?.contains(text, ignoreCase = true) == true }
    }

    /** Clicks the first node whose text matches [text]. */
    fun tapNodeByText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return clickNode(node)
    }

    /** Clicks the first node whose content description matches [description]. */
    fun tapNodeByDescription(description: String): Boolean {
        val node = findNode {
            it.contentDescription?.toString()?.equals(description, ignoreCase = true) == true
        } ?: return false
        return clickNode(node)
    }

    /** Clicks a node, or the nearest clickable ancestor. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val parent = current.parent
            if (current !== node) current.recycle()
            current = parent
        }
        return false
    }

    /** The currently focused editable field, if any. */
    fun focusedEditable(): AccessibilityNodeInfo? {
        val focus = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) return focus
        return findNode { it.isEditable && it.className?.toString()?.contains("EditText") == true }
    }

    /** Sets text on the focused editable field (voice typing). */
    fun setTextOnFocusedNode(text: String): Boolean {
        val node = focusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** All visible text on the current screen, de-duplicated — for "Idi enti?". */
    fun screenTextSummary(maxChars: Int = 2000): String {
        val root = root() ?: return ""
        val seen = LinkedHashSet<String>()
        collectText(root, seen)
        val joined = seen.joinToString(" | ")
        return if (joined.length > maxChars) joined.take(maxChars) else joined
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        node.text?.toString()?.trim()?.takeIf { it.length > 1 }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.length > 1 }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }

    /** Bounds of a node on screen, or null. */
    fun nodeBounds(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return if (rect.isEmpty) null else rect
    }
}
