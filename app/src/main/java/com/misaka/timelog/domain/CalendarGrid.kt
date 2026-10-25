package com.misaka.timelog.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历的**格子怎么摆** —— 纯函数，不碰数据库、不碰 Android。
 *
 * ## 为什么要单独一层
 *
 * 「10 月 1 日是周三，所以前面要空 2 格」这种偏移算错一格，
 * 整张月历就错位一天，而且**看起来完全正常**（每一格都是合法日期、都点得动），
 * 只有用户跳到错误的日期时才会发现。
 *
 * 这类错误没法靠截图看出来，只能靠断言钉死 —— 所以算法放这里，
 * UI 一行偏移都不算，照着 [CalMonth.cells] 顺序铺出去就完了。
 */
object CalendarGrid {

    /**
     * 一格。
     *
     * @param key      `'2026-10-01'` —— 点它就是翻到这一天
     * @param day      几号（1..31）
     * @param inMonth  属不属于当前显示的月份（不属于的是前后月补位，画灰）
     * @param future   在今天之后 —— **不可点**（未来既没记录也没法补记）
     * @param hasData  这天有真实记录（非空白）—— 画一个小圆点
     */
    data class Cell(
        val key: String,
        val day: Int,
        val inMonth: Boolean,
        val isToday: Boolean,
        val future: Boolean,
        val hasData: Boolean,
        /**
         * 这一天里**存在「未记录」的空白块**（月历上的小黄点）。
         *
         * 跟 [hasData] 是**两个独立的维度**：一天可以既有记录、中间又有缝，
         * 那种天两个点都会亮。所以别把它写成 `hasData && hasGap` 那种耦合判断。
         */
        val hasGap: Boolean = false,
    )

    /** 一整月。`cells` 恒为 **42 格 = 6 行 × 7 列**，周一起。 */
    data class Month(
        val year: Int,
        val month: Int,
        val cells: List<Cell>,
    ) {
        /** `'2026年10月'` —— 标题只在这里拼，界面别自己格式化。 */
        val title: String get() = "${year}年${month}月"
    }

    /** 一个月恒定 6 行 —— 见 [Month.cells] 的注释。 */
    const val ROWS = 6
    const val COLS = 7
    const val CELLS = ROWS * COLS

    /**
     * 摆出某年某月的 42 格。
     *
     * ## 为什么恒定 42 格、不按需 5 行
     *
     * 因为翻月时格子数变来变去会让弹窗高度跳动，手正点在某个日期上、
     * 界面自己缩一下，就会点错。固定 6 行 = 弹窗高度恒定。
     * 多出来的格子用**前后月的真实日期**补齐（画灰），不是空白 —— 空白格子点了没反应才叫怪。
     *
     * @param daysWithData 「有真实记录」的日期键集合（调用方查库给，这里只管比对）
     */
    fun of(
        year: Int,
        month: Int,
        today: String,
        daysWithData: Set<String> = emptySet(),
        daysWithGap: Set<String> = emptySet(),
    ): Month {
        val ym = YearMonth.of(year, month)
        val first = ym.atDay(1)
        // 周一起：周一的 offset = 0，周日的 offset = 6。LocalDate 的 value 是 1..7（周一=1）
        val lead = first.dayOfWeek.value - 1
        val start = first.minusDays(lead.toLong())

        val cells = (0 until CELLS).map { i ->
            val d: LocalDate = start.plusDays(i.toLong())
            val key = DayKey.of(d)
            Cell(
                key = key,
                day = d.dayOfMonth,
                inMonth = d.monthValue == month && d.year == year,
                isToday = key == today,
                future = key > today,
                hasData = key in daysWithData,
                hasGap = key in daysWithGap,
            )
        }
        return Month(year = year, month = month, cells = cells)
    }

    /** 从 `'2026-10-14'` 取出 `(2026, 10)`。 */
    fun ymOf(key: String): Pair<Int, Int> {
        val d = DayKey.parse(key)
        return d.year to d.monthValue
    }

    /**
     * 年月一起加减（`months` 可以是负数）。
     *
     * ⚠️ 别自己写 `month + n` 之后再 `% 12` —— 负数取模在 Kotlin 里是负的，
     * `YearMonth` 已经处理好了（`2026-01` 往前一个月 = `2025-12`）。
     */
    fun shift(year: Int, month: Int, months: Int): Pair<Int, Int> {
        val ym = YearMonth.of(year, month).plusMonths(months.toLong())
        return ym.year to ym.monthValue
    }

    /** 星期表头，周一起 —— 跟时间线顶部周条是同一套顺序，别弄反。 */
    val WEEK_HEADER = listOf("一", "二", "三", "四", "五", "六", "日")
}
