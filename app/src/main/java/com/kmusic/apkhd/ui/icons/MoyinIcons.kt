package com.kmusic.apkhd.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * KMusic 线性图标库。
 *
 * 关键：stroke/fill 的颜色写**不透明的白**，由 MoyinIcon 渲染时通过 tint 着色。
 * 写 Color.Unspecified（全透明）会让图标整体消失（tint 的 SrcIn 混合无从附着）。
 * 这样同一套图标在深色/浅色主题下都能正确着色。
 *
 * 规范：24x24 视口、1.7px 描边、圆头圆角、光学居中（内容区 3~21）。
 */

/** 线性图标构造器 —— 颜色留空，由 tint 着色 */
private fun lineIcon(
    block: PathBuilder.() -> Unit
): ImageVector {
    val pb = PathBuilder()
    pb.block()
    return ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).addPath(
        pathData = pb.nodes,
        // ⚠️ 路径颜色必须**不透明**（White），不能写 Color.Unspecified（全透明）。
        // ColorFilter.tint 是 SrcIn 混合：保留源 alpha、替换颜色。
        // 源透明 → alpha=0 → tint 无从附着 → 图标整体不可见。
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ).build()
}

/** 实心图标构造器（播放/暂停等） */
private fun solidIcon(
    block: PathBuilder.() -> Unit
): ImageVector {
    val pb = PathBuilder()
    pb.block()
    return ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).addPath(
        pathData = pb.nodes,
        fill = SolidColor(Color.White),
        stroke = null
    ).build()
}

// ============================================================
// 导航
// ============================================================

val IconHome: ImageVector by lazy {
    lineIcon {
        moveTo(3.5f, 10.5f)
        lineTo(12f, 3.8f)
        lineTo(20.5f, 10.5f)
        lineTo(20.5f, 19.2f)
        quadTo(20.5f, 20.3f, 19.4f, 20.3f)
        lineTo(15.4f, 20.3f)
        lineTo(15.4f, 14.3f)
        lineTo(8.6f, 14.3f)
        lineTo(8.6f, 20.3f)
        lineTo(4.6f, 20.3f)
        quadTo(3.5f, 20.3f, 3.5f, 19.2f)
        close()
    }
}

val IconSearch: ImageVector by lazy {
    lineIcon {
        // 严格对径半圆：弦长必须 = 2r = 13.2，否则弧段会鼓成透镜形（出现双圈）
        moveTo(4.2f, 10.8f)
        arcTo(6.6f, 6.6f, 0f, false, true, 17.4f, 10.8f)
        arcTo(6.6f, 6.6f, 0f, false, true, 4.2f, 10.8f)
        close()
        moveTo(15.9f, 15.9f)
        lineTo(20.3f, 20.3f)
    }
}

val IconLibrary: ImageVector by lazy {
    lineIcon {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17.5f); lineTo(14f, 17.5f)
    }
}

val IconProfile: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 8.2f)
        arcTo(3.7f, 3.7f, 0f, true, true, 12f, 15.6f)
        arcTo(3.7f, 3.7f, 0f, true, true, 12f, 8.2f)
        close()
        moveTo(4.8f, 20.2f)
        curveTo(4.8f, 16.6f, 8f, 14.2f, 12f, 14.2f)
        curveTo(16f, 14.2f, 19.2f, 16.6f, 19.2f, 20.2f)
    }
}

val IconBack: ImageVector by lazy {
    lineIcon { moveTo(14.8f, 5f); lineTo(7.8f, 12f); lineTo(14.8f, 19f) }
}

val IconChevronRight: ImageVector by lazy {
    lineIcon { moveTo(9.2f, 6f); lineTo(15f, 12f); lineTo(9.2f, 18f) }
}

val IconMore: ImageVector by lazy {
    lineIcon {
        moveTo(4.7f, 12f); arcTo(1.5f, 1.5f, 0f, false, true, 7.7f, 12f); arcTo(1.5f, 1.5f, 0f, false, true, 4.7f, 12f); close()
        moveTo(10.7f, 12f); arcTo(1.5f, 1.5f, 0f, false, true, 13.7f, 12f); arcTo(1.5f, 1.5f, 0f, false, true, 10.7f, 12f); close()
        moveTo(16.7f, 12f); arcTo(1.5f, 1.5f, 0f, false, true, 19.7f, 12f); arcTo(1.5f, 1.5f, 0f, false, true, 16.7f, 12f); close()
    }
}

