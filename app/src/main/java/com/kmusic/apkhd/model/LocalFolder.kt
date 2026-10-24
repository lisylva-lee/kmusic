package com.kmusic.apkhd.model

/**
 * 本地音乐的一个可扫描目录。
 *
 * 两个来源合在一张表里：
 * - MediaStore 扫出来的目录（快、有完整标签和封面）
 * - 用户手动用系统文件选择器指定的目录（能扫到 MediaStore 看不见的地方 ——
 *   安卓 11+ 起别的 App 的私有目录不会进 MediaStore，用户「明明手机里有歌却扫不到」
 *   基本就是这个原因）
 */
data class LocalFolder(
    /** MediaStore 侧是 RELATIVE_PATH（如 "Music"）；SAF 侧是 tree uri 字符串 */
    val path: String,
    /** 展示名（目录最后一段） */
    val name: String,
    val trackCount: Int,
    /** true = 用户手动添加的目录 */
    val fromSaf: Boolean = false
)
