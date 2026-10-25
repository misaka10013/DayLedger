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
import com.misaka.timelog.domain.Outcome
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
 * M2-B 的**记录编辑**操作 —— 每条测试都盯着同一句话：
 *
 * > **改完之后，这一天还是完整覆盖 0..1440 吗？**
 *
 * ## 为什么这个文件比界面代码重要得多
 *
 * 时间线上「点一下」的那些动作（改时间 / 合并 / 拆分 / 删除 / 补记）每一条都会
 * 牵动密铺不变式，而**破了它不会报错、不会崩**。症状要几个月后才出现：
 * 统计数字对不上，那时候已经查不出是哪次操作弄的。
 *
 * 所以这里测的不是「代码有没有按我想的跑」，而是「这个 App 的地基有没有塌」。
 * 每组用例最后那句 `assertDense()` 才是主角，前面的断言只是**说清它是怎么塌的**。
 *
 * ## 造数据为什么直接插库
 *
 * 因为要摆出「昨天 10:00–11:00 打工、11:00–12:00 学习、其余空白」这种局面，
 * 用仓库自己的接口得绕七八步、还得依赖一堆操作的正确性 —— 那样测试就变成了
 * 在验证自己。插库造数据 + 只用仓库做被测操作，测出来的才是真东西。
 *
 * ## ⚠️ `lay()` 返回的 id 是**按你写的段顺序**来的
 *
 * 你显式写了三段，就拿回三个 id、位置一一对应；中间/两头自动补出来的空白
 * **不在里面**（要用就查 `gapAt()`）。所以 `lay(day, Seg(0,600), Seg(600,660,a), Seg(660,1440))`
 * 里的 `ids[0]` 是那段**空白**，`ids[1]` 才是「打工」—— 这一点踩过一次。
 */
@RunWith(RobolectricTestRunner::class)
class TimelineEditTest {

    private val today = "2026-10-14"
    private val yesterday = "2026-10-13"

    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var catalog: CatalogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
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

    /** 一天里的一段：`[a, b)` 是谁的（`itemId = null` 就是空白）。 */
    private data class Seg(
        val a: Int,
        val b: Int,
        val itemId: String? = null,
        val note: String = "",
        val tags: List<String> = emptyList(),
    )

    private var catId: String? = null

    private suspend fun cat(): String =
        catId ?: ((catalog.addCategory("工作", "#F59E0B") as Outcome.Ok).value.id).also { catId = it }

    private suspend fun item(name: String): String =
        (catalog.addItem(cat(), name, "#F59E0B") as Outcome.Ok).value.id

    private suspend fun tag(name: String): String {
        val bucket = (catalog.addBucket("状态", "★") as Outcome.Ok).value
        return (catalog.addTag(bucket.id, name, "#EF4444", "有产出") as Outcome.Ok).value.id
    }

    /**
     * 把某天摆成指定的样子，中间/两头的空隙自动补成空白 —— **保证造出来就是密铺的**。
     *
     * @param full 这一天要覆盖到第几分钟（过去的一天是 1440，今天是「此刻」）
     * @return 每个显式 [Seg] 对应的记录 id，**按段顺序**
     */
    private suspend fun lay(day: String, vararg segs: Seg, full: Int = 1440): List<String> {
        val dao = db.recordDao()
        val ids = ArrayList<String>(segs.size)
        var cursor = 0

        segs.forEach { s ->
            require(s.b > s.a) { "造的段必须是正的" }
            if (s.a > cursor) dao.insert(plainRow(day, cursor, s.a, null))
            val id = newId()
            dao.insert(plainRow(day, s.a, s.b, s.itemId).copy(id = id, note = s.note))
            s.tags.forEach { db.tagDao().attachToRecord(RecordTagEntity(id, it)) }
            ids += id
            cursor = s.b
        }
        if (cursor < full) dao.insert(plainRow(day, cursor, full, null))
        return ids
    }