// ============================================================
// 播放控制（实心）
// ============================================================

val IconPlay: ImageVector by lazy {
    solidIcon {
        // 光学居中：内容区 4~20，视觉重心对齐
        moveTo(8.2f, 5.4f)
        lineTo(19.2f, 12f)
        lineTo(8.2f, 18.6f)
        quadTo(7.4f, 19.1f, 7.4f, 18.2f)
        lineTo(7.4f, 5.8f)
        quadTo(7.4f, 4.9f, 8.2f, 5.4f)
        close()
    }
}

val IconPause: ImageVector by lazy {
    solidIcon {
        moveTo(7f, 5.2f)
        quadTo(8.4f, 5.2f, 8.4f, 5.2f)
        lineTo(8.4f, 18.8f)
        quadTo(8.4f, 18.8f, 7f, 18.8f)
        quadTo(5.6f, 18.8f, 5.6f, 18.8f)
        lineTo(5.6f, 5.2f)
        quadTo(5.6f, 5.2f, 7f, 5.2f)
        close()
        moveTo(17f, 5.2f)
        quadTo(18.4f, 5.2f, 18.4f, 5.2f)
        lineTo(18.4f, 18.8f)
        quadTo(18.4f, 18.8f, 17f, 18.8f)
        quadTo(15.6f, 18.8f, 15.6f, 18.8f)
        lineTo(15.6f, 5.2f)
        quadTo(15.6f, 5.2f, 17f, 5.2f)
        close()
    }
}

val IconPrev: ImageVector by lazy {
    solidIcon {
        moveTo(19.4f, 6f)
        lineTo(9.4f, 12f)
        lineTo(19.4f, 18f)
        close()
        moveTo(6.4f, 5.6f)
        quadTo(8f, 5.6f, 8f, 5.6f)
        lineTo(8f, 18.4f)
        quadTo(8f, 18.4f, 6.4f, 18.4f)
        quadTo(4.8f, 18.4f, 4.8f, 18.4f)
        lineTo(4.8f, 5.6f)
        quadTo(4.8f, 5.6f, 6.4f, 5.6f)
        close()
    }
}

val IconNext: ImageVector by lazy {
    solidIcon {
        moveTo(4.6f, 6f)
        lineTo(14.6f, 12f)
        lineTo(4.6f, 18f)
        close()
        moveTo(16f, 5.6f)
        quadTo(17.6f, 5.6f, 17.6f, 5.6f)
        lineTo(17.6f, 18.4f)
        quadTo(17.6f, 18.4f, 16f, 18.4f)
        quadTo(14.4f, 18.4f, 14.4f, 18.4f)
        lineTo(14.4f, 5.6f)
        quadTo(14.4f, 5.6f, 16f, 5.6f)
        close()
    }
}

// ============================================================
// 功能
// ============================================================

val IconVolume: ImageVector by lazy {
    lineIcon {
        moveTo(4.2f, 14.8f)
        lineTo(4.2f, 9.2f)
        quadTo(4.2f, 7.6f, 5.4f, 7.6f)
        lineTo(8.2f, 7.6f)
        lineTo(12.8f, 4.4f)
        lineTo(12.8f, 19.6f)
        lineTo(8.2f, 16.4f)
        lineTo(5.4f, 16.4f)
        quadTo(4.2f, 16.4f, 4.2f, 14.8f)
        close()
        moveTo(16.2f, 9.4f)
        curveTo(17.6f, 10.6f, 17.6f, 13.4f, 16.2f, 14.6f)
    }
}

val IconLyrics: ImageVector by lazy {
    lineIcon {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(5f, 17f); lineTo(13f, 17f)
    }
}

val IconEqualizer: ImageVector by lazy {
    lineIcon {
        // 三条推子 + 三个把手
        moveTo(6f, 5f); lineTo(6f, 19f)
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(18f, 5f); lineTo(18f, 19f)
        moveTo(4f, 9f); lineTo(8f, 9f)
        moveTo(10f, 14.5f); lineTo(14f, 14.5f)
        moveTo(16f, 7.5f); lineTo(20f, 7.5f)
    }
}

