package com.misaka.timelog.domain

import com.misaka.timelog.data.entity.RecordEntity

/** 一天恒定的总分钟数 —— 所有等式的右边。 */
const val MINUTES_PER_DAY = 1440

/**
 * 密铺校验 —— **纯函数，不碰数据库**。
 *
 * ## 为什么单独抽出来当纯函数
 *
 * 密铺是这个 App 的**地基不变式**：同一天的记录按开始时间排好后必须首尾相接，
 * 完整覆盖 `[0, cut]`，一天恒 = 1440 分钟。
 *
 * 而这类错误的特点是 —— **不报错、不崩溃**，只会在几个月后让统计数字对不上。
 * 所以它必须被测试盯死。做成纯函数（输入 List、输出问题清单）的好处是：
 * 测试**不需要数据库、不需要 Android 环境**，纯 JVM 就能跑，秒级出结果。
 */
object Density {

    /**
     * 这一天该铺到哪。
     *
     * ⚠️ 这是**唯一**的判定处 —— 别在别处再写一遍。
     *
     * 注意日期用的是 ISO 格式（`2026-10-14`），它的**字典序恰好等于时间序**，
     * 所以可以直接用字符串比较判断先后。这也是选 ISO 格式存日期的原因。
     */
    fun cutFor(dayKey: String, now: Now): Int = when {
        dayKey == now.dayKey -> now.minuteOfDay   // 今天：只铺到此刻，未来不铺
        dayKey < now.dayKey -> MINUTES_PER_DAY    // 过去：整天过完了，铺满（用户才能点空白补记）
        else -> 0                                  // 未来：一条都不铺
    }

    /**
     * 检查一天的记录是否密铺。
     *
     * @param records 该天的全部记录（顺序随意，内部会排）
     * @param cut     这一天该铺到哪，由 [cutFor] 算出
     * @return 问题清单，**空 = 没问题**
     */
    fun check(records: List<RecordEntity>, cut: Int): List<Problem> {
        // 未来的日子就该是空的
        if (cut == 0) {
            return if (records.isEmpty()) emptyList() else listOf(Problem.ShouldBeEmpty(records.size))
        }

        if (records.isEmpty()) return listOf(Problem.NotStarted(cut))

        val sorted = records.sortedBy { it.startMin }
        val problems = mutableListOf<Problem>()

        if (sorted.first().startMin != 0) {
            problems += Problem.StartsLate(sorted.first().startMin)
        }

        sorted.forEachIndexed { index, cur ->
            // 进行中的那条 endMin 是 null —— 按「铺到 cut」看待
            val curEnd = cur.endMin ?: cut
            val next = sorted.getOrNull(index + 1)

            if (next == null) {
                if (curEnd != cut) problems += Problem.EndsEarly(curEnd, cut)
            } else {
                when {
                    curEnd > next.startMin -> problems += Problem.Overlap(curEnd, next.startMin)
                    curEnd < next.startMin -> problems += Problem.Hole(curEnd, next.startMin)
                }
            }
        }

        return problems
    }

    /**
     * 算出「要补哪些空白段」。
     *
     * 已经密铺的话返回空列表。每个洞给的是一个**半开区间** `[from, to)` ——
     * 用 `IntRange` 表示时意味着 `endInclusive == to - 1`。
     *
     * ⚠️ **所有分支必须统一用 `until`**。这里是曾经踩过的坑：
     * 「一整天完全空白」那一支写成了 `0..cut`（闭区间），于是补出来的空白段
     * 会到第 `cut + 1` 分钟 —— 比一天该有的多一寸，**密铺反而被它自己破坏**。
     * 这个错误不报错、不崩，只会让当天的总时长比 1440 多 1 分钟，
     * 而且只有「一整天一条记录都没有」才触发，极难碰到。测试抓住了它。
     */
    fun gapRanges(records: List<RecordEntity>, cut: Int): List<ClosedRange<Int>> {
        if (cut == 0) return emptyList()

        val sorted = records.sortedBy { it.startMin }
        if (sorted.isEmpty()) return listOf(0 until cut)

        val gaps = mutableListOf<ClosedRange<Int>>()

        if (sorted.first().startMin > 0) {
            gaps += 0 until sorted.first().startMin
        }

        sorted.forEachIndexed { index, cur ->
            val curEnd = cur.endMin ?: cut
            val next = sorted.getOrNull(index + 1)
            val boundary = next?.startMin ?: cut
            if (curEnd < boundary) gaps += curEnd until boundary
        }

        return gaps
    }

    /** 密铺问题的种类。 */
    sealed interface Problem {
        /** 这一天该是空的（未来），却有记录。 */
        data class ShouldBeEmpty(val count: Int) : Problem

        /** 该铺却没开始（缺口从 0 起）。 */
        data class NotStarted(val cut: Int) : Problem

        /** 首条不是从 0 开始。 */
        data class StartsLate(val actualStart: Int) : Problem

        /** 末条没铺到 cut。 */
        data class EndsEarly(val actualEnd: Int, val expectedEnd: Int) : Problem

        /** 两条记录重叠了（前一条的终点越过了后一条的起点）。 */
        data class Overlap(val prevEnd: Int, val nextStart: Int) : Problem

        /** 两条记录之间有洞。 */
        data class Hole(val prevEnd: Int, val nextStart: Int) : Problem
    }
}
