package com.misaka.timelog.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.timelog.domain.ChartGeom
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.MINUTES_PER_DAY
import com.misaka.timelog.domain.StatsCalc
import com.misaka.timelog.ui.component.toColor

/**
 * 四张统计图 —— **全部手写 Canvas，不引任何图表库**。
 *
 * ## 为什么不引库（Vico / MPAndroidChart 之类）
 *
 * 这张图上有三处**库大概率不支持、或者要绕着做**的东西：
 *
 * 1. **曲线必须钳制**（[StatsCalc.smooth]）—— 库的 `monotone` / `natural` 平滑
 *    各有各的过冲行为，我控制不了它会不会沉到 0 轴以下。而那个 bug 原型踩过一次。
 * 2. **② 图今天那根柱顶上要点一个小圆点**，说明「矮是正常的，不是漏记」。
 * 3. **① 图的灰带要排在最顶**、**③ 图一格都不许合并** —— 都是很具体的画法要求。
 *
 * 引库的收益（省下画柱子和坐标轴的代码）远小于「画法被库决定」的代价。
 *
 * ## 坐标系：虚拟 350×200，跟原型的 SVG `viewBox` 一模一样
 *
 * 所有位置都按 `0..350` / `0..200` 这些数写，再由一个比例系数映射到真实画布。
 * 好处：**这一层的数字可以直接跟原型逐行对照**，不用在两套坐标之间换算。
 * 靠 `aspectRatio(350/200)` 保证两个方向同一个比例，图不会变形。
 */
/**
 * 画布几何（尺寸 + 四边留白）的**唯一来源**在 [ChartGeom] —— 那里有一份不依赖 Compose
 * 的定义，配 `ChartGeomTest` 穷举列数钉住「权重之和 == 画布尺寸」这条不变量。
 *
 * 这里只是给它起个短名字，方便下面的绘制代码读起来跟原型逐行对照。
 * ⚠️ **别在这里改数值** —— 改 [ChartGeom]。
 */
private val VW = ChartGeom.VW
private val VH = ChartGeom.VH
private val PAD_L = ChartGeom.PAD_L
private val PAD_R = ChartGeom.PAD_R
private val PAD_T = ChartGeom.PAD_T
private val PAD_B = ChartGeom.PAD_B

/**
 * ## ★★ 图表的一套配色 —— **必须跟主题走**
 *
 * 这里原本是 6 个写死的 `Color` 常量（`AX` / `GRID` / `RING_BG` / `INK` …），
 * 全部按**浅色底**挑的。App 一旦跑在深色主题下就全错：
 *
 * - `GRID = #EDF1F7`（近白）→ 深色卡片上几条**刺眼的白线**，比数据本身还抢眼；
 * - `RING_BG = #EEF2F8`（近白）→ 甜甜圈后面挂一个白环；
 * - `INK = #14161C`（近黑）→ **甜甜圈中间那个「总时长」数字在深色底上几乎看不见**；
 * - 再叠上面积图 0.72 的半透明，好几层相互穿透 → 整张图糊成一片。
 *
 * 御坂的原话是「对比度完全是乱的」。根因就这一条：**颜色没跟主题联动**。
 *
 * ⚠️ 所以这几个值**不要再写成顶层 val**。加颜色请往这两个实例里加，
 * 并且**浅色/深色两份都要给** —— 少给一份就会出现「浅色下好好的、深色下瞎了」。
 * （`ChartColorsTest` 会钉住「两套字段都非空且不相同」，别绕过它。）
 *
 * ⚠️ 另一个坑：`drawGrid` / `drawXLabel` 这些是 `DrawScope` 扩展、**不是 @Composable**，
 * 读不到 `MaterialTheme`。所以配色是**当参数传下去**的，不能在绘制函数里现取。
 */
