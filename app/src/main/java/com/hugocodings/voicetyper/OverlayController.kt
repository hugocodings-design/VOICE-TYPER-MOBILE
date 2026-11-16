package com.hugocodings.voicetyper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hugocodings.voicetyper.core.Preset
import kotlin.math.abs

/**
 * Floating windows drawn by the accessibility service (TYPE_ACCESSIBILITY_OVERLAY: no "draw over other
 * apps" permission needed, never takes focus so the cursor stays in the target field):
 * the draggable bubble, a caption with the live transcript, and the long-press preset menu.
 */
class OverlayController(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onBubbleTap()
        fun onBubbleLongPress()
        fun onBubbleMoved(x: Int, y: Int)
        fun onMenuPreset(preset: Preset, remember: Boolean)
        fun onMenuRepaste()
        fun onMenuHideHere()
        fun onMenuToggleLanguage()
    }

    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    val bubble = BubbleView(context)
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var caption: TextView? = null
    private var menu: View? = null

    val isBubbleShown: Boolean get() = bubble.isAttachedToWindow

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun baseParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble(sizeDp: Int, x: Int, y: Int) {
        if (isBubbleShown) return
        val size = dp(sizeDp)
        val metrics = context.resources.displayMetrics
        val params = baseParams(size, size).apply {
            this.x = if (x >= 0) x else metrics.widthPixels - size - dp(8)
            this.y = if (y >= 0) y else (metrics.heightPixels * 0.35f).toInt()
        }
        bubbleParams = params
        bubble.setOnTouchListener(DragTouch(params))
        runCatching { wm.addView(bubble, params) }
    }

    fun hideBubble() {
        hideCaption()
        hideMenu()
        if (isBubbleShown) runCatching { wm.removeView(bubble) }
    }

    fun resizeBubble(sizeDp: Int) {
        val p = bubbleParams ?: return
        val size = dp(sizeDp)
        if (p.width == size) return
        p.width = size; p.height = size
        if (isBubbleShown) runCatching { wm.updateViewLayout(bubble, p) }
    }

    // ---- caption (live transcript / messages) ----

    fun showCaption(text: String, autoHideMs: Long = 0) {
        val p = bubbleParams ?: return
        val tv = caption ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(225, 32, 33, 36)) }
        }.also { caption = it }
        tv.text = text
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.8f).toInt()
        val params = baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
            this.x = (metrics.widthPixels - width) / 2
            this.y = (p.y - dp(110)).coerceAtLeast(dp(24))
        }
        if (tv.isAttachedToWindow) runCatching { wm.updateViewLayout(tv, params) } else runCatching { wm.addView(tv, params) }
        main.removeCallbacks(hideCaptionRunnable)
        if (autoHideMs > 0) main.postDelayed(hideCaptionRunnable, autoHideMs)
    }

    private val hideCaptionRunnable = Runnable { hideCaption() }

    fun hideCaption() {
        caption?.let { if (it.isAttachedToWindow) runCatching { wm.removeView(it) } }
    }

    // ---- long-press menu ----

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    fun showMenu(presets: List<Preset>, currentId: String, appLabel: String, hasLast: Boolean, language: String) {
        hideMenu()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(240, 32, 33, 36)) }
        }
        fun item(text: String, color: Int?, bold: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            if (color != null) {
                val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(dp(12), dp(12)) }
                setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = dp(10)
            }
            setOnClickListener { onClick() }
        }
        list.addView(TextView(context).apply {
            text = "Style pour $appLabel (mémorisé)"
            setTextColor(Color.LTGRAY); textSize = 13f
            setPadding(dp(14), dp(4), dp(14), dp(4))
        })
        // The chosen style is remembered for this app (next plain tap uses it), unless « juste cette fois ».
        val onceOnly = CheckBox(context).apply {
            text = "Juste cette fois (ne pas mémoriser pour $appLabel)"
            setTextColor(Color.LTGRAY)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
        }
        list.addView(onceOnly)
        for (p in presets) {
            list.addView(item(p.name, p.color.toInt(), bold = p.id == currentId) {
                hideMenu(); callbacks.onMenuPreset(p, !onceOnly.isChecked)
            })
        }
        list.addView(item("🌐  Langue : $language (toucher pour changer)", null) { hideMenu(); callbacks.onMenuToggleLanguage() })
        if (hasLast) list.addView(item("↺  Recoller la dernière dictée", null) { hideMenu(); callbacks.onMenuRepaste() })
        list.addView(item("✕  Masquer la bulle ici", null) { hideMenu(); callbacks.onMenuHideHere() })

        val scroll = ScrollView(context).apply { addView(list) }
        val metrics = context.resources.displayMetrics
        val p = bubbleParams
        val width = dp(260)
        val params = baseParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
            x = ((p?.x ?: 0) - width + (p?.width ?: 0)).coerceIn(dp(8), metrics.widthPixels - width - dp(8))
            y = ((p?.y ?: 0) - dp(40)).coerceIn(dp(24), (metrics.heightPixels * 0.5f).toInt())
        }
        scroll.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false }
        menu = scroll
        runCatching { wm.addView(scroll, params) }
    }

    fun hideMenu() {
        menu?.let { if (it.isAttachedToWindow) runCatching { wm.removeView(it) } }
        menu = null
    }

    /** Tap / long press / drag on the bubble. */
    private inner class DragTouch(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            callbacks.onBubbleLongPress()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop) && !longPressed) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(bubble, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    when {
                        dragging -> callbacks.onBubbleMoved(params.x, params.y)
                        !longPressed -> { v.performClick(); callbacks.onBubbleTap() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }
}
