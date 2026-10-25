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
import com.misaka.timelog.domain.MINUTES_PER_DAY
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
 * M2-D 的**跨天接续**（`CARRY`）—— 端到端（真 Room、真刷新链）。
 *
 * ## 它在盯的那句话
 *
 * > 昨晚 23:50 你开始记「看小说」，然后就睡了，一直没按「结束」。
 * > 今天打开 App，这段时间该记成什么？
 *
 * 御坂 v1.8 定稿 + M2-D 复述的答案：
 *
 * > 「跨天就正常结算，然后自动接续记录就行。**如果我几天都忘记打开，
 * >  那就连续几天接续同一件事情就行。** 我看得到大段的事件异常，会自己去处理。」
 *
 * ## ⚠️ 这个功能最大的风险不是「接不上」，是「接错」
 *
 * 「接不上」你一眼就看见了，当场就能补。真正麻烦的是**接错**：
 * - 把「空白」当成一件事接着记 → 一整天莫名其妙挂着个事项
 * - 把「模板铺的」也接上 → 模板和接续两边一起写同一天，**密铺当场破**
 * - 每天刷新都接一次 → 同一天攒出十几条重复记录
 *
 * 所以下面一半的测试是**反向**的：**这些情况必须「不接」**。
 *
 * ## 时钟
 *
 * 全用 [FakeClock] —— 「跨没跨天」就是整个功能的核心判断点，不固定时钟根本没法测。
 *
 * ⚠️ 日期基准：`2026-10-14` 周三 · `2026-10-15` 周四 · `2026-10-17` 周六。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineCarryTest {

    /** 周三 —— 记「看小说」的那天。 */
    private val wed = "2026-10-14"

    /** 周四 —— 醒来打开 App 的那天。 */
    private val thu = "2026-10-15"

    /** 周五。 */
    private val fri = "2026-10-16"

    /** 周六 —— 没排进「工作日」模板。 */
    private val sat = "2026-10-17"

    /** 23:50 —— 那个「记完就睡了」的时刻。 */
    private val lateNight = 1430

    /** 08:10 —— 醒来打开 App 的时刻。 */
    private val morning = 490

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(wed, lateNight)
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun item(name: String): String =
        catalog.items().first { it.name == name }.id

    private fun row(day: String, a: Int, b: Int?, itemId: String?, source: RecordSource) =
        RecordEntity(
            id = newId(),
            day = day,
            startMin = a,
            endMin = b,
            itemId = itemId,
            categoryIdSnap = null,
            source = source,
            chainId = null,
            noTemplate = false,
            note = "",
            updatedAt = 0L,
            deleted = false,
        )

    /**
     * 一次性走完整条刷新链 —— **跟 ViewModel 里的顺序逐字一致**。
     *
     * ⚠️ 这里**必须**是 `syncTemplateDay → carryOver → ensureDense → applyTemplate`。
     * 把 `carryOver` 挪到 `ensureDense` 后面，「昨晚那条 live 先被封口了」，
     * 于是接续永远不发生 —— 而测试如果不照抄真实顺序，这个错**测不出来**。
     */
    private suspend fun refresh(day: String) {
        timeline.syncTemplateDay()
        timeline.carryOver()
        timeline.ensureDense(day)
        timeline.applyTemplate(day)
    }

    /** 这一天的样子压成 `"0-1430 未记录"` 这样一行行。 */
    private suspend fun layout(day: String): List<String> {
        val names = catalog.items().associate { it.id to it.name }
        val tagOf = mapOf(
            RecordSource.GAP to "未记录",
            RecordSource.CARRY to "(续)",
            RecordSource.TEMPLATE to "(模板)",
        )
        return timeline.dayRecords(day).map { r ->
            val who = if (r.source == RecordSource.GAP) {
                "未记录"
            } else {
                (names[r.itemId] ?: "?") + (tagOf[r.source] ?: "")
            }
            "${r.startMin}-${r.endMin ?: "现在"} $who"
        }
    }

    private suspend fun assertDense(day: String, hint: String = "") {
        val problems = timeline.verify(day)
        assertTrue("$day 应该密铺，实际问题：$problems  $hint", problems.isEmpty())
    }

    /** 造出「23:50 开始记 X，然后就睡了」这个现场。 */
    private suspend fun sleepWhileRecording(name: String) {
        refresh(wed)
        assertTrue(timeline.pick(item(name), wed).isOk)
        val live = timeline.liveRecord(wed)
        assertNotNull("构造失败：应该有一条进行中的记录", live)
        assertEquals("构造失败：它应该是手动记的", RecordSource.MANUAL, live!!.source)
    }

    // ================================================================ 接得上

    @Test
    fun `昨晚记完就睡了_今天打开时自动接着记`() = runBlocking {
        sleepWhileRecording("看小说")

        // 一觉睡到今天 08:10
        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertEquals(
            "今天应该整段接着「看小说」，而不是从「未记录」重新开始",
            listOf("0-现在 看小说(续)"),
            layout(thu),
        )
        assertDense(thu)
    }

    @Test
    fun `昨晚那一条在 24_00 收尾_不是 23_59_一分钟都不丢`() = runBlocking {
        sleepWhileRecording("看小说")

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        val last = timeline.dayRecords(wed).last()
        assertEquals("跨天结算要收到 1440", MINUTES_PER_DAY, last.endMin)
        assertEquals(
            "昨天这一天的总覆盖必须还是 1440 —— 少一分钟就永远留在库里了",
            MINUTES_PER_DAY,
            timeline.dayRecords(wed).sumOf { (it.endMin ?: 0) - it.startMin },
        )
        assertDense(wed)
    }

    @Test
    fun `接续出来的那条是进行中_底部才会显示已持续多久`() = runBlocking {
        sleepWhileRecording("看小说")

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        val live = timeline.liveRecord(thu)
        assertNotNull("接续出来的一定是「进行中」—— 不然点哪儿结束？", live)
        assertEquals(RecordSource.CARRY, live!!.source)
        assertEquals("应该从 00:00 起，缝为 0", 0, live.startMin)
        assertNull("进行中 = endMin 是 null", live.endMin)
    }

    @Test
    fun `两段用同一个 chainId 串起来`() = runBlocking {
        sleepWhileRecording("看小说")

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        val lastNight = timeline.dayRecords(wed).last()
        val today = timeline.dayRecords(thu).single()

        assertNotNull("chainId 是「这两段其实是同一件事」的唯一凭据，不能空", today.chainId)
        assertEquals(
            "跨天切成两段，但它们是同一件连续的事 —— 必须用同一个 chainId",
            lastNight.chainId,
            today.chainId,
        )
    }

    @Test
    fun `缝为 0_昨天结束的那一刻今天接着开始`() = runBlocking {
        sleepWhileRecording("看书")

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertEquals("昨天的终点", MINUTES_PER_DAY, timeline.dayRecords(wed).last().endMin)
        assertEquals("今天的起点", 0, timeline.dayRecords(thu).first().startMin)
    }

    // ================================================================ 隔了好几天

    @Test
    fun `几天没开 App_连续几天都续上同一件事`() = runBlocking {
        sleepWhileRecording("看小说")

        // 周三 23:50 记上，一直没打开，周六 09:00 才打开
        clock.onDay(sat).atMinute(540)
        refresh(sat)

        assertEquals("周四：整天都是「看小说」", listOf("0-1440 看小说(续)"), layout(thu))
        assertEquals("周五：整天都是「看小说」", listOf("0-1440 看小说(续)"), layout(fri))
        assertEquals("周六（今天）：续到现在", listOf("0-现在 看小说(续)"), layout(sat))

        assertDense(wed)
        assertDense(thu)
        assertDense(fri)
        assertDense(sat)
    }

    @Test
    fun `连着几天续下来_还是同一条链`() = runBlocking {
        sleepWhileRecording("看小说")

        clock.onDay(sat).atMinute(540)
        refresh(sat)

        val chains = listOf(wed, thu, fri, sat)
            .map { timeline.dayRecords(it).last().chainId }
            .toSet()
        assertEquals("一条链只能有一个 chainId，实际：$chains", 1, chains.size)
        assertNotNull(chains.first())
    }

    // ================================================================ 这些必须**不接**

    @Test
    fun `昨晚啥也没记就睡了_今天不该凭空冒出一个事项`() = runBlocking {
        refresh(wed) // 只补密，一条真记录都没有
        assertTrue(
            "构造前提：昨天末尾必须是空白",
            timeline.dayRecords(wed).all { it.source == RecordSource.GAP },
        )

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertEquals(
            "空白没有「同一件事」可续 —— 这一天必须是空白",
            listOf("0-$morning 未记录"),
            layout(thu),
        )
        assertTrue(
            "一条 CARRY 都不许有",
            timeline.dayRecords(thu).none { it.source == RecordSource.CARRY },
        )
    }

    @Test
    fun `昨晚末尾是模板铺的_不接续_交给模板自己管`() = runBlocking {
        refresh(wed)
        assertTrue(timeline.startTemplate().isOk)
        timeline.applyTemplate(wed)
        assertEquals(
            "构造前提：模板铺的末尾那条是进行中的",
            RecordSource.TEMPLATE,
            timeline.liveRecord(wed)!!.source,
        )

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertTrue(
            "模板铺的段不该被「接续」接管 —— 两边一起写同一天，密铺会破",
            timeline.dayRecords(thu).none { it.source == RecordSource.CARRY },
        )
        assertEquals(
            "交给模板，今天就是「睡觉」从 0 铺到 08:10（末条保持进行中），不是「看小说」续下来",
            listOf("0-现在 睡觉(模板)"),
            layout(thu),
        )
        assertDense(thu)
    }

    @Test
    fun `昨天末尾挂着进行中_今天不重复接第二次`() = runBlocking {
        sleepWhileRecording("看小说")

        clock.onDay(thu).atMinute(morning)
        refresh(thu)
        val afterFirst = timeline.dayRecords(thu).size

        // 再刷三次（真实世界里 App 每分钟都在刷）
        repeat(3) { refresh(thu) }

        assertEquals(
            "接续必须幂等 —— 判据是「昨天还有没有 live」，接完就封口了，不该靠额外标记",
            afterFirst,
            timeline.dayRecords(thu).size,
        )
        assertDense(thu)
    }

    @Test
    fun `今天已经记过东西了_接续不硬盖`() = runBlocking {
        sleepWhileRecording("看小说")

        // 今天已经有一条（模拟「今天的数据先落库了，接续才跑」这个顺序）
        db.recordDao().insert(
            row(thu, 0, morning, item("打工"), RecordSource.MANUAL)
        )

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertEquals(
            "这一天已经有真记录了 → 不拿接续盖掉它",
            listOf("0-490 打工"),
            layout(thu),
        )
        assertDense(thu)
    }

    @Test
    fun `备注不会被粘到今天那一条上`() = runBlocking {
        refresh(wed)
        timeline.pick(item("看小说"), wed)
        val liveId = timeline.liveRecord(wed)!!.id
        db.recordDao().update(db.recordDao().byId(liveId)!!.copy(note = "凉宫春日第 5 卷"))

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertEquals(
            "「凉宫春日第 5 卷」是写给【昨晚那一条】的，粘到今天等于说了句张冠李戴的话",
            "",
            timeline.dayRecords(thu).single().note,
        )
        assertEquals(
            "但昨晚那条自己的备注要原样留着",
            "凉宫春日第 5 卷",
            timeline.dayRecords(wed).last().note,
        )
    }

    @Test
    fun `没跨天时什么都不做_今天自己记的那条照常挂着`() = runBlocking {
        sleepWhileRecording("看小说")

        clock.atMinute(1439) // 还在同一天里
        refresh(wed)

        val live = timeline.liveRecord(wed)
        assertNotNull("今天自己的进行中记录不许被动", live)
        assertEquals(RecordSource.MANUAL, live!!.source)
        assertNull("不该被当成跨天结算掉", live.endMin)
    }

    // ================================================================ 和模板打配合

    @Test
    fun `模板在跑时_接续出来的段不会被模板改掉`() = runBlocking {
        // 昨晚手记「看小说」→ 今天 08:10 醒来接着记（这一条是 CARRY）
        sleepWhileRecording("看小说")
        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        // 09:00 切模板模式（它会把此刻正在记的那条封口），之后走到 10:00
        clock.atMinute(540)
        assertTrue(timeline.startTemplate().isOk)
        refresh(thu)
        clock.atMinute(600)
        refresh(thu)

        val rows = timeline.dayRecords(thu)
        assertEquals(
            "「看小说」是你亲手记的（接续 = 昨晚那件事还在继续），模板是兜底不是命令 —— 不许碰",
            item("看小说"),
            rows.first().itemId,
        )
        assertTrue(
            "模板只能铺 09:00 之后的空白，实际：${layout(thu)}",
            rows.any { it.source == RecordSource.TEMPLATE },
        )
        assertTrue(
            "那一段不该变成模板的「睡觉」。实际：${layout(thu)}",
            rows.none { it.startMin == 0 && it.source == RecordSource.TEMPLATE },
        )
        assertDense(thu)
    }

    @Test
    fun `接续之后今天依然满 1440 分钟`() = runBlocking {
        sleepWhileRecording("看小说")
        clock.onDay(sat).atMinute(540)
        refresh(sat)

        assertEquals(MINUTES_PER_DAY, timeline.dayRecords(thu).sumOf { it.endMin!! - it.startMin })
        assertEquals(MINUTES_PER_DAY, timeline.dayRecords(fri).sumOf { it.endMin!! - it.startMin })
    }

    @Test
    fun `昨晚记的事项被删掉之后_不比不接更糟`() = runBlocking {
        refresh(wed)
        val id = item("看小说")
        timeline.pick(id, wed)

        // 删事项会把历史记录原地改写成空白，那条 live 也就没了
        assertTrue(catalog.deleteItemDeep(id).isOk)

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertTrue(
            "没有 live 了 → 今天就是空白，不该凭空接出个东西来",
            timeline.dayRecords(thu).all { it.source == RecordSource.GAP },
        )
        assertDense(thu)
    }

    @Test
    fun `空白段补记之后_不再被当成接续对象`() = runBlocking {
        refresh(wed)
        val gapId = timeline.dayRecords(wed).single().id
        assertTrue(timeline.fillGap(gapId, item("看书")).isOk)

        clock.onDay(thu).atMinute(morning)
        refresh(thu)

        assertFalse(
            "补记出来的是【已结束】的记录，不是 live → 不构成接续依据",
            timeline.dayRecords(thu).any { it.source == RecordSource.CARRY },
        )
    }
}
