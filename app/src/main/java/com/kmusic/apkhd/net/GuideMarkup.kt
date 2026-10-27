package com.kmusic.apkhd.net

/**
 * 把 AI 导赏的流式纯文本切成可渲染的块。
 *
 * ## 为什么不用 JSON
 *
 * 导赏是**边生成边显示**的（打字机效果），JSON 没闭合就解析不了 ——
 * 末尾少一个 `}` 或模型把 ```json 围栏一起吐出来，整篇就作废，而 token 已经花掉了。
 * 国内几家 OpenAI 兼容端点还经常不听 JSON 指令：外面套围栏、前面加「好的，以下是…」。
 * 歌词翻译当时也是因为这个原因选了「序号|文本」而不是 JSON，这里沿用同样的取舍。
 *
 * 所以走的是**容错的行协议**：模型守约定就出结构化卡片，不守就退化成普通段落，
 * 永远不会整篇消失。
 *
 * ## 识别规则（都很宽松，宁可不识别也不要吃掉正文）
 *
 * ```
 * ## 创作背景          → 小节标题（也认 ###/####、【创作背景】、创 作 背 景）
 * > 从前相生怎会到日后  → 金句引用（连续多行合并成一组）
 * - 弦乐 / * 弦乐      → 列表项
 * ---  ***  ___       → 分割线（丢弃原字符，画一条真线）
 * 其余                 → 段落
 * ```
 *
 * 第一段如果很短（≤28 字）且不含标点结尾，按「导语 / 一句话主旨」处理，
 * 界面上放大加粗显示，不占正文的字号。
 */
object GuideMarkup {

    sealed interface Block {
        /** 文档开头的一句话主旨 */
        data class Lead(val text: String) : Block

        /** 小节标题 */
        data class Section(val title: String) : Block

        /** 正文段落 */
        data class Para(val text: String) : Block

        /** 歌词金句（连续多行已合并） */
        data class Quote(val lines: List<String>) : Block

        /** 列表（第一个元素可能为空串，表示无序列表） */
        data class Bullets(val items: List<String>) : Block

        /** 横向分割线 */
        data object Rule : Block
    }

    /** 形如 `## 创作背景` / `###创作背景` / `#### 创作背景` */
    private val RE_HASH_HEAD = Regex("""^\s{0,3}#{1,6}\s*(.+?)\s*#*\s*$""")

    /** 形如 `【创作背景与内核】` —— 模型很爱用这个当标题 */
    private val RE_BRACKET_HEAD = Regex("""^\s*[【\[]([^】\]]{2,20})[】\]]\s*[:：]?\s*$""")

    /** 形如 `**创作背景**` 独占一行（整行只有粗体包裹） */
    private val RE_BOLD_HEAD = Regex("""^\s*\*\*(.+?)\*\*\s*[:：]?\s*$""")

    /** 形如 `创 作 背 景` —— 中间被空格/全角空格隔开的短行 */
    private val RE_SPACED = Regex("""^\s*([^\s]{1,2}(?:[ \u3000]+[^\s]{1,2}){1,8})\s*$""")

    /** 分割线：3 个以上的 - * _ ，允许中间夹空格 */
    private val RE_RULE = Regex("""^\s*([-*_])\s*(?:\1\s*){2,}$""")

    /** 代码围栏标记行：``` 或 ```json。模型偶尔不听话直接吐 JSON */
    private val RE_FENCE = Regex("""^\s*```.*$""")

    /** JSON 残渣行：围栏里如果是 JSON，宁可丢掉也不要把 `{"badge": ...}` 画到屏幕上 */
    private val RE_JSON_ISH = Regex("""^\s*[{\["]\s*[}\]"]?\s*$|^\s*"[^"]*"\s*:\s*.*$|^\s*[}\]]\s*,?\s*$""")

    /** 引用：`> 文字` / `＞ 文字` */
    private val RE_QUOTE = Regex("""^\s*[>＞]\s?(.*)$""")

    /**
     * 列表：`- x` / `* x` / `• x` / `1. x` / `1、x`
     *
     * 中英文的空格规则不一样：`- 项目` 必须有空格（否则 `-《书名》` 会被误吞），
     * 但 `1、项目` 里中文顿号后面通常直接跟字，不能强制要求空格。
     */
    private val RE_BULLET = Regex("""^\s*(?:[-*•·]\s+|\d{1,2}[.、)]\s*)(.*)$""")

    /** 行内粗体 `**x**` 与斜体 `*x*`：只去标记，不做富文本（正文统一样式就够干净） */
    private val RE_INLINE_BOLD = Regex("""\*\*(.+?)\*\*""")
    private val RE_INLINE_ITALIC = Regex("""(?<![\w*])\*([^*\n]{1,80})\*(?![\w*])""")
    private val RE_INLINE_CODE = Regex("""`([^`\n]{1,80})`""")

    /** 中文/英文句末标点 —— 用来判断第一段是不是「一句话主旨」 */
    private const val SENTENCE_END = "。！？!?…；;，,、"

