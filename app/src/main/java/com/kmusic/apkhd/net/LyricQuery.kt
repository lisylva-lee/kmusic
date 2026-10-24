package com.kmusic.apkhd.net

/**
 * 歌词查询的**归一化与校验**。
 *
 * 为什么需要：lrclib 的 `/api/get` 要求歌名与歌手几乎逐字一致，而聚合源返回的
 * 名字带一堆后缀 —— 「晴天 (Live)」「以父之名 feat. 某人」「周杰伦/方文山」
 * 「歌名 - 歌手」…… 直接拿去查必然查不到，这就是「有些歌没有歌词」的主因。
 *
 * 另一半问题是兜底搜索**不校验**：取回一堆结果直接拿第一条有词的用，
 * 歌名相近但根本不是同一首时就会配错词。所以这里同时给出 [verify]。
 *
 * 抽成纯函数是为了能测 —— 匹配这事错了不会崩，只会静默地少词或者配错词。
 */
object LyricQuery {

    /**
     * 高频繁→简映射表。
     *
     * 为什么必须字级转换：lrclib 上大量条目是繁体（实测 '野心家' 6 条候选里
     * 4 条繁体），不归一化就匹配不上。而**不能**用「容忍少量字符差异」代替 ——
     * 实测同一首的繁简写法差 3 个字，而不同首的「爱上 / 爱上你」只差 1 个字，
     * 阈值无法区分，放松就会配错歌。
     *
     * 只收华语歌名/歌手的高频字（约 200 个）。表里没有的字保持原样 ——
     * 最坏情况退回原来的严格行为，不会误判。
     */
    private val TRAD_TO_SIMP: Map<Char, Char> = mapOf(
        '不' to '不', '丞' to '丞', '亮' to '亮', '今' to '今', '低' to '低', '佑' to '佑', '來' to '来', '依' to '依', '俊' to '俊', '個' to '个',
        '們' to '们', '倫' to '伦', '假' to '假', '傑' to '杰', '傷' to '伤', '價' to '价', '光' to '光', '兩' to '两', '冬' to '冬', '分' to '分',
        '刻' to '刻', '劉' to '刘', '午' to '午', '卻' to '却', '吳' to '吴', '哀' to '哀', '喜' to '喜', '單' to '单', '圓' to '圆', '土' to '土',
        '地' to '地', '塊' to '块', '壞' to '坏', '夏' to '夏', '夜' to '夜', '夢' to '梦', '太' to '太', '好' to '好', '姿' to '姿', '孤' to '孤',
        '孫' to '孙', '學' to '学', '寒' to '寒', '對' to '对', '山' to '山', '帶' to '带', '年' to '年', '張' to '张', '彩' to '彩', '影' to '影',
        '很' to '很', '後' to '后', '忘' to '忘', '快' to '快', '怒' to '怒', '怨' to '怨', '恨' to '恨', '悲' to '悲', '愁' to '愁', '愛' to '爱',
        '慢' to '慢', '憂' to '忧', '憶' to '忆', '戀' to '恋', '戲' to '戏', '新' to '新', '日' to '日', '早' to '早', '明' to '明', '昏' to '昏',
        '春' to '春', '昨' to '昨', '時' to '时', '晚' to '晚', '晨' to '晨', '暗' to '暗', '會' to '会', '月' to '月', '條' to '条', '榮' to '荣',
        '樂' to '乐', '歡' to '欢', '歲' to '岁', '沒' to '没', '沙' to '沙', '河' to '河', '泥' to '泥', '海' to '海', '涵' to '涵', '涼' to '凉',
        '淚' to '泪', '湖' to '湖', '溪' to '溪', '溫' to '温', '滅' to '灭', '滴' to '滴', '滿' to '满', '濃' to '浓', '灰' to '灰', '為' to '为',
        '無' to '无', '熱' to '热', '燕' to '燕', '獨' to '独', '琪' to '琪', '瑋' to '玮', '瑩' to '莹', '甄' to '甄', '痕' to '痕', '痛' to '痛',
        '瘋' to '疯', '發' to '发', '白' to '白', '真' to '真', '短' to '短', '石' to '石', '碎' to '碎', '秋' to '秋', '秒' to '秒', '穎' to '颖',
        '紅' to '红', '紫' to '紫', '結' to '结', '綠' to '绿', '綺' to '绮', '線' to '线', '緣' to '缘', '羅' to '罗', '習' to '习', '聲' to '声',
        '聽' to '听', '興' to '兴', '舊' to '旧', '色' to '色', '茹' to '茹', '華' to '华', '葉' to '叶', '蕭' to '萧', '藍' to '蓝', '蘇' to '苏',
        '裡' to '里', '褐' to '褐', '見' to '见', '記' to '记', '詞' to '词', '詩' to '诗', '話' to '话', '語' to '语', '說' to '说', '調' to '调',
        '謙' to '谦', '謝' to '谢', '讓' to '让', '貞' to '贞', '買' to '买', '賣' to '卖', '這' to '这', '遊' to '游', '遠' to '远', '還' to '还',
        '鄧' to '邓', '金' to '金', '銀' to '银', '銅' to '铜', '鋼' to '钢', '錢' to '钱', '錯' to '错', '鐘' to '钟', '鐵' to '铁', '長' to '长',
        '開' to '开', '間' to '间', '關' to '关', '陰' to '阴', '陳' to '陈', '陽' to '阳', '隻' to '只', '雙' to '双', '離' to '离', '雲' to '云',
        '霜' to '霜', '霧' to '雾', '露' to '露', '靚' to '靓', '靜' to '静', '音' to '音', '韻' to '韵', '響' to '响', '顆' to '颗', '題' to '题',
        '風' to '风', '飄' to '飘', '飛' to '飞', '騰' to '腾', '高' to '高', '麼' to '么', '黃' to '黄', '黑' to '黑', '點' to '点',
    )

