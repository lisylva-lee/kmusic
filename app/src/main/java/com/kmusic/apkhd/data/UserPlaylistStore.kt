package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.model.UserPlaylist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.playlistDataStore by preferencesDataStore(name = "user_playlists")

/**
 * 自建歌单存储（DataStore + JSON）。
 *
 * 单文件 JSON 而不是 Room：歌单总量小（几十个、每个几十到几百首），
 * 全部读进内存的开销可以忽略，换来零迁移成本。
 * 真到几千首再换数据库也不迟 —— 现在上 Room 是给自己找活干。
 */
class UserPlaylistStore(private val context: Context) {

    private val KEY = stringPreferencesKey("user_playlists_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(UserPlaylist.serializer())

    val playlists: Flow<List<UserPlaylist>> = context.playlistDataStore.data.map { p ->
        val raw = p[KEY] ?: return@map emptyList()
        runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    private suspend fun mutate(f: (List<UserPlaylist>) -> List<UserPlaylist>) {
        context.playlistDataStore.edit { p ->
            val cur = p[KEY]?.let {
                runCatching { json.decodeFromString(serializer, it) }.getOrElse { emptyList() }
            } ?: emptyList()
            p[KEY] = json.encodeToString(serializer, f(cur))
        }
    }

    private suspend fun current(): List<UserPlaylist> = playlists.first()

    /** 新建歌单，返回新歌单 id（名字为空则用「新建歌单」兜底） */
    suspend fun create(name: String): String {
        val id = "up:${System.currentTimeMillis()}"
        val n = name.trim().ifEmpty { "新建歌单" }
        mutate { it + UserPlaylist(id = id, name = n, createdAt = System.currentTimeMillis()) }
        return id
    }

    suspend fun rename(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        mutate { list -> list.map { if (it.id == id) it.copy(name = n) else it } }
    }

    suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    /** 加歌（已在里面就置顶，不重复） */
    suspend fun addTrack(id: String, track: Track) {
        mutate { list ->
            list.map { pl ->
                if (pl.id != id) pl
                else pl.copy(tracks = listOf(track) + pl.tracks.filterNot { it.id == track.id })
            }
        }
    }

    suspend fun addTracks(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        mutate { list ->
            list.map { pl ->
                if (pl.id != id) pl
                else {
                    val ids = tracks.map { it.id }.toSet()
                    pl.copy(tracks = tracks + pl.tracks.filterNot { it.id in ids })
                }
            }
        }
    }

    suspend fun removeTrack(id: String, trackId: String) {
        mutate { list ->
            list.map { pl ->
                if (pl.id != id) pl
                else pl.copy(tracks = pl.tracks.filterNot { it.id == trackId })
            }
        }
    }

    suspend fun find(id: String): UserPlaylist? = current().firstOrNull { it.id == id }
}
