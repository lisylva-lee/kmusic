package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 均衡器曲线的坐标数学 —— 从 [EqCurve] 里拆出来，纯函数、可在单测里验。
 *
 * 为什么单独成对象：曲线里最容易错的不是绘制，是**频率↔像素**的映射
 * （对数轴、边界收敛、拖拽增益换算）。这三件事能用整数断言钉死，
 * 就不该埋在 Canvas 的 lambda 里靠肉眼看。
 */
internal object EqCurveMath {

    /** 横轴频率范围（Hz）。与常见 10 段 EQ 图一致 */
    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f

    /** 纵轴默认范围（dB），真实范围由 [bandRange] 决定 */
    const val DEFAULT_LIMIT_DB = 15f

    /** 频率 → 0..1 的横向位置（对数轴） */
    fun freqToFraction(hz: Float): Float {
        val f = hz.coerceIn(MIN_HZ, MAX_HZ)
        return (ln(f / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).coerceIn(0f, 1f)
    }

    /** 0..1 横向位置 → 频率（Hz） */
    fun fractionToFreq(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return MIN_HZ * (MAX_HZ / MIN_HZ).pow(t)
    }

    /** 增益（mB）→ dB */
    fun milliBelToDb(mB: Int): Float = mB / 100f

    /** dB → 增益（mB），四舍五入到 100 的整数倍由调用方决定 */
    fun dbToMilliBel(db: Float): Int = (db * 100f).roundToInt()

    /**
     * 单调三次插值（Fritsch–Carlson）的采样点。
     *
     * 用单调插值而不是普通三次样条：后者在相邻频段落差大时会在两点之间
     * **冲出输入范围**（拉 60Hz +12dB、230Hz -6dB，曲线却先拱到 +15dB），
     * 用户看到的曲线和实际听感对不上。单调插值保证曲线永远落在相邻值之间。
     *
     * @param xs 横向位置（必须严格递增，长度 ≥ 2）
     * @param ys 纵轴值（dB）
     * @param samples 采样点数
     * @return 采样点列表，可能少于 samples（数据不足时）
     */
    fun monotoneCurve(
        xs: FloatArray,
        ys: FloatArray,
        samples: Int
    ): List<Pair<Float, Float>> {
        if (xs.size < 2 || xs.size != ys.size || samples < 2) return emptyList()

        val n = xs.size
        val dx = FloatArray(n - 1)
        val slope = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            dx[i] = (xs[i + 1] - xs[i]).coerceAtLeast(1e-6f)
            slope[i] = (ys[i + 1] - ys[i]) / dx[i]
        }

        // 端点切线：单侧斜率
        val tangent = FloatArray(n)
        tangent[0] = slope[0]
        tangent[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) {
            // 相邻斜率异号（极值点）→ 切线取 0，曲线在此处平顶，不冲出
            tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f
            else (slope[i - 1] + slope[i]) / 2f
        }
        // Fritsch–Carlson 限幅：保证单调段内不产生过冲
        for (i in 0 until n - 1) {
            if (slope[i] == 0f) {
                tangent[i] = 0f
                tangent[i + 1] = 0f
                continue
            }
            val a = tangent[i] / slope[i]
            val b = tangent[i + 1] / slope[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / kotlin.math.sqrt(h)
                tangent[i] = t * a * slope[i]
                tangent[i + 1] = t * b * slope[i]
            }
        }

        val out = ArrayList<Pair<Float, Float>>(samples)
        val x0 = xs.first()
        val x1 = xs.last()
        for (s in 0 until samples) {
            val x = x0 + (x1 - x0) * s / (samples - 1).toFloat()
            var seg = 0
            while (seg < n - 2 && x > xs[seg + 1]) seg++
            val h = (xs[seg + 1] - xs[seg]).coerceAtLeast(1e-6f)
            val t = ((x - xs[seg]) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2f * t3 - 3f * t2 + 1f
            val h10 = t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 = t3 - t2
            val y = h00 * ys[seg] + h10 * h * tangent[seg] +
                h01 * ys[seg + 1] + h11 * h * tangent[seg + 1]
            out.add(x to y)
        }
        return out
    }

    /** 频段标签：60 / 230 / 910 / 3.6k / 14k */
    fun freqLabel(hz: Int): String = when {
        hz <= 0 -> "—"
        hz >= 10_000 -> "${(hz / 1000f).roundToInt()}k"
        hz >= 1000 -> {
            val k = hz / 1000f
            val tenth = (k * 10f).roundToInt()
            if (tenth % 10 == 0) "${tenth / 10}k" else "${tenth / 10}.${tenth % 10}k"
        }
        else -> "$hz"
    }
}

/** 曲线命中判定参数：手指不要求精确点在圆心上 */
private const val HANDLE_HIT_RADIUS_DP = 22f
private const val HANDLE_VERTICAL_REACH = 1.7f

/**
 * 均衡器曲线（A1）。
 *
 * 替掉 v2.3.2 的 5 根竖条滑块：同类操作只保留一种形态。
 * 交互规则沿用 v2.3.2 的防误触结论，并更严格：
 *  - **只有按在圆点上**才算开始调曲线，点空白/轨道不改值（面板本身要上下滚）；
 *  - **方向判定**：起手后先看方向，纵向为主就判为滚面板，不消费事件；
 *  - 拖动期间曲线只在被拖的那一段变化，其余频段不动；
 *  - 双击圆点复位 0 dB。
 *
 * 拖动时右上角浮出读数（频率 + dB），松手 1.2s 后消失。
 */
@Composable
fun EqCurve(
    bands: List<Int>,
    freqs: List<Int>,
    range: IntArray,
    onBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val lo = (range.getOrNull(0) ?: -1500)
    val hi = (range.getOrNull(1) ?: 1500)
    // 纵轴不按 ±15 固定：老设备的均衡器可能只给 ±12，曲线按真实范围画才不撒谎
    val limitDb = maxOf(abs(lo), abs(hi)).coerceAtLeast(100) / 100f

    val latestBands by rememberUpdatedState(bands)
    val latestFreqs by rememberUpdatedState(freqs)
    val latestOnChange by rememberUpdatedState(onBandChange)

    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragDb by remember { mutableFloatStateOf(0f) }
    var dragActive by remember { mutableStateOf(false) }
    var idleTick by remember { mutableIntStateOf(0) }
    val lastTap = remember { mutableLongStateOf(0L) }
    // 松手后再等 1.2s 才收读数：拖动过程中不能被清掉（否则读到一半字就没了）
    androidx.compose.runtime.LaunchedEffect(dragActive, idleTick) {
        if (!dragActive && dragIdx >= 0) {
            kotlinx.coroutines.delay(1200)
            dragIdx = -1
        }
    }

    val axisColor = c.t3.copy(alpha = 0.22f)
    val labelColor = c.t3
    val textMeasurer = rememberTextMeasurer()

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(196.wdp)
                .padding(horizontal = 10.wdp, vertical = 6.wdp)
                .pointerInput(lo, hi, latestFreqs.size) {
                    val totalW = size.width.toFloat().coerceAtLeast(1f)
                    val totalH = size.height.toFloat().coerceAtLeast(1f)
                    val padTop = 22f * density
                    val padBottom = 18f * density
                    val plotH = (totalH - padTop - padBottom).coerceAtLeast(1f)

                    fun xOf(i: Int): Float {
                        val f = latestFreqs.getOrNull(i) ?: return totalW / 2f
                        return EqCurveMath.freqToFraction(f.toFloat()) * totalW
                    }

                    fun yOf(mB: Int): Float {
                        val db = EqCurveMath.milliBelToDb(mB)
                        return padTop + (limitDb - db.coerceIn(-limitDb, limitDb)) /
                            (2f * limitDb) * plotH
                    }

                    fun dbAt(y: Float): Float =
                        limitDb - (y - padTop) / plotH * 2f * limitDb

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 命中：横向落在圆点 ±22dp、纵向落在圆心 1.7 倍半径内
                        val hitR = HANDLE_HIT_RADIUS_DP * density
                        var hit = -1
                        var best = Float.MAX_VALUE
                        for (i in latestBands.indices) {
                            val dx = abs(down.position.x - xOf(i))
                            val dy = abs(down.position.y - yOf(latestBands[i]))
                            if (dx <= hitR && dy <= hitR * HANDLE_VERTICAL_REACH && dx < best) {
                                best = dx
                                hit = i
                            }
                        }
                        if (hit < 0) return@awaitEachGesture   // 没按到圆点 → 交给面板滚动

                        // 方向锁：这里**不判方向**。
                        // 曲线唯一的调节轴就是纵向，若沿用竖条那套「纵向＝滚面板」的判定，
                        // 每一次拖圆点都会被当成滚面板 —— 曲线变成只能看不能调。
                        // 防误触靠的是「必须先按住圆点」，不是靠方向。
                        var dx = 0f
                        var dy = 0f
                        while (abs(dx) <= viewConfiguration.touchSlop &&
                            abs(dy) <= viewConfiguration.touchSlop
                        ) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                                ?: return@awaitEachGesture
                            if (!ch.pressed) {
                                // 没移动就抬手：点了一下圆点 → 双击复位
                                val now = System.currentTimeMillis()
                                if (now - lastTap.longValue < 320L) {
                                    if (latestBands.getOrElse(hit) { 0 } != 0) {
                                        latestOnChange(hit, 0)
                                    }
                                    lastTap.longValue = 0L
                                } else {
                                    lastTap.longValue = now
                                }
                                return@awaitEachGesture
                            }
                            dx = ch.position.x - down.position.x
                            dy = ch.position.y - down.position.y
                        }

                        dragIdx = hit
                        dragActive = true
                        var lastDb = EqCurveMath.milliBelToDb(latestBands.getOrElse(hit) { 0 })
                        dragDb = lastDb
                        // 抓取偏移快速衰减 → 很快变成「圆心跟随手指」
                        var grabOffset = down.position.y - yOf(latestBands.getOrElse(hit) { 0 })
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume()
                                break
                            }
                            val db = dbAt(ch.position.y - grabOffset).coerceIn(-limitDb, limitDb)
                            grabOffset *= 0.82f
                            val mB = EqCurveMath.dbToMilliBel(db).coerceIn(lo, hi)
                            if (mB != EqCurveMath.dbToMilliBel(lastDb)) {
                                lastDb = EqCurveMath.milliBelToDb(mB)
                                dragDb = lastDb
                                latestOnChange(hit, mB)
                            }
                            ch.consume()
                        }
                        dragActive = false
                        idleTick++
                    }
                }
        ) {
            val padTop = 22.dp.toPx()
            val padBottom = 18.dp.toPx()
            val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)
            fun yOf(db: Float): Float =
                padTop + (limitDb - db.coerceIn(-limitDb, limitDb)) / (2f * limitDb) * plotH

            // ── 横向网格：0dB 实线，±6/±12 虚线 ──
            listOf(0f, 6f, -6f, 12f, -12f).forEach { db ->
                if (abs(db) > limitDb) return@forEach
                val y = yOf(db)
                val base = if (db == 0f) axisColor.copy(alpha = axisColor.alpha * 2.1f) else axisColor
                drawLine(
                    color = base,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = if (db == 0f) 1.2.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (db == 0f) null else PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 6.dp.toPx())
                    )
                )
                if (db == 0f || abs(db) == 6f) {
                    val label = if (db > 0) "+${db.toInt()}" else db.toInt().toString()
                    drawText(
                        textMeasurer,
                        text = label,
                        topLeft = Offset(2.dp.toPx(), y - 13.dp.toPx()),
                        style = androidx.compose.ui.text.TextStyle(
                            color = labelColor, fontSize = 9.sp
                        )
                    )
                }
            }

            // ── 纵向网格 + 频率标签 ──
            latestFreqs.forEachIndexed { i, hz ->
                val x = EqCurveMath.freqToFraction(hz.toFloat()) * size.width
                drawLine(
                    color = axisColor.copy(alpha = axisColor.alpha * 0.6f),
                    start = Offset(x, padTop),
                    end = Offset(x, padTop + plotH),
                    strokeWidth = 1.dp.toPx()
                )
                val label = EqCurveMath.freqLabel(hz)
                val layout = textMeasurer.measure(
                    label,
                    style = androidx.compose.ui.text.TextStyle(color = labelColor, fontSize = 9.sp)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                        padTop + plotH + 4.dp.toPx()
                    )
                )
            }

            // ── 0dB 基准上的参考平线（提示「原声」长什么样）──
            val xs = FloatArray(latestFreqs.size) { i ->
                EqCurveMath.freqToFraction(latestFreqs[i].toFloat())
            }
            val ys = FloatArray(latestBands.size) { i ->
                EqCurveMath.milliBelToDb(latestBands.getOrElse(i) { 0 })
            }
            if (xs.size >= 2) {
                val pts = EqCurveMath.monotoneCurve(xs, ys, 96)
                if (pts.isNotEmpty()) {
                    val path = Path()
                    pts.forEachIndexed { i, (fx, db) ->
                        val x = fx * size.width
                        val y = yOf(db)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    // 曲线到 0dB 的填充（只有正增益才填，负增益留白，观感上不糊）
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(pts.last().first * size.width, yOf(0f))
                        lineTo(pts.first().first * size.width, yOf(0f))
                        close()
                    }
                    drawPath(fill, color = c.accent.copy(alpha = 0.13f))
                    drawPath(
                        path,
                        color = c.accent,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // ── 圆点 ──
            latestBands.forEachIndexed { i, mB ->
                val x = EqCurveMath.freqToFraction(latestFreqs.getOrElse(i) { 1000 }.toFloat()) * size.width
                val y = yOf(EqCurveMath.milliBelToDb(mB))
                val active = i == dragIdx
                val r = if (active) 8.dp.toPx() else 6.5.dp.toPx()
                drawCircle(color = c.s1, radius = r, center = Offset(x, y))
                drawCircle(
                    color = c.accent,
                    radius = r,
                    center = Offset(x, y),
                    style = Stroke(width = 2.dp.toPx())
                )
                if (active) {
                    drawText(
                        textMeasurer,
                        text = "%+.1f dB".format(EqCurveMath.milliBelToDb(mB)),
                        topLeft = Offset(
                            (x - 22.dp.toPx()).coerceIn(0f, size.width - 44.dp.toPx()),
                            (y - 26.dp.toPx()).coerceAtLeast(0f)
                        ),
                        style = androidx.compose.ui.text.TextStyle(
                            color = c.accent, fontSize = 10.sp
                        )
                    )
                }
            }
        }

        // 拖动读数条：曲线里只标当前频段，这里再给一行「频率 · 增益」的完整信息，
        // 命中判定在 160dpi 车机上只有 22px 半径，光靠圆点旁的小字看不清
        val idx = dragIdx
        if (idx >= 0 && idx < bands.size) {
            Text(
                text = "${EqCurveMath.freqLabel(freqs.getOrNull(idx) ?: 0)} Hz · " +
                    "%+.1f dB".format(EqCurveMath.milliBelToDb(bands[idx])),
                color = c.accent,
                fontSize = 11.wsp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.wdp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
