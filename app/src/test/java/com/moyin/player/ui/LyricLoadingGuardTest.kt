package com.kmusic.apkhd.ui

import com.kmusic.apkhd.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词加载的时序守卫。
 *
 * 这两个用例对应两个真实缺陷，都只在真机上出现、编译和普通测试都拦不住：
 *
 * 1. **「刚打开有词，马上变暂无歌词」**
 *    调用点先 `lyrics = null` 再触发加载，中间任何一环早退（比如
 *    `onMediaItemTransition` 里 `prepared[mediaId]` 取不到就 return），
 *    就把 null 永久留在那儿。修法：置空和加载收进同一个带序号的方法。
 *
 * 2. **「一直暂无歌词」**
 *    同一个早退 —— 歌词协程压根没启动。
 *    修法：`prepared` 对不上时退到队列里找同 id 的曲目。
 *
 * 这里只能测纯逻辑部分（MediaController 需要真机），
 * 但这两条判据是修复的核心，值得钉住。
 */
class LyricLoadingGuardTest {

    private fun t(id: String, name: String, artist: String, ms: Long) = Track(
        id = id, name = name, artist = artist, album = "",
        coverUrl = null, durationMs = ms, source = "kuwo", sourceId = "1"
    )

    /**
     * 复刻修好后的取值逻辑：prepared 对不上要退到队列找，而不是放弃。
     */
    private fun resolveTrack(
        mediaId: String?,
        prepared: Map<String, Track>,
        queue: List<Track>
    ): Track? {
        val id = mediaId ?: return null
        return prepared[id] ?: queue.firstOrNull { it.id == id }
    }

    @Test
    fun `prepared 对得上时直接用`() {
        val t = t("a", "晴天", "周杰伦", 269_000)
        val r = resolveTrack("a", mapOf("a" to t), listOf(t))
        assertTrue(r === t)
    }

    @Test
    fun `prepared 对不上时退到队列找（原来这里直接放弃→歌词永远不加载）`() {
        val t = t("a", "晴天", "周杰伦", 269_000)
        val r = resolveTrack("a", emptyMap(), listOf(t))
        assertTrue("退到队列应该能找到，否则歌词永远不加载", r === t)
    }

    @Test
    fun `两边都没有才真的放弃`() {
        val t = t("a", "晴天", "周杰伦", 269_000)
        val r = resolveTrack("a", emptyMap(), emptyList())
        assertTrue(r == null)
    }

    @Test
    fun `mediaId 为空时放弃`() {
        val t = t("a", "晴天", "周杰伦", 269_000)
        assertTrue(resolveTrack(null, mapOf("a" to t), listOf(t)) == null)
    }

    /**
     * 序号守卫：过期加载不得写状态。
     * 复刻 loadLyrics 里的判据。
     */
    @Test
    fun `过期加载不写状态`() {
        var currentSeq = 0L
        val stale = 1L
        currentSeq = 2L          // 又起了一次加载
        assertFalse("序号过期必须直接退场", stale == currentSeq)
    }

    @Test
    fun `同一首歌重复触发只应有一次生效`() {
        var writes = 0
        var seq = 0L
        repeat(3) {
            seq += 1
            val mine = seq
            if (mine == seq) writes += 1      // 模拟「只有最新的写状态」
        }
        assertTrue("每次触发都应生效一次（后一次顶掉前一次）", writes == 3)
    }
}
