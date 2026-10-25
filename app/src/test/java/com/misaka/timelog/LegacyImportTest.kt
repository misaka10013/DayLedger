package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.import.ImportPreview
import com.misaka.timelog.data.import.LegacyImport
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Density
import com.misaka.timelog.domain.ImportDay
import com.misaka.timelog.domain.ImportReport
import com.misaka.timelog.domain.ImportSegment
import com.misaka.timelog.domain.MINUTES_PER_DAY
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.valueOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.time.LocalDate

/**
 * 旧数据导入的测试 —— **一份完整数据，一次完整导入**。
 *
 * ## 为什么这个测试跟别的长得不一样
 *
 * 别的测试都是「造几条记录，验一条规则」。这个不一样：它读的是**随包出厂的那份
 * 内置文件**（`assets/legacy_timelog.json`，60 天、1011 段、86400 分钟），
 * 走一遍**真实的**导入流程。
 *
 * 理由：导入这件事的风险不在「单条逻辑对不对」，而在**规模** ——
 * 几十天里只要有一天接不上，那天的统计就永远错位，而且不报错。
 * 只有拿整份出厂数据全量跑一遍，才谈得上验过。
 *
 * ⚠️ 这份出厂文件是**合成演示数据**（由 `tools/gen_demo_data.py` 生成），不是谁的真实记录。
 *    所以这里的期望值全部**从 JSON 自己算**，而不是写死一串数字 ——
 *    以后换一份演示数据，改一下生成脚本就行，测试不用跟着改。
 *
 * ## 断言分五组
 *
 * 1. **密铺**：每一天 `Density.check` 全空（这是这个 App 的地基不变式）
 * 2. **账目**：段数 / 空白分钟 / 总分钟三方对得上，且跟 `preview` 报的数一致
 * 3. **挂载**：没有孤儿标签、没有查不到的事项、跨天链两头都在
 * 4. **逐段对账**：内置 JSON 里怎么写的，库里就必须一模一样
 * 5. **安全性**：预览不写库、失败整包不写、重复导入不翻倍、能撤销
 */
@RunWith(RobolectricTestRunner::class)
class LegacyImportTest {

    /** 测试里的「今天」——**必须晚于**内置数据里最后一天（2026-10-14）。 */
    private val today = "2026-10-15"

    private lateinit var context: Context
    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var importer: LegacyImport

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            // ★ 跟生产同一份种子回调 —— 内置数据的 33 个事项里，29 个本该在这里就有
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(today, 600) // 今天 10:00
        timeline = TimelineRepository(db, clock)
        importer = LegacyImport(db, timeline, context.assets)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun allRecords(): List<RecordEntity> {
        // 几百天一天一天查会磨死人，用「区间查询」一次捞回来（统计页也是这么干的）
        return timeline.recordsBetween("0000-01-01", "9999-12-31")
    }

