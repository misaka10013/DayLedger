package com.misaka.timelog

import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.domain.Density
import com.misaka.timelog.domain.MINUTES_PER_DAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密铺校验的测试 —— **纯 JVM，不碰数据库、不需要安卓环境**，秒级跑完。
 *
 * ## 为什么值得为它单写一个文件
 *
 * 密铺是这个 App 的地基不变式（一天恒 = 1440 分钟）。这类错误的特点是
 * **不报错、不崩溃**，只会在几个月后让统计数字对不上 —— 到那时已经无法回溯。
 * 所以它必须被测试盯死，而且测试要能**高频跑**（每次改代码都跑）。
 *
 * 做成纯函数（输入 List、输出问题清单）就是为了这个：去掉数据库这个重家伙，
 * 跑一次是毫秒级的事。
 */
class DensityTest {

    private val today = "2026-10-14"
    private val yesterday = "2026-10-13"
    private val tomorrow = "2026-10-15"

    private fun rec(
        start: Int,
        end: Int?,
        source: RecordSource = RecordSource.MANUAL,
        day: String = today,
        id: String = "r-$start-$end",
    ) = RecordEntity(
        id = id,
        day = day,
        startMin = start,
        endMin = end,
        itemId = if (source == RecordSource.GAP) null else "item-1",
        categoryIdSnap = if (source == RecordSource.GAP) null else "cat-1",
        source = source,
        chainId = null,
        noTemplate = false,
        note = "",
        updatedAt = 0L,
        deleted = false,
    )

    private fun gap(start: Int, end: Int, day: String = today) = rec(start, end, RecordSource.GAP, day)

    private fun now(day: String, minute: Int) = FakeClock.at(day, minute).now()

    // ============================================================== cutFor

    @Test
    fun `今天只铺到此刻`() {
        assertEquals(870, Density.cutFor(today, now(today, 870)))
        assertEquals(0, Density.cutFor(today, now(today, 0)))
        assertEquals(1439, Density.cutFor(today, now(today, 1439)))
    }

    @Test
    fun `过去铺满一整天`() {
        assertEquals(MINUTES_PER_DAY, Density.cutFor(yesterday, now(today, 870)))
    }

    @Test
    fun `未来一条都不铺`() {
        assertEquals(0, Density.cutFor(tomorrow, now(today, 870)))
    }

    // =============================================================== check

    @Test
    fun `密铺的一天没有问题`() {
        val day = listOf(
            rec(0, 300),
            rec(300, 870),
        )
        assertTrue(Density.check(day, 870).isEmpty())
    }

    @Test
    fun `进行中的那条按铺到 cut 看待`() {
        // 最后一条 endMin = null = 正在记 → 它铺到此刻
        val day = listOf(
            rec(0, 300),
            rec(300, null),
        )
        assertTrue(Density.check(day, 870).isEmpty())
    }

    @Test
    fun `空白也是一等公民_参与密铺`() {
        val day = listOf(
            rec(0, 300),
            gap(300, 500),
            rec(500, null),
        )
        assertTrue(Density.check(day, 870).isEmpty())
    }

    @Test
    fun `首条不从 0 开始算问题`() {
        val problems = Density.check(listOf(rec(30, 870)), 870)
        assertEquals(1, problems.size)
        assertTrue(problems.first() is Density.Problem.StartsLate)
    }

    @Test
    fun `末条没铺到 cut 算问题`() {
        val problems = Density.check(listOf(rec(0, 600)), 870)
        assertEquals(1, problems.size)
        val p = problems.first() as Density.Problem.EndsEarly
        assertEquals(600, p.actualEnd)
        assertEquals(870, p.expectedEnd)
    }

    @Test
    fun `重叠被认出来`() {
        val problems = Density.check(listOf(rec(0, 500), rec(400, 870)), 870)
        assertTrue(problems.any { it is Density.Problem.Overlap })
    }

    @Test
    fun `洞被认出来`() {
        val problems = Density.check(listOf(rec(0, 300), rec(500, 870)), 870)
        assertTrue(problems.any { it is Density.Problem.Hole })
    }

    @Test
    fun `该空的未来却有记录算问题`() {
        val problems = Density.check(listOf(rec(0, 100, day = tomorrow)), 0)
        assertTrue(problems.any { it is Density.Problem.ShouldBeEmpty })
    }

    @Test
    fun `未来的空天没有问题`() {
        assertTrue(Density.check(emptyList(), 0).isEmpty())
    }

    @Test
    fun `一条都没有但该铺算问题`() {
        val problems = Density.check(emptyList(), 870)
        assertTrue(problems.any { it is Density.Problem.NotStarted })
    }

    // ========================================================== gapRanges

    @Test
    fun `完全空白的一天_要补一整段`() {
        val gaps = Density.gapRanges(emptyList(), 870)
        assertEquals(1, gaps.size)
        assertEquals(0, gaps.first().start)
        assertEquals(869, gaps.first().endInclusive) // 开区间后端：[0, 870)
    }

    @Test
    fun `密铺的没有要补的`() {
        assertTrue(Density.gapRanges(listOf(rec(0, 870)), 870).isEmpty())
    }

    @Test
    fun `中间的洞要补`() {
        val gaps = Density.gapRanges(listOf(rec(0, 300), rec(500, 870)), 870)
        assertEquals(1, gaps.size)
        assertEquals(300, gaps.first().start)
        assertEquals(499, gaps.first().endInclusive)
    }

    @Test
    fun `头部和尾部各补一段`() {
        val gaps = Density.gapRanges(listOf(rec(100, 300)), 870)
        assertEquals(2, gaps.size)
        assertEquals(0 to 99, gaps[0].start to gaps[0].endInclusive)
        assertEquals(300 to 869, gaps[1].start to gaps[1].endInclusive)
    }

    @Test
    fun `进行中的那条不产生尾部洞`() {
        // 最后一条 endMin = null，它铺到 cut → 没洞
        assertTrue(Density.gapRanges(listOf(rec(0, null)), 870).isEmpty())
    }

    @Test
    fun `未来不补`() {
        assertTrue(Density.gapRanges(emptyList(), 0).isEmpty())
    }

    @Test
    fun `补完之后一定密铺`() {
        // 这是最重要的一条：gapRanges 的输出必须能让 check 变干净
        val day = listOf(rec(200, 300), rec(600, 700))
        val cut = 870

        val filled = day + Density.gapRanges(day, cut).map { gap(it.start, it.endInclusive + 1) }
        assertTrue(
            "补完还该是密铺的，实际：" + Density.check(filled, cut),
            Density.check(filled, cut).isEmpty(),
        )
    }
}
