package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.ui.PickerMode
import com.misaka.timelog.ui.Sheet
import com.misaka.timelog.ui.TimelineViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ViewModel 层的**流程**测试 —— 点一下之后，到底发生了什么。
 *
 * ## 这一层测的是「接线」，不是「规则」
 *
 * 规则（密铺、让位、守卫）已经在 `TimelineEditTest` 里穷举过了。
 * 这里管的是另一类问题，它们**全都不会崩、也不会报错**：
 *
 * - 面板关了之后，上一次的操作模式还挂着 → 下一次正常「开始记」被当成「改某条记录」
 *   （★ 原型在这里栽过，`closePicker()` 里专门写了一句复位，还留了注释）
 * - 「结束」按钮在不是今天的页面上也能点 → 昨天凭空长出一条记录
 * - 翻页之后面板还开着，而它指向的那条记录已经不在眼前
 *
 * 这些错误的共同点是：**用户会一脸懵，而日志里什么都没有**。
 * 所以得有一条断言盯着。
 *
 * ## 为什么要 `waitUntil` 轮询
 *
 * ViewModel 的操作都是 `viewModelScope.launch { … }` 的（界面不等人，点了就返回）。
 * 而 Room 的查询会真的切到后台线程。所以测试得等一会儿 ——
 * 不是「代码慢」，是这套设计**本来就异步**。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineViewModelTest {

    private val today = "2026-10-14"
    private val yesterday = "2026-10-13"

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository
    private lateinit var vm: TimelineViewModel

    private var catId: String? = null
    private val messages = mutableListOf<String>()
    private var collector: Job? = null

    @Before
    fun setUp() {
        // ViewModel 往 Main 上 launch —— 测试里把它换成「立刻执行」的那个，
        // 否则每条断言前都得等 Looper 转一圈
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(today, 870)
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
        vm = TimelineViewModel(timeline, catalog, clock)

        // 提示消息是个 SharedFlow，没有订阅者时会被丢掉 —— 先挂一个收集器
        collector = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.messages.collect { messages += it }
        }
    }

    @After
    fun tearDown() {
        collector?.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun item(name: String): String {
        val cid = catId ?: ((catalog.addCategory("工作", "#F59E0B") as Outcome.Ok).value.id)
            .also { catId = it }
        return (catalog.addItem(cid, name, "#F59E0B") as Outcome.Ok).value.id
    }

    /** 等某个条件成立。超时就直接失败 —— 总比断言读到一个还没到的状态强。 */
    private fun waitUntil(what: String = "", timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(8)
        }
        throw AssertionError("等待超时：${what.ifEmpty { cond.toString() }}")
    }

    private fun pickerMode(): PickerMode? = (vm.sheet as? Sheet.Picker)?.mode

    // ======================================================== 面板开关

    @Test
    fun `选事项面板关掉之后_模式不会残留`() = runBlocking {
        // ★ 这个坑原型踩过：关了面板还带着上次的「变更事项」，
        //   于是下一次正常「开始记」被当成「改某条记录」——
        //   表现是「点了一个事项，时间线上什么也没多出来」。
        vm.openPickerForNow()
        assertEquals(PickerMode.StartNew, pickerMode())

        vm.closeSheets()
        assertTrue(vm.sheet is Sheet.None)

        vm.openPickerForNow()
        assertEquals("重新打开必须是全新的 StartNew", PickerMode.StartNew, pickerMode())
    }

    @Test
    fun `变更事项和补记走的是同一张网格但模式不同`() = runBlocking {
        val a = item("打工")
        vm.refresh()
        waitUntil("等第一屏数据") { !vm.loading && vm.rows.isNotEmpty() }
        val gap = vm.rows.first { it.isGap }

        vm.startFillGap(gap)
        assertEquals(PickerMode.FillGap, pickerMode())

        vm.closeSheets()
        assertTrue(vm.sheet is Sheet.None)
        assertEquals("关掉之后不该还剩着上一个模式", null, pickerMode())

        // 换一个入口进来，模式必须是新的那个
        timeline.ensureDense(today)
        vm.openPickerForNow()
        assertEquals(PickerMode.StartNew, pickerMode())
        assertNotNull(a)
    }

    @Test
    fun `翻页会把打开的面板关掉`() = runBlocking {
        // 面板是针对「某一天的某一条记录」打开的 —— 翻页之后它指向的东西已经不在眼前了
        vm.openPickerForNow()
        assertTrue(vm.sheet is Sheet.Picker)

        vm.goPrev()

        assertTrue(vm.sheet is Sheet.None)
    }

    @Test
    fun `过去的一天点开始时_被拦下并说明原因`() = runBlocking {
        vm.go(yesterday)
        waitUntil("等昨天的数据") { !vm.loading && vm.viewing == yesterday }

        vm.openPickerForNow()

        assertTrue("过去的一天不该弹出选事项面板", vm.sheet is Sheet.None)
        waitUntil("等提示条") { messages.isNotEmpty() }
        assertTrue("提示要说清为什么：${messages.last()}", messages.last().contains("过去"))
    }

    // ======================================================== 开始记 / 结束

    @Test
    fun `选一个事项之后_底部就变成正在记它`() = runBlocking {
        val a = item("打工")
        vm.openPickerForNow()

        vm.pick(a)

        waitUntil("等底部状态条跟上") { vm.liveRow != null }
        assertEquals("打工", vm.liveRow?.name)
        assertEquals(870, vm.liveRow?.startMin)
        assertTrue("选完面板要自动关掉", vm.sheet is Sheet.None)
    }

    @Test
    fun `记着一件事的时候再选另一件_前一件在此刻收尾`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        vm.openPickerForNow()
        vm.pick(a)
        waitUntil { vm.liveRow?.name == "打工" }

        clock.atMinute(900)
        vm.refresh()
        waitUntil("等时间推进") { vm.liveRow?.durationMin == 30 }

        vm.openPickerForNow()
        vm.pick(b)

        waitUntil("等切到「学习」") { vm.liveRow?.name == "学习" }
        // 「打工」被收尾成 870→900
        val done = vm.rows.first { it.name == "打工" }
        assertEquals(870, done.startMin)
        assertEquals(900, done.endMin)
        assertTrue("提示要说清结束了什么", messages.any { it.contains("已结束") })
    }

    // ======================================================== 补记

    @Test
    fun `补记_把一段空白填成一件事`() = runBlocking {
        val a = item("打工")
        vm.refresh()
        waitUntil("等第一屏数据") { !vm.loading && vm.rows.isNotEmpty() }
        assertTrue("今天从空开始，第一眼该是一整段空白", vm.rows.single().isGap)

        val gap = vm.rows.single()
        vm.openMenu(gap)
        assertTrue(vm.sheet is Sheet.Menu)

        vm.startFillGap(gap)
        assertEquals(PickerMode.FillGap, pickerMode())

        vm.pick(a)

        waitUntil("等空白被填掉") { vm.rows.none { it.isGap } }
        assertEquals("填完之后不该再有任何「未记录」", 0, vm.gapMin)
        assertEquals(870, vm.recordedMin)
    }

    // ======================================================== 改时间

    @Test
    fun `把一条记录改短_空出来的那段变成未记录`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        vm.openPickerForNow()
        vm.pick(a)
        waitUntil { vm.liveRow?.name == "打工" }

        clock.atMinute(900)
        vm.openPickerForNow()
        vm.pick(b)
        waitUntil("等「学习」接上") { vm.liveRow?.name == "学习" }

        // 现在「打工」是 870→900（30 分钟）
        val rowA = vm.rows.first { it.name == "打工" }
        assertEquals(30, rowA.durationMin)

        vm.openTimeEdit(rowA)
        assertNotNull("改时间面板该开出来", vm.timeEdit)

        vm.nudgeTime('b', -10) // 900 → 890
        vm.saveTime()

        waitUntil("等「打工」缩到 20 分钟") { vm.rows.any { it.name == "打工" && it.durationMin == 20 } }
        assertTrue("空出来的 10 分钟要变成「未记录」，不能凭空消失", vm.gapMin >= 10)
    }

    @Test
    fun `空白卡片点「修改时间」会被拦下并给建议`() = runBlocking {
        vm.refresh()
        waitUntil("等第一屏数据") { !vm.loading && vm.rows.isNotEmpty() }
        val gap = vm.rows.single()

        vm.openTimeEdit(gap)

        assertTrue("空白不该开出改时间面板", vm.sheet is Sheet.None)
        waitUntil("等提示条") { messages.isNotEmpty() }
        assertTrue("要告诉用户该走哪条路：${messages.last()}", messages.last().contains("补记"))
    }

    // ======================================================== 删除 + 撤销

    @Test
    fun `删除之后出现撤销条_点一下就回来`() = runBlocking {
        val a = item("打工")
        vm.openPickerForNow()
        vm.pick(a)
        waitUntil { vm.liveRow?.name == "打工" }
        clock.atMinute(900)
        vm.refresh()
        waitUntil("等它长出 30 分钟") { vm.liveRow?.durationMin == 30 }

        val row = vm.rows.first { !it.isGap }
        assertEquals(30, row.durationMin)
        vm.delete(row)

        waitUntil("等撤销条出现") { vm.undo != null }
        assertTrue(
            "撤销条上要写清删了什么：${vm.undo!!.token.label}",
            vm.undo!!.token.label.contains("打工"),
        )

        // 那 30 分钟变成未记录 —— 未记录从 870 涨到 900，一分钟都没少
        waitUntil("等它变成未记录") { vm.gapMin == 900 }
        assertTrue(vm.rows.all { it.isGap })
        assertEquals(0, vm.recordedMin)

        vm.doUndo()

        waitUntil("等撤销生效") { vm.liveRow?.name == "打工" }
        assertEquals("撤销之后那 30 分钟又变回「打工」", 870, vm.gapMin)
        assertEquals(30, vm.recordedMin)
        assertEquals(null, vm.undo)
    }

    @Test
    fun `撤销条点过之后就没了_不能撤两次`() = runBlocking {
        val a = item("打工")
        vm.openPickerForNow()
        vm.pick(a)
        waitUntil { vm.liveRow?.name == "打工" }
        clock.atMinute(900)
        vm.refresh()
        waitUntil { vm.liveRow?.durationMin == 30 }

        vm.delete(vm.rows.first { !it.isGap })
        waitUntil { vm.undo != null }
        vm.doUndo()
        waitUntil { vm.undo == null }

        vm.doUndo() // 再点一次 —— 什么都不该发生，更不能崩

        waitUntil("应该还是那条「打工」") { vm.liveRow?.name == "打工" }
        assertEquals("时间线上那 870 分钟的空白该在（跟撤销无关）", 870, vm.gapMin)
        assertEquals(30, vm.recordedMin)
    }

    // ======================================================== 合并 / 拆分

    @Test
    fun `把两段合并之后_时长合起来且不产生空隙`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        vm.openPickerForNow()
        vm.pick(a)
        waitUntil { vm.liveRow?.name == "打工" }

        clock.atMinute(900)
        vm.openPickerForNow()
        vm.pick(b)
        waitUntil { vm.liveRow?.name == "学习" }

        val before = vm.recordedMin
        val rowA = vm.rows.first { it.name == "打工" }

        // 往「更晚」的方向并 —— 也就是跟「学习」合并
        vm.mergeWith(rowA, dir = -1)

        waitUntil("等合并生效") { vm.rows.none { it.name == "学习" } }
        val merged = vm.rows.first { it.name == "打工" }
        assertEquals(870, merged.startMin)
        assertEquals("合并是把两段时长加起来，不该多也不该少", before, vm.recordedMin)
        assertEquals("上午那 870 分钟的空白跟这次合并无关，不该被吃掉", 870, vm.gapMin)
    }

    @Test
    fun `拆一条记录_变成两段等长`() = runBlocking {
        val a = item("打工")
        vm.openPickerForNow()
        vm.pick(a)
        waitUntil { vm.liveRow?.name == "打工" }

        clock.atMinute(900)
        vm.refresh()
        waitUntil { vm.liveRow?.durationMin == 30 }

        vm.split(vm.rows.first { it.name == "打工" })

        waitUntil("等拆成两段") { vm.rows.count { it.name == "打工" } == 2 }
        val two = vm.rows.filter { it.name == "打工" }
        assertEquals(15, two[0].durationMin)
        assertEquals(15, two[1].durationMin)
        assertEquals("拆分不该改变总时长", 30, vm.recordedMin)
    }
}