    /** 内置文件的原文。用来把**期望值从文件里算出来**，而不是写死一串数字。 */
    private fun assetText(): String =
        context.assets.open(LegacyImport.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val assetJson: JSONObject by lazy { JSONObject(assetText()) }

    /** 出厂数据一共几天 —— 从 JSON 数，不在测试里写死。 */
    private val assetDays: Int get() = assetJson.getJSONArray("days").length()

    /** 出厂数据一共几段。 */
    private val assetSegments: Int
        get() {
            val days = assetJson.getJSONArray("days")
            return (0 until days.length()).sumOf { days.getJSONObject(it).getJSONArray("s").length() }
        }

    /**
     * 把几千个 id 一次塞进 `IN (?,…)` 会撞 SQLite 的占位符上限（999），
     * 所以这里**必须分批** —— 顺带也就是在验生产那侧的分批写对了。
     */
    private suspend fun refsOf(records: List<RecordEntity>) =
        records.map { it.id }.chunked(500).flatMap { db.tagDao().refsOfRecords(it) }

    /** 绕过 DAO 直接数一张表有几行 —— 专治「没有对应查询方法」的场合。 */
    private fun rawCount(table: String): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private suspend fun previewOrFail(): ImportPreview {
        val p = importer.preview()
        assertTrue("预览就该成功，实际是 ${p}", p is Outcome.Ok)
        return (p as Outcome.Ok).value
    }

    private suspend fun runOrFail(): ImportReport {
        val r = importer.run()
        assertTrue("导入就该成功，实际是 ${r}", r is Outcome.Ok)
        return (r as Outcome.Ok).value
    }

    private suspend fun assertDenseEverywhere(records: List<RecordEntity>) {
        val byDay = records.groupBy { it.day }
        val bad = mutableListOf<String>()
        byDay.forEach { (day, rows) ->
            val problems = Density.check(rows, MINUTES_PER_DAY)
            if (problems.isNotEmpty()) bad += "$day → $problems"
        }
        assertTrue("有 ${bad.size} 天没接上：${bad.take(3)}", bad.isEmpty())
    }

    // ======================================================== ① 密铺

    @Test
    fun `导入后每一天都严丝合缝`() = runBlocking {
        val report = runOrFail()
        val all = allRecords()

        assertEquals(assetDays, report.days)
        assertDenseEverywhere(all)

        // 再核一遍「每天恰好 1440 分钟」—— 用记录自己算，不看报告
        val byDay = all.groupBy { it.day }
        assertEquals(assetDays, byDay.size)
        byDay.forEach { (day, rows) ->
            val minutes = rows.sumOf { (it.endMin ?: 0) - it.startMin }
            assertEquals("$day 加起来不是 1440 分钟", MINUTES_PER_DAY, minutes)
        }
    }

    // ======================================================== ② 账目

    @Test
    fun `账目三方对得上`() = runBlocking {
        val preview = previewOrFail()
        val report = runOrFail()
        val all = allRecords()

        // 报告 vs 预览：**两套实现算出来的空白必须一模一样**。
        // 「预览是给人看的、报告是给账看的」，两个数字对不上就是 bug，不是差不多。
        assertEquals(preview.days, report.days)
        assertEquals(preview.segments, report.segments)
        assertEquals(preview.gapCount, report.gaps)
        assertEquals(preview.gapMinutes, report.gapMinutes)

        // 报告 vs 库里真躺着的
        val realSegments = all.count { it.source != RecordSource.GAP }
        val realGaps = all.count { it.source == RecordSource.GAP }
        assertEquals(report.segments, realSegments)
        assertEquals(report.gaps, realGaps)
        assertEquals(MINUTES_PER_DAY * report.days, report.totalMinutes)

        // 恒等式：这些天一共 = 真实记录 + 空白，一分钟都不能少
        val realMinutes = all.sumOf { (it.endMin ?: 0) - it.startMin }
        assertEquals(report.totalMinutes, realMinutes)

        // 内置文件自己的账：真实记录段加起来 = 总分钟 − 空白分钟
        val segMinutes = all.filter { it.source != RecordSource.GAP }
            .sumOf { (it.endMin ?: 0) - it.startMin }
        assertEquals(report.totalMinutes - report.gapMinutes, segMinutes)
        // ⚠️ 出厂数据是**密铺**的（一天一秒不少），所以空白应当为 0。
        //    这行本身就是一个断言：生成脚本只要哪天漏填了一块，这里就红。
        assertEquals(0, report.gapMinutes)
        assertEquals(assetSegments, report.segments)
    }

    // ======================================================== ③ 挂载

    @Test
    fun `没有孤儿标签_也没有查不到的事项`() = runBlocking {
        runOrFail()
        val all = allRecords()

        val tagIds = db.tagDao().all().map { it.id }.toSet()
        val refs = refsOf(all)
        refs.forEach { ref ->
            assertTrue("记录 ${ref.recordId} 挂着不存在的标签 ${ref.tagId}", ref.tagId in tagIds)
        }
        assertTrue("一条标签关联都没有，那这测试等于没测", refs.isNotEmpty())
        val aliveIds = all.map { it.id }.toSet()
        assertEquals(
            "有标签关联挂在已经不存在的记录上（孤儿行）",
            0,
            refs.count { it.recordId !in aliveIds },
        )

        val itemIds = db.itemDao().all().map { it.id }.toSet()
        all.filter { it.source != RecordSource.GAP }.forEach { r ->
            assertNotNull("$r 没有事项", r.itemId)
            assertTrue("记录挂了不存在的事项 ${r.itemId}", r.itemId in itemIds)
        }

        // 空白必须是「真空白」：没有事项、也没有标签
        all.filter { it.source == RecordSource.GAP }.forEach { g ->
            assertNull("空白不该挂事项：$g", g.itemId)
            assertTrue("空白不该有标签：$g", db.tagDao().tagIdsOfRecord(g.id).isEmpty())
        }
    }

    @Test
    fun `跨天链两头都在_而且是缝为零的相邻两天`() = runBlocking {
        runOrFail()
        val all = allRecords()

        val byChain = all.filter { it.chainId != null }.groupBy { it.chainId!! }
        assertTrue("一条跨天链都没有，那这测试等于没测", byChain.isNotEmpty())

        byChain.forEach { (cid, list) ->
            assertTrue("链 $cid 只有 ${list.size} 段 —— 半条链比没有链更糟", list.size >= 2)

            val ordered = list.sortedBy { it.day }
            assertEquals(
                "链 $cid 在同一个日子里出现了不止一段：${ordered.map { it.day }}",
                ordered.size,
                ordered.map { it.day }.toSet().size,
            )

            for (k in 1 until ordered.size) {
                val prev = ordered[k - 1]
                val cur = ordered[k]
                val gap = LocalDate.parse(cur.day).toEpochDay() -
                    LocalDate.parse(prev.day).toEpochDay()
                assertEquals("链 $cid 的两段不在相邻两天", 1L, gap)
                assertEquals("链 $cid 上一天没到 24:00", MINUTES_PER_DAY, prev.endMin)
                assertEquals("链 $cid 下一天不是从 00:00 起", 0, cur.startMin)
                assertEquals("链 $cid 上下两天事项不一样", prev.itemId, cur.itemId)
            }
        }

        // ★ 反方向也要卡：缝为 0 的相邻跨天**必须**被连上，不能漏
        val byDay = all.groupBy { it.day }
        val chainOf = all.associate { it.id to it.chainId }
        val missed = mutableListOf<String>()
        val days = byDay.keys.sorted()
        for (k in 1 until days.size) {
            val nextDay = LocalDate.parse(days[k])
            if (nextDay.toEpochDay() - LocalDate.parse(days[k - 1]).toEpochDay() != 1L) continue
            val tail = byDay.getValue(days[k - 1]).maxByOrNull { it.startMin } ?: continue
            val head = byDay.getValue(days[k]).minByOrNull { it.startMin } ?: continue
            if (tail.endMin == MINUTES_PER_DAY && head.startMin == 0 && tail.itemId == head.itemId) {
                if (chainOf[tail.id] == null || chainOf[tail.id] != chainOf[head.id]) {
                    missed += "${days[k - 1]} → ${days[k]}"
                }
            }
        }
        assertEquals("这些跨天接缝没连上链：$missed", 0, missed.size)
    }

    // ======================================================== ④ 抽查

    @Test
    fun `入库结果跟内置文件逐段一致`() = runBlocking {
        runOrFail()

        // ★ 这条比「写死某天某时刻」强得多：
        //   它把内置 JSON 和数据库**一段一段对**，一共几百段，
        //   任何一段的起止、事项、顺序被写歪都会当场红。
        //   而且换了演示数据也不用改测试 —— 期望值就是从那份文件里读的。
        val days = assetJson.getJSONArray("days")
        var checkedSegs = 0

        for (k in 0 until days.length()) {
            val d = days.getJSONObject(k)
            val day = d.getString("d")
            val segs = d.getJSONArray("s")
            val rows = timeline.dayRecords(day)

            assertEquals("$day 的段数对不上", segs.length(), rows.size)
            for (j in 0 until segs.length()) {
                val s = segs.getJSONObject(j)
                val r = rows[j]
                assertEquals("$day 第 ${j + 1} 段起点", s.getInt("a"), r.startMin)
                assertEquals("$day 第 ${j + 1} 段终点", s.getInt("b"), r.endMin)
                assertEquals("$day 第 ${j + 1} 段事项", s.getString("i"), nameOf(r.itemId))
            }
            checkedSegs += segs.length()
        }

        assertEquals("比对过的段数应当等于文件里的总段数", assetSegments, checkedSegs)
    }

    @Test
    fun `每天都是从 0 铺到 1440_没有残留空白`() = runBlocking {
        runOrFail()

        val days = assetJson.getJSONArray("days")
        for (k in 0 until days.length()) {
            val day = days.getJSONObject(k).getString("d")
            val rows = timeline.dayRecords(day)
            assertEquals("$day 第一条不是从 00:00 开始", 0, rows.first().startMin)
            assertEquals("$day 最后一条不是到 24:00 结束", MINUTES_PER_DAY, rows.last().endMin)
            // 出厂数据密铺 → 不该有任何 GAP
            assertTrue(
                "$day 出现了空白段，但出厂数据是密铺的",
                rows.none { it.source == RecordSource.GAP },
            )
        }

        // 预览报的首末日期跟文件一致
        val preview = previewOrFail()
        assertEquals(days.getJSONObject(0).getString("d"), preview.firstDay)
        assertEquals(days.getJSONObject(days.length() - 1).getString("d"), preview.lastDay)
    }

    private suspend fun nameOf(itemId: String?): String =
        itemId?.let { db.itemDao().byId(it)?.name } ?: "（无）"

    // ======================================================== ⑤ 安全性

    @Test
    fun `预览是只读的_不建事项也不动记录`() = runBlocking {
        val before = db.itemDao().all().size
        val preview = previewOrFail()

        assertEquals("预览不该建事项", before, db.itemDao().all().size)
        assertTrue("预览不该写记录", allRecords().isEmpty())

        // 那些「App 里没有」的事项应当被点名（顺序 = 内置文件里 newItems 的顺序）
        assertEquals(expectedNewItems().map { it.first }, preview.toCreate)
        assertTrue("不该有彻底找不到的事项", preview.hardMissingItems.isEmpty())
        assertTrue("不该有认不出的标签", preview.missingTags.isEmpty())
        assertEquals("App 里一开始一条记录都没有", 0, preview.existingRecords)
    }

    /** 内置文件里「需要现场新建」的事项：清单 → 分类 id。 */
    private fun expectedNewItems(): List<Pair<String, String>> {
        val arr = assetJson.getJSONArray("newItems")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("name") to o.getString("categoryId")
        }
    }

