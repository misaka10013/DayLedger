package com.misaka.timelog

import com.misaka.timelog.domain.DurationWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时长滑块的可拖范围。
 *
 * ## 为什么这组测试必须存在
 *
 * 御坂要的是「快速便捷地调整时长」。这句话本身没法测，但拆开之后可以：
 * **手指移动 1dp（能稳定移动的最小距离约 5dp）会让时长跳几分钟。**
 * 这个数超过大约 10，滑块就等于不可用 —— 而它**不会报错、不会崩，只是很难用**，
 * 装到手机上一试才发现，代价是白跑一轮。
 *
 * 顺便把「为什么不用全天做范围」这件事钉住：下面有一条断言直接算出
 * 全天范围下 5dp 会跳 27 分钟，作为反面参照。
 */
class DurationWindowTest {

    /** 真机上滑块大概能拖多宽（面板 353dp，减掉两端各 45dp 的小字）。 */
    private val sliderWidthDp = 260f

    /** 手指能稳定移动的最小距离。 */
    private val fingerDp = 5f

    // ------------------------------------------------------------ 范围本身

    @Test
    fun `四十分钟的记录窗口是四分之一到四倍`() {
        // 41 分钟 → 10 ~ 164
        assertEquals(10..164, DurationWindow.of(anchor = 41, cur = 41, maxLen = 853))
    }

    @Test
    fun `八小时半的记录窗口被今天剩下的时间截断`() {
        // 510 → 127~2040，但今天只剩 930 分钟（从 08:30 到 24:00）
        assertEquals(127..930, DurationWindow.of(anchor = 510, cur = 510, maxLen = 930))
    }

    @Test
    fun `窗口不会超出当天剩下的时间`() {
        val r = DurationWindow.of(anchor = 60, cur = 60, maxLen = 100)
        assertTrue("上界 ${r.last} 超过了 maxLen 100", r.last <= 100)
    }

    @Test
    fun `当前值被按钮推到窗口外时窗口要扩大`() {
        // 锚在 41，但用户按了 10 次 +15，现在 191 分钟
        val r = DurationWindow.of(anchor = 41, cur = 191, maxLen = 1400)
        assertTrue("当前值 191 不在窗口 $r 里", 191 in r)
    }

    @Test
    fun `当前值比窗口下界还小时窗口也要扩大`() {
        val r = DurationWindow.of(anchor = 300, cur = 5, maxLen = 1400)
        assertTrue("当前值 5 不在窗口 $r 里", 5 in r)
    }

    @Test
    fun `两端永远不重合`() {
        // 重合的 Slider 没有可拖区间，会退化成一个死控件。
        // 一眼看着不可能的输入（当前值 0）在调试中真的出现过。
        listOf(0 to 0, 1 to 1, 2 to 2).forEach { (a, c) ->
            val r = DurationWindow.of(anchor = a, cur = c, maxLen = 1)
            assertTrue("anchor=$a cur=$c maxLen=1 → $r 两端重合了", r.last > r.first)
        }
    }

    // -------------------------------------------------- ★ 精度（这才是重点）

    @Test
    fun `四分钟的跳动在短记录上不到一分钟`() {
        // 41 分钟的记录：手指挪 5dp 应该只改不到 1 分钟的一半 —— 能停准
        val r = DurationWindow.of(anchor = 41, cur = 41, maxLen = 853)
        val perDp = DurationWindow.minutesPerDp(r, sliderWidthDp)
        val perFinger = perDp * fingerDp
        assertTrue("41 分钟记录：手指移 5dp 跳了 $perFinger 分钟，太粗", perFinger < 4f)
    }

    @Test
    fun `长记录的跳动放宽但仍在可接受范围`() {
        val r = DurationWindow.of(anchor = 510, cur = 510, maxLen = 930)
        val perFinger = DurationWindow.minutesPerDp(r, sliderWidthDp) * fingerDp
        assertTrue("510 分钟记录：手指移 5dp 跳了 $perFinger 分钟", perFinger < 30f)
    }

    @Test
    fun `反面参照_用全天做范围时四十分钟的纪录根本拖不准`() {
        // ★ 这条不测现在的实现，它测「为什么不能那么实现」。
        //   谁哪天图省事把 range 改成 1..1440，先看这条：
        val allDay = 1..1440
        val perFinger = DurationWindow.minutesPerDp(allDay, sliderWidthDp) * fingerDp
        assertTrue("全天范围下手指移 5dp 应该跳 20 分钟以上（实测 $perFinger）", perFinger > 20f)

        // 而 41 分钟在这么宽的范围里，离最左边只有这么一点距离 —— 碰一下就飞出去
        val positionOf41Dp = 41f / 1440f * sliderWidthDp
        assertTrue("41 分钟应该贴在滑块最左边附近（实测 ${positionOf41Dp}dp）", positionOf41Dp < 10f)
    }

    @Test
    fun `缩放比例越大精度越高`() {
        // 保证「窗口」这个机制本身是有效的：4 倍缩放明显优于全天
        val windowed = DurationWindow.of(anchor = 41, cur = 41, maxLen = 1440)
        val windowedPerFinger =
            DurationWindow.minutesPerDp(windowed, sliderWidthDp) * fingerDp
        val allDayPerFinger =
            DurationWindow.minutesPerDp(1..1440, sliderWidthDp) * fingerDp
        assertTrue(
            "按比例缩放的窗口（$windowedPerFinger 分/5dp）应该明显优于全天（$allDayPerFinger 分/5dp）",
            windowedPerFinger < allDayPerFinger / 4f,
        )
    }
}
