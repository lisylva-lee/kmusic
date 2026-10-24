package com.kmusic.apkhd.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传的判据。
 *
 * 这里错了不会崩、不会报错 —— 只会给用户一个能打开但全是噪音的文件，
 * 而且下次重下还是会错。所以必须钉死。
 */
class DownloadResumeTest {

    @Test
    fun resumesOnlyOnPartialContent() {
        // 有半截 + 服务器回 206 = 真的续传
        assertTrue(DownloadManager.canResume(existingBytes = 1024, httpCode = 206))
    }

    @Test
    fun serverIgnoringRange_mustRestartFromZero() {
        // 有半截但服务器回 200（Range 被忽略，发的是完整文件）
        // → 绝不能追加，必须从头下，否则拼出错位数据
        assertFalse(DownloadManager.canResume(existingBytes = 1024, httpCode = 200))
    }

    @Test
    fun nothingDownloaded_neverResume() {
        assertFalse(DownloadManager.canResume(existingBytes = 0, httpCode = 206))
        assertFalse(DownloadManager.canResume(existingBytes = 0, httpCode = 200))
    }

    @Test
    fun errorCodes_neverResume() {
        listOf(403, 404, 416, 500, 503).forEach { code ->
            assertFalse("HTTP $code 不能当成可续传", DownloadManager.canResume(2048, code))
        }
    }
}