    @Test
    fun `导入会把内置文件点名的那些新事项建出来_而且挂在对的分类下`() = runBlocking {
        val expect = expectedNewItems()
        assertTrue("这份出厂数据本来就应该带几个新事项，否则这条测试等于没测", expect.isNotEmpty())

        val before = db.itemDao().all().size
        runOrFail()

        assertEquals("应当正好新建 ${expect.size} 个事项", before + expect.size, db.itemDao().all().size)

        val catOfItem = db.itemDao().all().associate { it.name to it.categoryId }
        val catName = db.categoryDao().all().associate { it.id to it.name }
        expect.forEach { (name, catId) ->
            assertEquals("事项「$name」没挂在 JSON 指定的分类下", catId, catOfItem[name])
            assertNotNull("分类 $catId 不存在", catName[catId])
        }
    }

    @Test
    fun `重复导入不会翻倍`() = runBlocking {
        val first = runOrFail()
        val itemsAfterFirst = db.itemDao().all().size
        val second = runOrFail()

        assertEquals(first.days, second.days)
        assertEquals(first.segments, second.segments)
        assertEquals("重复导入又把事项建了一遍", itemsAfterFirst, db.itemDao().all().size)
        assertEquals(
            "记录数翻倍了，说明导入前没清空",
            first.segments + first.gaps,
            allRecords().size,
        )
    }

