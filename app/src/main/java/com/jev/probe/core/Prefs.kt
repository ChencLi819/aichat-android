package com.jev.probe.core

import android.content.Context
import android.util.Log

/**
 * App-private config store. Holds the three API routes (judge / reply / vision),
 * the relationship description used in the judgment prompt, the conversation
 * whitelist, plus the context (D stage) and OCR (B stage) switches.
 *
 * All three routes are plain OpenAI-compatible chat hosts — no Jev endpoint is
 * involved anywhere.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) { migrateIfNeeded(); migrateToLlmIfNeeded() } }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    /**
     * v1.4 -> v1.5: the judge route stopped being a Jev endpoint and became an
     * ordinary OpenAI-compatible chat route. A provider value saved by the Jev
     * era ("bocha" / "typesafe" / "vercel" / "zen") is not a valid LLM preset,
     * and its address (a Jev host with a Jev path) would POST nowhere useful —
     * so reset that one route to the LLM default, exactly once. A user who had
     * already typed an LLM address keeps it: only known Jev hosts are replaced.
     */
    private fun migrateToLlmIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_LLM, false)) return
        val e = sp.edit().putBoolean(K_MIGRATED_LLM, true)
        val prov = sp.getString(K_JUDGE_PROVIDER, null)
        val base = sp.getString(K_JUDGE_BASE, "") ?: ""
        if ((prov != null && prov !in LLM_PROVIDERS) || base.contains("jev.bocha.cn") ||
            base.contains("api.typesafe.ai") || base.contains("ai-gateway.vercel.sh") ||
            base.contains("opencode.ai/zen")) {
            e.remove(K_JUDGE_PROVIDER).remove(K_JUDGE_BASE).remove(K_JUDGE_MODEL)
            Log.i(TAG, "prefs: reset Jev-era judge route to the LLM default")
        }
        e.apply()
    }

    // ---------------------------------------------------------------- judge

    /** Judgment route: any OpenAI-compatible chat host.
     *  "deepseek" | "dashscope" | "openrouter" | "openai" | "custom". */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_DASHSCOPE) ?: PROVIDER_DASHSCOPE
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** OpenAI-compatible base, up to and including `/v1`. */
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, LLM_BASE_BAILIAN) ?: LLM_BASE_BAILIAN
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    /** The model that answers the 7 judgment questions. */
    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, LLM_MODEL_BAILIAN_DEEPSEEK) ?: LLM_MODEL_BAILIAN_DEEPSEEK
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    /** Back-compat alias so older call sites keep compiling. */
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /**
     * OpenAI-compatible base, up to and including `/v1`. Defaults to the same
     * host as the judge route: [replyKey] already falls back to [judgeKey], so
     * one provider and one key are enough to run the whole app.
     */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, LLM_BASE_BAILIAN) ?: LLM_BASE_BAILIAN
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** Blank = fall back to [judgeKey]. */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, LLM_MODEL_BAILIAN_DEEPSEEK) ?: LLM_MODEL_BAILIAN_DEEPSEEK
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    /**
     * Blank = the OpenRouter vision default. Deliberately does NOT follow
     * [replyBaseUrl]: a reply host like DeepSeek has no vision endpoint, so
     * inheriting it would silently break OCR.
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    /** Blank = fall back to [replyKey] then [judgeKey]. */
    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // -------------------------------------------------------- context (D)

    /**
     * How many of the newest on-screen messages one analysis carries. This is
     * the per-analysis window; the per-conversation buffer the user injects
     * from the bubble menu is separate (see `ContextSessions`).
     */
    var analysisWindow: Int
        get() = sp.getInt(K_ANALYSIS_WINDOW, 10).coerceIn(1, 100)
        set(v) = sp.edit().putInt(K_ANALYSIS_WINDOW, v.coerceIn(1, 100)).apply()

    /**
     * Ceiling for one conversation's injected context, in tokens (approximated
     * by character count — one CJK character is about one token). Default 1M.
     */
    var contextMaxTokens: Int
        get() = sp.getInt(K_CTX_MAX_TOKENS, DEFAULT_CONTEXT_MAX_TOKENS)
            .coerceIn(MIN_CONTEXT_MAX_TOKENS, MAX_CONTEXT_MAX_TOKENS)
        set(v) = sp.edit().putInt(K_CTX_MAX_TOKENS,
            v.coerceIn(MIN_CONTEXT_MAX_TOKENS, MAX_CONTEXT_MAX_TOKENS)).apply()

    /**
     * `max_tokens` for the generative routes (candidate replies and "问 AI"
     * answers), so a long-winded model cannot run away.
     *
     * The judgment route sends NO cap at all: on a reasoning model the cap
     * counts the thinking tokens too, and a small value there truncates the
     * answer mid-thought into unparseable garbage. The same applies here — the
     * default leaves room for thinking plus the answer (a measured run spent
     * ~1000 tokens thinking before writing ~40).
     */
    var maxOutputTokens: Int
        get() = sp.getInt(K_MAX_OUTPUT, DEFAULT_MAX_OUTPUT_TOKENS)
            .coerceIn(MIN_MAX_OUTPUT_TOKENS, MAX_MAX_OUTPUT_TOKENS)
        set(v) = sp.edit().putInt(K_MAX_OUTPUT,
            v.coerceIn(MIN_MAX_OUTPUT_TOKENS, MAX_MAX_OUTPUT_TOKENS)).apply()

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------ proxy

    /**
     * User-configured proxy (the HTTP/SOCKS port a local VPN or accelerator
     * such as Clash / v2rayNG exposes). When enabled, EVERY model request —
     * judge, reply, vision — is tunneled through it; when anything is off or
     * malformed, requests go direct. Defaults are Clash's usual 7890.
     */
    var proxyEnabled: Boolean
        get() = sp.getBoolean(K_PROXY_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_PROXY_ENABLED, v).apply()

    /** [PROXY_HTTP] or [PROXY_SOCKS]. */
    var proxyType: String
        get() = sp.getString(K_PROXY_TYPE, PROXY_HTTP) ?: PROXY_HTTP
        set(v) = sp.edit().putString(K_PROXY_TYPE, v.trim()).apply()

    var proxyHost: String
        get() = sp.getString(K_PROXY_HOST, "") ?: ""
        set(v) = sp.edit().putString(K_PROXY_HOST, v.trim()).apply()

    var proxyPort: Int
        get() = sp.getInt(K_PROXY_PORT, 7890).coerceIn(1, 65535)
        set(v) = sp.edit().putInt(K_PROXY_PORT, v.coerceIn(1, 65535)).apply()

    /** The proxy to dial through, or null for a direct connection. */
    fun proxy(): java.net.Proxy? {
        if (!proxyEnabled) return null
        val host = proxyHost.trim()
        if (host.isEmpty()) return null
        val type = if (proxyType == PROXY_SOCKS) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        return java.net.Proxy(type, java.net.InetSocketAddress(host, proxyPort))
    }

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; goes into the judge prompt. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /**
     * Panel background colour, RGB only (alpha comes from [overlayOpacity], so
     * the two settings stay independent). Default white; a user who finds even a
     * translucent white panel in the way can tint it towards the chat's own
     * background instead.
     */
    var panelColor: Int
        get() = sp.getInt(K_PANEL_COLOR, DEFAULT_PANEL_COLOR)
        set(v) = sp.edit().putInt(K_PANEL_COLOR, v and 0xFFFFFF).apply()

    /** Panel width in px; -1 = the built-in default. */
    var panelWidth: Int
        get() = sp.getInt(K_PANEL_W, -1)
        set(v) = sp.edit().putInt(K_PANEL_W, v).apply()

    /** Panel content height in px; -1 = the built-in default. */
    var panelHeight: Int
        get() = sp.getInt(K_PANEL_H, -1)
        set(v) = sp.edit().putInt(K_PANEL_H, v).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /**
     * Whether an automatic round may start without asking.
     *
     * On (default): incoming messages are analysed as they arrive. Off: every
     * round the service would have started by itself first puts a yes/no card on
     * the panel (see OverlayController.requestRunConsent), so nothing is spent on
     * a round the user did not want. Rounds the user taps for — 分析当前对话 /
     * 重新分析 — never ask: the tap is the consent.
     */
    var autoConfirmRerun: Boolean
        get() = sp.getBoolean(K_AUTO_RERUN, true)
        set(v) = sp.edit().putBoolean(K_AUTO_RERUN, v).apply()

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    fun effectiveReplyKey(): String = replyKey.ifBlank { judgeKey }

    /** Vision route key, falling back to reply then judge. */
    fun effectiveVisionKey(): String = visionKey.ifBlank { effectiveReplyKey() }

    /** Full POST URL for the judgment route; same shape as [replyEndpoint]. */
    fun judgeEndpoint(): String = chatEndpoint(judgeBaseUrl, LLM_BASE_BAILIAN)

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String = chatEndpoint(replyBaseUrl, LLM_BASE_BAILIAN)

    /** Same shape as [replyEndpoint]; blank falls back to the vision default. */
    fun visionEndpoint(): String = chatEndpoint(visionBaseUrl, DEFAULT_VISION_BASE)

    /**
     * `<base>/chat/completions`, tolerating a base that already carries the
     * path (a user pasting the full endpoint from a provider's docs).
     */
    private fun chatEndpoint(base: String, fallback: String): String {
        val b = base.trim().ifBlank { fallback }.trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        return "$b/chat/completions"
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    fun hasKey(): Boolean = judgeKey.isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_MIGRATED_LLM = "prefs_migrated_llm_v15"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_CTX_MAX_TOKENS = "context_max_tokens"
        private const val K_MAX_OUTPUT = "max_output_tokens"
        private const val K_ANALYSIS_WINDOW = "analysis_window"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_PANEL_COLOR = "panel_color"
        private const val K_PANEL_W = "panel_width"
        private const val K_PANEL_H = "panel_height"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_AUTO_RERUN = "auto_confirm_rerun"
        private const val K_PROXY_ENABLED = "proxy_enabled"
        private const val K_PROXY_TYPE = "proxy_type"
        private const val K_PROXY_HOST = "proxy_host"
        private const val K_PROXY_PORT = "proxy_port"

        const val PROXY_HTTP = "http"
        const val PROXY_SOCKS = "socks"

        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_DASHSCOPE = "dashscope"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CUSTOM = "custom"

        /** Every provider value that is a real LLM preset (see [migrateToLlmIfNeeded]). */
        val LLM_PROVIDERS = setOf(
            PROVIDER_DEEPSEEK, PROVIDER_DASHSCOPE, PROVIDER_OPENROUTER, PROVIDER_OPENAI, PROVIDER_CUSTOM)

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // LLM presets. Every route speaks plain OpenAI-compatible
        // `/chat/completions`; only host + model differ between them.
        //
        // Bailian (DashScope's compatible mode) is the default: one key covers
        // both text routes and the vision route. `deepseek-v4.1-flash` is the
        // model id Bailian publishes for DeepSeek V4.1 (there is no bare
        // `deepseek-v4.1`; the other V4 ids are -pro / -pro-0813 / -flash-0731).
        const val LLM_BASE_BAILIAN = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val LLM_MODEL_BAILIAN_DEEPSEEK = "deepseek-v4.1-flash"
        const val LLM_MODEL_BAILIAN_QWEN = "qwen-plus"
        const val LLM_BASE_DEEPSEEK = "https://api.deepseek.com/v1"
        /** DeepSeek's current model (2026-09 docs): deepseek-flash = V4.1-Flash,
         *  1M context, native vision. Thinking is toggled per request via the
         *  `thinking` body field (see HttpJson) — the legacy names
         *  deepseek-chat / deepseek-reasoner were deprecated 2026-07-24. */
        const val LLM_MODEL_DEEPSEEK_FLASH = "deepseek-flash"
        const val LLM_BASE_OPENROUTER = "https://openrouter.ai/api/v1"
        const val LLM_MODEL_OPENROUTER = "deepseek/deepseek-chat"
        const val LLM_BASE_OPENAI = "https://api.openai.com/v1"
        const val LLM_MODEL_OPENAI = "gpt-4o-mini"

        // Vision route preset (multimodal host; user may change). Same Bailian
        // key as the text routes.
        const val DEFAULT_VISION_BASE = LLM_BASE_BAILIAN
        const val DEFAULT_VISION_MODEL = "qwen-vl-max"

        // Injected-context ceiling: 1M tokens by default, approximated by
        // characters. The floor keeps a stray "0" from silently disabling it.
        const val DEFAULT_CONTEXT_MAX_TOKENS = 1_000_000
        const val MIN_CONTEXT_MAX_TOKENS = 1_000
        const val MAX_CONTEXT_MAX_TOKENS = 2_000_000

        // Output ceiling for the generative routes. On a reasoning model the
        // cap counts the thinking tokens too (a measured run spent ~1000 tokens
        // thinking before writing ~40), so the default leaves room for both.
        // The judgment route sends no cap at all.
        const val DEFAULT_MAX_OUTPUT_TOKENS = 2_048
        const val MIN_MAX_OUTPUT_TOKENS = 256
        const val MAX_MAX_OUTPUT_TOKENS = 32_768

        /** Panel background: plain white, tinted per pixel by [overlayOpacity]. */
        const val DEFAULT_PANEL_COLOR = 0xFFFFFF

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
