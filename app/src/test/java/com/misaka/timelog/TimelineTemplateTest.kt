package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.TemplateSegmentEntity
import com.misaka.timelog.data.newId
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M2-C 的**模板懒结算** —— 端到端（真 Room、真外键、真刷新链）。
 *
 * ## 每条测试都在盯同一句话
 *
 * > **铺完之后，这一天还是完整覆盖 `0..cut` 吗？而且手动记过的一秒钟都没被改？**
 *
 * 这两句话就是模板这个功能的全部风险所在：
 * - 破了密铺 → 不报错、不崩，几个月后统计数字对不上
 * - 覆盖了手动记录 → 用户觉得「这 App 自己乱改我的东西」，直接不敢用了
 *
 * ## 为什么用**真的种子数据**（`seedCallback`）
 *
 * 因为内置那套「工作日」模板就是靠它种下去的，而且模板段**必须按事项名挂到真 id 上**
 * （种子事项的 id 是 UUID，不是名字）。这里再用一份自己造的模板，
 * 测的就只是那份复制品 —— 而生产种子写错的话，两个测试各自都"通过"。
 *
 * ## 时钟
 *
 * 全用 [FakeClock]。模板的每一件事都跟「现在几点」强相关
 * （铺到哪、锁定段是哪一段、跨天了没有），不固定时钟这条路径根本没法测。
 *
 * ⚠️ `2026-10-14` 是**周三**（模板指派到了），`2026-10-17` 是**周六**（没指派）。
 *    这两个日期是这一整个文件的基准，改之前先确认星期。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineTemplateTest {

    /** 周三 —— 内置模板指派到了这一天。 */
    private val wed = "2026-10-14"

    /** 周四 —— 也被指派。跨天「接着跑」用它。 */
    private val thu = "2026-10-15"

    /** 周六 —— 没指派。**跨天不铺、但也不退出**用它。 */
    private val sat = "2026-10-17"

    /** 周一 —— 休息日过完回到工作日，模板该自己接上。 */
    private val mon = "2026-10-19"

    /** 14:30。 */
    private val afternoon = 870

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository
    private lateinit var templateId: String

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            // ★ 跟生产同一份种子回调 —— 内置的「工作日」模板就是这么来的
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(wed, afternoon)
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
        templateId = db.templateDao().template()!!.id
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 辅助

    /** 一天里的一段：`[a, b)` 是谁的（`itemId = null` 就是空白）。 */
    private data class Seg(val a: Int, val b: Int, val itemId: String? = null)

    private suspend fun item(name: String): String =
        catalog.items().first { it.name == name }.id

    private fun row(day: String, a: Int, b: Int, itemId: String?) = RecordEntity(
        id = newId(),
        day = day,
        startMin = a,
        endMin = b,
        itemId = itemId,
        categoryIdSnap = null,
        source = if (itemId == null) RecordSource.GAP else RecordSource.MANUAL,
        chainId = null,
        noTemplate = false,
        note = "",
        updatedAt = 0L,
        deleted = false,
    )

    /** 把某天摆成指定的样子，中间/两头的空隙自动补成空白。 */
    private suspend fun lay(day: String, vararg segs: Seg, full: Int): List<String> {
        val dao = db.recordDao()
        val ids = ArrayList<String>()
        var cursor = 0
        segs.forEach { s ->
            if (s.a > cursor) dao.insert(row(day, cursor, s.a, null))
            val id = newId()
            dao.insert(row(day, s.a, s.b, s.itemId).copy(id = id))
            ids += id
            cursor = s.b
        }
        if (cursor < full) dao.insert(row(day, cursor, full, null))
        return ids
    }

    /** 这一天的样子压成 `"0-510 睡觉"` 这样一行行，方便一眼看出哪块是谁的。 */
    private suspend fun layout(day: String): List<String> {
        val names = catalog.items().associate { it.id to it.name }
        return timeline.dayRecords(day).map { r ->
            val who = when (r.source) {
                RecordSource.GAP -> "未记录"
                else -> names[r.itemId] ?: "?"
            }
            "${r.startMin}-${r.endMin ?: "现在"} $who" +
                (if (r.source == RecordSource.TEMPLATE) "(模板)" else "")
        }
    }

    private suspend fun assertDense(day: String, hint: String = "") {
        val problems = timeline.verify(day)
        assertTrue("$day 应该密铺，实际问题：$problems  $hint", problems.isEmpty())
    }

    /** 这一天的总覆盖时长 —— 密铺的话恒等于 cut。 */
    private suspend fun coverOf(day: String): Int =
        timeline.dayRecords(day).sumOf { (it.endMin ?: clock.now().minuteOfDay) - it.startMin }

    /**
     * 一次性走完整条刷新链（跟 ViewModel 里的顺序**逐字一致**：
     * 跨天守卫 → **跨天接续** → 补密 → 铺写）。
     *
     * ⚠️ `carryOver` 那一行不能省。它跟模板是**有交互**的：
     * 昨晚那条还是「进行中」的时候，它由谁收尾、今天该不该接着记 ——
     * 测试不照抄真实顺序，这类协作就会在两边各自的测试里都"通过"。
     */
    private suspend fun refresh(day: String) {
        timeline.syncTemplateDay()
        timeline.carryOver()
        timeline.ensureDense(day)
        timeline.applyTemplate(day)
    }

    private suspend fun segsOf(): List<TemplateSegmentEntity> =
        db.templateDao().segments(templateId)

    /** 改模板某一段记什么 —— 用来模拟「运行中你去改模板」。 */
    private suspend fun retargetSeg(startMin: Int, newItemId: String?) {
        val all = segsOf()
        db.templateDao().putSegments(
            all.map { if (it.startMin == startMin) it.copy(itemId = newItemId) else it }
        )
    }

    // ================================================================ 启用与铺写

    @Test
    fun `切到模板模式_今天到此刻的空白全被按模板铺上`() = runBlocking {
        refresh(wed) // 先把 [0,870) 补成空白
        assertEquals(listOf("0-870 未记录"), layout(wed))

        assertTrue(timeline.startTemplate().isOk)
        timeline.applyTemplate(wed)

        assertEquals(
            listOf(
                "0-510 睡觉(模板)",
                "510-540 洗脸洗漱(模板)",
                "540-720 打工(模板)",
                "720-810 午休(模板)",
                "810-现在 打工(模板)",
            ),
            layout(wed),
        )
    }

    @Test
    fun `铺完之后这一天依然密铺_总时长一分钟都不多不少`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        assertDense(wed)
        assertEquals("模板铺完不能改变这一天的总覆盖", afternoon, coverOf(wed))
    }

    @Test
    fun `未来不铺_只铺到此刻`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        // 870 之后一分钟都不许出现
        assertTrue(
            "模板不许预写未来",
            timeline.dayRecords(wed).all { (it.endMin ?: afternoon) <= afternoon },
        )
    }

    @Test
    fun `手动记过的那一段一秒钟都不被改`() = runBlocking {
        val book = item("看书")
        // 0-510 空白｜510-720 手动记的「看书」｜720-870 空白
        val ids = lay(wed, Seg(510, 720, book), full = afternoon)
        val before = timeline.dayRecords(wed).first { it.id == ids[0] }

        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val after = timeline.dayRecords(wed).first { it.id == ids[0] }
        assertEquals("时间一分不动", before.startMin, after.startMin)
        assertEquals(before.endMin, after.endMin)
        assertEquals("事项不许被改成模板的", book, after.itemId)
        assertEquals("来源不许被改成模板", RecordSource.MANUAL, after.source)
        assertDense(wed)
    }

    @Test
    fun `手动删掉模板铺的那一段之后_它不会被填回来`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val sleep = timeline.dayRecords(wed).first { it.startMin == 0 }
        assertEquals(RecordSource.TEMPLATE, sleep.source)
        assertTrue(timeline.deleteRecord(sleep.id).isOk)

        // 再刷新一遍 —— 模板绕开这段（noTemplate），不然用户看着就像「删不掉」
        refresh(wed)

        val again = timeline.dayRecords(wed).first { it.startMin == 0 }
        assertEquals(RecordSource.GAP, again.source)
        assertEquals(0, again.startMin)
        assertEquals(510, again.endMin)
        assertDense(wed)
    }

    @Test
    fun `时间往前走_新出现的那一段会被切开并接着铺`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)
        // 此刻锁在「810-1050 打工」这一段里

        // 走到 17:40（1060）—— 中间跨过了 1050 这个段边界
        clock.atMinute(1060)
        refresh(wed)

        assertEquals(
            listOf(
                "0-510 睡觉(模板)",
                "510-540 洗脸洗漱(模板)",
                "540-720 打工(模板)",
                "720-810 午休(模板)",
                "810-1050 打工(模板)",
                "1050-现在 开车通勤(模板)",
            ),
            layout(wed),
        )
        assertDense(wed)
        assertEquals(1060, coverOf(wed))
    }

    @Test
    fun `末尾那一段始终是进行中_不然就没有地方点退出了`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val live = timeline.liveRecord(wed)
        assertNotNull("模板在跑时末尾那段必须挂着「进行中」", live)
        assertEquals(RecordSource.TEMPLATE, live!!.source)
    }

    // ================================================================ 锁定段

    @Test
    fun `运行中改模板_正在执行的那一段先按旧的跑完`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)
        // 此刻锁定的是「810-1050 打工」

        // 你在这时把模板改了：810-1050 改成「兼职」
        retargetSeg(810, item("兼职"))

        // 走到 900（15:00）—— 还在锁定段里
        clock.atMinute(900)
        refresh(wed)

        val inLock = timeline.dayRecords(wed).first { it.startMin == 810 }
        assertEquals("锁定段必须还按旧模板跑", item("打工"), inLock.itemId)
    }

    @Test
    fun `锁定段跑完之后_才改用新模板`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)
        retargetSeg(810, item("兼职"))

        // 走到 17:40（1060）—— 锁定段在 1050 就结束了
        clock.atMinute(1060)
        refresh(wed)

        assertEquals(
            listOf(
                "0-510 睡觉(模板)",
                "510-540 洗脸洗漱(模板)",
                "540-720 打工(模板)",
                "720-810 午休(模板)",
                "810-1050 打工(模板)",
                "1050-现在 开车通勤(模板)",
            ),
            layout(wed),
        )
        assertNull("跑完之后锁定段该被清掉", db.templateDao().run()!!.lockedSegSnapshot)
        assertDense(wed)
    }

    // ================================================================ 退出

    @Test
    fun `点结束并亲手选别的事项_就是退出模板`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        assertTrue(timeline.pick(item("看书"), wed).isOk)

        assertFalse("亲手选别的事项必须退出模板", timeline.templateState().on)
        // 已经按模板记下的那些**留着**
        assertTrue(layout(wed).contains("0-510 睡觉(模板)"))
        assertDense(wed)
    }

    @Test
    fun `点结束又选回同一件事_不算退出`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        // 末尾那段正是模板的「打工」
        timeline.pick(item("打工"), wed)

        assertTrue("说的得是「别的事项」才算退出", timeline.templateState().on)
    }

    @Test
    fun `退出之后新出现的空白不再自动填_但旧的原样留着`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)
        timeline.pick(item("看书"), wed) // 退出

        clock.atMinute(1100)
        refresh(wed)

        val texts = layout(wed)
        assertTrue(texts.contains("0-510 睡觉(模板)"))
        // 1060 之后应该是「看书」在记 + 一段未记录，绝不该冒出「开车通勤(模板)」
        assertFalse("退出后不许再铺模板", texts.any { it.contains("开车通勤(模板)") })
        assertDense(wed)
    }

    @Test
    fun `已结束的模板段不会被反复重切`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val sleep = timeline.dayRecords(wed).first { it.startMin == 0 }
        val countBefore = timeline.dayRecords(wed).size

        clock.atMinute(1000)
        refresh(wed)

        // 这一轮里 810 那一段还在同一个模板段内，只是变长了 —— 不该被切成两条
        assertEquals("不该越刷越多条", countBefore, timeline.dayRecords(wed).size)
        assertEquals(
            "已经切对了的段不该被反复重写（否则云同步天天白跑）",
            sleep.updatedAt,
            db.recordDao().byId(sleep.id)!!.updatedAt,
        )
    }

    // ================================================================ 启动守卫

    @Test
    fun `今天没被指派时_启用会被拦下并说明原因`() = runBlocking {
        clock.onDay(sat)

        val out = timeline.startTemplate()

        assertTrue(out is Outcome.Blocked)
        assertTrue(
            "理由要说清是「哪一天没指派」",
            (out as Outcome.Blocked).message.contains("周六"),
        )
        assertFalse(timeline.templateState().on)
    }

    @Test
    fun `跨天进入没指派的一天_模板保持开启但今天不铺`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        assertTrue(timeline.templateState().on)

        clock.onDay(sat).atMinute(600) // 周六 10:00
        refresh(sat)

        assertTrue(
            "模板**没有被退出** —— 休息日悄悄关掉的话，周一你还得记得手动再开一次",
            timeline.templateState().on,
        )
        assertEquals("跑的那一天要跟着挪过来", sat, db.templateDao().run()!!.day)
        assertEquals(
            "休息日只按空白记，模板一段都不许铺",
            listOf("0-600 未记录"),
            layout(sat),
        )
        assertDense(sat)
    }

    @Test
    fun `休息日之后回到工作日_模板自己接着铺_不用重新启用`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()

        // 先跨到周六（没指派）
        clock.onDay(sat).atMinute(600)
        refresh(sat)
        assertEquals(listOf("0-600 未记录"), layout(sat))

        // 再过一天到周一
        clock.onDay(mon).atMinute(600)
        refresh(mon)

        assertTrue("过了个周末，模板该自己接上", timeline.templateState().on)
        assertEquals(
            listOf(
                "0-510 睡觉(模板)",
                "510-540 洗脸洗漱(模板)",
                "540-现在 打工(模板)", // 末条保持「进行中」
            ),
            layout(mon),
        )
        assertDense(mon)
    }

    @Test
    fun `跨天进入被指派的一天_模板接着跑`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()

        clock.onDay(thu)
        timeline.syncTemplateDay()

        assertTrue(timeline.templateState().on)
        assertEquals("跑的那一天要跟着挪过来", thu, db.templateDao().run()!!.day)
    }

    @Test
    fun `跨天时锁定段要清掉_否则今天一整天都在拿昨天的旧段铺`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)
        assertNotNull("构造前提：启用时锁住了「此刻所在的那一段」", db.templateDao().run()!!.lockedSegSnapshot)

        // 跨天到周四，此刻才 00:10 —— cut 很小，靠 `cut > lock.endMin` 根本判不出过期
        clock.onDay(thu).atMinute(10)
        refresh(thu)

        assertNull(
            "锁定段属于【启用那一天】，跨天必然跑完了 —— 不清的话今天读的是昨天的旧段",
            db.templateDao().run()!!.lockedSegSnapshot,
        )
    }

    @Test
    fun `新建的那一天会被模板从头铺上`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()

        // 一觉睡到第二天 09:05 —— 周四也被指派，模板自动接着跑
        // （09:05 让最后一块落在「打工」里，而不是正好压在段边界上）
        clock.onDay(thu).atMinute(545)
        refresh(thu)

        assertEquals(
            listOf(
                "0-510 睡觉(模板)",
                "510-540 洗脸洗漱(模板)",
                "540-现在 打工(模板)",
            ),
            layout(thu),
        )
        assertDense(thu)
    }

    // ================================================================ 幽灵记录

    @Test
    fun `模板段指的事项被删掉时_那一段留成空白而不是幽灵记录`() = runBlocking {
        refresh(wed)
        // 把「睡觉」这个事项删掉 → 外键 SET_NULL 会把模板段上的 itemId 置空
        assertTrue(catalog.deleteItemDeep(item("睡觉")).isOk)
        assertNull(
            "模板段的 itemId 必须被置空（外键 SET_NULL）",
            segsOf().first { it.startMin == 0 }.itemId,
        )

        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val first = timeline.dayRecords(wed).first { it.startMin == 0 }
        assertEquals("填不上就必须落回空白", RecordSource.GAP, first.source)
        assertNull(first.itemId)
        assertEquals(510, first.endMin)
        // 后面那些还能填的照填
        assertTrue(layout(wed).contains("510-540 洗脸洗漱(模板)"))
        assertDense(wed)
    }

    @Test
    fun `模板段全都填不上时_这一天保持原样`() = runBlocking {
        refresh(wed)
        segsOf().forEach { s -> retargetSeg(s.startMin, null) }

        timeline.startTemplate()
        timeline.applyTemplate(wed)

        assertEquals(listOf("0-870 未记录"), layout(wed))
        assertDense(wed)
    }

    // ================================================================ 与其它操作互不打架

    @Test
    fun `变更事项之后的段_模板不再重切它`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val sleep = timeline.dayRecords(wed).first { it.startMin == 0 }
        assertTrue(timeline.changeItem(sleep.id, item("看书")).isOk)

        clock.atMinute(1000)
        refresh(wed)

        val after = db.recordDao().byId(sleep.id)!!
        assertEquals(RecordSource.MANUAL, after.source)
        assertEquals(item("看书"), after.itemId)
        assertEquals("时间也不该被重切", 0, after.startMin)
        assertEquals(510, after.endMin)
    }

    @Test
    fun `补记过的段_模板不会再拿它当空白`() = runBlocking {
        // 摆成三段空白：0-510｜510-720｜720-870
        lay(wed, Seg(0, 510), Seg(720, 870), full = afternoon)
        val middle = timeline.dayRecords(wed).first { it.startMin == 510 && it.endMin == 720 }

        assertTrue(timeline.fillGap(middle.id, item("看书")).isOk)

        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val after = db.recordDao().byId(middle.id)!!
        assertEquals("补记就是「我记的」，模板不许改它", item("看书"), after.itemId)
        assertEquals(RecordSource.MANUAL, after.source)
        assertEquals(510, after.startMin)
        assertEquals(720, after.endMin)
        assertTrue(layout(wed).contains("510-720 看书"))
        assertDense(wed)
    }

    @Test
    fun `删记录之后的撤销_会把模板标记一起还原`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)

        val sleep = timeline.dayRecords(wed).first { it.startMin == 0 }
        val token = (timeline.deleteRecord(sleep.id) as Outcome.Ok).value

        assertTrue(timeline.undo(token).isOk)

        val back = db.recordDao().byId(sleep.id)!!
        assertEquals("撤销要把 noTemplate 也退回去，否则模板再也填不上它", false, back.noTemplate)
        assertEquals(RecordSource.TEMPLATE, back.source)
        assertDense(wed)
    }

    @Test
    fun `关掉模板之后重开_不会把已经填过的又填一遍`() = runBlocking {
        refresh(wed)
        timeline.startTemplate()
        timeline.applyTemplate(wed)
        val before = layout(wed)

        timeline.stopTemplate()
        refresh(wed)
        timeline.startTemplate()
        refresh(wed)

        assertEquals(before, layout(wed))
        assertDense(wed)
    }
}
