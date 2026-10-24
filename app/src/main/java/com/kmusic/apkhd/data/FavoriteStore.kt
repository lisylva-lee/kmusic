package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.favoriteDataStore by preferencesDataStore(name = "favorites")
private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 我喜欢的音乐。
 *
 * 和 RecentStore 一样存**完整曲目 JSON** 而不是只存 id ——
 * 只存 id 的话回查拿不到歌名/歌手（酷我按 rid 查不到元数据），
 * 列表会退化成「曲目 440613」这种占位。
 */
class FavoriteStore(private val context: Context) {

    private val KEY = stringPreferencesKey("favorite_tracks_json")

    val favorites: Flow<List<Track>> = context.favoriteDataStore.data.map { p ->
        val raw = p[KEY] ?: return@map emptyList()
        runCatching { JSON.decodeFromString<List<Track>>(raw) }.getOrElse { emptyList() }
    }

    /** 收藏 / 取消收藏。返回操作**之后**是否处于已收藏状态。 */
    suspend fun toggle(track: Track): Boolean {
        var nowFavorite = false
        context.favoriteDataStore.edit { p ->
            val cur = p[KEY]?.let {
                runCatching { JSON.decodeFromString<List<Track>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val next = if (cur.any { it.id == track.id }) {
                cur.filterNot { it.id == track.id }
            } else {
                nowFavorite = true
                listOf(track) + cur
            }
            p[KEY] = JSON.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Track.serializer()), next
            )
        }
        return nowFavorite
    }
}
