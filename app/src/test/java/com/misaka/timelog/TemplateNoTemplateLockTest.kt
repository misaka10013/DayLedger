package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.newId
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `noTemplate` 这把锁**只锁住它该锁的那几分钟**。
 *
 * ## 事故现场（2026-10-19，御坂报的）
 *
 * > 「今天中午我使用模板后，一直显示模板运行中，但又显示未记录，未开始？」
 *
 * 查库发现当天的形状是：
 *
 * ```
 *   01:21-08:22  睡觉      MANUAL
 *   ...
 *   12:23-12:48  吃饭      MANUAL
 *   12:48-14:29  (空白)    GAP  noTemplate = 1   ← 问题在这
 * ```
 *
 * 模板运行态、日期、星期指派**全部正常**，一个错都没报，模板就是一下午不铺。
 *
 * ## 根因：锁的范围会自己长大
 *
 * `noTemplate` 的本意是「这**几分钟**我不想要模板填」（删掉模板记录、删事项擦历史时留下）。
 * 但它挂在一条**空白记录**上，而 `ensureDense` 每次刷新都会把**尾部空白延长到此刻**：
 *
 * ```
 *   12:48 删掉 → [12:48, 12:51) 带锁     ← 本意：就这 3 分钟
 *   12:51 刷新 → [12:48, 12:52) 带锁     ← 延长的时候把锁一起带走了
 *   14:30 刷新 → [12:48, 14:30) 带锁     ← 保护了 102 分钟
 * ```
 *
 * 全程不报错。症状看起来像「模板坏了／有时候不生效」，实际是锁自己在长。
 *
 * ## 三条规矩
 *
 * 1. **不延长**：尾部空白带锁时冻在原处，从它的终点另起一条干净的
 * 2. **启用模板时清掉紧贴此刻那条的锁**：用户点「启用」= 从现在起照模板来
 * 3. **锁本身照常有效**：别把锁改成永远无效，那会让「删掉模板记录」重新变成删不掉
 *
 * ⚠️ `2026-10-14` 是**周三**（内置模板指派到了这一天）。
 */
@RunWith(RobolectricTestRunner::class)
class TemplateNoTemplateLockTest {

    private val wed = "2026-10-14"

    /** 12:48 —— 事故里那条空白开始的那一分钟。 */
    private val twelveFortyEight = 768

    /** 14:30。 */
    private val afternoon = 870

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(wed, afternoon)
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 辅助

    private fun row(
        day: String,
        a: Int,
        b: Int?,
        itemId: String?,
        noTemplate: Boolean = false,
    ) = RecordEntity(
        id = newId(),
        day = day,
        startMin = a,
        endMin = b,
        itemId = itemId,
        categoryIdSnap = null,
        source = if (itemId == null) RecordSource.GAP else RecordSource.MANUAL,
        chainId = null,
        noTemplate = noTemplate,
        note = "",
        updatedAt = 0L,
        deleted = false,
    )

    private suspend fun item(name: String): String =
        catalog.items().first { it.name == name }.id

    /** 摆成事故现场：`[0, lockFrom)` 是一件事，`[lockFrom, cut)` 是一条**带锁的空白**。 */
    private suspend fun reproduceIncident(lockFrom: Int = twelveFortyEight): String {
        val dao = db.recordDao()
        dao.insert(row(wed, 0, lockFrom, item("睡觉")))
        val gapId = newId()
        dao.insert(row(wed, lockFrom, afternoon, null, noTemplate = true).copy(id = gapId))
        return gapId
    }

    private suspend fun rows() = db.recordDao().byDay(wed)

    /** 这一天所有「带锁空白」加起来多长 —— 锁有没有自己长大，看这个数最直接。 */
    private suspend fun lockedSpan(): Int =
        rows().filter { it.source == RecordSource.GAP && it.noTemplate }
            .sumOf { (it.endMin ?: 0) - it.startMin }

    private suspend fun whoAt(minute: Int): String {
        val names = catalog.items().associate { it.id to it.name }
        val r = rows().firstOrNull { minute >= it.startMin && minute < (it.endMin ?: 0) } ?: return "空"
        return if (r.source == RecordSource.GAP) "未记录" else names[r.itemId] ?: "?"
    }

    /** 跟 ViewModel 里**逐字一致**的刷新链。 */
    private suspend fun refresh() {
        timeline.syncTemplateDay()
        timeline.carryOver()
        timeline.ensureDense(wed)
        timeline.applyTemplate(wed)
    }

    private suspend fun startTemplate() {
        val r = timeline.startTemplate()
        assertTrue("启用模板应该成功，实际：$r", r is Outcome.Ok)
    }

    private suspend fun assertDense(hint: String = "") {
        val problems = timeline.verify(wed)
        assertTrue("应该密铺，实际问题：$problems $hint", problems.isEmpty())
    }

    // ------------------------------------------------- 规矩 ①：不许延长