    /** 繁→简。表外字符原样返回 */
    private fun toSimplified(s: String): String =
        if (s.none { it in TRAD_TO_SIMP }) s
        else s.map { TRAD_TO_SIMP[it] ?: it }.joinToString("")

    /** 归一化：转简体 → 只留字母/数字/汉字，忽略大小写、空格与标点 */
    fun norm(s: String): String = toSimplified(s).lowercase().filter { it.isLetterOrDigit() }

    /** 歌手分隔符：酷我/网易云常把合唱写成「周杰伦/方文山」「A、B」「A & B」 */
    private val ARTIST_SEP = Regex("[/、,，;；&]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE)

    /**
     * 横杠后的补充说明：'-原曲：xxx' '-《xxx》' '- Remix' '- Live'。
     *
     * ⚠️ 只在**横杠前后都有内容**时才切 —— 否则会把本身带连字符的歌名砍残
     * （'ABC-DEF' 这类、以及 'Rock-N-Roll' 之类会被误伤）。
     */
    private val DASH_NOTE = Regex("\\s*[-–—]\\s*\\S.*$")

    /** 所有括号字符（含书名号）。成对内容被吃掉后可能残留孤立的半个 */
    private val ANY_BRACKET = setOf('(', ')', '（', '）', '[', ']', '【', '】', '《', '》')

    /**
     * 匹配一对括号及其内容 —— **允许内容里再嵌括号**。
     *
     * 用 `[^()（）]*`（不含同类括号）而不是 `.*?`：非贪婪的 `.*?` 在
     * 'A(B)C(D)E' 上会只吃到 'A(B)'，把后面的 'C' 留下来；
     * 而这个版本要求括号内不能再出现**同种**括号，跨种嵌套留给下面的循环处理。
     */
    private val PAIR = mapOf(
        '(' to ')', '（' to '）', '[' to ']', '【' to '】', '《' to '》'
    )

    /** 从最内层开始，反复删掉成对的括号内容，直到没有成对括号为止 */
    private fun dropBracketed(s: String): String {
        var out = s
        var changed = true
        while (changed) {
            changed = false
            for ((open, close) in PAIR) {
                // 同种括号内不允许再出现同种括号 → 天然匹配最内层
                val re = Regex(
                    "\\" + open + "[^\\" + open + "\\" + close + "]*\\" + close
                )
                val next = re.replace(out, " ")
                if (next != out) {
                    out = next
                    changed = true
                }
            }
        }
        return out
    }

    /**
     * 去掉括号内容、feat. 尾巴、横杠补充说明。
     * 「晴天 (Live)」→「晴天」
     *
     * ⚠️ 分两阶段，且各自循环到稳定 —— 顺序错了就配不上，实测踩过：
     * '野心家-《灼灼韶华》影视剧主题曲' 砍横杠后得 '野心家'；
     * 而 '野心家(《灼灼韶華風禾起》主題曲)' 这种嵌套写法，
     * 若先砍横杠会让括号残破，必须**先把括号内容吃干净**。
     *
     * 实例（实测）：两种写法必须是同一首，strip 后都得到 '野心家'。
     */
    fun stripBrackets(s: String): String {
        // 阶段 1：括号内容吃到干净（含嵌套，反复到稳定）
        var out = dropBracketed(s)

        // 阶段 2：feat. / 横杠补充说明。每砍一刀都可能暴露出新的括号片段，
        // 所以砍完要回到阶段 1 再剥一轮，直到整体不再变化。
        var prev: String
        do {
            prev = out
            out = out.replace(Regex("\\b(feat|ft)\\.?\\s+.*$", RegexOption.IGNORE_CASE), " ")
            val dash = DASH_NOTE.find(out)
            // 横杠前至少 2 个字符才切，避免砍残 'X- Y'
            if (dash != null && dash.range.first >= 2) {
                out = out.substring(0, dash.range.first)
            }
            out = dropBracketed(out).replace(Regex("\\s+"), " ").trim()
        } while (out != prev)

        // 兜底：清掉任何残留的孤立括号字符（配对的另一边已被吃掉）
        out = out.filter { it !in ANY_BRACKET }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    /** 取第一个歌手。「周杰伦/方文山」→「周杰伦」 */
    fun firstArtist(artist: String): String =
        artist.split(ARTIST_SEP).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    /**
     * 生成候选 (歌名, 歌手)，**按可信度从高到低**。调用方按顺序试，命中即止。
     *
     * 顺序的理由：先用最干净的组合（成功率最高），再去掉歌手兜底
     * （有些源的歌手字段是错的/是专辑名，留着反而查不到）。
     */
    fun candidates(title: String, artist: String): List<Pair<String, String>> {
        val t0 = title.trim()
        if (t0.isEmpty()) return emptyList()
        val t = stripBrackets(t0).ifBlank { t0 }
        val a1 = firstArtist(artist)
        val a0 = artist.trim()

        return listOf(
            t to a1,                                  // 干净歌名 + 第一位歌手
            t to a0,                                  // 干净歌名 + 完整歌手串
            t to "",                                  // 干净歌名，不限歌手（查不到时最后一搏）
            t0 to a1                                  // 带括号的原歌名 + 第一位歌手（有些歌名括号是正名）
        ).distinct().filter { it.first.isNotBlank() }
    }

    /**
     * 非人身的歌手署名：OST / 原声带 / 影视剧名 / 综艺名。
     *
     * 实例（实测）：酷我里「爱上 / 刘宇宁」，网易云把这首 OST 的歌手登记成
     * 「玉簟秋/OST」—— 歌手字段压根不是人。若只按「包含关系」判，这种
     * 明明有 754 字符现成歌词的歌会被否掉。
     *
     * ⚠️ 放这一类进去**不会**放松到能配错歌：歌名仍必须归一化后完全相等，
     * 且只有在候选歌手看起来是集体署名时才豁免歌手判据。
     */
    private val COLLECTIVE_ARTIST = Regex(
        "ost|原声|影视|原声带|电视剧|电影|主题曲|插曲|片尾|宣传曲|" +
            "soundtrack|cast|various|群星|合辑|杂锦|精选|演唱会|live版",
        RegexOption.IGNORE_CASE
    )

    /** 候选歌手是不是「集体署名」而非具体歌手 */
    fun isCollectiveArtist(artist: String): Boolean =
        artist.isNotBlank() && COLLECTIVE_ARTIST.containsMatchIn(artist)

    /**
     * 判断候选结果**是不是**我们想找的那首歌。
     *
     * 判据：
     * - 歌名归一化后必须**完全相等**（不是包含 —— 包含会把「晴天」和「晴天 (Live)」
     *   甚至「晴天娃娃」混进来）
     * - 候选歌手为空、或是 OST/原声带这类集体署名 → 跳过歌手判据
     *   （源之间会差「周杰伦」vs「周杰伦/方文山」，OST 更是直接署名成剧名）
     * - 否则要求一方包含另一方
     * - 时长只用来**排序**，不在这里否决 —— 否则又会造出「明明有词却查不到」
     */
    fun verify(
        candidateTitle: String,
        candidateArtist: String,
        wantTitle: String,
        wantArtist: String
    ): Boolean {
        val w = norm(stripBrackets(wantTitle).ifBlank { wantTitle })
        if (w.isEmpty()) return false
        if (norm(stripBrackets(candidateTitle).ifBlank { candidateTitle }) != w) return false

        // 集体署名（OST / 原声带 / 剧名）不作为歌手判据 —— 它不是具体歌手
        if (isCollectiveArtist(candidateArtist)) return true

        val wa = norm(firstArtist(wantArtist))
        if (wa.isEmpty()) return true
        val ca = norm(candidateArtist)
        if (ca.isEmpty()) return true
        return ca.contains(wa) || wa.contains(ca)
    }

    /**
     * 时长接近度（秒）。用于在多个都通过校验的候选里挑最像的。
     * 任一时长未知就返回一个大值（不参与优选，但也不否决）。
     */
    fun durationDistance(wantSec: Long, candSec: Long): Long {
        if (wantSec <= 0 || candSec <= 0) return Long.MAX_VALUE / 2
        return kotlin.math.abs(wantSec - candSec)
    }
}