    @Test
    fun `导入会留一份快照_撤销能把原来的记录还回来`() = runBlocking {
        // 先放一条「导入前就在」的记录（模拟用户已经记过几笔）
        val cat = db.categoryDao().all().first()
        val item = db.itemDao().all().first { it.categoryId == cat.id }
        val old = RecordEntity(
            id = "old-record-1",
            day = today,
            startMin = 0,
            endMin = 600,
            itemId = item.id,
            categoryIdSnap = cat.id,
            source = RecordSource.MANUAL,
            chainId = null,
            noTemplate = false,
            note = "导入之前就有的",
            updatedAt = 1L,
            deleted = false,
        )
        db.recordDao().insert(old)

        assertFalse("还没导入过，不该有快照", importer.hasSnapshot())

        val report = runOrFail()
        assertTrue("导入之后必须有快照可回退", importer.hasSnapshot())
        assertNull("导入会把原来那条清掉", db.recordDao().byId("old-record-1"))
        assertEquals(assetDays, report.days)

        // 撤销
        val back = importer.undo()
        assertTrue(back is Outcome.Ok)
        assertEquals(1, back.valueOrNull())

        val restored = db.recordDao().byId("old-record-1")
        assertNotNull("撤销之后原来那条应当回来", restored)
        assertEquals("导入之前就有的", restored!!.note)
        assertTrue("撤销之后不该还留着导入进来的数据", allRecords().size == 1)

        // 快照是一次性的
        assertFalse("回退完就该把快照删掉", importer.hasSnapshot())
    }

