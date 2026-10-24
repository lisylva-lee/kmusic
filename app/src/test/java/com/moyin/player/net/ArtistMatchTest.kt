package com.kmusic.apkhd.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「这个词是不是歌手」的判定。
 *
 * 认错的后果很实在：把别人的专辑当成周杰伦的，或者反过来 —— 明明有 39 张真专辑
 * 却退化成按专辑名搜，搜出一堆同名杂碟。
 */
class ArtistMatchTest {

    @Test
    fun exactNameMatches() {
        assertEquals(0, ArtistMatch.matchIndex("周杰伦", listOf("周杰伦", "贺瑄")))
    }

    @Test
    fun ignoresCaseSpacesAndPunctuation() {
        assertEquals(0, ArtistMatch.matchIndex("jay chou", listOf("Jay Chou")))
        assertEquals(0, ArtistMatch.matchIndex(" 周杰伦 ", listOf("周杰伦")))
    }

    @Test
    fun partialNameDoesNotMatchLongerName() {
        // 「周杰」不是周杰伦 —— 差一个字也可能是别人
        assertNull(ArtistMatch.matchIndex("周杰", listOf("周杰伦")))
    }

    @Test
    fun exactShortNameMatchesItsOwnIndex() {
        // 列表里真有叫「周杰」的歌手时，命中的是他（下标 1），
        // 不能被排在前面的「周杰伦」抢走
        assertEquals(1, ArtistMatch.matchIndex("周杰", listOf("周杰伦", "周杰")))
    }

    @Test
    fun artistNameContainingKeywordIsNotAMatch() {
        // 搜「周杰伦」时返回的「周杰伦歌迷」「翻唱周杰伦」都不算
        assertNull(ArtistMatch.matchIndex("周杰伦", listOf("周杰伦歌迷", "翻唱周杰伦")))
    }

    @Test
    fun picksMatchingIndexNotFirst() {
        // 相关度最高的不一定排第一 —— 必须按名字找，不能闭眼取 [0]
        assertEquals(2, ArtistMatch.matchIndex("叶惠美", listOf("周杰伦", "贺瑄", "叶惠美")))
    }

    @Test
    fun emptyKeywordNeverMatches() {
        assertNull(ArtistMatch.matchIndex("", listOf("周杰伦")))
        assertNull(ArtistMatch.matchIndex("   ", listOf("周杰伦")))
    }

    @Test
    fun pickArtistIdKeepsListsAligned() {
        val names = listOf("周杰伦歌迷", "周杰伦")
        val ids = listOf("999", "336")
        assertEquals("336", ArtistMatch.pickArtistId("周杰伦", names, ids))
    }

    @Test
    fun pickArtistIdReturnsNullWhenNoMatch() {
        assertNull(ArtistMatch.pickArtistId("林俊杰", listOf("周杰伦"), listOf("336")))
    }

    @Test
    fun pickArtistIdToleratesBlankId() {
        assertNull(ArtistMatch.pickArtistId("周杰伦", listOf("周杰伦"), listOf("")))
        assertNull(ArtistMatch.pickArtistId("周杰伦", listOf("周杰伦"), emptyList()))
    }
}
