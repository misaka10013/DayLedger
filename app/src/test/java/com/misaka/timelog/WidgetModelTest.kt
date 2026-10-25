package com.misaka.timelog

import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.ui.TimelineAssembler
import com.misaka.timelog.widget.WidgetModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面小部件的「该显示什么」—— **纯 JVM，秒级跑完**。
 *
 * ## 为什么这个文件比别的测试都重要
 *
 * 小部件的界面**没法自动化验收**：它跑在系统桌面的宿主进程里，
 * Robolectric 模拟不了 AppWidgetHost，只能真机手动加减看。
 * 也就是说，`TimelineWidget` 那个文件里写错了什么，构建照样绿、测试照样绿。
 *
 * 所以对策只有一个：**把判断全部推到纯函数里**（`WidgetModel`），
 * 让界面退化到「量尺寸 + 照着画」。这个文件盯的就是那一坨判断。
 *
 * ⚠️ 断言的落点跟别处一样：**看数字，不看代码长相**。
 * 比如「色条铺满」断言的是权重和 = 1，不是「bars 不为空」。
 */
class WidgetModelTest {

    private val today = "2026-10-15"

    private fun item(id: String, name: String, color: String = "#4C6FE8") =
        ItemEntity(id = id, categoryId = "c1", name = name, colorHex = color, orderInCat = 0)

    /**
     * 造一条记录。
     *
     * ⚠️ 不给 `itemId` 时默认就是空白（`GAP`）—— 因为 `RecordEntity.isGap`
     * 的判据是「`source == GAP` **或** `itemId == null`」，两者得一起对上，
     * 否则造出来的数据跟真实情况不是一回事。
     */
    private fun rec(
        id: String,
        start: Int,
        end: Int?,
        itemId: String? = null,
        source: RecordSource = if (itemId == null) RecordSource.GAP else RecordSource.MANUAL,
    ) = RecordEntity(
        id = id,
        day = today,
        startMin = start,
        endMin = end,
        itemId = itemId,
        categoryIdSnap = null,
        source = source,
        chainId = null,
        noTemplate = false,
        note = "",
        updatedAt = 0L,
        deleted = false,
    )

    /** 走**真实的那条链路**：实体 + 字典 → 装配器 → WidgetModel。 */
    private fun model(
        records: List<RecordEntity>,
        cut: Int,
        items: List<ItemEntity> = emptyList(),
    ): WidgetModel = WidgetModel.from(
        TimelineAssembler.day(
            records = records,
            cut = cut,
            itemsById = items.associateBy { it.id },
            catsById = emptyMap(),
            tagsByRec = emptyMap(),
        ),
        cut,
    )

    // ============================================================ 进行中

    @Test
    fun `空闲时_没有进行中的那条`() {
        val m = model(listOf(rec("g", 0, 600)), cut = 600)
        assertNull(m.live)
    }

    @Test
    fun `进行中_出名字_已持续时长和起点`() {
        val items = listOf(item("i1", "打工"))
        val m = model(
            records = listOf(rec("a", 0, 315, "i1"), rec("live", 315, null, "i1")),
            cut = 375,
            items = items,
        )
        assertEquals("打工", m.live?.name)
        // 375 - 315 = 60 分钟
        assertEquals("1时0分", m.live?.durationLabel)
        assertEquals("05:15", m.live?.startLabel)
    }

    @Test
    fun `进行中_颜色跟着事项的分类色走`() {
        val items = listOf(item("i1", "打工", "#AABBCC"))
        val m = model(listOf(rec("live", 0, null, "i1")), cut = 60, items = items)
        assertEquals("#AABBCC", m.live?.colorHex)
    }

    @Test
    fun `模板铺的那条_带 fromTemplate 标记`() {
        val items = listOf(item("i1", "打工"))
        val m = model(
            records = listOf(rec("t", 0, null, "i1", RecordSource.TEMPLATE)),
            cut = 120,
            items = items,
        )
        assertTrue("模板铺的必须能看出来（不然记录自己冒出来会以为见鬼了）", m.live!!.fromTemplate)
    }

