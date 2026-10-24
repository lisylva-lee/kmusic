package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.localScanDataStore by preferencesDataStore(name = "local_scan")

/**
 * 本地音乐的扫描范围设置。
 *
 * 语义（别搞反）：
 * - [enabledFolders] **为空 = 不限制**（MediaStore 扫到什么都算）。
 *   空集合表示「用户没做选择」，不是「什么都不扫」—— 后者会让升级上来的老用户
 *   一打开就发现本地库空了。
 * - [safTrees] 是用户手动添加的目录，**总是**参与扫描（它们是 MediaStore 之外的增量）。
 */
class LocalScanStore(private val context: Context) {

    private val enabledKey = stringSetPreferencesKey("local_enabled_folders")
    private val safKey = stringSetPreferencesKey("local_saf_trees")

    val enabledFolders: Flow<Set<String>> =
        context.localScanDataStore.data.map { it[enabledKey] ?: emptySet() }

    val safTrees: Flow<Set<String>> =
        context.localScanDataStore.data.map { it[safKey] ?: emptySet() }

    suspend fun setEnabled(paths: Set<String>) {
        context.localScanDataStore.edit { it[enabledKey] = paths }
    }

    suspend fun addTree(uri: String) {
        context.localScanDataStore.edit { it[safKey] = (it[safKey] ?: emptySet()) + uri }
    }

    suspend fun removeTree(uri: String) {
        context.localScanDataStore.edit { it[safKey] = (it[safKey] ?: emptySet()) - uri }
    }
}
