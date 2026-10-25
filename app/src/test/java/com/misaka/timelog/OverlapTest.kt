package com.misaka.timelog

import com.misaka.timelog.domain.Overlap
import com.misaka.timelog.domain.Overlap.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撞车让位。
 *
 * 这个函数是 M2-B 里唯一「算错了不会当场报错、只在几个月后让统计对不上」的地方，
 * 所以下面的用例是**照着密铺不变式一条条摆的**：每测完一种撞法，
 * 都要能把「改动后这一天还是不是完整覆盖」推出来。
 */
class OverlapTest {

    private fun span(id: String, a: Int, b: Int?) = Overlap.Span(id, a, b)

    /** 白天用不到 1440，但让位逻辑只认数字，用 1440 更贴近真实。 */
    private val cut = 1440

    @Test
    fun `没撞到任何人就什么都不做`() {
        val me = span("me", 600, 660)
        val others = listOf(span("a", 0, 600), span("b", 660, 720))

        assertTrue(Overlap.resolve(me, others, cut).isEmpty())
    }

    @Test
    fun `首尾相接不算重叠`() {
        // 密铺的正常形态就是首尾相接 —— 把它当成重叠会让每次改动都误伤邻居
        val me = span("me", 600, 660)
        val others = listOf(span("a", 540, 600), span("b", 660, 700))

        assertTrue(Overlap.resolve(me, others, cut).isEmpty())
    }

    @Test
    fun `自己不算自己的邻居`() {
        val me = span("me", 600, 660)
        val others = listOf(span("me", 600, 660))

        assertTrue(Overlap.resolve(me, others, cut).isEmpty())
    }

    @Test
    fun `完全被吞掉的邻居要被删掉`() {
        val me = span("me", 600, 660)
        val others = listOf(span("a", 610, 650))

        assertEquals(listOf<Effect>(Effect.Drop("a")), Overlap.resolve(me, others, cut))
    }

    @Test
    fun `和 me 完全重合的邻居要被删掉`() {
        val me = span("me", 600, 660)
        val others = listOf(span("a", 600, 660))

        assertEquals(listOf<Effect>(Effect.Drop("a")), Overlap.resolve(me, others, cut))
    }

    @Test
    fun `前面那条的尾巴被切到我的起点`() {
        // a 原本 540→620，我占了 600 以后，a 该缩到 540→600
        val me = span("me", 600, 660)
        val others = listOf(span("a", 540, 620))

        assertEquals(listOf<Effect>(Effect.TrimEnd("a", 600)), Overlap.resolve(me, others, cut))
    }

    @Test
    fun `后面那条的头被切到我的终点`() {
        // b 原本 640→720，我占了 600→660，b 该缩到 660→720
        val me = span("me", 600, 660)
        val others = listOf(span("b", 640, 720))

        assertEquals(listOf<Effect>(Effect.TrimStart("b", 660)), Overlap.resolve(me, others, cut))
    }

    @Test
    fun `一次改动可以同时压住前后两条`() {
        val me = span("me", 600, 660)
        val others = listOf(span("a", 540, 620), span("b", 640, 720))

        val effects = Overlap.resolve(me, others, cut)

        assertEquals(2, effects.size)
        assertTrue(effects.contains(Effect.TrimEnd("a", 600)))
        assertTrue(effects.contains(Effect.TrimStart("b", 660)))
    }

    @Test
    fun `一次改动可以把中间好几条全吞掉`() {
        // 我把时间拉长到 600→800，中间夹着的 b、c 都被接管
        val me = span("me", 600, 800)
        val others = listOf(span("a", 0, 600), span("b", 600, 700), span("c", 700, 800), span("d", 800, 900))

        val effects = Overlap.resolve(me, others, cut)

        assertEquals(2, effects.size)
        assertTrue(effects.contains(Effect.Drop("b")))
        assertTrue(effects.contains(Effect.Drop("c")))
    }

    @Test
    fun `零长度的改动不挤开任何人`() {
        // 它不代表任何一段时间 —— 没资格让别人让位
        val me = span("me", 600, 600)
        val others = listOf(span("a", 600, 660))

        assertTrue(Overlap.resolve(me, others, cut).isEmpty())
    }

    @Test
    fun `终点倒挂的改动不挤开任何人`() {
        val me = span("me", 700, 600)
        val others = listOf(span("a", 600, 660))

        assertTrue(Overlap.resolve(me, others, cut).isEmpty())
    }

    @Test
    fun `进行中的邻居用此刻当终点`() {
        // a 是正在进行的那条（600 → 现在 650）；我改到 620→700，a 该缩到 600→620
        val me = span("me", 620, 700)
        val others = listOf(span("a", 600, null))

        assertEquals(listOf<Effect>(Effect.TrimEnd("a", 620)), Overlap.resolve(me, others, cut = 650))
    }

    @Test
    fun `进行中的邻居被完整覆盖时也要能删掉`() {
        val me = span("me", 590, 700)
        val others = listOf(span("a", 600, null))

        assertEquals(listOf<Effect>(Effect.Drop("a")), Overlap.resolve(me, others, cut = 650))
    }

    @Test
    fun `自己进行中时用此刻当终点`() {
        // 我改成了「600 → 现在 650」，邻居 b 在 640→720，该缩到 650→720
        val me = span("me", 600, null)
        val others = listOf(span("b", 640, 720))

        assertEquals(listOf<Effect>(Effect.TrimStart("b", 650)), Overlap.resolve(me, others, cut = 650))
    }

    @Test
    fun `邻居把我整个包住时只切它的尾巴`() {
        // 极端情况：我挪到了 a 的内部（600→700 落进 540→800 里）
        // 已知简化 —— 只切尾，我终点到 a 原终点那一截由 ensureDense 补成空白
        val me = span("me", 600, 700)
        val others = listOf(span("a", 540, 800))

        assertEquals(listOf<Effect>(Effect.TrimEnd("a", 600)), Overlap.resolve(me, others, cut))
    }

    // ---------------------------------------------------------- 相邻空白的合并

    @Test
    fun `相邻的两段空白要配对`() {
        val list = listOf(span("a", 0, 600), span("b", 600, 700))
        val isGap = { id: String -> true }

        assertEquals(listOf("a" to "b"), Overlap.adjacentGapPairs(list, isGap))
    }

    @Test
    fun `中间隔着一条真实记录的两段空白不配对`() {
        val list = listOf(span("a", 0, 600), span("r", 600, 700), span("b", 700, 800))
        val isGap = { id: String -> id != "r" }

        assertTrue(Overlap.adjacentGapPairs(list, isGap).isEmpty())
    }

    @Test
    fun `三条连着的空白只配一次、两条一起被吞`() {
        // 配对是「保留 a、吞掉 b」，a 的终点会被拉到 c 的终点（由调用方做）
        val list = listOf(span("a", 0, 100), span("b", 100, 200), span("c", 200, 300))
        val isGap = { _: String -> true }

        assertEquals(listOf("a" to "b"), Overlap.adjacentGapPairs(list, isGap))
    }

    @Test
    fun `不是空白的不参与配对`() {
        val list = listOf(span("a", 0, 100), span("b", 100, 200))
        val isGap = { _: String -> false }

        assertTrue(Overlap.adjacentGapPairs(list, isGap).isEmpty())
    }

    @Test
    fun `端点没接上的两段空白不配对`() {
        // a 的终点是 null（进行中）—— 没法跟 b 配
        val list = listOf(span("a", 0, null), span("b", 100, 200))
        val isGap = { _: String -> true }

        assertTrue(Overlap.adjacentGapPairs(list, isGap).isEmpty())
    }
}
