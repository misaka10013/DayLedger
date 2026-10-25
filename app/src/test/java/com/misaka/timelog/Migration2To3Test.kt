package com.misaka.timelog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
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
 * `MIGRATION_2_3` 的测试 —— **「一分类一色」重排成「12 色系 + 组内渐变」**。
 *
 * ## 为什么这种「一次性代码」最该测
 *
 * 迁移只在**用户升级一次**的时候跑：
 *
 * - 跑错了**没有任何补救机会**（`version` 已经变成 3，不会再来一次）；
 * - 症状也不明显 —— 顶多是「分类颜色跟预想的不一样」或者「事项还是全同色」，
 *   用户很可能以为「本来就这样」，过几个月才发现；
 * - 而它做的事恰好很危险：**改用户的分类颜色** + **把事项色改成『自动』**。
 *
 * ## 测法
 *
 * 先在**已经是 v3 结构**的内存库上把数据摆成「v2 的样子」（分类用旧色、
 * 事项照抄分类色），然后直接调 `MIGRATION_2_3.migrate(...)`。
 *
 * 表结构在 2→3 之间**一个字段都没变**（只改了数据），所以这样测等价于真迁移，
 * 而且不需要 `exportSchema` 的 JSON（项目一直是 `exportSchema = false`）。
 */
