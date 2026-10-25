package com.misaka.timelog

import androidx.compose.ui.graphics.Color
import com.misaka.timelog.ui.stats.chartPaletteFor
import com.misaka.timelog.ui.theme.DarkSurface
import com.misaka.timelog.ui.theme.LightSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 图表配色的自动化防线。
 *
 * ## 为什么需要这个测试
 *
 * 2026-10-17 御坂报「统计里的时间对比度完全是乱的」，根因是配色**写死成浅色、
 * 没跟主题联动**：深色主题下 `GRID = #EDF1F7` 变成几条刺眼白线、
 * `INK = #14161C` 让甜甜圈中间的总时长**几乎看不见**。
 *
 * 这类 bug 的特征是：**不报错、不崩、截图之外看不出来**，只有真机肉眼能发现。
 * 所以把「暗到什么程度」「亮到什么程度」变成数字钉在这里 ——
 * 以后谁改配色，跑一遍测试就知道有没有把对比度改坏。
 *
 * ## 判据用 WCAG 的对比度，不是「我觉得行」
 *
 * 相对亮度（linearized sRGB）→ `(L1+0.05)/(L2+0.05)`。
 * 正文级的可读门槛是 4.5，这里给甜甜圈的总时长数字定 7（它是最重要的一个数）。
 */
class ChartColorsTest {

    // ---------------------------------------------------------------- 工具

    private fun linear(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(c: Color): Float =
        0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    // ---------------------------------------------------------------- 深色

    /**
     * ★ 这条是本次 bug 的直接守卫：网格线是**背景**，不许亮。
     *
     * 深色底下 `#EDF1F7`（近白）的相对亮度约 0.85 —— 那条会直接变红。
     */
    @Test
    fun `深色网格线必须很暗_不能是亮色`() {
        val grid = chartPaletteFor(dark = true).grid
        assertTrue(
            "深色网格线相对亮度必须 < 0.15，实际 ${luminance(grid)}（#EDF1F7 那种近白会是 0.85）",
            luminance(grid) < 0.15f,
        )
    }

    /** 网格线相对卡片背景只亮一档 —— 拉得太开就变成「白线抢戏」。 */
    @Test
    fun `深色网格线相对卡片的对比度必须很低`() {
        val grid = chartPaletteFor(dark = true).grid
        val c = contrast(grid, DarkSurface)
        assertTrue("深色网格线 vs 卡片对比度必须 < 1.8，实际 $c", c < 1.8f)
    }

    /** 网格线必须比轴字暗 —— 否则「格子」比「刻度」还显眼，本末倒置。 */
    @Test
    fun `深色网格线必须比轴字暗`() {
        val p = chartPaletteFor(dark = true)
        assertTrue(
            "网格线亮度(${luminance(p.grid)}) 必须低于轴字亮度(${luminance(p.ax)})",
            luminance(p.grid) < luminance(p.ax),
        )
    }

    /** ★ 甜甜圈中间那个总时长数字 —— 深色下必须反白，不能是近黑。 */
    @Test
    fun `深色主要数字必须高对比`() {
        val ink = chartPaletteFor(dark = true).ink
        val c = contrast(ink, DarkSurface)
        assertTrue("深色主要数字 vs 卡片对比度必须 >= 7，实际 $c（#14161C 那种近黑会是 1.2）", c >= 7f)
    }

    /** 轴字（刻度、横轴标签）至少要能读。 */
    @Test
    fun `深色轴字必须可读`() {
        val ax = chartPaletteFor(dark = true).ax
        val c = contrast(ax, DarkSurface)
        assertTrue("深色轴字 vs 卡片对比度必须 >= 3，实际 $c", c >= 3f)
    }

    /** 底环也不能是白环。 */
    @Test
    fun `深色甜甜圈底环必须很暗`() {
        val ring = chartPaletteFor(dark = true).ring
        assertTrue("深色底环相对亮度必须 < 0.15，实际 ${luminance(ring)}", luminance(ring) < 0.15f)
    }

    // ---------------------------------------------------------------- 浅色

    /** 浅色下网格线同样是背景 —— 在纯白卡片上要几乎看不见。 */
    @Test
    fun `浅色网格线相对白卡片几乎不可见`() {
        val grid = chartPaletteFor(dark = false).grid
        val c = contrast(grid, LightSurface)
        assertTrue("浅色网格线 vs 白卡片对比度必须 < 1.35，实际 $c", c < 1.35f)
    }

    /** 浅色下主要数字沿用的是原来的近黑，不能因为这次改动被顺手改坏。 */
    @Test
    fun `浅色主要数字必须高对比`() {
        val ink = chartPaletteFor(dark = false).ink
        val c = contrast(ink, LightSurface)
        assertTrue("浅色主要数字 vs 白卡片对比度必须 >= 7，实际 $c", c >= 7f)
    }

    /**
     * 浅色轴字：**门槛按原型的实际值定，不是按 WCAG 的 3.0**。
     *
     * 原型挑的 `#98A3B2` 在白卡片上只有 **2.45:1** —— 它本来就是个淡色轴字，
     * 这是设计现状，不是 bug。这次的任务是「修深色」，**不该顺手改浅色外观**。
     * 所以这条只负责防止「有人把它改得更淡」，门槛取 2.2。
     */
    @Test
    fun `浅色轴字不能比原型更淡`() {
        val ax = chartPaletteFor(dark = false).ax
        val c = contrast(ax, LightSurface)
        assertTrue("浅色轴字 vs 白卡片对比度必须 >= 2.2（原型 #98A3B2 是 2.45），实际 $c", c >= 2.2f)
    }

    // ---------------------------------------------------------------- 两套

    /**
     * 两套必须真的不一样。
     *
     * 防的是「加了 ChartPalette 但两份填了同样的值」—— 那种情况下
     * 上面所有断言都能过（因为它拿到的都是同一套），而深色主题实际上一点没修。
     */
    @Test
    fun `两套配色的每个字段都必须不同`() {
        val l = chartPaletteFor(dark = false)
        val d = chartPaletteFor(dark = true)
        assertTrue("ax", l.ax != d.ax)
        assertTrue("grid", l.grid != d.grid)
        assertTrue("today", l.today != d.today)
        assertTrue("future", l.future != d.future)
        assertTrue("ring", l.ring != d.ring)
        assertTrue("ink", l.ink != d.ink)
    }

    /**
     * 深色那套的「亮度极性」必须整体反转：浅色是「深字浅底」，深色是「浅字深底」。
     *
     * 一句话判据：深色的主要文字比深色卡片亮，浅色的主要文字比浅色卡片暗。
     */
    @Test
    fun `明暗极性必须相反`() {
        val inkL = chartPaletteFor(dark = false).ink
        val inkD = chartPaletteFor(dark = true).ink
        assertTrue("浅色：字要比底暗", luminance(inkL) < luminance(LightSurface))
        assertTrue("深色：字要比底亮", luminance(inkD) > luminance(DarkSurface))
    }
}
