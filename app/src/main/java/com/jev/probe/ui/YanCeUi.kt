package com.jev.probe.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

object YanCeUi {
    val NAVY = Color.parseColor("#171A3A")
    val MINT = Color.parseColor("#57D8C9")
    val MINT_SOFT = Color.parseColor("#DDF8F3")
    val BG = Color.parseColor("#F7F8FA")
    val SURFACE = Color.WHITE
    val TEXT = Color.parseColor("#171A3A")
    val MUTED = Color.parseColor("#747B98")
    val LINE = Color.parseColor("#E9EBF2")
    val SUCCESS = Color.parseColor("#159A83")
    val DANGER = Color.parseColor("#D45665")

    fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).roundToInt()

    fun bg(context: Context, color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radius).toFloat()
            setColor(color)
            stroke?.let { setStroke(dp(context, 1), it) }
        }

    fun text(context: Context, value: String, size: Float, color: Int = TEXT, bold: Boolean = false) =
        TextView(context).apply {
            text = value; textSize = size; setTextColor(color); includeFontPadding = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun card(context: Context, padding: Int = 16): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(context, SURFACE, 20, LINE)
        elevation = dp(context, 1).toFloat()
        setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 12)
        }
    }

    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
    }
}
