package com.kmusic.apkhd.playback

import com.kmusic.apkhd.model.Track

/**
 * 恢复上次播放时的两个纯计算：**恢复到第几首**、**从第几毫秒开始**。
 *
 * 抽出来是因为这两件事全是边界条件（下标越界、id 找不到、文件被删、快照比队列旧、
 * 上次正好听完），写在 ViewModel 里只能靠真机试，抽成纯函数就能用单测钉住。
 */
object ResumePlan {

    /** 剩这么点就当作「已听完」，从 0 开始（否则一播就结束，看起来像没恢复） */
    const val NEAR_END_MS: Long = 3_000L

    /**
     * 恢复哪一首。
     *
     * 优先按 `currentId` 反查 —— 下标会漂（队列被增删/排序过），id 不会。
     * id 找不到（那首歌被删了）才退回保存时的下标，并夹进合法区间。
     *
     * @return 合法下标；[tracks] 为空时 -1
     */
    fun index(tracks: List<Track>, currentId: String, savedIndex: Int): Int {
        if (tracks.isEmpty()) return -1
        val byId = tracks.indexOfFirst { it.id == currentId }
        if (byId >= 0) return byId
        return savedIndex.coerceIn(0, tracks.lastIndex)
    }

    /**
     * 从哪开始播。
     *
     * @param savedMs   上次存下的位置
     * @param durationMs 这首的时长（0 = 不知道，此时原样返回）
     * @return 0 或 [savedMs]；负数一律当 0
     */
    fun positionMs(savedMs: Long, durationMs: Long): Long {
        val p = savedMs.coerceAtLeast(0L)
        if (durationMs <= 0L) return p
        // 已知时长才判「快到结尾」；比时长还长的脏数据也归零
        return if (p >= durationMs - NEAR_END_MS) 0L else p
    }
}
