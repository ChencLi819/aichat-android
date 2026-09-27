package com.jev.probe.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.Palette
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing the model's read of the chat plus 3 ranked candidate replies.
 * All actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var panelScroll: ScrollView? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** Bubble menu → start this conversation's own context buffer. */
    var onInjectContext: (() -> Unit)? = null

    /** Bubble menu → drop this conversation's context buffer. */
    var onClearContext: (() -> Unit)? = null

    /** "问 AI" tab → the service collects the question and runs the model. */
    var onAskSubmitted: ((String) -> Unit)? = null

    /** Bubble menu → 结束本会话: drop this conversation's records AND context. */
    var onEndConversation: (() -> Unit)? = null

    /** Inline button next to the candidate replies → re-run the analysis now. */
    var onReanalyze: (() -> Unit)? = null

    /**
     * Whether the open conversation currently has an injected context. Set by
     * the capture service (which owns the conversation titles); the menu shows
     * the matching one of inject / clear.
     */
    var contextInjected: Boolean = false

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0

    /** Whether the last analysis carried this conversation's own context. */
    private var ctxSession = false

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    /**
     * True while the ask tab holds the input: the overlay window is focusable,
     * which ALSO makes it the accessibility service's active window. The service
     * needs this to tell "the user opened our own screens" (hide the bubble)
     * apart from "our focusable overlay became the active window" (keep it!) —
     * conflating the two hid the whole overlay the moment the ask tab opened.
     */
    fun isInputFocusActive(): Boolean = expanded && tab == TAB_ASK

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null

    /**
     * Candidates that arrived while no judgment was on the panel (a new message
     * reset the panel between the two parallel calls of one round). Held here
     * and merged into [showJudgment], so a reply is never silently dropped —
     * the reported "分析中对方发消息会被覆盖".
     */
    private var pendingReplies: List<RankedReply>? = null

    /** True between [showJudgment] and the round's [showReplies]. */
    private var awaitingReplies = false

    // ----- per-conversation round history ----------------------------------

    /** One finished analysis round, kept so the panel can be scrolled back. */
    private class Round(val at: Long, val analysis: Analysis) {
        /** Whether this round's 思考过程 is expanded on the panel. */
        var thinkOpen = false
    }

    /**
     * Rounds per conversation, newest first. Records are NOT wiped by new
     * content any more (the reported "旧分析被清空"): they stay on the panel as a
     * scrollable stack and only go away when the user ends the conversation by
     * hand (bubble menu → 结束本会话) or clears everything in settings.
     */
    private val history = HashMap<String, ArrayList<Round>>()

    /** Identity of the conversation the panel is showing (pkg + title). */
    private var convKey = ""

    /** A round is in flight, so the top of the panel says "分析中…". */
    private var loading = false

    /** The last failure, shown above the records until the next round starts. */
    private var lastError: String? = null

    /** A neutral one-off notice (WeChat unreadable, OCR caveats), shown above the
     *  records and dropped when the next round starts. */
    private var noticeText: String? = null

    /** When the round currently being built started — its record's timestamp.
     *  Zero between rounds. */
    private var currentRoundAt = 0L

    /** An automatic round waiting for the user's yes/no (see [requestRunConsent]). */
    private var consentReason: String? = null
    private var consentRun: (() -> Unit)? = null

    private val stampFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ----- panel tabs -------------------------------------------------------

    private var tab = TAB_ANALYZE
    private var tabAnalyzePill: TextView? = null
    private var tabAskPill: TextView? = null
    private var askInput: EditText? = null
    private var askBox: LinearLayout? = null

    /** The last 问 AI exchange (question, answer); answer empty while asking. */
    private var lastAsk: Pair<String, String>? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /**
     * Panel background: the user's RGB tint at the user's opacity.
     *
     * The colour comes from [Prefs.panelColor] and the alpha from
     * [Prefs.overlayOpacity], so transparency and hue are independent — a user
     * can match the chat's own background and still dial how much of it shows
     * through.
     */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        val rgb = prefs.panelColor
        return Color.argb(a, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#22000000"))
    }

    /**
     * Overlay-safe click wiring for the panel's controls.
     *
     * The standard setOnClickListener path makes the view enter the pressed
     * state on DOWN, which invalidates the window — and on some
     * devices/emulators the overlay's batched input then never flushes: the
     * DOWN arrives but the UP is dropped, so the click silently never fires
     * (the bubble, which never presses, keeps working — exactly the reported
     * "tab taps do nothing / the two tabs became one feature"). Consuming DOWN
     * here skips the pressed path entirely; the UP then arrives, and we run
     * the click ourselves when the finger is still on the control. The pressed
     * visual is lost; reliability wins.
     */
    private fun View.onOverlayClick(click: () -> Unit) {
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_UP -> {
                    if (e.x >= -dp(12) && e.y >= -dp(12) &&
                        e.x <= v.width + dp(12) && e.y <= v.height + dp(12)) click()
                    true
                }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
        reveal()
    }

    /**
     * Make sure the window is actually on screen.
     *
     * A screenshot round trip sets the root INVISIBLE and puts it back when the
     * shot finishes. If that round trip never completed — the service was torn
     * down mid-shot, or the screenshot callback never arrived — the root stayed
     * INVISIBLE while [isShowing] kept reporting true, so nothing would ever
     * bring the bubble back. Every show path passes through here.
     */
    private fun reveal() {
        root?.visibility = View.VISIBLE
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.BUBBLE)
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                prefs.panelWidth.takeIf { it > 0 } ?: dp(PANEL_W_DP),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(56) } // sit just below the bubble
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "AIChat"; setTextColor(Color.parseColor("#111827")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(resizeHandle())
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)
        p.addView(tabRow())

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the chat's input box / keyboard. Scroll inside if taller. A
            // user-set height replaces the cap.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                prefs.panelHeight.takeIf { it > 0 } ?: (screenH * 0.40f).roundToInt()
            ).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panelScroll = scroll
        panel = p
        return p
    }

    /**
     * The ⤡ header button: drag it to resize the panel.
     *
     * A plain handle rather than a gesture on the panel's edge, because an
     * overlay window has no decorations to grab, and because a visible handle is
     * discoverable. Width and the scroll area's height change; the panel stays
     * where it is. Sizes are read at ACTION_DOWN and written back on release, so
     * a rebuild mid-drag can never resize from a stale baseline.
     */
    private fun resizeHandle(): View {
        var startX = 0f; var startY = 0f
        var startW = 0; var startH = 0
        var moved = false
        return TextView(ctx).apply {
            text = "⤡"; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
            gravity = Gravity.CENTER
            // A real touch target (44dp): the glyph alone is a few pixels and
            // the drag kept missing it.
            minWidth = dp(44); minHeight = dp(40)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnTouchListener { _, e ->
                // Look the pieces up through the stored fields, NOT child indexes:
                // the tab row was inserted between header and scroll, and an index
                // lookup silently cast-failed to null — the reported "调整大小没做".
                val p = panel
                val scroll = panelScroll
                val params = p?.layoutParams as? FrameLayout.LayoutParams
                val scrollParams = scroll?.layoutParams as? LinearLayout.LayoutParams
                if (p == null || scroll == null || params == null || scrollParams == null) {
                    return@setOnTouchListener false
                }
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY; moved = false
                        startW = params.width; startH = scrollParams.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - startX).toInt(); val dy = (e.rawY - startY).toInt()
                        if (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP)) moved = true
                        params.width = (startW + dx).coerceIn(dp(MIN_PANEL_W_DP), screenW - dp(12))
                        scrollParams.height = (startH + dy).coerceIn(dp(MIN_PANEL_H_DP), screenH - dp(120))
                        p.layoutParams = params
                        scroll.layoutParams = scrollParams
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.panelWidth = params.width
                            prefs.panelHeight = scrollParams.height
                            toast("已记住大小")
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(Color.parseColor("#6B7280")); textSize = 16f
        setPadding(dp(10), dp(2), dp(6), dp(2))
        onOverlayClick { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    v.postDelayed(longPress, LONG_PRESS_MS); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    // The moment this stops being a tap, it must stop being a long
                    // press too: without this, pressing and pausing for half a
                    // second before dragging popped the menu open instead of
                    // moving the bubble, which is exactly how a drag gets reported
                    // as "the bubble won't move".
                    if (!moved && (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP))) {
                        moved = true
                        v.removeCallbacks(longPress)
                        dismissBubbleMenu()
                    }
                    // Keep a margin from both side edges: the extreme edge is the
                    // system back-gesture zone (MIUI/HyperOS/MagicOS all have one),
                    // which steals touches and makes the bubble "stuck". Free
                    // positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    /** Take the bubble menu away, if it is up. Used when a drag starts. */
    private fun dismissBubbleMenu() {
        val m = menuView ?: return
        root?.removeView(m)
        menuView = null
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(16, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = FrameLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        menu.addView(menuItem("📸", "截屏识别一次") { root?.removeView(menu); onOcrCapture?.invoke() })
        menu.addView(menuItem("👤", "把当前会话存为联系人") { onSaveContact?.invoke(); root?.removeView(menu) })
        // Only the action that applies right now: one conversation either has a
        // context injected or it does not. Clearing never touches other windows.
        if (contextInjected) {
            menu.addView(menuItem("🧠", "清除本会话上下文") { root?.removeView(menu); onClearContext?.invoke() })
        } else {
            menu.addView(menuItem("🧠", "注入上下文到本会话") { root?.removeView(menu); onInjectContext?.invoke() })
        }
        // The one way to drop this conversation's analysis records + context.
        menu.addView(menuItem("🗑", "结束本会话（清空记录）") {
            root?.removeView(menu); onEndConversation?.invoke()
        })
        menu.addView(menuItem("⚙", "打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("🙈", "隐藏助手（本次）") { hide() })
        menu.addView(menuItem("✕", "取消") { root?.removeView(menu) })
        root?.addView(menu)
        menuView = menu
    }

    /** The bubble menu currently on screen, if any (see [dismissBubbleMenu]). */
    private var menuView: View? = null

    private fun menuItem(icon: String, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = "$icon  $label"; setTextColor(Color.parseColor("#111827")); textSize = 14f
        setPadding(dp(12), dp(13), dp(12), dp(13))
        onOverlayClick { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.jev.probe.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            collapsedX = params.x; collapsedY = params.y
            // Open the panel NEXT TO the bubble, on whichever side has room, and
            // as close to the bubble's height as the screen allows — so the panel
            // lands where the user is looking instead of always at the top-left.
            val pw = prefs.panelWidth.takeIf { it > 0 } ?: dp(PANEL_W_DP)
            params.x = if (collapsedX + dp(26) < screenW / 2) dp(6)
                else (screenW - pw - dp(6)).coerceAtLeast(dp(2))
            // Clamp the FLOOR first: with a user-resized tall panel the raw
            // ceiling can drop below dp(24), and coerceIn(min, max) with
            // min > max throws IllegalArgumentException — the reported
            // crash when switching to the 问 AI tab.
            val ph = (prefs.panelHeight.takeIf { it > 0 } ?: (screenH * 0.40f).roundToInt()) + dp(110)
            val maxTop = (screenH - ph - dp(140)).coerceAtLeast(dp(24))
            params.y = (collapsedY - dp(24)).coerceIn(dp(24), maxTop)
            panel?.visibility = View.VISIBLE
            // The ask tab holds an input box: it needs the keyboard, so the
            // window must take focus while that tab is open. Every other state
            // stays NOT_FOCUSABLE so the chat's keyboard is never stolen.
            if (tab == TAB_ASK) setWindowFocusable(true)
        } else {
            panel?.visibility = View.GONE
            setWindowFocusable(false)
            params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
        }
        android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        ensureRoot(); reveal(); bubble?.alpha = 0.55f
        // Event bursts call this several times a second; rebuilding the panel on
        // every call is part of what made taps feel stuck. Repaint at most once
        // per coalesce window, and never wipe content that is mid-round.
        val now = SystemClock.uptimeMillis()
        if (now - lastIdlePaintAt < IDLE_COALESCE_MS && (contentBox?.childCount ?: 0) > 0) return
        lastIdlePaintAt = now
        // Only ever fills a panel that is literally blank (root rebuilt after
        // hide() leaves contentBox with zero children) — a panel that is showing
        // records is left alone, or the user's scroll position would jump back to
        // the top every 300ms.
        if (contentBox?.childCount == 0) {
            if (tab == TAB_ASK) setContent(askViews()) else renderHistory()
        }
    }

    /** See [showIdle] — the coalesce gate for repeated idle repaints. */
    private var lastIdlePaintAt = 0L

    /**
     * Show the records of whichever conversation is open now.
     *
     * Called when the conversation IDENTITY changes (different app or different
     * chat title). This swaps which history is on screen — it does NOT erase
     * anything, so coming back to a chat still shows its earlier rounds. New
     * content within the same conversation never calls this at all.
     */
    fun switchConversation(key: String) {
        if (key == convKey) return
        convKey = key
        lastJudgment = null
        lastFill = null
        noteText = null
        replyError = null
        pendingReplies = null
        awaitingReplies = false
        loading = false
        lastError = null
        lastAsk = null
        noticeText = null
        currentRoundAt = 0L
        clearConsent()
        renderHistory()
    }

    /**
     * Bubble menu → 结束本会话: the user's explicit "we are done here". This is
     * the ONLY thing that drops a conversation's records (plus its context,
     * which the service clears).
     */
    fun endConversation() {
        history.remove(convKey)
        lastJudgment = null
        lastFill = null
        pendingReplies = null
        awaitingReplies = false
        loading = false
        lastError = null
        replyError = null
        lastAsk = null
        noticeText = null
        noteText = null
        currentRoundAt = 0L
        clearConsent()
        renderHistory(open = true)
    }

    /** Settings → 清除全部会话上下文: every conversation's records go too. */
    fun clearAllHistory() {
        history.clear()
        clearTransient()
        lastError = null
        noticeText = null
        noteText = null
        currentRoundAt = 0L
        clearConsent()
        renderHistory()
    }

    /** Drop only the in-flight round state, keeping the records (notices/errors). */
    private fun clearTransient() {
        lastJudgment = null
        pendingReplies = null
        awaitingReplies = false
        loading = false
        resetThinking()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, Palette.ACCENT)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        onOverlayClick { onClick() }
    }

    fun showLoading() {
        ensureRoot(); bubble?.alpha = 1f
        ctxNotes = 0; ctxHistory = 0; ctxSession = false   // counts for the round that is starting
        replyError = null              // this round has not failed (yet)
        lastError = null
        noticeText = null
        currentRoundAt = 0L
        pendingReplies = null          // a held reply from an aborted round must not merge into this one
        clearConsent()                 // a consent that was given is now a running round
        loading = true
        resetThinking()                // a fresh round's thinking stream starts empty
        // The records below stay on screen; only the top says what is happening.
        renderHistory(open = true)
    }

    /**
     * How many knowledge notes / history lines went into the pending analysis,
     * and whether this conversation's own context was part of it.
     */
    fun setContextInfo(notes: Int, history: Int, session: Boolean = false) {
        ctxNotes = notes; ctxHistory = history; ctxSession = session
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    fun setHiddenForShot(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun showError(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        loading = false
        resetThinking()   // a failed round's stream is dead
        lastError = msg
        renderHistory(open = true)
    }

    /**
     * A neutral one-time notice (used when the foreground is WeChat, which is
     * fully disabled). Not framed as an error: shows the bubble, drops any stale
     * judgment from the previous chat, puts the message in the panel and opens it
     * once so the user actually reads it. Never auto-dismisses (unlike a toast)
     * and never takes input focus (the overlay window is FLAG_NOT_FOCUSABLE).
     */
    fun showNotice(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        clearTransient()
        noticeText = msg
        renderHistory(open = true)
    }

    // ------------------------------------------------ live thinking stream
    // The judgment route thinks with the model's reasoning exposed
    // (reasoning_content, streamed). Showing it as it arrives turns the 10–60s
    // black-box wait into something inspectable — the reported "响应速度太慢"
    // was mostly that silence.

    /** Accumulated thinking of the phase in flight (round or 问 AI). */
    private val thinkText = StringBuilder()
    private var thinkSince = 0L
    /** Live views: the "分析中…(思考 Ns)" head and the stream body. Re-created
     *  on every render; null while not on screen. */
    private var thinkHeadView: TextView? = null
    private var thinkBodyView: TextView? = null
    private var askThinkView: TextView? = null
    /** The finished 问 AI thinking, collapsible under the answer. */
    private var lastAskReasoning: String? = null
    private var askThinkOpen = false

    private fun resetThinking() {
        thinkText.setLength(0)
        thinkSince = System.currentTimeMillis()
        thinkHeadView = null
        thinkBodyView = null
        askThinkView = null
    }

    /** Service → one streamed thinking chunk. Called on the main thread. */
    fun onThinkingDelta(chunk: String) {
        thinkText.append(chunk)
        val shown = thinkTail()
        val secs = (System.currentTimeMillis() - thinkSince).coerceAtLeast(0) / 1000
        if (tab == TAB_ASK) {
            askThinkView?.text = shown
        } else {
            thinkHeadView?.text = "分析中…（思考 ${secs}s）"
            thinkBodyView?.text = shown
        }
    }

    /** The newest part of the thinking; older lines roll off the card. */
    private fun thinkTail(): String {
        val s = thinkText.toString()
        return if (s.length <= 600) s else "…" + s.substring(s.length - 600)
    }

    fun showJudgment(a: Analysis) {
        loading = false
        resetThinking()   // the pass is over; it lives on in the record (a.reasoning)
        // Replies from this round may have landed first (the two calls run in
        // parallel and a panel reset in between used to drop them).
        val merged = pendingReplies
        val full: Analysis
        if (merged != null) {
            pendingReplies = null
            awaitingReplies = false
            full = a.copy(rankedReplies = merged)
        } else {
            awaitingReplies = true
            full = a
        }
        lastJudgment = full
        // The round becomes a record the moment its read is known, so it can no
        // longer be wiped by whatever the chat does next; the candidates are
        // folded into the same record when they arrive.
        currentRoundAt = System.currentTimeMillis()
        commit(Round(currentRoundAt, full))
        renderHistory(open = true, scrollTop = true)
    }

    /**
     * "问 AI" answer. The ask tab holds input + answer, so this just switches to
     * that tab and repaints its result box; the answer is markdown-rendered.
     */
    fun showAnswer(question: String, answer: String, reasoning: String? = null) {
        ensureRoot(); bubble?.alpha = 1f
        lastAsk = question to answer
        lastAskReasoning = reasoning?.takeIf { it.isNotBlank() }
        askThinkOpen = false
        resetThinking()   // live stream is over; the pass stays under the answer
        showTab(TAB_ASK)
    }

    /** "思考中…" placeholder, so a slow answer does not look like a no-op. */
    fun showAsking(question: String) {
        ensureRoot(); bubble?.alpha = 1f
        lastAsk = question to ""
        lastAskReasoning = null
        askThinkOpen = false
        resetThinking()   // this ask's thinking stream starts empty
        showTab(TAB_ASK)
    }

    /** A guard failure before the request even went out (no snapshot / no key). */
    fun showAskError(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        lastAsk = (lastAsk?.first ?: "") to msg
        lastAskReasoning = null
        resetThinking()
        showTab(TAB_ASK)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        val current = lastJudgment
        // Only fold into the CURRENT round — its [showJudgment] must have
        // landed (that is what commits the round and sets awaitingReplies). A
        // judgment still on the panel from a PREVIOUS round (currentRoundAt
        // was reset by this round's showLoading) means ours is still in
        // flight; the parallel reply call can overtake the judgment, and
        // folding into the stale record produced a phantom "第 N 轮 00:00:00"
        // duplicate. Hold, as the no-judgment case already does.
        if (current == null || currentRoundAt == 0L || !awaitingReplies) {
            awaitingReplies = false
            pendingReplies = ranked
            return
        }
        awaitingReplies = false
        val full = current.copy(rankedReplies = ranked)
        lastJudgment = full
        replaceCurrent(full)
        renderHistory(scrollTop = true)
    }

    /**
     * Service → "may this automatic round start?"
     *
     * With [Prefs.autoConfirmRerun] off, every round the service would have
     * started on its own comes through here instead: the panel opens on a yes/no
     * card and [onRun] fires only when the user taps 立即分析. Rounds the user
     * taps for never ask — the tap IS the consent. Deliberately not an
     * AlertDialog: a dialog takes focus away from the accessibility service and
     * re-triggers the hide/show loop the ask tab used to suffer from.
     */
    fun requestRunConsent(reason: String, onRun: () -> Unit) {
        ensureRoot(); bubble?.alpha = 1f
        consentReason = reason
        consentRun = onRun
        renderHistory(open = true, scrollTop = true)
    }

    private fun clearConsent() {
        consentReason = null
        consentRun = null
    }

    // ------------------------------------------------------------ panel tabs

    /** One row of two tabs: analysis of the open chat, and free-form ask. */
    private fun tabRow(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        tabAnalyzePill = pill("AI 分析", primary = true) { showTab(TAB_ANALYZE) }
        tabAskPill = pill("问 AI", primary = false) { showTab(TAB_ASK) }
        row.addView(tabAnalyzePill)
        row.addView(tabAskPill)
        return row
    }

    private fun showTab(t: Int) {
        tab = t
        paintTabPills()
        when (t) {
            TAB_ASK -> {
                setWindowFocusable(true)
                setContent(askViews())
            }
            else -> {
                setWindowFocusable(false)
                renderHistory()
            }
        }
        if (!expanded) toggle()
    }

    private fun paintTabPills() {
        tabAnalyzePill?.let { paintPill(it, tab == TAB_ANALYZE) }
        tabAskPill?.let { paintPill(it, tab == TAB_ASK) }
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else Color.parseColor("#6B7280"))
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = card(14, if (on) Palette.ACCENT else Color.parseColor("#F3F4F6"))
    }

    /** The ask tab: an input (the window is focusable while this tab is open),
     *  an ask button, and the result box below. */
    private fun askViews(): List<View> {
        val input = EditText(ctx).apply {
            hint = "想问什么？例如：她到底在气什么"
            setTextSize(14f)
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            background = card(10, Color.parseColor("#F3F4F6"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minLines = 2; maxLines = 4
        }
        askInput = input
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(input, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(pill("提问", primary = true) {
            val q = input.text.toString().trim()
            if (q.isEmpty()) toast("先输入问题") else onAskSubmitted?.invoke(q)
        })
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        askBox = box
        paintAskBox()
        return listOf(row, box)
    }

    /** Repaint just the result box; the input row stays untouched. */
    private fun paintAskBox() {
        val box = askBox ?: return
        box.removeAllViews()
        val (q, a) = lastAsk ?: run {
            box.addView(hint("输入问题，AI 会结合当前聊天记录和已注入的上下文回答。"))
            return
        }
        if (q.isNotBlank()) box.addView(hint(q))
        if (a.isBlank()) {
            box.addView(hint("思考中…"))
            if (thinkText.isNotEmpty()) {
                box.addView(TextView(ctx).apply {
                    text = thinkTail()
                    setTextColor(Color.parseColor("#6B7280")); setTextSize(11f)
                    setLineSpacing(dp(1).toFloat(), 1f)
                    setPadding(0, dp(2), 0, dp(2))
                }.also { askThinkView = it })
            }
            return
        }
        box.addView(divider())
        // Markdown-rendered, selectable; one TextView keeps it light in an
        // overlay window where a WebView is not an option.
        box.addView(TextView(ctx).apply {
            text = MdText.toSpannable(a)
            setTextColor(Color.parseColor("#111827")); setTextSize(14f)
            setPadding(0, dp(4), 0, dp(4))
            setTextIsSelectable(true)
        })
        lastAskReasoning?.let { reasoning ->
            box.addView(pill(if (askThinkOpen) "收起思考过程" else "看思考过程", false) {
                askThinkOpen = !askThinkOpen
                paintAskBox()
            }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
            })
            if (askThinkOpen) {
                box.addView(TextView(ctx).apply {
                    text = reasoning
                    setTextColor(Color.parseColor("#6B7280")); setTextSize(11f)
                    setLineSpacing(dp(1).toFloat(), 1f)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    background = card(8, Color.parseColor("#F3F4F6"))
                })
            }
        }
        box.addView(pill("复制回答", primary = false) { copy(a) }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
        })
    }

    /**
     * Focusability of the overlay window. Normally NOT_FOCUSABLE — the bubble
     * must never steal the chat's keyboard. The ask tab is the one exception:
     * its input needs focus, so the flag is lifted while that tab is open and
     * restored on every other path (tab switch, collapse, hide).
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val params = lp ?: return
        val want = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (want == params.flags) return
        params.flags = want
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; panelScroll = null
        contentBox = null; askBox = null; dangerDot = null
        menuView = null; expanded = false
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        reveal()
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    /**
     * Paint this conversation's records — newest round first, everything else
     * (consent card, notice, error, "分析中…") stacked above them.
     *
     * Nothing here erases a finished round: records only leave through the bubble
     * menu's 结束本会话, or 设置 → 清除全部会话上下文. That is the whole point of
     * the stack — the model reading the chat three messages ago is still worth
     * scrolling back to.
     *
     * @param open whether arriving here may pop the panel open. Tab switches
     *        and catch-up repaints pass false so the panel does not fight the user.
     * @param scrollTop jump back to the newest round (used when one lands).
     */
    private fun renderHistory(open: Boolean = false, scrollTop: Boolean = false) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        // The ask tab owns its content (input box + answer). A repaint from the
        // ANALYSIS side must never touch it — the old render() did exactly that,
        // so a judgment arriving mid-question swapped the 问 AI screen for
        // analysis results and the two tabs became "the same feature". The round
        // still lands in the records and is there when the user switches back.
        if (tab == TAB_ASK) {
            if (open && !expanded) toggle()
            return
        }
        val views = ArrayList<View>()

        // What context the pending / newest round was based on (knowledge base /
        // remembered history / this conversation's own recorded context).
        val kb = if (ctxNotes == 0 && ctxHistory == 0) "未用知识库"
            else "知识库 $ctxNotes 条 · 历史 $ctxHistory 条"
        views.add(hint(if (ctxSession) "$kb · 本会话上下文记录中" else kb))

        // How this snapshot was captured, when it changes how to read it.
        noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }

        consentReason?.let { views.add(consentCard(it)) }

        noticeText?.let {
            views.add(divider())
            views.add(line("提示", "#00B96B", 14f, true))
            views.add(hint(it))
        }

        if (loading) {
            views.add(divider())
            val secs = (System.currentTimeMillis() - thinkSince).coerceAtLeast(0) / 1000
            views.add(line("分析中…（思考 ${secs}s）", "#6B7280", 14f, true).also { thinkHeadView = it })
            if (thinkText.isNotEmpty()) {
                // The model's reasoning, streaming in. New lines arrive at the
                // bottom via [onThinkingDelta] updating this view in place.
                views.add(TextView(ctx).apply {
                    text = thinkTail()
                    setTextColor(Color.parseColor("#6B7280")); setTextSize(11f)
                    setLineSpacing(dp(1).toFloat(), 1f)
                    setPadding(0, dp(2), 0, dp(2))
                }.also { thinkBodyView = it })
            }
            views.add(hint("模型思考通常 10–30 秒，先前的记录留在下面。"))
        }

        lastError?.let {
            views.add(divider())
            views.add(line("⚠ $it", "#DC2626", 13f))
        }

        val rounds = history[convKey].orEmpty()
        if (rounds.isEmpty() && !loading && consentReason == null) {
            views.add(bigButton("分析当前对话") { onManualAnalyze?.invoke() })
            views.add(hint("切到「问 AI」可以直接就这段聊天提问"))
        }
        // Newest first, and numbered oldest = 1 so the number matches the round's
        // order in the conversation.
        rounds.forEachIndexed { i, r -> views.addAll(roundViews(r, i == 0, rounds.size - i)) }

        setContent(views)
        if (scrollTop) panelScroll?.scrollTo(0, 0)
        if (open && !expanded) toggle()
    }

    /**
     * One round's block: its stamp, the read, then the candidate replies.
     *
     * @param newest the round still being filled in / acted on. Only it carries
     *        the inline 重新分析 button and the 填入 pills — older rounds offer
     *        copy only, since their candidates were written for a chat that has
     *        moved on.
     */
    private fun roundViews(r: Round, newest: Boolean, no: Int): List<View> {
        val a = r.analysis
        val views = ArrayList<View>()
        views.add(divider())
        views.add(line("第 $no 轮 · ${stampFmt.format(Date(r.at))}", "#9CA3AF", 11f))

        // Danger badge — the alarm signal, up top and color-coded.
        a.dangerLevel?.let {
            views.add(dangerBadge(it.score.roundToInt(), it.maxLevel))
            if (newest) tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            views.add(line("对方真实意图：${INTENT[it.choice] ?: it.choice}", "#111827", 15f, true))
            views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }

        // The thinking pass, collapsible: kept with the record so a judgment can
        // be audited ("why did it read it this way?"), folded away by default.
        a.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
            views.add(pill(if (r.thinkOpen) "收起思考过程" else "看思考过程", false) {
                r.thinkOpen = !r.thinkOpen
                renderHistory()
            }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(2)
            })
            if (r.thinkOpen) {
                views.add(TextView(ctx).apply {
                    text = reasoning
                    setTextColor(Color.parseColor("#6B7280")); setTextSize(11f)
                    setLineSpacing(dp(1).toFloat(), 1f)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    background = card(8, Color.parseColor("#F3F4F6"))
                })
            }
        }

        views.add(divider())
        // The re-analyze action lives INLINE here, next to the section it acts
        // on — it used to be a full-width button under the cards and took real
        // effort not to mis-touch (the reported "太容易误触").
        val candHead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        candHead.addView(line("候选回复", "#9CA3AF", 12f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (newest) {
            candHead.addView(TextView(ctx).apply {
                text = "重新分析"; setTextColor(Palette.ACCENT); textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(6), dp(2), dp(6))
                onOverlayClick { onReanalyze?.invoke() }
            })
        }
        views.add(candHead)
        if (newest && awaitingReplies) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, rep ->
                views.add(replyCard(i + 1, rep.text, (rep.prob * 100).roundToInt(), fill, allowFill = newest))
            }
            if (a.rankedReplies.isEmpty()) {
                val msg = if (newest && replyError != null) "回复接口出错：$replyError"
                    else "（未生成候选回复）"
                views.add(hint(msg))
            }
        }
        return views
    }

    /**
     * The yes/no card an automatic round needs before it may start (see
     * [requestRunConsent]). Styled as a card, not a dialog: the overlay window is
     * FLAG_NOT_FOCUSABLE and must stay that way.
     */
    private fun consentCard(reason: String): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, Palette.ACCENT_SOFT)
            setPadding(dp(10), dp(10), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        c.addView(line("要分析这段新消息吗？", "#111827", 14f, true))
        c.addView(hint(reason))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
        }
        row.addView(pill("立即分析", true) {
            val run = consentRun
            clearConsent()
            run?.invoke()
        })
        row.addView(pill("暂不", false) {
            clearConsent(); renderHistory()
        })
        c.addView(row)
        return c
    }

    /** Put a round on top of this conversation's records (newest first). */
    private fun commit(r: Round) {
        val list = history.getOrPut(convKey) { ArrayList() }
        list.add(0, r)
        // Keep the panel finite: the oldest rounds roll off.
        while (list.size > MAX_ROUNDS) list.removeAt(list.size - 1)
    }

    /** Fold late arrivals (the candidates) into the round they belong to. */
    private fun replaceCurrent(a: Analysis) {
        val list = history[convKey] ?: return
        val idx = list.indexOfFirst { it.at == currentRoundAt }
        if (idx >= 0) list[idx] = Round(currentRoundAt, a)
        else commit(Round(currentRoundAt, a))
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(
        rank: Int, text: String, pct: Int, onFill: (String) -> Unit, allowFill: Boolean = true
    ): View {
        val top = rank == 1
        val cardBg = if (top) Palette.ACCENT_SOFT else Color.parseColor("#F3F4F6")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = "#$rank · ${pct}%"; setTextColor(Palette.ACCENT); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#111827")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        if (allowFill) {
            btns.addView(pill("填入", true) { android.util.Log.d("JEVASSIST", "overlay: fill tapped"); onFill(text); if (expanded) toggle() })
        }
        c.addView(btns)
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Palette.ACCENT)
        background = card(18, if (primary) Palette.ACCENT else Color.parseColor("#FFFFFF"), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        onOverlayClick { onClick() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#9CA3AF", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")

        /** Hold this long without moving → bubble menu. Longer than the usual
         *  500ms on purpose: a finger that is about to drag often rests first. */
        private const val LONG_PRESS_MS = 650L

        /** Movement past this (dp) means the gesture is a drag, not a tap. */
        private const val DRAG_SLOP_DP = 5

        /** Panel width before the user resizes it, and the resize floor. */
        private const val PANEL_W_DP = 316
        private const val MIN_PANEL_W_DP = 180
        private const val MIN_PANEL_H_DP = 90

        /** Panel tabs: analysis of the open chat vs free-form ask. */
        private const val TAB_ANALYZE = 0
        private const val TAB_ASK = 1

        /** Coalesce window for repeated idle repaints (tap-stuck fix, see [showIdle]). */
        private const val IDLE_COALESCE_MS = 300L

        /** How many rounds one conversation keeps on the panel. Older ones roll
         *  off the end; the panel is a scratchpad, not an archive. */
        private const val MAX_ROUNDS = 20
    }
}
