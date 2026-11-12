package com.hugocodings.voicetyper

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hugocodings.voicetyper.core.Insertion

/** Writes text into another app's focused field through the accessibility API. */
class TextInserter(private val context: Context) {

    /** What was inserted last, for « efface ça » (only removed if still exactly there). */
    private data class Last(val pkg: String?, val viewId: String?, val inserted: String, val at: Int)
    private var last: Last? = null

    fun insert(node: AccessibilityNodeInfo, text: String, keepCase: Boolean): Boolean {
        node.refresh()
        val existing = currentText(node)
        val merged = Insertion.merge(existing, node.textSelectionStart, node.textSelectionEnd, text, keepCase)
        val ok = setText(node, merged.text, merged.cursor) || paste(node, merged.inserted)
        if (ok) last = Last(node.packageName?.toString(), node.viewIdResourceName, merged.inserted, merged.at)
        return ok
    }

    /** Removes the last dictation if the field still holds it at the same place. */
    fun undo(node: AccessibilityNodeInfo): Boolean {
        val l = last ?: return false
        node.refresh()
        if (l.pkg != node.packageName?.toString() || l.viewId != node.viewIdResourceName) return false
        val existing = currentText(node)
        if (!existing.startsWith(l.inserted, l.at)) return false
        val updated = existing.removeRange(l.at, l.at + l.inserted.length)
        last = null
        return setText(node, updated, l.at)
    }

    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    /**
     * Fallback for fields that refuse ACTION_SET_TEXT (some web views): paste through the clipboard,
     * marked sensitive so it stays out of clipboard previews/history, then put the old clip back.
     */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        val previous = runCatching { cm.primaryClip }.getOrNull()
        val clip = ClipData.newPlainText("Voice Typer", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (previous != null) Handler(Looper.getMainLooper()).postDelayed({ runCatching { cm.setPrimaryClip(previous) } }, 600)
        return ok
    }
}
