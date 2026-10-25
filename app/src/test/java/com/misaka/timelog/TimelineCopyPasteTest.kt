package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.RecordTagEntity
import com.misaka.timelog.data.newId
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.MINUTES_PER_DAY
import com.misaka.timelog.domain.valueOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M5-2 整天复制 / 粘贴 —— 端到端（真 Room、真仓库）。
 *
 * ## 它在盯的那句话
 *
 * > 「我可以把我目前选中的这一天的时间线的事项和时间信息复制下来，
 * >  再点击另一天，然后再选择这个按钮里面的粘贴按钮，可以把事项和时间信息粘贴到另一天去。」
 * > 粘贴语义（御坂拍板）：**覆盖整天**。
 *
 * ## 这个功能最容易出的事故
 *
 * 1. **把目标日搞出个洞** —— 清掉旧行之后没写全，那天从此缺一块，
 *    而且**不报错不崩**，几个月后统计对不上才知道。
 * 2. **贴到未来** —— 复制的是完整 24 小时，一贴就把明天铺满了，
 *    「不铺未来」这条地基当场破。
 * 3. **把「进行中」搬到过去** —— 昨天挂着一条「正在记」，
 *    这就是第 19 轮那个 bug 的同一种毒。
 * 4. **撤销不回去** —— 覆盖整天不可逆，撤销条是最后一道保险，它必须真的好使。
 *
 * 所以下面一半的测试是**反向**的：这些情况必须「不成立」。
 *
 * 日期基准：`2026-10-14` 周三 · `2026-10-15` 周四 · `2026-10-17` 周六（今天）。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineCopyPasteTest {

    /** 复制的那一天（过去，已铺满 1440）。 */
    private val wed = "2026-10-14"

    /** 粘贴的目标日（过去、空的）。 */
    private val thu = "2026-10-15"

    /** 今天。 */
    private val sat = "2026-10-17"

    /** 今天此刻 —— 10:00。 */
    private val tenAm = 600

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
        clock = FakeClock.at(sat, tenAm)
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
        source: RecordSource = RecordSource.MANUAL,
    ) = RecordEntity(
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

    /** 直接写库摆出一天的形状 —— 这里是**造现场**，不是测写路径，所以绕过仓库。 */
    private suspend fun seedDay(day: String, segs: List<Triple<Int, Int?, String?>>) {
        db.recordDao().insertAll(
            segs.map { (a, b, item) ->
                // 拼一天的标准做法：除了最后一段，前面都挂事项；item 为空就是空白段
                row(day, a, b, item, if (item == null) RecordSource.GAP else RecordSource.MANUAL)
            }
        )
    }

    private suspend fun items(n: Int): List<String> = catalog.items().take(n).map { it.id }

    // ---------------------------------------------------------------- 复制

    @Test
    fun `复制整天_段数一致`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, 480, a), Triple(480, MINUTES_PER_DAY, b)))

        val clip = timeline.clipDay(wed)

        assertEquals(2, clip.count)
        assertEquals(wed, clip.from)
        assertEquals(0, clip.segs[0].startMin)
        assertEquals(480, clip.segs[0].endMin)
        assertEquals(a, clip.segs[0].itemId)
        assertEquals(MINUTES_PER_DAY, clip.segs[1].endMin)
    }

    // ---------------------------------------------------------------- 粘贴

    @Test
    fun `贴到空的日子_内容和时间原样过去`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, 480, a), Triple(480, MINUTES_PER_DAY, b)))

        val clip = timeline.clipDay(wed)
        val out = timeline.pasteDay(thu, clip)
        assertTrue("粘贴应当成功", out.isOk)

        val got = timeline.dayRecords(thu)
        assertEquals(2, got.size)
        assertEquals(0 to 480, got[0].startMin to got[0].endMin)
        assertEquals(a, got[0].itemId)
        assertEquals(480 to MINUTES_PER_DAY, got[1].startMin to got[1].endMin)
        assertEquals(b, got[1].itemId)
    }

    @Test
    fun `贴过来的每条记录都是新id`() = runBlocking {
        val (a) = items(1)
        seedDay(wed, listOf(Triple(0, MINUTES_PER_DAY, a)))
        val srcIds = timeline.dayRecords(wed).map { it.id }.toSet()

        timeline.pasteDay(thu, timeline.clipDay(wed))

        val dstIds = timeline.dayRecords(thu).map { it.id }.toSet()
        assertTrue("id 必须全部重新生成（否则两天的记录会互相顶掉）", srcIds.intersect(dstIds).isEmpty())
    }

    @Test
    fun `覆盖粘贴_目标日原有记录全部消失`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, MINUTES_PER_DAY, a)))
        seedDay(thu, listOf(Triple(0, 600, b), Triple(600, MINUTES_PER_DAY, b)))
        val oldIds = timeline.dayRecords(thu).map { it.id }.toSet()

        timeline.pasteDay(thu, timeline.clipDay(wed))

        val now = timeline.dayRecords(thu)
        assertEquals(1, now.size)
        assertEquals(a, now[0].itemId)
        assertTrue("旧记录不该还留着", now.none { it.id in oldIds })
    }

    @Test
    fun `粘贴后这一天仍然是密铺_1440`() = runBlocking {
        val (a, b) = items(2)
        // 源那天故意不铺满（只到中午）—— 粘贴后尾巴必须由空白补齐
        seedDay(wed, listOf(Triple(0, 720, a), Triple(720, 900, b)))

        timeline.pasteDay(thu, timeline.clipDay(wed))

        assertEquals("密铺必须完整", emptyList<Any>(), timeline.verify(thu))
        val segs = timeline.dayRecords(thu)
        assertEquals("首段从头开始", 0, segs.first().startMin)
        assertEquals("末段到一天结束", MINUTES_PER_DAY, segs.last().endMin)
    }

    @Test
    fun `空白段也一起粘过去`() = runBlocking {
        val (a) = items(1)
        // 源：0-600 有事，600-1440 是空白
        seedDay(wed, listOf(Triple(0, 600, a), Triple(600, MINUTES_PER_DAY, null)))

        timeline.pasteDay(thu, timeline.clipDay(wed))

        val segs = timeline.dayRecords(thu)
        assertTrue("那段空白必须还是空白（不是被填成事项、也不是消失）", segs.any { it.source == RecordSource.GAP })
        assertEquals(emptyList<Any>(), timeline.verify(thu))
    }

    @Test
    fun `标签跟着记录一起过去`() = runBlocking {
        val (a) = items(1)
        val tag = catalog.tags().first().id
        seedDay(wed, listOf(Triple(0, MINUTES_PER_DAY, a)))
        val src = timeline.dayRecords(wed).first()
        db.tagDao().attachToRecord(RecordTagEntity(recordId = src.id, tagId = tag))

        timeline.pasteDay(thu, timeline.clipDay(wed))

        val dst = timeline.dayRecords(thu).first()
        val tags = timeline.tagsOn(listOf(dst.id))[dst.id].orEmpty().map { it.id }
        assertEquals(listOf(tag), tags)
    }

    @Test
    fun `连贴两次结果一样_不会翻倍`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, 480, a), Triple(480, MINUTES_PER_DAY, b)))
        val clip = timeline.clipDay(wed)

        timeline.pasteDay(thu, clip)
        timeline.pasteDay(thu, clip)

        assertEquals(2, timeline.dayRecords(thu).size)
        assertEquals(emptyList<Any>(), timeline.verify(thu))
    }

    // ---------------------------------------------------------------- 目标日是今天

    @Test
    fun `贴到今天_只贴到此刻_之后仍是空白`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, 480, a), Triple(480, MINUTES_PER_DAY, b)))

        val out = timeline.pasteDay(sat, timeline.clipDay(wed))
        assertTrue(out.isOk)

        val segs = timeline.dayRecords(sat)
        assertEquals("未来一格都不许铺", emptyList<Any>(), timeline.verify(sat))
        assertTrue("最后一段必须停在此刻（$tenAm）", segs.last().endMin == tenAm)
        assertTrue("不能有任何一段越过此刻", segs.all { (it.endMin ?: tenAm) <= tenAm })
    }

    @Test
    fun `源那天的进行中_贴到今天仍然是进行中`() = runBlocking {
        val (a) = items(1)
        // 源：0-300 有事，300 起一条「正在进行」
        seedDay(wed, listOf(Triple(0, 300, a), Triple(300, null, a)))

        timeline.pasteDay(sat, timeline.clipDay(wed))

        val live = timeline.liveRecord(sat)
        assertNotNull("今天该有一条进行中的", live)
        assertEquals(300, live!!.startMin)
    }

    @Test
    fun `源那天的进行中_贴到过去会就地收尾_不留进行中`() = runBlocking {
        val (a) = items(1)
        seedDay(wed, listOf(Triple(0, 300, a), Triple(300, null, a)))

        timeline.pasteDay(thu, timeline.clipDay(wed))

        val segs = timeline.dayRecords(thu)
        assertTrue("过去的一天不许有「进行中」", segs.all { it.endMin != null })
        assertEquals("就地收在一天末尾", MINUTES_PER_DAY, segs.last().endMin)
        assertEquals(emptyList<Any>(), timeline.verify(thu))
    }

    // ---------------------------------------------------------------- 拒绝

    @Test
    fun `贴到未来会被拒绝_而且那天一点没动`() = runBlocking {
        val (a) = items(1)
        seedDay(wed, listOf(Triple(0, MINUTES_PER_DAY, a)))
        val future = "2026-10-20"

        val out = timeline.pasteDay(future, timeline.clipDay(wed))

        assertFalse("未来不许贴", out.isOk)
        assertEquals("未来那天不该长出任何记录", emptyList<RecordEntity>(), timeline.dayRecords(future))
    }

    @Test
    fun `复制空的那天_贴的时候会被拒绝`() = runBlocking {
        val clip = timeline.clipDay("2026-10-01")   // 一天都没记过

        val out = timeline.pasteDay(thu, clip)

        assertFalse(out.isOk)
        assertEquals(emptyList<RecordEntity>(), timeline.dayRecords(thu))
    }

    // ---------------------------------------------------------------- 撤销

    @Test
    fun `撤销_把目标日恢复成覆盖前一模一样`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, MINUTES_PER_DAY, a)))
        seedDay(thu, listOf(Triple(0, 600, b), Triple(600, MINUTES_PER_DAY, b)))

        val before = timeline.dayRecords(thu).map { it.startMin to it.endMin to it.itemId }

        val token = timeline.pasteDay(thu, timeline.clipDay(wed)).valueOrNull()!!
        assertTrue("粘贴真的改了东西", timeline.dayRecords(thu).first().itemId == a)

        timeline.undo(token)

        val after = timeline.dayRecords(thu).map { it.startMin to it.endMin to it.itemId }
        assertEquals("撤销必须一模一样", before, after)
        assertEquals(emptyList<Any>(), timeline.verify(thu))
    }

    @Test
    fun `撤销不碰源那天`() = runBlocking {
        val (a, b) = items(2)
        seedDay(wed, listOf(Triple(0, 480, a), Triple(480, MINUTES_PER_DAY, b)))
        val srcBefore = timeline.dayRecords(wed).map { it.startMin to it.endMin to it.itemId }

        val token = timeline.pasteDay(thu, timeline.clipDay(wed)).valueOrNull()!!
        timeline.undo(token)

        assertEquals(srcBefore, timeline.dayRecords(wed).map { it.startMin to it.endMin to it.itemId })
    }
}
