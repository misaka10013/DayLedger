package com.misaka.timelog

import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.Now

/**
 * 测试用时钟 —— **固定在某一刻不动**。
 *
 * ## 为什么单元测试里必须有它
 *
 * 密铺逻辑完全依赖「现在几点」：
 * - **今天** → 只铺到此刻
 * - **过去** → 铺满 1440
 * - **未来** → 一条都不铺
 *
 * 如果生产代码里直接写 `System.currentTimeMillis()`，这些分支就**没法测** ——
 * 「今天是 14:30」这个场景你根本复现不出来。第 19 轮那个
 * 「翻到昨天点事项，昨天凭空长出一条 24 小时记录」的 bug 就出在这条路径上。
 */
class FakeClock(var now: Now) : Clock {

    override fun now(): Now = now

    /** 把时间推到今天的第 [minute] 分钟。 */
    fun atMinute(minute: Int): FakeClock = apply {
        now = now.copy(minuteOfDay = minute)
    }

    /** 推移 [minutes] 分钟（不跨天，跨天请自己改 day）。 */
    fun plusMinutes(minutes: Int): FakeClock = apply {
        now = now.copy(minuteOfDay = now.minuteOfDay + minutes)
    }

    /** 换一天。 */
    fun onDay(dayKey: String): FakeClock = apply {
        now = now.copy(dayKey = dayKey)
    }

    companion object {
        /** 固定在某天的某一分钟。 */
        fun at(dayKey: String, minuteOfDay: Int): FakeClock =
            FakeClock(Now(epochMillis = 1_760_000_000_000L, dayKey = dayKey, minuteOfDay = minuteOfDay))
    }
}
