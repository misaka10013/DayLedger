package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.export.DataExport
import com.misaka.timelog.data.export.ExportBundle
import com.misaka.timelog.data.export.ExportCategory
import com.misaka.timelog.data.export.ExportDay
import com.misaka.timelog.data.export.ExportItem
import com.misaka.timelog.data.export.ExportSegment
import com.misaka.timelog.data.export.ExportStats
import com.misaka.timelog.data.export.ExportTag
import com.misaka.timelog.data.import.LegacyImport
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.valueOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * 导出的测试 —— **一份备份只有「能读回去」才算数**。
 *
 * ## 为什么这个测试的核心是「往返」而不是「字段对不对」
 *
 * 导出功能最容易出的错不是「少写一个字段」，而是**写出一份自己都读不回来的文件**：
 * 结构看着像、用编辑器打开也像，一点导入就报「找不到事项」。
 * 而这类错在界面上表现得很温和（"保存成功"），只有真的拿它恢复一次才会暴露。
 *
 * 所以这里的头号断言是：**导入内置数据 → 导出 → 用导出的文件再导入 → 天数/段数/总分钟数一模一样**。
 *
 * ⚠️ 三条规则在这份文件里被反复钉住：
 * 1. **备份 JSON 里不写空白段**（写了导入会整包被拦下，因为「未记录」不是事项名）
 * 2. **给 AI 的 Markdown 里必须写空白段**（对 AI 来说「这段时间没记」是信息，不是缺失）
 * 3. **`newItems` 必须覆盖记录里出现的每一个事项名**（否则重装后导不回来）
 */
@RunWith(RobolectricTestRunner::class)
class DataExportTest {

    /** 测试里的「今天」——**必须晚于**内置数据里最后一天（2026-10-14）。 */
    private val today = "2026-10-15"

    private lateinit var context: Context
    private lateinit var db: TimeLogDatabase
    private lateinit var clock: FakeClock
    private lateinit var timeline: TimelineRepository
    private lateinit var importer: LegacyImport
    private lateinit var exporter: DataExport

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock.at(today, 600)
        timeline = TimelineRepository(db, clock)
        importer = LegacyImport(db, timeline, context.assets)
        exporter = DataExport(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ================================================================ 往返

    /**
     * ★★ 最重要的一条：**导出的备份能被自己读回去，而且一条不差**。
     *
     * 这就是「换手机 / 重装 / 换签名版本」时数据不丢的全部依据。
     */
    @Test
    fun `导出再导入_天数段数总分钟数完全一致`() = runBlocking {
        // ① 先当真用户用一次：把内置演示数据导进来
        val first = (importer.run() as Outcome.Ok).value

        // ② 导出
        val bundle = exporter.collect()
        val json = exporter.backupJson(bundle)
        assertTrue("导出的文件不该是空的", json.length > 1000)

        // ③ 再导回去（`replaceAllRecords` 会整库替换，等价于"装了新 App 再导入"）
        val back = importer.runOf(json)
        assertTrue("导出的备份必须能被自己读回来：$back", back is Outcome.Ok)
        val second = (back as Outcome.Ok).value

        // ④ 对账 —— 这三项相等 = 数据完整
        assertEquals("天数对不上", first.days, second.days)
        assertEquals("真实段数对不上", first.segments, second.segments)
        assertEquals("总分钟数对不上", first.totalMinutes, second.totalMinutes)
    }

    /**
     * 往返之后**每一天都还是密铺的 1440 分钟**。
     *
     * 单看总数相等还不够：段数一样、总数一样，也可能是「A 天的段跑到 B 天去了」。
     */
    @Test
    fun `往返之后每一天都还是 1440 分钟`() = runBlocking {
        importer.run()
        val json = exporter.backupJson(exporter.collect())
        importer.runOf(json)

        val days = exporter.collect().days
        assertTrue("应该有若干天数据", days.isNotEmpty())
        days.forEach { d ->
            val total = d.segments.sumOf { it.endMin - it.startMin }
            assertEquals("${d.day} 不是 1440 分钟", 1440, total)
        }
    }

    // ================================================================ 备份格式

    /** 备份里**不能**出现空白段 —— 否则导入器会把「未记录」当成查不到的事项，整包拦下。 */
    @Test
    fun `备份 JSON 里一段空白都不写`() = runBlocking {
        val json = JSONObject(exporter.backupJson(bundleWithGap()))

        val days = json.getJSONArray("days")
        assertEquals(1, days.length())
        val segs = days.getJSONObject(0).getJSONArray("s")

        // 三段的场景里有一整段是空白，写进去应该只剩两段
        assertEquals("空白段不该被写进备份", 2, segs.length())

        // 而且这两段的边界必须能接得上（导入器靠它们算缝隙）
        assertEquals(0, segs.getJSONObject(0).getInt("a"))
        assertEquals(480, segs.getJSONObject(0).getInt("b"))
        assertEquals(600, segs.getJSONObject(1).getInt("a"))
        assertEquals(1440, segs.getJSONObject(1).getInt("b"))
    }

    /** 每段的 `a` / `b` / `i` 必须齐 —— 导入器对这三个字段是 `getInt` / `getString`（缺了直接抛异常）。 */
    @Test
    fun `备份里每一段都带齐了导入器必需的字段`() = runBlocking {
        val json = JSONObject(exporter.backupJson(bundleWithGap()))
        val segs = json.getJSONArray("days").getJSONObject(0).getJSONArray("s")

        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            // 这三行只要有一个字段缺失就会抛 JSONException，测试当场红
            s.getInt("a")
            s.getInt("b")
            val name = s.getString("i")
            assertTrue("事项名不该是空的", name.isNotEmpty())
        }
    }

