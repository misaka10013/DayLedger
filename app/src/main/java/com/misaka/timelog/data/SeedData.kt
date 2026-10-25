package com.misaka.timelog.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.misaka.timelog.domain.ColorSeries

/**
 * 首次建库时种进去的「起手式」字典。
 *
 * ## 为什么要预置，而不是让用户从零开始
 *
 * 这个 App 一天要记十几笔，而「分类 / 事项」是记录的前提 ——
 * 打开是一片空白的话，第一次用就得先自己建十个分类、五十多个事项，才能记第一笔。
 * 那这个 App 就永远用不起来了。
 *
 * 这里的数据**与原型（已验收冻结）逐字一致** —— 分类名、颜色、事项名、
 * 事项在每个分类里的先后，全部照搬。要改就两边一起改，
 * 否则用惯了原型会觉得「东西怎么变了」。
 *
 * ## 为什么挂在 `onCreate` 上 —— 它天然幂等
 *
 * 它的语义正好是我们要的：**数据库文件第一次被创建时跑一次，之后永不执行**。
 * 所以不需要「先查有没有数据再决定种不种」这种判断，
 * 也不会出现「用户把种子数据删光了，下次启动又被填回来」这种鬼故事。
 *
 * ⚠️ 代价是：**改种子内容不能改这里** —— 老用户的库早就建好了，onCreate 不会再跑。
 *    真要改，得写一个 Migration（而且得先判断用户是不是把种子改过了，
 *    否则会把用户自己改的名字又覆盖回默认值，那是灾难）。
 *
 * ## 为什么用 execSQL 而不是调 DAO
 *
 * `onCreate` 触发时数据库还在初始化过程中，走 DAO 会绕一圈协程 + 事务，时机上不干净。
 * 这里就是几十条 INSERT，直接交给 SQLite 最稳。
 */
internal object SeedData {

    private class SeedCat(val id: String, val name: String, val color: String)
    private class SeedItem(val cat: String, val name: String)
    private class SeedBucket(val id: String, val icon: String, val name: String)
    private class SeedTag(
        val id: String,
        val bucket: String,
        val name: String,
        val color: String,
        val desc: String,
    )

    /** 10 个一级分类。⚠️ 数组顺序 = 网格顺序 = 存进去的 `position`。 */
    private val CATS = listOf(
        SeedCat("skill", "技能成长", "#7B3FC4"),
        SeedCat("input", "精神输入", "#DE2E63"),
        SeedCat("output", "动手输出", "#DC3545"),
        SeedCat("love", "恋爱相伴", "#F062A0"),
        SeedCat("social", "人际交往", "#C77DBB"),
        SeedCat("work", "赚钱营生", "#8A9A3C"),
        SeedCat("life", "生活必需", "#E8A33D"),
        SeedCat("relax", "放松娱乐", "#B7D96A"),
        SeedCat("rest", "生理休息", "#6C7AE0"),
        SeedCat("chore", "杂事", "#7CB342"),
    )

    /**
     * 58 个二级事项。
     *
     * ⚠️ **数组顺序就是 `orderInCat`**：同一个分类下的事项，谁先出现谁在前。
     *    所以往中间插一个事项会把它后面那些的次序整体挪一位 —— 这是有意为之，
     *    因为原型里就是这么排的（用户是按这个顺序形成肌肉记忆的）。
     */
    private val ITEMS = listOf(
        // 技能成长
        SeedItem("skill", "技能"), SeedItem("skill", "整理思绪"), SeedItem("skill", "英语"),
        SeedItem("skill", "印尼语"), SeedItem("skill", "日语"), SeedItem("skill", "编程"),
        SeedItem("skill", "写作"),

        // 精神输入
        SeedItem("input", "AI讨论"), SeedItem("input", "看书"), SeedItem("input", "笔记"),
        SeedItem("input", "健身"), SeedItem("input", "唱歌"), SeedItem("input", "爱好"),

        // 动手输出
        SeedItem("output", "视频学习"), SeedItem("output", "摄影"), SeedItem("output", "骑行"),
        SeedItem("output", "做饭"), SeedItem("output", "音乐学习"), SeedItem("output", "手工"),

        // 恋爱相伴
        SeedItem("love", "陪伴伴侣"), SeedItem("love", "视频通话"), SeedItem("love", "一起看剧"),
        SeedItem("love", "语音通话"), SeedItem("love", "一起吃饭"), SeedItem("love", "计划未来"),

        // 人际交往
        SeedItem("social", "线下面基"), SeedItem("social", "在线闲聊"), SeedItem("social", "无意义陪跑"),
        SeedItem("social", "家人联系"), SeedItem("social", "朋友聚会"), SeedItem("social", "逛街"),

        // 赚钱营生
        SeedItem("work", "打工"), SeedItem("work", "兼职"), SeedItem("work", "副业"),
        SeedItem("work", "开车通勤"), SeedItem("work", "加班"), SeedItem("work", "赚钱"),

        // 生活必需
        SeedItem("life", "生存必需品"), SeedItem("life", "洗脸洗漱"), SeedItem("life", "清洁家务"),
        SeedItem("life", "洗澡"), SeedItem("life", "采购"), SeedItem("life", "取快递"),

        // 放松娱乐
        SeedItem("relax", "补番"), SeedItem("relax", "游戏"), SeedItem("relax", "刷资讯"),
        SeedItem("relax", "听音乐"), SeedItem("relax", "看小说"), SeedItem("relax", "玩手机"),

        // 生理休息
        SeedItem("rest", "睡觉"), SeedItem("rest", "午休"), SeedItem("rest", "吃饭"),
        SeedItem("rest", "发呆"), SeedItem("rest", "躺着"),

        // 杂事
        SeedItem("chore", "待办杂事"), SeedItem("chore", "办事跑腿"), SeedItem("chore", "去医院"),
        SeedItem("chore", "去银行"),
    )

