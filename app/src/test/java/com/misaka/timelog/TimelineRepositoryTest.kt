package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.valueOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 时间线仓库的测试 —— 密铺不变式的**看门人**。
 *
 * ## 为什么这些测试比代码本身重要
 *
 * 密铺破了不会报错、不会崩，只会在几个月后让统计数字对不上。
 * 所以「我改完感觉没问题」在这件事上**完全不算数** —— 必须有一条断言盯着它。
 *
 * ## Robolectric 是干什么的
 *
 * Room 的内存数据库需要一个安卓 `Context`（它的 SQLite 实现是安卓系统提供的），
 * 纯 JVM 跑会报 `not mocked`。Robolectric 在 JVM 上模拟出一套安卓环境，
 * 于是这些测试**不需要插真机、不需要模拟器**就能跑。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineRepositoryTest {

    private val today = "2026-10-14"
    private val yesterday = "2026-10-13"
    private val tomorrow = "2026-10-15"

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            // 测试里就在主线程同步调，不需要切线程 —— 让断言读起来简单
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(today, 870) // 今天 14:30
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun seedItem(name: String = "打工"): String {
        val cat = (catalog.addCategory("工作", "#F59E0B") as Outcome.Ok).value
        return (catalog.addItem(cat.id, name, "#F59E0B") as Outcome.Ok).value.id
    }

    /** 断言这天密铺 —— 这一整套设计的存在意义就在这一句上。 */
    private suspend fun assertDense(day: String, hint: String = "") {
        val problems = timeline.verify(day)
        assertTrue("$day 应该密铺，实际问题：$problems  $hint", problems.isEmpty())
    }

    // ======================================================== ★ 密铺维护

    @Test
    fun `空白的今天_补出一整段空白`() = runBlocking {
        val added = timeline.ensureDense(today)

        assertEquals(1, added)
        val list = timeline.dayRecords(today)
        assertEquals(1, list.size)
        assertEquals(0, list[0].startMin)
        assertEquals(870, list[0].endMin)
        assertEquals(RecordSource.GAP, list[0].source)
        assertNull("空白段不挂事项", list[0].itemId)
        assertDense(today)
    }

    @Test
    fun `密铺是幂等的_重复补不会越补越多`() = runBlocking {
        timeline.ensureDense(today)
        val first = timeline.dayRecords(today).size

        val added = timeline.ensureDense(today)

        assertEquals(0, added)
        assertEquals(first, timeline.dayRecords(today).size)
    }

    @Test
    fun `补空白绝不覆盖已经记过的`() = runBlocking {
        val itemId = seedItem()
        timeline.pick(itemId, today)

        timeline.ensureDense(today)

        val list = timeline.dayRecords(today)
        val live = list.last()
        assertEquals("正在记的那条不能被动", itemId, live.itemId)
        assertNull(live.endMin)
        assertDense(today)
    }

    @Test
    fun `尾部空白是延长_不是每次刷新都插一条`() = runBlocking {
        // 14:00 时补一次
        clock.atMinute(840)
        timeline.ensureDense(today)
        assertEquals(1, timeline.dayRecords(today).size)

        // 半小时后再刷新
        clock.atMinute(870)
        timeline.ensureDense(today)

        val list = timeline.dayRecords(today)
        assertEquals("尾部空白应该被延长，而不是新插一条", 1, list.size)
        assertEquals(870, list[0].endMin)
    }

    @Test
    fun `未来的一天一条都不铺`() = runBlocking {
        val added = timeline.ensureDense(tomorrow)

        assertEquals(0, added)
        assertTrue(timeline.dayRecords(tomorrow).isEmpty())
    }

    @Test
    fun `过去的一天补满 1440 分钟`() = runBlocking {
        timeline.ensureDense(yesterday)

        val list = timeline.dayRecords(yesterday)
        assertEquals(1, list.size)
        assertEquals(0, list[0].startMin)
        assertEquals(1440, list[0].endMin)
        assertDense(yesterday)
    }

    @Test
    fun `中间挖掉的空白会被补回来`() = runBlocking {
        // 造一条只有中间一段的日子
        val itemId = seedItem()
        timeline.pick(itemId, yesterday) // 过去不能 pick，应该被挡
        timeline.ensureDense(yesterday)

        assertDense(yesterday)
    }

    // ======================================================== ★ pick（点事项）

    @Test
    fun `点事项_今天能开始记`() = runBlocking {
        val itemId = seedItem()

        val result = timeline.pick(itemId, today)

        assertTrue(result.isOk)
        val live = timeline.liveRecord(today)
        assertNotNull(live)
        assertEquals(itemId, live!!.itemId)
        assertEquals(870, live.startMin)
        assertNull("进行中的 endMin 必须是 null", live.endMin)
        assertDense(today)
    }

    @Test
    fun `点事项_自动补上前面那段空白`() = runBlocking {
        val itemId = seedItem()

        timeline.pick(itemId, today)

        val list = timeline.dayRecords(today)
        assertEquals(2, list.size)
        assertEquals(RecordSource.GAP, list[0].source)
        assertEquals(0, list[0].startMin)
        assertEquals(870, list[0].endMin)
    }

    /**
     * ★★ **第 19 轮那个 bug 的回归测试。**
     *
     * 症状：在 14 号翻到 13 号点一下事项，13 号凭空长出一条从 9:37 铺到 24:00 的记录。
     * 根因：「从现在开始记」这个动作，代码拿「今天的第几分钟」当起点，
     * 但没有先核对「你正在看的这一天是不是今天」。
     */
    @Test
    fun `点事项_过去的一天必须被挡住`() = runBlocking {
        val itemId = seedItem()

        val result = timeline.pick(itemId, yesterday)

        assertTrue("过去的一天不该能「从现在开始记」", result is Outcome.Blocked)
        assertTrue("而且必须一条记录都不许产生", timeline.dayRecords(yesterday).isEmpty())
    }

    @Test
    fun `点事项_未来的一天也必须被挡住`() = runBlocking {
        val itemId = seedItem()

        val result = timeline.pick(itemId, tomorrow)

        assertTrue(result is Outcome.Blocked)
        assertTrue(timeline.dayRecords(tomorrow).isEmpty())
    }

    @Test
    fun `点事项_切换时前一条被就地封口（不弹窗）`() = runBlocking {
        val a = seedItem("打工")
        val b = seedItem("看书")

        timeline.pick(a, today)
        clock.atMinute(900)
        timeline.pick(b, today)

        val list = timeline.dayRecords(today)
        val prev = list.first { it.itemId == a }
        assertEquals("前一条应该被封口到切换的那一刻", 900, prev.endMin)

        val live = timeline.liveRecord(today)
        assertEquals(b, live!!.itemId)
        assertDense(today)
    }

    @Test
    fun `点事项_重复点同一个不会插零长度记录`() = runBlocking {
        val itemId = seedItem()

        timeline.pick(itemId, today)
        val countAfterFirst = timeline.dayRecords(today).size
        val result = timeline.pick(itemId, today)

        assertEquals("已经记着它了，不该再插一条", countAfterFirst, timeline.dayRecords(today).size)
        assertEquals(Outcome.Ok(false), result)
    }

    @Test
    fun `结束_把进行中的封口到此刻`() = runBlocking {
        val itemId = seedItem()
        timeline.pick(itemId, today)
        clock.atMinute(930)

        val result = timeline.closeLive(today)

        assertTrue(result.isOk)
        assertNull("结束之后不该还有进行中的记录", timeline.liveRecord(today))
        assertDense(today)
    }

    @Test
    fun `结束_没有正在记的事时给人话提示`() = runBlocking {
        val result = timeline.closeLive(today)

        assertTrue(result is Outcome.Blocked)
        assertTrue((result as Outcome.Blocked).message.isNotBlank())
    }

    // ==================================================== ★★ 删除 = 原地擦白

    /**
     * ★★ **最重要的一条**：删除记录**不是删掉那一行**。
     *
     * 如果真删了，这段时间在密铺里就成了一个洞。而「洞」是这个设计里
     * 不该存在的东西 —— 空白必须是**一条看得见的记录**，用户才能点它补记。
     */
    @Test
    fun `删记录_不是真删掉那一行_而是把它擦成空白`() = runBlocking {
        val work = seedItem("打工")
        val read = seedItem("看书")

        // 这一条特地让工作段前面**不是**空白 —— 否则它会被并进前面的空白里，
        // 就看不到「行还在」了（合并本身是另一条测试的事）
        clock.atMinute(0)
        timeline.pick(work, today)
        clock.atMinute(540)
        timeline.pick(read, today)

        val workRec = timeline.dayRecords(today).first { it.itemId == work }
        timeline.deleteRecord(workRec.id)

        val row = db.recordDao().byId(workRec.id)
        assertNotNull("行必须还在 —— 真删掉会在密铺里留一个洞", row)
        assertEquals(RecordSource.GAP, row!!.source)
        assertNull(row.itemId)
        assertTrue("必须打上标记，否则模板会把它填回来（用户看着像删不掉）", row.noTemplate)
        assertDense(today)
    }

    @Test
    fun `删记录_之后依然密铺`() = runBlocking {
        val work = seedItem("打工")
        timeline.pick(work, today)
        clock.atMinute(960)
        timeline.pick(seedItem("看书"), today)

        val toDelete = timeline.dayRecords(today).first { it.itemId == work }
        timeline.deleteRecord(toDelete.id)

        assertDense(today, hint = "删完一条之后")

        // ⚠️ 不能断言「那条记录的 id 还在」—— 它会被并进前面那段空白里。
        //    要验的是「那段时间仍然被一条空白盖着」，也就是没留洞。
        val covering = timeline.dayRecords(today)
            .first { it.startMin <= 870 && (it.endMin ?: 1440) >= 960 }
        assertEquals("被删的那段应该变成空白", RecordSource.GAP, covering.source)
    }

    @Test
    fun `删记录_前后都是空白时合并成一条`() = runBlocking {
        val work = seedItem("打工")
        timeline.pick(work, today)
        clock.atMinute(960)
        timeline.pick(seedItem("看书"), today)

        timeline.deleteRecord(timeline.dayRecords(today).first { it.itemId == work }.id)
        timeline.deleteRecord(timeline.dayRecords(today).first { it.itemId != null }.id)

        val gaps = timeline.dayRecords(today).filter { it.source == RecordSource.GAP }
        assertEquals("挨着的空白应该并成一段，不然时间线上会并排出现一串一模一样的「▨ 未记录」", 1, gaps.size)
        assertEquals(0, gaps[0].startMin)
        assertEquals(timeline.cutOf(today), gaps[0].endMin)
        assertDense(today)
    }

    @Test
    fun `删记录_本来就是空白的会被拒绝`() = runBlocking {
        timeline.ensureDense(today)
        val gapId = timeline.dayRecords(today).first().id

        val result = timeline.deleteRecord(gapId)

        assertTrue(result is Outcome.Blocked)
    }

    // ============================================================== 补记

    @Test
    fun `补记_把一段空白填成事项`() = runBlocking {
        val itemId = seedItem()
        timeline.ensureDense(today)
        val gapId = timeline.dayRecords(today).first().id

        val result = timeline.fillGap(gapId, itemId)

        assertTrue(result.isOk)
        val row = db.recordDao().byId(gapId)!!
        assertEquals(RecordSource.MANUAL, row.source)
        assertEquals(itemId, row.itemId)
        assertNotNull("补记也要留下「当时分类快照」", row.categoryIdSnap)
        assertDense(today)
    }

    @Test
    fun `补记_不能补一段已经有内容的`() = runBlocking {
        val itemId = seedItem()
        timeline.pick(itemId, today)
        val liveId = timeline.liveRecord(today)!!.id

        val result = timeline.fillGap(liveId, itemId)

        assertTrue(result is Outcome.Blocked)
    }

    // ==================================================== ★ 删事项 → 历史擦白

    @Test
    fun `删事项_历史记录全部变成空白块`() = runBlocking {
        val itemId = seedItem()
        clock.atMinute(840)
        timeline.pick(itemId, today)
        clock.atMinute(870)
        timeline.closeLive(today)

        val erased = timeline.eraseItemFromHistory(itemId)

        assertEquals(1, erased.valueOrNull())

        // ⚠️ 同样不能断言「原来那条还在」—— 它会和前面的空白合并。
        //    要验的是那段时间被一条空白盖着，且这条空白免疫模板。
        val erasedGap = timeline.dayRecords(today)
            .first { it.startMin <= 840 && (it.endMin ?: 1440) >= 870 }
        assertEquals(RecordSource.GAP, erasedGap.source)
        assertNull(erasedGap.itemId)
        assertTrue(erasedGap.noTemplate)
        assertDense(today)
    }

    // ============================================================== 默认标签

    @Test
    fun `点事项_自动带上事项的默认标签`() = runBlocking {
        val bucket = (catalog.addBucket("💼", "工作状态") as Outcome.Ok).value
        val tag = (catalog.addTag(bucket.id, "加班", "#EF4444", "超出常规工时") as Outcome.Ok).value
        val itemId = seedItem()
        catalog.setDefaultTags(itemId, listOf(tag.id))

        timeline.pick(itemId, today)

        val live = timeline.liveRecord(today)!!
        assertEquals(listOf(tag.id), db.tagDao().tagIdsOfRecord(live.id))
    }

    // ============================================================== 综合回归

    @Test
    fun `折腾一整天之后_依然是密铺的`() = runBlocking {
        val work = seedItem("打工")
        val read = seedItem("看书")

        // 9:00 开始打工
        clock.atMinute(540)
        timeline.pick(work, today)
        assertDense(today)

        // 12:00 换看书
        clock.atMinute(720)
        timeline.pick(read, today)
        assertDense(today)

        // 13:00 结束
        clock.atMinute(780)
        timeline.closeLive(today)
        assertDense(today)

        // 14:30 又点回打工
        clock.atMinute(870)
        timeline.pick(work, today)
        assertDense(today)

        // 把「看书」那段删掉
        val readRec = timeline.dayRecords(today).first { it.itemId == read }
        timeline.deleteRecord(readRec.id)
        assertDense(today, hint = "删完之后")

        // 补记回去
        val gap = timeline.dayRecords(today).first { it.source == RecordSource.GAP && it.id == readRec.id }
        timeline.fillGap(gap.id, read)
        assertDense(today, hint = "补记之后")

        // 总和必须永远是 1440（此刻之前 = 870）
        val covered = timeline.dayRecords(today)
            .sumOf { (it.endMin ?: 870) - it.startMin }
        assertEquals("一天恒 = 1440 分钟，此刻之前必须铺满 870", 870, covered)
    }
}
