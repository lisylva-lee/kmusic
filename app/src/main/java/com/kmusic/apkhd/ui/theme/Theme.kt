package com.kmusic.apkhd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * KMusic 设计 Token —— 与 UI 设计稿一一对应。
 * 全站仅一个强调色 indigo，只用于进度条、母带标签细线、选中态。
 */

// ---- 深色 ----
val DarkBg = Color(0xFF0A0C11)
val DarkS1 = Color(0xFF12141A)
val DarkS2 = Color(0xFF1A1D25)
val DarkS3 = Color(0xFF22262F)
val DarkS4 = Color(0xFF2A2F3A)
val DarkLine = Color(0x14FFFFFF)          // 白 8%
val DarkT1 = Color(0xFFEDEFF4)
val DarkT2 = Color(0xFF9BA3B4)
val DarkT3 = Color(0xFF5C6472)
val DarkAccent = Color(0xFF7B78FF)
val DarkAccentDim = Color(0x267B78FF)     // 14%
val DarkAccentLine = Color(0x6B7B78FF)    // 42%
val DarkOnAccent = Color(0xFFFFFFFF)

// ---- 深色 · 藏蓝（默认）----
// 纯黑 #0A0C11 在手机/车机上太"黑"，层次全糊在一起；换成偏蓝的深色，
// 卡片/浮层靠亮度差分层，不再依赖描边。
val NavyBg = Color(0xFF0B1526)
val NavyS1 = Color(0xFF101D31)
val NavyS2 = Color(0xFF16263D)
val NavyS3 = Color(0xFF1D3050)
val NavyS4 = Color(0xFF243A5E)
val NavyLine = Color(0x1AFFFFFF)          // 白 10%
val NavyT1 = Color(0xFFE8EEF8)
val NavyT2 = Color(0xFF9FB0C9)
val NavyT3 = Color(0xFF66799A)
val NavyAccent = Color(0xFF8B9BFF)
val NavyOnAccent = Color(0xFFFFFFFF)
val NavyCoverGrad = listOf(Color(0xFF1B2A47), Color(0xFF0A1120))

// ---- 浅色 ----
val LightBg = Color(0xFFF6F3EC)
val LightS1 = Color(0xFFFDFCF9)
val LightS2 = Color(0xFFEFEBE1)
val LightS3 = Color(0xFFE6E0D3)
val LightS4 = Color(0xFFDDD6C6)
val LightLine = Color(0xFFE3DDCF)         // 纸感细线（B 套的可见分隔线）
val LightT1 = Color(0xFF15140F)
val LightT2 = Color(0xFF6B675C)
val LightT3 = Color(0xFFA8A192)
val LightAccent = Color(0xFFC4553B)
val LightAccentDim = Color(0x1AC4553B)
val LightAccentLine = Color(0x52C4553B)
val LightOnAccent = Color(0xFFFFFFFF)

// ---- 封面渐变（与设计稿一致，两套）----
val CoverGradDark = listOf(Color(0xFF252248), Color(0xFF0F1119))
val CoverGradLight = listOf(Color(0xFFE7E0CE), Color(0xFFF6F3EC))

private val MoyinDarkScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentDim,
    onPrimaryContainer = Color(0xFFB9B8F5),
    secondary = DarkT2,
    onSecondary = DarkT1,
    background = DarkBg,
    onBackground = DarkT1,
    surface = DarkS1,
    onSurface = DarkT1,
    surfaceVariant = DarkS2,
    onSurfaceVariant = DarkT2,
    surfaceContainer = DarkS2,
    surfaceContainerHigh = DarkS3,
    surfaceContainerHighest = DarkS4,
    outline = DarkT3,
    outlineVariant = DarkLine,
    error = Color(0xFFE5484D)
)

private val MoyinLightScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccentDim,
    onPrimaryContainer = Color(0xFF3F3CB8),
    secondary = LightT2,
    onSecondary = LightT1,
    background = LightBg,
    onBackground = LightT1,
    surface = LightS1,
    onSurface = LightT1,
    surfaceVariant = LightS2,
    onSurfaceVariant = LightT2,
    surfaceContainer = LightS2,
    surfaceContainerHigh = LightS3,
    surfaceContainerHighest = LightS4,
    outline = LightT3,
    outlineVariant = LightLine,
    error = Color(0xFFD32F2F)
)

private val MoyinTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    bodySmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp)
)