@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {

    private lateinit var db: TimeLogDatabase

    /** 真库里那 10 个分类的旧颜色（2026-10-17 从 `6ae3b15d` 上导出）。 */
    private val oldColors = mapOf(
        "技能成长" to "#7B3FC4",
        "精神输入" to "#DE2E63",
        "动手输出" to "#DC3545",
        "恋爱相伴" to "#F062A0",
        "人际交往" to "#C77DBB",
        "赚钱营生" to "#8A9A3C",
        "生活必需" to "#E8A33D",
        "放松娱乐" to "#B7D96A",
        "生理休息" to "#6C7AE0",
        "杂事" to "#7CB342",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TimeLogDatabase::class.java)
            .addCallback(TimeLogDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        // 触发建库 + 种子
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 把库摆回「升级前」的样子：
     *
     * - 分类色换回那 10 个旧色
     * - 所有事项的色照抄分类色（这就是老代码干的事）
     */
    private fun makeItLookLikeV2() {
        val s = db.openHelper.writableDatabase
        oldColors.forEach { (name, hex) ->
            s.execSQL("UPDATE categories SET colorHex = ? WHERE name = ?", arrayOf(hex, name))
        }
        s.execSQL(
            """
            UPDATE items SET colorHex =
                (SELECT c.colorHex FROM categories c WHERE c.id = items.categoryId)
            """.trimIndent(),
        )
    }

    private fun migrate() {
        TimeLogDatabase.MIGRATION_2_3.migrate(db.openHelper.writableDatabase)
    }

    // ---------------------------------------------------------------- 用例

    /**
     * ★ 主用例：迁移之后，**所有照抄分类色的事项都变成「自动」**。
     *
     * 这一条不过，整个改动就白做了 —— 事项还是各带一个死色值，
     * 同一分类下照样一模一样，统计图里还是一团。
     */
    @Test
    fun `迁移后照抄分类色的事项全部变成自动`() = runBlocking {
        makeItLookLikeV2()
        // 前置确认：确实摆成了「事项色 == 分类色」的样子
        val before = db.itemDao().all()
        assertTrue("前置条件不成立：应该有很多事项带具体色值", before.all { it.colorHex.isNotBlank() })

        migrate()

        val after = db.itemDao().all()
        assertEquals("事项数不能变", before.size, after.size)
        val notAuto = after.filter { it.colorHex.isNotBlank() }
        assertTrue(
            "这些事项迁移后还带着具体色值（应该变成自动）：" +
                notAuto.joinToString { "${it.name}=${it.colorHex}" },
            notAuto.isEmpty(),
        )
    }

    /**
     * ★ 分类色必须被重排成「12 色系里的主色」，而且 10 个分类分到 10 个**不同**色系。
     *
     * 如果迁移只清空了事项、没动分类色，那 `ColorSeries.itemColor` 还是会按旧色相的
     * 色相去算 —— 能算出东西，但 4 个挤在红紫区的分类照旧分不开。
     */
    @Test
    fun `迁移后分类色必须是十二色系主色且互不相同`() = runBlocking {
        makeItLookLikeV2()
        migrate()

        val cats = db.categoryDao().all()
        val allowed = ColorSeries.allCategoryColors()
        cats.forEach { c ->
            assertTrue("分类「${c.name}」的颜色 ${c.colorHex} 不在 12 色系里", c.colorHex in allowed)
        }
        assertEquals(
            "10 个分类必须分到 10 个不同色系，实际：${cats.map { it.name to it.colorHex }}",
            cats.size,
            cats.map { it.colorHex.uppercase() }.toSet().size,
        )
    }

    /** 手选过颜色的事项**必须保留** —— 那是用户自己的选择，迁移不许动。 */
    @Test
    fun `迁移不碰用户手选的颜色`() = runBlocking {
        makeItLookLikeV2()
        // 挑一件事项，手动改成一个明显不属于任何分类的色
        val victim = db.itemDao().all().first()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE items SET colorHex = '#00FFCC' WHERE id = ?",
            arrayOf(victim.id),
        )

        migrate()

        val after = db.itemDao().all().first { it.id == victim.id }
        assertEquals("手选的色被迁移改掉了", "#00FFCC", after.colorHex)
    }

    /**
     * ★ 迁移**不许碰记录**。
     *
     * 这是整个项目最贵的数据（两年半、6516 段）。迁移只该写 `categories` / `items`。
     * 这里用「迁移前后 records 的条数 + 内容摘要完全一致」来守。
     */
    @Test
    fun `迁移一条记录都不许动`() = runBlocking {
        // 造几条记录（种子不带记录）—— 走 DAO 而不是手写 SQL，
        // 免得列名一改测试就假红（第一次写的时候就踩了：records 没有 createdAt 这列）
        db.recordDao().insertAll(
            listOf(
                RecordEntity(
                    id = "r1",
                    day = "2026-10-01",
                    startMin = 0,
                    endMin = 600,
                    itemId = null,
                    categoryIdSnap = null,
                    source = RecordSource.MANUAL,
                    chainId = null,
                    noTemplate = false,
                    note = "睡觉",
                    updatedAt = 0,
                    deleted = false,
                ),
                RecordEntity(
                    id = "r2",
                    day = "2026-10-01",
                    startMin = 600,
                    endMin = 1440,
                    itemId = null,
                    categoryIdSnap = null,
                    source = RecordSource.MANUAL,
                    chainId = null,
                    noTemplate = false,
                    note = "打工",
                    updatedAt = 0,
                    deleted = false,
                ),
            ),
        )
        val before = dumpRecords()
        assertEquals("前置条件：应该有两条记录", 2, before.lines().count { it.isNotBlank() })

        makeItLookLikeV2()
        migrate()

        assertEquals("迁移改了 records", before, dumpRecords())
    }

    /** 迁移要**幂等**：跑两遍的结果跟跑一遍一样（不会把色系又推一轮）。 */
    @Test
    fun `迁移跑两遍结果一致`() = runBlocking {
        makeItLookLikeV2()
        migrate()
        val once = db.categoryDao().all().map { it.name to it.colorHex }

        migrate()
        val twice = db.categoryDao().all().map { it.name to it.colorHex }

        assertEquals("迁移不幂等", once, twice)
    }

    /** 空库（没有任何分类）也不能崩。 */
    @Test
    fun `没有分类时迁移安全`() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM items")
        db.openHelper.writableDatabase.execSQL("DELETE FROM categories")
        migrate()
        assertNotNull(db.categoryDao().all())
    }

    // ---------------------------------------------------------------- 工具

    private fun dumpRecords(): String {
        val sb = StringBuilder()
        db.openHelper.writableDatabase
            .query("SELECT id, day, startMin, endMin, note FROM records ORDER BY id")
            .use { c ->
                while (c.moveToNext()) {
                    sb.append(c.getString(0)).append('|')
                        .append(c.getString(1)).append('|')
                        .append(c.getInt(2)).append('|')
                        .append(c.getInt(3)).append('|')
                        .append(c.getString(4) ?: "").append('\n')
                }
            }
        return sb.toString()
    }
}