    /**
     * ★ 这条是「改统计口径」那类 bug 的亲戚。
     *
     * 事项被删掉但记录还在（孤儿）**不是空白** —— 空白的意思是「我没记」，
     * 把一条真记过的记录显示成「未记录」等于篡改历史。
     *
     * ⚠️ 判据是 `itemId` 有没有值，**不是**「事项查不查得到」：
     * 查不到时显示「（事项已删除）」，那是另一回事。
     */
    @Test
    fun `事项被删掉_显示事项已删除而不是未记录`() {
        val m = model(
            records = listOf(rec("a", 0, null, "i-gone")),
            cut = 600,
            items = emptyList(),
        )
        assertEquals(TimelineAssembler.ORPHAN_NAME, m.live?.name)
        assertFalse("孤儿记录不是空白 —— 别让它算进未记录", m.hasGap)
    }

    // ============================================================ 今日合计

    @Test
    fun `合计_已记录和未记录分开算`() {
        val items = listOf(item("i1", "打工"))
        val m = model(
            records = listOf(rec("a", 0, 375, "i1"), rec("g", 375, 600)),
            cut = 600,
            items = items,
        )
        assertEquals("6时15分", m.recordedLabel)
        assertEquals("3时45分", m.gapLabel)
        assertTrue(m.hasGap)
    }

    @Test
    fun `记全了的一天_不提未记录`() {
        val items = listOf(item("i1", "打工"))
        val m = model(listOf(rec("a", 0, 1440, "i1")), cut = 1440, items = items)
        assertFalse(m.hasGap)
        assertEquals("0分", m.gapLabel)
    }

    @Test
    fun `合计用分钟数不要被 1440 当分母`() {
        // 只过了 10 小时 —— 剩下那 14 小时是「还没到」，**不是未记录**
        val items = listOf(item("i1", "打工"))
        val m = model(listOf(rec("a", 0, 600, "i1")), cut = 600, items = items)
        assertEquals("10时0分", m.recordedLabel)
        assertEquals("0分", m.gapLabel)
    }

    // ============================================================ 色条

    @Test
    fun `色条_按真实时序铺满到此刻`() {
        val items = listOf(item("i1", "打工", "#111111"), item("i2", "听歌", "#222222"))
        val m = model(
            records = listOf(
                rec("a", 0, 240, "i1"),
                rec("b", 240, 480, "i2"),
                rec("g", 480, 600),
            ),
            cut = 600,
            items = items,
        )

        assertEquals(3, m.bars.size)
        // ★ 权重和必须 = 1（铺满）—— 不是「bars 不为空」那种软断言
        assertEquals(1.0f, m.bars.sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
        assertEquals(0.4f, m.bars[0].weight, 0.001f) // 240/600
        assertFalse(m.bars[0].isGap)
        assertTrue("最后那段是未记录，色条上得看得出来", m.bars[2].isGap)
    }

    /**
     * ⚠️ 相邻同色要合并 —— 不只是「省几个元素」的事：
     * 小部件的 UI 树会被序列化进 RemoteViews，一天几十条记录不合并的话
     * 那条色条就是一串几十个 View，渲染明显变慢。
     */
    @Test
    fun `色条_相邻同色的两段合成一段`() {
        val items = listOf(item("i1", "打工", "#111111"))
        val m = model(
            records = listOf(rec("a", 0, 240, "i1"), rec("b", 240, 480, "i1")),
            cut = 480,
            items = items,
        )
        assertEquals(1, m.bars.size)
        assertEquals(1.0f, m.bars[0].weight, 0.001f)
    }

    @Test
    fun `色条_中间隔着别的颜色就不合并`() {
        val items = listOf(item("i1", "打工", "#111111"), item("i2", "听歌", "#222222"))
        val m = model(
            records = listOf(
                rec("a", 0, 240, "i1"),
                rec("b", 240, 480, "i2"),
                rec("c", 480, 600, "i1"),
            ),
            cut = 600,
            items = items,
        )
        assertEquals(3, m.bars.size)
    }

    @Test
    fun `色条_零长度段不参与`() {
        val items = listOf(item("i1", "打工"))
        val m = model(
            records = listOf(rec("a", 0, 600, "i1"), rec("z", 100, 100, "i1")),
            cut = 600,
            items = items,
        )
        // 零长度那段被丢掉，剩下那一整段权重 = 1
        assertEquals(1, m.bars.size)
        assertEquals(1.0f, m.bars[0].weight, 0.001f)
    }

    /**
     * ⚠️ 0 点整那一瞬 cut = 0，比例的分母就是 0 —— 不挡住的话小部件会在午夜崩掉，
     * 而且一年只崩一次，属于永远查不出来的那种。
     */
    @Test
    fun `零点整_色条为空而不是除零`() {
        val m = model(records = emptyList(), cut = 0)
        assertTrue(m.bars.isEmpty())
        assertTrue(m.empty)
    }
}