    /**
     * ★ `newItems` 必须覆盖**记录里出现过的所有事项名**。
     *
     * 这是「重装后导不回来」的根因：目标 App 只有种子事项，用户在旧机器上新加/改名的
     * 那些一个都认不出，导入器就会报「有几个事项在 App 里找不到」并整包拒绝。
     */
    @Test
    fun `备份的 newItems 覆盖了记录里出现的每一个事项`() = runBlocking {
        importer.run()
        val json = JSONObject(exporter.backupJson(exporter.collect()))

        // 记录里出现过的事项名
        val used = linkedSetOf<String>()
        val days = json.getJSONArray("days")
        for (i in 0 until days.length()) {
            val segs = days.getJSONObject(i).getJSONArray("s")
            for (j in 0 until segs.length()) used += segs.getJSONObject(j).getString("i")
        }

        // newItems 声明会建的事项名
        val declared = linkedSetOf<String>()
        val ni = json.getJSONArray("newItems")
        for (i in 0 until ni.length()) declared += ni.getJSONObject(i).getString("name")

        val missing = used - declared
        assertTrue("这些事项没写进 newItems，重装后导入会失败：$missing", missing.isEmpty())
    }

    /** 一个事项都不少的另一个保证：`newItems` 里每一项都要能对上一个分类。 */
    @Test
    fun `newItems 里每一项都带了分类信息`() = runBlocking {
        importer.run()
        val json = JSONObject(exporter.backupJson(exporter.collect()))
        val ni = json.getJSONArray("newItems")
        assertTrue(ni.length() > 0)
        for (i in 0 until ni.length()) {
            val o = ni.getJSONObject(i)
            assertTrue("${o.getString("name")} 没有 categoryId", o.has("categoryId"))
            assertTrue("${o.getString("name")} 没有 categoryName", o.has("categoryName"))
        }
    }

    // ================================================================ 给 AI 的 Markdown

    /** 空白段**必须**出现在 Markdown 里 —— 漏掉它 AI 会把前后两段当成连续的一整块。 */
    @Test
    fun `给 AI 的文本里写了未记录那一段`() {
        val md = exporter.aiMarkdown(bundleWithGap())
        assertTrue("没有标出未记录：\n$md", md.contains("▨ 未记录"))
        // 那一段的起止时刻也要可读
        assertTrue(md.contains("08:00-10:00"))
    }

    /** 摘要表要在明细**前面** —— AI 读长文时前段权重最高。 */
    @Test
    fun `汇总表排在逐天明细之前`() {
        val md = exporter.aiMarkdown(bundleWithGap())
        val catAt = md.indexOf("## 按分类汇总")
        val itemAt = md.indexOf("## 按事项汇总")
        val dayAt = md.indexOf("## 逐天明细")
        assertTrue("缺按分类汇总", catAt > 0)
        assertTrue("缺按事项汇总", itemAt > 0)
        assertTrue("缺逐天明细", dayAt > 0)
        assertTrue("顺序错了：分类($catAt) → 事项($itemAt) → 明细($dayAt)", catAt < itemAt && itemAt < dayAt)
    }

    /** 备注和标签要带出去 —— 它们是「这段时间在干什么」的细节，导出丢了就永远找不回。 */
    @Test
    fun `备注和标签都会写进给 AI 的文本`() {
        val md = exporter.aiMarkdown(bundleWithGap())
        assertTrue("备注丢了：\n$md", md.contains("备注：赶版本"))
        assertTrue("标签丢了：\n$md", md.contains("标签：高价值投资"))
    }

    /** 抬头必须点明「一天恒 1440」—— 否则 AI 会把空白当成数据缺失，得出"漏记了很多"的结论。 */
    @Test
    fun `抬头说明了未记录也是一等公民`() {
        val md = exporter.aiMarkdown(bundleWithGap())
        assertTrue(md.contains("1440"))
        assertTrue(md.contains("未记录"))
    }

    // ================================================================ 自建分类的重建

