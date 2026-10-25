package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.RecordTagEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `MIGRATION_3_4` 的测试 —— **从历史记录反推「事项默认标签」并回填**。
 *
 * ## 为什么这条迁移必须测死
 *
 * 触发条件很特殊：只有「升级前 `item_default_tags` 是空表」的库才会真的回填。
 * 而**用户手设过的默认标签绝不能被覆盖** —— 这条如果写错，
 * 用户点开某个事项会发现「我明明设过，怎么变成别的了」。
 *
 * 所以这里重点测的是**守卫**，不是算法（算法在 `DefaultTagInferTest` 里已经测透）。
 *
 * ## 测法
 *
 * 在已经是 v4 结构的内存库上，先造一批记录和 `record_tags`（摆成「刚导入完、默认标签还空着」
 * 的样子），然后直接调 `MIGRATION_3_4.migrate(...)`。表结构在 3→4 之间一个字段都没变。
 */
@RunWith(RobolectricTestRunner::class)
class Migration3To4Test {

    private lateinit var db: TimeLogDatabase

    /**
     * 记录 id 的全局序号。
     *
     * ⚠️ 不能拿「事项 id + 循环下标」当 id —— 同一个事项会分两批造
     * （比如先造 7 条只带 A 标签的、再造 3 条带 A+B 的），
     * 两批的下标都从 0 开始，主键直接撞车。
     */
    private var seq = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun migrate() {
        TimeLogDatabase.MIGRATION_3_4.migrate(db.openHelper.writableDatabase)
    }

    /** 造 n 条同一天的记录，每条都挂上给定标签。 */
    private suspend fun record(
        itemId: String,
        tags: List<String>,
        n: Int,
        deleted: Boolean = false,
    ) {
        val recs = (0 until n).map { i ->
            RecordEntity(
                id = "r${seq++}",
                day = "2026-01-01",
                startMin = 0,
                endMin = 1,
                itemId = itemId,
                categoryIdSnap = null,
                source = RecordSource.MANUAL,
                chainId = null,
                noTemplate = false,
                note = "",
                updatedAt = 0L,
                deleted = deleted,
            )
        }
        db.recordDao().insertAll(recs)
        val refs = recs.flatMap { r -> tags.map { RecordTagEntity(recordId = r.id, tagId = it) } }
        db.tagDao().attachToRecord(refs)
    }

    private suspend fun itemIdOf(name: String): String =
        db.itemDao().all().first { it.name == name }.id

    private suspend fun tagIdOf(name: String): String =
        db.tagDao().all().first { it.name == name }.id

    private suspend fun defaultsOf(itemId: String): Set<String> =
        db.itemDao().defaultTagIds(itemId).toSet()

    // ---------------------------------------------------------------- 用例

    /** ★ 主用例：空表 + 有历史记录 → 按规律回填。 */
    @Test
    fun `空表时按历史规律回填`() = runBlocking {
        val sleep = itemIdOf("睡觉")
        val work = itemIdOf("打工")
        val need = tagIdOf("日常必需")
        val energy = tagIdOf("能量消耗")
        val gain = tagIdOf("能量增益")

        // 睡觉：10 条全带「日常必需」，3 条带「能量增益」（30%，不该入选）
        record(sleep, listOf(need), 7)
        record(sleep, listOf(need, gain), 3)
        // 打工：10 条全带「日常必需」，6 条带「能量消耗」
        record(work, listOf(need), 4)
        record(work, listOf(need, energy), 6)

        migrate()

        assertEquals("睡觉只该有日常必需", setOf(need), defaultsOf(sleep))
        assertEquals("打工两个都够 50%", setOf(need, energy), defaultsOf(work))
    }

    /** ★ 守卫：已经有默认标签就整个跳过，一行都不许动。 */
    @Test
    fun `表里已有默认标签时完全跳过`() = runBlocking {
        val sleep = itemIdOf("睡觉")
        val need = tagIdOf("日常必需")
        val gain = tagIdOf("能量增益")

        record(sleep, listOf(need), 10)
        // 用户手设的：只想要「能量增益」
        db.itemDao().addDefaultTag(
            com.misaka.timelog.data.entity.ItemDefaultTagEntity(sleep, gain)
        )

        migrate()

        assertEquals("手设的必须原样保留，不能被推导结果覆盖", setOf(gain), defaultsOf(sleep))
    }

    /** 守卫：这个事项的记录太少（少于 3 条）就不推断。 */
    @Test
    fun `记录太少的事项不回填`() = runBlocking {
        val item = itemIdOf("编程") // 种子里有，真实库里只有 1 条记录
        val tag = tagIdOf("高价值投资")
        record(item, listOf(tag), 2)

        migrate()

        assertTrue("2 条记录不足以定义默认标签", defaultsOf(item).isEmpty())
    }

    /** 守卫：软删除的记录不算进分母，否则覆盖率会凭空变低。 */
    @Test
    fun `已软删的记录不算分母`() = runBlocking {
        val sleep = itemIdOf("睡觉")
        val need = tagIdOf("日常必需")

        record(sleep, listOf(need), 4)
        // 另外造 6 条已删的、而且不带标签的 —— 它们不该把覆盖率从 100% 压到 40%
        record(sleep, emptyList(), 6, deleted = true)

        migrate()

        assertEquals("活着的 4 条全带标签，就是 100%", setOf(need), defaultsOf(sleep))
    }

    /** 兜底：完全空的库（新装、还没导入）跑一遍不能炸，也不能插出鬼东西。 */
    @Test
    fun `空库跑一遍不炸也不留东西`() = runBlocking {
        db.itemDao().clearDefaultTags(itemIdOf("睡觉")) // 确保是空的
        migrate()

        val total = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM item_default_tags").use { c ->
                c.moveToFirst(); c.getInt(0)
            }
        assertEquals(0, total)
    }

    /** 跑两遍结果必须一致（迁移理论上只跑一次，但补丁版固件可能重跑）。 */
    @Test
    fun `跑两遍结果不变`() = runBlocking {
        val sleep = itemIdOf("睡觉")
        val need = tagIdOf("日常必需")
        record(sleep, listOf(need), 5)

        migrate()
        val first = defaultsOf(sleep)
        migrate()

        assertEquals(first, defaultsOf(sleep))
        assertTrue("应该有回填结果", first.isNotEmpty())
    }
}
