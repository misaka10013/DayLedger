package com.misaka.timelog

import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.ui.TimelineAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配器的测试 —— **纯 JVM，不起 Android 环境，整个类一两秒跑完**。
 *
 * ## 这里守的是什么
 *
 * 装配器负责回答「这条记录该显示成什么样」，而它要处理的分支里有一半
 * **在真机上几乎复现不出来**：
 * - 事项被删了但历史记录还在（正常流程下会被擦成空白，只有异常才留下）
 * - `itemId` 为空却又是 `MANUAL` 的记录（说不通的状态，但得兜住）
 * - 零长度的记录（同一分钟内点开又删掉）
 * - 进行中的那一条，终点该算到哪
 *
 * 这些要是只在真机上验，就得先去数据库里造脏数据 —— 既麻烦又不该那么干。
 * 纯函数之后，构造一个对象就测到了。
 *
 * ## 最值钱的那一条
 *
 * [已记录加未记录恒等于该铺的长度] —— 它是密铺不变式（一天恒 = 1440 分钟）
 * 在**用户可见层面**的样子。顶部那两个数字加起来对不上，
 * 就说明那天的数据已经破了，而用户会先于我们发现。
 */
class TimelineAssemblerTest {

    private val day = "2026-10-14"

    private fun rec(
        id: String = "r1",
        start: Int,
        end: Int?,
        itemId: String? = null,
        source: RecordSource = RecordSource.MANUAL,
        note: String = "",
    ) = RecordEntity(
        id = id,
        day = day,
        startMin = start,
        endMin = end,
        itemId = itemId,
        categoryIdSnap = null,
        source = source,
        chainId = null,
        noTemplate = false,
        note = note,
        updatedAt = 0L,
        deleted = false,
    )

    private fun gap(id: String = "g1", start: Int, end: Int?) =
        rec(id = id, start = start, end = end, itemId = null, source = RecordSource.GAP)

    private val item = ItemEntity(
        id = "i1",
        categoryId = "c1",
        name = "编程",
        colorHex = "#7B3FC4",
        orderInCat = 0,
    )

    private val cat = CategoryEntity(id = "c1", name = "技能成长", colorHex = "#7B3FC4", position = 0)

    private val tag = TagEntity(
        id = "g7",
        bucketId = "t3",
        name = "健康维护",
        colorHex = "#0EA5E9",
        description = "健康饮食、运动、正念",
    )

    private val items = mapOf(item.id to item)
    private val cats = mapOf(cat.id to cat)

    private fun assemble(
        records: List<RecordEntity>,
        cut: Int,
        tagsByRec: Map<String, List<TagEntity>> = emptyMap(),
    ) = TimelineAssembler.day(records, cut, items, cats, tagsByRec)

    // ====================================================== 单条：该显示成什么

    @Test
    fun `空白段显示为未记录 整体调灰 且不带标签和备注`() {
        // 就算数据库里有人手贱给空白挂了个备注/标签，界面上也不该显示 ——
        // 空白就是空白，它没有内容。
        val row = TimelineAssembler.row(
            record = gap(start = 480, end = 870).copy(note = "不该显示", itemId = null),
            cut = 870,
            itemName = null,
            itemColor = null,
            categoryName = null,
            tags = listOf(tag),
        )

        assertTrue(row.isGap)
        assertEquals(TimelineAssembler.GAP_NAME, row.name)
        assertEquals(TimelineAssembler.GAP_COLOR, row.colorHex)
        assertNull(row.categoryName)
        assertTrue(row.tags.isEmpty())
        assertEquals("", row.note)
    }

    @Test
    fun `正常记录带上事项名 颜色 分类名和标签`() {
        val row = TimelineAssembler.row(
            record = rec(start = 480, end = 870, itemId = "i1"),
            cut = 870,
            itemName = item.name,
            itemColor = item.colorHex,
            categoryName = cat.name,
            tags = listOf(tag),
        )

        assertFalse(row.isGap)
        assertEquals("编程", row.name)
        assertEquals("#7B3FC4", row.colorHex)
        assertEquals("技能成长", row.categoryName)
        assertEquals(1, row.tags.size)
        assertEquals("健康维护", row.tags.first().name)
    }

    @Test
    fun `事项被删掉后 那条记录不会变成空白 而是明说事项已删除`() {
        // ⚠️ 这里是个刻意的取舍：itemId 查不到事项时**不当作空白**。
        //    「未记录」的含义是「这段时间我没记」—— 而这条明明记过，只是事项没了。
        //    显示成未记录会篡改历史；显示「（事项已删除）」才是实话。
        val row = TimelineAssembler.row(
            record = rec(start = 480, end = 870, itemId = "i-不存在"),
            cut = 870,
            itemName = null,
            itemColor = null,
            categoryName = null,
            tags = emptyList(),
        )

        assertFalse(row.isGap)
        assertEquals(TimelineAssembler.ORPHAN_NAME, row.name)
    }

