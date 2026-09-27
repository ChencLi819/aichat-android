package com.jev.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.isChromeLine
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.ContextSessions
import com.jev.probe.jev.ChatAiClient
import com.jev.probe.jev.JudgePrompts
import com.jev.probe.jev.LlmJudgeClient
import com.jev.probe.jev.ReplyClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the whole analysis pipeline over simulated everyday conversations.
 *
 * The network tests use whatever route the app itself is configured with (they
 * read the key out of the app's own prefs, so no key is ever written to source)
 * and are skipped when that route is unconfigured. They assert INVARIANTS —
 * every field populated, every choice inside its allowed set — never exact
 * wording, because the model is free to disagree with any particular label.
 *
 * The context-buffer tests are pure logic and always run.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {

    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val key = InstrumentationRegistry.getArguments().getString("judgeKey")?.trim()
        prefs = if (key.isNullOrBlank()) {
            // No test route supplied: use whatever the app itself is configured
            // with (the live tests skip when that is empty too).
            Prefs(ctx)
        } else {
            // A scratch config, so a test run neither depends on nor disturbs the
            // app's own saved settings.
            ctx.getSharedPreferences(SCRATCH, Context.MODE_PRIVATE).edit().clear().commit()
            Prefs(ctx, SCRATCH).apply {
                judgeKey = key
                val args = InstrumentationRegistry.getArguments()
                args.getString("judgeBaseUrl")?.takeIf { it.isNotBlank() }
                    ?.let { judgeBaseUrl = it }
                args.getString("judgeModel")?.takeIf { it.isNotBlank() }
                    ?.let { judgeModel = it }
                // The reply route mirrors the judge route (same key, same host).
                replyBaseUrl = judgeBaseUrl
                replyModel = judgeModel
            }
        }
    }

    // ------------------------------------------------------- simulated chats

    /** Everyday couple chat with a test buried in it (the classic "you promised"). */
    private val everydayWithSubtext = ChatSnapshot(
        "小鱼",
        listOf(
            Msg("me", "我到家了"),
            Msg("me", "今天加班到九点，累死了"),
            Msg("other", "嗯"),
            Msg("other", "你上次答应我的事，还记得吗")
        )
    )

    /** Light banter — nothing at stake. */
    private val casualChat = ChatSnapshot(
        "阿哲",
        listOf(
            Msg("other", "周末那家店你还去不去"),
            Msg("me", "去啊，几点"),
            Msg("other", "随便，中午吧，我请")
        )
    )

    /** Open conflict with a concrete ask. */
    private val openConflict = ChatSnapshot(
        "老张",
        listOf(
            Msg("me", "那个报表我明天给你"),
            Msg("other", "明天？你上周就说下周"),
            Msg("other", "这次再拖，我就直接跟老板说了")
        )
    )

    // ------------------------------------------------------ prompt assembly

    @Test
    fun systemPromptCarriesEveryQuestionAndTheContract() {
        val prompt = JudgePrompts.renderSystemPrompt()
        listOf(
            "literal_question", "true_intent", "danger_level",
            "should_reply_now", "best_action", "she_needs", "tension_resolved"
        ).forEach { key ->
            assertTrue("system prompt is missing 【$key】", prompt.contains("【$key】"))
        }
        // The parse relies on these exact field names, so the contract must name them.
        assertTrue(prompt.contains("\"danger_level\""))
        assertTrue(prompt.contains("\"true_intent\""))
        assertTrue(prompt.contains("\"score\""))
        assertTrue(prompt.contains("\"choice\""))
        // Every choice question must list its option values, or the model invents them.
        assertTrue(prompt.contains("confirm_you_care"))
        assertTrue(prompt.contains("check_history"))
        assertTrue(prompt.contains("nothing"))
    }

    @Test
    fun userMessageHonoursTheConfiguredWindow() {
        val msg = JudgePrompts.renderUserMessage(
            snapshot = everydayWithSubtext,
            relationship = "伴侣",
            background = "背景X",
            history = emptyList(),
            window = 2
        )
        // Only the newest two lines, and the newest speaker named correctly.
        assertTrue(msg.contains("你上次答应我的事，还记得吗"))
        assertTrue(msg.contains("嗯"))
        assertFalse("window=2 must drop the older line", msg.contains("今天加班到九点"))
        assertTrue(msg.contains("最新一条是对方发的"))
        assertTrue(msg.contains("背景X"))
    }

    // --------------------------------------------------- context buffer (pure)

    @Test
    fun sessionCountsOnlyFromTheMomentRecordingStarts() {
        ContextSessions.clearAll()
        val onScreen = listOf(Msg("me", "我到家了"), Msg("other", "嗯"))
        ContextSessions.inject("小鱼", onScreen)

        // Re-reading the same window must not backfill what predates the start.
        ContextSessions.append("小鱼", onScreen, 100_000)
        assertNull("pre-existing lines must not be recorded", ContextSessions.render("小鱼", 100_000))

        ContextSessions.append("小鱼", onScreen + Msg("other", "你在干嘛"), 100_000)
        val text = ContextSessions.render("小鱼", 100_000)
        assertNotNull(text)
        assertTrue(text!!.contains("你在干嘛"))
        assertFalse(text.contains("我到家了"))
        ContextSessions.clearAll()
    }

    @Test
    fun sessionsAreIndependentPerConversation() {
        ContextSessions.clearAll()
        ContextSessions.inject("小鱼", emptyList())
        ContextSessions.inject("阿哲", emptyList())
        ContextSessions.append("小鱼", listOf(Msg("other", "只属于小鱼的一句")), 100_000)

        assertTrue(ContextSessions.render("小鱼", 100_000)!!.contains("只属于小鱼的一句"))
        assertNull("another window must not see it", ContextSessions.render("阿哲", 100_000))

        ContextSessions.clear("小鱼")
        assertFalse(ContextSessions.isActive("小鱼"))
        assertTrue("clearing one window must not touch another", ContextSessions.isActive("阿哲"))
        ContextSessions.clearAll()
    }

    @Test
    fun sessionTrimsToTheConfiguredBudgetOldestFirst() {
        ContextSessions.clearAll()
        ContextSessions.inject("小鱼", emptyList())
        // 60 lines of ~45 chars ≈ 2.9k, against a 1k budget: the cap has to bite,
        // and it has to bite from the OLD end.
        repeat(60) { i ->
            ContextSessions.append("小鱼", listOf(Msg("other", "第${i}条消息" + "内容".repeat(20))), 1_000)
        }
        val text = ContextSessions.render("小鱼", 1_000)!!
        assertTrue("newest line must survive the cap", text.contains("第59条消息"))
        assertFalse("oldest line must be dropped", text.contains("第0条消息"))
        assertTrue("the cap must actually bound the buffer", text.length < 1_400)
        ContextSessions.clearAll()
    }

    // -------------------------------------------- capture noise vs real content

    @Test
    fun chromeLinesDoNotFakeANewMessage() {
        val base = listOf(Msg("me", "我到家了"), Msg("other", "你上次答应我的事还记得吗"))
        val quiet = ChatSnapshot("小鱼", base)
        // A typing indicator / receipt / date strip is captured as a "message"
        // by the tree and OCR paths — it must not look like new content, or the
        // panel regenerates on its own (the reported bug).
        val noisy = ChatSnapshot("小鱼", base + Msg("other", "对方正在输入…"))
        assertEquals(quiet.signature(), noisy.signature())
        assertEquals("other", noisy.latestFrom)
        assertEquals("你上次答应我的事还记得吗", noisy.lastIncoming())
    }

    @Test
    fun chromeAloneIsNotSomethingToAnswer() {
        val onlyChrome = ChatSnapshot("小鱼", listOf(Msg("me", "在"), Msg("other", "对方正在输入…")))
        assertNull(onlyChrome.lastIncoming())
        assertEquals("me", onlyChrome.latestFrom)
        // Timestamps and read receipts are chrome too.
        assertTrue(isChromeLine("10:23"))
        assertTrue(isChromeLine("已读"))
        assertTrue(isChromeLine("昨天"))
        assertFalse(isChromeLine("你上次答应我的事还记得吗"))
    }

    @Test
    fun aRealNewMessageChangesTheIdentity() {
        val a = ChatSnapshot("小鱼", listOf(Msg("other", "第一句")))
        val b = ChatSnapshot("小鱼", listOf(Msg("other", "第一句"), Msg("other", "第二句")))
        assertNotEquals("catch-up 必须对真正的新消息触发", a.lastIncoming(), b.lastIncoming())
    }

    @Test
    fun appendingWithoutRecordingIsANoOp() {
        ContextSessions.clearAll()
        ContextSessions.append("没开记录的会话", listOf(Msg("other", "不该被存")), 100_000)
        assertFalse(ContextSessions.isActive("没开记录的会话"))
        assertNull(ContextSessions.render("没开记录的会话", 100_000))
        ContextSessions.clearAll()
    }

    // ------------------------------------------------------ live pipeline

    @Test
    fun judgmentIsCompleteForAnEverydayConversation() {
        assumeTrue("no judge route configured; skipping live test", prefs.hasKey())
        val analysis = LlmJudgeClient(prefs).judge(everydayWithSubtext, prefs.relationship)

        assertNull("judge returned an error: ${analysis.error}", analysis.error)
        assertNotNull("true_intent missing", analysis.trueIntent)
        assertTrue(
            "true_intent not in the allowed set: ${analysis.trueIntent!!.choice}",
            analysis.trueIntent!!.choice in TRUE_INTENT
        )
        assertNotNull("danger_level missing", analysis.dangerLevel)
        assertTrue(
            "danger score out of range: ${analysis.dangerLevel!!.score}",
            analysis.dangerLevel!!.score in 0.0..9.0
        )
        assertNotNull("she_needs missing", analysis.sheNeeds)
        assertTrue(
            "she_needs not in the allowed set: ${analysis.sheNeeds!!.choice}",
            analysis.sheNeeds!!.choice in SHE_NEEDS
        )
        assertNotNull("best_action missing", analysis.bestAction)
        assertTrue(
            "best_action not in the allowed set: ${analysis.bestAction!!.choice}",
            analysis.bestAction!!.choice in BEST_ACTION
        )
        // The three 0..1 questions must be answered, not left null.
        assertNotNull("literal_question missing", analysis.literalQuestion)
        assertNotNull("should_reply_now missing", analysis.shouldReplyNow)
        assertNotNull("tension_resolved missing", analysis.tensionResolved)
    }

    @Test
    fun judgmentHoldsForConflictAndBanter() {
        assumeTrue("no judge route configured; skipping live test", prefs.hasKey())
        val client = LlmJudgeClient(prefs)
        listOf("冲突" to openConflict, "闲聊" to casualChat).forEach { (label, snap) ->
            val a = client.judge(snap, prefs.relationship)
            assertNull("$label: judge returned an error: ${a.error}", a.error)
            assertNotNull("$label: danger_level missing", a.dangerLevel)
            assertTrue("$label: danger score out of range", a.dangerLevel!!.score in 0.0..9.0)
        }
    }

    @Test
    fun candidateRepliesComeBackThreeAndRanked() {
        assumeTrue("no reply route configured; skipping live test", prefs.hasKey())
        val client = ChatAiClient(prefs)
        val ranked = client.draftAndRank(everydayWithSubtext, prefs.relationship)

        assertEquals("must always offer 3 candidates", 3, ranked.size)
        ranked.forEach { assertTrue("blank candidate", it.text.isNotBlank()) }
        // Sorted best-first; the rank route may tie, so this is <=, not <.
        assertEquals(ranked.sortedByDescending { it.prob }.map { it.text }, ranked.map { it.text })
    }

    @Test
    fun replyDraftingParsesIntoThreeScoredLines() {
        assumeTrue("no reply route configured; skipping live test", prefs.hasKey())
        val drafts = ReplyClient(prefs).draft(everydayWithSubtext, prefs.relationship)
        assertEquals(3, drafts.size)
        drafts.forEach { assertTrue("blank draft", it.text.isNotBlank()) }
        // The drafting call scores its own candidates now, so a well-formed
        // answer must actually carry scores — otherwise the panel's "#1 · 62%"
        // would read 0% across the board.
        assertTrue("no candidate carried a score", drafts.any { it.prob > 0.0 })
    }

    @Test
    fun knowledgeContextReachesTheJudge() {
        assumeTrue("no judge route configured; skipping live test", prefs.hasKey())
        val ctx = ChatContext(null, emptyList(), emptyList(), session = "对方的名字叫小鱼，我们在一起三年了。")
        assertFalse(ctx.isEmpty())
        assertTrue(ctx.background("伴侣").contains("小鱼"))
        val a = LlmJudgeClient(prefs).judge(everydayWithSubtext, prefs.relationship, ctx)
        assertNull("judge failed with a session context: ${a.error}", a.error)
    }

    private companion object {
        /** Scratch prefs file for a test-supplied route; never the app's own. */
        const val SCRATCH = "pipeline_test_route"

        val TRUE_INTENT = setOf(
            "confirm_you_care", "vent_anger", "request_action",
            "seek_explanation", "casual_chat", "close_topic"
        )
        val SHE_NEEDS = setOf("apology", "action", "explanation", "care", "nothing")
        val BEST_ACTION = setOf(
            "check_history", "apologize", "give_commitment",
            "explain", "acknowledge", "say_less", "make_plan"
        )
    }
}
