package com.kmusic.apkhd.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 直链登记表的时效语义。
 *
 * 这套逻辑存在的唯一理由是「player timeline 里的 URL 会过期而没人管」，
 * 所以测试重点全在**边界**：刚好到期、过期后再登记、非法地址不登记。
 */
class PlaybackUrlRegistryTest {

    private var fakeNow = 1_000_000L

    @Before
    fun setUp() {
        PlaybackUrlRegistry.clear()
        PlaybackUrlRegistry.clock = { fakeNow }
    }

    @After
    fun tearDown() {
        PlaybackUrlRegistry.clear()
        PlaybackUrlRegistry.clock = null
    }

    @Test
    fun `未登记的 key 返回 null`() {
        assertNull(PlaybackUrlRegistry.fresh("netease:123:无损"))
        assertEquals(-1L, PlaybackUrlRegistry.remainingMs("netease:123:无损"))
        assertEquals(-1L, PlaybackUrlRegistry.ageMs("netease:123:无损"))
    }

    @Test
    fun `登记后立刻可取`() {
        PlaybackUrlRegistry.remember("netease:123:无损", "https://m801.music.126.net/a.mp3?x=1")
        assertEquals("https://m801.music.126.net/a.mp3?x=1", PlaybackUrlRegistry.fresh("netease:123:无损"))
    }

    @Test
    fun `到期前一秒仍可用`() {
        PlaybackUrlRegistry.remember("k", "https://a/1.mp3")
        fakeNow += PlaybackUrlRegistry.DEFAULT_TTL_MS - 1_000
        assertTrue(PlaybackUrlRegistry.fresh("k") != null)
        assertEquals(1_000L, PlaybackUrlRegistry.remainingMs("k"))
    }

    @Test
    fun `到期后返回 null 但 remainingMs 为负`() {
        PlaybackUrlRegistry.remember("k", "https://a/1.mp3")
        fakeNow += PlaybackUrlRegistry.DEFAULT_TTL_MS + 1
        assertNull(PlaybackUrlRegistry.fresh("k"))
        assertTrue(PlaybackUrlRegistry.remainingMs("k") < 0)
    }

    @Test
    fun `重新登记会覆盖并重置计时`() {
        PlaybackUrlRegistry.remember("k", "https://a/old.mp3")
        fakeNow += PlaybackUrlRegistry.DEFAULT_TTL_MS - 1
        PlaybackUrlRegistry.remember("k", "https://a/new.mp3")
        assertEquals("https://a/new.mp3", PlaybackUrlRegistry.fresh("k"))
        assertEquals(0L, PlaybackUrlRegistry.ageMs("k"))
    }

    @Test
    fun `forget 之后取不到`() {
        PlaybackUrlRegistry.remember("k", "https://a/1.mp3")
        PlaybackUrlRegistry.forget("k")
        assertNull(PlaybackUrlRegistry.fresh("k"))
    }

    @Test
    fun `非 http 地址不登记`() {
        // 本地文件 / content:// 不该进这张表：它们不会过期，也换不掉
        PlaybackUrlRegistry.remember("local", "content://media/external/audio/1")
        PlaybackUrlRegistry.remember("local2", "/sdcard/a.flac")
        assertNull(PlaybackUrlRegistry.fresh("local"))
        assertNull(PlaybackUrlRegistry.fresh("local2"))
    }

    @Test
    fun `空 key 或空 url 不登记`() {
        PlaybackUrlRegistry.remember("", "https://a/1.mp3")
        PlaybackUrlRegistry.remember("k", "")
        assertNull(PlaybackUrlRegistry.fresh(""))
        assertNull(PlaybackUrlRegistry.fresh("k"))
    }

    @Test
    fun `自定义 ttl 生效`() {
        PlaybackUrlRegistry.remember("k", "https://a/1.mp3", ttlMs = 5_000)
        fakeNow += 4_000
        assertTrue(PlaybackUrlRegistry.fresh("k") != null)
        fakeNow += 2_000
        assertNull(PlaybackUrlRegistry.fresh("k"))
    }

    @Test
    fun `pick 有新鲜地址用新的`() {
        PlaybackUrlRegistry.remember("k", "https://a/new.mp3")
        assertEquals("https://a/new.mp3", PlaybackUrlRegistry.pick("k", "https://a/old.mp3"))
    }

    @Test
    fun `pick 没登记或已过期就用调用方给的`() {
        // 本地文件、content:// 这类根本不会登记，必须原样返回
        assertEquals("file:///sdcard/a.flac", PlaybackUrlRegistry.pick("local", "file:///sdcard/a.flac"))
        PlaybackUrlRegistry.remember("k", "https://a/new.mp3")
        fakeNow += PlaybackUrlRegistry.DEFAULT_TTL_MS + 1
        assertEquals("https://a/old.mp3", PlaybackUrlRegistry.pick("k", "https://a/old.mp3"))
    }

    @Test
    fun `不同 key 互不影响`() {
        PlaybackUrlRegistry.remember("netease:1:无损", "https://a/1.mp3")
        PlaybackUrlRegistry.remember("kuwo:2:臻品母带", "https://b/2.flac")
        assertEquals("https://a/1.mp3", PlaybackUrlRegistry.fresh("netease:1:无损"))
        assertEquals("https://b/2.flac", PlaybackUrlRegistry.fresh("kuwo:2:臻品母带"))
        PlaybackUrlRegistry.forget("netease:1:无损")
        assertNull(PlaybackUrlRegistry.fresh("netease:1:无损"))
        assertEquals("https://b/2.flac", PlaybackUrlRegistry.fresh("kuwo:2:臻品母带"))
    }
}
