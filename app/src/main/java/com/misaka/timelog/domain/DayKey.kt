package com.misaka.timelog.domain

import java.time.LocalDate

/**
 * 日期的「键」与它的各种显示形式 —— **纯函数，不碰数据库、不碰 Android**。
 *
 * ## 为什么日期要有「键」这个说法
 *
 * 全库的日期只有一个规范形式：`'2026-10-14'`（ISO，见 [Clock.Now.dayKey]）。
 * 这个形式有个很好的性质 —— **字典序恰好等于时间序**。
 *
 * 于是「这一天在不在那一天之前」这种判断可以直接用字符串比大小，
 * 不需要解析成日期对象，也不用担心时区。`Density.cutFor()` 就是靠这一点
 * 一行判断出「今天 / 过去 / 未来」的。
 *
 * 所有**显示用**的中文串（「10月14日 周三」）都从键推出来，反过来不成立 ——
 * 界面上显示成什么样，一个字都不会写回数据库。
 *
 * ## 都在这里做，是为了「一处改、处处对」
 *
 * 时间是「1时12分」还是「1 时 12 分」、一天的第 1440 分钟显示成「24:00」还是「00:00」——
 * 这类口径如果散在各个界面里，迟早会出现「列表里写 23:59 卡片上写 24:00」。
 */
object DayKey {

    /**
     * 星期几的中文单字，**周一起**。
     *
     * ⚠️ 索引是 `DayOfWeek.value - 1` —— `java.time` 里周一是 1、周日是 7。
     * 别跟 `java.util.Calendar` 那套（周日 = 0）混了，两套的偏移量不一样。
     */
    private val WEEKDAY_CN = listOf("一", "二", "三", "四", "五", "六", "日")

    // ------------------------------------------------------------ 键的运算

    /** `LocalDate` → `'2026-10-14'`。 */
    fun of(date: LocalDate): String =
        "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    /** `'2026-10-14'` → `LocalDate`。 */
    fun parse(key: String): LocalDate = LocalDate.parse(key)

    /** 今天。⚠️ 生产代码里请用 [Clock] 取，别直接用它 —— 这是给测试和边界兜底留的。 */
    fun today(): String = of(LocalDate.now())

    /** 从时钟取今天的键（和其它地方口径一致）。 */
    fun today(clock: Clock): String = clock.now().dayKey

    /** 前后挪几天。`plus(key, -1)` = 昨天。跨月跨年由 `java.time` 负责，不用自己算。 */
    fun plus(key: String, days: Int): String = of(parse(key).plusDays(days.toLong()))

    /** 那一天所在那一周的**周一**。周条的第一格就是它。 */
    fun mondayOf(key: String): String {
        val date = parse(key)
        return of(date.minusDays((date.dayOfWeek.value - 1).toLong()))
    }

    /** 那一天所在那一周的 7 个键，周一起、周日尾。周条直接照着它渲染。 */
    fun weekOf(key: String): List<String> {
        val monday = parse(mondayOf(key))
        return (0 until 7).map { of(monday.plusDays(it.toLong())) }
    }

    /**
     * 这一天在「星期位掩码」里占哪一位 —— **bit0 = 周一** … **bit6 = 周日**。
     *
     * 模板表用这个掩码记「指派给哪几天」（`templates.weekdaysMask`）。
     * `java.time` 的 `dayOfWeek.value` 是 `1=周一 … 7=周日`，正好减一就是位号。
     *
     * ⚠️ 用位掩码而不是存一串日期，是因为判断只有一句 `mask and bit != 0`；
     * 而且它跟「今天是周几」这件事的口径**只有这一个定义处**（见 [coversWeekday]）。
     */
    fun weekdayBit(key: String): Int = 1 shl (parse(key).dayOfWeek.value - 1)

    /**
     * 这一天被掩码指派到了吗。
     *
     * ★ 模板「今天该不该跑」的唯一判据 —— 别在别处再写一遍
     * （原型里这句散在两个地方，读起来像互相矛盾）。
     */
    fun coversWeekday(mask: Int, key: String): Boolean = (mask and weekdayBit(key)) != 0

