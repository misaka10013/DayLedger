package com.misaka.timelog

import androidx.compose.ui.graphics.Color
import com.misaka.timelog.domain.ColorSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 调色板的自动化防线。
 *
 * ## 为什么这几条值得写
 *
 * 「分类 → 色系，事项 → 色系内渐变」这个模型**坏了完全不报错**：
 * 轻则两个事项撞色、统计图分不出来，重则 12 个分类色挤成一团 ——
 * 而症状只有「看图觉得糊」，肉眼很难定位到是哪条规则错了。
 * 所以把「色相均匀」「档位单调」「同组不重色」变成数字钉在这里。
 *
 * 另外 `assignSeries` 是**迁移时跑一次就再也不跑的代码** ——
 * 它错一次，用户的分类颜色就永久错位了。这种「一次性代码」最该测。
 */
class ColorSeriesTest {

    // ---------------------------------------------------------------- 工具

    private fun color(hex: String): Color =
        Color(hex.removePrefix("#").toLong(16) or 0xFF000000L)

    private fun rgba(hex: String): Triple<Int, Int, Int> {
        val v = hex.removePrefix("#").toInt(16)
        return Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    private fun linear(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(hex: String): Float {
        val (r, g, b) = rgba(hex)
        return 0.2126f * linear(r / 255f) + 0.7152f * linear(g / 255f) + 0.0722f * linear(b / 255f)
    }

    // ---------------------------------------------------------------- 色系本身

    /** ★ 12 个色系必须真的均匀 —— 相邻色相差 30°，这是「分类能分开」的唯一保障。 */
    @Test
    fun `十二个色系的色相必须均匀三十度`() {
        val hues = (0 until ColorSeries.SERIES).map { ColorSeries.seriesHue(it) }
        assertEquals("色系数量", 12, hues.size)
        for (i in 0 until ColorSeries.SERIES) {
            val next = hues[(i + 1) % ColorSeries.SERIES]
            val d = if (next > hues[i]) next - hues[i] else 360f - hues[i] + next
            assertEquals("第 $i 系到第 ${(i + 1) % ColorSeries.SERIES} 系的间隔", 30f, d, 0.01f)
        }
    }

    @Test
    fun `十二个分类主色必须互不相同`() {
        val cols = (0 until ColorSeries.SERIES).map { ColorSeries.categoryColor(it) }
        assertEquals("主色不能有重复", cols.size, cols.toSet().size)
    }

    @Test
    fun `越界的色系序号会绕回来`() {
        assertEquals(ColorSeries.seriesHue(0), ColorSeries.seriesHue(ColorSeries.SERIES), 0.01f)
        assertEquals(ColorSeries.seriesHue(1), ColorSeries.seriesHue(ColorSeries.SERIES + 1), 0.01f)
    }

    // ---------------------------------------------------------------- 渐变档

    @Test
    fun `每个色系必须有八档且互不相同`() {
        for (s in 0 until ColorSeries.SERIES) {
            val sh = ColorSeries.shades(s)
            assertEquals("第 $s 系档数", ColorSeries.SHADES, sh.size)
            assertEquals("第 $s 系有重复档：$sh", sh.size, sh.toSet().size)
        }
    }

    /**
     * ★ 8 档必须**严格单调变暗**。
     *
     * 这条盯的是「同分类事项能不能分辨」：如果两档亮度接近，
     * 统计图里相邻两块就糊成一块 —— 那等于白做。
     * 这里要求相邻两档的相对亮度**至少差 8%**（相对值，不是差值）。
     */
    @Test
    fun `八档渐变必须严格单调变暗且档间可分辨`() {
        for (s in 0 until ColorSeries.SERIES) {
            val sh = ColorSeries.shades(s)
            for (k in 0 until sh.size - 1) {
                val a = luminance(sh[k])
                val b = luminance(sh[k + 1])
                assertTrue(
                    "第 $s 系：档 $k(${sh[k]}) 亮度 ${a} 必须高于档 ${k + 1}(${sh[k + 1]}) 亮度 $b",
                    a > b,
                )
                val rel = (a - b) / a
                assertTrue(
                    "第 $s 系：档 $k → ${k + 1} 只差 ${"%.3f".format(rel)}，至少要有 0.08 才分得出来",
                    rel >= 0.08f,
                )
            }
        }
    }

    /** 12 个色系 × 8 档 = 96 个颜色，一个都不许撞。 */
    @Test
    fun `所有色系的全部档位互不相同`() {
        val all = (0 until ColorSeries.SERIES).flatMap { ColorSeries.shades(it) } +
            (0 until ColorSeries.SERIES).map { ColorSeries.categoryColor(it) }
        assertEquals("96 档 + 12 主色 里出现了重复颜色", all.size, all.toSet().size)
    }

    // ---------------------------------------------------------------- 事项取色

    /** 同一个分类下 8 个事项，颜色必须两两不同 —— 这就是御坂报的那个痛点的判据。 */
    @Test
    fun `同分类下八个事项的颜色必须互不相同`() {
        for (s in 0 until ColorSeries.SERIES) {
            val catHex = ColorSeries.categoryColor(s)
            val cols = (0 until ColorSeries.SHADES).map { ColorSeries.itemColor(catHex, it) }
            assertEquals("第 $s 系：同分类 8 个事项撞色了", cols.size, cols.toSet().size)
        }
    }

    /** 事项色必须留在**自己分类的色系**里（色相偏移不超过 ±4°）—— 否则「红类」里冒出个绿的。 */
    @Test
    fun `事项色必须留在所属色系内`() {
        for (s in 0 until ColorSeries.SERIES) {
            val catHex = ColorSeries.categoryColor(s)
            val catHue = ColorSeries.seriesHue(s)
            (0 until ColorSeries.SHADES).forEach { k ->
                val itemHex = ColorSeries.itemColor(catHex, k)
                val h = ColorSeries.hueOf(itemHex)
                assertNotNull("第 $s 系第 $k 档色相解析失败：$itemHex", h)
                val d = ColorSeries.hueDistance(h!!, catHue)
                // 低饱和度的浅色档色相会有点漂，容差给 8°
                assertTrue(
                    "第 $s 系第 $k 档（$itemHex）色相 $h 偏离本系 $catHue 有 ${"%.1f".format(d)}°",
                    d <= 8f,
                )
            }
        }
    }

    /** 超过 8 个事项时循环回第 0 档，不能崩、不能越界。 */
    @Test
    fun `事项数超过八会循环`() {
        val catHex = ColorSeries.categoryColor(3)
        assertEquals(ColorSeries.itemColor(catHex, 0), ColorSeries.itemColor(catHex, ColorSeries.SHADES))
        assertEquals(ColorSeries.itemColor(catHex, 1), ColorSeries.itemColor(catHex, ColorSeries.SHADES + 1))
    }

    /** 分类色被用户改成别的色系后，事项要跟着新色系走 —— 这就是「分类应用色系」的意思。 */
    @Test
    fun `换分类色系后事项颜色跟着变`() {
        val oldCat = ColorSeries.categoryColor(0)
        val newCat = ColorSeries.categoryColor(8)
        assertTrue(
            "换色系后第一个事项的颜色应该变",
            ColorSeries.itemColor(oldCat, 0) != ColorSeries.itemColor(newCat, 0),
        )
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析带井号和不带井号都能认`() {
        assertEquals(0xFF0000, ColorSeries.parseHex("#FF0000"))
        assertEquals(0xFF0000, ColorSeries.parseHex("FF0000"))
        assertEquals(0xFF0000, ColorSeries.parseHex("#FFFF0000"))
        assertNull(ColorSeries.parseHex(""))
        assertNull(ColorSeries.parseHex("#XYZ"))
        assertNull(ColorSeries.parseHex("#12345"))
    }

    @Test
    fun `纯灰没有色相_归到第零系`() {
        assertEquals(0f, ColorSeries.hueOf("#808080")!!, 0.01f)
        assertEquals(0, ColorSeries.nearestSeries("#808080"))
    }

    @Test
    fun `标准色的色相要算对`() {
        assertEquals(0f, ColorSeries.hueOf("#FF0000")!!, 0.5f)
        assertEquals(120f, ColorSeries.hueOf("#00FF00")!!, 0.5f)
        assertEquals(240f, ColorSeries.hueOf("#0000FF")!!, 0.5f)
        assertEquals(60f, ColorSeries.hueOf("#FFFF00")!!, 0.5f)
    }

    // ---------------------------------------------------------------- 分配

    /**
     * ★ 御坂现有 10 个分类的真实颜色 —— 迁移分配必须「尽量不动」。
     *
     * 这是这次改动里唯一一处**会永久改变用户界面**的地方，
     * 而且是在数据库迁移里跑一次就再也不跑的代码。所以按真实数据钉死。
     */
    @Test
    fun `现有十个分类的分配必须尽量保持原色相`() {
        // 按 position 顺序（真库里的顺序）
        val existing = listOf(
            "#7B3FC4", // 技能成长
            "#DE2E63", // 精神输入
            "#DC3545", // 动手输出
            "#F062A0", // 恋爱相伴
            "#C77DBB", // 人际交往
            "#8A9A3C", // 赚钱营生
            "#E8A33D", // 生活必需
            "#B7D96A", // 放松娱乐
            "#6C7AE0", // 生理休息
            "#7CB342", // 杂事
        )
        val got = ColorSeries.assignSeries(existing)

        assertEquals("分类数", 10, got.size)
        assertEquals("10 个分类必须分到 10 个不同色系", 10, got.toSet().size)

        // 逐个核对「偏移了多少度」—— 7 个应该基本不动，2 个被让位
        val names = listOf(
            "技能成长", "精神输入", "动手输出", "恋爱相伴", "人际交往",
            "赚钱营生", "生活必需", "放松娱乐", "生理休息", "杂事",
        )
        val shifts = existing.indices.map { i ->
            ColorSeries.hueDistance(ColorSeries.hueOf(existing[i])!!, ColorSeries.seriesHue(got[i]))
        }

        // 至少 7 个分类的色相偏移不超过 25°（"几乎不变/接近"）
        val closeCount = shifts.count { it <= 25f }
        assertTrue(
            "色相偏移明细：" + names.indices.joinToString { "${names[it]}=${"%.0f".format(shifts[it])}°" },
            closeCount >= 7,
        )
    }

    @Test
    fun `分类数超过色系数时不崩且每个都有值`() {
        val many = (0 until 20).map { "#%06X".format(0x101010 * (it + 1)) }
        val got = ColorSeries.assignSeries(many)
        assertEquals(20, got.size)
        assertTrue("序号必须都在 0..SERIES-1", got.all { it in 0 until ColorSeries.SERIES })
    }

    @Test
    fun `空列表安全`() {
        assertTrue(ColorSeries.assignSeries(emptyList()).isEmpty())
    }

    @Test
    fun `识别不了的色值也能分配`() {
        val got = ColorSeries.assignSeries(listOf("", "乱写"))
        assertEquals(2, got.size)
        assertEquals("两个都识别不了 → 分到不同槽", 2, got.toSet().size)
    }
}