internal data class ChartPalette(
    /** 轴上的小字（刻度 + 横轴标签）。 */
    val ax: Color,
    /** 网格线。**必须比卡片背景只亮一点点** —— 它是背景，不是内容。 */
    val grid: Color,
    /** 今天那一列的高亮。 */
    val today: Color,
    /** 还没到的那一列 / 空值。 */
    val future: Color,
    /** 甜甜圈的底环（表示「还没填满的部分」）。 */
    val ring: Color,
    /** 主要数字（甜甜圈中间那个总时长）。 */
    val ink: Color,
)/** 浅色主题。跟原型的 `app.css` 是一套，别改。 */
private val LightChart = ChartPalette(
    ax = Color(0xFF98A3B2),
    grid = Color(0xFFEDF1F7),
    today = Color(0xFF4C6FFF),
    future = Color(0xFFC9D2DF),
    ring = Color(0xFFEEF2F8),
    ink = Color(0xFF14161C),
)

/**
 * 深色主题。
 *
 * 取值原则：**网格线只比卡片背景亮一档**（`#2F333A` vs 卡片约 `#1E1E20`），
 * 这样它是「隐约的参考线」而不是「白线」；轴字用中灰 `#8B93A3`（能读但不抢戏）；
 * 主要数字反过来给近白 `#F2F4F8` —— 深色下「反白」才是「黑字」的等价物。
 */
private val DarkChart = ChartPalette(
    ax = Color(0xFF8B93A3),
    grid = Color(0xFF2F333A),
    today = Color(0xFF6E8CFF),
    future = Color(0xFF3A4048),
    ring = Color(0xFF2A2E35),
    ink = Color(0xFFF2F4F8),
)

/** 当前主题该用哪一套。**所有画图的地方都从这里取，不要自己判断深色**。 */
@Composable
private fun chartPalette(): ChartPalette = chartPaletteFor(isSystemInDarkTheme())

/**
 * 「深色用哪套」这个判断的**唯一入口**，单独抽出来是为了让 `ChartColorsTest` 能直接调它
 * （`@Composable` 在纯单元测试里跑不起来）。
 */
internal fun chartPaletteFor(dark: Boolean): ChartPalette =
    if (dark) DarkChart else LightChart

/** ①②③ 三张图画布上的字号。**颜色不在这里** —— 它跟着 [ChartPalette] 走。 */
private val X_LABEL = TextStyle(fontSize = 10.sp)
private val Y_LABEL = TextStyle(fontSize = 9.sp)

// ==================================================================== ① 曲线叠加

/**
 * ① **时长对比**（多条独立曲线叠加）—— 看**每个事项自己**在这一周怎么变。
 *
 * ## 为什么不是堆叠（2026-10-18 改，御坂反馈「完全看不到事项在一周的变化情况」）
 *
 * 原来画的是**堆叠**面积图：每条带子从下一条的顶上开始往上累积，总和恒等于 24 小时。
 * 那样读出来的只有「时间结构」——上面那块变厚了，但**下面那些被压扁的带子
 * 各自的走势完全看不出来**，因为它们的高度只表示「占了多少」，而且形状会被邻居拉扯。
 *
 * 现在每条曲线**各自独立**：高度 = 这个事项当天有多少分钟，从 0 起算，互相重叠。
 * 于是「棕色那根周三冲到 19 小时、粉色周末才爬起来」这种话才有意义。
 *
 * ## 两个必须的细节
 *
 * 1. **先画全部填充，再画全部描线**。一条一条「填充+描线」交替画的话，
 *    后画的半透明填充会把先画的那条线糊掉 —— 线是最重要的辨识依据，不能糊。
 * 2. **y 上界跟着数据走**（峰值 × 1.05，至少 6 小时）。固定 24 小时的话，
 *    大部分曲线都会挤在下半截，等于白画。原版也是这么做的（它的上界就是 19 时）。
 *
 * ## 未记录排在最底、最淡
 *
 * 它是背景信息（那天漏了多少），不该和事项曲线抢注意力。
 * 所以不描线、不透明度压到 0.10，而且永远第一个画。
 */
