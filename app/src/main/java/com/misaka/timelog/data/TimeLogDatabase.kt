package com.misaka.timelog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.misaka.timelog.data.dao.BackupDao
import com.misaka.timelog.data.dao.CategoryDao
import com.misaka.timelog.data.dao.ItemDao
import com.misaka.timelog.data.dao.RecordDao
import com.misaka.timelog.data.dao.TagBucketDao
import com.misaka.timelog.data.dao.TagDao
import com.misaka.timelog.data.dao.TemplateDao
import com.misaka.timelog.data.entity.BackupSnapshotEntity
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemDefaultTagEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordTagEntity
import com.misaka.timelog.data.entity.TagBucketEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.data.entity.TemplateEntity
import com.misaka.timelog.data.entity.TemplateRunEntity
import com.misaka.timelog.data.entity.TemplateSegmentEntity
import com.misaka.timelog.domain.ColorSeries
import com.misaka.timelog.domain.DefaultTagInfer

/**
 * 本地数据库。
 *
 * ## 11 张表，分 4 组
 *
 * - **字典（5）**：[CategoryEntity] [ItemEntity] [TagBucketEntity] [TagEntity] [ItemDefaultTagEntity]
 * - **记录（2）**：[RecordEntity] [RecordTagEntity]
 * - **模板（3）**：[TemplateEntity] [TemplateSegmentEntity] [TemplateRunEntity]
 * - **备份（1）**：[BackupSnapshotEntity]
 *
 * ## 关于 `version`
 *
 * - **1**（M1）：第一版表结构，当时**没有历史数据要迁**，所以不写 Migration。
 * - **2**（M2-C）：表结构一个字段都没加，只是**补种了内置模板**（`templates` /
 *   `template_segments` / `template_run` 三张表 M1 就建好了，一直是空的）。
 *
 * ⚠️ 为什么「只是补个种子」也要正经写 Migration：
 *
 * 种子的语义是「库文件第一次诞生时跑一次」，而**老库的文件早就存在了** ——
 * 光改 `SeedData.install` 对已经装过 App 的机器完全无效，升上来会看到一个空的模板页。
 * 所以必须有一条 Migration 把它补上。
 *
 * ⚠️ **绝不允许**用 `fallbackToDestructiveMigration()` —— 那会把用户几个月的记录一次清光。
 * 这条从 M2-B 起就是硬要求（手机上有真记录了），M2-C 正式开始执行。
 *
 * ## 关于外键
 *
 * Room 建库时会自动执行 `PRAGMA foreign_keys = ON`，所以实体上声明的
 * RESTRICT / CASCADE / SET_NULL 都是真的生效的（不是摆设）。
 */
