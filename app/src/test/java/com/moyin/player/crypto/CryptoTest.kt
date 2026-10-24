package com.kmusic.apkhd.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实酷我母带数据验证 Kotlin 移植与 Python 参考实现的一致性。
 * 参考数据来自配套的 Python 全链路验证脚本。
 */
class CryptoTest {

    private val ekey = "Njw+DTAxpGxFMh+QP5rdPtMdcHrgyPk2TmWiiEx7qtwl+QHxaM+JhwsTeQ2mXss6tQ7hP61d1C+XXHkj0VORD7MF8L0n23uMc5xLU4oy4w9lIkTKHV5z4YQZ5fmo2+0+eaKffvtkJW/q2Dr4QE4UufoQQyu4fm7DHj7mqp1iDnYF6AWj1qdG+R0KNvy3uAzlAQ7xn0PBNZBf2NLEuXebF//4tQuzdBK0VeTAJBIhDa9UD4VhdYtmNs1FtYhxEq4Xd9tyA1nyqP76PInmu86OggY1slGYP2q8S4QXaLNh5WiRyFvJYFKoV2uiFSSMr1jlvzeoav6Uu8CzF3H0qpEKP1RExl3kXNvF5oFBjh9zOP1dsORuHGioyAu3TtmfyOgxgD8/IMvfA5kAYXcnIIx9SqswsjJQt4ktAV6mJGIWrSyrJEjtKAbq6pUxnxGCMJurt5crmphbIWvtUjWnQVZedFnWpB5191vyqpcFgPnba+IrH6iNTRjE70ee18OodSiivA40Yoyre7dPPJkQfa/k+L48kt0za1/h6/GOsmUbGQmoQo1efO5Mg3faQOEtwYucUIkmkQWn88LK+XsKvr718LTHINRXpT+XAGdCgIV3SBh6cUsRd4T6j1s6pMM74BaYQ/n+1tdhG3X79niTAJhE5UH0gsvSWWF+8m6iUlHMw+Mn6bB9oJCBJfdS1uyUD4yrQtBoURE8O7IriVeQ3GjWEy+sfewXXXHWipTFFcR9H353sEmEt5Kcd3AWC13JNIC6ZGAuftFWp7ZnNZhHAtBIp2wIb/5HuHXSG9/v7NbI6IGSMVkeUOCGYz3jFqHj+R/inMYuYwyeZTlFeXeBXWvaFXrJH653wvCfcYVtNpvohgXgw+y4YcqYzFgz/87e97tOFQrZM+r3Vkn72Mqtrwax/CjU//cdWKDToUx8sDOO38Xo+qqUeDE1WWIoqP4tAfmw"
    private val user = "1234567890"

    @Test
    fun kwDes_matchesPython() {
        // um_crypto 官方测试向量: decrypt_ksing("tx5ct5ilzeLs7pN1C4RI6w==", "12345678") == "hello world"
        val result = KwDes.decryptBase64("tx5ct5ilzeLs7pN1C4RI6w==", "12345678".toByteArray())
        assertArrayEquals("hello world".toByteArray(), result)
    }

    @Test
    fun tcTea_cbc_matchesCrateVector() {
        // tc_tea-0.2.1 crate 官方测试向量
        val good = byteArrayOf(
            0x91.toByte(), 0x09, 0x51, 0x62, 0xe3.toByte(), 0xf5.toByte(),
            0xb6.toByte(), 0xdc.toByte(), 0x6b, 0x41, 0x4b, 0x50,
            0xd1.toByte(), 0xa5.toByte(), 0xb8.toByte(), 0x4e, 0xc5.toByte(),
            0x0d, 0x0c, 0x1b, 0x11, 0x96.toByte(), 0xfd.toByte(), 0x3c
        )
        val key = "12345678ABCDEFGH".toByteArray(Charsets.US_ASCII)
        val r = TcTea.decrypt(good, key)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), r)
    }

    @Test
    fun qmcEkey_decrypts() {
        val qmcEkey = QmcEkeyTestHelper.qmcEkey
        val key = QmcEkey.decrypt(qmcEkey)
        assertEquals("QMC key 应为 512 字节（RC4）", 512, key.size)
        val head = String(key, 0, 16, Charsets.UTF_8)
        assertTrue("QMC key 前缀不匹配，实际: $head", "0rW98I5d0fWCXbbb" == head)
    }

    @Test
    fun fullChain_realKuwoMaster() {
        val cipher = KuwoCipher.createFromEkey(ekey, user)
        assertNotNull("QMC 解密器创建失败", cipher)
        // 用真实 mflac 头部验证
        val head = KuwoCipherTestData.ENCRYPTED_HEAD
        cipher!!.decrypt(head, 0)
        assertArrayEquals("fLaC".toByteArray(), head.copyOfRange(0, 4))
    }

    /**
     * seek 正确性：从非零偏移解密必须与全量解密的对应片段逐字节相等。
     *
     * 这是在"拖动进度条"场景下的关键性质 —— RC4 密钥流必须按**文件绝对偏移**
     * 推导。如果实现里忽略了 absoluteOffset（从头重新生成密钥流），
     * 表现就是拖动后解码出噪音，但全量播放正常。这个 bug 不会被其它测试抓到。
     */
    @Test
    fun decryptRange_nonZeroOffset_matchesFullDecrypt() {
        val n = 4096
        // 确定性内容（不用随机，失败时可复现）
        val ct = ByteArray(n) { i -> ((i * 31 + 7) % 256).toByte() }

        // 全量解密
        val full = ct.copyOf()
        KuwoCipher.createFromEkey(ekey, user)!!.decryptRange(full, 0, n, 0)

        // 换一个全新的 cipher，从偏移 1500 开始解密 1000 字节
        val back = 1500
        val len = 1000
        val slice = ct.copyOfRange(back, back + len)
        KuwoCipher.createFromEkey(ekey, user)!!.decryptRange(slice, 0, len, back.toLong())

        assertArrayEquals(
            "从偏移解密的结果与全量解密不一致（RC4 密钥流没有按绝对偏移对齐，拖动进度条会出噪音）",
            full.copyOfRange(back, back + len),
            slice
        )
    }

    /** 二次 seek 到更后面的位置同样要对齐（模拟连续拖动） */
    @Test
    fun decryptRange_multipleSeeks_consistent() {
        val n = 8192
        val ct = ByteArray(n) { i -> ((i * 17 + 3) % 256).toByte() }
        val full = ct.copyOf()
        KuwoCipher.createFromEkey(ekey, user)!!.decryptRange(full, 0, n, 0)

        for (start in listOf(0L, 512L, 4096L, 7000L)) {
            val len = 256
            val slice = ct.copyOfRange(start.toInt(), start.toInt() + len)
            KuwoCipher.createFromEkey(ekey, user)!!.decryptRange(slice, 0, len, start)
            assertArrayEquals(
                "偏移 $start 处解密不一致",
                full.copyOfRange(start.toInt(), start.toInt() + len),
                slice
            )
        }
    }
}

object KuwoCipherTestData {
    /** /tmp/mflac_sample.bin 的前 16 字节（密文头） */
    val ENCRYPTED_HEAD: ByteArray = byteArrayOf(
        0x54, 0x2b, 0x37, 0x24, 0x75, 0x4c, 0x74, 0x66,
        0x59, 0x4f, 0x26, 0x4f, 0x38, 0x38, 0x9e.toByte(), 0x50
    )
}