/** 主题模式：跟随系统 / 固定深色 / 固定浅色 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * 马卡龙主题色。
 *
 * 每套给两个色：strong（文字/图标用，压得住浅底）+ soft（填充面用，配合透明度）。
 * 只给一个色不行：马卡龙本身偏淡，直接当文字色在米白底上会糊；
 * 反过来 strong 当大面积填充又太扎眼。两色分工，才有"糖纸"的感觉又不牺牲可读性。
 */
enum class AccentPreset(val label: String, val strong: Long, val soft: Long) {
    PEACH("蜜桃", 0xFFE2777C, 0xFFF7A8A8),
    MINT("薄荷", 0xFF3FA98F, 0xFF9FD8CB),
    LEMON("柠檬", 0xFFC99A28, 0xFFF3D97B),
    LAVENDER("薰衣草", 0xFF8B6FC0, 0xFFC3B1E1),
    SKY("海盐", 0xFF4C8CBF, 0xFFA8C8E8);

    companion object {
        /** 存的是名字；未知值一律回默认（避免老版本残留导致崩） */
        fun of(name: String?): AccentPreset? =
            entries.firstOrNull { it.name == name }
    }
}

/** 语义色板，UI 通过 LocalMoyinColors 直接取，不必每次写 if(dark) */
data class MoyinColors(
    val isDark: Boolean,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val s4: Color,
    val line: Color,
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val accent: Color,
    val accentDim: Color,
    val accentLine: Color,
    val onAccent: Color,
    val coverGrad: List<Color>,

    // ── A/B 两套视觉语言的差异项 ──
    /** 玻璃面板底色：深色下是白色 7.5%，浅色下是纸白 66% */
    val glass: Color,
    /** 玻璃面板的高光边 */
    val glassBorder: Color,
    /** B 套的可见分割线（比 line 重，纸感靠它撑） */
    val lineStrong: Color,
    /** 卡片圆角：A 圆润 20dp，B 近乎直角 6dp */
    val radiusCard: Dp,
    /** 大标题字体：A 无衬线，B 衬线 */
    val titleSerif: Boolean,
    /** 列表编号色：A 灰，B 砖红 */
    val numeral: Color,
    /** 填充面用的强调色（播放键底色 / 进度条 / 选中 chip）。默认与 accent 相同；
     *  选马卡龙时是 soft 色 × 不透明度，字和图标仍用 accent */
    val accentFill: Color = accent
)

val LocalMoyinColors = staticCompositionLocalOf {
    MoyinColors(true, DarkBg, DarkS1, DarkS2, DarkS3, DarkS4, DarkLine, DarkT1, DarkT2, DarkT3,
        DarkAccent, DarkAccentDim, DarkAccentLine, DarkOnAccent, CoverGradDark,
        Color(0x13FFFFFF), Color(0x38FFFFFF), Color(0x1FFFFFFF), 20.dp, true, DarkT3)
}

