package com.kmusic.apkhd.model

import kotlinx.serialization.Serializable

/**
 * 上次播放的队列快照。
 *
 * 只存**逻辑队列**（用户看到的那个列表），不存播放器的 timeline ——
 * 播放器里永远只有窗口内的几首（见 `buildQueueAround`），拿它恢复会丢歌。
 *
 * ⚠️ 曲目的 `playUrl` 是**过期直链**，冷启动后绝不能直接拿来播：
 * 在线源的签名地址只活几分钟。恢复时必须重新取链，见 `MoyinViewModel.restoreLastSession`。
 * 存 `playUrl` 只是为了「同一次会话内重进 App」时少一次网络请求。
 */
@Serializable
data class PlaybackSnapshot(
    val tracks: List<Track> = emptyList(),
    /** 当前曲目 id（比下标可靠：队列被增删过时下标会漂） */
    val currentId: String = "",
    /** 保存时的下标，仅作为 id 对不上时的兜底 */
    val index: Int = 0,
    /** 保存时刻（wall clock），用于「太久没听就别自动接着播」的判定 */
    val savedAt: Long = 0L
)
