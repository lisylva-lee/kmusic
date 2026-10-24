package com.kmusic.apkhd.model

import com.kmusic.apkhd.net.KuwoClient
import com.kmusic.apkhd.net.QualityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放模式解析 + 音质档位换算（「无损降级」的真实语义） */
class PlayModeQualityTest {

    // ---- 播放模式 ----

    @Test
    fun playMode_ofParsesStoredValue() {
        assertEquals(PlayMode.LOOP, PlayMode.of("LOOP"))
        assertEquals(PlayMode.SINGLE, PlayMode.of("single"))
        assertEquals(PlayMode.SHUFFLE, PlayMode.of("Shuffle"))
    }

    @Test
    fun playMode_unknownFallsBackToOrder() {
        // 存量数据里没有、或者被改坏时，不能崩，也不能随机跳到别的模式
        assertEquals(PlayMode.ORDER, PlayMode.of(null))
        assertEquals(PlayMode.ORDER, PlayMode.of(""))
        assertEquals(PlayMode.ORDER, PlayMode.of("乱码"))
    }

    @Test
    fun playMode_allFourExist() {
        assertEquals(4, PlayMode.entries.size)
        assertEquals(
            listOf(PlayMode.ORDER, PlayMode.LOOP, PlayMode.SINGLE, PlayMode.SHUFFLE),
            PlayMode.entries.toList()
        )
    }

    // ---- 音质档位 ----

    @Test
    fun tiers_masterWithFallback_triesThreeInOrder() {
        assertEquals(
            listOf(KuwoClient.BR_MASTER, KuwoClient.BR_HIRES, KuwoClient.BR_HIGH),
            QualityPrefs.tiers("master", true)
        )
    }

    @Test
    fun tiers_noFallback_takesPreferredOnly() {
        // 「无损降级」关掉 = 只要这一档，拿不到就报错
        assertEquals(listOf(KuwoClient.BR_MASTER), QualityPrefs.tiers("master", false))
        assertEquals(listOf(KuwoClient.BR_HIRES), QualityPrefs.tiers("hires", false))
        assertEquals(listOf(KuwoClient.BR_STANDARD), QualityPrefs.tiers("standard", false))
    }

    @Test
    fun tiers_neverUpgrades() {
        // 选「无损」不能因为降级链就拿到母带 —— 那是升档，与用户选择无关
        val hires = QualityPrefs.tiers("hires", true)
        assertFalse("无损档位链里不能出现母带", hires.contains(KuwoClient.BR_MASTER))
        val high = QualityPrefs.tiers("high", true)
        assertFalse(high.contains(KuwoClient.BR_MASTER))
        assertFalse(high.contains(KuwoClient.BR_HIRES))
    }

    @Test
    fun tiers_unknownQualityBehavesLikeMaster() {
        // 存量/损坏的设置值不能让播放直接失败
        assertEquals(QualityPrefs.tiers("master", true), QualityPrefs.tiers("xyz", true))
        assertTrue(QualityPrefs.tiers("xyz", true).isNotEmpty())
    }

    @Test
    fun qualityNames_coverEveryTier() {
        QualityPrefs.ALL.forEach { q ->
            assertTrue("每个档位都要有中文名: $q", QualityPrefs.name(q).isNotBlank())
        }
    }
}