val IconQueue: ImageVector by lazy {
    lineIcon {
        // 三条曲目行 + 右下角播放三角。
        // 以前只有三条横线，和均衡器的三根推子并排时几乎分不出来。
        moveTo(3.5f, 6.5f); lineTo(15f, 6.5f)
        moveTo(3.5f, 12f); lineTo(15f, 12f)
        moveTo(3.5f, 17.5f); lineTo(10f, 17.5f)
        moveTo(15.5f, 14.5f); lineTo(20.5f, 17.5f); lineTo(15.5f, 20.5f); close()
        // 右下角下载小箭头：单笔画，不重叠
        moveTo(19.2f, 14.2f)
        lineTo(19.2f, 19.2f)
        moveTo(16.6f, 17.2f)
        lineTo(19.2f, 19.4f)
        lineTo(21.4f, 17.2f)
    }
}

val IconDownload: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 4.5f)
        lineTo(12f, 14f)
        moveTo(7.6f, 10.6f)
        lineTo(12f, 15f)
        lineTo(16.4f, 10.6f)
        moveTo(5f, 19.5f)
        lineTo(19f, 19.5f)
    }
}

val IconDisk: ImageVector by lazy {
    lineIcon {
        moveTo(3.6f, 4.6f)
        lineTo(20.4f, 4.6f)
        quadTo(21.4f, 4.6f, 21.4f, 5.6f)
        lineTo(21.4f, 18.4f)
        quadTo(21.4f, 19.4f, 20.4f, 19.4f)
        lineTo(3.6f, 19.4f)
        quadTo(2.6f, 19.4f, 2.6f, 18.4f)
        lineTo(2.6f, 5.6f)
        quadTo(2.6f, 4.6f, 3.6f, 4.6f)
        close()
        moveTo(2.6f, 10f); lineTo(21.4f, 10f)
        moveTo(9.4f, 4.6f); lineTo(9.4f, 19.4f)
        moveTo(13.4f, 7.3f); arcTo(1.3f, 1.3f, 0f, true, true, 13.4f, 9.9f); arcTo(1.3f, 1.3f, 0f, true, true, 13.4f, 7.3f); close()
    }
}

val IconServer: ImageVector by lazy {
    lineIcon {
        moveTo(4f, 5.5f); lineTo(20f, 5.5f)
        moveTo(4f, 18.5f); lineTo(20f, 18.5f)
        moveTo(4f, 5.5f); lineTo(4f, 9.5f)
        moveTo(20f, 5.5f); lineTo(20f, 9.5f)
        moveTo(4f, 14.5f); lineTo(4f, 18.5f)
        moveTo(20f, 14.5f); lineTo(20f, 18.5f)
        moveTo(9.5f, 7.5f); arcTo(1.3f, 1.3f, 0f, true, true, 9.5f, 10.1f); arcTo(1.3f, 1.3f, 0f, true, true, 9.5f, 7.5f); close()
        moveTo(9.5f, 16.5f); arcTo(1.3f, 1.3f, 0f, true, true, 9.5f, 19.1f); arcTo(1.3f, 1.3f, 0f, true, true, 9.5f, 16.5f); close()
    }
}

val IconMusicNote: ImageVector by lazy {
    lineIcon {
        moveTo(9.2f, 17.4f)
        lineTo(9.2f, 6.4f)
        lineTo(19.2f, 4.4f)
        lineTo(19.2f, 15.4f)
        moveTo(6.8f, 17.4f)
        arcTo(2.4f, 2.4f, 0f, true, true, 6.8f, 22.2f)
        arcTo(2.4f, 2.4f, 0f, true, true, 6.8f, 17.4f)
        close()
        moveTo(16.8f, 15.4f)
        arcTo(2.4f, 2.4f, 0f, true, true, 16.8f, 20.2f)
        arcTo(2.4f, 2.4f, 0f, true, true, 16.8f, 15.4f)
        close()
    }
}

/** 图片文件（WebDAV 里的 cover.jpg 之类） */
val IconImage: ImageVector by lazy {
    lineIcon {
        moveTo(4.2f, 5.4f)
        lineTo(19.8f, 5.4f)
        lineTo(19.8f, 18.6f)
        lineTo(4.2f, 18.6f)
        close()
        // 山形 + 太阳
        moveTo(4.6f, 15.6f)
        lineTo(9.4f, 10.8f)
        lineTo(13.2f, 14.6f)
        lineTo(15.6f, 12.2f)
        lineTo(19.4f, 16f)
        moveTo(15.6f, 8.2f)
        arcTo(1.5f, 1.5f, 0f, true, true, 15.6f, 11.2f)
        arcTo(1.5f, 1.5f, 0f, true, true, 15.6f, 8.2f)
        close()
    }
}

