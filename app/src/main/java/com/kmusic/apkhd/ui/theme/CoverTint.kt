package com.kmusic.apkhd.ui.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 播放页封面取色（B1）。
 *
 * 目标：让播放页跟着封面走色，而不是所有歌都是同一个 accent。
 *
 * 但「跟封面走色」有两个必须挡住的情况，否则宁可不用：
 *  1. 封面是灰白/黑白专辑 → 取出来是一个脏灰，铺在深色底上像蒙了一层雾；
 *  2. 取出来的颜色和背景太接近 → 进度条、按钮看不见。
 *
 * 所以这里不是「取平均色就完事」，而是一条带门禁的流水线：
 * 平均色 → 抬饱和度 → 抬亮度 → 与背景对比度/色距校验 → 不达标就返回 null，
 * 由调用方回退到主题 accent。
 *
 * 只采 64×64：再大对结果没有帮助（平均色对降采样不敏感），却会让每次切歌多解几 MB。
 */
internal object CoverTint {

    /** 取色用的小图边长 */
    private const val SAMPLE_SIZE = 64

    /**
     * 饱和度下限。低于它的颜色基本是灰，抬上来也是人造色，直接判失败。
     * 0.32 来自实测：普通专辑封面的主色饱和度多在 0.4~0.7，
     * 纯黑白封面的噪声饱和度在 0.05 以下。
     */
    private const val MIN_SATURATION = 0.32f

    /** 抬饱和度后的目标值（只在原值介于门限和它之间时抬到这里） */
    private const val BOOSTED_SATURATION = 0.52f

    /**
     * 与背景的**对比度**下限（WCAG 比值）。
     *
     * 这里刻意不用「绝对亮度」当门槛：纯红 #CC2222 的相对亮度只有 0.27，
     * 但放在 #141416 的深底上对比度是 5.7 —— 肉眼非常清楚。
     * 早先按绝对亮度 0.42 卡，结果所有深红/深蓝封面全被拒，
     * 播放页在这些歌上永远是主题紫，看起来像取色功能坏了。
     */
    private const val MIN_CONTRAST = 3.0f

    /** 绝对亮度地板：再怎么样也不能是「和背景一样黑」的颜色 */
    private const val MIN_LUMINANCE_FLOOR = 0.12f

    /** 与背景的色距下限（RGB 欧氏距离 / √3） */
    private const val MIN_COLOR_DISTANCE = 0.16f

    /**
     * 结果缓存：切回上一首不该重新解码。
     *
     * 存 Int（ARGB）而不是 Color —— Color 是 inline value class，
     * 不能直接当泛型参数（编译期就是「不在 Any 边界内」）。
     * 0 用作「取色失败」的哨兵值（真彩色 alpha 恒为 0xFF，不会撞）。
     */
    private val cache = object : LruCache<String, Int>(24) {}

    private const val CACHE_MISS = 0

