package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.domain.ColorSeries
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 起手式字典（种子数据）的测试。
 *
 * ## 为什么这份数据值得单独测
 *
 * 它是用户**第一次打开 App 看到的东西**，而且 `onCreate` 只跑一次 ——
 * 一旦种错了（少一个分类、事项次序有洞、颜色全是灰的），
 * 在真机上要「卸载重装」才能回到干净状态，很难发现也很难修。
 *
 * 它还捆着一条容易被忽视的规则：**事项在分类里的次序是显式字段**。
 * 那句「每个分类的 orderInCat 必须从 0 连续、不能有洞」就是在守它 ——
 * 有洞的话，网格里那个位置会空一格，或者顺序跟预期不一致。
 *
 * ⚠️ 这里挂的是 `TimeLogDatabase.seedCallback`，**和生产同一份逻辑**。
 * 测试自己再写一遍 insert 就等于测了个复制品，生产那份照样可能错。
 */
@RunWith(RobolectricTestRunner::class)
class SeedDataTest {

    private lateinit var db: TimeLogDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            // ★ 跟生产同一份回调 —— 在第一次访问数据库时触发
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ====================================================== 分类

    @Test
    fun `十个分类的名字与次序与原型一致`() = runBlocking {
        val cats = db.categoryDao().all()

        assertEquals(10, cats.size)
        assertEquals(
            listOf(
                "技能成长", "精神输入", "动手输出", "恋爱相伴", "人际交往",
                "赚钱营生", "生活必需", "放松娱乐", "生理休息", "杂事",
            ),
            cats.map { it.name },
        )
        // position 必须正好是 0..9：有洞或重号都会让排序变得不可预测
        assertEquals((0 until 10).toList(), cats.map { it.position })
    }

    @Test
    fun `分类颜色都是合法的井号六位十六进制`() = runBlocking {
        db.categoryDao().all().forEach { c ->
            assertTrue("分类「${c.name}」的颜色不合法：${c.colorHex}", HEX.matches(c.colorHex))
        }
    }

    // ====================================================== 事项

    @Test
    fun `事项总数是五十八个`() = runBlocking {
        // ★ 精确断言是有意的：如果 onCreate 因为某种原因跑了两次，数量会翻倍 —— 这条立刻红
        assertEquals(58, db.itemDao().all().size)
    }

    @Test
    fun `每个分类的组内次序都从零连续无洞`() = runBlocking {
        // ★ 这是种子代码最容易写错的地方：orderInCat 是「每个分类各数各的」，
        //   如果错用一个全局计数器，第二个分类就会从 7 开始，网格里前面空一片。
        val items = db.itemDao().all()

        items.groupBy { it.categoryId }.forEach { (catId, list) ->
            val orders = list.map { it.orderInCat }.sorted()
            assertEquals(
                "分类 $catId 的 orderInCat 不连续：$orders",
                (0 until list.size).toList(),
                orders,
            )
        }
    }

    @Test
    fun `每个分类下都有事项`() = runBlocking {
        val cats = db.categoryDao().all()
        val items = db.itemDao().all()

        cats.forEach { c ->
            assertTrue(
                "分类「${c.name}」下一条事项都没有 —— 选择网格里会出现一个点不动的空格",
                items.any { it.categoryId == c.id },
            )
        }
    }

    /**
     * ★ 事项的初始颜色是**自动**（空串），不是照抄分类色。
     *
     * 这一条是 2→3 迁移的核心语义：空串表示「跟随分类色系 + 组内第 N 档渐变」，
     * 由 `resolveItemColors` 在读取时算出来。
     *
     * ⚠️ 原来是断言「事项色 == 分类色」—— 那正是「一分类一色、组内分不出来」的病根。
     *    现在如果谁把种子改回写死分类色，这条会红。
     */
    @Test
    fun `事项初始颜色必须是自动_不是照抄分类色`() = runBlocking {
        db.itemDao().all().forEach { item ->
            assertEquals(
                "事项「${item.name}」的初始颜色应该是空串（自动），实际是 ${item.colorHex}",
                "",
                item.colorHex,
            )
        }
    }

    /**
     * ★ 分类颜色必须是 12 色系之一的主色 —— 这样组内渐变才能从它派生出来。
     *
     * 如果种子里还留着 `#DE2E63` 那种旧色，`ColorSeries.itemColor` 也能算出东西
     * （它只取色相），但「12 个分类色相均匀 30°」这个前提就破了。
     */
    @Test
    fun `分类颜色必须是十二色系的主色`() = runBlocking {
        val allowed = (0 until ColorSeries.SERIES).map { ColorSeries.categoryColor(it) }.toSet()
        db.categoryDao().all().forEach { c ->
            assertTrue(
                "分类「${c.name}」的颜色 ${c.colorHex} 不在十二色系里：$allowed",
                c.colorHex in allowed,
            )
        }
    }

    @Test
    fun `事项 id 互不相同`() = runBlocking {
        // 事项 id 是建库时用 newId() 现生成的（58 个硬编码 UUID 只会让代码没法看）。
        // 万一生成逻辑出错（比如失败时回退成常量），后面的会静默盖掉前面的 —— 数量就少了。
        val ids = db.itemDao().all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ====================================================== 标签

    @Test
    fun `三个标签桶和八个标签`() = runBlocking {
        assertEquals(listOf("价值判断", "能量", "领域"), db.tagBucketDao().all().map { it.name })
        assertEquals(8, db.tagDao().all().size)
    }

    @Test
    fun `每个标签都挂在一个真实存在的桶上`() = runBlocking {
        val bucketIds = db.tagBucketDao().all().map { it.id }.toSet()

        db.tagDao().all().forEach { tag ->
            assertTrue("标签「${tag.name}」挂在不存在的桶 ${tag.bucketId} 上", tag.bucketId in bucketIds)
            // 一行说明不是可有可无的装饰 —— 它是防止标签越加越含糊的东西
            assertTrue("标签「${tag.name}」缺少说明", tag.description.isNotBlank())
        }
    }

    // ====================================================== 通用的质量守卫

    @Test
    fun `名字都不为空也不带首尾空格`() = runBlocking {
        val names = db.categoryDao().all().map { it.name } +
            db.itemDao().all().map { it.name } +
            db.tagBucketDao().all().map { it.name } +
            db.tagDao().all().map { it.name }

        names.forEach { n ->
            assertNotNull(n)
            assertTrue("名字「$n」有问题（空或带首尾空格）", n.isNotBlank() && n == n.trim())
        }
    }

    @Test
    fun `种子里没有留下任何记录`() = runBlocking {
        // ★ 种子**只种字典，不种记录**。
        //   如果哪天有人「顺手塞几条示例记录」进来，用户第一眼就会看到
        //   自己从没记过的时间段 —— 那是假数据，比空白糟糕得多。
        assertEquals(0, db.recordDao().countOn("2026-10-14"))
        assertTrue(db.recordDao().allDays().isEmpty())
    }

    private companion object {
        val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}