    @Test
    fun `清空整张表时_标签关联会跟着走`() = runBlocking {
        // ⚠️ 这一条是在验「外键 CASCADE 真的生效」，不是验我以为它生效。
        //    整库替换靠的就是它 —— 要是它其实没开，record_tags 里会留一堆
        //    指向已经消失的记录的孤儿行，而且**不报错**。
        runOrFail()
        assertTrue(db.tagDao().all().isNotEmpty())

        db.recordDao().deleteAll()

        assertTrue("records 没清干净", allRecords().isEmpty())
        assertEquals("records 清了但 record_tags 还留着孤儿行 —— 外键 CASCADE 没生效",
            0, rawCount("record_tags"))
        // 当然，字典本身一条都不该动
        assertTrue("清记录把标签字典也清了？", db.tagDao().all().isNotEmpty())
    }

    @Test
    fun `今天的和未来的日子会被跳过_不报错`() = runBlocking {
        val itemId = db.itemDao().all().first().id
        val spec = listOf(
            day("2026-10-14", seg(0, 100, itemId)),
            day(today, seg(0, 100, itemId)),          // 今天 → 跳过
            day("2026-10-16", seg(0, 100, itemId)),   // 未来 → 跳过
        )

        val outcome = timeline.replaceAllRecords(spec, "测试")
        assertTrue("不该被拦下，实际 $outcome", outcome is Outcome.Ok)
        val report = (outcome as Outcome.Ok).value

        assertEquals(1, report.days)
        assertEquals(listOf(today, "2026-10-16"), report.skippedDays)

        val all = allRecords()
        assertEquals("只该有 2026-10-14 这一天", setOf("2026-10-14"), all.map { it.day }.toSet())
        assertDenseEverywhere(all)
    }

    @Test
    fun `数据有问题时整包不写`() = runBlocking {
        val itemId = db.itemDao().all().first().id
        val spec = listOf(
            day("2026-10-14", seg(0, 500, itemId), seg(400, 600, itemId)), // 重叠
        )

        val outcome = timeline.replaceAllRecords(spec, "测试")
        assertTrue("重叠的包应当被拦下", outcome is Outcome.Blocked)
        assertTrue("拦下了却还是写了东西", allRecords().isEmpty())
    }

    @Test
    fun `导入时事项在 App 里被删掉了_就拦下整包`() = runBlocking {
        // ★ 这一条守的是「宁可不导，也不写一条不知道该挂到哪的记录」。
        //   真实的场景：用户在设置页把某个事项删了，而内置的那份历史数据里还有它。
        val victim = db.itemDao().all().first { it.name == "刷资讯" }
        db.itemDao().deleteById(victim.id)

        val outcome = importer.run()
        assertTrue("事项没了应当拦下，实际 $outcome", outcome is Outcome.Blocked)
        assertTrue("拦下了却还是写了东西", allRecords().isEmpty())
        assertTrue(
            "拦下的话里应当点名是哪个事项：" + (outcome as Outcome.Blocked).message,
            outcome.message.contains("刷资讯"),
        )
    }

    // ---------------------------------------------------------------- 构造小包

    private fun day(d: String, vararg segs: ImportSegment) = ImportDay(d, segs.toList())

    private fun seg(a: Int, b: Int, itemId: String) = ImportSegment(
        startMin = a,
        endMin = b,
        itemId = itemId,
        note = "",
        tagIds = emptyList(),
        chainId = null,
    )
}
