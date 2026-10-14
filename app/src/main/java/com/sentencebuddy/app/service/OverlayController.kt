package com.sentencebuddy.app.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sentencebuddy.app.R
import com.sentencebuddy.app.data.AppPreferences
import com.sentencebuddy.app.data.Tone
import com.sentencebuddy.app.ui.MaxHeightScrollView
import com.sentencebuddy.app.util.dp
import com.sentencebuddy.app.util.hide
import com.sentencebuddy.app.util.show
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns everything drawn on top of other apps: the tiny draggable bubble and the suggestion panel.
 *
 * Both windows use TYPE_ACCESSIBILITY_OVERLAY, which an enabled accessibility service may add
 * without the "Display over other apps" permission.
 */
class OverlayController(
    private val service: AccessibilityService,
    private val prefs: AppPreferences,
    private val listener: Listener,
    private val keyboardTop: () -> Int?
) {

    interface Listener {
        fun onBubbleClicked()
        fun onSuggestionChosen(text: String)
        fun onToneClicked()
        fun onOpenApp()
    }

    private class PanelViews(
        val root: View,
        val tone: TextView,
        val close: View,
        val status: TextView,
        val openApp: View,
        val scroll: MaxHeightScrollView,
        val container: LinearLayout
    )

    private val context: Context = ContextThemeWrapper(service, R.style.Theme_SentenceBuddy_Overlay)
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleRoot: FrameLayout? = null
    private var bubbleDot: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: PanelViews? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val autoDismiss = Runnable { hidePanel() }

    val isPanelShowing: Boolean get() = panel != null
    val isBubbleShowing: Boolean get() = bubbleRoot != null

    // ---------------------------------------------------------------------------------------------
    // Bubble
    // ---------------------------------------------------------------------------------------------

    /** Uses "Display over other apps" when granted, otherwise the accessibility overlay. */
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            android.provider.Settings.canDrawOverlays(service)
        ) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun showBubble() {
        if (bubbleRoot != null) return

        val root = inflater.inflate(R.layout.overlay_bubble, null) as FrameLayout
        val dot = root.findViewById<ImageView>(R.id.bubbleDot)
        val touch = touchSize()
        val start = initialPosition(touch)

        val params = WindowManager.LayoutParams(
            touch,
            touch,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (prefs.showBubble) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = start.x
            y = start.y
        }

        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            return
        }

        bubbleRoot = root
        bubbleDot = dot
        bubbleParams = params
        root.setOnClickListener { listener.onBubbleClicked() }
        attachDragHandling(root)
        updateAppearance()
    }

    fun hideBubble() {
        val root = bubbleRoot ?: return
        root.animate().cancel()
        bubbleRoot = null
        bubbleDot = null
        bubbleParams = null
        try {
            windowManager.removeView(root)
        } catch (_: Exception) {
        }
    }

    /** Applies size and opacity from settings to the live bubble. */
    fun updateAppearance() {
        val root = bubbleRoot ?: return
        val dot = bubbleDot ?: return
        val params = bubbleParams ?: return

        val size = context.dp(prefs.bubbleSizeDp)
        dot.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        val padding = (size * 0.24f).toInt()
        dot.setPadding(padding, padding, padding, padding)

        val touch = touchSize()
        params.width = touch
        params.height = touch
        root.animate().cancel()
        root.alpha = baseAlpha()
        updateBubbleLayout()
    }

    /** Moves the bubble back to its default spot (used after "Reset bubble position"). */
    fun resetPosition() {
        val params = bubbleParams ?: return
        val start = initialPosition(params.width)
        params.x = start.x
        params.y = start.y
        updateBubbleLayout()
    }

    /** Briefly makes the faint bubble visible to say "suggestions are ready". */
    fun pulseBubble() {
        if (!prefs.showBubble) return
        val root = bubbleRoot ?: return
        root.animate().cancel()
        root.animate()
            .alpha(0.95f)
            .setStartDelay(0)
            .setDuration(180)
            .withEndAction {
                bubbleRoot?.animate()
                    ?.alpha(baseAlpha())
                    ?.setStartDelay(1600)
                    ?.setDuration(500)
                    ?.start()
            }
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandling(root: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        root.setOnTouchListener { view, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    view.animate().cancel()
                    // Make the faint bubble easy to see while it is being touched.
                    view.alpha = max(baseAlpha(), 0.7f)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val screen = screenSize()
                        params.x = (startX + dx).toInt().coerceIn(0, max(0, screen.x - params.width))
                        params.y = (startY + dy).toInt().coerceIn(0, max(0, screen.y - params.height))
                        updateBubbleLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) snapToEdge() else view.performClick()
                    fadeToBase()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    fadeToBase()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val params = bubbleParams ?: return
        val screen = screenSize()
        val edge = context.dp(2)
        val target = if (params.x + params.width / 2 < screen.x / 2) edge else screen.x - params.width - edge

        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener {
                params.x = it.animatedValue as Int
                updateBubbleLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    prefs.saveBubblePosition(params.x, params.y)
                }
            })
            start()
        }
    }

    private fun fadeToBase() {
        bubbleRoot?.animate()
            ?.alpha(baseAlpha())
            ?.setStartDelay(0)
            ?.setDuration(250)
            ?.start()
    }

    private fun updateBubbleLayout() {
        val root = bubbleRoot ?: return
        val params = bubbleParams ?: return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun baseAlpha(): Float = if (prefs.showBubble) prefs.bubbleOpacityPercent / 100f else 0f

    /** Touch target is always at least 44dp even when the visible dot is tiny. */
    private fun touchSize(): Int = max(context.dp(44), context.dp(prefs.bubbleSizeDp) + context.dp(16))

    private fun initialPosition(touch: Int): Point {
        val screen = screenSize()
        var x = prefs.bubbleX
        var y = prefs.bubbleY
        if (x < 0 || y < 0) {
            x = screen.x - touch - context.dp(2)
            val kb = keyboardTop()
            y = if (kb != null) kb - touch - context.dp(96) else (screen.y * 0.55f).toInt()
        }
        return Point(
            x.coerceIn(0, max(0, screen.x - touch)),
            y.coerceIn(0, max(0, screen.y - touch))
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Panel
    // ---------------------------------------------------------------------------------------------

    fun showLoading(tone: Tone) {
        val views = ensurePanel() ?: return
        views.tone.text = toneLabel(tone)
        views.container.removeAllViews()
        views.openApp.hide()
        views.status.text = context.getString(R.string.panel_loading)
        views.status.show()
        scheduleAutoDismiss()
    }

    fun showSuggestions(items: List<String>, tone: Tone) {
        val views = ensurePanel() ?: return
        views.tone.text = toneLabel(tone)
        views.status.hide()
        views.openApp.hide()
        views.container.removeAllViews()
        for ((index, item) in items.withIndex()) {
            val row = inflater.inflate(R.layout.item_suggestion, views.container, false) as TextView
            row.text = if (index == 0) "\u2713  $item" else "\uD83D\uDCA1  $item"
            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onSuggestionChosen(item)
            }
            views.container.addView(row)
        }
        views.scroll.scrollTo(0, 0)
        scheduleAutoDismiss()
    }

    fun showMessage(message: String, showOpenApp: Boolean) {
        val views = ensurePanel() ?: return
        views.tone.text = toneLabel(prefs.tone)
        views.container.removeAllViews()
        views.status.text = message
        views.status.show()
        if (showOpenApp) views.openApp.show() else views.openApp.hide()
        scheduleAutoDismiss()
    }

    fun hidePanel() {
        handler.removeCallbacks(autoDismiss)
        val views = panel ?: return
        panel = null
        panelParams = null
        try {
            windowManager.removeView(views.root)
        } catch (_: Exception) {
        }
    }

    fun hideAll() {
        hidePanel()
        hideBubble()
    }

    fun destroy() {
        hideAll()
        handler.removeCallbacksAndMessages(null)
    }

    private fun ensurePanel(): PanelViews? {
        panel?.let {
            positionPanel()
            return it
        }

        val root = inflater.inflate(R.layout.overlay_panel, null)
        val views = PanelViews(
            root = root,
            tone = root.findViewById(R.id.toneButton),
            close = root.findViewById(R.id.closeButton),
            status = root.findViewById(R.id.statusText),
            openApp = root.findViewById(R.id.openAppButton),
            scroll = root.findViewById(R.id.suggestionScroll),
            container = root.findViewById(R.id.suggestionContainer)
        )
        views.scroll.maxHeightPx = context.dp(300)
        views.close.setOnClickListener { hidePanel() }
        views.tone.setOnClickListener { listener.onToneClicked() }
        views.openApp.setOnClickListener { listener.onOpenApp() }

        val screen = screenSize()
        val width = min(screen.x - context.dp(16), context.dp(520))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (prefs.panelX >= 0 && prefs.panelY >= 0) {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.panelX.coerceIn(0, max(0, screen.x - width))
                y = prefs.panelY.coerceIn(0, max(0, screen.y - context.dp(120)))
            } else {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = bottomOffset()
            }
        }

        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            return null
        }

        panel = views
        panelParams = params
        attachPanelDrag(root.findViewById<View>(R.id.panelTitle).parent as View)
        root.alpha = 0f
        root.translationY = context.dp(12).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(160).start()
        return views
    }

    /** Keeps the panel glued to the top edge of the keyboard. */
    private fun positionPanel() {
        val views = panel ?: return
        val params = panelParams ?: return
        if (prefs.panelX >= 0 && prefs.panelY >= 0) return // user placed it: keep it there
        val offset = bottomOffset()
        if (params.y != offset) {
            params.y = offset
            try {
                windowManager.updateViewLayout(views.root, params)
            } catch (_: Exception) {
            }
        }
    }

    /** Lets the user drag the panel by its title; the position is saved and reused next time. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPanelDrag(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            val views = panel ?: return@setOnTouchListener false
            val params = panelParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    views.root.getLocationOnScreen(loc)
                    params.gravity = Gravity.TOP or Gravity.START
                    params.x = loc[0]
                    params.y = loc[1]
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    handler.removeCallbacks(autoDismiss)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val screen = screenSize()
                    params.x = (startX + event.rawX - downX).toInt().coerceIn(0, max(0, screen.x - params.width))
                    params.y = (startY + event.rawY - downY).toInt().coerceIn(0, max(0, screen.y - context.dp(120)))
                    try {
                        windowManager.updateViewLayout(views.root, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.savePanelPosition(params.x, params.y)
                    scheduleAutoDismiss()
                    true
                }
                else -> false
            }
        }
    }

    private fun bottomOffset(): Int {
        val kb = keyboardTop()
        return if (kb != null) max(0, screenSize().y - kb + context.dp(8)) else context.dp(260)
    }

    private fun scheduleAutoDismiss() {
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
    }

    private fun toneLabel(tone: Tone): String = "${tone.label} ↻"

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val size = Point()
            windowManager.defaultDisplay.getRealSize(size)
            size
        }
    }

    private companion object {
        const val AUTO_DISMISS_MS = 25_000L
    }
}