@Composable
fun ProvideMoyinColors(
    themeMode: ThemeMode,
    /** 马卡龙主题色；null = 默认（按明暗两套原色） */
    accentPreset: AccentPreset? = null,
    /** 填充面不透明度（0.2~1.0），只作用于马卡龙色 */
    accentAlpha: Float = 0.8f,
    /** 深色风格："NAVY" 藏蓝（默认） / "BLACK" 纯黑 */
    darkStyle: String = "NAVY",
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val navy = darkStyle != "BLACK"
    val base = if (dark) {
        // A · 暗夜流光：圆润 + 玻璃 + 无衬线（默认藏蓝，可切纯黑）
        if (navy) {
            MoyinColors(true, NavyBg, NavyS1, NavyS2, NavyS3, NavyS4, NavyLine, NavyT1, NavyT2, NavyT3,
                NavyAccent, NavyAccent.copy(alpha = 0.15f), NavyAccent.copy(alpha = 0.42f), NavyOnAccent,
                NavyCoverGrad,
                glass = Color(0x16FFFFFF), glassBorder = Color(0x3DFFFFFF),
                lineStrong = Color(0x2EFFFFFF), radiusCard = 20.wdp,
                titleSerif = false, numeral = NavyT3)
        } else {
            MoyinColors(true, DarkBg, DarkS1, DarkS2, DarkS3, DarkS4, DarkLine, DarkT1, DarkT2, DarkT3,
                DarkAccent, DarkAccentDim, DarkAccentLine, DarkOnAccent, CoverGradDark,
                glass = Color(0x13FFFFFF), glassBorder = Color(0x38FFFFFF),
                lineStrong = Color(0x1FFFFFFF), radiusCard = 20.wdp,
                titleSerif = false, numeral = DarkT3)
        }
    } else {
        // B · 纸感编辑：近乎直角 + 可见细线 + 衬线标题 + 砖红编号
        MoyinColors(false, LightBg, LightS1, LightS2, LightS3, LightS4, LightLine, LightT1, LightT2, LightT3,
            LightAccent, LightAccentDim, LightAccentLine, LightOnAccent, CoverGradLight,
            glass = Color(0xA6FDFCF9), glassBorder = Color(0xFFD5CEBC),
            lineStrong = Color(0xFF15140F), radiusCard = 6.wdp,
            titleSerif = true, numeral = LightAccent)
    }

    // 选了马卡龙：accent 换成 strong（保证文字对比度），填充面用 soft × 不透明度
    val colors = if (accentPreset == null) base else {
        val strong = Color(accentPreset.strong)
        val soft = Color(accentPreset.soft)
        base.copy(
            accent = strong,
            accentDim = soft.copy(alpha = 0.24f),
            accentLine = strong.copy(alpha = 0.45f),
            accentFill = soft.copy(alpha = accentAlpha.coerceIn(0.3f, 1f)),
            // 文字对比度要按「半透明填充叠在页面底色上」的实际结果算，
            // 不能只看 soft —— 30% 透明度时底下是深色还是浅色，结论完全相反。
            onAccent = if (soft.copy(alpha = accentAlpha.coerceIn(0.3f, 1f))
                    .compositeOver(base.bg).luminance() > 0.55f
            ) Color(0xFF1E1E22) else Color.White
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMoyinColors provides colors) {
        MaterialTheme(
            colorScheme = (if (dark) darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                background = colors.bg,
                onBackground = colors.t1,
                surface = colors.s1,
                onSurface = colors.t1,
                surfaceVariant = colors.s2,
                onSurfaceVariant = colors.t2,
                surfaceContainer = colors.s2,
                surfaceContainerHigh = colors.s3,
                surfaceContainerHighest = colors.s4,
                outline = colors.t3,
                outlineVariant = colors.line
            ) else MoyinLightScheme).copy(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                primaryContainer = colors.accentDim,
                onPrimaryContainer = colors.accent
            ),
            typography = MoyinTypography,
            content = content
        )
    }
}

/** 便捷访问：val c = moyinColors() */
@Composable
fun moyinColors(): MoyinColors = LocalMoyinColors.current

/**
 * 把带透明度的前景色叠到不透明背景上，得到最终显示色。
 *
 * 用途：`accentFill` 是半透明的，判断它上面的字该用黑还是白时，
 * 必须看它与页面底色混合后的实际亮度，而不是原色本身。
 */
fun Color.compositeOver(bg: Color): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = red * a + bg.red * (1f - a),
        green = green * a + bg.green * (1f - a),
        blue = blue * a + bg.blue * (1f - a),
        alpha = 1f
    )
}

/** 主题模式 → 实际明暗（SYSTEM 跟随系统） */
fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/**
 * 首页/设置页卡片的渐变。
 *
 * 之前卡片渐变是**硬编码**的：浅色两个色差只有 8，肉眼等于纯色；
 * 未高亮卡更是 `linearGradient(同色, 同色)`，两个主题都没有渐变。
 * 现在统一由 token 生成，并跟随马卡龙强调色。
 */
val MoyinColors.cardGradient: List<Color>
    get() = if (isDark) listOf(s3, s2) else listOf(Color.White, s4)

val MoyinColors.cardHighlightGradient: List<Color>
    get() = if (isDark) {
        listOf(
            accent.copy(alpha = 0.26f).compositeOver(s3),
            accent.copy(alpha = 0.10f).compositeOver(s2)
        )
    } else {
        listOf(
            accent.copy(alpha = 0.16f).compositeOver(Color.White),
            accent.copy(alpha = 0.05f).compositeOver(Color.White)
        )
    }

/** 顶部氛围光强度：深色更明显，浅色只留一层极淡的色雾 */
val MoyinColors.ambientAlpha: Float
    get() = if (isDark) 0.18f else 0.07f
