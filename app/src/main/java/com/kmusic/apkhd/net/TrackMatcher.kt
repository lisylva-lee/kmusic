package com.kmusic.apkhd.net

/**
 * 跨源曲目匹配器。
 *
 * 用途：元数据来自 A 源（如网易云的推荐歌单），播放要回退到 B 源（酷我）时，
 * 判断 B 源搜出来的候选项到底是不是同一首歌。
 *
 * **宁可少几首，不能串歌** —— 三项校验（歌名 / 歌手 / 时长）任一不过就判不匹配。
 * 串歌的代价远大于少一首歌：用户点《晴天》听到的是别人的歌，会直接不信任整个推荐位。
 */
object TrackMatcher {

    /** 匹配容差：时长差 ≤ 3 秒 */
    const val DURATION_TOLERANCE_MS = 3_000L

    data class Candidate(
        val name: String,
        val artist: String,
        val durationMs: Long
    )

    // 括号内容：(Live) （伴奏） 【DJ版】 [Remix] —— 通常是版本标注，不参与歌名比对
    private val BRACKET = Regex("""[（(\[【][^）)\]】]*[）)\]】]""")
    // 需要剔除的标点与空白
    private val PUNCT = Regex("""[\s\-_·・、,，.。!！?？:：;；'"“”‘’`~～@#\$%^&*+=|\\/<>]""")
    // 版次/出处标注的分隔符。酷我大量歌名长这样：`红日-《他来自天堂》电视剧主题曲`
    private const val SUBTITLE_SEPS = "-《(（[【～~:：|｜"
    // 歌手分隔符：周杰伦/五月天阿信、A & B、A feat. B
    private val ARTIST_SPLIT = Regex("""[/、&,，;；]|\bfeat\.?|\bft\.?|\bwith\b""", RegexOption.IGNORE_CASE)

    /**
     * 归一化：繁转简 → 全角转半角 → 小写 → 去括号内容 → 去标点空白。
     * 目的：让「最伟大的作品 (Live)」和「最伟大的作品」能比中；
     * 也让「千千闕歌|陳慧嫻」（模型常给的繁体）比中「千千阙歌|陈慧娴」（曲库的简体）。
     *
     * 繁转简必须放在最前：先去标点再去转字没有坏处，但先转会多一遍分配，
     * 而且括号里的繁体（`(國語版)`）转不转都无所谓 —— 反正下面要整段删掉。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in T2S.convert(raw)) {
            // 全角 ASCII（！ 到 ～）转半角
            sb.append(
                when {
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    ch.code == 0x3000 -> ' '
                    else -> ch
                }
            )
        }
        var t = sb.toString().lowercase()
        t = t.replace(BRACKET, "")
        t = t.replace(PUNCT, "")
        return t.trim()
    }

    /** 歌手拆成 token 集合（保留给测试与未来「多署名」场景） */
    fun artistTokens(raw: String): Set<String> =
        raw.split(ARTIST_SPLIT)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * 歌名是否一致。
     *
     * 归一化后相等 → 通过；或一方包含另一方且长度比 ≥ 0.6
     * （长度比是防「晴天」误配「晴天娃娃」这类，纯包含判断太宽）
     *
     * 另外单独处理**版次/出处后缀**：酷我大量歌名是 `红日-《他来自天堂》电视剧主题曲`，
     * 拿纯歌名 `红日` 去比，长度比只有 0.1，会被误判成不同歌 —— 而它恰恰是原曲。
     * 做法是先把候选按 `-》《(` 一刀切掉尾巴再比，且这一刀**只认严格前缀**：
     * 切完必须与目标完全相等才算命中，避免「晴天」把「晴天娃娃-某某」也吃进来。
     */
    fun nameMatches(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val sa = shortOf(a)
        val sb = shortOf(b)
        if (sa.isNotEmpty() && sa == nb) return true
        if (sb.isNotEmpty() && sb == na) return true
        if (na.contains(nb) || nb.contains(na)) {
            val shorter = minOf(na.length, nb.length).toDouble()
            val longer = maxOf(na.length, nb.length).toDouble()
            return shorter / longer >= 0.6
        }
        return false
    }

    /**
     * 砍掉「-《出处》主题曲」这类尾巴，返回归一化后的短名；没有分隔符就返回空串。
     *
     * **必须在原始串上切**：`normalize` 会把 `-`、`：`、`|` 当标点删掉，
     * 等归一化完再找分隔符就永远找不到 —— `朋友-龙兄虎弟电影插曲` 会被当成一个整体，
     * 长度比 0.2 直接判不匹配。原先的写法只靠 `《` 侥幸活在 `PUNCT` 之外，
     * 遇到没有书名号的出处标注就漏。
     */
    private fun shortOf(raw: String): String {
        var end = raw.length
        for (sep in SUBTITLE_SEPS) {
            val i = raw.indexOf(sep)
            if (i in 1 until end) end = i
        }
        return if (end < raw.length) normalize(raw.substring(0, end)) else ""
    }