    private fun plainRow(day: String, a: Int, b: Int, itemId: String?) = RecordEntity(
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

    /** 找出盖住第 [minute] 分钟的那一段空白。 */
    private suspend fun gapAt(day: String, minute: Int): String =
        timeline.dayRecords(day)
            .first { it.source == RecordSource.GAP && it.startMin <= minute && (it.endMin ?: 1440) > minute }
            .id

    private suspend fun markTemplate(recordId: String) {
        val r = db.recordDao().byId(recordId)!!
        db.recordDao().update(r.copy(source = RecordSource.TEMPLATE))
    }

    private suspend fun record(day: String, id: String): RecordEntity =
        timeline.dayRecords(day).first { it.id == id }

    private suspend fun tagIdsOf(recordId: String): List<String> = db.tagDao().tagIdsOfRecord(recordId)

    /** 今天**此刻**正在计时的那条。 */
    private suspend fun liveId(day: String): String =
        timeline.dayRecords(day).single { it.endMin == null }.id

    /** ★ 整套设计的存在意义就在这一句上。 */
    private suspend fun assertDense(day: String, hint: String = "") {
        val problems = timeline.verify(day)
        assertTrue("$day 应该密铺，实际问题：$problems  $hint", problems.isEmpty())
    }

    /** 这一天的总覆盖时长 —— 密铺的话恒等于 1440（过去）/ cut（今天）。 */
    private suspend fun coverOf(day: String): Int =
        timeline.dayRecords(day).sumOf { (it.endMin ?: 0) - it.startMin }

    // ======================================================== 换事项

    @Test
    fun `换事项_时间一点不动`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        // ids[0]=空白 0-600｜ids[1]=打工 600-660｜ids[2]=空白 660-1440
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 1440))
        val before = record(yesterday, ids[1])

        val ok = timeline.changeItem(ids[1], b)

        assertTrue(ok.isOk)
        val after = record(yesterday, ids[1])
        assertEquals(b, after.itemId)
        assertEquals(before.startMin, after.startMin)
        assertEquals(before.endMin, after.endMin)
        assertDense(yesterday)
    }

    @Test
    fun `换事项会把模板标记摘掉`() = runBlocking {
        // 你亲手改过的段就不再是「模板记的」—— 不摘的话卡片会一直显示「模板」，
        // 将来做「今日偏差」也永远认不出哪些段被你修正过
        val a = item("打工")
        val b = item("学习")
        val ids = lay(yesterday, Seg(600, 660, a))
        markTemplate(ids[0])

        timeline.changeItem(ids[0], b)

        assertEquals(RecordSource.MANUAL, record(yesterday, ids[0]).source)
    }

    @Test
    fun `换事项会带上新事项的默认标签`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        val g = tag("高价值投资")
        catalog.setDefaultTags(b, listOf(g))
        val ids = lay(yesterday, Seg(600, 660, a))

        timeline.changeItem(ids[0], b)

        assertEquals(listOf(g), tagIdsOf(ids[0]))
    }

    @Test
    fun `新事项没有默认标签时保留原来挂着的`() = runBlocking {
        // 「换过去反而把标签清空了」是用户会觉得莫名其妙的行为
        val a = item("打工")
        val b = item("学习")
        val g = tag("高价值投资")
        val ids = lay(yesterday, Seg(600, 660, a, tags = listOf(g)))

        timeline.changeItem(ids[0], b)

        assertEquals(listOf(g), tagIdsOf(ids[0]))
    }

    @Test
    fun `空白不能换事项`() = runBlocking {
        val a = item("打工")
        lay(yesterday, Seg(600, 660, a))
        val gapId = gapAt(yesterday, 300)

        val out = timeline.changeItem(gapId, a)

        assertTrue(out is Outcome.Blocked)
        assertTrue("拦下的理由得说人话", (out as Outcome.Blocked).message.contains("空白"))
    }

    @Test
    fun `换成一个已经被删掉的事项会被拦下`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(600, 660, a))

        val out = timeline.changeItem(ids[0], "不存在的id")

        assertTrue(out is Outcome.Blocked)
    }

    // ======================================================== 改时间

    @Test
    fun `改短留出的空隙会变成一条看得见的空白`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 1440))

        val out = timeline.setTime(ids[1], 600, 620)

        assertTrue(out.isOk)
        assertEquals(600, record(yesterday, ids[1]).startMin)
        assertEquals(620, record(yesterday, ids[1]).endMin)
        assertDense(yesterday)
        assertEquals("改短之后总覆盖不能变", 1440, coverOf(yesterday))
    }

    @Test
    fun `改长撞到后面那条_后面那条的头被切掉`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        // ids[1]=打工 600-660｜ids[2]=学习 660-720
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 720, b), Seg(720, 1440))

        val msgs = (timeline.setTime(ids[1], 600, 700) as Outcome.Ok).value

        assertEquals(700, record(yesterday, ids[1]).endMin)
        val nb = record(yesterday, ids[2])
        assertEquals("学习应该被切到 700 开始", 700, nb.startMin)
        assertEquals(720, nb.endMin)
        assertDense(yesterday)
        assertEquals(1, msgs.size)
        assertTrue("提示要说清是谁让位：$msgs", msgs[0].contains("学习"))
    }

    @Test
    fun `改长撞到前面那条_前面那条的尾被切掉`() = runBlocking {
        val a = item("打工")
        // 0-600 空白｜600-720 打工｜其余空白。把打工往前拉到 540，压到空白的尾巴
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 720, a), Seg(720, 1440))

        val msgs = (timeline.setTime(ids[1], 540, 720) as Outcome.Ok).value

        val prev = record(yesterday, gapAt(yesterday, 100))
        assertEquals(0, prev.startMin)
        assertEquals("前面那条空白的尾巴该被切到 540", 540, prev.endMin)
        assertDense(yesterday)
        assertTrue("提示要说清是谁让位：$msgs", msgs.isNotEmpty())
    }

    @Test
    fun `邻居被完全覆盖就直接删掉它`() = runBlocking {
        // 这是唯一允许「真删一行」的场景之一：它的那段时间已经被完整接管，
        // 没有哪一段时间失去代表 —— 所以删它是安全的，也是唯一正确的
        val a = item("打工")
        val b = item("学习")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 720, b), Seg(720, 1440))

        val msgs = (timeline.setTime(ids[1], 600, 800) as Outcome.Ok).value

        assertEquals(800, record(yesterday, ids[1]).endMin)
        assertNull("被吞掉的那条应该不在了", db.recordDao().byId(ids[2]))
        assertDense(yesterday)
        assertTrue("要告诉用户它被删了：$msgs", msgs.any { it.contains("删除") })
        assertEquals(1440, coverOf(yesterday))
    }

    @Test
    fun `进行中的记录只改开始时间_终点还是现在`() = runBlocking {
        val a = item("打工")
        timeline.ensureDense(today)
        timeline.pick(a, today)
        val id = liveId(today)

        val out = timeline.setTime(id, 800, 9999)

        assertTrue(out.isOk)
        val r = record(today, id)
        assertEquals(800, r.startMin)
        assertNull("终点不该被写死 —— 那是「结束」按钮的事", r.endMin)
        assertDense(today)
    }

    @Test
    fun `改时间不会伸到未来去`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(600, 660, a))

        val out = timeline.setTime(ids[0], 600, 5000)

        assertTrue(out.isOk)
        assertEquals("5000 要被夹到 1440", 1440, record(yesterday, ids[0]).endMin)
        assertDense(yesterday)
    }

    @Test
    fun `结束时间早于开始时间会被拦下`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(600, 660, a))

        val out = timeline.setTime(ids[0], 700, 600)

        assertTrue(out is Outcome.Blocked)
        assertEquals("被拦下就不该改到库里", 660, record(yesterday, ids[0]).endMin)
        assertDense(yesterday)
    }

    @Test
    fun `空白不能改时间`() = runBlocking {
        timeline.ensureDense(yesterday)
        val gapId = gapAt(yesterday, 300)

        val out = timeline.setTime(gapId, 100, 200)

        assertTrue(out is Outcome.Blocked)
    }

    // ======================================================== 合并 / 拆分

    @Test
    fun `与上一条合并_留下点的那条`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 720, b), Seg(720, 1440))

        // 「上一条」= 时间线上方 = 时间更晚 = 升序数组里 index + 1
        val out = timeline.mergeWith(ids[1], dir = -1)

        assertTrue(out.isOk)
        val host = record(yesterday, ids[1])
        assertEquals("宿主还是「打工」", a, host.itemId)
        assertEquals(600, host.startMin)
        assertEquals(720, host.endMin)
        assertNull("被吸收的那条要消失", db.recordDao().byId(ids[2]))
        assertDense(yesterday)
        assertEquals(1440, coverOf(yesterday))
    }

    @Test
    fun `合并以点的那条为宿主_名字跟着点的那条`() = runBlocking {
        val a = item("打工")
        val b = item("学习")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 720, b), Seg(720, 1440))

        // 这次点的是「学习」（ids[2]），让它往下（更早）跟「打工」合并
        timeline.mergeWith(ids[2], dir = 1)

        val host = record(yesterday, ids[2])
        assertEquals("宿主是「学习」", b, host.itemId)
        assertEquals(600, host.startMin)
        assertEquals(720, host.endMin)
        assertNull(db.recordDao().byId(ids[1]))
        assertDense(yesterday)
    }

    @Test
    fun `合并时边上没有邻居会被拦下`() = runBlocking {
        // 一整天只有一条记录 —— 它上边下边都没有东西
        val a = item("打工")
        val ids = lay(yesterday, Seg(0, 1440, a))

        val up = timeline.mergeWith(ids[0], dir = -1)
        val down = timeline.mergeWith(ids[0], dir = 1)

        assertTrue(up is Outcome.Blocked)
        assertTrue(down is Outcome.Blocked)
        assertDense(yesterday)
    }

    @Test
    fun `空白不能走合并记录那条路`() = runBlocking {
        val a = item("打工")
        lay(yesterday, Seg(600, 660, a))
        val gapId = gapAt(yesterday, 300)

        val out = timeline.mergeWith(gapId, dir = 1)

        assertTrue(out is Outcome.Blocked)
    }

    @Test
    fun `对半拆分_两段等长且都还是原来那件事`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 720, a), Seg(720, 1440))

        val out = timeline.split(ids[1])

        assertTrue(out.isOk)
        val head = record(yesterday, ids[1])
        assertEquals(600, head.startMin)
        assertEquals(660, head.endMin)

        val tail = timeline.dayRecords(yesterday).single { it.id != ids[1] && it.itemId == a }
        assertEquals(660, tail.startMin)
        assertEquals(720, tail.endMin)
        assertDense(yesterday)
        assertEquals(1440, coverOf(yesterday))
    }

    @Test
    fun `拆分出来的那半连备注和标签一起复制`() = runBlocking {
        // ⚠️ 这里跟原型不同：原型拆出来的新段只带名字和标签，备注丢了。
        //    说不通 —— 两半本来就是同一件事，凭什么后半段的备注没了。
        val a = item("打工")
        val g = tag("高价值投资")
        val ids = lay(yesterday, Seg(600, 720, a, note = "修 bug", tags = listOf(g)))

        timeline.split(ids[0])

        val tail = timeline.dayRecords(yesterday).single { it.id != ids[0] && it.itemId == a }
        assertEquals("修 bug", tail.note)
        assertEquals(listOf(g), tagIdsOf(tail.id))
    }

    @Test
    fun `太短的记录拆不开`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(600, 601, a))

        val out = timeline.split(ids[0])

        assertTrue(out is Outcome.Blocked)
        assertDense(yesterday)
    }

    @Test
    fun `拆分一条进行中的记录_计时状态搬给后半段`() = runBlocking {
        val a = item("打工")
        timeline.ensureDense(today)
        timeline.pick(a, today)
        clock.atMinute(900) // 让它先长出 30 分钟，不然零长度的东西拆不开
        val id = liveId(today)

        timeline.split(id)

        val list = timeline.dayRecords(today).filter { it.itemId == a }
        assertEquals(2, list.size)
        assertNull("后半段继续计时", list.last().endMin)
        assertNotNull("前半段要封口", list.first().endMin)
        assertDense(today)
    }

    // ======================================================== 空白并入邻居

    @Test
    fun `空白并入上一条_邻居的起点被往前拉`() = runBlocking {
        val a = item("打工")
        lay(yesterday, Seg(600, 720, a)) // 其余是空白：0-600 和 720-1440
        val gapBefore = gapAt(yesterday, 300)

        val out = timeline.mergeGapInto(gapBefore, dir = -1) // -1 = 时间线上面 = 更晚

        assertTrue(out.isOk)
        val host = timeline.dayRecords(yesterday).single { it.itemId == a }
        assertEquals(0, host.startMin)
        assertEquals(720, host.endMin)
        assertDense(yesterday)
        assertEquals(1440, coverOf(yesterday))
    }

    @Test
    fun `空白并入下一条_邻居的终点被往后推`() = runBlocking {
        val a = item("打工")
        lay(yesterday, Seg(600, 720, a))
        val gapAfter = gapAt(yesterday, 1000)

        timeline.mergeGapInto(gapAfter, dir = 1) // +1 = 时间线下面 = 更早

        val host = timeline.dayRecords(yesterday).single { it.itemId == a }
        assertEquals(600, host.startMin)
        assertEquals(1440, host.endMin)
        assertDense(yesterday)
    }

    @Test
    fun `真实记录不能走空白并入那条路`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(600, 660, a))

        val out = timeline.mergeGapInto(ids[0], dir = 1)

        assertTrue(out is Outcome.Blocked)
    }

    @Test
    fun `一天只有一条空白时并入会被拦下`() = runBlocking {
        timeline.ensureDense(yesterday)
        val only = timeline.dayRecords(yesterday).single().id

        val up = timeline.mergeGapInto(only, dir = -1)
        val down = timeline.mergeGapInto(only, dir = 1)

        assertTrue(up is Outcome.Blocked)
        assertTrue(down is Outcome.Blocked)
        assertDense(yesterday)
    }

    // ======================================================== 编辑内容与标签

    @Test
    fun `备注和标签一起落库`() = runBlocking {
        val a = item("打工")
        val g = tag("高价值投资")
        val ids = lay(yesterday, Seg(600, 660, a))

        val out = timeline.editContent(ids[0], "  写完了 M2-B  ", listOf(g))

        assertTrue(out.isOk)
        assertEquals("备注要去掉两头的空格", "写完了 M2-B", record(yesterday, ids[0]).note)
        assertEquals(listOf(g), tagIdsOf(ids[0]))
    }

    @Test
    fun `编辑是整体替换_传空就摘光标签`() = runBlocking {
        val a = item("打工")
        val g = tag("高价值投资")
        val ids = lay(yesterday, Seg(600, 660, a, tags = listOf(g)))

        timeline.editContent(ids[0], "", emptyList())

        assertTrue(tagIdsOf(ids[0]).isEmpty())
        assertEquals("", record(yesterday, ids[0]).note)
    }

    @Test
    fun `挂一个已经不存在的标签不会留下悬空引用`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(600, 660, a))

        val out = timeline.editContent(ids[0], "", listOf("幽灵标签id"))

        assertTrue(out.isOk)
        assertTrue(tagIdsOf(ids[0]).isEmpty())
    }

    @Test
    fun `空白没有备注和标签可编辑`() = runBlocking {
        timeline.ensureDense(yesterday)
        val gapId = gapAt(yesterday, 300)

        val out = timeline.editContent(gapId, "试试", emptyList())

        assertTrue(out is Outcome.Blocked)
    }

    // ======================================================== 删除 + 撤销

    @Test
    fun `删除是原地擦成空白_一天一秒都没少`() = runBlocking {
        val a = item("打工")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 1440))

        val token = (timeline.deleteRecord(ids[1]) as Outcome.Ok).value

        val after = timeline.dayRecords(yesterday)
        assertEquals("擦掉中间那段后，前后两段空白会合并成一条", 1, after.size)
        assertEquals(RecordSource.GAP, after[0].source)
        assertEquals(0, after[0].startMin)
        assertEquals(1440, after[0].endMin)
        assertNull("被擦掉的那条不能再挂事项", after[0].itemId)
        assertDense(yesterday)
        assertEquals(1440, coverOf(yesterday))
        assertTrue("撤销条上要有人话：${token.label}", token.label.contains("已删除"))
    }

    @Test
    fun `撤销把被合并掉的那几条空白也一起还回来`() = runBlocking {
        // ⚠️ 这就是「为什么快照要拍一整天」：擦成空白后前后空白会合并，
        //    合并会把**行真的删掉**。只记被删的那一条的话，撤销回来会缺几行。
        val a = item("打工")
        val ids = lay(yesterday, Seg(0, 600), Seg(600, 660, a), Seg(660, 1440))
        val before = timeline.dayRecords(yesterday)

        val token = (timeline.deleteRecord(ids[1]) as Outcome.Ok).value
        timeline.undo(token)

        val after = timeline.dayRecords(yesterday)
        assertEquals(before.size, after.size)
        assertEquals(a, record(yesterday, ids[1]).itemId)
        assertDense(yesterday)
    }

    @Test
    fun `撤销把被摘掉的标签也挂回来`() = runBlocking {
        val a = item("打工")
        val g = tag("高价值投资")
        val ids = lay(yesterday, Seg(600, 660, a, note = "修 bug", tags = listOf(g)))

        val token = (timeline.deleteRecord(ids[0]) as Outcome.Ok).value
        assertTrue("删除会把标签摘掉", tagIdsOf(ids[0]).isEmpty())

        timeline.undo(token)

        assertEquals(listOf(g), tagIdsOf(ids[0]))
        assertEquals("修 bug", record(yesterday, ids[0]).note)
    }

    @Test
    fun `删除一条刚开就删的记录_连行都不留`() = runBlocking {
        // 同一分钟里点开又删掉（「点错了想撑回」最常见的一种）。
        // 它**不代表任何一段时间**，所以直接清掉行是安全的：没有哪段时间会失去代表。
        // 留成一条 [x, x) 的空白虽然不破密铺，但脏数据里会多一条看不见的记录。
        val a = item("打工")
        timeline.ensureDense(today)
        timeline.pick(a, today)
        val id = liveId(today)

        val token = (timeline.deleteRecord(id) as Outcome.Ok).value

        assertNull("零长度的记录不留行", db.recordDao().byId(id))
        assertDense(today)
        assertEquals(870, coverOf(today))

        timeline.undo(token)

        assertNotNull("撤销要把它带回来", db.recordDao().byId(id))
        assertDense(today)
    }

    @Test
    fun `删除一条正在进行的记录_那段时间立刻变空白`() = runBlocking {
        val a = item("打工")
        timeline.ensureDense(today)
        timeline.pick(a, today)
        clock.atMinute(900) // 让它已经记了 30 分钟，才有东西可删
        val id = liveId(today)

        timeline.deleteRecord(id)

        // 擦成空白后与前面那段空白连成一片，合并成一条
        val after = timeline.dayRecords(today)
        assertEquals(1, after.size)
        assertEquals(RecordSource.GAP, after.single().source)
        assertEquals(0, after.single().startMin)
        assertEquals(900, after.single().endMin)
        assertDense(today)
        assertEquals("那 30 分钟一秒都没少", 900, coverOf(today))
    }

    @Test
    fun `空白本来就没有可删的`() = runBlocking {
        timeline.ensureDense(yesterday)
        val gapId = gapAt(yesterday, 300)

        val out = timeline.deleteRecord(gapId)

        assertTrue(out is Outcome.Blocked)
    }

    @Test
    fun `删掉今天唯一的那条记录后依然是密铺的`() = runBlocking {
        val a = item("打工")
        // 今天此刻是 14:30，所以只铺到 870
        val ids = lay(today, Seg(0, 870, a), full = 870)
        timeline.deleteRecord(ids[0])

        assertDense(today)
        assertEquals(870, coverOf(today))
    }
}
