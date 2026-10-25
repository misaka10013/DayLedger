package com.misaka.timelog

import com.misaka.timelog.domain.CalendarGrid
import com.misaka.timelog.domain.DayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月历格子怎么摆。
 *
 * ## 为什么这个要单独钉
 *
 * 「10 月 1 日是周四，所以前面补 3 格」偏一格，整张月历就整体错一天，
 * 而且**看起来完全正常** —— 每一格都是合法日期、都点得动、都长得对。
 * 截图看不出来，只有真的跳到某一天才发现「怎么跳到的是昨天」。
 *
 * 所以这里断言的是**坐标级事实**（第几格是哪天），不是「有个格子」。
 */
class CalendarGridTest {

    private val today = "2026-10-17"

    @Test
    fun `一个月恒为四十二格`() {
        // 固定 6 行：翻月时弹窗高度不跳，手正点在日期上时界面不会自己缩走
        for (m in 1..12) {
            assertEquals("2026-$m 格数", 42, CalendarGrid.of(2026, m, today).cells.size)
        }
    }

    @Test
    fun `第一格一定是周一`() {
        for (m in 1..12) {
            val first = CalendarGrid.of(2026, m, today).cells.first()
            assertEquals("2026-$m 第一格应该是周一", "一", DayKey.weekdayCn(first.key))
        }
    }

    @Test
    fun `每一行都以周一起`() {
        val cells = CalendarGrid.of(2026, 10, today).cells
        cells.chunked(7).forEachIndexed { i, row ->
            assertEquals("第 $i 行第一格", "一", DayKey.weekdayCn(row.first().key))
            assertEquals("第 $i 行最后一格", "日", DayKey.weekdayCn(row.last().key))
        }
    }

    @Test
    fun `二零二六年十月一日落在第四格`() {
        // 2026-10-01 是周四 → 前面补周一/周二/周三 3 格 → 它是第 4 格（索引 3）
        val cells = CalendarGrid.of(2026, 10, today).cells
        assertEquals("2026-10-01", cells[3].key)
        assertEquals(1, cells[3].day)
        assertTrue(cells[3].inMonth)
    }

    @Test
    fun `相邻两格恒差一天_没有断也没有重`() {
        val cells = CalendarGrid.of(2026, 3, today).cells
        cells.zipWithNext().forEachIndexed { i, (a, b) ->
            assertEquals("第 $i 格到第 ${i + 1} 格", 1, DayKey.parse(b.key).toEpochDay() - DayKey.parse(a.key).toEpochDay())
        }
    }

    @Test
    fun `前后月补位的格子标成不属于本月`() {
        val cells = CalendarGrid.of(2026, 10, today).cells
        assertFalse("9 月 28 日补在前面，不该算本月", cells.first().inMonth)
        assertTrue("10 月 5 日该算本月", cells.first { it.key == "2026-10-05" }.inMonth)
        // 本月的日子恰好等于这个月的天数
        assertEquals(31, cells.count { it.inMonth })
    }

    @Test
    fun `今天那格被打上标记_而且只有一格`() {
        val cells = CalendarGrid.of(2026, 10, today).cells
        assertEquals(1, cells.count { it.isToday })
        assertTrue(cells.first { it.isToday }.key == today)
    }

    @Test
    fun `未来的日子不可点`() {
        val cells = CalendarGrid.of(2026, 10, today).cells
        assertFalse("今天不该被封", cells.first { it.key == today }.future)
        assertTrue("明天是未来", cells.first { it.key == "2026-10-18" }.future)
        assertFalse("昨天不是未来", cells.first { it.key == "2026-10-16" }.future)
        // 补位到 11 月的那些也是未来 —— 同样不可点
        assertTrue(cells.filter { it.key > today }.all { it.future })
    }

    @Test
    fun `翻到未来的月份时_整月都不可点`() {
        val cells = CalendarGrid.of(2026, 12, today).cells
        assertTrue("12 月整月都在今天之后", cells.all { it.future })
    }

    @Test
    fun `有记录的天带小圆点_没记录的不带`() {
        val marked = setOf("2026-10-01", "2026-10-17")
        val cells = CalendarGrid.of(2026, 10, today, marked).cells
        assertEquals(2, cells.count { it.hasData })
        assertTrue(cells.first { it.key == "2026-10-17" }.hasData)
        assertFalse(cells.first { it.key == "2026-10-02" }.hasData)
    }

    @Test
    fun `上个月跨年`() {
        assertEquals(2025 to 12, CalendarGrid.shift(2026, 1, -1))
        // 往前整整一年：2026-01 的 -12 个月是 2025-01（不是 2025-10，别把「减 12 个月」
        // 和「减 3 个月」记混 —— 这条断言当初就是被自己写错的那一版逼出来的）
        assertEquals(2025 to 1, CalendarGrid.shift(2026, 1, -12))
    }

    @Test
    fun `下个月跨年`() {
        assertEquals(2027 to 1, CalendarGrid.shift(2026, 12, 1))
        assertEquals(2028 to 12, CalendarGrid.shift(2026, 12, 24))
    }

    @Test
    fun `从日期键里取出年月`() {
        assertEquals(2026 to 10, CalendarGrid.ymOf("2026-10-17"))
        assertEquals(2026 to 1, CalendarGrid.ymOf("2026-01-05"))
    }

    @Test
    fun `标题只有一种写法`() {
        assertEquals("2026年10月", CalendarGrid.of(2026, 10, today).title)
        assertEquals("2026年1月", CalendarGrid.of(2026, 1, today).title)
    }

    // ------------------------------------------------------------ 未记录标记（黄点）

    /**
     * ★ `hasData` 和 `hasGap` 必须是**两个独立维度**。
     *
     * 一天完全可能「记了大半天、中间漏了一段」—— 那种天既该亮白点（有记录），
     * 又该亮黄点（有缝）。如果写成 `hasData && hasGap` 或者让后者覆盖前者，
     * 恰恰会把最该提醒的那种天藏起来。
     */
    @Test
    fun `同一天可以既有记录又有未记录`() {
        val m = CalendarGrid.of(
            year = 2026, month = 10, today = today,
            daysWithData = setOf("2026-10-05"),
            daysWithGap = setOf("2026-10-05"),
        )
        val c = m.cells.first { it.key == "2026-10-05" }
        assertTrue("有记录", c.hasData)
        assertTrue("也有未记录", c.hasGap)
    }

    @Test
    fun `只有未记录的天不会亮白点`() {
        val m = CalendarGrid.of(
            year = 2026, month = 10, today = today,
            daysWithData = emptySet(),
            daysWithGap = setOf("2026-10-06"),
        )
        val c = m.cells.first { it.key == "2026-10-06" }
        assertFalse("这天没有任何真实记录", c.hasData)
        assertTrue("但有未记录块", c.hasGap)
    }

    @Test
    fun `不传未记录集合时默认全无`() {
        val m = CalendarGrid.of(2026, 10, today, daysWithData = setOf("2026-10-07"))
        val c = m.cells.first { it.key == "2026-10-07" }
        assertTrue(c.hasData)
        assertFalse("默认不该凭空亮黄点", c.hasGap)
    }
}
