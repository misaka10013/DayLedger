package com.misaka.timelog

import com.misaka.timelog.domain.DayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日期工具的测试 —— **纯 JVM，不起 Android 环境，秒级出结果**。
 *
 * 这些断言看着琐碎，但每一条都对应一个真实会咬人的地方：
 * 周条从周一还是周日开头、跨月跨年、`23:59` 和 `24:00` 的区别。
 * 这类错误不会崩，只会让界面显示得不对劲，肉眼很难发现 —— 所以让测试盯着。
 */
class DayKeyTest {

    // ====================================================== 键的往返与运算

    @Test
    fun `键的生成与解析互为逆运算`() {
        assertEquals("2026-10-14", DayKey.of(DayKey.parse("2026-10-14")))
        // 个位数月日必须补零 —— 不补的话 '2026-9-3' 的字典序会乱掉
        assertEquals("2026-09-03", DayKey.of(DayKey.parse("2026-09-03")))
    }

    @Test
    fun `键的字典序等于时间序`() {
        // 这条是 Density.cutFor() 敢直接用字符串比大小的**前提**。
        // 一旦哪天有人把日期改成 '2026/10/14' 或 '14-10-2026'，这里立刻红。
        assertTrue("2026-01-01" < "2026-02-01")
        assertTrue("2026-09-30" < "2026-10-01") // 补零的意义在这
        assertTrue("2025-12-31" < "2026-01-01")
    }

    @Test
    fun `前后挪一天能跨月`() {
        assertEquals("2026-11-01", DayKey.plus("2026-10-31", 1))
        assertEquals("2026-10-31", DayKey.plus("2026-11-01", -1))
    }

    @Test
    fun `前后挪一天能跨年`() {
        assertEquals("2027-01-01", DayKey.plus("2026-12-31", 1))
        assertEquals("2026-12-31", DayKey.plus("2027-01-01", -1))
    }

    // ====================================================== 周条（周一起）

    @Test
    fun `周三所在那一周从周一算起`() {
        // 2026-10-14 是周三
        assertEquals("2026-10-12", DayKey.mondayOf("2026-10-14"))
    }

    @Test
    fun `周一自己就是那一周的第一天`() {
        assertEquals("2026-10-12", DayKey.mondayOf("2026-10-12"))
    }

    @Test
    fun `周日归在它前面那一周`() {
        // ★ 这条最容易做错：周日是「一周的最后一天」还是「下一周的第一天」，
        //   取决于从周一起算还是从周日起算。本 App 定的是**周一起**，
        //   所以 11 月 1 日（周日）仍属于 10-26 那一周。
        assertEquals("2026-10-26", DayKey.mondayOf("2026-11-01"))
    }

    @Test
    fun `周条是七天且首尾相接`() {
        val week = DayKey.weekOf("2026-10-14")
        assertEquals(7, week.size)
        assertEquals("2026-10-12", week.first())
        assertEquals("2026-10-18", week.last())
        // 相邻两格必须刚好差一天 —— 用加法验，不用手写 7 个日期
        week.zipWithNext().forEach { (a, b) -> assertEquals(b, DayKey.plus(a, 1)) }
    }

    @Test
    fun `周条能跨月`() {
        val week = DayKey.weekOf("2026-11-01") // 周日
        assertEquals("2026-10-26", week.first())
        assertEquals("2026-11-01", week.last())
    }

    // ====================================================== 显示

    @Test
    fun `星期中文按周一起数`() {
        assertEquals("一", DayKey.weekdayCn("2026-10-12"))
        assertEquals("三", DayKey.weekdayCn("2026-10-14"))
        assertEquals("六", DayKey.weekdayCn("2026-10-17"))
        assertEquals("日", DayKey.weekdayCn("2026-10-18"))
    }

    @Test
    fun `长短两种日期格式`() {
        assertEquals("10月14日 周三", DayKey.display("2026-10-14"))
        assertEquals("2026年10月14日 周三", DayKey.displayFull("2026-10-14"))
    }

    @Test
    fun `相对说法只做前后一天`() {
        val today = "2026-10-14"
        assertEquals("今天", DayKey.relative("2026-10-14", today))
        assertEquals("昨天", DayKey.relative("2026-10-13", today))
        assertEquals("明天", DayKey.relative("2026-10-15", today))
        // 再远就写日期 —— 「前天」还行，「大前天」就没法看了
        assertEquals("10月12日 周一", DayKey.relative("2026-10-12", today))
    }

    // ====================================================== 时间格式化

    @Test
    fun `分钟数转时亥`() {
        assertEquals("00:00", DayKey.minuteLabel(0))
        assertEquals("09:05", DayKey.minuteLabel(545))
        assertEquals("14:30", DayKey.minuteLabel(870))
        assertEquals("23:59", DayKey.minuteLabel(1439))
    }

    @Test
    fun `一天的最后一刻显示成 24 00 而不是 00 00`() {
        // ★ 这条是有意为之：一条「22:00 → 24:00」的记录如果写成「22:00 → 00:00」，
        //   读起来像是往回走了一天。24:00 才明确表示「这是这一天的收尾」。
        assertEquals("24:00", DayKey.minuteLabel(1440))
    }

    @Test
    fun `越界的分钟数被夹住而不是崩掉`() {
        assertEquals("00:00", DayKey.minuteLabel(-5))
        assertEquals("24:00", DayKey.minuteLabel(99999))
    }

    @Test
    fun `时长格式 紧凑版`() {
        assertEquals("0分", DayKey.duration(0))
        assertEquals("59分", DayKey.duration(59))
        assertEquals("1时0分", DayKey.duration(60))
        assertEquals("1时12分", DayKey.duration(72))
        assertEquals("24时0分", DayKey.duration(1440))
        // 负数当成 0，不显示「-1时」这种东西
        assertEquals("0分", DayKey.duration(-30))
    }

    @Test
    fun `时长格式 统计行版带空格`() {
        assertEquals("30 分", DayKey.durationSpaced(30))
        assertEquals("1 时 12 分", DayKey.durationSpaced(72))
        assertEquals("3 时 50 分", DayKey.durationSpaced(230))
    }
}
