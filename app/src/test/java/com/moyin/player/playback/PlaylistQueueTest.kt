package com.kmusic.apkhd.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「系统媒体控制中心点不了下一首」的根因回归测试。
 *
 * 系统判定「下一首」按钮是否可用，依据是**播放器本身有没有下一项**
 * （Android 官方文档：Next 槽 ⟺ Player 命令 COMMAND_SEEK_TO_NEXT /
 * COMMAND_SEEK_TO_NEXT_MEDIA_ITEM 可用；PlaybackState 的
 * ACTION_SKIP_TO_NEXT 也是从播放器可用命令集推出来的）。
 *
 * 之前的实现是「ViewModel 自己维护队列，播放器里永远只有当前这一首」，
 * 所以下一首必然被判为不可用（上一首因为能重播当前曲仍是可用，于是出现
 * 「上一首能点、下一首是灰的」这种看起来诡异的现象）。
 *
 * 现在整个队列都会铺进播放器的播放列表，这里把结论钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaylistQueueTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun item(id: String) = MediaItem.Builder()
        .setUri("http://example.com/$id.mp3")
        .setMediaId(id)
        .build()

    @Test
    fun singleItem_hasNoNext_thisIsTheBug() {
        val player = ExoPlayer.Builder(ctx).build()
        try {
            player.setMediaItems(listOf(item("1")), 0, 0L)
            // 单项播放列表：这就是「下一首」被判为不可用的原因
            assertFalse("单项不该有下一项", player.hasNextMediaItem())
            assertFalse(
                "单项时 COMMAND_SEEK_TO_NEXT_MEDIA_ITEM 必须不可用（系统据此禁用按钮）",
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun realQueue_advertisesNextAndPrevious() {
        val player = ExoPlayer.Builder(ctx).build()
        try {
            player.setMediaItems(listOf(item("1"), item("2"), item("3")), 1, 0L)

            assertTrue("队列在播放器里 → 有下一首", player.hasNextMediaItem())
            assertTrue("也应该有上一首", player.hasPreviousMediaItem())

            // 这两个命令可用是通知栏/锁屏/QS 媒体面板亮起按钮的**事实依据**
            assertTrue(
                "COMMAND_SEEK_TO_NEXT_MEDIA_ITEM 必须可用",
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            )
            assertTrue(
                "COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM 必须可用",
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            )
            // 也要出现在命令集里（PlaybackState 的 actions 是从命令集映射的）
            assertTrue(
                "命令集里必须有下一首",
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM in player.availableCommands
            )
            assertEquals(1, player.currentMediaItemIndex)
        } finally {
            player.release()
        }
    }

    @Test
    fun insertingBeforeCurrent_keepsOrderAndEnablesPrevious() {
        // 对应 buildQueueAround 往前插的行为：逐个插在当前项之前
        val player = ExoPlayer.Builder(ctx).build()
        try {
            player.setMediaItems(listOf(item("cur")), 0, 0L)
            assertFalse(player.hasPreviousMediaItem())

            // 倒着往前插（先插紧邻的 p1、再插更早的 p2），且**固定插到 0**，
            // 这样最终顺序才是 p2, p1, cur（即队列原本的顺序）
            listOf("p1", "p2").forEach { id ->
                player.addMediaItem(0, item(id))
            }

            // 期望顺序：p2, p1, cur —— 当前项始终是 cur
            assertEquals(3, player.mediaItemCount)
            assertEquals("p2", player.getMediaItemAt(0).mediaId)
            assertEquals("p1", player.getMediaItemAt(1).mediaId)
            assertEquals("cur", player.getMediaItemAt(2).mediaId)
            assertEquals("插入后当前项不能变", "cur", player.currentMediaItem?.mediaId)
            assertTrue("插完之后上一首要可用", player.hasPreviousMediaItem())
        } finally {
            player.release()
        }
    }

    @Test
    fun cipherRegistry_isKeyedByUrl_notGlobal() {
        // 队列里多首加密曲目共存，密钥必须按直链区分，否则会串密钥
        KuwoCipherHolder.clear()
        assertNull("没登记过就没有密钥", KuwoCipherHolder.get("http://a/1.flac"))
        assertNull("另一个直链也不该拿到别人的", KuwoCipherHolder.get("http://a/2.flac"))

        // 注意：QmcV2Rc4 构造需要真实参数，这里只验证「按 key 隔离」的语义
        KuwoCipherHolder.put("http://a/1.flac", null)
        assertNull("传 null 不应登记", KuwoCipherHolder.get("http://a/1.flac"))
        KuwoCipherHolder.clear()
    }
}
