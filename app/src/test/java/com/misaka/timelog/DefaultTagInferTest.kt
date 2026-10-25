package com.misaka.timelog

import com.misaka.timelog.domain.DefaultTagInfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从历史记录反推「事项默认标签」的算法。
 *
 * 这块逻辑只有一行 SQL 的距离就能写错，而写错的后果是**用户每次记录都带着错的标签**，
 * 得一条条手动去掉 —— 比不填还烦。所以判据（覆盖率、条数、排序）都钉死。
 */
class DefaultTagInferTest {

    private fun u(item: String, tag: String, n: Int) = DefaultTagInfer.Usage(item, tag, n)

    @Test
    fun `覆盖率不到一半就不入选`() {
        val got = DefaultTagInfer.infer(
            usages = listOf(u("打工", "日常必需", 4), u("打工", "能量消耗", 1)),
            recordCountByItem = mapOf("打工" to 10),
        )
        // 日常必需 40%、能量消耗 10% —— 都不够
        assertTrue("两个都不到 50%，不该有结果", got.isEmpty())
    }

    @Test
    fun `覆盖率刚好一半要入选_边界含等于`() {
        val got = DefaultTagInfer.infer(
            usages = listOf(u("打工", "日常必需", 5)),
            recordCountByItem = mapOf("打工" to 10),
        )
        assertEquals(1, got.size)
        assertEquals("日常必需", got[0].tagId)
    }

    @Test
    fun `记录太少就不敢推断`() {
        // 只有 2 条记录、100% 都带某个标签 —— 统计上太弱，不推
        val got = DefaultTagInfer.infer(
            usages = listOf(u("编程", "高价值投资", 2)),
            recordCountByItem = mapOf("编程" to 2),
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `一个事项最多给三个标签`() {
        val got = DefaultTagInfer.infer(
            usages = listOf(
                u("面基", "能量增益", 10),
                u("面基", "社交经营", 10),
                u("面基", "健康维护", 9),
                u("面基", "高价值投资", 8),
            ),
            recordCountByItem = mapOf("面基" to 10),
        )
        assertEquals(3, got.size)
        // 被砍掉的是覆盖率最低的那个
        assertTrue("64% 的那个该被砍", got.none { it.tagId == "高价值投资" })
    }

    @Test
    fun `砍掉的是覆盖率最低的_不是随便砍`() {
        val got = DefaultTagInfer.infer(
            usages = listOf(
                u("X", "低", 5),   // 50%
                u("X", "高", 10),  // 100%
                u("X", "中", 8),   // 80%
                u("X", "更低", 5), // 50%
            ),
            recordCountByItem = mapOf("X" to 10),
        )
        assertEquals(3, got.size)
        val kept = got.map { it.tagId }.toSet()
        assertTrue("100% 和 80% 必须在", kept.containsAll(setOf("高", "中")))
    }

    @Test
    fun `同一份数据跑两次结果必须一样`() {
        val usages = listOf(
            u("A", "t3", 9), u("A", "t1", 9), u("A", "t2", 9),
            u("B", "t9", 7), u("B", "t8", 7),
        )
        val counts = mapOf("A" to 10, "B" to 10)
        val first = DefaultTagInfer.infer(usages, counts)
        val second = DefaultTagInfer.infer(usages.reversed(), counts)
        assertEquals(
            "覆盖率相同的标签靠 tagId 定序，换输入顺序不能变结果",
            first, second,
        )
    }

    @Test
    fun `空输入不炸`() {
        assertTrue(DefaultTagInfer.infer(emptyList(), emptyMap()).isEmpty())
        assertTrue(DefaultTagInfer.infer(listOf(u("A", "t", 5)), emptyMap()).isEmpty())
    }

    @Test
    fun `必须按覆盖率而不是出现次数_不然长尾事项会污染短事项`() {
        // 「刷资讯」1196 条记录，某标签只覆盖 10% = 119 次
        // 「摄影」只有 3 条记录，某标签覆盖 100% = 3 次
        // 按次数排：刷资讯那个 119 次完胜 —— 但它是这个事项的偶发情况，不该变成默认标签
        val got = DefaultTagInfer.infer(
            usages = listOf(
                u("刷资讯", "偶发标签", 119),
                u("摄影", "固有标签", 3),
            ),
            recordCountByItem = mapOf("刷资讯" to 1196, "摄影" to 3),
        )
        val forSheying = got.filter { it.itemId == "摄影" }
        assertEquals(1, forSheying.size)
        assertEquals("固有标签", forSheying[0].tagId)

        val forShua = got.filter { it.itemId == "刷资讯" }
        assertTrue("10% 覆盖率的标签不该入选", forShua.isEmpty())
    }

    @Test
    fun `真库里的典型规律`() {
        // 睡觉 829 条：日常必需 812（98%）、能量增益 340（41%）
        // 打工 537 条：日常必需 531（99%）、能量消耗 324（60%）
        val got = DefaultTagInfer.infer(
            usages = listOf(
                u("睡觉", "日常必需", 812), u("睡觉", "能量增益", 340),
                u("打工", "日常必需", 531), u("打工", "能量消耗", 324),
            ),
            recordCountByItem = mapOf("睡觉" to 829, "打工" to 537),
        )
        val sleep = got.filter { it.itemId == "睡觉" }.map { it.tagId }
        assertEquals("睡觉只该带日常必需（能量增益只有 41%）", listOf("日常必需"), sleep)

        val work = got.filter { it.itemId == "打工" }.map { it.tagId }.toSet()
        assertEquals(setOf("日常必需", "能量消耗"), work)
    }
}