    /** 3 个一级标签（桶）—— 纯容器，不挂记录。 */
    private val BUCKETS = listOf(
        SeedBucket("t1", "🎯", "价值判断"),
        SeedBucket("t2", "🔋", "能量"),
        SeedBucket("t3", "🧭", "领域"),
    )

    /** 8 个二级标签 —— 真正挂到记录上的那一层。`desc` 是一行说明，防止标签越加越含糊。 */
    private val TAGS = listOf(
        SeedTag("g1", "t1", "高价值投资", "#E5484D", "收益明确，对长期目标有推动作用"),
        SeedTag("g2", "t1", "情感充电", "#F062A0", "放松、愉悦、恢复精力（爱好、休闲娱乐）"),
        SeedTag("g3", "t1", "低价值消耗", "#94A3B8", "价值低，可避免"),
        SeedTag("g4", "t2", "能量增益", "#22C55E", "做完精力更足"),
        SeedTag("g5", "t2", "能量消耗", "#E5484D", "做完更累"),
        SeedTag("g6", "t2", "日常必需", "#E8A33D", "不做不行，但不增不减"),
        SeedTag("g7", "t3", "健康维护", "#0EA5E9", "健康饮食、运动、正念"),
        SeedTag("g8", "t3", "社交经营", "#7C5CFF", "经营关系与人脉"),
    )

    /**
     * 把种子写进刚建好的空库。
     *
     * ⚠️ **顺序不能动**：先分类后事项、先桶后标签 —— 外键是真的开着的
     * （Room 建库时会 `PRAGMA foreign_keys = ON`），顺序反了会直接违反约束。
     */
    fun install(db: SupportSQLiteDatabase) {
        // ★ 分类的颜色不再是 CATS 里写死的那几个，而是**算出来的**：
        //   拿 CATS 里的原色当「色相线索」，交给 ColorSeries 分配到 12 个均匀色系里
        //   （就近 + 冲突让位）。这样**新装的库和老库迁移后完全一致** ——
        //   两边跑的是同一个 assignSeries，不存在「新用户和我看到的颜色不一样」。
        val series = ColorSeries.assignSeries(CATS.map { it.color })

        CATS.forEachIndexed { index, cat ->
            db.execSQL(
                "INSERT INTO categories (id, name, colorHex, position) VALUES (?, ?, ?, ?)",
                arrayOf(cat.id, cat.name, ColorSeries.categoryColor(series[index]), index),
            )
        }

        // 每个分类各数各的次序，所以要用一个「分类 → 已经数到几」的小账本
        val counted = mutableMapOf<String, Int>()
        ITEMS.forEach { item ->
            val orderInCat = counted.getOrDefault(item.cat, 0)
            counted[item.cat] = orderInCat + 1

            db.execSQL(
                "INSERT INTO items (id, categoryId, name, colorHex, orderInCat) VALUES (?, ?, ?, ?, ?)",
                arrayOf(
                    newId(),
                    item.cat,
                    item.name,
                    // ★ 空串 = **自动**：跟随分类色系，按 orderInCat 取第 N 档渐变。
                    //   这里故意不写死具体色值 —— 写死了就退回到「一分类一色」那个老问题，
                    //   而且以后分类换色系时这些事项也不会跟着变。
                    //   用户想在事项页单独挑色，那里写进去才是具体值。
                    "",
                    orderInCat,
                ),
            )
        }

        BUCKETS.forEachIndexed { index, bucket ->
            db.execSQL(
                "INSERT INTO tag_buckets (id, icon, name, position) VALUES (?, ?, ?, ?)",
                arrayOf(bucket.id, bucket.icon, bucket.name, index),
            )
        }

        TAGS.forEach { tag ->
            db.execSQL(
                "INSERT INTO tags (id, bucketId, name, colorHex, description) VALUES (?, ?, ?, ?, ?)",
                arrayOf(tag.id, tag.bucket, tag.name, tag.color, tag.desc),
            )
        }

        installTemplate(db)
    }

