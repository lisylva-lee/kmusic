package com.kmusic.apkhd.net

import com.kmusic.apkhd.model.Track
import java.util.Random

/**
 * 「今日推荐」的挑选逻辑。
 *
 * 设计取舍（都是有意为之，不是随手写的）：
 *
 * 1. **以「日」为种子，而不是每次进页面随机**。同一天反复进来必须是同一批，
 *    否则「今日推荐」就退化成「每次刷新推荐」—— 用户一眼看出是假东西。
 *    日期一变种子就变，自然换一批。
 * 2. **先按来源轮流取再乱序**。直接对整个池子洗牌，一次随机很可能 20 首全来自
 *    同一个平台；轮流取能保证两边的歌都上得来。
 * 3. **丢掉太短的**。不足 30 秒的基本是试听片段 / 间奏 / 报幕，不是歌。
 * 4. 去重按「歌名 + 歌手」归一化，两个源常有同一首歌。
 */
object DailyMix {

    /** 短于这个时长的不算歌（试听片段/间奏） */
    const val MIN_MS = 30_000L

    /** 一次推荐多少首 */
    const val DEFAULT_COUNT = 20

    /**
     * 当天序号（UTC 天）。
     *
     * 用毫秒数整除天数而不是按本地时区取日期：不引时区依赖，且行为稳定 ——
     * 反正只要「同一天同一种子、隔天不同种子」这一个性质。
     */
    fun todaySeed(nowMs: Long = System.currentTimeMillis()): Long = nowMs / 86_400_000L

    /** 清洗候选池：去空名、去太短、按「歌名+歌手」去重（保留先出现的） */
    fun clean(pool: List<Track>): List<Track> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Track>()
        for (t in pool) {
            if (t.name.isBlank()) continue
            if (t.durationMs in 1 until MIN_MS) continue
            val key = LyricQuery.norm(t.name) + "|" + LyricQuery.norm(t.artist)
            if (key == "|") continue
            if (!seen.add(key)) continue
            out += t
        }
        return out
    }

    /**
     * 挑出今天的推荐。
     *
     * @param pool 已 [clean] 过的候选池
     * @param seed [todaySeed]
     * @return 同一 seed 必然返回同一结果（含顺序）
     */
    fun pick(pool: List<Track>, seed: Long, count: Int = DEFAULT_COUNT): List<Track> {
        if (pool.isEmpty() || count <= 0) return emptyList()

        // 按来源轮流取，避免一次随机全落在一个平台上
        val bySource = pool.groupBy { it.source }
        val sources = bySource.keys.sorted()
        val interleaved = ArrayList<Track>(pool.size)
        var i = 0
        while (interleaved.size < pool.size) {
            var added = false
            for (src in sources) {
                val list = bySource[src] ?: continue
                if (i < list.size) {
                    interleaved += list[i]
                    added = true
                }
            }
            if (!added) break
            i++
        }

        val rnd = Random(seed * 2654435761L)
        return interleaved.shuffled(rnd).take(count)
    }

    /** 一步到位：清洗 + 挑选 */
    fun build(pool: List<Track>, seed: Long, count: Int = DEFAULT_COUNT): List<Track> =
        pick(clean(pool), seed, count)
}
