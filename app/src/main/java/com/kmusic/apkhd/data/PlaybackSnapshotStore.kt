package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmusic.apkhd.model.PlaybackSnapshot
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.queueDataStore by preferencesDataStore(name = "playback_queue")
private val Context.positionDataStore by preferencesDataStore(name = "playback_position")

/**
 * 播放会话落盘：队列快照 + 播放位置。
 *
 * ## 为什么拆成两个 DataStore
 *
 * DataStore 每次 `edit` 会**重写整个文件**。队列快照（几百首 JSON）可能有几百 KB，
 * 而位置每 5 秒就要写一次 —— 放同一个文件等于每 5 秒重写几百 KB，白烧闪存。
 * 拆开后位置写在只有几个字节的文件里，代价可以忽略。
 *
 * ## 为什么不存播放器的 timeline
 *
 * 播放器里永远只有窗口内的几首（顺序模式只铺后 5 前 3，见 `buildQueueAround`），
 * 拿它当快照恢复，用户会发现「我的歌单只剩 8 首」。所以存的是**逻辑队列**。
 */
class PlaybackSnapshotStore(private val context: Context) {

    private val queueKey = stringPreferencesKey("snapshot_json")
    private val posKey = longPreferencesKey("position_ms")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 队列最多存多少首。
     *
     * 单条 Preferences 值太大时读写都会变慢，2000 首（≈500 KB）是能接受的上限；
     * 再长的队列截断尾部 —— 用户重开后至少能接着播。
     */
    private val maxTracks = 2000

    suspend fun saveQueue(snapshot: PlaybackSnapshot) {
        val tracks = snapshot.tracks.take(maxTracks).map(::slim)
        if (tracks.isEmpty()) {
            clearQueue()
            return
        }
        // index 跟着瘦身/截断后的列表重算：id 对不上就退回原下标（并夹到合法区间）
        val idx = tracks.indexOfFirst { it.id == snapshot.currentId }.takeIf { it >= 0 }
            ?: snapshot.index.coerceIn(0, tracks.lastIndex)
        val raw = json.encodeToString(
            PlaybackSnapshot.serializer(),
            snapshot.copy(tracks = tracks, index = idx)
        )
        context.queueDataStore.edit { p -> p[queueKey] = raw }
    }

    suspend fun loadQueue(): PlaybackSnapshot? {
        val raw = context.queueDataStore.data.first()[queueKey] ?: return null
        return runCatching { json.decodeFromString(PlaybackSnapshot.serializer(), raw) }.getOrNull()
            ?.takeIf { it.tracks.isNotEmpty() }
    }

    suspend fun savePosition(ms: Long) {
        if (ms < 0) return
        context.positionDataStore.edit { it[posKey] = ms }
    }

    suspend fun loadPosition(): Long =
        context.positionDataStore.data.first()[posKey] ?: 0L

    suspend fun clearQueue() {
        context.queueDataStore.edit { it.remove(queueKey) }
    }

    suspend fun clearAll() {
        clearQueue()
        context.positionDataStore.edit { it.remove(posKey) }
    }

    /**
     * 落盘前瘦身：在线源的 `playUrl` / `ekey` 是**几分钟就过期的签名地址**，
     * 存下来只占地方（冷启动后必然要重取链）。本地 / WebDAV / Navidrome 的地址
     * 由 sourceId 推导或本身就是稳定地址，留着能在同一次会话内省一次解析。
     */
    private fun slim(t: Track): Track =
        if (t.source in setOf("kuwo", "netease", "migu")) t.copy(playUrl = null, ekey = null) else t
}