    @Test
    fun `事项为空却又不是空白的记录 按空白处理`() {
        // 这是个说不通的状态（正常流程造不出来，只能是异常残留）。
        // 与其显示一个没有名字的卡片，不如老实当空白 —— 至少用户能点它补记。
        val row = TimelineAssembler.row(
            record = rec(start = 480, end = 870, itemId = null, source = RecordSource.MANUAL),
            cut = 870,
            itemName = null,
            itemColor = null,
            categoryName = null,
            tags = emptyList(),
        )

        assertTrue(row.isGap)
        assertEquals(TimelineAssembler.GAP_NAME, row.name)
    }

    @Test
    fun `进行中那一条的终点是现在 时长算到此刻`() {
        // ★ 这是「正在记」最容易算错的地方：它的 endMin 是 null，
        //   如果直接拿 null 去减，要么崩、要么算成 0。
        val row = TimelineAssembler.row(
            record = rec(start = 780, end = null, itemId = "i1"),
            cut = 870,
            itemName = item.name,
            itemColor = item.colorHex,
            categoryName = cat.name,
            tags = emptyList(),
        )

        assertTrue(row.isLive)
        assertEquals("现在", row.endLabel)
        assertEquals("13:00", row.startLabel)
        assertEquals(90, row.durationMin)
        assertEquals("1时30分", row.durationLabel)
    }

    @Test
    fun `已完结那一条的终点显示真实时刻`() {
        val row = TimelineAssembler.row(
            record = rec(start = 780, end = 870, itemId = "i1"),
            cut = 1440,
            itemName = item.name,
            itemColor = item.colorHex,
            categoryName = cat.name,
            tags = emptyList(),
        )

        assertFalse(row.isLive)
        assertEquals("14:30", row.endLabel)
        assertEquals(90, row.durationMin)
    }

    @Test
    fun `模板铺出来的记录带着标记`() {
        val row = TimelineAssembler.row(
            record = rec(start = 0, end = 60, itemId = "i1", source = RecordSource.TEMPLATE),
            cut = 1440,
            itemName = item.name,
            itemColor = item.colorHex,
            categoryName = cat.name,
            tags = emptyList(),
        )

        assertTrue(row.fromTemplate)
    }

    @Test
    fun `零长度的记录时长是零分 不是负数`() {
        // 同一分钟内点开又删掉 —— 「点错了想撤回」最常见的一种
        val row = TimelineAssembler.row(
            record = rec(start = 500, end = 500, itemId = "i1"),
            cut = 1440,
            itemName = item.name,
            itemColor = item.colorHex,
            categoryName = cat.name,
            tags = emptyList(),
        )

        assertEquals(0, row.durationMin)
        assertEquals("0分", row.durationLabel)
    }

    // ====================================================== 装配一整天

    @Test
    fun `列表是倒序的 越晚越靠上`() {
        val view = assemble(
            records = listOf(
                gap("g1", 0, 480),
                rec("r2", 480, 870, itemId = "i1"),
            ),
            cut = 870,
        )

        assertEquals(2, view.rows.size)
        // 480 开始的那条（更晚）必须排在 0 开始的那条前面
        assertEquals("r2", view.rows[0].id)
        assertEquals("g1", view.rows[1].id)
    }

    @Test
    fun `已记录加未记录恒等于该铺的长度`() {
        // ★★ 这是密铺不变式在用户可见层面的样子。
        //    顶部「已记录 X · 未记录 Y」这两个数字加起来如果对不上，
        //    就说明那天的数据破了 —— 而用户会比我们先发现。
        val view = assemble(
            records = listOf(
                rec("r1", 0, 480, itemId = "i1"),
                gap("g1", 480, 870),
            ),
            cut = 870,
        )

        assertEquals(480, view.recordedMin)
        assertEquals(390, view.gapMin)
        assertEquals(870, view.recordedMin + view.gapMin)
    }

    @Test
    fun `过去的一天铺满时 两个数字加起来是 1440`() {
        val view = assemble(
            records = listOf(
                rec("r1", 0, 100, itemId = "i1"),
                gap("g1", 100, 1440),
            ),
            cut = 1440,
        )

        assertEquals(1440, view.recordedMin + view.gapMin)
        assertEquals(100, view.recordedMin)
    }