@Composable
fun OverlayArea(
    cols: List<StatsCalc.Col>,
    slices: List<StatsCalc.Slice>,
    onTapColumn: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cols.isEmpty()) return
    val n = cols.size
    val measurer = rememberTextMeasurer()
    val palette = chartPalette()

    // y 上界：峰值留 5% 余量，且至少 6 小时（否则一两条矮曲线会被放大成噪声）
    val peak = slices.filter { !it.isGap }.maxOfOrNull { sl -> sl.vals.maxOrNull() ?: 0 } ?: 0
    val max = maxOf(OVERLAY_MIN_Y, (peak * 1.05f).toInt())

    ChartScaffold(
        columnCount = n,
        onTapColumn = onTapColumn,
        modifier = modifier,
    ) { s ->
        val pw = VW - PAD_L - PAD_R
        val ph = VH - PAD_T - PAD_B
        val step = pw / n
        fun xc(i: Int) = PAD_L + step * i + step / 2f
        fun yv(v: Float) = PAD_T + ph - (v / max.toFloat()).coerceIn(0f, 1f) * ph

        drawGrid(
            grain = StatsCalc.Grain.DAY,
            yMaxMinutes = max,
            measurer = measurer,
            palette = palette,
            s = s,
        )

        // 画序：未记录最底，其余按**峰值降序**（高的先画 → 在底层，矮的压在上面更容易看见）
        val order = slices.sortedWith(
            compareBy<StatsCalc.Slice> { if (it.isGap) 0 else 1 }
                .thenByDescending { it.vals.maxOrNull() ?: 0 }
        )

        // ---------- 第一遍：填充 ----------
        order.forEach { slice ->
            val up = (0 until n).map { StatsCalc.Pt(xc(it), yv(slice.vals.getOrElse(it) { 0 }.toFloat())) }
            val dn = (0 until n).map { StatsCalc.Pt(xc(it), yv(0f)) }
            drawPath(
                path = bandPath(up, dn, s),
                color = toColor(slice.color),
                // 叠加图必须半透明，否则上面那条会把下面的整个盖死。
                // 0.26 是「能看出颜色、又能透出下面」的平衡点；未记录更淡，退成背景。
                alpha = if (slice.isGap) 0.10f else 0.26f,
            )
        }

        // ---------- 第二遍：描线 ----------
        // ★ 单独一遍，绝不和填充交替 —— 见上面注释第 1 条。
        order.forEach { slice ->
            if (slice.isGap) return@forEach
            val up = (0 until n).map { StatsCalc.Pt(xc(it), yv(slice.vals.getOrElse(it) { 0 }.toFloat())) }
            val upC = StatsCalc.smooth(up)
            if (upC.isEmpty()) return@forEach
            val edge = Path()
            edge.moveTo(upC[0].from.x * s, upC[0].from.y * s)
            upC.forEach { c ->
                edge.cubicTo(c.c1.x * s, c.c1.y * s, c.c2.x * s, c.c2.y * s, c.to.x * s, c.to.y * s)
            }
            drawPath(
                path = edge,
                color = toColor(slice.color),
                alpha = 0.92f,
                style = Stroke(width = 1.8f * s),
            )
        }

        for (i in 0 until n) {
            if (StatsCalc.showLabel(i, n)) {
                drawXLabel(
                    measurer = measurer,
                    text = cols[i].label,
                    centerX = xc(i) * s,
                    color = if (cols[i].isToday) palette.today else palette.ax,
                    bold = cols[i].isToday,
                    s = s,
                )
            }
        }
    }
}

/** 叠加图的 y 上界下限：6 小时。太小的上界会把噪声放大成大起大落。 */
private const val OVERLAY_MIN_Y = 360

/**
 * 一条堆叠带子的闭合路径：上边界从左往右，下边界从右往左，合上。
 *
 * ⚠️ 两处容易错：
 * - 下边界那条要**从右往左**（`smoothReversed`），否则首尾接不上，会画出一个蝴蝶结
 * - 第一段用 `moveTo`，接下边界的第一步用 `lineTo`（不是 `moveTo`）——
 *   原型就是靠 `replace('M','L')` 做这件事的
 */
