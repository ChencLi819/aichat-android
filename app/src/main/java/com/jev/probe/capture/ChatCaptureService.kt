package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.ContextSessions
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.ChatAiClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs the model analysis off the main thread, and drives the
 * floating overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Three threads, not two: [runAnalysis] runs one task that then submits the
     * judgment and the reply drafting as two more. With a pool of two the outer
     * task held a thread, so the second inner task queued behind the first and
     * the two "parallel" requests serialized — the round took judge + draft
     * instead of max(judge, draft).
     */
    private val worker = Executors.newFixedThreadPool(3)

    /**
     * Adapted chat apps, keyed by package name.
     *
     * WeChat is wired in for its NODE TREE only. The disguised accessibility
     * service class (see the SelectToSpeak subclass) is what makes WeChat expose
     * that tree; the screenshot path stays off for it on purpose — WeChat marks
     * its chat windows secure, so the shot comes back black anyway, and taking
     * one is exactly what trips its anti-screenshot risk control. See
     * [showWeChatUnreadable].
     */
    private val adapters = listOf(WeChatAdapter(), QQAdapter(), XAdapter(), FeishuAdapter())
        .associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }

    /**
     * Input actions (填入) run here, never on [worker]: a round occupies all
     * three of its threads for tens of seconds, and a fill queued behind one
     * made the button feel dead — or pushed it so late that the user had
     * already given up and tapped again. Single thread, sequential, always free.
     */
    private val inputWorker = Executors.newSingleThreadExecutor()
    private fun submitInput(task: () -> Unit) {
        try { inputWorker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var analyzing = false

    /** The identity of the conversation the panel currently shows (pkg + title). */
    private var activeConv: String? = null

    /** Identity of the newest incoming message the running/last round analysed. */
    private var analyzedIncoming: String? = null

    /** When the last round finished — the cooldown gate in the catch-up check. */
    private var lastRoundEndAt = 0L

    /** The extract gate (see [maybeCapture]): when the tree was last walked,
     *  and for which package. */
    private var lastExtractAt = 0L
    private var lastExtractPkg: String? = null

    /**
     * Screen on / unlock → put the bubble back if it is missing. An overlay
     * window survives screen-off, but a static screen fires no accessibility
     * events after unlock, so without this the bubble stayed gone until the
     * user scrolled or switched apps (the reported "息屏后展示框消失").
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                    main.post { if (prefs.enabled) runCatching { parkIdleIfMissing() } }
            }
        }
    }

    /** Last known-good (non-transient) title per package. See [isTransientTitle]:
     *  a page like X's DM thread briefly shows "连接中…" as `snapshot.title`
     *  right after opening, which must never overwrite a real conversation
     *  title or get saved as a contact name. Never cleared on app switch — the
     *  next real title for that package simply replaces it. Deliberately kept
     *  even when the tree stops yielding a title (routine in WeChat): this
     *  cache is what keeps the listen list matching a conversation whose title
     *  is momentarily unreadable, and dropping it turned a readable chat into
     *  a permanently unreadable one. */
    private val lastGoodTitle: MutableMap<String, String> = HashMap()
    private val debounce = Runnable { startAutoRound() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    /** The package [currentSnapshot] was captured from — a snapshot only
     *  describes the app it was read from. */
    private var snapshotPkg: String? = null
    /** The active window's package, adapted or not (updated in maybeCapture and
     *  on window-state events). */
    private var foregroundPkg: String? = null

    // ---- per-conversation context buffer lifetime ---------------------------
    // The buffer represents the chat window that is CURRENTLY open. When that
    // window closes (back to the list, another chat, another app), the buffer is
    // dropped after a short grace period — a quick peek elsewhere cancels the
    // drop by re-opening the same window, but coming back later starts a fresh
    // session instead of silently extending the old one.

    /** Conversation whose buffer is scheduled to expire (title only, one at a
     *  time — only one chat window is ever open). Touched on the main thread. */
    private var expiringTitle: String? = null

    /** Title part of [activeConv]; the conversation whose window is open. */
    private var activeConvTitle: String? = null

    private val expireContext = Runnable {
        val title = expiringTitle
        expiringTitle = null
        if (title != null && ContextSessions.clear(title)) {
            Log.i(TAG, "context session expired after grace (titleChars=${title.length})")
            syncContextState()
        }
    }

    /** The chat window closed: give this conversation's buffer [CONTEXT_EXPIRE_GRACE_MS]. */
    private fun scheduleContextExpire(title: String?) {
        if (title.isNullOrBlank()) return
        main.post {
            if (ContextSessions.isActive(title)) {
                expiringTitle = title
                main.removeCallbacks(expireContext)
                main.postDelayed(expireContext, CONTEXT_EXPIRE_GRACE_MS)
            }
        }
    }

    /** The chat window is open again (or recording restarted) — keep its buffer. */
    private fun cancelContextExpire(title: String?) {
        if (title.isNullOrBlank()) return
        main.post {
            if (expiringTitle == title) {
                expiringTitle = null
                main.removeCallbacks(expireContext)
            }
        }
    }

    /**
     * The foreground moved to a DIFFERENT adapted chat app than the one the
     * held snapshot came from: that snapshot no longer describes what is on
     * screen. Drop it — a later 分析当前对话 / 问 AI must never re-run against
     * the OLD app's conversation (the reported "切到微信后读取的还是 QQ 那个
     * 会话的内容") — and blank the panel so the old conversation's records stop
     * masquerading as the current read. The records come back when that chat is
     * opened again (history is per conversation).
     *
     * Only called with a package that has an adapter, so an IME, the launcher or
     * any other foreign window can never evict a still-valid snapshot.
     *
     * @return true when a stale snapshot was dropped.
     */
    private fun dropStaleSnapshot(pkg: String?): Boolean {
        val held = currentSnapshot ?: return false
        if (snapshotPkg == null || snapshotPkg == pkg) return false
        scheduleContextExpire(held.title)
        currentSnapshot = null
        snapshotPkg = null
        pendingSnapshot = null
        activeConv = ""
        activeConvTitle = null
        main.post { overlay?.switchConversation("") }
        Log.i(TAG, "dropped stale snapshot from another app")
        return true
    }

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** WeChat is read through its node tree only — see [showWeChatUnreadable].
     *  This tracks whether the "tree came back empty" notice has been shown for
     *  the current WeChat visit, so it appears once per visit instead of on every
     *  accessibility callback. Reset whenever a non-WeChat foreground is seen. */
    private var wechatNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis(fromUser = true) }
                ?: explainUnreadable()   // never a silent dead tap
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Bubble menu: start/stop this conversation's own context buffer. Each
        // chat window has its own, so injecting one never affects another.
        overlay?.onInjectContext = { toggleSessionContext(inject = true) }
        overlay?.onClearContext = { toggleSessionContext(inject = false) }
        // "问 AI" tab in the panel: the overlay collects the question, we run it.
        overlay?.onAskSubmitted = { q -> runAsk(q) }
        // Inline button next to the candidate replies: re-run the analysis now.
        overlay?.onReanalyze = { reanalyze() }
        // Bubble menu → 结束本会话: the user's explicit "done with this chat".
        // Drops THIS conversation's analysis records and its context buffer;
        // every other window keeps its own.
        overlay?.onEndConversation = {
            currentSnapshot?.title?.takeIf { it.isNotBlank() }?.let {
                cancelContextExpire(it)
                ContextSessions.clear(it)
            }
            syncContextState()
            overlay?.endConversation()
            overlay?.toast("已结束本会话，分析记录与上下文已清空")
        }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Bubble restore after screen-on / unlock (see [screenReceiver]).
        runCatching {
            ContextCompat.registerReceiver(this, screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({
            if (prefs.enabled) runCatching { maybeCapture() }
            parkIdleIfMissing()
        }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg !in adapters) {
                foregroundPkg = fg
                wechatNoticeShown = false // left WeChat → allow the notice again next visit
                // Our own package is "drop" ONLY when the user really opened our
                // screens. With the ask tab open the focusable overlay IS the
                // active window, so fg == packageName there too — hiding it then
                // removed the whole panel (and the next showIdle re-added it:
                // the reported hide/show flicker loop).
                val selfOverlay = fg == packageName && overlay?.isInputFocusActive() == true
                val drop = (fg == packageName && !selfOverlay) ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                main.post {
                    if (drop) {
                        overlay?.hide()
                        scheduleContextExpire(currentSnapshot?.title)
                    } else if (!selfOverlay) {
                        overlay?.showIdle(null)
                        scheduleContextExpire(currentSnapshot?.title)
                    }
                }
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        foregroundPkg = pkg   // the real active window, adapted or not
        if (pkg != PKG_WECHAT) wechatNoticeShown = false // allow the notice again next WeChat visit
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: return
        // A snapshot from ANOTHER adapted app no longer describes the screen
        // (QQ → WeChat and similar switches) — drop it before anything reads it.
        dropStaleSnapshot(pkg)
        // The tree walk below is the most expensive thing this service does on
        // the main thread, and one incoming message fires a BURST of
        // content-changed events. Without this gate the walk runs several times
        // a second, the bubble's own touch handling queues behind it, and taps
        // feel stuck — the reported "点悬浮球会卡". 300ms bounds the walk to
        // ~3/s while costing a new message at most one extra interval, well
        // under the analysis debounce.
        val now = SystemClock.uptimeMillis()
        if (pkg == lastExtractPkg && now - lastExtractAt < EXTRACT_INTERVAL_MS) return
        lastExtractAt = now
        lastExtractPkg = pkg
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) {
            // Not a chat window of this app any more (the list, a profile…):
            // the just-closed conversation's context buffer lives on grace now.
            scheduleContextExpire(currentSnapshot?.title)
            main.post { overlay?.showIdle(null) }
            return
        }
        // Stabilize the title BEFORE anything below reads it: some apps (X) show
        // a transient "连接中…" title for a moment right after opening a thread.
        val snapshot = stabilizeTitle(pkg ?: "", rawSnapshot)
        // A blank title is not a whitelist miss — it is "the title could not be
        // read", which is routine in WeChat. Hiding on that would leave the user
        // with no bubble and no way to reach the menu, so park the idle bubble.
        // With a configured whitelist a blank title can never match — say so on
        // the panel instead of leaving a silently dead 分析当前对话 button.
        if (!prefs.isAllowed(snapshot.title)) {
            if (snapshot.title.isNullOrBlank() && prefs.whitelist.isNotEmpty()) {
                overlay?.setNote("这个会话的标题没读到，监听名单匹配不了，所以不分析；" +
                    "可在设置里清空监听名单后恢复")
                main.post { overlay?.showIdle(null) }
            } else {
                main.post { overlay?.hide() }
            }
            return
        }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) main.post { overlay?.showIdle(snapshot.title) }
            // WeChat is tree-only: no screenshot, ever (see [showWeChatUnreadable]).
            if (pkg == PKG_WECHAT) { showWeChatUnreadable(auto = true); return }
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        snapshotPkg = pkg
        syncContextState()
        // Window confirmed open → any pending expiry for THIS conversation is a
        // false alarm (a transient extract glitch). Must run before the dedupe
        // early-return below, which skips the identity block entirely.
        cancelContextExpire(snapshot.title)
        // Record here, not only in runAnalysis: an analysis only fires when the
        // newest message is from the other person and auto-analyze is on, so
        // lines would otherwise be lost whenever the user types or taps instead.
        // No-op unless the user started recording this conversation.
        ContextSessions.append(snapshot.title, snapshot.messages, prefs.contextMaxTokens)
        val sig = snapshot.signature()
        // Same content → make sure the bubble is actually up, and never re-analyze
        // (that would burn tokens for nothing).
        //
        // The "is it on screen" test MUST happen on the main thread. Reading it
        // here raced the hide() that leaving the app queues: swiping out of QQ and
        // straight back in saw showing=true (the hide had not run yet), returned
        // doing nothing, and then the queued hide() removed the bubble for good.
        if (sig == lastSignature) {
            main.post { if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title) }
            return
        }
        // A leftover judgment/candidates from ANOTHER conversation must not leak
        // into this one — but new content in the SAME conversation must not wipe
        // the panel mid-round either (the reported "分析时对方发消息被覆盖"):
        // results stay up until this conversation's next round replaces them.
        val convKey = (pkg ?: "") + "\u0000" + (snapshot.title ?: "")
        if (convKey != activeConv) {
            scheduleContextExpire(activeConvTitle)   // the window just left
            activeConv = convKey
            activeConvTitle = snapshot.title
            main.post { overlay?.switchConversation(convKey) }
        }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            main.post { overlay?.showIdle(snapshot.title) }; return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, DEBOUNCE_MS) // debounce bursts of content-changed events
    }

    /**
     * Put the idle bubble up when nothing else has, unless the foreground is a
     * place where it would only be in the way (our own screens, the launcher,
     * the system UI).
     *
     * Needed after a (re)connect: a static screen — a chat app's sign-in page, a
     * conversation list, anything with no adapter — fires no further
     * accessibility events, so nothing would ever bring the bubble back. The
     * user would have to scroll or switch apps before the menu was reachable.
     */
    private fun parkIdleIfMissing() {
        if (overlay?.isShowing() == true) return
        if (overlay?.isInputFocusActive() == true) return   // ask tab mid-typing
        val fg = rootInActiveWindow?.packageName?.toString() ?: return
        val selfOverlay = fg == packageName
        val drop = selfOverlay ||
            fg.contains("launcher", ignoreCase = true) ||
            fg == "com.miui.home" ||
            fg == "com.android.systemui"
        if (!drop) overlay?.showIdle(null)
    }

    /**
     * In a WeChat chat window, but its tree came back without text.
     *
     * This is the one case WeChat cannot be helped through: its bubble text is
     * hidden from services whose disguise fails, and a screenshot is not an
     * option — WeChat marks chat windows secure (the shot would be black) and
     * taking one is what trips its anti-screenshot risk control. So the panel
     * carries a PERSISTENT notice with the actual remedies (a one-time toast
     * vanished and left the user staring at a dead 分析当前对话 button):
     * WeChat caches its per-service accessibility decision, so after this app
     * is reinstalled (or its service re-toggled) WeChat MUST be killed from the
     * recents tray and reopened, or it keeps hiding every node.
     *
     * [auto] dedupes to once per visit ([wechatNoticeShown]); a manual tap
     * (分析当前对话 / 重新分析) always re-shows it.
     */
    private fun showWeChatUnreadable(auto: Boolean) {
        if (auto && wechatNoticeShown) return
        wechatNoticeShown = true
        val msg = wechatUnreadableText()
        main.post {
            overlay?.toast(WECHAT_UNREADABLE_MSG)
            overlay?.showNotice(msg)
        }
    }

    /**
     * The WeChat-unreadable message plus the last tree walk's counts.
     *
     * The counts matter: without them a real-device user has no way to tell
     * "WeChat hid the whole tree" from "only the bubble text is hidden", so
     * neither of us can see why the read failed without adb.
     */
    private fun wechatUnreadableText(): String =
        WECHAT_UNREADABLE_MSG +
            (WeChatAdapter.lastDiag.takeIf { it.isNotBlank() }?.let { "\n读取详情：$it" } ?: "")

    /** The live active window's package, or the last known one — used to tell
     *  whether an unreadable screen is WeChat, which has its own notice. */
    private fun activeOrLastPkg(): String? =
        rootInActiveWindow?.packageName?.toString() ?: foregroundPkg ?: activePkg

    /**
     * The user asked for an analysis but no usable snapshot exists. Never
     * silent: a dead tap with no feedback is exactly how 分析当前对话 came
     * to feel broken (the reported "点不动"). WeChat gets its full notice —
     * the most common case — everything else a plain nudge.
     */
    private fun explainUnreadable() {
        // Ask the LIVE active window first: foregroundPkg/activePkg can both be
        // stale (they lag until a capture succeeds), and mistaking WeChat for
        // "some other app" would hide the one notice that explains the fix.
        if (activeOrLastPkg() == PKG_WECHAT) { showWeChatUnreadable(auto = false); return }
        overlay?.toast("还没读到会话内容：请先进入聊天窗口再分析")
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Replace a transient title with the last known-good one for this package
     *  (if any), and otherwise remember the current title as the new good one. */
    private fun stabilizeTitle(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        if (isTransientTitle(snapshot.title)) {
            val good = lastGoodTitle[pkg] ?: return snapshot
            return snapshot.copy(title = good)
        }
        snapshot.title?.let { lastGoodTitle[pkg] = it }
        return snapshot
    }

    /**
     * An automatic round wants to start (debounce fired, or OCR auto-analyzed).
     *
     * With [Prefs.autoConfirmRerun] off it does NOT start: a yes/no card goes on
     * the panel instead (see [OverlayController.requestRunConsent]) and the round
     * only runs when the user taps 立即分析. Rounds the user taps for —
     * 分析当前对话 / 重新分析 — never pass through here; the tap is the consent.
     *
     * A declined (暂不) message never re-asks: its signature is already recorded,
     * so only the NEXT genuinely new incoming message produces another card.
     */
    private fun startAutoRound() {
        val fresh = pendingSnapshot ?: return
        if (prefs.autoConfirmRerun) { runAnalysis(); return }
        val msg = fresh.lastIncoming()
        val reason = if (msg.isNullOrBlank()) "对方发来新消息"
            else "「" + msg.take(24) + (if (msg.length > 24) "…" else "") + "」"
        overlay?.requestRunConsent(reason) { runAnalysis(fromUser = true) }
    }

    private fun runAnalysis(fromUser: Boolean = false) {
        val snapshot = pendingSnapshot ?: return
        // Busy: a USER tap gets feedback (with thinking on, a round runs tens of
        // seconds) — but an automatic trigger must stay silent. pendingSnapshot
        // already holds the newer content, and the catch-up check at the end of
        // this round picks it up. Toasting here used to nag on every incoming
        // message that arrived mid-round.
        if (analyzing) {
            if (fromUser) overlay?.toast("上一轮分析还在进行中，请稍候")
            return
        }
        if (!prefs.hasKey()) { main.post { overlay?.showError("未设置判断接口密钥，去设置里填") }; return }
        analyzing = true
        analyzedIncoming = snapshot.lastIncoming()
        main.post { overlay?.setNote(snapshot.note); overlay?.showLoading() }
        val client = ChatAiClient(prefs)
        val rel = prefs.relationship
        // Knowledge context first (local file reads only, a few ms), then the two
        // network calls in parallel on the pool. A failure here must never stop
        // the analysis — it just means no extra context this round.
        submit {
            // This conversation's own buffer was already updated when the snapshot
            // was captured; here it is only read back.
            val ctx = buildContext(snapshot)
            val recording = ContextSessions.isActive(snapshot.title)
            main.post { overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0, recording) }

            // Judgment is fast (~1s) — show it immediately.
            submit {
                resetThinkBuf()
                val judgment = client.judge(snapshot, rel, ctx, ::onStreamDelta)
                main.post {
                    if (judgment.error != null) { analyzing = false; overlay?.showError(judgment.error) }
                    else overlay?.showJudgment(judgment)
                }
            }
            // Candidate replies are slower (generative + rank) — fill in when ready.
            submit {
                var replyError: String? = null
                val ranked = try { client.draftAndRank(snapshot, rel, ctx) } catch (e: Exception) {
                    replyError = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                main.post {
                    analyzing = false
                    overlay?.showReplies(ranked, replyError) { text -> fillInput(text) }
                    // A message that arrived mid-round must not be swallowed by
                    // the analyzing guard — but re-running on ANY change made the
                    // panel regenerate by itself (the reported "生成候选回复后
                    // 自动重新生成"): a typing indicator, a receipt, or OCR
                    // re-grouping was enough. So this now requires a genuinely
                    // NEW incoming message, and a short quiet gap.
                    val fresh = pendingSnapshot
                    val now = SystemClock.uptimeMillis()
                    val quiet = now - lastRoundEndAt >= CATCHUP_COOLDOWN_MS
                    lastRoundEndAt = now
                    if (prefs.autoAnalyze && fresh != null && quiet &&
                        fresh.lastIncoming() != analyzedIncoming
                    ) {
                        main.removeCallbacks(debounce)
                        main.postDelayed(debounce, DEBOUNCE_MS)
                    }
                }
            }
        }
    }

    // --------------------------------------------------- per-conversation context

    /**
     * Streaming thinking chunks → the overlay, coalesced. Chunks arrive on the
     * worker doing the request, many times a second; posting each one would
     * flood the main thread and fight the tree walks. So they append into a
     * buffer and ONE posted flush forwards whatever is new.
     */
    private val thinkBuf = StringBuilder()
    private var thinkSent = 0
    @Volatile private var thinkFlushQueued = false

    /** New streaming phase: forget everything forwarded before it. */
    private fun resetThinkBuf() {
        synchronized(thinkBuf) {
            thinkBuf.setLength(0)
            thinkSent = 0
        }
    }

    private fun onStreamDelta(kind: String, chunk: String) {
        if (kind != "reasoning" || chunk.isEmpty()) return
        synchronized(thinkBuf) { thinkBuf.append(chunk) }
        if (thinkFlushQueued) return
        thinkFlushQueued = true
        main.post {
            thinkFlushQueued = false
            val piece = synchronized(thinkBuf) {
                if (thinkBuf.length > thinkSent) {
                    val s = thinkBuf.substring(thinkSent)
                    thinkSent = thinkBuf.length
                    s
                } else ""
            }
            if (piece.isNotEmpty()) overlay?.onThinkingDelta(piece)
        }
    }

    /**
     * The knowledge + session context one round gets, or null when there is
     * none. Runs on a worker: it touches the knowledge base files.
     *
     * A failure here must never stop an analysis — it just means no extra
     * context this round.
     */
    private fun buildContext(snapshot: ChatSnapshot): ChatContext? {
        val pkg = activePkg ?: ""
        val session = ContextSessions.render(snapshot.title, prefs.contextMaxTokens)
        val built = try {
            ContextBuilder.build(this, snapshot, pkg, prefs)
        } catch (e: Exception) {
            Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
        }
        return if (built == null && session == null) null
            else (built ?: ChatContext(null, emptyList(), emptyList())).copy(session = session)
    }

    /**
     * "问 AI" tab → the overlay collected a question; run it against the open
     * chat and hand the answer back to the same tab. Guards report into the tab
     * (not a toast), because the user is looking at it.
     */
    private fun runAsk(question: String) {
        if (question.isBlank()) return
        val snapshot = currentSnapshot
        if (snapshot == null) {
            // The ask tab paints its own box, so a panel notice would be
            // invisible here — put the explanation where the user is looking.
            if (activeOrLastPkg() == PKG_WECHAT) overlay?.showAskError(wechatUnreadableText())
            else overlay?.showAskError("还没读到会话，先进聊天窗口")
            return
        }
        if (!prefs.hasKey()) { overlay?.showAskError("未设置判断接口密钥，去设置里填"); return }
        overlay?.showAsking(question)
        submit {
            resetThinkBuf()
            val ctx = buildContext(snapshot)
            val res = ChatAiClient(prefs).ask(question, snapshot, prefs.relationship, ctx, ::onStreamDelta)
            main.post { overlay?.showAnswer(question, res.answer, res.reasoning) }
        }
    }

    /**
     * Bubble menu → re-run the analysis for the open conversation right now,
     * ignoring the "same content, already analysed" dedupe. This is how the user
     * asks for a fresh read after recording more context.
     */
    private fun reanalyze() {
        val snapshot = currentSnapshot
        if (snapshot == null) { explainUnreadable(); return }
        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        runAnalysis(fromUser = true)
    }

    /**
     * Bubble menu → start or stop recording THIS conversation's context.
     *
     * Starting marks everything currently on screen as already accounted for, so
     * the buffer counts from this instant on rather than reaching back into the
     * chat that came before — that earlier part already goes out with every
     * analysis as the on-screen window. Clearing drops only this window's
     * buffer; every other conversation keeps its own.
     */
    private fun toggleSessionContext(inject: Boolean) {
        val snapshot = currentSnapshot
        val title = snapshot?.title
        if (inject) {
            when {
                title.isNullOrBlank() -> { overlay?.toast("当前会话没有标题，稍后再试"); return }
                isTransientTitle(title) -> { overlay?.toast("会话标题还没加载出来，稍后再试"); return }
            }
        } else if (title.isNullOrBlank()) {
            overlay?.toast("当前会话没有标题，无法对应上下文"); return
        }
        if (!inject) {
            cancelContextExpire(title)
            ContextSessions.clear(title)
            syncContextState()
            overlay?.toast("已清除本会话上下文")
            return
        }
        cancelContextExpire(title)
        ContextSessions.inject(title, snapshot.messages)
        syncContextState()
        overlay?.toast("已从此刻开始记录本会话上下文（离开聊天约1分钟后自动清除）")
    }

    /** Tell the bubble menu which context action to offer for this conversation. */
    private fun syncContextState() {
        overlay?.contextInjected = ContextSessions.isActive(currentSnapshot?.title)
    }

    /** Settings → 清除全部会话上下文: the analysis records on the panel go too. */
    fun clearAllAnalysisHistory() {
        main.post { overlay?.clearAllHistory() }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // WeChat is tree-only: a manual "截屏识别一次" there must NOT take a
        // screenshot — say why instead of returning a black picture.
        if (pkg == PKG_WECHAT) { showWeChatUnreadable(auto = false); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy) return
        ocrBusy = true
        screenCapture.capture { res ->
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines, resources.displayMetrics.widthPixels)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one, and each group's horizontal extent decides who sent it
     * (see [sideOf]). Line bounds are SCREEN coordinates — MlKitOcr maps the crop
     * back and undoes the screenshot scale before handing them over.
     */
    private fun groupOcrLines(lines: List<OcrLine>, screenWidth: Int): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        var left = Int.MAX_VALUE
        var right = 0
        fun flush() {
            if (buf.isEmpty()) return
            out.add(Msg(sideOf(left, right, screenWidth), buf.toString()))
            buf.setLength(0); left = Int.MAX_VALUE; right = 0
        }
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) flush()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            left = minOf(left, l.bounds.left)
            right = maxOf(right, l.bounds.right)
            prev = l
        }
        flush()
        return out
    }

    /**
     * Who sent a bubble, from where it sits horizontally.
     *
     * A flat-screen read has no node tree, so the bubble's alignment is the only
     * signal left: one hugging the left margin is the other person's, one hugging
     * the right margin is mine. Comparing the two margins against each other —
     * rather than an absolute threshold — keeps this correct on any screen width.
     *
     * Two cases stay "other": a line that spans nearly the whole width (a system
     * notice or a date strip, not a bubble), and an empty extent. Both are the
     * safe default, because mislabelling my own words as theirs is the error the
     * panel's advice would then be built on.
     */
    private fun sideOf(left: Int, right: Int, screenWidth: Int): String {
        if (left == Int.MAX_VALUE || right <= 0 || screenWidth <= 0) return "other"
        if (right - left > screenWidth * 0.8f) return "other"
        return if (screenWidth - right < left) "me" else "other"
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean) {
        ocrBusy = false
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        snapshotPkg = pkg
        syncContextState()
        cancelContextExpire(snapshot.title)
        ContextSessions.append(snapshot.title, snapshot.messages, prefs.contextMaxTokens)
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: a conversation IDENTITY change drops the
        // old panel; new content in the same conversation does not (it is handled
        // by the catch-up round after the current one ends). Context buffers
        // follow the same lifetime (see maybeCapture).
        val convKey = pkg + "\u0000" + (snapshot.title ?: "")
        if (convKey != activeConv) {
            scheduleContextExpire(activeConvTitle)
            activeConv = convKey
            activeConvTitle = snapshot.title
            overlay?.switchConversation(convKey)
        }
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            if (manual) runAnalysis(fromUser = true) else startAutoRound()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Fill the chat input box with the chosen reply (never sends). */
    private fun fillInput(text: String) {
        submitInput {
            // Fast path: SET_TEXT works when the box already has input focus and
            // no IME composing session is active.
            var ok = trySetText(text)
            if (!ok) {
                // Otherwise focus the box (click pops the keyboard), let the app
                // settle, and retry SET_TEXT on a FRESH node; if the app still
                // refuses (WeChat's IME composing region swallows SET_TEXT),
                // PASTE from the clipboard. Never clicks send.
                val edit = withActiveRoot { findEditable(it) }
                if (edit != null) {
                    runCatching { edit.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    Thread.sleep(350)
                    ok = trySetText(text)
                }
                if (!ok) ok = pasteFallback(text)
            }
            Log.i(TAG, "fill: ok=$ok")
            val filled = ok
            main.post {
                if (filled) overlay?.toast("已填入输入框，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("未能自动填入，已复制，请长按输入框粘贴") }
            }
        }
    }

    /** Run [block] with the active window's root; null when it is unavailable
     *  or the node walk throws (stale nodes between fetch and use are routine
     *  while the chat redraws). */
    private inline fun <T> withActiveRoot(block: (AccessibilityNodeInfo) -> T?): T? {
        val root = rootInActiveWindow ?: return null
        return runCatching { block(root) }.getOrNull()
    }

    /** Set text on the chat input box, verifying it actually took. */
    private fun trySetText(text: String): Boolean {
        val edit = withActiveRoot { findEditable(it) } ?: return false
        // SET_TEXT silently does nothing on an unfocused box on some apps; ask
        // for focus first — a no-op when the box already has it.
        runCatching { edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        if (!setTextRaw(edit, text)) return false
        // SET_TEXT applies on the app's UI thread, and the node cache can still
        // hold the old (empty) text right after the action — a single immediate
        // readback raced it and demoted WORKING fills to clipboard copies. Poll
        // briefly instead; contains, because some apps append a composing mark.
        return waitForInputText(text)
    }

    /** Poll the input box until it contains [text], for about a second. */
    private fun waitForInputText(text: String): Boolean {
        repeat(5) {
            Thread.sleep(200)
            val after = readInput()
            if (after != null && after.contains(text)) return true
        }
        return false
    }

    /** Last resort: clipboard + ACTION_PASTE performed by the target app itself
     *  (paste is executed with the app's own focus, so it bypasses the service's
     *  clipboard limits). */
    private fun pasteFallback(text: String): Boolean {
        copyToClipboard(text)
        val edit = withActiveRoot { findEditable(it) } ?: return false
        // Clear the box first so a paste that lands twice can never double the
        // text — but only when the box is readable; an unreadable box is left
        // alone (clearing what we cannot see risks eating the user's draft).
        if (!readInput().isNullOrEmpty()) setTextRaw(edit, "")
        runCatching { edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val pasted = runCatching { edit.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
            .getOrDefault(false)
        if (waitForInputText(text)) return true
        // The action reported success but the box cannot be read back (some
        // apps guard their input node). Trust the paste rather than demoting to
        // copy — the wrong "已复制" is exactly how 填入 came to feel like 复制.
        Log.i(TAG, "fill: paste=$pasted readback inconclusive")
        return pasted
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Current text of the input box, fetched fresh (bypassing the node cache). */
    private fun readInput(): String? {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return null
        runCatching { edit.refresh() }
        return edit.text?.toString()
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacks(expireContext)
        expiringTitle = null
        inputWorker.shutdownNow()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onInjectContext = null
        overlay?.onClearContext = null
        overlay?.onAskSubmitted = null
        overlay?.onReanalyze = null
        overlay?.onEndConversation = null
        instance = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Live instance for same-process callers (the settings screen); null
         *  while torn down. HyperOS may kill and reconnect us — always re-set. */
        @Volatile var instance: ChatCaptureService? = null

        /** WeChat's package. Read through the node tree only: its chat windows
         *  are secure (screenshots come back black) and taking one trips its
         *  anti-screenshot risk control, so the OCR path never runs here. */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** Shown when WeChat's chat window yields no text. WeChat keeps its own
         *  protected state on disk (chat windows screenshot-blocked AND their
         *  node tree hidden); that state survives force-stopping the app and
         *  rebooting, and is what hides the text — not our service, which the
         *  user proved by turning it off and seeing the block persist. Verified
         *  remedy: uninstalling and reinstalling WeChat clears it. */
        private const val WECHAT_UNREADABLE_MSG =
            "微信没读到文字。这是微信自己的保护状态（禁止截屏并隐藏文字），强行停止微信无效；" +
                "已实测有效的办法是卸载重装微信（重装前先备份聊天记录）。" +
                "微信禁止截屏，所以这里不截图识别。"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 按左右位置推断谁说的，个别可能错位"

        /** Wait this long after the last event before analysing. Long enough to
         *  coalesce the burst a single incoming message fires, short enough not
         *  to be felt as latency. */
        private const val DEBOUNCE_MS = 450L

        /** Minimum gap between two tree walks of the same app — the tap-stuck
         *  fix, see [maybeCapture]. */
        private const val EXTRACT_INTERVAL_MS = 300L

        /** Quiet gap required before an automatic catch-up round may start, so
         *  capture jitter cannot cascade into repeated regeneration. */
        private const val CATCHUP_COOLDOWN_MS = 4_000L

        /** Grace before a closed conversation's context buffer is dropped: short
         *  peeks elsewhere (the list, a permission dialog, another app) keep the
         *  recording, a real goodbye does not — a later visit to the same chat is
         *  a NEW session, never a silent extension of the old one. */
        private const val CONTEXT_EXPIRE_GRACE_MS = 60_000L

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
