package com.misaka.timelog.data.import

import android.content.res.AssetManager
import androidx.room.withTransaction
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.dao.CategoryDao
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.newId
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.ColorSeries
import com.misaka.timelog.domain.ImportDay
import com.misaka.timelog.domain.ImportReport
import com.misaka.timelog.domain.ImportSegment
import com.misaka.timelog.domain.MINUTES_PER_DAY
import com.misaka.timelog.domain.Outcome
import org.json.JSONObject

/**
 * ★ 把旧版「时间日志」导出来的历史数据搬进本 App。
 *
 * ## 数据是怎么来的
 *
 * 御坂把旧 App（`me.liujia95.solitude`）的导出表丢过来，经 `_import/` 下那套
 * Python 脚本换算成 [ASSET] 这份 JSON，随 APK 一起装进去。
 *
 * 换算阶段（**不在这个类里**）已经处理掉的事，这里不再重复做：
 * - 边界时刻各自四舍五入到分钟（保证相邻两段首尾严格相接）
 * - 丢掉四舍五入后塌成零长度的段
 * - 跨天按 24:00 切开、共用 `chainId`
 * - 重叠按「短段优先占位、长段让位」消解（旧 App 允许记录互相重叠，本 App 不允许）
 * - 相邻同事项合并（**合并前要比过备注和标签**，不然会丢信息）
 *
 * 所以这里拿到的东西**已经是一天一天、首尾相接**的了 —— 但「已经」不等于
 * 「可以不查」：这个类仍然要把每一天交给 `TimelineRepository` 再体检一遍。
 *
 * ## 这个类的职责边界
 *
 * | 它管 | 它不管 |
 * |---|---|
 * | 读 assets、解 JSON | 密铺校验、补空白（`TimelineRepository` 管） |
 * | 把**事项名/标签名**解析成 id | 往 `records` 写一个字（同上） |
 * | 按名字补齐 App 里没有的事项 | 界面（`ImportViewModel` / `ImportScreen` 管） |
 *
 * ⚠️ 名字 → id 这一步**必须在这里做**，因为 JSON 里存的是名字（旧 App 没有 id 概念），
 * 而本 App 的记录一律挂 id —— 这正是「改名不会留下孤儿标签」那条设计的入口。
 */
