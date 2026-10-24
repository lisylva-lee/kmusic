package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmusic.apkhd.model.PlaylistBrief
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.playlistFavDataStore by preferencesDataStore(name = "playlist_favorites")

/**
 * 「我收藏的歌单」——收藏**别的平台的歌单**（酷我 / 网易云）。
 *
 * 和 [UserPlaylistStore] 的区别：那个是自己建的（存完整曲目），这个是收藏别人的
 * （只存 [PlaylistBrief] 快照，曲目点进去实时拉）。
 *
 * ⚠️ **为什么只存 brief、不存曲目**：
 * 收藏的歌单在服务端是会变的（作者加歌、删歌、改封面）。把曲目也缓存下来，
 * 用户看到的就是一份过期快照，而且和我们「进入歌单必实时拉」的现有行为不一致
 * （见 MoyinViewModel.openPlaylist 里关于 trackCount 回写的注释）。
 * 存 brief 的代价是断网时点进去是空的，但那种情况本来也播不了。
 *
 * ⚠️ **跨源 id 会撞**：酷我和网易云的歌单 id 都是纯数字，可能相同。
 * 所以 [PlaylistBrief] 里必须带 source，判重一律用 `source:id` 而不是 id。
 * 光比 id 会把「网易云的 12345」和「酷我的 12345」当成同一个，收藏第二个时把第一个顶掉。
 */
class PlaylistFavStore(private val context: Context) {

    private val KEY = stringPreferencesKey("favorite_playlists_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PlaylistBrief.serializer())

    /** 收藏列表，**最近收藏的在最前**（用户刚收藏完回来看得见） */
    val favorites: Flow<List<PlaylistBrief>> = context.playlistFavDataStore.data.map { p ->
        val raw = p[KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    private suspend fun current(): List<PlaylistBrief> = favorites.first()

    private fun keyOf(p: PlaylistBrief): String = "${p.source}:${p.id}"

    /** 是否已收藏 */
    suspend fun isFavorite(source: String, id: String): Boolean =
        current().any { it.source == source && it.id == id }

    /**
     * 收藏 / 取消收藏。返回操作**之后**是否处于已收藏状态。
     * 已收藏时再点 = 取消（和「我喜欢的音乐」的心形行为一致）。
     */
    suspend fun toggle(brief: PlaylistBrief): Boolean {
        var nowFavorite = false
        context.playlistFavDataStore.edit { p ->
            val cur = p[KEY]?.let {
                runCatching { json.decodeFromString(serializer, it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val k = keyOf(brief)
            val exists = cur.any { keyOf(it) == k }
            val next = if (exists) {
                nowFavorite = false
                cur.filterNot { keyOf(it) == k }
            } else {
                nowFavorite = true
                listOf(brief) + cur
            }
            p[KEY] = json.encodeToString(serializer, next)
        }
        return nowFavorite
    }

    /** 取消收藏（列表页长按 / 滑动删除用，语义比 toggle 明确） */
    suspend fun remove(source: String, id: String) {
        context.playlistFavDataStore.edit { p ->
            val cur = p[KEY]?.let {
                runCatching { json.decodeFromString(serializer, it) }.getOrElse { emptyList() }
            } ?: emptyList()
            p[KEY] = json.encodeToString(serializer, cur.filterNot { it.source == source && it.id == id })
        }
    }

    /**
     * 用实时拉到的真实曲目数刷新 brief 快照。
     *
     * 为什么需要：服务端给的 trackCount 经常是假的（网易云硬截断到 10 首、
     * 酷我 rn 上限 100），收藏列表里会一直显示「367 首」，点进去只有 10 首。
     * 打开歌单时顺手把真实数量写回去，下次看列表就是对的。
     */
    suspend fun updateCount(source: String, id: String, realCount: Int) {
        if (realCount <= 0) return
        context.playlistFavDataStore.edit { p ->
            val cur = p[KEY]?.let {
                runCatching { json.decodeFromString(serializer, it) }.getOrElse { emptyList() }
            } ?: emptyList()
            p[KEY] = json.encodeToString(
                serializer,
                cur.map {
                    if (it.source == source && it.id == id) it.copy(trackCount = realCount) else it
                }
            )
        }
    }
}