private fun bandPath(up: List<StatsCalc.Pt>, dn: List<StatsCalc.Pt>, s: Float): Path {
    val path = Path()
    val upC = StatsCalc.smooth(up)
    val dnC = StatsCalc.smoothReversed(dn)

    if (upC.isEmpty() || dnC.isEmpty()) {
        // 只有一列 → 没有「趋势」可言，直接铺一个矩形（正常不会走到，纯属兜底）
        val a = up.firstOrNull() ?: return path
        val b = dn.firstOrNull() ?: return path
        path.addRect(
            androidx.compose.ui.geometry.Rect(
                left = a.x * s,
                top = minOf(a.y, b.y) * s,
                right = (a.x + 2f) * s,
                bottom = maxOf(a.y, b.y) * s,
            )
        )
        return path
    }

    path.moveTo(upC[0].from.x * s, upC[0].from.y * s)
    upC.forEach { c -> path.cubicTo(c.c1.x * s, c.c1.y * s, c.c2.x * s, c.c2.y * s, c.to.x * s, c.to.y * s) }
    path.lineTo(dnC[0].from.x * s, dnC[0].from.y * s)
    dnC.forEach { c -> path.cubicTo(c.c1.x * s, c.c1.y * s, c.c2.x * s, c.c2.y * s, c.to.x * s, c.to.y * s) }
    path.close()
    return path
}

// ==================================================================== ② 汇总柱

/**
 * ② **时长汇总**（堆叠柱）—— ★「发现缺漏」的核心图。
 *
 * ## 为什么不含未记录（这是全图最重要的一句）
 *
 * 纵轴固定 `0–24 时`。**柱子矮 = 那天没记全**。
 * 要是把未记录也堆进去，每根柱子都会是满的 24 小时 —— 缺漏就被藏起来了，
 * 而这张图存在的意义就是暴露它。
 *
 * ## 今天那根柱顶上一个小圆点
 *
 * 今天只画到「此刻」，柱子当然矮。点个蓝点是说「矮是正常的，不是漏记」——
 * 不然你每天下午看这张图都会以为自己漏记了半天。
 */
@Composable
fun StackedBar(
    cols: List<StatsCalc.Col>,
    slices: List<StatsCalc.Slice>,
    grain: StatsCalc.Grain,
    onTapColumn: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cols.isEmpty()) return
    val n = cols.size
    val measurer = rememberTextMeasurer()
    val yMax = StatsCalc.yMaxFor(grain).toFloat()
    val palette = chartPalette()

    ChartScaffold(columnCount = n, onTapColumn = onTapColumn, modifier = modifier) { s ->
        val pw = VW - PAD_L - PAD_R
        val ph = VH - PAD_T - PAD_B
        val step = pw / n
        val bw = (step * 0.74f).coerceIn(2.5f, 26f)

        drawGrid(
            grain = grain,
            yMaxMinutes = yMax.toInt(),
            measurer = measurer,
            palette = palette,
            s = s,
        )

        for (i in 0 until n) {
            val col = cols[i]
            if (col.future) continue // 还没到的一天：一根都不画

            val cx = PAD_L + step * i + step / 2f
            var base = PAD_T + ph // 从底往上堆
            slices.forEach { slice ->
                val v = slice.vals.getOrElse(i) { 0 }
                if (v <= 0) return@forEach
                val hgt = v / yMax * ph
                base -= hgt
                drawRect(
                    color = toColor(slice.color),
                    topLeft = Offset((cx - bw / 2f) * s, base * s),
                    size = Size(bw * s, hgt * s),
                    // ⚠️ 原来是 0.88 —— 半透明会让**下面那一段的颜色透上来**
                    //    （截图里粉色柱子上能看到深红的影子），堆叠柱一旦串色就
                    //    读不出「这一段是什么」。柱子必须实心。
                    alpha = 1f,
                )
            }

            if (col.isToday && base < PAD_T + ph - 1f) {
                drawCircle(
                    color = palette.today,
                    radius = 2f * s,
                    center = Offset(cx * s, (base - 4f).coerceAtLeast(PAD_T) * s),
                )
            }

            if (StatsCalc.showLabel(i, n)) {
                drawXLabel(
                    measurer = measurer,
                    text = col.label,
                    centerX = cx * s,
                    color = when {
                        col.isToday -> palette.today
                        col.future -> palette.future
                        else -> palette.ax
                    },
                    bold = col.isToday,
                    s = s,
                )
            }
        }
    }
}

