package com.moyin.player.net

import com.kmusic.apkhd.net.GuideMarkup
import com.kmusic.apkhd.net.GuideMarkup.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导赏排版解析的回归用例。
 *
 * 这些样例都取自真机抓到的模型输出（DeepSeek / gemini 两家），
 * 包括它不守约定的写法 —— 解析层必须把这些都吃下去，最差退化成段落。
 */
class GuideMarkupTest {

    private fun types(blocks: List<Block>) = blocks.map {
        when (it) {
            is Block.Lead -> "lead"
            is Block.Section -> "section"
            is Block.Para -> "para"
            is Block.Quote -> "quote"
            is Block.Bullets -> "bullets"
            Block.Rule -> "rule"
        }
    }

    // ---------------- Markdown 标记 ----------------

    @Test
    fun hashHeadings_becomeSections() {
        val blocks = GuideMarkup.parse(
            """
            ## 创作背景与内核
            作为港剧《富贵门》的片尾曲，作品把镜头对准名利场。

            ### 词作与隐喻解读
            周博贤的词作冷峻而极具撕裂感。
            """.trimIndent()
        )
        assertEquals(listOf("section", "para", "section", "para"), types(blocks))
        assertEquals("创作背景与内核", (blocks[0] as Block.Section).title)
    }

    @Test
    fun bracketHeadings_becomeSections() {
        val blocks = GuideMarkup.parse(
            """
            【创作背景与内核】
            正文一。

            【听觉与编曲亮点】
            正文二。
            """.trimIndent()
        )
        assertEquals(listOf("section", "para", "section", "para"), types(blocks))
        assertEquals("听觉与编曲亮点", (blocks[2] as Block.Section).title)
    }

    @Test
    fun boldHeadingOnOwnLine_becomesSection() {
        val blocks = GuideMarkup.parse("**创作背景**\n正文内容在这里。")
        assertEquals(listOf("section", "para"), types(blocks))
        assertEquals("创作背景", (blocks[0] as Block.Section).title)
    }

    @Test
    fun spacedHeading_becomesSection() {
        // 模型有时把标题写成「创 作 背 景」
        val blocks = GuideMarkup.parse("创 作 背 景\n正文内容在这里。")
        assertEquals(listOf("section", "para"), types(blocks))
        assertEquals("创作背景", (blocks[0] as Block.Section).title)
    }

    @Test
    fun hrules_becomeRule_andCollapse() {
        val blocks = GuideMarkup.parse("正文一。\n\n---\n\n***\n\n正文二。")
        assertEquals(listOf("para", "rule", "para"), types(blocks))
        // 连续两条分割线只留一条
        assertEquals(1, blocks.count { it === Block.Rule })
    }

    @Test
    fun ruleIsNotMistakenForBullet() {
        // `---` 会被 RE_BULLET 当成「- 空内容」，必须先判分割线
        val blocks = GuideMarkup.parse("---")
        assertEquals(listOf("rule"), types(blocks))
    }

    @Test
    fun inlineMarkupIsStripped() {
        val blocks = GuideMarkup.parse("这首歌用了 **弦乐** 和 `电吉他`，还有 *克制的鼓点*。")
        val text = (blocks.single() as Block.Para).text
        assertFalse(text.contains("**"))
        assertFalse(text.contains("`"))
        assertTrue(text.contains("弦乐"))
        assertTrue(text.contains("电吉他"))
        assertTrue(text.contains("克制的鼓点"))
    }

    // ---------------- 金句引用 ----------------

    @Test
    fun quotes_groupConsecutiveLines() {
        val blocks = GuideMarkup.parse(
            """
            > 从前相生怎会到日后
            > 沦落到相克的对手
            正文解读文字。
            """.trimIndent()
        )
        assertEquals(listOf("quote", "para"), types(blocks))
        val q = blocks[0] as Block.Quote
        assertEquals(listOf("从前相生怎会到日后", "沦落到相克的对手"), q.lines)
    }

    @Test
    fun fullWidthQuoteMark_alsoRecognised() {
        val blocks = GuideMarkup.parse("＞ 回赠我开锋的匕首")
        assertEquals(listOf("quote"), types(blocks))
    }

    // ---------------- 列表 ----------------

