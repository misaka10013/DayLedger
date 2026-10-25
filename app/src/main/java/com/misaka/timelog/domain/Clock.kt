package com.misaka.timelog.domain

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「现在」的一个快照。
 *
 * ⚠️ **三样东西必须来自同一个瞬间**：如果日期和分钟数是分别去问系统时钟的，
 * 恰好卡在午夜那一秒就可能取到「今天是 14 号，现在是 23:59」这种自相矛盾的结果。
 * 所以一次取好、整体传递。
 */
data class Now(
    val epochMillis: Long,
    /** `'2026-10-14'`（ISO 格式）。 */
    val dayKey: String,
    /** 当天第几分钟，`0..1439`。 */
    val minuteOfDay: Int,
)

/**
 * 时钟 —— ★ **必须可注入**。
 *
 * ## 为什么这件事在 M1 里是硬要求
 *
 * 密铺逻辑（「这一天该铺到哪」）**完全依赖现在几点**，而它偏偏又是：
 * - **今天** → 只铺到此刻
 * - **过去** → 铺满 1440
 * - **未来** → 一条都不铺
 *
 * 如果代码里直接写 `System.currentTimeMillis()`，单元测试就**没法固定场景** ——
 * 「今天 14:30 打开 App」这种情况根本复现不了，而它恰恰是最容易出 bug 的那条路径
 * （第 19 轮那个「翻到昨天点事项，昨天长出一条 24 小时记录」的 bug 就出在这里）。
 *
 * 所以：生产用 [SystemClock]，测试用 `FixedClock`。
 */
interface Clock {
    fun now(): Now
}

/**
 * 生产环境的时钟：读系统时间。
 *
 * `java.time` 在 API 26 起原生可用，而本工程 `minSdk = 26`，所以不需要 desugaring。
 */
class SystemClock(private val zone: ZoneId = ZoneId.systemDefault()) : Clock {

    override fun now(): Now {
        val zoned = ZonedDateTime.now(zone)
        return Now(
            epochMillis = zoned.toInstant().toEpochMilli(),
            // LocalDate.toString() 给出的就是 ISO 的 '2026-10-14'
            dayKey = zoned.toLocalDate().toString(),
            minuteOfDay = zoned.hour * 60 + zoned.minute,
        )
    }
}