// ==================================================================== ③ 时间分布

/**
 * ③ **时间分布**（堆叠柱·按真实时序）—— 看「作息怎么变的」。
 *
 * ## ★ 每根柱子就是那一天被压扁的 24 小时：最底下是 0 点，最上面是 24 点
 *
 * 睡觉那块颜色落在柱子的哪一段、有多长，一眼就能看出来。
 *
 * ## ★ 绝不合并（这是它跟 ①② 最本质的区别）
 *
 * 早上 9 点那段「打工」和下午 2 点那段，本来就在时间轴的**不同位置**上，
 * 合并了就没法画了。数据来自 [StatsCalc.dayTimeline]，那边一格都不合并。
 *
 * ## 只在周 / 月口径出现
 *
 * 年 / 自 口径里横轴的一列是「一个月」——三十天压不成一根 24 小时的时间轴。
 * 这件事写在 [StatsCalc.chartsFor] 里，**这里不再判一次**。
 */
@Composable
fun TimeDist(
    cols: List<StatsCalc.Col>,
    onTapColumn: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cols.isEmpty()) return
    val n = cols.size
    val measurer = rememberTextMeasurer()
    val max = MINUTES_PER_DAY.toFloat()
    val palette = chartPalette()

    ChartScaffold(columnCount = n, onTapColumn = onTapColumn, modifier = modifier) { s ->
        val pw = VW - PAD_L - PAD_R
        val ph = VH - PAD_T - PAD_B
        val step = pw / n
        val bw = (step * 0.74f).coerceIn(2.5f, 26f)

        drawGrid(
            grain = StatsCalc.Grain.DAY,
            yMaxMinutes = max.toInt(),
            measurer = measurer,
            palette = palette,
            s = s,
        )

        for (i in 0 until n) {
            val col = cols[i]
            if (col.future) continue

            val cx = PAD_L + step * i + step / 2f
            col.timeline.forEach { seg ->
                // y 轴向下：结束分钟在上面、开始分钟在下面
                val yTop = PAD_T + ph - (seg.to / max) * ph
                val yBottom = PAD_T + ph - (seg.from / max) * ph
                drawRect(
                    color = toColor(seg.color),
                    topLeft = Offset((cx - bw / 2f) * s, yTop * s),
                    // 最细也留 0.6 —— 一分钟的段也得看得见，不然「这里有东西」就丢了
                    size = Size(bw * s, (yBottom - yTop).coerceAtLeast(0.6f) * s),
                    alpha = if (seg.isGap) 0.42f else 1f,
                )
            }

            if (StatsCalc.showLabel(i, n)) {
                drawXLabel(
                    measurer = measurer,
                    text = col.label,
                    centerX = cx * s,
                    color = if (col.isToday) palette.today else palette.ax,
                    bold = col.isToday,
                    s = s,
                )
            }
        }
    }
}

// ==================================================================== ④ 甜甜圈

/**
 * ④ **总时长汇总**（甜甜圈）+ 图例。
 *
 * ## 含未记录，灰扇区排**最后**
 *
 * 排最后而不是按大小插进去 —— 它是「没记的部分」，不是一件事，
 * 混在事项中间会让人以为它也是一个事项。
 *
 * ## 图例是**唯一按时长降序**的地方
 *
 * 堆叠图用的是固定色序（按顺序排才不会让每天的颜色换位置），
 * 但饼图这边要的就是「谁最大」。两个口径都在 [StatsCalc] 里写明了。
 */
