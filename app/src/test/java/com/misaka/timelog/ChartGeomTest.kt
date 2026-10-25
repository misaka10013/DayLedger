package com.misaka.timelog

import com.misaka.timelog.domain.ChartGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四张图的**画布几何不变量**。
 *
 * ## 为什么这几条值一条测试
 *
 * 因为它们钉的是**唯一一个「不报错、不崩、截图也看不出来」的 bug**：
 *
 * 图上那排点击热区是透明的，跟柱子一一对应。当年写成
 * `Spacer(weight(PAD_L)) + repeat(n){ weight(1f) } + Spacer(weight(PAD_R))`，
 * 横向权重之和 = `30 + 7 + 8 = 45`（应该是 350）→ 左边距吃掉 67% 宽度，
 * 只剩最右边 15% 的一条能点。
 *
 * 症状是「**图上点哪都没反应**」。没有异常、没有日志、截图上热区是透明的看不见 ——
 * 只能靠真机上一格一格试。所以把它变成一条**穷举列数的断言**，
 * 以后改留白、改列数都不可能再悄悄错位。
 */
class ChartGeomTest {

    /** 实际会用到的列数：日 1 / 周 7 / 月 28–31 / 年 12 / 自 ≤31 或按月（最多几百列）。 */
    private val realCounts = listOf(1, 7, 12, 28, 29, 30, 31)

    private val eps = 0.0001f

    @Test
    fun `横向_左边距加所有列加右边距_恰好等于画布宽度`() {
        for (n in 1..60) {
            val sum = ChartGeom.PAD_L + ChartGeom.colWeight(n) * n + ChartGeom.PAD_R
            assertEquals("$n 列的横向权重之和必须是画布宽度", ChartGeom.VW, sum, eps)
        }
    }

    @Test
    fun `纵向_上边距加中间行加下边距_恰好等于画布高度`() {
        val sum = ChartGeom.PAD_T + ChartGeom.rowWeight() + ChartGeom.PAD_B
        assertEquals("纵向权重之和必须是画布高度", ChartGeom.VH, sum, eps)
    }

    @Test
    fun `回归_横向的分母是画布宽度_不是左边距加列数加右边距`() {
        // ★ 这一条就是当年那个 bug。旧写法下左边距占整行的 30/45 ≈ 67%，
        //    新写法应该是 30/350 ≈ 8.6%。
        val left = ChartGeom.tapLeftFraction()
        assertEquals("左边距占宽度的比例", ChartGeom.PAD_L / ChartGeom.VW, left, eps)
        assertTrue(
            "左边距最多占宽度的一成五 —— 占了一半以上就是那个老 bug 又回来了（左边距=$left）",
            left < 0.15f,
        )
        assertEquals("右边距占宽度的比例", ChartGeom.PAD_R / ChartGeom.VW, 1f - ChartGeom.tapRightFraction(), eps)
        // 左边距 + 全部热区 + 右边距 在比例上也要凑满 1
        realCounts.forEach { n ->
            val span = ChartGeom.tapLeftFraction() +
                ChartGeom.tapWidthFraction(n) * n +
                (1f - ChartGeom.tapRightFraction())
            assertEquals("$n 列时热区横向正好铺满整块画布", 1f, span, eps)
        }
    }

    @Test
    fun `每个热区的中心_跟横轴那一列的中心严丝合缝`() {
        realCounts.forEach { n ->
            for (i in 0 until n) {
                // 热区按比例算出来的中心（换算回画布坐标）
                val hot = (
                    ChartGeom.tapLeftFraction() +
                        ChartGeom.tapWidthFraction(n) * i +
                        ChartGeom.tapWidthFraction(n) / 2f
                    ) * ChartGeom.VW
                assertEquals(
                    "第 $i/$n 个热区的中心必须落在那一列的中心上",
                    ChartGeom.colCenterX(i, n),
                    hot,
                    eps,
                )
            }
        }
    }

    @Test
    fun `第一列从左边距开始_最后一列到右边距结束`() {
        realCounts.forEach { n ->
            val firstLeft = ChartGeom.tapLeftFraction() * ChartGeom.VW
            assertEquals("第一列的左边界就是 PAD_L", ChartGeom.PAD_L, firstLeft, eps)

            val lastRight = (
                ChartGeom.tapLeftFraction() +
                    ChartGeom.tapWidthFraction(n) * n
                ) * ChartGeom.VW
            assertEquals("最后一列的右边界就是 VW - PAD_R", ChartGeom.VW - ChartGeom.PAD_R, lastRight, eps)
        }
    }

    @Test
    fun `热区纵向_顶端差一个上边距_底端差一个下边距`() {
        assertEquals("热区上边就是 PAD_T", ChartGeom.PAD_T, ChartGeom.tapTopFraction() * ChartGeom.VH, eps)
        assertEquals(
            "热区下边就是 VH - PAD_B",
            ChartGeom.VH - ChartGeom.PAD_B,
            ChartGeom.tapBottomFraction() * ChartGeom.VH,
            eps,
        )
    }

    @Test
    fun `每列的宽度都大于零_且加起来是画布减去左右留白`() {
        for (n in 1..60) {
            assertTrue("$n 列的每一列都得有宽度", ChartGeom.colWeight(n) > 0f)
            assertEquals(
                "$n 列的总宽 = VW - PAD_L - PAD_R",
                ChartGeom.VW - ChartGeom.PAD_L - ChartGeom.PAD_R,
                ChartGeom.colWeight(n) * n,
                eps,
            )
        }
    }

    @Test
    fun `最窄的场景_年口径十二列_每列也有画布宽度的百分之二以上`() {
        // 再怎么挤，一列也得占得住手指。350/12 ≈ 29 个画布单位 ≈ 8.3% 宽度。
        val n = 12
        val each = ChartGeom.colWeight(n) / ChartGeom.VW
        assertTrue("12 列时每列占宽度 $each，太窄了会点不准", each > 0.02f)
    }
}