val IconRecent: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 7.2f)
        lineTo(12f, 12f)
        lineTo(15.4f, 14f)
        moveTo(12f, 4.2f)
        arcTo(7.8f, 7.8f, 0f, true, true, 12f, 19.8f)
        arcTo(7.8f, 7.8f, 0f, true, true, 12f, 4.2f)
        close()
    }
}

val IconPlaylist: ImageVector by lazy {
    lineIcon {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17.5f); lineTo(20f, 17.5f)
        moveTo(7.6f, 6.5f); arcTo(1.5f, 1.5f, 0f, true, true, 7.6f, 9.5f); arcTo(1.5f, 1.5f, 0f, true, true, 7.6f, 6.5f); close()
        moveTo(13.4f, 12f); arcTo(1.5f, 1.5f, 0f, true, true, 13.4f, 15f); arcTo(1.5f, 1.5f, 0f, true, true, 13.4f, 12f); close()
        moveTo(9.4f, 17.5f); arcTo(1.5f, 1.5f, 0f, true, true, 9.4f, 20.5f); arcTo(1.5f, 1.5f, 0f, true, true, 9.4f, 17.5f); close()
    }
}

val IconArtist: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 4.6f)
        arcTo(3.7f, 3.7f, 0f, true, true, 12f, 11.4f)
        arcTo(3.7f, 3.7f, 0f, true, true, 12f, 4.6f)
        close()
        moveTo(5f, 20.2f)
        curveTo(5f, 16.6f, 8.1f, 14.2f, 12f, 14.2f)
        curveTo(15.9f, 14.2f, 19f, 16.6f, 19f, 20.2f)
    }
}

val IconFolder: ImageVector by lazy {
    lineIcon {
        moveTo(3.6f, 7.4f)
        lineTo(9.4f, 7.4f)
        lineTo(11f, 9.4f)
        lineTo(20.4f, 9.4f)
        quadTo(21.4f, 9.4f, 21.4f, 10.4f)
        lineTo(21.4f, 17.4f)
        quadTo(21.4f, 18.4f, 20.4f, 18.4f)
        lineTo(3.6f, 18.4f)
        quadTo(2.6f, 18.4f, 2.6f, 17.4f)
        lineTo(2.6f, 8.4f)
        quadTo(2.6f, 7.4f, 3.6f, 7.4f)
        close()
    }
}

val IconSparkle: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 3.6f)
        lineTo(13.8f, 9.4f)
        lineTo(19.6f, 11.2f)
        lineTo(13.8f, 13f)
        lineTo(12f, 18.8f)
        lineTo(10.2f, 13f)
        lineTo(4.4f, 11.2f)
        lineTo(10.2f, 9.4f)
        close()
    }
}

val IconHeart: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 19.4f)
        curveTo(6.4f, 15.4f, 3.6f, 12.6f, 3.6f, 9.4f)
        curveTo(3.6f, 6.6f, 5.8f, 4.6f, 8.2f, 4.6f)
        curveTo(9.8f, 4.6f, 11.2f, 5.4f, 12f, 6.8f)
        curveTo(12.8f, 5.4f, 14.2f, 4.6f, 15.8f, 4.6f)
        curveTo(18.2f, 4.6f, 20.4f, 6.6f, 20.4f, 9.4f)
        curveTo(20.4f, 12.6f, 17.6f, 15.4f, 12f, 19.4f)
        close()
    }
}

// ---------------- 播放模式（同一视觉家族：线条 + 箭头） ----------------
/** 顺序播放：单向直行箭头 */
val IconPlayOrder: ImageVector by lazy {
    lineIcon {
        moveTo(4.5f, 12f); lineTo(18.2f, 12f)
        moveTo(15.2f, 8.6f); lineTo(18.8f, 12f); lineTo(15.2f, 15.4f)
    }
}