    @Test
    fun bullets_groupTogether() {
        val blocks = GuideMarkup.parse("- 弦乐铺底\n- 流行摇滚骨架\n- 克制的鼓点")
        assertEquals(listOf("bullets"), types(blocks))
        assertEquals(3, (blocks[0] as Block.Bullets).items.size)
    }

    @Test
    fun numberedList_alsoBullets() {
        val blocks = GuideMarkup.parse("1. 弦乐铺底\n2、流行摇滚骨架")
        assertEquals(listOf("bullets"), types(blocks))
        assertEquals(2, (blocks[0] as Block.Bullets).items.size)
    }

    // ---------------- 导语 ----------------

    @Test
    fun shortFirstLine_becomesLead() {
        val blocks = GuideMarkup.parse("从并肩走向相克\n\n正文第一段，内容比较长而且有标点。")
        assertEquals(listOf("lead", "para"), types(blocks))
        assertEquals("从并肩走向相克", (blocks[0] as Block.Lead).text)
    }

    @Test
    fun longFirstParagraph_staysParagraph() {
        val long = "作为港剧《富贵门》的片尾曲，作品把镜头对准名利场中人性与情感的异化"
        val blocks = GuideMarkup.parse(long)
        assertEquals(listOf("para"), types(blocks))
    }

    @Test
    fun firstLineEndingWithPeriod_staysParagraph() {
        // 有句末标点说明是完整句子，不该被当成主旨放大
        val blocks = GuideMarkup.parse("从并肩走向相克。\n\n正文。")
        assertEquals(listOf("para", "para"), types(blocks))
    }

    // ---------------- 容错：模型不守约定 ----------------

    @Test
    fun plainTextWithoutAnyMarkup_singleParagraph() {
        val blocks = GuideMarkup.parse("这是一段没有任何标记的纯文本导赏，模型偶尔就这么给。")
        assertEquals(listOf("para"), types(blocks))
    }

    @Test
    fun halfWrittenStreamingText_rendersPartial() {
        // 流式到一半的状态：标题写完了，正文一句没结束
        val blocks = GuideMarkup.parse("## 创作背景\n作为港剧《富贵门》的片尾")
        assertEquals(listOf("section", "para"), types(blocks))
        assertTrue((blocks[1] as Block.Para).text.contains("富贵门"))
    }

    @Test
    fun emptyInput_noBlocks() {
        assertTrue(GuideMarkup.parse("").isEmpty())
        assertTrue(GuideMarkup.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun hardWrappedChineseLines_areJoinedWithoutSpace() {
        // 模型常把一句中文硬折成几行 —— 拼接时不能塞空格
        val blocks = GuideMarkup.parse("这是一句被硬折成\n两行的中文句子")
        val text = (blocks.single() as Block.Para).text
        assertEquals("这是一句被硬折成两行的中文句子", text)
    }

    @Test
    fun hardWrappedEnglishLines_getSpace() {
        val blocks = GuideMarkup.parse("this line is\nhard wrapped")
        val text = (blocks.single() as Block.Para).text
        assertEquals("this line is hard wrapped", text)
    }

    @Test
    fun jsonFence_isNotSwallowed() {
        // 万一模型真的吐了 JSON 围栏，至少不能把 ``` 当正文画到屏幕上
        val blocks = GuideMarkup.parse("```json\n{\"badge\": \"AI 导赏\"}\n```")
        val anyBacktick = blocks.filterIsInstance<Block.Para>().any { it.text.contains("```") }
         assertFalse(anyBacktick)
    }

    @Test
    fun realModelOutput_fullStructure() {
        // 真机抓到的一篇完整输出（DeepSeek 的典型写法）
        val raw = """
            从并肩走向相克

            ## 创作背景与内核
            作为港剧《富贵门》的片尾曲，作品将镜头对准了名利场中人性与情感的异化。它剥离了浮华的商战外衣，剖析了亲密关系在欲望面前的脆弱与崩塌。

            ---

            ## 词作与隐喻解读
            > 从前相生怎会到日后，沦落到相克的对手
            > 回赠我开锋的匕首

            周博贤的词作冷峻而极具撕裂感。

            ## 听觉与编曲亮点
            - 弦乐铺底
            - 克制的鼓点
        """.trimIndent()

        val blocks = GuideMarkup.parse(raw)
        assertEquals(
            listOf("lead", "section", "para", "rule", "section", "quote", "para", "section", "bullets"),
            types(blocks)
        )
    }
}
