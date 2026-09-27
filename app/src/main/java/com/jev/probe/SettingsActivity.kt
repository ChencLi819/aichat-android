package com.jev.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.ChatCaptureService
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Palette
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ContextSessions
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.LlmJudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Palette.ACCENT
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 接口 ===================
        root.addView(section("接口"))

        // --- 判断接口（大模型） ---
        val judgeCard = card()
        judgeCard.addView(cardTitle("判断接口（大模型）"))
        judgeCard.addView(text("读对方消息、给意图判断和候选排序。必须配置。", 12f, sub))

        val judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.LLM_BASE_BAILIAN)
        val judgeModelEdit = edit(prefs.judgeModel, Prefs.LLM_MODEL_BAILIAN_DEEPSEEK)
        judgeProviderIdx = presetIndexOf(prefs.judgeBaseUrl, prefs.judgeModel, judge = true)
        judgeCard.addView(pills(LLM_PRESETS.map { it.label }, judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            val p = LLM_PRESETS[idx]
            // 自定义 leaves both boxes alone: they hold the user's own values.
            if (p.base.isNotBlank()) {
                judgeBaseEdit.setText(p.base)
                judgeModelEdit.setText(p.judgeModel)
            }
        })
        judgeCard.addView(label("Base URL（填到 /v1 为止）"))
        judgeCard.addView(judgeBaseEdit)
        judgeCard.addView(text("所有档位都是标准 OpenAI 兼容接口，走 /chat/completions（流式）。" +
            "百炼判断时显式开启思考（enable_thinking）；DeepSeek 官方判断发 thinking:enabled、回复发 disabled（2026-09 文档）。", 11f, sub))
        judgeCard.addView(label("密钥"))
        judgeCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        judgeCard.addView(label("模型"))
        judgeCard.addView(judgeModelEdit)
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…（思考模型约需 10–30 秒）"
            // Address wins over the pill, so a stale pill selection cannot send
            // one provider's model to another provider's host.
            val provider = resolveJudgeProvider(judgeProviderIdx, base)
            if (provider == Prefs.PROVIDER_CUSTOM && base.isBlank()) {
                judgeResult.text = "自定义档要填地址"; return@cardBtn
            }
            // Custom means we know nothing about the endpoint — guessing a model
            // name here would test something the user never asked for.
            if (provider == Prefs.PROVIDER_CUSTOM && model.isBlank()) {
                judgeResult.text = "请填写模型名"; return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_JUDGE) {
                judgeProvider = provider
                judgeBaseUrl = base.ifBlank { defaultJudgeBase(provider) }
                judgeKey = key
                judgeModel = model.ifBlank { defaultJudgeModel(provider) }
                analysisWindow = prefs.analysisWindow
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = LlmJudgeClient(probe).judge(demo, prefs.relationship)
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("生成 3 条候选回复。任何 OpenAI 兼容地址，填到 /v1 为止。", 12f, sub))

        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.LLM_BASE_BAILIAN)
        val replyModelEdit = edit(prefs.replyModel, Prefs.LLM_MODEL_BAILIAN_DEEPSEEK)
        val replyIdx = presetIndexOf(prefs.replyBaseUrl, prefs.replyModel, judge = false)
        replyCard.addView(pills(LLM_PRESETS.map { it.label }, replyIdx) { idx ->
            val p = LLM_PRESETS[idx]
            if (p.base.isNotBlank()) {
                replyBaseEdit.setText(p.base)
                replyModelEdit.setText(p.replyModel)
            }
        })
        replyCard.addView(label("Base URL"))
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥"))
        replyCard.addView(edit(prefs.replyKey, "留空则用判断接口密钥", password = true).also { replyKeyEdit = it })
        replyCard.addView(label("模型"))
        replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_REPLY) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = base.ifBlank { Prefs.LLM_BASE_BAILIAN }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.LLM_MODEL_BAILIAN_DEEPSEEK }
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥（或填判断接口密钥）"; return@cardBtn }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).ping()
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("视觉接口（OCR 用，可先不填）"))
        visionCard.addView(text("读不到控件树的 App 走截图识别。B 阶段才用到，现在填不填都不影响。", 12f, sub))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEFAULT_VISION_MODEL)
        val visionIdx = VISION_PRESETS.indexOfFirst {
            it.base.isNotBlank() && it.base == prefs.visionBaseUrl.trim().trimEnd('/') &&
                it.model == prefs.visionModel.trim()
        }.let { if (it < 0) VISION_PRESETS.size - 1 else it }
        visionCard.addView(pills(VISION_PRESETS.map { it.label }, visionIdx) { idx ->
            val p = VISION_PRESETS[idx]
            if (p.base.isNotBlank()) { visionBaseEdit.setText(p.base); visionModelEdit.setText(p.model) }
        })
        visionCard.addView(label("Base URL"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥", password = true).also { visionKeyEdit = it })
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_VISION) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.LLM_BASE_BAILIAN }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（或填回复/判断接口密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // --- 网络代理（VPN/加速器的本机端口） ---
        val proxyCard = card()
        proxyCard.addView(cardTitle("网络代理（VPN/加速器）"))
        proxyCard.addView(text("本机 VPN（Clash、v2rayNG 等）通常在本机开代理端口。填上并启用后，" +
            "判断/回复/视觉所有模型请求都走它——线路慢时换个快节点即可提速。", 12f, sub))
        val proxyRow = toggleRow("启用代理", prefs.proxyEnabled)
        proxyCard.addView(proxyRow)
        var proxyTypeSel = if (prefs.proxyType == Prefs.PROXY_SOCKS) 1 else 0
        proxyCard.addView(pills(listOf("HTTP", "SOCKS5"), proxyTypeSel) { idx -> proxyTypeSel = idx })
        proxyCard.addView(label("地址"))
        val proxyHostEdit = edit(prefs.proxyHost.ifBlank { "" }, "127.0.0.1")
        proxyCard.addView(proxyHostEdit)
        proxyCard.addView(label("端口"))
        val proxyPortEdit = edit(prefs.proxyPort.toString(), "7890").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        proxyCard.addView(proxyPortEdit)
        proxyCard.addView(text("留空地址或关闭开关 = 直连。保存后用「测试判断」验证——它走的就是这条代理。", 11f, sub))
        root.addView(proxyCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给模型判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        val autoRerunRow = toggleRow("自动确认重跑（免确认）", prefs.autoConfirmRerun)
        card2.addView(autoRerunRow)
        card2.addView(text("关闭后，凡是由助手自己发起的分析都会先在悬浮窗里问一句，点「立即分析」才开始（同一段新消息只问一次）。" +
            "手动点「分析当前对话」「重新分析」不受影响。", 11f, sub))

        // --- OCR 兜底（B 阶段）---
        val ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书正文是画上去的，节点树里读不到，这时截一次屏本地识别（不上传）。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库与历史") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "密钥、白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空知识库与历史"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Deliberately low-key: a developer aid, not a user feature.
        card2.addView(text("自检", 12f, sub).apply {
            setPadding(dp(2), dp(12), dp(8), dp(2))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { kbResult.text = out }
                }
            }
        })
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 上下文 ===================
        root.addView(section("上下文"))
        val ctxCard = card()
        ctxCard.addView(cardTitle("一次分析的上下文"))
        ctxCard.addView(text("每次分析带上最近几条消息（1–100）。", 12f, sub))
        val winEdit = edit(prefs.analysisWindow.toString(), "10").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ctxCard.addView(winEdit)
        ctxCard.addView(label("单个会话上下文上限（tokens，默认 1M）"))
        val maxTokEdit = edit(prefs.contextMaxTokens.toString(), "1000000").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ctxCard.addView(maxTokEdit)
        ctxCard.addView(label("AI 生成最大字数（tokens，256–32768）"))
        val maxOutEdit = edit(prefs.maxOutputTokens.toString(), "2048").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ctxCard.addView(maxOutEdit)
        ctxCard.addView(text("只限制候选回复和「问 AI」的回答长度；判断结果不设上限。" +
            "注意上限包含模型的思考过程，设太小会把回答截断。", 11f, sub))
        ctxCard.addView(text("用法：在聊天窗口长按悬浮球 →「注入上下文到本会话」开始记录。" +
            "记录从点下那一刻算起，之前屏幕上已有的消息不算（那部分每次分析本来就会带上）；" +
            "这个聊天聊完，再选「清除本会话上下文」清掉。每个聊天窗口的上下文各自独立，" +
            "清一个不影响别的窗口，也不写进知识库。", 11f, sub))
        val sessionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessionBoxRef = sessionBox
        ctxCard.addView(sessionBox)
        ctxCard.addView(cardBtn("清除全部会话上下文") {
            val n = ContextSessions.clearAll()
            // The panel's analysis records are part of the same conversation
            // state — they go with the contexts, in the live service if any.
            ChatCaptureService.instance?.clearAllAnalysisHistory()
            refreshSessions()
            Toast.makeText(this, "已清除 $n 个会话的上下文", Toast.LENGTH_SHORT).show()
        })
        root.addView(ctxCard)

        // =================== 外观 ===================
        root.addView(section("外观"))

        // Custom RGB first, so the opacity slider below can repaint the preview.
        val rEdit = edit(((prefs.panelColor shr 16) and 0xFF).toString(), "255").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val gEdit = edit(((prefs.panelColor shr 8) and 0xFF).toString(), "255").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val bEdit = edit((prefs.panelColor and 0xFF).toString(), "255").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val preview = TextView(this).apply {
            text = "预览"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(ink)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        /** Repaint the swatch, so the colour is visible before it is saved. */
        fun paintPreview(opacityPct: Int) {
            val c = rgbFrom(rEdit, gEdit, bEdit)
            val a = (opacityPct / 100f * 255).roundToInt().coerceIn(150, 255)
            preview.background = round(dp(10), Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)))
        }

        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                    paintPreview(p + 60)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)

        // A tint that matches the chat behind the panel, instead of always white.
        // Alpha stays the slider's job, so the two settings are independent.
        card3.addView(label("面板背景色（RGB，0–255）"))
        card3.addView(rgbRow("R", rEdit))
        card3.addView(rgbRow("G", gEdit))
        card3.addView(rgbRow("B", bEdit))
        card3.addView(preview)
        listOf(rEdit, gEdit, bEdit).forEach { e ->
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) =
                    paintPreview(seek.progress + 60)
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        paintPreview(seek.progress + 60)
        card3.addView(cardBtn("恢复白色背景") {
            rEdit.setText("255"); gEdit.setText("255"); bEdit.setText("255")
        })
        card3.addView(text("拖动悬浮球可以换位置，长按弹出菜单；分析框右上角的 ⤡ 可以拖动改大小。" +
            "被系统清掉的话：把本应用加入自启动白名单、电池设为「无限制」（首页有分机型的具体步骤）。", 11f, sub))
        root.addView(card3)

        // =================== 关于与隐私 ===================
        root.addView(section("关于与隐私"))
        val aboutCard = card()
        aboutCard.addView(text(
            "这个 App 会读取你当前聊天窗口的文字，发给你自己配置的模型接口做判断和起草回复。作者不运营服务器，收不到你的数据。",
            12f, sub))
        aboutCard.addView(cardBtn("隐私政策") { openUrl(PRIVACY_URL) })
        aboutCard.addView(cardBtn("开源仓库") { openUrl(REPO_URL) })
        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(10), 0, dp(2)) })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            // Address wins over the pill: a preset HOST in the box means that
            // preset's provider (and so its path), whatever the pill last said.
            val judgeBaseTyped = judgeBaseEdit.text.toString().trim()
            val judgeProv = resolveJudgeProvider(judgeProviderIdx, judgeBaseTyped)
            val judgeModelTyped = judgeModelEdit.text.toString().trim()
            prefs.judgeProvider = judgeProv
            // Blank falls back to THIS provider's preset — never OpenRouter's by
            // default. Custom is left exactly as typed (blank included): guessing
            // a URL for it would silently point somewhere the user did not choose.
            prefs.judgeBaseUrl = when {
                judgeBaseTyped.isNotBlank() -> judgeBaseTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeBase(judgeProv)
            }
            prefs.judgeKey = judgeKeyEdit.text.toString()
            prefs.judgeModel = when {
                judgeModelTyped.isNotBlank() -> judgeModelTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeModel(judgeProv)
            }

            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.LLM_BASE_BAILIAN }
            prefs.replyKey = replyKeyEdit.text.toString()
            prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.LLM_MODEL_BAILIAN_DEEPSEEK }

            prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
            prefs.visionKey = visionKeyEdit.text.toString()
            prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }

            prefs.proxyEnabled = (proxyRow.tag as? Boolean) ?: false
            prefs.proxyType = if (proxyTypeSel == 1) Prefs.PROXY_SOCKS else Prefs.PROXY_HTTP
            prefs.proxyHost = proxyHostEdit.text.toString().trim()
            prefs.proxyPort = proxyPortEdit.text.toString().trim().toIntOrNull() ?: 7890

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.autoConfirmRerun = (autoRerunRow.tag as? Boolean) ?: true
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.analysisWindow = winEdit.text.toString().trim().toIntOrNull() ?: 10
            prefs.contextMaxTokens = maxTokEdit.text.toString().trim().toIntOrNull()
                ?: Prefs.DEFAULT_CONTEXT_MAX_TOKENS
            prefs.maxOutputTokens = maxOutEdit.text.toString().trim().toIntOrNull()
                ?: Prefs.DEFAULT_MAX_OUTPUT_TOKENS
            prefs.panelColor = rgbFrom(rEdit, gEdit, bEdit)
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
        refreshSessions()
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    /** Holds the list of conversations that currently have a context injected. */
    private var sessionBoxRef: LinearLayout? = null

    /** Pill index for a saved provider value; unknown values fall back to custom. */
    private fun presetIndexOf(base: String, model: String, judge: Boolean): Int {
        val b = base.trim().trimEnd('/')
        val m = model.trim()
        return LLM_PRESETS.indexOfFirst {
            it.base.isNotBlank() && it.base == b &&
                (if (judge) it.judgeModel else it.replyModel) == m
        }.let { if (it < 0) LLM_PRESETS.size - 1 else it }
    }

    private fun presetFor(provider: String): LlmPreset? =
        LLM_PRESETS.firstOrNull { it.provider == provider }

    /**
     * The provider actually implied by what is in the address box. Leaving a
     * preset's host in the box while the pill says something else would send
     * that preset's model to the wrong host, so the address wins.
     */
    private fun resolveJudgeProvider(idx: Int, base: String): String {
        val b = base.trim().trimEnd('/')
        return LLM_PRESETS.firstOrNull { it.base.isNotBlank() && it.base == b }?.provider
            ?: LLM_PRESETS[idx].provider
    }

    private fun defaultJudgeBase(provider: String): String =
        presetFor(provider)?.base ?: Prefs.LLM_BASE_BAILIAN

    private fun defaultJudgeModel(provider: String): String =
        presetFor(provider)?.judgeModel ?: Prefs.LLM_MODEL_BAILIAN_DEEPSEEK

    /** One row per conversation that currently has a context injected. */
    private fun refreshSessions() {
        val box = sessionBoxRef ?: return
        box.removeAllViews()
        val rows = ContextSessions.summary()
        if (rows.isEmpty()) {
            box.addView(text("当前没有注入任何会话上下文", 12f, sub).apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        box.addView(label("已注入的会话（${rows.size}）"))
        rows.forEach { (title, lines, at) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(text("$title · $lines 条 · ${clock(at)}", 13f, ink).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "清除"; textSize = 13f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener {
                    ContextSessions.clear(title)
                    refreshSessions()
                    Toast.makeText(this@SettingsActivity, "已清除「$title」的上下文", Toast.LENGTH_SHORT).show()
                }
            })
            box.addView(row)
        }
    }

    private fun clock(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    /** "R" + its number box, on one line. */
    private fun rgbRow(name: String, field: EditText): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(text(name, 13f, ink, bold = true).apply {
            width = dp(20)
        })
        row.addView(field.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    /** The three boxes as one RGB int; a blank or out-of-range box reads as 255. */
    private fun rgbFrom(r: EditText, g: EditText, b: EditText): Int =
        ((r.text.toString().trim().toIntOrNull() ?: 255).coerceIn(0, 255) shl 16) or
            ((g.text.toString().trim().toIntOrNull() ?: 255).coerceIn(0, 255) shl 8) or
            (b.text.toString().trim().toIntOrNull() ?: 255).coerceIn(0, 255)

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
        return Prefs(this, scratchName).apply(fill)
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /**
         * One LLM preset: a label, its provider key, the host, and a model PER
         * ROUTE. Judge and reply want different models on some platforms —
         * DeepSeek official picks thinking via the model NAME (deepseek-reasoner
         * for judgment, deepseek-chat for replies), and on Bailian both routes
         * share deepseek-v4.1-flash but differ by the enable_thinking flag the
         * clients set per route.
         */
        private data class LlmPreset(
            val label: String,
            val provider: String,
            val base: String,
            val judgeModel: String,
            val replyModel: String
        )

        /** Judge and reply routes: all OpenAI-compatible, so they share one list. */
        private val LLM_PRESETS = listOf(
            LlmPreset("百炼 DeepSeek V4.1", Prefs.PROVIDER_DASHSCOPE,
                Prefs.LLM_BASE_BAILIAN, Prefs.LLM_MODEL_BAILIAN_DEEPSEEK, Prefs.LLM_MODEL_BAILIAN_DEEPSEEK),
            LlmPreset("DeepSeek 官方", Prefs.PROVIDER_DEEPSEEK,
                Prefs.LLM_BASE_DEEPSEEK, Prefs.LLM_MODEL_DEEPSEEK_FLASH, Prefs.LLM_MODEL_DEEPSEEK_FLASH),
            LlmPreset("百炼 通义千问", Prefs.PROVIDER_DASHSCOPE,
                Prefs.LLM_BASE_BAILIAN, Prefs.LLM_MODEL_BAILIAN_QWEN, Prefs.LLM_MODEL_BAILIAN_QWEN),
            LlmPreset("OpenAI", Prefs.PROVIDER_OPENAI,
                Prefs.LLM_BASE_OPENAI, Prefs.LLM_MODEL_OPENAI, Prefs.LLM_MODEL_OPENAI),
            LlmPreset("OpenRouter", Prefs.PROVIDER_OPENROUTER,
                Prefs.LLM_BASE_OPENROUTER, Prefs.LLM_MODEL_OPENROUTER, Prefs.LLM_MODEL_OPENROUTER),
            // Custom keeps whatever is in the boxes; blank base/model on purpose.
            LlmPreset("自定义", Prefs.PROVIDER_CUSTOM, "", "", "")
        )

        /** Vision presets. deepseek-flash (2026-09) has native vision, so the
         *  DeepSeek official host is on the list now; v4-pro does NOT, which the
         *  API error will say if a user hand-types it. */
        private data class VisionPreset(
            val label: String, val base: String, val model: String
        )

        private val VISION_PRESETS = listOf(
            VisionPreset("百炼 通义 VL", Prefs.DEFAULT_VISION_BASE, Prefs.DEFAULT_VISION_MODEL),
            VisionPreset("DeepSeek VL", Prefs.LLM_BASE_DEEPSEEK, Prefs.LLM_MODEL_DEEPSEEK_FLASH),
            VisionPreset("OpenRouter", Prefs.LLM_BASE_OPENROUTER, "qwen/qwen2.5-vl-72b-instruct"),
            VisionPreset("自定义", "", "")
        )

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"
        private const val REPO_URL = "https://github.com/jev-chat/jev-chat-jarvis"
    }
}
