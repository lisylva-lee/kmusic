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
    // 歌手分隔符：周杰伦/五月天阿信、A & B、A feat. B
    private val ARTIST_SPLIT = Regex("""[/、&,，;；]|\bfeat\.?|\bft\.?|\bwith\b""", RegexOption.IGNORE_CASE)

    /**
     * 归一化：全角转半角 → 小写 → 去括号内容 → 去标点空白。
     * 目的：让「最伟大的作品 (Live)」和「最伟大的作品」能比中。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
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

    /** 歌手拆成 token 集合，便于「周杰伦/五月天阿信」这类多人署名比对 */
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
     */
    fun nameMatches(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        if (na.contains(nb) || nb.contains(na)) {
            val shorter = minOf(na.length, nb.length).toDouble()
            val longer = maxOf(na.length, nb.length).toDouble()
            return shorter / longer >= 0.6
        }
        return false
    }

    /**
     * 歌手是否匹配。
     *
     * 归一化后相等 → 通过；互相包含 → 通过；token 有交集 → 通过。
     * 例：目标歌手「周杰伦」vs 候选「周杰伦/五月天阿信」→ token 交集命中。
     */
    fun artistMatches(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        if (na.contains(nb) || nb.contains(na)) return true
        return artistTokens(a).intersect(artistTokens(b)).isNotEmpty()
    }

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
     * 多个命中时取时长最接近的。
     */
    fun pickBest(target: Candidate, candidates: List<Candidate>): Candidate? =
        candidates
            .filter { isMatch(target, it) }
            .minByOrNull { kotlin.math.abs(it.durationMs - target.durationMs) }
}
