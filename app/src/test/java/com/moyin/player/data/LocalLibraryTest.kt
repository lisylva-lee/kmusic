package com.kmusic.apkhd.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.model.Track
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地扫描测试 —— 重点验证「无权限时不崩、返回空列表」，
 * 这是 0.3.0 真机闪退的根因（query() 抛 SecurityException 未被捕获）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLibraryTest {

    @Test
    fun scan_withoutPermission_returnsEmpty_notCrash() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // 默认未授权：必须返回空列表而不是抛异常
        val result = LocalLibrary(ctx).scan()
        assertTrue("无权限应返回空列表而不是抛异常", result.isEmpty())
    }

    @Test
    fun hasAudioPermission_isFalseByDefault() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(!LocalLibrary(ctx).hasAudioPermission())
    }

    @Test
    fun trackUri_buildsValidMediaStoreUri() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val t = Track(
            id = "local:42", name = "x", artist = "", album = "",
            source = "local", sourceId = "42"
        )
        val uri = LocalLibrary(ctx).trackUri(t)
        assertNotNull(uri)
        assertTrue(uri.toString().contains("42"))
    }
}
