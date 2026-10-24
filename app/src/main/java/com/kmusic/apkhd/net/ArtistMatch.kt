package com.kmusic.apkhd.net

/**
 * 判断搜索词是不是某个歌手，返回他的 id。
 *
 * 为什么需要：酷我搜「周杰伦」时，歌手搜索（`ft=artist`）除了周杰伦本人，
 * 还会返回一堆名字里含这三个字的**翻唱歌手/同名歌手**。只有精确同名才算命中，
 * 否则宁可当普通关键词处理 —— 认错了会把别人的专辑当成他的。
 */
object ArtistMatch {

    /** 归一化：只留字母/数字/汉字，忽略大小写、空格与标点 */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * @param keyword 用户输入
     * @param artistNames 歌手搜索结果里的歌手名（按相关度排序）
     * @return 精确同名歌手的**下标**，没有则 null
     */
    fun matchIndex(keyword: String, artistNames: List<String>): Int? {
        val want = norm(keyword)
        if (want.isEmpty()) return null
        for ((i, n) in artistNames.withIndex()) {
            if (norm(n) == want) return i
        }
        return null
    }

    /**
     * 同样规则，但直接要 id。
     * @param artistIds 与 artistNames 一一对应
     */
    fun pickArtistId(keyword: String, artistNames: List<String>, artistIds: List<String>): String? {
        val i = matchIndex(keyword, artistNames) ?: return null
        return artistIds.getOrNull(i)?.ifEmpty { null }
    }
}
