package com.kmusic.apkhd.net

/**
 * 专辑 → 歌曲的严格判定。
 *
 * 酷我**没有**「按专辑 id 取歌曲列表」的可用端点（实测 `pl.svc` 传专辑 id 返回空
 * musiclist，web 的 `albumInfo` 没带 kw_token 直接被拒），所以只能
 * 「按专辑名搜歌」再逐首比对。
 *
 * 比对必须严：同名专辑在酷我上大量存在（翻唱碟、精选集、合辑），
 * 松一点就会把别的歌塞进这张专辑里。**宁可空着，也不串歌**。
 */
object AlbumMatch {

    /** 归一化：只留字母/数字/汉字，忽略大小写、空格与标点 */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * @param candidateAlbum  搜索结果里这首歌的专辑名
     * @param candidateArtist 搜索结果里这首歌的歌手
     * @param wantAlbum       目标专辑名
     * @param wantArtist      目标专辑的歌手（空则不校验歌手）
     */
    fun matches(
        candidateAlbum: String,
        candidateArtist: String,
        wantAlbum: String,
        wantArtist: String
    ): Boolean {
        val want = norm(wantAlbum)
        // 目标专辑名归一化后为空 → 无法判定，一律不通过
        if (want.isEmpty()) return false
        // 专辑名必须**完全相等**（不是包含）—— 包含会把「叶惠美（纪念版）」也算进来
        if (norm(candidateAlbum) != want) return false
        val artist = norm(wantArtist)
        if (artist.isEmpty()) return true
        return norm(candidateArtist).contains(artist)
    }
}
