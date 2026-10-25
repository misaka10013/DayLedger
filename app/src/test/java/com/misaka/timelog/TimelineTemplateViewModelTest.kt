package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 模板在 **ViewModel 那一层**的接线 —— 只管一件事：
 *
 * > **打开 App 的时候，那三件事是不是按顺序都做了？**
 *
 * 顺序是 `syncTemplateDay()` → `ensureDense()` → `applyTemplate()`，一步不能少、
 * 一步不能换位。而它坏掉的样子**极其安静**：界面正常、不报错、不崩，
 * 只是模板不铺（或者铺出来跟空白打架）。
 *
 * ⚠️ 这正是「凡声称『跟 X 走』的，必须有唯一入口负责同步」那条病根 ——
 * 在这个项目里已经发作过五次（周条不跟翻页、网格顺序不跟分类、
 * 底部结束条不跟页面、模板页不跟运行态）。**所以这里必须有一条断言盯着。**
 *
 * 规则本身（怎么切、填哪儿、锁定段）已经在 `TemplateEngineTest` 和
 * `TimelineTemplateTest` 里穷举过了，这个文件不重复。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineTemplateViewModelTest {

    /** 周三，模板指派到了这一天。 */
    private val wed = "2026-10-14"

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository
    private lateinit var vm: TimelineViewModel

    private val messages = mutableListOf<String>()
    private var collector: Job? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .addCallback(TimeLogDatabase.seedCallback) // 内置的「工作日」模板靠它
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(wed, 870) // 14:30
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
        vm = TimelineViewModel(timeline, catalog, clock)

        collector = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.messages.collect { messages += it }
        }
    }

    @After
    fun tearDown() {
        collector?.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    /** 等 ViewModel 那次异步刷新落定。 */
    private fun settle() {
        Thread.sleep(120)
    }

    private suspend fun itemId(name: String): String = catalog.items().first { it.name == name }.id

    // ================================================================

    @Test
    fun `刚打开时_模板没在跑_但状态是「配好了」`() {
        settle()

        assertFalse("配置 ≠ 启动 —— 配好模板什么都不该发生", vm.tpl.on)
        assertTrue("模板必须已经存在（靠种子/Migration 种下去）", vm.tpl.exists)
        assertTrue(vm.tpl.coveredToday)
        assertTrue(vm.tpl.name.isNotBlank())
    }

    @Test
    fun `启用之后_刷新链会把今天到此刻的空白按模板铺上`() {
        settle()
        vm.startTemplate()
        settle()

        assertTrue(vm.tpl.on)
        runBlocking {
            // 0-510 应该是模板的「睡觉」，不是一片空白
            val first = timeline.dayRecords(wed).first()
            assertTrue("启用之后必须真的铺出来了", first.source == RecordSource.TEMPLATE)
            assertTrue(timeline.verify(wed).isEmpty())
        }
    }

    @Test
    fun `底部状态条那行字跟着运行态走`() {
        settle()
        assertFalse("没跑之前不该显示运行中", vm.tpl.on)

        vm.startTemplate()
        settle()
        assertTrue("跑起来之后界面才该说「运行中」", vm.tpl.on)

        vm.stopTemplate()
        settle()
        assertFalse(vm.tpl.on)
    }

    @Test
    fun `点横条先弹确认_点了确认才真的退出`() {
        settle()
        vm.startTemplate()
        settle()

        vm.askStopTemplate()
        assertTrue("点横条要先问一句", vm.sheet is Sheet.TplExit)

        vm.stopTemplate()
        settle()
        assertFalse(vm.tpl.on)
        assertTrue("确认之后面板要关掉", vm.sheet is Sheet.None)
    }

    @Test
    fun `没在跑的时候点横条不该弹窗`() {
        settle()

        vm.askStopTemplate()

        assertTrue("没跑就别弹「确定要退出吗」", vm.sheet is Sheet.None)
    }

    @Test
    fun `启用成功之后要说清怎么退出_这是用户唯一猜不到的事`() {
        settle()
        messages.clear()

        vm.startTemplate()
        settle()

        assertTrue(
            "提示里必须交代退出方式，否则用户会以为关不掉。实际是：$messages",
            messages.any { it.contains("退出") },
        )
    }

    @Test
    fun `今天没被指派时_启用会失败并把原因说出来`() {
        // 换到周六（没指派）
        clock.onDay("2026-10-17")
        vm.go("2026-10-17")
        settle()
        messages.clear()

        vm.startTemplate()
        settle()

        assertFalse(vm.tpl.on)
        assertTrue("被拦下时得给一句人话。实际是：$messages", messages.any { it.contains("周六") })
        assertFalse("拦下时不该露出「运行中」", vm.tpl.on)
    }

    @Test
    fun `模板替用户记的那条_在界面上一眼看得出是模板记的`() {
        settle()
        vm.startTemplate()
        settle()

        assertTrue(
            "记录自己冒出来是最吓人的一件事 —— 卡片必须标明来源",
            vm.rows.any { it.fromTemplate },
        )
    }

    @Test
    fun `底部那句「正在记什么」落到了模板记的那条上`() {
        settle()
        vm.startTemplate()
        settle()

        val live = vm.liveRow
        assertTrue("模板在跑时底部该显示它正在替你记什么", live != null && live.fromTemplate)
    }
}