@Composable
fun Donut(
    slices: List<StatsCalc.Slice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.total }

    if (total <= 0) {
        Text(
            text = "这段时间还没有任何记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 36.dp),
        )
        return
    }

    val ranked = StatsCalc.bySize(slices)
    val measurer = rememberTextMeasurer()
    val palette = chartPalette()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
        ) {
            val s = size.width / 160f
            val r = 54f
            val sw = 20f
            val cx = 80f
            val cy = 80f

            // 底环：一眼看出「还有多少是空的」
            drawCircle(
                color = palette.ring,
                radius = r * s,
                center = Offset(cx * s, cy * s),
                style = Stroke(width = sw * s),
            )

            var start = -90f // SVG 那边是 rotate(-90)，Compose 的 0° 在 3 点钟方向，所以减 90
            ranked.forEach { slice ->
                val sweep = slice.total / total.toFloat() * 360f
                drawArc(
                    color = toColor(slice.color),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset((cx - r) * s, (cy - r) * s),
                    size = Size(2 * r * s, 2 * r * s),
                    style = Stroke(width = sw * s, cap = StrokeCap.Butt),
                )
                start += sweep
            }

            drawCentered(measurer, "总时长", cy - 4f, s, TextStyle(fontSize = 10.sp, color = palette.ax))
            drawCentered(
                measurer,
                DayKey.durationMultiDay(total),
                cy + 12f,
                s,
                TextStyle(fontSize = 14.sp, color = palette.ink, fontWeight = FontWeight.Bold),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ranked.forEach { slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(toColor(slice.color))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = slice.name,
                        style = MaterialTheme.typography.bodySmall,
                        // 未记录那行压淡一点 —— 它不是一个事项
                        color = if (slice.isGap) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = DayKey.durationMultiDayCompact(slice.total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "%.1f%%".format(slice.total * 100f / total),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** 在甜甜圈正中间居中写一行字。 */
private fun DrawScope.drawCentered(
    measurer: TextMeasurer,
    text: String,
    centerY: Float,
    s: Float,
    style: TextStyle,
) {
    val layout = measurer.measure(AnnotatedString(text), style = style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = 80f * s - layout.size.width / 2f,
            y = centerY * s - layout.size.height / 2f,
        ),
    )
}

// ==================================================================== 共用零件

/**
 * 三张柱/面积图的外壳：**画布 + 覆盖在上面的点击热区**。
 *
 * ## 为什么热区是 Compose 的 `Box`，不是画布上的命中测试
 *
 * 原型当年就吃过这个亏（注释还在）：真机上手指点一根 10px 宽的柱子本来就点不准，
 * 而「等高的一排元素」随手就能按到。所以这里排一行**等宽的透明格子**，
 * 跟横轴的每一列一一对应 —— 手指落在哪一格，就是点的那一列。
 *
 * ⚠️ 那一行格子的四边留白**必须**等于 `PAD_L / PAD_R / PAD_T / PAD_B`
 * （用权重表达），否则热区跟柱子会整体错开半格 —— 点这根柱跳出旁边那天的明细。
 *
 * ★ **权重要加起来等于 350（也就是 `VW`），不是等于「左边距 + 列数 + 右边距」。**
 *
 * 这里踩过一次坑（真机验出来的）：横向写成 `Spacer(weight = PAD_L)` + `repeat(n) { weight(1f) }`
 * + `Spacer(weight = PAD_R)`，加起来是 `30 + 7 + 8 = 45` 份 —— 于是左边距占了整行的 30/45，
 * 七个热区被挤到右边 15% 里。症状是**图上点哪都没反应**（只有最右那一小条有反应），
 * 而且不报错、不崩，非常难查。
 *
 * 正确的写法是让分母回到 `VW`：左边距 `PAD_L`、每列 `(VW - PAD_L - PAD_R) / n`、右边距 `PAD_R`。
 * 纵向本来就是这么写的（`12 + 166 + 22 = 200 = VH`），所以纵向一直是对的 ——
 * 这也说明「看着差不多的两段代码，一段对一段错」是真实存在的。
 */
@Composable
private fun ChartScaffold(
    columnCount: Int,
    onTapColumn: (Int) -> Unit,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Float) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(VW / VH)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 靠 aspectRatio 保证两个方向同一个比例，所以一个系数就够
            draw(size.width / VW)
        }

        // 热区：四边留白用权重表达，跟画布上的 padding 严格同比例
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(PAD_T))
            Row(modifier = Modifier.fillMaxWidth().weight(ChartGeom.rowWeight())) {
                Spacer(Modifier.weight(PAD_L))
                val colWeight = ChartGeom.colWeight(columnCount)
                repeat(columnCount) { i ->
                    Box(
                        modifier = Modifier
                            .weight(colWeight)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onTapColumn(i) }
                    )
                }
                Spacer(Modifier.weight(PAD_R))
            }
            Spacer(Modifier.weight(PAD_B))
        }
    }
}