    /**
     * 解析全文。
     *
     * 传入的是**每次追加后的完整文本**（流式场景），所以这个函数必须便宜：
     * 只做一次按行切分 + 一次线性扫描，不做正则回溯密集操作。
     * 300~450 字的量级，每帧重算也无压力。
     */
    fun parse(raw: String): List<Block> {
        if (raw.isBlank()) return emptyList()

        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<Block>(12)

        val para = StringBuilder()
        var paraLines = 0
        val quotes = ArrayList<String>(3)
        val bullets = ArrayList<String>(4)

        fun flushPara() {
            val t = para.toString().trim()
            val single = paraLines == 1
            para.setLength(0)
            paraLines = 0
            if (t.isEmpty()) return
            // 文档第一个内容块，且在原文里就独占一行、够短、不是完整句 → 当成一句话主旨
            val isLead = out.isEmpty() && single &&
                t.length <= 28 && t.lastOrNull()?.let { it in SENTENCE_END } != true
            out.add(if (isLead) Block.Lead(t) else Block.Para(t))
        }

        fun flushQuotes() {
            if (quotes.isNotEmpty()) {
                out.add(Block.Quote(quotes.toList()))
                quotes.clear()
            }
        }

        fun flushBullets() {
            if (bullets.isNotEmpty()) {
                out.add(Block.Bullets(bullets.toList()))
                bullets.clear()
            }
        }

        fun flushAll() {
            flushPara(); flushQuotes(); flushBullets()
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            // 空行：结束当前累积块
            if (line.isBlank()) {
                flushAll()
                continue
            }

            // 代码围栏 / JSON 残渣：直接丢弃。模型不听话吐 JSON 时，
            // 与其把 `{"badge": "AI 导赏"}` 和 ``` 画到屏幕上，不如什么都不显示。
            if (RE_FENCE.matches(line) || RE_JSON_ISH.matches(line)) continue

            // 分割线（要在列表之前判，否则 `---` 会被当成「- 空列表项」）
            if (RE_RULE.matches(line)) {
                flushAll()
                // 连续分割线只留一条，避免模型用它当段落间隔刷出一排横线
                if (out.lastOrNull() !== Block.Rule) out.add(Block.Rule)
                continue
            }

            // 引用：连续行为一组
            val quote = RE_QUOTE.matchEntire(line)
            if (quote != null) {
                flushPara(); flushBullets()
                val body = inline(quote.groupValues[1])
                if (body.isNotBlank()) quotes.add(body)
                continue
            }

            // 列表项
            val bullet = RE_BULLET.matchEntire(line)
            if (bullet != null) {
                flushPara(); flushQuotes()
                val body = inline(bullet.groupValues[1])
                if (body.isNotBlank()) bullets.add(body)
                continue
            }

            // 三级标题
            val head = heading(line)
            if (head != null) {
                flushAll()
                out.add(Block.Section(head))
                continue
            }

            // 普通正文：并到当前段落（模型常把一句话硬折成几行）
            flushQuotes(); flushBullets()
            if (para.isNotEmpty()) {
                // 中文行与行之间不加空格直接拼，英文之间补一个，避免粘连
                val prev = para.last()
                val body = inline(line.trim())
                if (needsSpace(prev, body)) para.append(' ')
                para.append(body)
            } else {
                para.append(inline(line.trim()))
            }
            paraLines++
        }
        flushAll()

        return out
    }

    /** 识别标题；不是标题返回 null */
    private fun heading(line: String): String? {
        RE_HASH_HEAD.matchEntire(line)?.let { return cleanHead(it.groupValues[1]) }
        RE_BRACKET_HEAD.matchEntire(line)?.let { return cleanHead(it.groupValues[1]) }
        // `**创作背景**` 独占一行
        RE_BOLD_HEAD.matchEntire(line)?.let { return cleanHead(it.groupValues[1]) }
        // `创 作 背 景`：按空白切完只剩 2~9 个 1~2 字的碎片，且总字数 ≤ 12
        RE_SPACED.matchEntire(line)?.let {
            val pieces = line.trim().split(Regex("""[ \u3000]+"""))
            if (pieces.size in 2..9 && pieces.joinToString("").length <= 12) {
                return cleanHead(pieces.joinToString(""))
            }
        }
        return null
    }

    private fun cleanHead(s: String): String =
        inline(s).trim().removeSuffix("：").removeSuffix(":").trim()

    /** 去行内标记（粗体/斜体/行内码）。刻意不做富文本：正文统一样式反而更好读 */
    private fun inline(s: String): String {
        var t = s
        t = RE_INLINE_BOLD.replace(t, "$1")
        t = RE_INLINE_CODE.replace(t, "$1")
        t = RE_INLINE_ITALIC.replace(t, "$1")
        return t.trim()
    }

    /** 中文与中文之间不补空格，其它情况补 */
    private fun needsSpace(prev: Char, next: String): Boolean {
        if (next.isEmpty()) return false
        val a = prev
        val b = next.first()
        val cjkA = a.code in 0x4E00..0x9FFF
        val cjkB = b.code in 0x4E00..0x9FFF
        return !(cjkA && cjkB)
    }
}