    @Test
    fun `记录完整时未记录为零`() {
        val view = assemble(
            records = listOf(rec("r1", 0, 1440, itemId = "i1")),
            cut = 1440,
        )

        assertEquals(0, view.gapMin)
        assertEquals(1440, view.recordedMin)
    }

    @Test
    fun `进行中的那条会被单独拎出来交给底部状态条`() {
        val view = assemble(
            records = listOf(
                gap("g1", 0, 780),
                rec("r2", 780, null, itemId = "i1"),
            ),
            cut = 870,
        )

        assertEquals("r2", view.liveRow?.id)
        assertEquals("编程", view.liveRow?.name)
        // 它的时长要算到此刻（780 → 870 = 90 分钟），不是 0
        assertEquals(90, view.liveRow?.durationMin)
    }

    @Test
    fun `没有进行中的记录时 liveRow 为空`() {
        val view = assemble(records = listOf(gap("g1", 0, 870)), cut = 870)

        assertNull(view.liveRow)
    }

    @Test
    fun `空的一天装配出来是空的 不会崩`() {
        // 今天刚过午夜那几分钟（cut = 0）会走到这里
        val view = assemble(records = emptyList(), cut = 0)

        assertTrue(view.rows.isEmpty())
        assertEquals(0, view.recordedMin)
        assertEquals(0, view.gapMin)
        assertNull(view.liveRow)
    }

    @Test
    fun `标签按记录分别挂 不会串到别的记录上`() {
        val tag2 = TagEntity("g4", "t2", "能量增益", "#22C55E", "做完精力更足")

        val view = assemble(
            records = listOf(
                rec("r1", 0, 480, itemId = "i1"),
                rec("r2", 480, 870, itemId = "i1"),
            ),
            cut = 870,
            tagsByRec = mapOf("r1" to listOf(tag), "r2" to listOf(tag2)),
        )

        assertEquals("健康维护", view.rows.first { it.id == "r1" }.tags.single().name)
        assertEquals("能量增益", view.rows.first { it.id == "r2" }.tags.single().name)
    }

    // ====================================================== 周条

    @Test
    fun `周条七格 从周一排到周日`() {
        val week = TimelineAssembler.week(day, day, viewingHasGap = false, gapDays = emptySet())

        assertEquals(7, week.size)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), week.map { it.weekdayLabel })
        assertEquals("2026-10-12", week.first().key)
        assertEquals("2026-10-18", week.last().key)
    }

    @Test
    fun `周条标出正在看的那天和今天`() {
        val week = TimelineAssembler.week(day, day, viewingHasGap = false, gapDays = emptySet())

        assertEquals(listOf("2026-10-14"), week.filter { it.isViewing }.map { it.key })
        assertEquals(listOf("2026-10-14"), week.filter { it.isToday }.map { it.key })
    }

    @Test
    fun `周条上未来的那几天被标成不可点`() {
        // 今天周三 → 周四下到下周日都是「未来」
        val week = TimelineAssembler.week(day, day, viewingHasGap = false, gapDays = emptySet())

        assertEquals(
            listOf("2026-10-15", "2026-10-16", "2026-10-17", "2026-10-18"),
            week.filter { it.isFuture }.map { it.key },
        )
    }

    @Test
    fun `正在看的那天的红点用实时值 不看历史查询`() {
        // ★ 这条分得细但很重要：今天刚打开、还没补密时，
        //   查库会说「没有空白」，而实际上补密后一定有。
        //   所以正在看的那天一律用实时值。
        val week = TimelineAssembler.week(
            dayKey = day,
            todayKey = day,
            viewingHasGap = true,
            gapDays = emptySet(), // ← 故意给一个说「没有空白」的历史查询结果
        )

        assertTrue(week.first { it.key == day }.hasGap)
    }

    @Test
    fun `别的日子有没有红点靠查库`() {
        val week = TimelineAssembler.week(
            dayKey = day,
            todayKey = day,
            viewingHasGap = false,
            gapDays = setOf("2026-10-12", "2026-10-13"),
        )

        assertEquals(
            listOf("2026-10-12", "2026-10-13"),
            week.filter { it.hasGap }.map { it.key },
        )
    }

    @Test
    fun `翻到上周时 周条跟着切到那一周`() {
        val lastWeek = "2026-10-07" // 上周三
        val week = TimelineAssembler.week(lastWeek, day, viewingHasGap = false, gapDays = emptySet())

        assertEquals("2026-10-05", week.first().key) // 上周一
        assertEquals("2026-10-07", week.first { it.isViewing }.key)
        // ⚠️ 上周里没有「今天」，也没有未来 —— 整周都可点
        assertTrue(week.none { it.isToday })
        assertTrue(week.none { it.isFuture })
    }
}