    // ============================================================ 模板

    /** 固定 id —— 用 `INSERT OR IGNORE`，所以重复跑是安全的（Migration 与 onCreate 共用）。 */
    private const val TPL_ID = "tpl1"

    /** V1 只有一套模板，名字就叫「工作日」。 */
    private const val TPL_NAME = "工作日"

    /** 指派给周一~周五：bit0=周一 … bit4=周五 → `0b0011111` = 31。 */
    private const val TPL_WEEKDAYS = 0b0011111

    /**
     * 模板的 9 段，**与原型逐字一致**（内置那套「工作日」）。
     *
     * ★ 首段起点恒 0、末段终点恒 1440 —— **必须首尾相接覆盖一整天**。
     *   想留白就选一个「未记录」类的事项塞进去，**不许挖洞**。
     *   （理由：模板是「这一天默认长什么样」，一段空白等于说「这半天我什么默认都定不了」，
     *   那就等于没配。要真不想自动记，正确做法是**别启用模板**。）
     *
     * ⚠️ 事项用**名字**在这里指定，插库时用子查询换成 id。
     *   存名字是原型留下的坑（原型的事项没有 id），真机必须是 id ——
     *   否则事项一改名，模板段就全成孤儿。
     */
    private val TPL_SEGS = listOf(
        TplSeg(0, 510, "睡觉"),
        TplSeg(510, 540, "洗脸洗漱"),
        TplSeg(540, 720, "打工"),
        TplSeg(720, 810, "午休"),
        TplSeg(810, 1050, "打工"),
        TplSeg(1050, 1110, "开车通勤"),
        TplSeg(1110, 1170, "做饭"),
        TplSeg(1170, 1380, "陪伴伴侣"),
        TplSeg(1380, 1440, "编程"),
    )

    private class TplSeg(val a: Int, val b: Int, val item: String)

    /**
     * 种下内置模板 —— **`onCreate` 和 `Migration(1→2)` 都调它**。
     *
     * ## 为什么必须写成「两处共用一个函数」
     *
     * 老库（M1/M2-A/M2-B 装出来的）**不会再走 `onCreate`** ——
     * 它的库文件早就存在了。所以光改 [install] 是没用的，
     * 老用户升上来会看到一个「没有模板」的模板页。
     *
     * 于是要补一条 Migration 去种。但**种的内容绝不能写两遍** ——
     * 写两遍就等于「新装的人」和「升级上来的人」拿到的是两份可能不一样的数据，
     * 而这种不一致极难发现（各自都"正常工作"）。
     *
     * ## 用 `INSERT OR IGNORE`
     *
     * 幂等。跑第二遍什么都不做，所以它同时在三条路径上都安全：
     * 全新安装、1→2 升级、以及将来某次重复调用。
     * ⚠️ 它**只补种，不覆盖** —— 用户改过的模板名、挪过的段边界不会被它踩掉。
     */
    fun installTemplate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT OR IGNORE INTO templates (id, name, weekdaysMask, revision, updatedAt) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf(TPL_ID, TPL_NAME, TPL_WEEKDAYS, 1L, 0L),
        )

        // 事项 id 用子查询现查 —— 种子事项的 id 是 UUID（见 newId()），不是按名字定的。
        // ⚠️ 查不到（用户把那个事项删了）→ itemId 落成 NULL，
        //    引擎会把这一段**留成空白**并提示「去重选事项」，这是设计好的降级路径。
        TPL_SEGS.forEachIndexed { index, seg ->
            db.execSQL(
                "INSERT OR IGNORE INTO template_segments " +
                    "(id, templateId, startMin, endMin, itemId, sort) " +
                    "VALUES (?, ?, ?, ?, (SELECT id FROM items WHERE name = ? LIMIT 1), ?)",
                arrayOf("tseg$index", TPL_ID, seg.a, seg.b, seg.item, index),
            )
        }

        // 运行态初值：**没在跑**。
        // ★ 「配置 ≠ 启动」—— 种下模板什么都不会发生，必须用户显式切一下。
        db.execSQL(
            "INSERT OR IGNORE INTO template_run (id, isOn, day, lockedSegSnapshot) VALUES (?, ?, ?, ?)",
            arrayOf(TemplateRunRow.ID, 0, null, null),
        )
    }

    /** 只为了让上面的 SQL 和实体的常量对上（`template_run` 永远只有 1 行）。 */
    private object TemplateRunRow {
        const val ID = 1
    }
}
