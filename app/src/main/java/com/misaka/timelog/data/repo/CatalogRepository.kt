package com.misaka.timelog.data.repo

import androidx.room.withTransaction
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemDefaultTagEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.TagBucketEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.data.newId
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.ColorSeries
import com.misaka.timelog.domain.valueOrNull

/**
 * 字典仓库 —— 分类 / 事项 / 标签桶 / 标签 的增删改。
 *
 * ## ★★ 这里最要紧的一对概念：**归属 ≠ 容器**
 *
 * | | 分类 → 事项 | 一级标签 → 二级标签 |
 * |---|---|---|
 * | 关系 | **归属** | **纯容器** |
 * | 换归属 | 历史统计**跟着一起重算**（v1.11 裁定） | 换个抽屉而已，**一个字都不动** |
 * | 删掉 | 必须先搬空事项 | 必须先搬空标签 |
 * | 下游影响 | 记录擦成空白 | **不改任何记录、不改统计** |
 *
 * 两者的删除守卫**长得一模一样**，但连带影响完全不同 ——
 * 所以实现时**不要**照着分类那一套去抄标签的换桶逻辑。
 *
 * ## ★ 守卫为什么必须在数据层
 *
 * 「先搬空、才可删」如果只写在界面里（把删除按钮灰掉），
 * 将来任何一条绕过界面的代码（导入备份、云同步、批量脚本）都能
 * 造出「指向不存在分类的事项」这种修都没法修的脏数据。
 *
 * 所以这里做**双保险**：外键 RESTRICT 兜底（防 SQL 层面的意外），
 * 这一层先说人话（防用户体验上的困惑）。两处都要有。
 */