class LegacyImport(
    private val db: TimeLogDatabase,
    private val timeline: TimelineRepository,
    private val assets: AssetManager,
) {

    // ------------------------------------------------------------------ 对外

    /**
     * 导入前的「看一眼会发生什么」—— **只读，不会改库**。
     *
     * 这一条是硬要求：用户在设置页点进去的那一下，不该有任何副作用。
     * 所以它**不建事项**，只是把「有哪 5 个事项得新建」算出来给用户看。
     */
    suspend fun preview(): Outcome<ImportPreview> = withAssetText { previewOf(it) }

    /**
     * 预览**一份外部文件**（用户在系统文件选择器里挑的那份）。
     *
     * 和 [preview] 唯一的区别是数据从哪来。解析失败**不抛异常**，
     * 而是返回 [Outcome.Blocked] —— 用户可能选中了任何一张图片或者聊天记录，
     * 那是正常操作失误，不该让 App 崩掉。
     */
    suspend fun previewOf(text: String): Outcome<ImportPreview> {
        val parsed = parseOrNull(text) ?: return notBackup()
        val plan = plan(parsed)

        val toCreateNames = parsed.newItems.map { it.name }.filter { it in plan.unresolvedItems }
        val hardMissing = (plan.unresolvedItems - parsed.newItems.map { it.name }.toSet()).sorted()

        return Outcome.Ok(
            ImportPreview(
                days = plan.spec.size,
                segments = plan.segmentCount,
                gapCount = plan.gapCount,
                gapMinutes = plan.gapMinutes,
                firstDay = parsed.days.firstOrNull()?.day ?: "",
                lastDay = parsed.days.lastOrNull()?.day ?: "",
                toCreate = toCreateNames,
                // 自建分类会顺带被重建 —— 这件事得让用户在按下去之前就知道，
                // 而不是导完发现「怎么多了一个分类」。
                toCreateCategories = plan.categoriesToCreate,
                hardMissingItems = hardMissing,
                missingTags = plan.unresolvedTags.sorted(),
                existingRecords = db.recordDao().countAll(),
            )
        )
    }

    /**
     * 真导入（读随包内置的那份）。
     *
     * 顺序：**先按名字补齐事项 → 再解析 → 再整库替换**。
     *
     * ⚠️ 补齐事项这一步是幂等的（已经有了就跳过），所以导入失败之后再点一次
     * 也不会多建出一份重名事项。
     */
    suspend fun run(): Outcome<ImportReport> = withAssetText { runOf(it) }

    /**
     * 真导入**一份外部文件**（就是「导出 → 换机 → 导回来」那条路）。
     *
     * ⚠️ 它跟 [run] 走的是同一条代码路径（`ensureItems` → `plan` → `replaceAllRecords`），
     * 所以不存在「备份导回来和内置数据导入行为不一样」这种可能。
     */
    suspend fun runOf(text: String): Outcome<ImportReport> {
        val parsed = parseOrNull(text) ?: return notBackup()

        // ① 先把 App 里没有、但记录里用到的事项建出来
        ensureItems(parsed.newItems)

        // ② 再解析（这时字典已经齐了）
        val plan = plan(parsed)
        if (plan.unresolvedItems.isNotEmpty()) {
            return Outcome.Blocked(
                "有几个事项在 App 里找不到：「${plan.unresolvedItems.sorted().joinToString("、")}」，" +
                    "整包都没有导入。"
            )
        }

        // ③ 换整份数据（校验 / 补空白 / 留快照全在仓库里）
        return timeline.replaceAllRecords(plan.spec, "导入（${parsed.days.size} 天）")
    }

    /** 撤销上一次整库替换。 */
    suspend fun undo(): Outcome<Int> = timeline.restoreSnapshot()

    /** 现在有没有可回退的快照 —— 界面靠它决定「撤销」显不显示。 */
    suspend fun hasSnapshot(): Boolean = timeline.hasSnapshot()

    // ------------------------------------------------------------------ 解析

    /** 随包内置的那份文件。⚠️ 改名要跟 `_import/build_import.py` 一起改。 */
    companion object {
        const val ASSET = "legacy_timelog.json"
    }

    private class RawSegment(
        val a: Int,
        val b: Int,
        val item: String,
        val note: String,
        val tags: List<String>,
        val chain: String?,
    )

    private class RawDay(val day: String, val segments: List<RawSegment>)

    /**
     * 记录里用到、但 App 的字典里没有、需要新建的事项。
     *
     * `categoryName` / `categoryColorHex` 是**本 App 的导出备份才有的可选字段**
     * （内置的旧版文件里没有）。它们解决的是同一个问题：**换设备之后，
     * 用户自建的那个分类在目标库里根本不存在**（真实库里就有一个 UUID 的「学习」），
     * 只有 id 的话 `ensureItems` 找不到归属，整个导入就被拦下。
     */
    private class NewItem(
        val name: String,
        val categoryId: String,
        val categoryName: String?,
        val categoryColorHex: String?,
    )

    private class Parsed(val days: List<RawDay>, val newItems: List<NewItem>)

    private class Plan(
        val spec: List<ImportDay>,
        /** 记录里出现、字典里查不到的事项名。**空的才可以导入。** */
        val unresolvedItems: Set<String>,
        /** 记录里出现、字典里查不到的标签名。**不拦导入**，只丢掉这几个标签。 */
        val unresolvedTags: Set<String>,
        val segmentCount: Int,
        val gapCount: Int,
        val gapMinutes: Int,
        /**
         * 会**顺带新建**的分类名。
         *
         * 只有一种情况会非空：备份来自另一台设备，里面有用户自建的分类
         * （或者事项挂在 `newItems` 带过来的分类上）。内置的旧版文件永远是空的 ——
         * 它的事项全部落在种子分类里。
         */
        val categoriesToCreate: List<String>,
    )

    /**
     * 读随包内置的那份文件，并把它交给 [block]。
     *
     * 单独抽出来是为了让 [preview] / [run] 的外部版本（[previewOf] / [runOf]）
     * **走完全同一条后续路径** —— 唯一不同的只有「文字从哪来」。
     *
     * ⚠️ 这里 catch 的是「文件本身读不出来」（assets 缺失 / IO 失败）。
     *    assets 是随包出厂的，正常永远不会失败；但不 catch 的话，
     *    一旦失败就是整个设置页崩掉，而失败原因一个字都看不到。
     */
    private suspend fun <T> withAssetText(block: suspend (String) -> Outcome<T>): Outcome<T> =
        try {
            val text = assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            block(text)
        } catch (t: Throwable) {
            Outcome.Blocked("内置数据文件读不出来：${t.message ?: t.javaClass.simpleName}")
        }

    /** 解析；不是合法 JSON 或结构不对就返回 `null`（**绝不往外抛**）。 */
    private fun parseOrNull(text: String): Parsed? = try {
        parse(text)
    } catch (t: Throwable) {
        null
    }

    private fun notBackup(): Outcome.Blocked =
        Outcome.Blocked("这个文件不是「一天账本」导出的备份（缺少 days / newItems 这些必需字段）。")

    /**
     * 解 JSON。
     *
     * 段字段（**短名是故意的** —— 这份文件有 6516 段，长键名纯粹是浪费体积）：
     * `a` 起点分钟 ｜ `b` 终点分钟 ｜ `i` 事项名 ｜ `n` 备注（可选）
     * `t` 标签名数组（可选）｜ `c` 跨天链 id（可选）
     */
    private fun parse(text: String): Parsed {
        val root = JSONObject(text)

        val daysArr = root.getJSONArray("days")
        val days = ArrayList<RawDay>(daysArr.length())
        for (i in 0 until daysArr.length()) {
            val d = daysArr.getJSONObject(i)
            val segsArr = d.getJSONArray("s")
            val segs = ArrayList<RawSegment>(segsArr.length())
            for (j in 0 until segsArr.length()) {
                val s = segsArr.getJSONObject(j)
                val ta = s.optJSONArray("t")
                val tagNames = if (ta == null) emptyList() else (0 until ta.length()).map { ta.getString(it) }
                segs += RawSegment(
                    a = s.getInt("a"),
                    b = s.getInt("b"),
                    item = s.getString("i"),
                    note = s.optString("n", ""),
                    tags = tagNames,
                    chain = s.optString("c", "").ifEmpty { null },
                )
            }
            days += RawDay(d.getString("d"), segs)
        }

        val niArr = root.optJSONArray("newItems")
        val newItems = ArrayList<NewItem>()
        if (niArr != null) {
            for (i in 0 until niArr.length()) {
                val o = niArr.getJSONObject(i)
                newItems += NewItem(
                    name = o.getString("name"),
                    // 三个都按「可选」读：内置的旧版文件只有 categoryId，
                    // 而导出备份多带两个 —— 少一个就用不上，不能因此报错。
                    categoryId = o.optString("categoryId", ""),
                    categoryName = o.optString("categoryName", "").ifEmpty { null },
                    categoryColorHex = o.optString("categoryColorHex", "").ifEmpty { null },
                )
            }
        }

        return Parsed(days, newItems)
    }

    /**
     * 把名字翻译成 id，并顺手算出「预计会补出多少空白」。
     *
     * ⚠️ 这里算的空白分钟数跟 `TimelineRepository` 写进去的**本来是两个实现** ——
     * 所以测试里专门断言两者相等。预览是给人看的、报告是给账看的，
     * 两个数字对不上就是 bug，不是「差不多」。
     */
    private suspend fun plan(parsed: Parsed): Plan {
        val itemIdByName = db.itemDao().all().associate { it.name to it.id }
        val tagIdByName = db.tagDao().all().associate { it.name to it.id }

        // 「将要新建的分类」——**只算不建**（preview 不能有副作用）。
        // 判定跟 `ensureItems` 里那三行必须一致：id 命中 → 名字命中 → 才轮到新建。
        val existingCats = db.categoryDao().all()
        val categoriesToCreate = parsed.newItems
            .filter { ni ->
                existingCats.none { it.id == ni.categoryId } &&
                    ni.categoryName != null &&
                    existingCats.none { it.name == ni.categoryName }
            }
            .mapNotNull { it.categoryName }
            .distinct()

        val unresolvedItems = linkedSetOf<String>()
        val unresolvedTags = linkedSetOf<String>()
        var segmentCount = 0
        var gapCount = 0
        var gapMinutes = 0

        val spec = parsed.days.map { d ->
            // ⚠️ 段数**按原始段数计，不跟解析成败挂钩**。
            //    一开始写在 mapNotNull 里面，于是「预览」比「真导入」少算了 1211 段 ——
            //    因为预览时那 5 个新事项还没建出来，挂它们的段全被算成「没解析成功」。
            //    预览和报告是同一件事的两个说法，数字必须一样；测试当场就把它抓出来了。
            segmentCount += d.segments.size

            val segs = d.segments.mapNotNull { s ->
                val itemId = itemIdByName[s.item]
                if (itemId == null) {
                    unresolvedItems += s.item
                    return@mapNotNull null
                }
                val ids = s.tags.mapNotNull { t ->
                    val id = tagIdByName[t]
                    if (id == null) unresolvedTags += t
                    id
                }
                ImportSegment(
                    startMin = s.a,
                    endMin = s.b,
                    itemId = itemId,
                    note = s.note,
                    tagIds = ids,
                    chainId = s.chain,
                )
            }

            // 空白账（只用来给用户报数，不参与写入）
            var cursor = 0
            d.segments.forEach { s ->
                if (cursor < s.a) {
                    gapCount++
                    gapMinutes += s.a - cursor
                }
                cursor = s.b
            }
            if (cursor < MINUTES_PER_DAY) {
                gapCount++
                gapMinutes += MINUTES_PER_DAY - cursor
            }

            ImportDay(d.day, segs)
        }

        return Plan(
            spec = spec,
            unresolvedItems = unresolvedItems,
            unresolvedTags = unresolvedTags,
            segmentCount = segmentCount,
            gapCount = gapCount,
            gapMinutes = gapMinutes,
            categoriesToCreate = categoriesToCreate,
        )
    }

    /**
     * 按名字补齐事项 —— **已经有了就跳过**（幂等）。
     *
     * ## 新事项挂到哪个分类
     *
     * JSON 里的 `categoryId` 直接写得就是种子的分类 id（`love` / `life` / `relax` / `social`）——
     * 因为分类 id 当初是**手写的固定字符串**（见 `SeedData.CATS`），不是 UUID。
     * 但万一用户改过分类名/删过分类，就退一步按「分类名 == 事项名」再找一次
     * （`恋爱相伴` 这个事项正好就落在同名分类里）；两次都找不到就**不建**——
     * 后面 `run()` 会把它报成「找不到的事项」并拦下，而不是悄悄塞进一个错的分类。
     */
    private suspend fun ensureItems(newItems: List<NewItem>): List<String> = db.withTransaction {
        val items = db.itemDao()
        val catDao = db.categoryDao()
        val alive = items.all().map { it.name }.toMutableSet()

        // ⚠️ 必须是 var，**每建一个分类都要把它并进来**。
        //    用进循环前那份快照的话：同一个新分类下的第二个事项会"又找不到"，
        //    于是再建一个同名分类 —— 表现是导完之后多出一串重复分类，
        //    而且不报错、只在「自建分类 + 多个事项」时才出现。
        var categories = catDao.all()
        val created = mutableListOf<String>()

        newItems.forEach { ni ->
            if (!alive.add(ni.name)) return@forEach // 已经有了

            val cat = categories.firstOrNull { it.id == ni.categoryId }
                ?: ni.categoryName?.let { cn -> categories.firstOrNull { it.name == cn } }
                ?: buildCategory(ni, categories, catDao)?.also { categories = categories + it }
                ?: return@forEach

            items.insert(
                ItemEntity(
                    id = newId(),
                    categoryId = cat.id,
                    name = ni.name,
                    // ★ 空串 = 自动（跟随分类色系，按 orderInCat 取第 N 档渐变）。
                    //   ⚠️ 这里**不能**写 `cat.colorHex` —— 那样导进来的事项全部是「手选」色，
                    //   同一分类下还是一模一样的颜色，等于把「组内分不出来」这个老问题
                    //   带进新库（而且 2→3 迁移已经跑过了，这辈子不会再帮你清一次）。
                    colorHex = "",
                    orderInCat = items.nextOrderInCat(cat.id),
                )
            )
            created += ni.name
        }
        created
    }

    /**
     * 目标库里没有这个分类（备份来自另一台设备、分类是用户自建的）→ **按备份重建一个**。
     *
     * ## 为什么非要建，而不是"随便挂个分类"
     *
     * 挂错分类是**静默的错**：记录照样导进去、数照样对得上，但统计口径全错，
     * 而且用户不会发现 —— 他只会觉得"那几天的学习时间怎么不见了"。
     * 建一个分类最多是「多了一个分类」，那是看得见、可以手动改的。
     *
     * ## id 为什么沿用备份里的
     *
     * 为了**幂等**：同一份备份导两次，第二次 `firstOrNull { it.id == ni.categoryId }`
     * 会直接命中刚建的那个，不会再多建一个。
     *
     * ⚠️ 颜色：备份里有就用它（用户当初挑的那个），没有就取一个还没被占用的色系。
     *    `ColorSeries` 是纯 Kotlin 的，放在数据层用没有依赖问题。
     */
    private suspend fun buildCategory(
        ni: NewItem,
        existing: List<CategoryEntity>,
        catDao: CategoryDao,
    ): CategoryEntity? {
        val name = ni.categoryName?.trim().orEmpty()
        if (name.isEmpty()) return null // 连名字都没有，就真的没法建了

        val cat = CategoryEntity(
            id = ni.categoryId.ifBlank { newId() },
            name = name,
            // ⚠️ `nextFreeSeries` 返回的是**色系索引（Int）**，不是颜色值 ——
            //    要再经 `categoryColor(idx)` 才拿到 `#RRGGBB`。少转这一手类型就不对。
            colorHex = ni.categoryColorHex?.takeIf { it.isNotBlank() }
                ?: ColorSeries.categoryColor(
                    ColorSeries.nextFreeSeries(existing.map { it.colorHex })
                ),
            position = catDao.nextPosition(),
        )
        catDao.insert(cat)
        return cat
    }
}

