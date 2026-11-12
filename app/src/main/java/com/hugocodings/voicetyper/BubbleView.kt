package com.hugocodings.voicetyper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The round microphone bubble. Same look in every app (green); the badge under the icon is the dictation
 * language ("FR" / "EN"). Red with a level ring while listening. The preset is shown in the caption.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : View(context) {

    enum class State { IDLE, LISTENING, WORKING, ERROR }

    var state: State = State.IDLE
        set(value) { field = value; invalidate() }
    var idleColor: Int = Color.parseColor("#22C55E")
        set(value) { field = value; invalidate() }
    /** Short badge drawn under the icon: the dictation language ("FR"). */
    var label: String = ""
        set(value) { field = value; invalidate() }
    private var level = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val mic = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val oval = RectF()

    init {
        elevation = 8f
        contentDescription = "Dicter"
    }

    /** RMS in dB from the recognizer (≈ -2…10). */
    fun setLevel(rmsDb: Float) {
        level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        fill.color = when (state) {
            State.IDLE -> idleColor
            State.LISTENING -> Color.parseColor("#EF4444")
            State.WORKING -> Color.parseColor("#6B7280")
            State.ERROR -> Color.parseColor("#991B1B")
        }
        fill.alpha = if (state == State.IDLE) 215 else 245
        canvas.drawCircle(cx, cy, r * 0.86f, fill)
        if (state == State.LISTENING) {
            ring.strokeWidth = r * 0.10f
            ring.alpha = (90 + 165 * level).toInt()
            val rr = r * (0.86f + 0.09f * level)
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawOval(oval, ring)
        }
        val icon = (r * (if (label.isNotEmpty() && state == State.IDLE) 0.80f else 0.95f)).toInt()
        val top = (cy - icon / 2f - if (label.isNotEmpty() && state == State.IDLE) r * 0.16f else 0f).toInt()
        mic.setBounds((cx - icon / 2f).toInt(), top, (cx + icon / 2f).toInt(), top + icon)
        mic.draw(canvas)
        if (label.isNotEmpty() && state == State.IDLE) {
            text.textSize = r * 0.32f
            text.isFakeBoldText = true
            val shown = label.take(4)
            canvas.drawText(shown, cx, cy + r * 0.62f, text)
        }
    }
}