    /**
     * 从封面 URL 取一个可用的强调色。
     *
     * @param fallbackBg 播放页背景色（用于对比度校验）
     * @return 可用颜色；封面缺失/纯灰/对比不足时返回 null（调用方回退 accent）
     */
    suspend fun resolve(
        imageLoader: coil.ImageLoader,
        context: android.content.Context,
        coverUrl: String?,
        fallbackBg: Color
    ): Color? {
        if (coverUrl.isNullOrBlank()) return null
        val cached = cache.get(coverUrl)
        if (cached != null) {
            return if (cached == CACHE_MISS) null else Color(cached)
        }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching null
                val sampled = averageColor(bitmap)
                sampled?.let { polish(it, fallbackBg) }
            }.getOrNull()
        }
        cache.put(coverUrl, color?.toArgb() ?: CACHE_MISS)
        return color
    }

    /**
     * 平均色：按饱和度加权，并剔除接近纯黑/纯白的像素。
     *
     * 直接算算术平均会把「大面积白边 + 一点点彩色」平均成灰；
     * 加权后画面里的主色才会占主导。纯黑纯白像素多是包装/描边，不参与。
     */
    internal fun averageColor(bitmap: Bitmap): Color? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val step = max(1, min(w, h) / SAMPLE_SIZE + 1)
        var r = 0f
        var g = 0f
        var b = 0f
        var wt = 0f
        var count = 0
        val px = IntArray(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                bitmap.getPixels(px, 0, 1, x, y, 1, 1)
                val c = px[0]
                val rr = ((c shr 16) and 0xFF) / 255f
                val gg = ((c shr 8) and 0xFF) / 255f
                val bb = (c and 0xFF) / 255f
                val mx = max(rr, max(gg, bb))
                val mn = min(rr, min(gg, bb))
                val sat = if (mx <= 0f) 0f else (mx - mn) / mx
                val lum = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                // 过暗/过亮的像素几乎不带色彩信息，权重压到 0
                val lw = when {
                    lum < 0.06f || lum > 0.96f -> 0f
                    else -> 1f
                }
                val weight = lw * (0.25f + sat)
                r += rr * weight
                g += gg * weight
                b += bb * weight
                wt += weight
                count++
                x += step
            }
            y += step
        }
        if (count == 0 || wt <= 0.0001f) return null
        return Color(r / wt, g / wt, b / wt)
    }

    /**
     * 把原始平均色整成「能在深色底上用的强调色」。
     *
     * 顺序有意义：先抬饱和（灰 → 有色彩），再抬亮度（深色底上要够亮），
     * 最后做对比校验 —— 校验必须用**最终颜色**，用中间值会放过不合格的颜色。
     */
    internal fun polish(raw: Color, bg: Color): Color? = polishColor(
        rawArgb = raw.toArgb(),
        bgArgb = bg.toArgb(),
        isDark = true
    )

    /**
     * 门禁的纯函数入口（不碰 `android.graphics.Color`，可在 JVM 单测里跑）。
     *
     * @param isDark 浅色主题一律不取色 —— B 套是纸面设计，见 [rememberCoverTint] 注释
     * @return 可用颜色；灰/太暗/与背景太近/浅色主题一律 null
     */
    internal fun polishColor(rawArgb: Int, bgArgb: Int, isDark: Boolean): Color? {
        if (!isDark) return null
        val bg = Color(bgArgb)
        val (h, s0, v0) = rgbToHsv(
            (rawArgb shr 16) and 0xFF,
            (rawArgb shr 8) and 0xFF,
            rawArgb and 0xFF
        )
        // 灰：判失败。不做「无中生有」的抬饱和 —— 黑白封面就该用主题色
        if (s0 < MIN_SATURATION) return null
        val sat = max(s0, BOOSTED_SATURATION)
        var value = v0.coerceAtLeast(0.55f)
        var candidate = hsvToColor(h, sat, value)

        // 亮度太低就往上提 value（保持色相，不掉饱和），提到「至少和背景分得开」为止
        var guard = 0
        while (candidate.luminance() < MIN_LUMINANCE_FLOOR && guard < 8) {
            value = (value + 0.06f).coerceAtMost(1f)
            candidate = hsvToColor(h, sat, value)
            guard++
        }
        if (candidate.luminance() < MIN_LUMINANCE_FLOOR) return null

        if (contrastRatio(candidate, bg) < MIN_CONTRAST) return null
        if (colorDistance(candidate, bg) < MIN_COLOR_DISTANCE) return null
        return candidate
    }

    /** WCAG 对比度：1..21。用它而不是亮度差，才不会把深彩色误判成「看不见」 */
    internal fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** RGB(0..255) → HSV(h:0..360, s:0..1, v:0..1)，纯数学，不依赖 Android 框架 */
    internal fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val d = mx - mn
        val h = when {
            d <= 1e-6f -> 0f
            mx == rf -> 60f * (((gf - bf) / d).mod(6f))
            mx == gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }
        val s = if (mx <= 1e-6f) 0f else d / mx
        return Triple(h, s, mx)
    }

    /** HSV → Color，纯数学 */
    internal fun hsvToColor(h: Float, s: Float, v: Float): Color {
        val c = v * s
        val hh = (h.mod(360f)) / 60f
        val x = c * (1f - kotlin.math.abs(hh.mod(2f) - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f)
        )
    }

    private fun colorFromHsv(h: Float, s: Float, v: Float): Color = hsvToColor(h, s, v)

    /** 归一化的 RGB 距离（0..1），比 luminance 差更能分辨「同类色」 */
    internal fun colorDistance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db) / 1.732f
    }
}

/**
 * 播放页封面强调色。
 *
 * 浅色主题**一律返回 null**：B 套是「纸感编辑」设计，一层封面色会把纸面弄脏，
 * 而且纸面上要保证文字对比度，颜色不能自由漂。
 */
@Composable
fun rememberCoverTint(coverUrl: String?, enabled: Boolean = true): Color? {
    val c = moyinColors()
    if (!enabled || !c.isDark) return null
    val context = LocalContext.current
    val loader = context.imageLoader
    val bg = c.bg
    val state = produceState<Color?>(initialValue = null, coverUrl, bg) {
        value = CoverTint.resolve(loader, context, coverUrl, bg)
    }
    return state.value
}

/**
 * 播放页内部的强调色：有封面取色就用它，没有就回退主题 accent。
 * 只作用于播放页（进度条已播段、主播放键、顶部氛围光），其它页面不受影响。
 */
val LocalPlayerAccent = staticCompositionLocalOf<Color?> { null }

/** 便捷读取：null 表示「没取到色，用主题 accent」 */
@Composable
fun playerAccent(fallback: Color): Color = LocalPlayerAccent.current ?: fallback

/**
 * 派生一份「强调色换成封面色」的调色板，只给播放页用。
 *
 * 为什么整份替换而不是逐处传色：播放页有 20+ 处 `c.accent` / `c.accentFill`
 * （进度条、主播放键、选中态、音效面板…），逐处改必然漏，漏一处就是
 * 「进度条跟着封面变、按钮还是紫色」的花屏感。
 *
 * 同时把 `accentDim` / `accentLine` / `onAccent` 一起重算：
 *  - dim/line 是从 accent 派生的半透明值，不跟着换会出现「亮色进度条 + 紫色光晕」；
 *  - onAccent 必须按新底色的实际亮度选黑白，否则浅封面色上白字看不见。
 */
fun MoyinColors.withPlayerAccent(tint: Color?): MoyinColors {
    if (tint == null) return this
    return copy(
        accent = tint,
        accentDim = tint.copy(alpha = 0.15f),
        accentLine = tint.copy(alpha = 0.42f),
        accentFill = tint,
        onAccent = if (tint.luminance() > 0.55f) Color(0xFF1E1E22) else Color.White
    )
}
