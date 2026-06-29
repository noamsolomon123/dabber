package com.dabber.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dabber.overlay.OverlayService

/**
 * Accessibility service that inserts dictated text into whatever editable field currently
 * has input focus, in any app. This is what makes "press the bubble anywhere and speak"
 * work system-wide.
 *
 * Strategy: locate the focused editable node and replace its text with
 * before-cursor + dictated + after-cursor via [AccessibilityNodeInfo.ACTION_SET_TEXT],
 * then restore the caret. If an app blocks SET_TEXT, fall back to clipboard + paste.
 */
class InsertionService : AccessibilityService() {

    /** Throttle for the noisy TYPE_WINDOW_CONTENT_CHANGED stream (uptime millis). */
    @Volatile
    private var lastContentEvalMs = 0L

    override fun onServiceConnected() {
        instance = this
        // The XML config registers typeViewFocused; also listen for window state/content
        // changes so we can detect when an editable field gains/loses input focus (keyboard
        // up/down) and drive the bubble's visibility.
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            serviceInfo = info
        }
        evaluateEditableFocus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We act on demand from the overlay for insertion; here we only track whether an
        // editable field currently has input focus to toggle the bubble's visibility.
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> evaluateEditableFocus()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastContentEvalMs >= CONTENT_THROTTLE_MS) {
                    lastContentEvalMs = now
                    evaluateEditableFocus()
                }
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        editableFocused = false
        // Accessibility is now off — the bubble can't insert text without us, so hide it.
        OverlayService.notifyEditableFocus(false)
        super.onDestroy()
    }

    /**
     * Recomputes [editableFocused] from the active window and notifies the overlay on change.
     *
     * If the active window can't be read right now (transiently null during window transitions)
     * we keep the last known state rather than reporting "no focus" — that avoids flicker where
     * the bubble would briefly hide while a field is still focused.
     */
    private fun evaluateEditableFocus() {
        val root = rootInActiveWindow ?: return
        val editable = runCatching {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val result = node != null && node.isEditable
            @Suppress("DEPRECATION")
            node?.recycle()
            result
        }.getOrDefault(false)

        if (editable != editableFocused) {
            editableFocused = editable
            OverlayService.notifyEditableFocus(editable)
        }
    }

    /** Forces a focus re-check (used by the overlay when it starts up). */
    fun forceEvaluate() = evaluateEditableFocus()

    /** Inserts [text] at the caret of the focused editable field. Returns true on success. */
    fun insertText(text: String): Boolean {
        if (text.isEmpty()) return false
        val node = findFocusedEditable() ?: return false
        return try {
            insertViaSetText(node, text) || pasteViaClipboard(node, text)
        } catch (_: Exception) {
            false
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focus != null && focus.isEditable) focus else null
    }

    private fun insertViaSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = node.text?.toString() ?: ""
        val len = current.length
        val selEnd = node.textSelectionEnd.let { if (it in 0..len) it else len }
        val selStart = node.textSelectionStart.let { if (it in 0..selEnd) it else selEnd }

        val needsSpace = selStart > 0 &&
            !current[selStart - 1].isWhitespace() &&
            !text.firstOrNull().let { it == null || it.isWhitespace() }
        val insert = if (needsSpace) " $text" else text

        val merged = buildString {
            append(current, 0, selStart)
            append(insert)
            append(current, selEnd, len)
        }
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false

        val caret = (selStart + insert.length).coerceIn(0, merged.length)
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("dabber", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    companion object {
        /** How often the chatty content-changed stream may trigger a focus re-check. */
        private const val CONTENT_THROTTLE_MS = 250L

        @Volatile
        var instance: InsertionService? = null
            private set

        /** True while an editable text field currently holds input focus (keyboard context). */
        @Volatile
        var editableFocused: Boolean = false
            private set

        /** True when the user has enabled the accessibility service in system settings. */
        val isEnabled: Boolean get() = instance != null
    }
}
