package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.cloud.groupTagNames
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.dao.RecordTagPair
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.RecordTagEntity
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 上传报文里的**标签与备注**。
 *
 * ## 这个测试在守什么
 *
 * 2026-11-16 发现：云端 2026-08-19 起的 241 天、1774 段**一个标签都没有**。
 * 原因不是「用户不写标签」——本地同期标签覆盖率是 95%——
 * 而是 `SyncEngine` 拼上传报文时**根本没带 `t` 字段**。
 *
 * 这种 bug 的全部杀伤力在于**它不报错**：
 * - App 里标签好好的，同步显示「成功」；
 * - 云端数据结构合法，只是少了一个键；
 * - 只有几个月后拿云端数据做分析，才会发现「标签没了」，而那时已经失去两个月的记录。
 *
 * 所以这里要盯的不是「代码能跑」，而是**「拼出来的东西里到底有没有标签」**。
 * 分成两段测：
 * 1. `pairsOfDays` 这个 SQL —— 写错的话返回空列表，下游全空且不报错；
 * 2. `groupTagNames` 这个分组 —— 写错的话同样是静默退化成空 Map。
 *
 * 报文最终形态（JSON 里真的有 `"t":[...]`）靠真机验收，单测覆盖不到网络层。
 */
@RunWith(RobolectricTestRunner::class)
class SyncTagPayloadTest {

    private val day = "2026-09-05"
    private val nextDay = "2026-09-06"

    private lateinit var db: TimeLogDatabase
    private lateinit var catalog: CatalogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val timeline = TimelineRepository(db, FakeClock.at(day, 870))
        catalog = CatalogRepository(db, timeline)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- 辅助

    private suspend fun itemId(name: String = "打工"): String {
        val cat = (catalog.addCategory("工作", "#F59E0B") as Outcome.Ok).value
        return (catalog.addItem(cat.id, name, "#F59E0B") as Outcome.Ok).value.id
    }

    private suspend fun record(
        id: String,
        d: String,
        a: Int,
        b: Int,
        item: String?,
        note: String = "",
        deleted: Boolean = false,
    ) {
        db.recordDao().insert(
            RecordEntity(
                id = id,
                day = d,
                startMin = a,
                endMin = b,
                itemId = item,
                categoryIdSnap = null,
                source = if (item == null) RecordSource.GAP else RecordSource.MANUAL,
                chainId = null,
                noTemplate = false,
                note = note,
                updatedAt = 0L,
                deleted = deleted,
            )
        )
    }

    /** 建一个桶 + 若干个标签，返回 名字 → id。 */
    private suspend fun tags(vararg names: String): Map<String, String> {
        val bucket = (catalog.addBucket("🏷", "价值") as Outcome.Ok).value
        return names.associateWith { n ->
            (catalog.addTag(bucket.id, n, "#3B82F6", "") as Outcome.Ok).value.id
        }
    }

    private suspend fun attach(recordId: String, tagId: String) {
        db.tagDao().attachToRecord(RecordTagEntity(recordId, tagId))
    }

    // ============================================================ DAO 查询

    @Test
    fun `按天取标签关联_只返回这一天的记录`() = runBlocking {
        val it = itemId()
        val t = tags("能量增益", "日常必需")

        record("r1", day, 0, 480, it)
        record("r2", day, 480, 1440, it)
        record("r3", nextDay, 0, 600, it)

        attach("r1", t["能量增益"]!!)
        attach("r2", t["日常必需"]!!)
        attach("r3", t["能量增益"]!!)   // 隔壁天，不该回来

        val pairs = db.tagDao().pairsOfDays(listOf(day))
        assertEquals("只有 r1、r2 两条关联", 2, pairs.size)
        assertEquals(setOf("r1", "r2"), pairs.map { it.recordId }.toSet())
    }

    @Test
    fun `按天取标签关联_一次问多天就都能取到`() = runBlocking {
        val it = itemId()
        val t = tags("能量增益")

        record("r1", day, 0, 720, it)
        record("r2", nextDay, 0, 720, it)
        attach("r1", t["能量增益"]!!)
        attach("r2", t["能量增益"]!!)

        val pairs = db.tagDao().pairsOfDays(listOf(day, nextDay))
        assertEquals(2, pairs.size)
    }

    @Test
    fun `按天取标签关联_软删的记录不参与`() = runBlocking {
        val it = itemId()
        val t = tags("能量增益")

        record("alive", day, 0, 600, it)
        record("dead", day, 600, 1440, it, deleted = true)
        attach("alive", t["能量增益"]!!)
        attach("dead", t["能量增益"]!!)

        val pairs = db.tagDao().pairsOfDays(listOf(day))
        assertEquals("墓碑不该被传上去", listOf("alive"), pairs.map { it.recordId })
    }

    @Test
    fun `按天取标签关联_没挂标签的天返回空`() = runBlocking {
        val it = itemId()
        record("r1", day, 0, 1440, it)

        assertTrue(db.tagDao().pairsOfDays(listOf(day)).isEmpty())
    }

    // ============================================================ 分组

    @Test
    fun `分组_一条记录挂两个标签会归到一起`() {
        val pairs = listOf(
            RecordTagPair("r1", "t1"),
            RecordTagPair("r1", "t2"),
            RecordTagPair("r2", "t2"),
        )
        val names = mapOf("t1" to "能量增益", "t2" to "日常必需")

        val got = groupTagNames(pairs, names)

        assertEquals(listOf("能量增益", "日常必需"), got["r1"])
        assertEquals(listOf("日常必需"), got["r2"])
    }

    @Test
    fun `分组_查不到名字的标签被丢掉而不是留空串`() {
        val pairs = listOf(
            RecordTagPair("r1", "t1"),
            RecordTagPair("r1", "gone"),     // 标签已被删，名字查不到
        )
        val got = groupTagNames(pairs, mapOf("t1" to "能量增益"))

        assertEquals("空标签名会让云端多出一个「」标签", listOf("能量增益"), got["r1"])
    }

    @Test
    fun `分组_没有标签的记录不出现在结果里`() {
        val got = groupTagNames(emptyList(), mapOf("t1" to "能量增益"))
        assertTrue("空 Map 才会让 segJson 跳过 t 字段，不写空数组", got.isEmpty())
    }

    // ============================================================ 端到端

    @Test
    fun `真实一天的标签能拼成上传需要的结构`() = runBlocking {
        val it = itemId()
        val t = tags("能量增益", "健康维护")

        // 一天 5 段，其中 2 段挂了标签、1 段是空白
        record("s1", day, 0, 300, it)
        record("s2", day, 300, 600, it)
        record("s3", day, 600, 900, null)          // 空白
        record("s4", day, 900, 1200, it)
        record("s5", day, 1200, 1440, it)

        attach("s1", t["能量增益"]!!)
        attach("s2", t["健康维护"]!!)
        attach("s2", t["能量增益"]!!)

        val idToName = db.tagDao().all().associate { it.id to it.name }
        val got = groupTagNames(db.tagDao().pairsOfDays(listOf(day)), idToName)

        assertEquals("只有 s1、s2 挂了标签", setOf("s1", "s2"), got.keys)
        assertEquals(listOf("能量增益"), got["s1"])
        assertEquals(2, got["s2"]?.size)
        // 空白段本来就没有标签 —— 它不该被塞进 Map，也就不会在上传时报错
        assertEquals(null, got["s3"])
    }
}