/**
 * 纵轴：刻度线 + 左边的小字。
 *
 * ★ 刻度**不由数据决定**（[StatsCalc.ticksFor] 给的是固定的 0/6/12/18/24 时），
 * 这正是 ② 图「柱子矮 = 漏记」的前提。让刻度自适应的话，每根柱子都会顶到天花板。
 */
private fun DrawScope.drawGrid(
    grain: StatsCalc.Grain,
    yMaxMinutes: Int,
    measurer: TextMeasurer,
    palette: ChartPalette,
    s: Float,
) {
    val ph = VH - PAD_T - PAD_B

    StatsCalc.ticksFor(grain).forEach { tick ->
        val v = StatsCalc.tickMinutes(grain, tick)
        if (v > yMaxMinutes + 1) return@forEach
        val yy = PAD_T + ph - (v.toFloat() / yMaxMinutes) * ph

        drawLine(
            color = palette.grid,
            start = Offset(PAD_L * s, yy * s),
            end = Offset((VW - PAD_R) * s, yy * s),
            strokeWidth = 1f * s,
        )

        val layout = measurer.measure(
            AnnotatedString(StatsCalc.tickLabel(grain, tick)),
            style = Y_LABEL.copy(color = palette.ax),
        )
        // 原型写的是 SVG 的基线（`yy + 3.2`），这里换算成「让文字竖向居中在 yy 上」
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = (PAD_L - 4f) * s - layout.size.width,
                y = yy * s - layout.size.height / 2f,
            ),
        )
    }
}

/** 横轴上的一个标签（居中在那一列下面）。 */
private fun DrawScope.drawXLabel(
    measurer: TextMeasurer,
    text: String,
    centerX: Float,
    color: Color,
    bold: Boolean,
    s: Float,
) {
    val layout = measurer.measure(
        AnnotatedString(text),
        style = X_LABEL.copy(
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
    )
    // ⚠️ 原型这里写的是 SVG 的 `y = H - 7`，那是**基线**、字往上长；
    //    Compose 的 `topLeft` 是**左上角**、字往下长。直接照抄 193 会让标签
    //    下半截被画布裁掉。所以往上挪一个字高（184 ≈ 200 - 16）。
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = centerX - layout.size.width / 2f,
            y = (VH - 16f) * s,
        ),
    )
}

/** 图例里那个小圆点（卡片外部复用）。 */
@Composable
internal fun LegendDot(hex: String, size: Int = 9) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 2))
            .background(toColor(hex))
    )
}

/** 空数据时卡片里的一句话。 */
@Composable
internal fun ChartEmpty(text: String = "这段时间还没有记录") {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 32.dp),
    )
}
