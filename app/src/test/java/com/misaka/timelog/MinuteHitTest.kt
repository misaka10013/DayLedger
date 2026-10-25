package com.misaka.timelog

import com.misaka.timelog.domain.MinuteHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模板色条的点击判定。
 *
 * ## 这一组为什么必须存在
 *
 * 御坂的原话：「十几分钟的模板太细了，我点不到」。
 *
 * 「点不到」有两种，处理方式完全不同：
 * - **视觉太细** → 调大缩放比例（`HOUR_DP`）
 * - **判定不对** → 按矩形命中在段互相重叠时会串到邻居身上
 *
 * 后者才是最坏的：点了有反应、但进的是别的段的面板，看起来像「这 App 乱了」。
 * 所以这里把「按分钟反查」这个判据钉死，尤其是**13 分钟这种超短段**。
 */
class MinuteHitTest {

    private data class Span(val name: String, val start: Int, val end: Int)

    /** 0.5 dp/分钟（= 30dp/小时，模板页现在的缩放）。 */
    private val dpPerMin = 0.5f

    private fun minuteAt(yDp: Float) = MinuteHit.minuteAt(yDp, dpPerMin)

    private fun pick(spans: List<Span>, minute: Int): Span? =
        MinuteHit.pick(spans, minute, { it.start }, { it.end })

    // ------------------------------------------------------------ 坐标换算

    @Test
    fun `色条顶端是第 0 分钟`() {
        assertEquals(0, minuteAt(0f))
    }

    @Test
    fun `半小时的位置是第 30 分钟`() {
        // 0.5 dp/分钟 → 30 分钟在 15dp 处
        assertEquals(30, minuteAt(15f))
    }

    @Test
    fun `一整天的位置被夹在 23时59分_不会溢出成 1440`() {
        // 24 小时 = 720dp。1440 分钟是「不存在的第 1440 分钟」，必须夹住，
        // 否则后面 `firstOrNull { it.startMin <= 1440 && it.endMin > 1440 }` 永远不命中
        assertEquals(1439, minuteAt(720f))
        assertEquals(1439, minuteAt(9999f))
    }

    @Test
    fun `负坐标不炸也不返回负数`() {
        assertEquals(0, minuteAt(-50f))
    }

    @Test
    fun `缩放为 0 时不要除零崩掉`() {
        assertEquals(0, MinuteHit.minuteAt(100f, 0f))
    }

    // ------------------------------------------------------------ 命中判定

    /** ★ 这一条就是御坂反馈的那个 bug 的回归测试。 */
    @Test
    fun `十三分钟的短段点得中_而且不会串到邻居身上`() {
        // 真库里的样子：13:51 → 14:04 那段只有 13 分钟，被夹在两条长段之间
        val segs = listOf(
            Span("打工", 540, 831),
            Span("短段", 831, 844),
            Span("打工", 844, 1080),
        )
        // 13:51 = 831 分钟，13:57 = 837 —— 这个位置在视觉上只有 5dp 的自然高度
        assertEquals("段起点必须命中短段", "短段", pick(segs, 831)?.name)
        assertEquals("段中间必须命中短段", "短段", pick(segs, 837)?.name)
        assertEquals("段末尾前一分钟仍属于短段", "短段", pick(segs, 843)?.name)
        // 边界：844 已经属于下一段（左闭右开）
        assertEquals("段的结束时刻归下一段", "打工", pick(segs, 844)?.name)
        assertEquals("段的开始前一分钟归上一段", "打工", pick(segs, 830)?.name)
    }

    @Test
    fun `每段都点得中_整条色条没有落空的分钟`() {
        // 一天里 13 段，逐个分钟扫一遍：不允许任何一分钟返回 null
        val segs = mutableListOf<Span>()
        var t = 0
        listOf(510, 30, 180, 103, 257, 30, 28, 50, 36, 156, 90, 120, 24).forEachIndexed { i, len ->
            if (t + len > 1440) return@forEachIndexed
            segs += Span("s$i", t, t + len)
            t += len
        }
        // 补齐到 1440，保证首尾相接
        if (t < 1440) segs += Span("s-last", t, 1440)

        for (m in 0 until 1440) {
            val hit = pick(segs, m)
            requireNotNull(hit) { "第 $m 分钟没有命中任何段 —— 点击会石沉大海" }
        }
    }

    @Test
    fun `两段交界处只会命中一段`() {
        val segs = listOf(Span("A", 0, 600), Span("B", 600, 1200))
        assertEquals("A", pick(segs, 599)?.name)
        assertEquals("B", pick(segs, 600)?.name)
        // 24:00 那一刻不该同时命中两段（左闭右开的理由）
        assertEquals("B", pick(segs, 1200 - 1)?.name)
    }

    @Test
    fun `空列表返回 null 而不是崩`() {
        assertNull(pick(emptyList(), 100))
        assertNull(MinuteHit.pick(emptyList<Span>(), 0, { it.start }, { it.end }))
    }

    @Test
    fun `数据有缝时取最近的那段_不让点击石沉大海`() {
        // 理论上模板必须首尾相接，但真出了缝时宁可点错也不能没反应
        val segs = listOf(Span("A", 0, 100), Span("B", 200, 300))
        // 缝是 [100, 200)。第 110 分钟离 A 只差 10 分钟、离 B 还差 90 → 判给 A
        assertEquals("A", pick(segs, 110)?.name)
        // 第 190 分钟离 A 差 90、离 B 只差 10 → 判给 B
        assertEquals("B", pick(segs, 190)?.name)
        // 正中间（150）两边等距，判给谁都行，但**不能返回 null**
        requireNotNull(pick(segs, 150)) { "缝中间也不能点空" }
    }

    @Test
    fun `超短段在最顶上和最底下也点得中`() {
        val segs = listOf(Span("开头", 0, 5), Span("中间", 5, 1435), Span("结尾", 1435, 1440))
        assertEquals("开头", pick(segs, 0)?.name)
        assertEquals("开头", pick(segs, 4)?.name)
        assertEquals("结尾", pick(segs, 1439)?.name)
    }
}
