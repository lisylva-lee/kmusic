package com.kmusic.apkhd.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角 / 间距 token（B4）。
 *
 * **只给新代码 + 播放页 + 音效面板用**，不做全仓替换。
 * 理由：这个 App 已经有两套视觉语言（A 圆润 / B 纸感），散落在各页的
 * `RoundedCornerShape(16.dp)` 之类是那两套的一部分；一次性扫平必然改到
 * 老页面的观感，回归成本远大于收益。
 *
 * 这里收的是「以后新写的界面该用哪一档」，让新代码先长齐，
 * 老页面等自然重写时再换。
 */
object MoyinShape {
    /** 卡片外框（歌单卡、专辑卡） */
    val card = RoundedCornerShape(14.dp)

    /** 分组容器（设置页的一组、面板里的一块） */
    val group = RoundedCornerShape(12.dp)

    /** 可点按控件（按钮、列表项内的小块） */
    val control = RoundedCornerShape(10.dp)

    /** 胶囊（chip、档位选择） */
    val chip = RoundedCornerShape(99.dp)

    /** 封面（跟随卡片，但不做 99dp 那种球） */
    val cover = RoundedCornerShape(10.dp)

    /** 对话框 */
    val dialog = RoundedCornerShape(16.dp)

    /** 底部面板（只有上圆角） */
    val sheetTop = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    /** 右侧抽屉（只有左圆角，横屏用） */
    val drawer = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
}

/**
 * 间距 token。
 *
 * 名字按用途取，不按数值取 —— `gapRow` 改天从 8 变 10 时，
 * 叫 `s8` 的那种命名会让所有调用点都变成谎言。
 */
object MoyinSpace {
    /** 行内元素之间（图标与文字、chip 与 chip） */
    val row = 8.dp

    /** 分块之间（一行与下一行、一块与下一块） */
    val block = 14.dp

    /** 分组之间（一组设置与下一组） */
    val section = 22.dp

    /** 页面左右内边距 */
    val pageH = 18.dp

    /** 面板内上下内边距 */
    val sheetV = 16.dp
}
