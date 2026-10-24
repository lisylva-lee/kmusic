package com.kmusic.apkhd.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专辑严格判定。
 *
 * 判错不会崩、不会报错 —— 只会把**别的专辑的歌混进这张专辑**，用户点开就发现串了。
 * 所以宽松的写法（包含匹配、歌手为空就放行）必须被这些用例挡住。
 */
class AlbumMatchTest {

    @Test
    fun exactAlbumAndArtistPasses() {
        assertTrue(AlbumMatch.matches("叶惠美", "周杰伦", "叶惠美", "周杰伦"))
    }

    @Test
    fun ignoresCaseSpacesAndPunctuation() {
        assertTrue(AlbumMatch.matches("叶惠美", "周杰伦", " 叶惠美 ", "周杰伦"))
        assertTrue(AlbumMatch.matches("OK Computer", "Radiohead", "okcomputer", "radiohead"))
        assertTrue(AlbumMatch.matches("We Will Rock You", "Queen", "WeWillRockYou", "Queen"))
    }

    @Test
    fun sameNameDifferentArtistIsRejected() {
        // 同名专辑在酷我上大量存在（翻唱碟 / 精选集 / 合辑）
        assertFalse(AlbumMatch.matches("叶惠美", "某翻唱歌手", "叶惠美", "周杰伦"))
    }

    @Test
    fun similarButNotEqualAlbumIsRejected() {
        // 包含匹配会把这两个也算进来，所以必须是完全相等
        assertFalse(AlbumMatch.matches("叶惠美（纪念版）", "周杰伦", "叶惠美", "周杰伦"))
        assertFalse(AlbumMatch.matches("叶惠美", "周杰伦", "叶惠美（纪念版）", "周杰伦"))
        assertFalse(AlbumMatch.matches("叶惠美 2", "周杰伦", "叶惠美", "周杰伦"))
    }

    @Test
    fun songWithoutAlbumIsRejected() {
        // 搜索结果里 ALBUM 为空是常态 —— 不能当成「匹配」
        assertFalse(AlbumMatch.matches("", "周杰伦", "叶惠美", "周杰伦"))
        assertFalse(AlbumMatch.matches("", "", "叶惠美", ""))
    }

    @Test
    fun emptyTargetAlbumNeverMatches() {
        // 目标专辑名归一化后为空 → 无法判定，宁可不给结果
        assertFalse(AlbumMatch.matches("叶惠美", "周杰伦", "", "周杰伦"))
        assertFalse(AlbumMatch.matches("叶惠美", "周杰伦", "  ", "周杰伦"))
    }

    @Test
    fun blankTargetArtistSkipsArtistCheck() {
        // 专辑结果里没带歌手时，只能按专辑名判定
        assertTrue(AlbumMatch.matches("叶惠美", "任何人", "叶惠美", ""))
    }

    @Test
    fun artistSubstringIsEnough() {
        // 搜索结果常把多歌手写成「周杰伦/方文山」，目标歌手是其中一个
        assertTrue(AlbumMatch.matches("叶惠美", "周杰伦/方文山", "叶惠美", "周杰伦"))
    }
}