/**
 * 导入前给用户看的那一屏字。
 *
 * 为什么要 preview 而不是「点一下直接导」：导入会**清空现有记录**，
 * 而这是全 App 唯一一个会动到一整年数据的操作。用户有权在按下去之前
 * 先看见「要写进多少、今天之前多少天、会清掉现在这几条」。
 */
data class ImportPreview(
    /** 会写进去几天。 */
    val days: Int,
    /** 会写进去几条真实记录。 */
    val segments: Int,
    /** 预计补出几段空白。 */
    val gapCount: Int,
    /** 预计补出的空白一共多少分钟。 */
    val gapMinutes: Int,
    val firstDay: String,
    val lastDay: String,
    /** 需要新建的事项名（App 里没有的那几个）。 */
    val toCreate: List<String>,
    /**
     * 会**顺带新建**的分类名。
     *
     * 非空只可能出现在一种场景：导入的是**另一台设备导出的备份**，里面有用户自建的分类。
     * 内置的旧版文件里永远为空（它的事项全挂在种子分类下）。
     *
     * 界面要把这几个名字显示出来 —— 用户有权在按下去之前知道「会多出一个分类」。
     */
    val toCreateCategories: List<String>,
    /**
     * **字典里彻底找不到**的事项名。
     *
     * 非空 = 拦下整包导入。这时候正确的做法是去 `_import/map_items.py` 修映射、
     * 重新生成 JSON，而不是让 App 硬塞一条不知道该挂到哪的记录。
     */
    val hardMissingItems: List<String>,
    /** 认不出的标签名。**不拦导入** —— 只是这几段会少一个标签。 */
    val missingTags: List<String>,
    /** App 现在有几条记录 —— 导入会把它们清掉。 */
    val existingRecords: Int,
)
