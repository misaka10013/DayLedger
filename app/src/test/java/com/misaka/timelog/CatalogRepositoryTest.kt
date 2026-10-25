package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.valueOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 字典仓库的测试 —— 重点盯**删除守卫**和**「归属 ≠ 容器」**这两件事。
 *
 * ## 为什么守卫必须在数据层被测
 *
 * 「先搬空、才可删」如果只写在界面里（把按钮灰掉），将来任何一条
 * 绕过界面的代码都能造出脏数据。所以测试要**直接调仓库**去撞这道门，
 * 撞不开才算数 —— 而不是去点界面上的按钮看它灰不灰。
 */
@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryTest {

    private val today = "2026-10-14"

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
        clock = FakeClock.at(today, 870)
        timeline = TimelineRepository(db, clock)
        catalog = CatalogRepository(db, timeline)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newCategory(name: String = "工作") =
        (catalog.addCategory(name, "#F59E0B") as Outcome.Ok).value

    private suspend fun newItem(catId: String, name: String = "打工") =
        (catalog.addItem(catId, name, "#F59E0B") as Outcome.Ok).value

    private suspend fun newBucket(name: String = "工作状态") =
        (catalog.addBucket("💼", name) as Outcome.Ok).value

    private suspend fun newTag(bucketId: String, name: String = "加班") =
        (catalog.addTag(bucketId, name, "#EF4444", "超出常规工时") as Outcome.Ok).value

    // ==================================================== 分类的删除守卫

    @Test
    fun `分类下面还有事项_删不掉`() = runBlocking {
        val cat = newCategory()
        newItem(cat.id)

        val result = catalog.deleteCategory(cat.id)

        assertTrue("必须被拦下", result is Outcome.Blocked)
        assertNotNull("分类必须还在", catalog.category(cat.id))
        assertTrue("而且提示得是人话", (result as Outcome.Blocked).message.contains("事项"))
    }

    @Test
    fun `把事项搬空之后_分类可以删`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id)
        catalog.deleteItemDeep(item.id)

        val result = catalog.deleteCategory(cat.id)

        assertTrue(result.isOk)
        assertNull(catalog.category(cat.id))
    }

    @Test
    fun `挪走最后一个事项之后_分类也可以删`() = runBlocking {
        val from = newCategory("工作")
        val to = newCategory("娱乐")
        val item = newItem(from.id)

        // 先试着删 —— 该被挡
        assertTrue(catalog.deleteCategory(from.id) is Outcome.Blocked)

        // 把事项挪到别的分类
        catalog.moveItemToCategory(item.id, to.id)

        // 再删 —— 这次可以了
        assertTrue(catalog.deleteCategory(from.id).isOk)
        assertEquals(1, catalog.itemsIn(to.id).size)
    }

    // ================================================== 标签桶的删除守卫

    @Test
    fun `标签桶里还有标签_删不掉`() = runBlocking {
        val bucket = newBucket()
        newTag(bucket.id)

        val result = catalog.deleteBucket(bucket.id)

        assertTrue(result is Outcome.Blocked)
        assertNotNull(catalog.buckets().firstOrNull { it.id == bucket.id })
    }

    @Test
    fun `把标签挪走之后_桶可以删`() = runBlocking {
        val from = newBucket("工作状态")
        val to = newBucket("情绪")
        val tag = newTag(from.id)
        catalog.moveTagToBucket(tag.id, to.id)

        assertTrue(catalog.deleteBucket(from.id).isOk)
    }

    // ============================================ ★ 删标签 = 级联摘掉引用

    /**
     * ★ 已拍板：删标签**级联摘掉引用**，而不是「有引用就拒绝删」。
     *
     * 这一条能这么轻松，全靠**记录上挂的是 tagId 不是名字**：
     * `record_tags.tagId` 上是 CASCADE 外键，数据库自动清干净。
     *
     * ⚠️ 原型里存的是 `'#标签名'` 字符串，删除时必须手写代码扫全表 ——
     * 漏一处就留下「孤儿标签」（列表里没了，卡片上还挂着，点也点不进去）。
     */
    @Test
    fun `删标签_记录上的引用被一起摘干净`() = runBlocking {
        val bucket = newBucket()
        val tag = newTag(bucket.id)
        val cat = newCategory()
        val item = newItem(cat.id)
        catalog.setDefaultTags(item.id, listOf(tag.id))

        timeline.pick(item.id, today)
        val recId = timeline.liveRecord(today)!!.id
        assertEquals("记的时候应该自动带上了", listOf(tag.id), db.tagDao().tagIdsOfRecord(recId))

        catalog.deleteTag(tag.id)

        assertTrue("记录上的引用必须被摘干净，不能留悬空 id", db.tagDao().tagIdsOfRecord(recId).isEmpty())
        assertNull(catalog.tag(tag.id))
    }

    @Test
    fun `删标签_事项的默认标签也被摘掉`() = runBlocking {
        val bucket = newBucket()
        val tag = newTag(bucket.id)
        val cat = newCategory()
        val item = newItem(cat.id)
        catalog.setDefaultTags(item.id, listOf(tag.id))

        catalog.deleteTag(tag.id)

        assertTrue(catalog.defaultTagIds(item.id).isEmpty())
    }

    @Test
    fun `删标签_记录本身一条都没少`() = runBlocking {
        val bucket = newBucket()
        val tag = newTag(bucket.id)
        val cat = newCategory()
        val item = newItem(cat.id)
        catalog.setDefaultTags(item.id, listOf(tag.id))
        timeline.pick(item.id, today)
        val before = timeline.dayRecords(today).size

        catalog.deleteTag(tag.id)

        assertEquals("删个标签不该动到记录", before, timeline.dayRecords(today).size)
        assertTrue(timeline.verify(today).isEmpty())
    }

    // ============================================ ★ 删事项 = 历史擦成空白

    @Test
    fun `删事项_历史记录变成空白块`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id)

        clock.atMinute(540)
        timeline.pick(item.id, today)
        clock.atMinute(720)
        timeline.closeLive(today)

        val erased = catalog.deleteItemDeep(item.id)

        assertTrue(erased.isOk)
        assertEquals("1 条记录被擦成空白", 1, erased.valueOrNull())
        assertNull("事项本身必须真的没了", catalog.item(item.id))

        // ⚠️ 不能断言「记录条数没变」—— 被擦的那段会和前后空白合并成一条，
        //    行数是会少的。真正该验的是「那段时间仍然被一条空白盖着」。
        val erasedGap = timeline.dayRecords(today)
            .first { it.startMin <= 540 && (it.endMin ?: 1440) >= 720 }
        assertEquals(RecordSource.GAP, erasedGap.source)
        assertNull(erasedGap.itemId)
        assertTrue("擦完还得是密铺", timeline.verify(today).isEmpty())
    }

    @Test
    fun `删事项_擦掉的空白不会被模板填回来`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id)
        clock.atMinute(540)
        timeline.pick(item.id, today)
        clock.atMinute(720)
        timeline.closeLive(today)

        catalog.deleteItemDeep(item.id)

        val erasedGap = timeline.dayRecords(today)
            .first { it.startMin <= 540 && (it.endMin ?: 1440) >= 720 }
        assertTrue(
            "必须打上 noTemplate，否则模板下次刷新又把它填满，用户看着像删不掉",
            erasedGap.noTemplate,
        )
    }

    // ==================================================== 归属 vs 容器

    /**
     * ★ 「换归属」和「换桶」看起来像，但连带影响完全不同。
     *
     * 这一条测**不该发生的事**：换归属时，记录上的东西**一个字都不该动**
     * （`itemId` 不动、`categoryIdSnap` 也不动）。
     *
     * 统计数字会跟着新归属走 —— 那是统计层在算的时候按事项**当前**分类取的，
     * **不是**去回写历史记录。这个区别很关键：回写历史是破坏性的、不可逆的，
     * 而按当前归属算是可逆的。
     */
    @Test
    fun `换归属_历史记录一个字都没动`() = runBlocking {
        val work = newCategory("工作")
        val funCat = newCategory("娱乐")
        val item = newItem(work.id, "打游戏")

        timeline.pick(item.id, today)
        val recId = timeline.liveRecord(today)!!.id
        val snapBefore = db.recordDao().byId(recId)!!.categoryIdSnap

        catalog.moveItemToCategory(item.id, funCat.id)

        val after = db.recordDao().byId(recId)!!
        assertEquals("记录挂的还是同一个事项", item.id, after.itemId)
        assertEquals("「当时分类快照」是历史事实，不该被改写", snapBefore, after.categoryIdSnap)
        assertEquals("事项自己的归属改了", funCat.id, catalog.item(item.id)!!.categoryId)
    }

    @Test
    fun `换桶_不影响标签本身也不影响记录`() = runBlocking {
        val a = newBucket("工作状态")
        val b = newBucket("情绪")
        val tag = newTag(a.id)
        val cat = newCategory()
        val item = newItem(cat.id)
        catalog.setDefaultTags(item.id, listOf(tag.id))
        timeline.pick(item.id, today)
        val recId = timeline.liveRecord(today)!!.id

        catalog.moveTagToBucket(tag.id, b.id)

        assertEquals(b.id, catalog.tag(tag.id)!!.bucketId)
        assertEquals("记录上挂的引用不该动", listOf(tag.id), db.tagDao().tagIdsOfRecord(recId))
    }

    // ======================================================= 改名 / 排序

    @Test
    fun `改事项名_记录上的引用一点不受影响`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id, "打工")
        timeline.pick(item.id, today)
        val recId = timeline.liveRecord(today)!!.id

        catalog.renameItem(item.id, "搬砖")

        assertEquals("搬砖", catalog.item(item.id)!!.name)
        assertEquals("记录挂的是 id，改名跟它无关", item.id, db.recordDao().byId(recId)!!.itemId)
    }

    @Test
    fun `空名字一律拒绝`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id)
        val bucket = newBucket()
        val tag = newTag(bucket.id)

        assertTrue(catalog.addCategory("   ", "#FFFFFF") is Outcome.Blocked)
        assertTrue(catalog.renameCategory(cat.id, "") is Outcome.Blocked)
        assertTrue(catalog.renameItem(item.id, " ") is Outcome.Blocked)
        assertTrue(catalog.renameTag(tag.id, "") is Outcome.Blocked)
        assertTrue(catalog.addBucket("💼", "  ") is Outcome.Blocked)
    }

    @Test
    fun `分类可以上下挪_但挪到头就停`() = runBlocking {
        val a = newCategory("A")
        val b = newCategory("B")
        newCategory("C")

        assertTrue("A 已经是最上面了", catalog.moveCategory(a.id, -1) is Outcome.Blocked)

        assertTrue(catalog.moveCategory(b.id, -1).isOk)
        assertEquals(listOf("B", "A", "C"), catalog.categories().map { it.name })

        // 反复挪也不会把次序搞乱
        catalog.moveCategory(b.id, 1)
        catalog.moveCategory(b.id, 1)
        assertEquals(listOf("A", "C", "B"), catalog.categories().map { it.name })
        assertEquals(listOf(0, 1, 2), catalog.categories().map { it.position })
    }

    @Test
    fun `事项在自己分类内按次序排`() = runBlocking {
        val cat = newCategory()
        val one = newItem(cat.id, "一")
        val two = newItem(cat.id, "二")

        assertEquals(listOf("一", "二"), catalog.itemsIn(cat.id).map { it.name })

        catalog.moveItem(two.id, -1)
        assertEquals(listOf("二", "一"), catalog.itemsIn(cat.id).map { it.name })
        assertNotEquals(catalog.item(one.id)!!.orderInCat, catalog.item(two.id)!!.orderInCat)
    }

    @Test
    fun `删事项前的后果预览说得清楚`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id)
        clock.atMinute(540)
        timeline.pick(item.id, today)
        clock.atMinute(720)
        timeline.closeLive(today)

        val impact = catalog.itemImpact(item.id)

        assertEquals(1, impact.recordCount)
        assertEquals(180, impact.totalMinutes)
        assertTrue(impact.describe().contains("空白"))
    }

    @Test
    fun `没记过的事项_预览会说不会有影响`() = runBlocking {
        val cat = newCategory()
        val item = newItem(cat.id)

        val impact = catalog.itemImpact(item.id)

        assertEquals(0, impact.recordCount)
        assertTrue(impact.describe().contains("不会有影响"))
    }
}
