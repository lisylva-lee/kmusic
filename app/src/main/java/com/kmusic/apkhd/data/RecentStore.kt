package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.recentDataStore by preferencesDataStore(name = "recent")
private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 最近播放 —— 存**完整曲目信息**（JSON），不是只存 id。
 *
 * 只存 id 的话回查拿不到歌名/歌手（酷我按 rid 查不到元数据），
 * 列表里就只能显示「曲目 440613」这种占位。存全量没这个问题。
 */
class RecentStore(private val context: Context) {

    private val KEY = stringPreferencesKey("recent_tracks_json")

    val recentTracks: Flow<List<Track>> = context.recentDataStore.data.map { p ->
        val raw = p[KEY] ?: return@map emptyList()
        runCatching { JSON.decodeFromString<List<Track>>(raw) }.getOrElse { emptyList() }
    }

    /** 记录一次播放（去重后置顶，最多 50 条） */
    suspend fun add(track: Track) {
        context.recentDataStore.edit { p ->
            val cur = p[KEY]?.let {
                runCatching { JSON.decodeFromString<List<Track>>(it) }.getOrElse { emptyList() }
            } ?: emptyList()
            val next = (listOf(track) + cur.filter { it.id != track.id }).take(50)
            p[KEY] = JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), next)
        }
    }

    suspend fun clear() {
        context.recentDataStore.edit { it.remove(KEY) }
    }
}
