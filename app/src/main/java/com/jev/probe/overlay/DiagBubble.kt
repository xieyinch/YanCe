package com.jev.probe.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small draggable ball that dumps the node tree of whatever app is *underneath* it.
 *
 * Why the Log page cannot do this: tapping a button there makes 言策 the active window, so
 * `rootInActiveWindow` is our own UI — which is exactly what happened the first time this was
 * tried. This ball is a non-focusable overlay window, so the chat app stays the active window
 * and keeps its node tree readable. That is the same mechanism the analysis overlay already
 * relies on when it reads WeChat.
 *
 * Tap = capture + copy to clipboard. Nothing is uploaded; the text only leaves the device if
 * the user pastes it somewhere.
 */
class DiagBubble(private val ctx: Context, private val capture: () -> String) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    fun isShowing(): Boolean = view != null

    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(ctx)) return
        if (ctx.resources.displayMetrics.widthPixels <= 0) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE is the whole point: it keeps the app underneath as the active
            // window, so rootInActiveWindow stays on the chat app rather than on us.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(560)
        }

        val ball = TextView(ctx).apply {
            text = "诊"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 180, 35, 53))
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44))
        }
        val wrap = FrameLayout(ctx).apply { addView(ball) }
        attachTouch(wrap, params)
        val added = runCatching { wm.addView(wrap, params) }.isSuccess
        if (added) view = wrap
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm.removeView(v) }
        view = null
    }

    private fun attachTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = e.rawX; downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    view?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) captureAndCopy()
                    true
                }
                else -> false
            }
        }
    }

    private fun captureAndCopy() {
        val text = runCatching { capture() }.getOrElse { "抓取失败：${it.message}" }
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("yance_nodes", text))
        }
        val head = text.lineSequence().firstOrNull { it.startsWith("包名=") } ?: "（无包名）"
        val nodes = text.lineSequence().firstOrNull { it.startsWith("统计：") } ?: ""
        Toast.makeText(ctx, "已抓取并复制 · $head $nodes", Toast.LENGTH_LONG).show()
    }
}