/** 列表循环：上下两条带箭头的回环 */
val IconPlayLoop: ImageVector by lazy {
    lineIcon {
        // 上行：左 → 右，右端箭头
        moveTo(6.8f, 7.6f); lineTo(15.6f, 7.6f)
        moveTo(13.2f, 5.2f); lineTo(15.9f, 7.6f); lineTo(13.2f, 10f)
        // 下行：右 → 左，左端箭头
        moveTo(17.2f, 16.4f); lineTo(8.4f, 16.4f)
        moveTo(10.8f, 18.8f); lineTo(8.1f, 16.4f); lineTo(10.8f, 14f)
    }
}

/** 单曲循环：回环 + 中间一个「1」 */
val IconPlaySingle: ImageVector by lazy {
    lineIcon {
        moveTo(6.8f, 7.6f); lineTo(15.6f, 7.6f)
        moveTo(13.2f, 5.2f); lineTo(15.9f, 7.6f); lineTo(13.2f, 10f)
        moveTo(17.2f, 16.4f); lineTo(8.4f, 16.4f)
        moveTo(10.8f, 18.8f); lineTo(8.1f, 16.4f); lineTo(10.8f, 14f)
        // 中间的 1
        moveTo(11.1f, 10.7f); lineTo(12.5f, 9.7f); lineTo(12.5f, 14.3f)
    }
}

/** 随机播放：两条交叉线，右端各带箭头 */
val IconPlayShuffle: ImageVector by lazy {
    lineIcon {
        moveTo(4.6f, 16f); lineTo(8.3f, 16f); lineTo(15.7f, 8f); lineTo(19.4f, 8f)
        moveTo(16.9f, 5.6f); lineTo(19.6f, 8f); lineTo(16.9f, 10.4f)
        moveTo(4.6f, 8f); lineTo(8.3f, 8f); lineTo(15.7f, 16f); lineTo(19.4f, 16f)
        moveTo(16.9f, 13.6f); lineTo(19.6f, 16f); lineTo(16.9f, 18.4f)
    }
}

/** 加号（加入播放队列） */
val IconPlus: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 5.4f); lineTo(12f, 18.6f)
        moveTo(5.4f, 12f); lineTo(18.6f, 12f)
    }
}

/** 勾选（选中态） */
val IconCheck: ImageVector by lazy {
    lineIcon {
        moveTo(5.2f, 12.6f); lineTo(10.2f, 17.4f); lineTo(18.8f, 6.8f)
    }
}

/** 关闭 / 移除 */
val IconClose: ImageVector by lazy {
    lineIcon {
        moveTo(6.4f, 6.4f); lineTo(17.6f, 17.6f)
        moveTo(17.6f, 6.4f); lineTo(6.4f, 17.6f)
    }
}

/**
 * 刷新（换一批）—— 圆弧 + 折线箭头，右上留开口。
 *
 * ⚠️ arcToRelative 的参数名容易搞错，Compose 1.x 的签名是
 * `(a, b, theta, isMoreThanHalf, isPositiveArc, dx1, dy1)`，
 * **没有** rx/ry/sweepAngle 这些名字。这里分两段弧各走 180°。
 *
 * ⚠️ 箭头只能用折线画，不要试图混实心三角：lineIcon 的 fill 是 null，
 * 三角会被吃掉只剩两条边线；而 ImageVector 上没有 addPath（那是 Builder 的方法）。
 */
val IconRefresh: ImageVector by lazy {
    lineIcon {
        // 圆弧：右上开口起，绕左侧一圈回右下
        moveTo(17.2f, 7.2f)
        arcToRelative(7.2f, 7.2f, 0f, false, true, 0f, 9.6f)
        arcToRelative(7.2f, 7.2f, 0f, false, true, 0f, 9.6f)
        // 箭头：开口处两段折线成 V 形，指向左下（"转回来"）
        moveTo(17.6f, 4.2f); lineTo(17.6f, 7.6f)
        moveTo(14.6f, 5.0f); lineTo(17.6f, 7.6f)
    }
}

/** 信息：圆圈 + 竖点 + 竖线，用于「音频信息 / AI 导赏」入口 */
val IconInfo: ImageVector by lazy {
    lineIcon {
        moveTo(12f, 21f)
        curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
        curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
        curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
        curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
        close()
        moveTo(12f, 10.5f)
        lineTo(12f, 16.5f)
        moveTo(12f, 7.4f)
        lineTo(12f, 7.5f)
    }
}