class CatalogRepository(
    private val db: TimeLogDatabase,
    private val timeline: TimelineRepository,
) {

    private val categories = db.categoryDao()
    private val items = db.itemDao()
    private val buckets = db.tagBucketDao()
    private val tags = db.tagDao()

    // ================================================================ 分类

    suspend fun categories(): List<CategoryEntity> = categories.all()

    suspend fun category(id: String): CategoryEntity? = categories.byId(id)

    suspend fun addCategory(name: String, colorHex: String): Outcome<CategoryEntity> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("分类名不能是空的。")

        val category = CategoryEntity(
            id = newId(),
            name = clean,
            colorHex = colorHex,
            position = categories.nextPosition(),
        )
        categories.insert(category)
        return Outcome.Ok(category)
    }

    /** 改名。⚠️ 历史记录挂的是 id，所以改名**一个字都不用去动记录**。 */
    suspend fun renameCategory(id: String, name: String): Outcome<Unit> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("分类名不能是空的。")
        val c = categories.byId(id) ?: return Outcome.Blocked("这个分类已经不在了。")
        categories.update(c.copy(name = clean))
        return Outcome.Ok(Unit)
    }

    suspend fun recolorCategory(id: String, colorHex: String): Outcome<Unit> {
        val c = categories.byId(id) ?: return Outcome.Blocked("这个分类已经不在了。")
        categories.update(c.copy(colorHex = colorHex))
        return Outcome.Ok(Unit)
    }

    /**
     * 上下移动一个分类。
     *
     * 用「重排整个列表、按下标重写 position」而不是「和邻居交换 position」——
     * 后者在 position 出现重复值时（比如导入过一份脏数据）会静默失效。
     */
    suspend fun moveCategory(id: String, delta: Int): Outcome<Unit> = db.withTransaction {
        val list = categories.all()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@withTransaction Outcome.Blocked("这个分类已经不在了。")

        val to = from + delta
        if (to < 0 || to >= list.size) {
            return@withTransaction Outcome.Blocked(if (delta < 0) "已经在最上面了。" else "已经在最下面了。")
        }

        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        reordered.forEachIndexed { index, c ->
            if (c.position != index) categories.updatePosition(c.id, index)
        }
        Outcome.Ok(Unit)
    }

    /**
     * ★ 删分类 —— **先搬空、才可删**。
     *
     * 拦下来的理由是给用户看的：告诉他还有几个事项，而不是「违反外键约束」。
     */
    suspend fun deleteCategory(id: String): Outcome<Unit> {
        val count = categories.itemCount(id)
        if (count > 0) {
            return Outcome.Blocked("这个分类下还有 $count 个事项，得先把它们搬走或者删掉。")
        }
        categories.deleteById(id)
        return Outcome.Ok(Unit)
    }

    // ================================================================ 事项

    /*
     * ## ★★ 事项颜色：「自动」与「手选」两种，**收口就在这三个函数**
     *
     * 库里 `items.colorHex` 有两种含义：
     *
     * | 值 | 含义 | 谁来算 |
     * |---|---|---|
     * | `""`（空） | **自动** —— 跟随所属分类的色系，按 `orderInCat` 取第 N 档渐变 | [ColorSeries.itemColor] |
     * | `#RRGGBB` | **手选** —— 用户自己挑的，任何地方都不许改写 | 用户 |
     *
     * ⚠️ **为什么要在这一层解析，而不是让界面各自去算**：
     * 取事项颜色的地方有十几处（时间线色块、选择网格、统计、小部件、模板页、
     * 快捷选事项窗…）。让每处都 `if (colorHex.isEmpty()) ColorSeries.itemColor(...)`
     * 的话，**必然漏掉一处**，而漏掉的样子是「那个页面上这个事项是黑的/灰的」——
     * 不报错、不崩，只有肉眼能发现。这是项目里栽过 8 次的那类坑。
     *
     * 所以规矩是：**除了这三个函数，没有任何地方允许看到空字符串**。
     */

    suspend fun items(): List<ItemEntity> = resolveColors(items.all())

    suspend fun itemsIn(categoryId: String): List<ItemEntity> =
        resolveColors(items.byCategory(categoryId))

    suspend fun item(id: String): ItemEntity? {
        val raw = items.byId(id) ?: return null
        return resolveColors(listOf(raw)).first()
    }

    /**
     * 把「自动」的 [ItemEntity.colorHex] 换成具体色值。
     *
     * 手选过的原样返回 —— **一个字都不改**，用户挑的就是用户挑的。
     * 具体规则见 [resolveItemColors]（那是唯一入口）。
     */
    private suspend fun resolveColors(list: List<ItemEntity>): List<ItemEntity> {
        if (list.none { it.colorHex.isBlank() }) return list
        val catColor = categories.all().associate { it.id to it.colorHex }
        return resolveItemColors(list, catColor)
    }

    suspend fun addItem(categoryId: String, name: String, colorHex: String): Outcome<ItemEntity> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("事项名不能是空的。")
        val cat = categories.byId(categoryId) ?: return Outcome.Blocked("归属的分类已经不在了。")

        val item = ItemEntity(
            id = newId(),
            categoryId = categoryId,
            name = clean,
            // 传空 = 自动跟随分类色系（新增事项默认就是这个）
            colorHex = colorHex,
            orderInCat = items.nextOrderInCat(categoryId),
        )
        items.insert(item)
        // 返回给界面的是**解析后**的（否则刚加完那一格是空的/黑的）
        return Outcome.Ok(
            if (item.colorHex.isBlank()) {
                item.copy(colorHex = ColorSeries.itemColor(cat.colorHex, item.orderInCat))
            } else {
                item
            },
        )
    }

    suspend fun renameItem(id: String, name: String): Outcome<Unit> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("事项名不能是空的。")
        val i = items.byId(id) ?: return Outcome.Blocked("这个事项已经不在了。")
        // ⚠️ 按 id 回写。原型里踩过「按名字回写」的坑：改名的第一个字还没敲完就找不到自己了
        items.update(i.copy(name = clean))
        return Outcome.Ok(Unit)
    }

    suspend fun recolorItem(id: String, colorHex: String): Outcome<Unit> {
        val i = items.byId(id) ?: return Outcome.Blocked("这个事项已经不在了。")
        items.update(i.copy(colorHex = colorHex))
        return Outcome.Ok(Unit)
    }

    /**
     * ★ **换归属分类** —— 这一改动会**连带重算历史统计**。
     *
     * 记录上留着一份「当时分类快照」(`categoryIdSnap`)，但统计**不按它算**：
     * 统计按事项**当前**归属算。所以把「打游戏」从「娱乐」挪到「学习」之后，
     * 过去一年打游戏的时间会**一起挪到学习的账上**（v1.11 推翻 v1.0 的裁定）。
     *
     * 换归属后要重排次序：它在新分类里排到最后。
     */
    suspend fun moveItemToCategory(itemId: String, categoryId: String): Outcome<Unit> = db.withTransaction {
        val i = items.byId(itemId) ?: return@withTransaction Outcome.Blocked("这个事项已经不在了。")
        if (categories.byId(categoryId) == null) {
            return@withTransaction Outcome.Blocked("目标分类已经不在了。")
        }
        if (i.categoryId == categoryId) return@withTransaction Outcome.Ok(Unit)

        items.update(i.copy(categoryId = categoryId, orderInCat = items.nextOrderInCat(categoryId)))
        Outcome.Ok(Unit)
    }

    /** 事项在自己分类内上下移动。 */
    suspend fun moveItem(itemId: String, delta: Int): Outcome<Unit> = db.withTransaction {
        val item = items.byId(itemId) ?: return@withTransaction Outcome.Blocked("这个事项已经不在了。")
        val siblings = items.byCategory(item.categoryId)
        val from = siblings.indexOfFirst { it.id == itemId }
        if (from < 0) return@withTransaction Outcome.Blocked("这个事项已经不在了。")

        val to = from + delta
        if (to < 0 || to >= siblings.size) {
            return@withTransaction Outcome.Blocked(if (delta < 0) "已经在最前面了。" else "已经在最后面了。")
        }

        val reordered = siblings.toMutableList().apply { add(to, removeAt(from)) }
        reordered.forEachIndexed { index, s ->
            if (s.orderInCat != index) items.updateOrderInCat(s.id, index)
        }
        Outcome.Ok(Unit)
    }

    /**
     * ★★ 删事项 —— **历史全部擦成空白块**（用户的原话）。
     *
     * 顺序很讲究：
     * 1. 先把历史记录擦成空白（[TimelineRepository.eraseItemFromHistory]）——
     *    这一步**必须真的执行**，因为 `records.itemId` 上**没有外键**，数据库不会帮忙
     * 2. 再删事项本身
     *
     * `item_default_tags` 和模板段上对它的引用由数据库自己处理
     * （CASCADE / SET_NULL）。模板段会变成悬空，渲染成「去重选事项」。
     *
     * @return 擦掉了几条历史记录
     */
    suspend fun deleteItemDeep(itemId: String): Outcome<Int> = db.withTransaction {
        items.byId(itemId) ?: return@withTransaction Outcome.Blocked("这个事项已经不在了。")

        val erased = timeline.eraseItemFromHistory(itemId)
        items.deleteById(itemId)
        Outcome.Ok(erased.valueOrNull() ?: 0)
    }

    /**
     * 删事项前的「后果预览」—— 用于那个确认弹窗。
     *
     * 目的不是吓唬用户，是让他知道**这一下会牵动多少**：
     * 「这个事项记过 87 条，共 132 小时，有 3 段模板在用它 —— 删掉之后这些时间全变成空白块。」
     */
    suspend fun itemImpact(itemId: String): ItemImpact {
        val affected = db.recordDao().byItem(itemId)
        val minutes = affected.sumOf { r ->
            val end = r.endMin ?: r.startMin // 进行中的那条算 0 分钟
            (end - r.startMin).coerceAtLeast(0)
        }
        return ItemImpact(
            recordCount = affected.size,
            totalMinutes = minutes,
            // 模板段数等 M3 建好模板仓库再补上，现在恒为 0
            templateSegmentCount = 0,
        )
    }

    // ============================================================ 标签 · 桶

    suspend fun buckets(): List<TagBucketEntity> = buckets.all()

    suspend fun addBucket(icon: String, name: String): Outcome<TagBucketEntity> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("标签桶的名字不能是空的。")
        val bucket = TagBucketEntity(
            id = newId(),
            icon = icon.ifBlank { "🏷" },
            name = clean,
            position = buckets.nextPosition(),
        )
        buckets.insert(bucket)
        return Outcome.Ok(bucket)
    }

    suspend fun renameBucket(id: String, name: String): Outcome<Unit> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("标签桶的名字不能是空的。")
        val b = buckets.byId(id) ?: return Outcome.Blocked("这个标签桶已经不在了。")
        buckets.update(b.copy(name = clean))
        return Outcome.Ok(Unit)
    }

    suspend fun setBucketIcon(id: String, icon: String): Outcome<Unit> {
        val b = buckets.byId(id) ?: return Outcome.Blocked("这个标签桶已经不在了。")
        buckets.update(b.copy(icon = icon))
        return Outcome.Ok(Unit)
    }

    suspend fun moveBucket(id: String, delta: Int): Outcome<Unit> = db.withTransaction {
        val list = buckets.all()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@withTransaction Outcome.Blocked("这个标签桶已经不在了。")

        val to = from + delta
        if (to < 0 || to >= list.size) {
            return@withTransaction Outcome.Blocked(if (delta < 0) "已经在最上面了。" else "已经在最下面了。")
        }

        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        reordered.forEachIndexed { index, b ->
            if (b.position != index) buckets.updatePosition(b.id, index)
        }
        Outcome.Ok(Unit)
    }

    /**
     * ★ 删标签桶 —— **先搬空、才可删**。
     *
     * ⚠️ 和删分类长得一样，但**连带影响不只是「不一样」，而是「没有」**：
     * 桶是纯容器，记录上根本不挂桶，所以删桶**一个字都不会牵连到记录，也不改统计**。
     */
    suspend fun deleteBucket(id: String): Outcome<Unit> {
        val count = buckets.tagCount(id)
        if (count > 0) {
            return Outcome.Blocked("这个标签桶里还有 $count 个标签，得先把它们搬走。")
        }
        buckets.deleteById(id)
        return Outcome.Ok(Unit)
    }

    // ============================================================ 标签 · 标签

    suspend fun tags(): List<TagEntity> = tags.all()

    suspend fun tagsIn(bucketId: String): List<TagEntity> = tags.byBucket(bucketId)

    suspend fun tag(id: String): TagEntity? = tags.byId(id)

    suspend fun addTag(bucketId: String, name: String, colorHex: String, description: String): Outcome<TagEntity> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("标签名不能是空的。")
        if (buckets.byId(bucketId) == null) return Outcome.Blocked("归属的标签桶已经不在了。")

        val tag = TagEntity(
            id = newId(),
            bucketId = bucketId,
            name = clean,
            colorHex = colorHex,
            description = description.trim(),
        )
        tags.insert(tag)
        return Outcome.Ok(tag)
    }

    /** 改名。⚠️ 挂 id 之后这就是一行的事，**不需要去扫几百条记录**（原型里必须扫）。 */
    suspend fun renameTag(id: String, name: String): Outcome<Unit> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Blocked("标签名不能是空的。")
        val t = tags.byId(id) ?: return Outcome.Blocked("这个标签已经不在了。")
        tags.update(t.copy(name = clean))
        return Outcome.Ok(Unit)
    }

    suspend fun editTag(id: String, colorHex: String, description: String): Outcome<Unit> {
        val t = tags.byId(id) ?: return Outcome.Blocked("这个标签已经不在了。")
        tags.update(t.copy(colorHex = colorHex, description = description.trim()))
        return Outcome.Ok(Unit)
    }

    /** 换桶 = 换个抽屉。**不动任何记录、不动统计。** */
    suspend fun moveTagToBucket(tagId: String, bucketId: String): Outcome<Unit> {
        if (buckets.byId(bucketId) == null) return Outcome.Blocked("目标标签桶已经不在了。")
        tags.byId(tagId) ?: return Outcome.Blocked("这个标签已经不在了。")
        tags.moveToBucket(tagId, bucketId)
        return Outcome.Ok(Unit)
    }

    /**
     * ★ 删标签 —— 已拍板：**级联摘掉引用**，而不是拒绝删。
     *
     * 为什么选级联：想删一个不用的标签时，选「拒绝删」就意味着得先去几百条记录里
     * 一条条把它摘下来 —— 太烦。代价只是历史记录上少一个标记，
     * 而「删掉这个标签」本身就是用户的明确意图。
     *
     * 实现上**不需要手写摘引用的代码**：`record_tags.tagId` 和
     * `item_default_tags.tagId` 上都是 CASCADE 外键，数据库会自动清干净。
     * ⚠️ 这正是「记录存 id 而不是存名字」换来的好处 —— 原型存的是 `'#标签名'`
     * 字符串，删除时必须手写代码扫全表，漏一处就留下孤儿标签。
     */
    suspend fun deleteTag(id: String): Outcome<Unit> {
        tags.byId(id) ?: return Outcome.Blocked("这个标签已经不在了。")
        tags.deleteById(id)
        return Outcome.Ok(Unit)
    }

    /**
     * 删标签的后果预览 —— 给确认弹窗用。
     *
     * ⚠️ 这两个数**都是 CASCADE 会静默清掉的东西**：
     * - `recordRefs`：历史记录上的这个标记
     * - `itemUsers`：把「删掉标签」的代价放大成「事项的默认标签也没了」，
     *   而后者从界面上完全看不出来（得点进某个事项的弹窗才发现空了）
     */
    suspend fun tagImpact(tagId: String): TagImpact = TagImpact(
        recordRefs = tags.countRecordRefs(tagId),
        itemUsers = items.countDefaultTagUsers(tagId),
    )

    // ====================================================== 事项的默认标签

    /** 记这个事项时会自动带上的标签。 */
    suspend fun defaultTagIds(itemId: String): List<String> = items.defaultTagIds(itemId)

    /**
     * 所有事项的默认标签，按事项分组。
     *
     * 事项列表用它显示「这个名字下面挂了哪几个标签」——
     * 免得为了看一眼还得逐个点开弹窗。
     * 一次全量查（2 次 SQL），不在列表里做 N+1。
     */
    suspend fun defaultTagsByItem(): Map<String, List<TagEntity>> {
        val refs = items.allDefaultTagRefs()
        if (refs.isEmpty()) return emptyMap()
        val byId = tags.all().associateBy { it.id }
        return refs
            .mapNotNull { ref -> byId[ref.tagId]?.let { ref.itemId to it } }
            .groupBy({ it.first }, { it.second })
    }

    /** 给事项设一批默认标签（整体替换，用起来最省心）。 */
    suspend fun setDefaultTags(itemId: String, tagIds: List<String>): Outcome<Unit> = db.withTransaction {
        items.byId(itemId) ?: return@withTransaction Outcome.Blocked("这个事项已经不在了。")

        // 防悬空：有不存在的 id 就整体拒绝，不要静默丢
        if (tagIds.isNotEmpty() && tags.countExisting(tagIds) != tagIds.size) {
            return@withTransaction Outcome.Blocked("有标签已经被删掉了，刷新一下再来。")
        }

        items.clearDefaultTags(itemId)
        if (tagIds.isNotEmpty()) {
            items.addDefaultTags(tagIds.map { ItemDefaultTagEntity(itemId = itemId, tagId = it) })
        }
        Outcome.Ok(Unit)
    }
}

