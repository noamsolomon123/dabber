package com.dabber.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to events; we act on demand from the overlay.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

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
        @Volatile
        var instance: InsertionService? = null
            private set

        /** True when the user has enabled the accessibility service in system settings. */
        val isEnabled: Boolean get() = instance != null
    }
}