    // ------------------------------------------------------------ 显示

    fun weekdayCn(key: String): String = WEEKDAY_CN[parse(key).dayOfWeek.value - 1]

    /** `'10月14日 周三'`。 */
    fun display(key: String): String {
        val d = parse(key)
        return "${d.monthValue}月${d.dayOfMonth}日 周${weekdayCn(key)}"
    }

    /** `'2026年10月14日 周三'`。底部状态条上写全一点，免得翻到别的日子看不出年份。 */
    fun displayFull(key: String): String {
        val d = parse(key)
        return "${d.year}年${d.monthValue}月${d.dayOfMonth}日 周${weekdayCn(key)}"
    }

    /**
     * 相对说法：「今天 / 昨天 / 明天」，再远就老老实实写日期。
     *
     * 只做前后一天 —— 再往前说「上周三」既有歧义（哪个上周），
     * 也不如直接写日期清楚。
     */
    fun relative(key: String, today: String): String = when (key) {
        today -> "今天"
        plus(today, -1) -> "昨天"
        plus(today, 1) -> "明天"
        else -> display(key)
    }

    // ------------------------------------------------------------ 时间格式化

    /**
     * 当天第几分钟 → `'14:30'`。
     *
     * ⚠️ 上界是 **1440**（一天的最后一刻），它显示成 `'24:00'` 而不是 `'00:00'`。
     * 这是刻意的：`'24:00'` 明确表示「这是这一天的结尾」，
     * 而 `'00:00'` 会让人以为是第二天的开头 —— 时间线上一条「22:00 → 24:00」的记录
     * 写成「22:00 → 00:00」是说不通的。
     *
     * 超出范围一律夹住，不抛异常：显示层不值得为一个越界值把界面搞崩。
     */
    fun minuteLabel(min: Int): String {
        val m = min.coerceIn(0, MINUTES_PER_DAY)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 时长 → `'1时12分'`（卡片上那个，紧凑些）。 */
    fun duration(min: Int): String {
        val m = if (min < 0) 0 else min
        val h = m / 60
        return (if (h > 0) "${h}时" else "") + "${m % 60}分"
    }

    /**
     * 时长 → `'1 时 12 分'`（顶部统计行那个，带空格）。
     *
     * 跟 [duration] 两种写法并存是**跟原型对齐**的：统计行是「已记录 3 时 50 分」，
     * 卡片右上角是「3时50分」。改的话两个一起改，别只改一处。
     */
    fun durationSpaced(min: Int): String {
        val m = if (min < 0) 0 else min
        val h = m / 60
        return (if (h > 0) "$h 时 " else "") + "${m % 60} 分"
    }

    /**
     * 时长 → `'3 天 4 时'` / `'7 时 25 分'` / `'40 分'` —— **统计的汇总行和点列明细用**。
     *
     * 为什么要单独一个：统计里动不动就是几十小时（一年口径的一列 ≈ 上百小时），
     * 只写到「时」的话会得到「745 时 30 分」这种读不出来的数字。
     */
    fun durationMultiDay(min: Int): String {
        val m = if (min < 0) 0 else min
        val d = m / MINUTES_PER_DAY
        val h = m % MINUTES_PER_DAY / 60
        return when {
            d > 0 -> "$d 天 $h 时"
            h > 0 -> "$h 时 ${m % 60} 分"
            else -> "${m % 60} 分"
        }
    }

    /**
     * 同上但紧凑：`'3天4时5分'` —— 明细列表每一行用它。
     *
     * 那里一行要挤下「色点 + 名字 + 时长 + 百分比」，带空格就换行了。
     */
    fun durationMultiDayCompact(min: Int): String {
        val m = if (min < 0) 0 else min
        val d = m / MINUTES_PER_DAY
        val h = m % MINUTES_PER_DAY / 60
        return when {
            d > 0 -> "${d}天${h}时${m % 60}分"
            h > 0 -> "${h}时${m % 60}分"
            else -> "${m % 60}分"
        }
    }
}