@Database(
    entities = [
        CategoryEntity::class,
        ItemEntity::class,
        TagBucketEntity::class,
        TagEntity::class,
        ItemDefaultTagEntity::class,
        RecordEntity::class,
        RecordTagEntity::class,
        TemplateEntity::class,
        TemplateSegmentEntity::class,
        TemplateRunEntity::class,
        BackupSnapshotEntity::class,
    ],
    version = 4,
    // 不导出 schema JSON：这是个单人自用项目，schema 以代码为准
    exportSchema = false,
)
abstract class TimeLogDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun itemDao(): ItemDao

    abstract fun tagBucketDao(): TagBucketDao

    abstract fun tagDao(): TagDao

    abstract fun recordDao(): RecordDao

    abstract fun templateDao(): TemplateDao

    abstract fun backupDao(): BackupDao

    companion object {
        private const val DB_NAME = "timelog.db"

        /**
         * 1 → 2：**只补数据，不动结构**。
         *
         * 老库升上来时 `templates` 是一张空表，所以模板页会是空的、也启用不了。
         * 这里把内置那套「工作日」种进去（`INSERT OR IGNORE`，幂等、不覆盖用户改过的内容）。
         *
         * ⚠️ 它**不能碰** `records` —— 用户几个月的记录一条都不许动。
         *    这个迁移从头到尾只写 `templates` / `template_segments` / `template_run` 三张表。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SeedData.installTemplate(db)
            }
        }

        /**
         * 2 → 3：**把「一分类一色」重排成「12 色系 + 组内渐变」**。
         *
         * ## 为什么要动
         *
         * 原来的模型是「一个事项一个颜色」，但 seed 之后**同一分类下所有事项都照抄了
         * 分类色**，于是 10 个分类、63 个事项实际只有 10 种颜色：
         * 「看书」和「AI讨论」在统计图里一模一样，根本分不出是两件事。
         *
         * ## 做两件事，**顺序不能反**
         *
         * ① **标记自动**：把「颜色正好等于所属分类颜色」的事项写成空串（= 自动）。
         * ② **重排分类色系**：按 `position` 顺序，用 [ColorSeries.assignSeries] 就近分配
         *    12 个色系里最近的那个，被占用的依次让位。
         *
         * ⚠️ **顺序反了第一步就永远匹配不上** —— 一旦分类色被改写，
         *    「事项色 == 分类色」这个判据就失效了，所有事项都会被当成「手选」保留下来，
         *    于是新颜色一套都没生效。这个 bug 不会报错，只会让人以为「迁移没跑」。
         *
         * ## 只碰两张表
         *
         * `categories` / `items`。**`records` 一个字节都不动** —— 记录挂的是事项 id，
         * 不是颜色，历史统计的口径不受影响（v1.11 裁定「改归属历史跟着重算」，
         * 而这里连归属都没改，只改了显示色）。
         *
         * ## 已知的误判（可接受）
         *
         * 如果用户**手动**把某个事项挑成了「正好等于分类色」的那个颜色，
         * 它会被误标成「自动」。概率极低，而且后果只是那个事项以后跟着分类走 ——
         * 在事项页点一下就能改回来。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ① 先标记「照抄分类色」的事项为自动（**必须在改分类色之前**，理由见上）
                db.execSQL(
                    """
                    UPDATE items SET colorHex = ''
                    WHERE EXISTS (
                        SELECT 1 FROM categories c
                        WHERE c.id = items.categoryId AND c.colorHex = items.colorHex
                    )
                    """.trimIndent(),
                )

                // ② 读出全部分类（按 position），算出色系分配
                val ids = ArrayList<String>()
                val olds = ArrayList<String>()
                db.query("SELECT id, colorHex FROM categories ORDER BY position").use { cur ->
                    while (cur.moveToNext()) {
                        ids += cur.getString(0)
                        olds += cur.getString(1).orEmpty()
                    }
                }
                if (ids.isEmpty()) return

                val series = ColorSeries.assignSeries(olds)

                // ③ 写回新的色系主色
                ids.forEachIndexed { i, id ->
                    db.execSQL(
                        "UPDATE categories SET colorHex = ? WHERE id = ?",
                        arrayOf(ColorSeries.categoryColor(series[i]), id),
                    )
                }
            }
        }

        /**
         * 3 → 4：从历史记录反推「事项的默认标签」，回填 `item_default_tags`。
         *
         * ## 为什么需要这一趟
         *
         * 旧版时间日志的标签挂在**记录**上，没有「事项预设」这个概念 →
         * 导入时 `record_tags` 进来了 11520 条，`item_default_tags` 却**没有数据源**，
         * 一直是空表。表现就是：点开事项的「默认标签」一个勾都没有，
         * 记「睡觉」也不再自动带「日常必需」。
         *
         * 而历史数据里规律极干净（睡觉 98% 日常必需、打工 99% 日常必需 + 60% 能量消耗），
         * 所以直接从记录里算回来。算法与阈值见 [DefaultTagInfer]。
         *
         * ## 三条安全守卫
         *
         * 1. **表里已经有东西就整个跳过** —— 用户手设过的默认标签神圣不可侵犯，
         *    迁移不是「纠正」，只是「本来该有却一直没有」的那一次补齐。
         * 2. **只 INSERT，不 DELETE** —— 就算守卫失效也不会删掉任何一行。
         * 3. 推断结果为空（新装库、或记录太少）时什么都不做。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 守卫①：已经有默认标签就不动
                db.query("SELECT COUNT(*) FROM item_default_tags").use { cur ->
                    if (cur.moveToFirst() && cur.getInt(0) > 0) return
                }

                // 每个事项的总记录数（deleted = 0 才算）
                val totalByItem = HashMap<String, Int>()
                db.query(
                    "SELECT itemId, COUNT(*) FROM records WHERE deleted = 0 GROUP BY itemId"
                ).use { cur ->
                    while (cur.moveToNext()) totalByItem[cur.getString(0)] = cur.getInt(1)
                }
                if (totalByItem.isEmpty()) return

                // 每个 (事项, 标签) 覆盖了多少条记录
                val usages = ArrayList<DefaultTagInfer.Usage>()
                db.query(
                    """
                    SELECT r.itemId, rt.tagId, COUNT(*) AS n
                    FROM records r
                    JOIN record_tags rt ON rt.recordId = r.id
                    WHERE r.deleted = 0
                    GROUP BY r.itemId, rt.tagId
                    """.trimIndent()
                ).use { cur ->
                    while (cur.moveToNext()) {
                        usages += DefaultTagInfer.Usage(cur.getString(0), cur.getString(1), cur.getInt(2))
                    }
                }
                if (usages.isEmpty()) return

                // 守卫②：只 INSERT OR IGNORE
                val stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO item_default_tags(itemId, tagId) VALUES(?, ?)"
                )
                DefaultTagInfer.infer(usages, totalByItem).forEach { row ->
                    stmt.clearBindings()
                    stmt.bindString(1, row.itemId)
                    stmt.bindString(2, row.tagId)
                    stmt.executeInsert()
                }
            }
        }

        @Volatile
        private var instance: TimeLogDatabase? = null

        /**
         * 建库时的「起手式」回调 —— 分类 / 事项 / 标签在这里种进去。
         *
         * ★ 为什么提成常量而不是写成内联的匿名对象：
         * **测试要能用到同一份**。测试如果自己再写一遍同样的 insert，
         * 那测的就只是「测试里那份复制品对不对」，而生产那份照样可能写错。
         *
         * ⚠️ 它的语义是「这个库文件刚刚诞生」—— 天然只跑一次、天然幂等。
         * 所以不需要「先查有没有数据再决定种不种」那种判断，
         * 也不会有「用户把种子删光了、下次启动又被填回来」的鬼故事。
         */
        val seedCallback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                SeedData.install(db)
            }
        }

        /** 取单例。数据库连接本身是线程安全的，全局一份就够。 */
        fun get(context: Context): TimeLogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimeLogDatabase::class.java,
                    DB_NAME,
                )
                    .addCallback(seedCallback)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
    }
}