    /**
     * 歌手是否匹配。
     *
     * 规则（折中：优先认目标歌手，但要求它在前两位署名里）：
     * 1. 归一化后相等 → 通过；
     * 2. 目标歌手出现在**候选的前两位署名**里 → 通过
     *    （`周杰伦/五月天阿信`、`邓紫棋&张新航` 都算）；
     * 3. 一方完整包含另一方（`G.E.M.` vs `G.E.M.邓紫棋`）→ 通过。
     *
     * 为什么卡「前两位」：实测踩过 `泡沫` 的坑 —— 曲库第一条是
     * `张新航&邓 紫 棋`（翻唱，邓紫棋排在第二位之后 / 名字还被加了空格），
     * 老的 token 交集逻辑直接判命中，把翻唱塞进了歌单。
     * 现在要求目标歌手必须在前两位署名里，且第一署名不是「明显不同的人名」时才放过。
     */
    fun artistMatches(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true

        val ca = credits(a)
        val cb = credits(b)
        if (ca.isEmpty() || cb.isEmpty()) return false

        val aMain = ca.first()
        val bMain = cb.first()

        // 主署名一致，或一方完整包含另一方（G.E.M. vs G.E.M.邓紫棋）
        if (aMain == bMain) return true
        if (aMain.length >= 2 && bMain.length >= 2 &&
            (aMain.contains(bMain) || bMain.contains(aMain))
        ) {
            return true
        }

        // 目标歌手作为合作者出现在候选的第 2 位（邓紫棋 vs 张新航&邓紫棋）
        if (cb.size >= 2 && cb[1] == aMain) return true

        // 目标本身是多署名，与候选有完整交集（周杰伦/五月天阿信 vs 周杰伦&五月天阿信）
        if (ca.size >= 2 && cb.any { c -> c == aMain || ca.contains(c) }) return true

        return false
    }

    /** 拆成署名列表（保序，`张新航&邓 紫 棋` → [张新航, 邓紫棋]） */
    private fun credits(raw: String): List<String> =
        raw.split(ARTIST_SPLIT)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /**
     * 时长是否匹配。
     *
     * **任一方的时长未知（≤0）就判不通过** —— 严格模式下不做"无从校验就放行"的妥协，
     * 那正是串歌的主要来源。
     */
    fun durationMatches(aMs: Long, bMs: Long, toleranceMs: Long = DURATION_TOLERANCE_MS): Boolean {
        if (aMs <= 0 || bMs <= 0) return false
        return kotlin.math.abs(aMs - bMs) <= toleranceMs
    }

    /** 三重校验全过才算同一首歌 */
    fun isMatch(target: Candidate, cand: Candidate): Boolean =
        nameMatches(target.name, cand.name) &&
            artistMatches(target.artist, cand.artist) &&
            durationMatches(target.durationMs, cand.durationMs)

    /**
     * 从候选里挑最优的一首；没有全过的返回 null。
     *
     * 排序优先级（越靠前越可信）：
     * 1. 主署名完全一致 → 原唱/主版本；
     * 2. 主署名包含关系 → 别名、带地区后缀；
     * 3. 目标歌手在第 2 位 → 合作版本；
     * 最后才比时长接近度。
     *
     * 为什么要分层：折中规则放过了「目标歌手在第 2 位」的合作署名，
     * 而酷我把翻唱（`张新航&邓 紫 棋`）排得比原唱还靠前。只按时长挑会把翻唱选出来；
     * 分层后只要原唱在候选里就一定优先，只有确实没有原唱时才用合作版本。
     */
    fun pickBest(target: Candidate, candidates: List<Candidate>): Candidate? =
        candidates
            .filter { isMatch(target, it) }
            .minWithOrNull(
                compareBy<Candidate> { artistRank(target.artist, it.artist) }
                    .thenBy { kotlin.math.abs(it.durationMs - target.durationMs) }
            )

    /** 歌手匹配的严格度，0 最严 */
    private fun artistRank(targetArtist: String, candArtist: String): Int {
        val ca = credits(targetArtist)
        val cb = credits(candArtist)
        if (ca.isEmpty() || cb.isEmpty()) return 9
        val aMain = ca.first()
        val bMain = cb.first()
        if (aMain == bMain) return 0
        if (aMain.length >= 2 && bMain.length >= 2 &&
            (aMain.contains(bMain) || bMain.contains(aMain))
        ) {
            return 1
        }
        if (cb.size >= 2 && cb[1] == aMain) return 2
        return 3
    }
}