    @Test
    fun `带锁的尾部空白不再被延长到此刻`() = runBlocking {
        // 不启用模板，只验 ensureDense 本身 —— 否则「启用时清锁」会把这个 case 搅浑
        val gapId = reproduceIncident()

        clock.atMinute(afternoon + 1)
        timeline.ensureDense(wed)

        val locked = rows().first { it.id == gapId }
        assertEquals(
            "带锁的空白被延长了 —— 锁会跟着「此刻」一路往前爬",
            afternoon,
            locked.endMin,
        )
        assertTrue("锁本身不该被这次补密清掉", locked.noTemplate)

        // 新起的那条必须是干净的（可被模板填）
        val fresh = rows().firstOrNull { it.startMin == afternoon }
        assertTrue("14:30 之后应该另起一条空白，实际：${rows().map { "${it.startMin}-${it.endMin}" }}",
            fresh != null)
        assertFalse("新起的空白不该继承锁", fresh!!.noTemplate)

        assertDense("另起一条之后仍然要密铺")
    }

    @Test
    fun `模板跑着的时候_锁不会随着刷新变大`() = runBlocking {
        // 真实流程：先启用模板让它铺到此刻，再模拟「运行中删掉一小段」
        startTemplate()
        refresh()
        assertDense("启用后应该铺满")

        // 删掉覆盖 12:48 的那条 → 它变成带锁空白（= 你删掉模板记录的样子）
        val victim = rows().first { twelveFortyEight >= it.startMin && twelveFortyEight < (it.endMin ?: 0) }
        timeline.deleteRecord(victim.id)
            .let { if (it !is Outcome.Ok) error("删除失败：$it") }

        val lockedBefore = lockedSpan()
        assertTrue("删完之后应该有带锁的空白，实际 $lockedBefore", lockedBefore > 0)

        // 时间往前走，反复刷新 —— 锁的范围必须一动不动
        clock.atMinute(afternoon + 1)
        refresh()
        clock.atMinute(afternoon + 5)
        refresh()
        clock.atMinute(afternoon + 20)
        refresh()

        assertEquals(
            "锁随着刷新长大了 —— 这就是「保护 3 分钟」变成「保护一下午」的现场",
            lockedBefore,
            lockedSpan(),
        )

        // 而且 14:30 之后必须被模板接上（事故里缺的就是这一块）
        assertFalse(
            "14:30 之后应该是模板内容，实际是「${whoAt(afternoon)}」",
            whoAt(afternoon) == "未记录",
        )
        assertDense("反复刷新之后仍然要密铺")
    }

    @Test
    fun `锁本身照常有效_删掉的那一小段不会被填回来`() = runBlocking {
        // 防回归：别把锁改成「永远无效」，那会让「删掉模板记录」重新变成删不掉
        startTemplate()
        refresh()

        val victim = rows().first { twelveFortyEight >= it.startMin && twelveFortyEight < (it.endMin ?: 0) }
        timeline.deleteRecord(victim.id)
            .let { if (it !is Outcome.Ok) error("删除失败：$it") }

        clock.atMinute(afternoon + 5)
        refresh()

        assertEquals(
            "被删掉的那一段必须保持「未记录」，否则就是当初那个「删不掉」的 bug",
            "未记录",
            whoAt(twelveFortyEight),
        )
        assertDense("锁生效的同时，剩下部分仍要密铺")
    }

    // ------------------------------------------------- 规矩 ②：启用时清锁

    @Test
    fun `重新启用模板能清掉那条锁_让模板接上`() = runBlocking {
        val gapId = reproduceIncident()
        startTemplate() // 第一次启用：会清掉紧贴此刻那条
        timeline.stopTemplate()
        startTemplate()
        refresh()

        val locked = rows().first { it.id == gapId }
        assertFalse("重新启用应该清掉紧贴此刻那条的锁", locked.noTemplate)
        assertFalse(
            "清锁后 12:48 应该被模板填上，实际「${whoAt(twelveFortyEight)}」",
            whoAt(twelveFortyEight) == "未记录",
        )
        assertDense()
    }

    @Test
    fun `启用模板只清紧贴此刻那条_更早的空白不动`() = runBlocking {
        val dao = db.recordDao()
        // 早上有一条用户主动删出来的空白（11:00-11:30），它不该被这次启用推翻
        dao.insert(row(wed, 0, 660, item("睡觉")))
        dao.insert(row(wed, 660, 690, null, noTemplate = true))
        dao.insert(row(wed, 690, twelveFortyEight, item("打工")))
        dao.insert(row(wed, twelveFortyEight, afternoon, null, noTemplate = true))

        startTemplate()

        val morning = rows().first { it.startMin == 660 }
        assertTrue("11:00 那条是用户对过去时间的态度，不该被这次启用清掉", morning.noTemplate)

        val recent = rows().first { it.startMin == twelveFortyEight }
        assertFalse("紧贴此刻那条应该被清掉（否则模板永远接不上）", recent.noTemplate)
    }

    @Test
    fun `没有模板跑着的时候_锁不会被动`() = runBlocking {
        reproduceIncident()
        clock.atMinute(afternoon + 1)
        refresh()

        assertEquals("模板没启用时，那条空白必须原样保留", "未记录", whoAt(twelveFortyEight))
        val locked = rows().first { it.source == RecordSource.GAP && it.noTemplate }
        assertTrue("锁不该被刷新清掉", locked.noTemplate)
    }
}
