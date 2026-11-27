package com.heygobi.app.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.heygobi.app.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * v1.4.3: the voice-command panel as a small floating card over whatever
 * app he is using — no full-screen takeover. Uses the "Display over other
 * apps" permission the app already holds (Setup step 12). The card shows
 * the mic, what he said ("Vinna: …") and the result, and vanishes when
 * the session ends; the ✕ button hides it early (the session keeps
 * listening in the background and the result is still spoken aloud).
 * If the overlay permission is not granted, the service falls back to
 * the full-screen VoiceCommandActivity instead.
 *
 * Classic Android Views on purpose: a ComposeView inside a
 * WindowManager overlay from a service would need a LifecycleOwner,
 * and this panel is three text lines — Views are the reliable tool.
 * All WindowManager work runs on the main thread, whatever thread
 * calls show() / hide().
 */
class VoiceOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var card: View? = null
    private var heardView: TextView? = null
    private var resultView: TextView? = null
    private var observeJob: Job? = null

    fun isAvailable(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        mainScope.launch {
            if (card != null) return@launch
            val gold = Color.parseColor("#FFC832")
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2233"))
                cornerRadius = dpF(20f)
                setStroke(dp(1f), Color.parseColor("#2E3A52"))
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                elevation = dpF(8f)
            }

            // Top row: gold mic + status + close button.
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val mic = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_btn_speak_now)
                setColorFilter(gold)
                layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply {
                    rightMargin = dp(10f)
                }
            }
            val status = TextView(context).apply {
                text = "Vinipisthunnanu… Cheppandi, Guruvu garu"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val close = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#8A94A6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(10f), 0, 0, 0)
                setOnClickListener { hide() }
            }
            topRow.addView(mic)
            topRow.addView(status)
            topRow.addView(close)
            root.addView(topRow)

            heardView = TextView(context).apply {
                setTextColor(gold)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(8f), 0, 0)
                visibility = View.GONE
            }
            resultView = TextView(context).apply {
                setTextColor(Color.parseColor("#E8ECF3"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(6f), 0, 0)
                visibility = View.GONE
            }
            root.addView(heardView)
            root.addView(resultView)

            val width = context.resources.displayMetrics.widthPixels - dp(32f)
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                // Touches on the card are ours (so ✕ works); touches
                // outside it go straight to the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(56f)
            }
            try {
                windowManager.addView(root, params)
            } catch (_: Exception) {
                return@launch
            }
            card = root

            observeJob = mainScope.launch {
                launch {
                    SessionState.commandHeard.collect { heard ->
                        heardView?.apply {
                            if (heard.isNullOrBlank()) {
                                visibility = View.GONE
                            } else {
                                text = "Vinna: \"$heard\""
                                visibility = View.VISIBLE
                            }
                        }
                    }
                }
                launch {
                    SessionState.commandResult.collect { result ->
                        resultView?.apply {
                            if (result.isNullOrBlank()) {
                                visibility = View.GONE
                            } else {
                                text = result
                                visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    }

    fun hide() {
        mainScope.launch {
            observeJob?.cancel()
            observeJob = null
            card?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            card = null
            heardView = null
            resultView = null
            mainScope.cancel()
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
        ).toInt()

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
        )
}