    /**
     * ★ 备份来自另一台设备、里面有用户自建的分类 → **导入时要把分类建出来**。
     *
     * 真实场景：御坂库里有一个 UUID 的「学习」分类，挂了 4 个事项。
     * 换手机后那个 id 在新库里根本不存在，光靠 `categoryId` 找是找不到的。
     */
    @Test
    fun `自建分类会按备份里的名字和颜色重建出来`() = runBlocking {
        val json = """
            {
              "version": 1,
              "newItems": [
                {
                  "name": "考试刷题",
                  "categoryId": "self-cat-1",
                  "categoryName": "学习",
                  "categoryColorHex": "#123456"
                }
              ],
              "days": [
                { "d": "2026-10-01", "s": [ { "a": 0, "b": 1440, "i": "考试刷题" } ] }
              ]
            }
        """.trimIndent()

        // 导入之前，库里没有「学习」这个分类
        assertTrue(db.categoryDao().all().none { it.name == "学习" })

        val report = importer.runOf(json)
        assertTrue("导入该成功：$report", report is Outcome.Ok)

        // 分类被建出来了，名字和颜色都对
        val cat = db.categoryDao().all().firstOrNull { it.name == "学习" }
        assertNotNull("自建分类没被重建", cat)
        assertEquals("颜色没跟着备份走", "#123456", cat!!.colorHex)

        // 事项挂在它下面
        val item = db.itemDao().all().firstOrNull { it.name == "考试刷题" }
        assertNotNull("事项没建出来", item)
        assertEquals("事项没挂到重建的分类下", cat.id, item!!.categoryId)
    }

    /** 同一份备份导两次，**分类不会翻倍**（`firstOrNull { it.id == ... }` 会先命中已有的）。 */
    @Test
    fun `自建分类重复导入不会建出两个`() = runBlocking {
        val json = """
            {
              "newItems": [
                { "name": "考试刷题", "categoryId": "self-cat-1", "categoryName": "学习", "categoryColorHex": "#123456" }
              ],
              "days": [ { "d": "2026-10-01", "s": [ { "a": 0, "b": 1440, "i": "考试刷题" } ] } ]
            }
        """.trimIndent()

        importer.runOf(json)
        importer.runOf(json)

        assertEquals(
            "同名分类被建出了多个",
            1,
            db.categoryDao().all().count { it.name == "学习" },
        )
    }

    /** 导入一份根本不是备份的文本 → **拦下并说人话**，不许抛异常。 */
    @Test
    fun `导入乱文件会被拦下而不是崩掉`() = runBlocking {
        val r1 = importer.previewOf("这不是 JSON，只是一段随便的文字")
        assertTrue("该被拦下", r1 is Outcome.Blocked)

        val r2 = importer.previewOf("""{"foo": 1}""")
        assertTrue("缺 days/newItems 也该被拦下", r2 is Outcome.Blocked)

        val r3 = importer.runOf("")
        assertTrue("空文件也该被拦下", r3 is Outcome.Blocked)
    }

    // ================================================================ 工具

    /**
     * 一份手搓的素材：一天三段，**中间那两小时是空白**。
     *
     * 为什么不从数据库里造：这里要验的是「渲染规则」（空白写不写、备注标签带不带），
     * 跟库长什么样无关。手搓一份能把变量降到最少。
     */
    private fun bundleWithGap(): ExportBundle = ExportBundle(
        generatedAtMillis = 1_793_000_000_000L,
        zoneId = "Asia/Shanghai",
        days = listOf(
            ExportDay(
                day = "2026-10-01",
                segments = listOf(
                    ExportSegment(
                        startMin = 0, endMin = 480,
                        itemName = "睡觉", categoryName = "生活必需",
                        note = "", tagNames = emptyList(), chainId = null, isGap = false,
                    ),
                    ExportSegment(
                        startMin = 480, endMin = 600,
                        itemName = null, categoryName = null,
                        note = "", tagNames = emptyList(), chainId = null, isGap = true,
                    ),
                    ExportSegment(
                        startMin = 600, endMin = 1440,
                        itemName = "打工", categoryName = "赚钱营生",
                        note = "赶版本", tagNames = listOf("高价值投资"), chainId = null, isGap = false,
                    ),
                ),
            ),
        ),
        categories = listOf(
            ExportCategory(id = "life", name = "生活必需", colorHex = "#E8A33D", position = 0),
            ExportCategory(id = "work", name = "赚钱营生", colorHex = "#8A9A3C", position = 1),
        ),
        items = listOf(
            ExportItem("i1", "睡觉", "life", "生活必需", "", 0),
            ExportItem("i2", "打工", "work", "赚钱营生", "", 0),
        ),
        tags = listOf(ExportTag("g1", "高价值投资", "t1", "#123456", "")),
        stats = ExportStats(
            days = 1,
            recordedSegments = 2,
            recordedMinutes = 1320,
            gapSegments = 1,
            gapMinutes = 120,
        ),
    )
}