/** 删事项的后果预览。 */
data class ItemImpact(
    /** 有多少条历史记录挂着它。 */
    val recordCount: Int,
    /** 这些记录加起来多少分钟。 */
    val totalMinutes: Int,
    /** 有几段模板正在用它（M3 补上，现在恒 0）。 */
    val templateSegmentCount: Int,
) {
    /** 给人看的一句话。 */
    fun describe(): String {
        if (recordCount == 0 && templateSegmentCount == 0) {
            return "这个事项还没有记过任何时间，删掉它不会有影响。"
        }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val duration = if (hours > 0) "${hours} 小时 ${mins} 分钟" else "${mins} 分钟"

        return buildString {
            append("这个事项记过 $recordCount 条，共 $duration")
            if (templateSegmentCount > 0) append("，有 $templateSegmentCount 段模板在用它")
            append("。删掉之后，这些时间会全部变成「未记录」的空白块。")
        }
    }
}

/**
 * 删标签的后果预览。
 *
 * 为什么标签要问、事项不用问（事项反而要弹确认）：差别在**可逆性**。
 * 删事项留下的是可见的「未记录」空白块，你一眼能看见、能补记；
 * 删标签摘掉的是**看不见的引用** —— 记录卡片上少一行小字，
 * 事项弹窗里少一个勾，都属于「事后才发现，且无法还原」。
 */
data class TagImpact(
    /** 有多少条历史记录挂着它。 */
    val recordRefs: Int,
    /** 有多少个事项把它设为默认标签。 */
    val itemUsers: Int,
) {
    /** 有没有值得提醒的连带影响。 */
    val warn: Boolean get() = recordRefs > 0 || itemUsers > 0

    fun describe(): String = buildString {
        if (recordRefs > 0) {
            append("会把 $recordRefs 条历史记录上的这个标记全部摘掉")
        }
        if (itemUsers > 0) {
            if (isNotEmpty()) append("；")
            append("$itemUsers 个事项把它设成了默认标签，那些也会一起清空")
        }
        if (isEmpty()) {
            append("这个标签还没被任何记录或事项用到，删掉不会有影响。")
        } else {
            append("。\n\n这一步没法撤销。")
        }
    }
}
