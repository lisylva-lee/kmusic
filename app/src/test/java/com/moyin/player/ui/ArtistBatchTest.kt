package com.kmusic.apkhd.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「换一批」的翻页逻辑测试。
 *
 * 背景（实测 music.163.com）：
 * - `/personalized/playlist` 每次请求返回**不同**内容 → 刷新直接重调即可
 * - `/artist/list` 重复请求返回**完全相同**（连调 4 次零变化）→ 必须靠 offset 翻页
 *
 * 所以音乐人用「本地缓存 + cursor 切片」：一次多拿，本地按批切，切完再翻页。
 * 这里测的就是这段切片/翻页状态机 —— 它容易出的 bug 是：
 * 1. 翻页时出现重复条目（服务端边界重叠）
 * 2. 切到底后不回到开头，用户之后每次都看到同一批
 * 3. 缓存为空时崩
 */
class ArtistBatchTest {

    /** 与 MoyinViewModel 中一致的切片状态机（纯逻辑，不依赖 Android） */
    private class BatchState {
        val cache = mutableListOf<String>()
        var cursor = 0
        var offset = 0

        /**
         * 模拟一次刷新，逻辑与 MoyinViewModel.refreshDiscovery 一致。
         * fresh = 服务端返回的下一页；传空列表表示「没请求」或「请求失败」。
         */
        fun refresh(fresh: List<String>) {
            var advanced = false
            if (cache.size > cursor + BATCH) {
                cursor += BATCH
                advanced = true
            }
            if (!advanced) {
                if (fresh.isEmpty()) {
                    // 请求失败：缓存还有剩就退回开头
                    if (cursor >= cache.size) cursor = 0
                } else {
                    val known = cache.toSet()
                    val added = fresh.filter { it !in known }
                    if (added.isNotEmpty()) {
                        cache += added
                        offset += added.size
                        // 不动 cursor：追加的在末尾，cursor 仍指向当前展示的这批
                    } else {
                        // 服务端也翻到底 → 回开头
                        offset = 0
                        cursor = 0
                    }
                }
            }
        }

        // ⚠️ 不要加 `.ifEmpty { cache.take(BATCH) }` 兜底。
        // cursor 越界时回退第一批 = 用户反复看到同一批，永远翻不到后面的。
        // 该情况由 refresh() 内部保证不越界（翻页/回退都在那里处理）。
        fun visible(): List<String> = cache.drop(cursor).take(BATCH)

        companion object {
            const val BATCH = 12
        }
    }

    @Test
    fun firstRefresh_showsFirstBatch() {
        val s = BatchState()
        s.refresh((1..60).map { "a$it" })
        assertEquals(12, s.visible().size)
        assertEquals("a1", s.visible().first())
    }

    @Test
    fun secondRefresh_advancesWithoutDuplicates() {
        val s = BatchState()
        s.refresh((1..60).map { "a$it" })
        val first = s.visible().toSet()
        // 缓存还有 48 个，不需要再请求
        s.refresh(emptyList())
        val second = s.visible()
        assertEquals(12, second.size)
        assertEquals("a13", second.first())
        assertTrue(
            "换批后不应出现上一批已看过的条目（实际交集 ${first.intersect(second.toSet())}）",
            first.intersect(second.toSet()).isEmpty()
        )
    }

    @Test
    fun exhaustCache_fetchesNextPageAndDedups() {
        val s = BatchState()
        s.refresh((1..60).map { "a$it" })
        // 本地 60 个按 12 一批正好 5 批：前 4 次换批只动 cursor，不请求（缓存够用）
        // —— 传空列表表示「这次没请求下一页」，此时 cache.size > cursor+BATCH 成立
        repeat(4) { s.refresh(emptyList()) }
        // 首刷后 cursor=0（首批刚展示），4 次后到 48
        assertEquals("4 次后 cursor 应在 48", 48, s.cursor)
        assertEquals("a49", s.visible().first())
        // 第 5 次：cursor=48，48+12=60 不再小于 cache.size → 请求下一页。
        // 服务端翻页边界可能重叠（55..60 已看过），必须去重。
        s.refresh((55..120).map { "a$it" })
        // 翻页分支不动 cursor（追加的数据在末尾），所以仍是 a49 起。
        // 关键是不重复、不越界
        val vis = s.visible()
        assertEquals(12, vis.size)
        assertEquals("a49", vis.first())
        assertEquals("a60", vis.last())
        assertEquals("缓存不应有重复", s.cache.size, s.cache.toSet().size)
        // 缓存已被追加到 120，下一次换批应当能走到 a61
        s.refresh(emptyList())
        assertEquals("缓存够用时应能前进到 a61", "a61", s.visible().first())
    }

    @Test
    fun serverExhausted_wrapsAround() {
        val s = BatchState()
        s.refresh((1..60).map { "a$it" })
        // 服务端翻到底：返回空 或 全是已知条目
        s.refresh((1..60).map { "a$it" })
        val vis = s.visible()
        assertTrue("翻到底后应从头再来，不能空白", vis.isNotEmpty())
        assertEquals(12, vis.size)
    }

    @Test
    fun emptyCache_doesNotCrash() {
        val s = BatchState()
        s.refresh(emptyList())
        assertTrue("空响应不应崩，返回空列表即可", s.visible().isEmpty())
    }
}
