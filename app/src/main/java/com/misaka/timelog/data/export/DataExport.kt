package com.misaka.timelog.data.export

import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.isGap
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.MINUTES_PER_DAY
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * ★ 把本地数据导出成**两种**东西：
 *
 * | 产物 | 格式 | 给谁 |
 * |---|---|---|
 * | [backupJson] | 和内置导入文件（`legacy_timelog.json`）**同一套 schema** | 换手机 / 重装 / 换签名版本时导回来 |
 * | [aiMarkdown] | Markdown（摘要表 + 逐天明细） | 直接拖给 AI 读 |
 *
 * ## 为什么必须两份，而不是「一份 JSON 兼用」
 *
 * JSON 是给**机器**的：字段短、结构死、一个字节都不能少（它要能被 `LegacyImport` 读回去）。
 * 而 AI 读 JSON 有两个实际麻烦：一是 token 消耗是纯文本的三倍左右（引号、括号、重复的键名），
 * 二是「这一天 1440 分钟是怎么铺的」在 JSON 里要靠自己还原。
 *
 * Markdown 那一份把**统计摘要放在最前面**，AI 一眼就能拿到全局，再往下才是明细。
 *
 * ## 职责边界（和 `LegacyImport` 对称）
 *
 * | 它管 | 它不管 |
 * |---|---|
 * | 从库里把素材一次捞齐 | 写文件（`ExportViewModel` / 系统文件选择器管） |
 * | 把 id 翻成**名字** | 分享（`Intent` 在界面层拼） |
 * | 排版 | 任何写操作（这个类**一个字都不写库**） |
 *
 * ⚠️ **为什么导出写名字而不是 id**：因为导入是按名字找事项的
 * （`LegacyImport.plan()` 里 `itemIdByName`）。存 id 的话，换一台设备
 * id 全变，整份备份就废了 —— 而名字是人给的，跨设备天然稳定。
 *
 * ## ★ 「未记录」在这两份里的待遇**故意不同**
 *
 * - **JSON 里不写空白段**。写进去的话，导入时 `itemIdByName["未记录"]` 查不到，
 *   会被当成「找不到的事项」而**整包拦下**。而且空白本来就由仓库自己补
 *   （`replaceAllRecords` 一路走一路在缝里补），不写反而更准。
 * - **Markdown 里必须写**。对 AI 来说「这段时间没记录」是一条**信息**，
 *   漏掉它 AI 会把相邻两段当成连续的一整块，得出完全错误的结论。
 */
class DataExport(
    private val db: TimeLogDatabase,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // ================================================================ 捞数据

    /**
     * 把整库读成一个不依赖数据库的 [ExportBundle]。
     *
     * ⚠️ **必须一次性捞齐，不要在渲染时回调数据库**：
     * 渲染函数要能在单元测试里直接跑（不给它库、不给它 Context），
     * 否则「导出的字对不对」就只能靠真机肉眼看。
     */
    suspend fun collect(): ExportBundle {
        val records = db.recordDao().allAlive()
        val items = db.itemDao().all()
        val categories = db.categoryDao().all()
        val tags = db.tagDao().all()
        val refs = db.tagDao().allRefs()

        val itemById = items.associateBy { it.id }
        val categoryById = categories.associateBy { it.id }
        val tagNameById = tags.associate { it.id to it.name }
        val tagIdsByRecord = refs.groupBy({ it.recordId }, { it.tagId })

        val byDay = records.groupBy { it.day }

        // 日期用 ISO 存，字典序恰好等于时间序 —— 所以直接 sorted() 就行
        val days = byDay.keys.sorted().map { day ->
            ExportDay(
                day = day,
                segments = byDay.getValue(day)
                    .sortedBy { it.startMin }
                    .map { r ->
                        r.toExportSegment(
                            itemName = r.itemId?.let { itemById[it]?.name },
                            categoryName = r.itemId?.let { id ->
                                itemById[id]?.let { item -> categoryById[item.categoryId]?.name }
                            },
                            tagNames = tagIdsByRecord[r.id].orEmpty().mapNotNull { tagNameById[it] },
                        )
                    },
            )
        }

        val allSegs = days.flatMap { it.segments }
        val realSegs = allSegs.filter { !it.isGap }

        return ExportBundle(
            generatedAtMillis = clock.now().epochMillis,
            zoneId = zone.id,
            days = days,
            categories = categories.map {
                ExportCategory(id = it.id, name = it.name, colorHex = it.colorHex, position = it.position)
            },
            items = items.map { item ->
                ExportItem(
                    id = item.id,
                    name = item.name,
                    categoryId = item.categoryId,
                    categoryName = categoryById[item.categoryId]?.name,
                    colorHex = item.colorHex,
                    orderInCat = item.orderInCat,
                )
            },
            tags = tags.map {
                ExportTag(
                    id = it.id,
                    name = it.name,
                    bucketId = it.bucketId,
                    colorHex = it.colorHex,
                    description = it.description,
                )
            },
            stats = ExportStats(
                days = days.size,
                recordedSegments = realSegs.size,
                recordedMinutes = realSegs.sumOf { it.endMin - it.startMin },
                gapSegments = allSegs.size - realSegs.size,
                gapMinutes = allSegs.filter { it.isGap }.sumOf { it.endMin - it.startMin },
            ),
        )
    }

    /**
     * 一条记录 → 一段可导出的记录。
     *
     * 三处要小心：
     * 1. `endMin == null`（**正在跑的那一段**）—— JSON 里 `b` 必须是整数，
     *    所以封口到当天结束。反正导入会跳过今天，这段只影响「给 AI 看」的那份。
     * 2. 事项查不到 = 孤儿记录（事项被删过）。它**不算空白**
     *    （`isGap` 的注释专门说过），所以 `itemName` 留空、靠 [ExportSegment.isOrphan] 标出来。
     * 3. 标签 id 查不到 = 标签被删但引用没清干净。**直接丢掉**，不要编一个假名字出来。
     */
    private fun RecordEntity.toExportSegment(
        itemName: String?,
        categoryName: String?,
        tagNames: List<String>,
    ): ExportSegment = ExportSegment(
        startMin = startMin,
        endMin = endMin ?: MINUTES_PER_DAY,
        itemName = itemName,
        categoryName = categoryName,
        note = note,
        tagNames = tagNames,
        chainId = chainId,
        isGap = isGap,
    )

    // ================================================================ 备份 JSON

    /**
     * 生成**能被自己读回去**的完整备份。
     *
     * 字段和内置的 `legacy_timelog.json` 完全对齐（见 `LegacyImport.parse`）：
     *
     * ```
     * { version, generatedAt, source, stats,
     *   categories[], items[], tags[],          ← 导入器目前不读，留给人和将来的自己
     *   newItems[{name, categoryId}],           ← ★ 决定「重装后导不导得回来」
     *   days[{d, s:[{a,b,i,n?,t?,c?}]}] }
     * ```
     *
     * ## ★★ `newItems` 为什么必须列**全部**事项（而不是只列「新增的」）
     *
     * 内置文件里 `newItems` 只有 5 个，因为它是「往一个已经有 58 个种子事项的 App 里导入」。
     * 但备份的场景正相反 —— **目标 App 是刚装的空壳，只有种子**。
     * 而用户的真实库里 39 个事项有改名的、有从别的分类挪过来的、有自建的，
     * 名字对不上种子就一个都解析不出来。
     *
     * `LegacyImport.ensureItems()` 是幂等的（`if (!alive.add(name)) return`），
     * 所以**全列出来不会多建**，而同名的种子事项会被跳过 —— 这是最稳的写法。
     *
     * `categoryName` 是新加的字段（`LegacyImport` 用 `optString` 读，老文件没有也不会崩）：
     * 光有 `categoryId` 不够，因为用户自建过分类（他真实库里就有一个 UUID 的「学习」），
     * 那个 id 在重装后的库里根本不存在，只能靠名字把分类重建出来。
     */
    fun backupJson(bundle: ExportBundle): String {
        val root = JSONObject()

        root.put("version", EXPORT_VERSION)
        root.put("generatedAt", isoDateTime(bundle.generatedAtMillis, bundle.zoneId))
        root.put("source", SOURCE_TAG)

        // ---- 概况：给人看的，导入器不读
        root.put(
            "stats",
            JSONObject().apply {
                put("days", bundle.stats.days)
                put("recordedSegments", bundle.stats.recordedSegments)
                put("recordedMinutes", bundle.stats.recordedMinutes)
                put("gapSegments", bundle.stats.gapSegments)
                put("gapMinutes", bundle.stats.gapMinutes)
                put("items", bundle.items.size)
                put("categories", bundle.categories.size)
                put("tags", bundle.tags.size)
                put("firstDay", bundle.days.firstOrNull()?.day ?: "")
                put("lastDay", bundle.days.lastOrNull()?.day ?: "")
            },
        )

        // ---- 字典：导入器目前不读，但缺了以后想「完整还原界面配置」就没素材了
        root.put("categories", JSONArray().apply {
            bundle.categories.forEach { c ->
                put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("colorHex", c.colorHex)
                        put("position", c.position)
                    },
                )
            }
        })

        root.put("items", JSONArray().apply {
            bundle.items.forEach { i ->
                put(
                    JSONObject().apply {
                        put("id", i.id)
                        put("name", i.name)
                        put("categoryId", i.categoryId)
                        if (i.categoryName != null) put("categoryName", i.categoryName)
                        put("colorHex", i.colorHex)
                        put("orderInCat", i.orderInCat)
                    },
                )
            }
        })

        root.put("tags", JSONArray().apply {
            bundle.tags.forEach { t ->
                put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("name", t.name)
                        put("bucketId", t.bucketId)
                        put("colorHex", t.colorHex)
                        put("description", t.description)
                    },
                )
            }
        })

        // ---- ★ 关键：记录里用到的事项全列一遍，让导入器能把它们建出来
        val usedItemNames = bundle.days
            .flatMap { d -> d.segments.asSequence() }
            .filter { !it.isGap && it.itemName != null }
            .map { it.itemName!! }
            .toSortedSet()

        root.put("newItems", JSONArray().apply {
            val itemByName = bundle.items.associateBy { it.name }
            val catColorById = bundle.categories.associate { it.id to it.colorHex }
            usedItemNames.forEach { name ->
                val item = itemByName[name]
                put(
                    JSONObject().apply {
                        put("name", name)
                        if (item != null) {
                            put("categoryId", item.categoryId)
                            if (item.categoryName != null) put("categoryName", item.categoryName)
                            // 颜色也带上：分类可能是用户自建的（真实库里就有一个 UUID 的「学习」），
                            // 重装后库里没有它，导入时要**连颜色一起重建**才不会变成一片灰。
                            catColorById[item.categoryId]?.let { put("categoryColorHex", it) }
                        }
                    },
                )
            }
        })

        // ---- 记录：**只写真段**，空白交给导入时的 `replaceAllRecords` 自己补
        //
        // ⚠️ 条件里那个 `itemName != null` 不是冗余：它挡的是**孤儿记录**
        //    （事项被删过、记录还挂着那个已消失的 id）。孤儿不是空白
        //    （`isGap` 的注释专门区分过这两种），但它**也没有名字可写**。
        //    漏掉这个条件，导出的段就会缺 `"i"` 字段，而导入器那边是
        //    `s.getString("i")`（不是 `optString`）—— 一读就抛 JSONException，
        //    整份备份直接废掉。跳过它更好：那天会由仓库补成一段空白，
        //    而「事项都没了」的记录本来也没什么可还原的。
        root.put("days", JSONArray().apply {
            bundle.days.forEach { d ->
                put(
                    JSONObject().apply {
                        put("d", d.day)
                        put("s", JSONArray().apply {
                            d.segments.filter { !it.isGap && it.itemName != null }.forEach { s ->
                                put(
                                    JSONObject().apply {
                                        put("a", s.startMin)
                                        put("b", s.endMin)
                                        s.itemName?.let { put("i", it) }
                                        if (s.note.isNotEmpty()) put("n", s.note)
                                        if (s.tagNames.isNotEmpty()) {
                                            put("t", JSONArray(s.tagNames))
                                        }
                                        s.chainId?.let { put("c", it) }
                                    },
                                )
                            }
                        })
                    },
                )
            }
        })

        // 紧凑输出：这份文件可能有几百 KB，缩进会让它翻倍，而它主要是给机器读的
        return root.toString()
    }

    // ================================================================ AI 用 Markdown

    /**
     * 生成给 AI 读的 Markdown。
     *
     * ## 排版的三条取舍
     *
     * 1. **摘要在前、明细在后**。AI 读长文时前几千 token 权重最高，
     *    把「按分类 / 按事项」的汇总放在最前面，它回答「我时间都花哪了」这类问题时
     *    根本不需要往下翻。
     * 2. **明细用紧凑行，不用表格**。652 天 × 15 段要是画成表格，
     *    光是 `| ` 和 ` |` 就要多吃三成 token，而 AI 读 `00:00-08:12 睡觉 492m`
     *    这种行完全不费劲。
     * 3. **每天开头写死「合计 1440 分」**。这是本 App 的铁律（一天恒 1440），
     *    写出来 AI 才知道「空白是记录的一部分」，而不是数据有缺口。
     *
     * ⚠️ 日期全量导出，不做「只导最近 N 天」的裁剪：AI 拿到全量才能做跨月对比，
     * 而裁剪是用户拿到文件之后自己截的事。
     */
    fun aiMarkdown(bundle: ExportBundle): String {
        val sb = StringBuilder(1 shl 18) // 预分配 256KB，省掉十几次扩容

        val allSegs = bundle.days.flatMap { it.segments }
        val realSegs = allSegs.filter { !it.isGap }
        val firstDay = bundle.days.firstOrNull()?.day ?: "（无数据）"
        val lastDay = bundle.days.lastOrNull()?.day ?: "（无数据）"
        val recorded = bundle.stats.recordedMinutes

        // ---------------------------------------------------------- 抬头
        sb.appendLine("# 一天账本 · 时间记录")
        sb.appendLine()
        sb.appendLine("> 这是「一天账本」App 导出的本地记录，可以直接交给 AI 分析。")
        sb.appendLine("> **一天恒 1440 分钟**：已记录和「未记录」加起来必定刚好 1440 分钟，没有缺口。")
        sb.appendLine()
        sb.appendLine("## 概况")
        sb.appendLine()
        sb.appendLine("- 导出时间：${isoDateTime(bundle.generatedAtMillis, bundle.zoneId)}")
        sb.appendLine("- 覆盖区间：$firstDay ~ $lastDay（共 ${bundle.stats.days} 天）")
        sb.appendLine("- 已记录：${bundle.stats.recordedSegments} 段 / ${hoursText(recorded)}")
        sb.appendLine("- 未记录：${bundle.stats.gapSegments} 段 / ${hoursText(bundle.stats.gapMinutes)}")
        sb.appendLine("- 事项 ${bundle.items.size} 个 · 分类 ${bundle.categories.size} 个 · 标签 ${bundle.tags.size} 个")
        sb.appendLine()

        // ---------------------------------------------------------- 按分类
        // 分母用「已记录分钟」而不是「天数×1440」：
        // 后者会被 136 个整天空白日稀释，看起来每件事都不值一提，反而不真实。
        val byCategory = realSegs
            .filter { it.categoryName != null }
            .groupBy { it.categoryName!! }
            .map { (name, segs) -> name to segs.sumOf { it.endMin - it.startMin } }
            .sortedByDescending { it.second }

        if (byCategory.isNotEmpty()) {
            sb.appendLine("## 按分类汇总")
            sb.appendLine()
            sb.appendLine("| 分类 | 总时长 | 占已记录 |")
            sb.appendLine("|---|---|---|")
            byCategory.forEach { (name, minutes) ->
                sb.appendLine("| $name | ${hoursText(minutes)} | ${pct(minutes, recorded)} |")
            }
            if (bundle.stats.gapMinutes > 0) {
                sb.appendLine("| （未记录） | ${hoursText(bundle.stats.gapMinutes)} | — |")
            }
            sb.appendLine()
        }

        // ---------------------------------------------------------- 按事项
        val byItem = realSegs
            .filter { it.itemName != null }
            .groupBy { it.itemName!! }
            .map { (name, segs) ->
                Triple(name, segs.sumOf { it.endMin - it.startMin }, segs.first().categoryName ?: "—")
            }
            .sortedByDescending { it.second }

        if (byItem.isNotEmpty()) {
            sb.appendLine("## 按事项汇总（全部）")
            sb.appendLine()
            sb.appendLine("| 事项 | 分类 | 总时长 | 占已记录 |")
            sb.appendLine("|---|---|---|---|")
            byItem.forEach { (name, minutes, cat) ->
                sb.appendLine("| $name | $cat | ${hoursText(minutes)} | ${pct(minutes, recorded)} |")
            }
            sb.appendLine()
        }

        // ---------------------------------------------------------- 逐天明细
        sb.appendLine("## 逐天明细")
        sb.appendLine()
        sb.appendLine("格式：`起-止 事项 分钟数`；`▨` 开头的是**未记录**（那段时间没记，不是数据缺失）。")
        sb.appendLine()

        bundle.days.forEach { d ->
            sb.appendLine("### ${d.day} ${weekdayCn(d.day)}")
            d.segments.forEach { s ->
                sb.append(hhmm(s.startMin))
                sb.append('-')
                sb.append(hhmm(s.endMin))
                sb.append(' ')
                if (s.isGap) {
                    sb.append("▨ 未记录")
                } else {
                    sb.append(s.itemName ?: "（事项已删除）")
                }
                sb.append(' ')
                sb.append(s.endMin - s.startMin)
                sb.append('m')

                if (s.note.isNotEmpty()) sb.append(" · 备注：").append(s.note)
                if (s.tagNames.isNotEmpty()) sb.append(" · 标签：").append(s.tagNames.joinToString("、"))
                sb.appendLine()
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    // ================================================================ 小工具

    private fun hhmm(minute: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

    private fun hoursText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m 分"
            m == 0 -> "$h 小时"
            else -> "$h 小时 $m 分"
        }
    }

    private fun pct(part: Int, total: Int): String {
        if (total <= 0) return "—"
        return String.format(Locale.ROOT, "%.1f%%", part * 100.0 / total)
    }

    private fun isoDateTime(epochMillis: Long, zoneId: String): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zoneId)).toString()

    /** `2026-10-19` → `周一`。日期解析不了就返回空串，**绝不往外抛异常**。 */
    private fun weekdayCn(day: String): String = runCatching {
        when (java.time.LocalDate.parse(day).dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
    }.getOrDefault("")

    companion object {
        /** 备份文件里的 schema 版本。往后加字段要往上加，导入器按 `opt*` 读，不会崩。 */
        const val EXPORT_VERSION = 1

        private const val SOURCE_TAG = "一天账本 DayLedger · 本机导出"
    }
}

// ==================================================================== 中间表示

/**
 * 整份导出素材 —— 已经和数据库无关了。
 *
 * ⚠️ 故意**不带 id 之外的东西**（不带 RecordEntity）：实体会随 Room 迁移变化，
 * 而这个类要稳定到「两年后还能拿它生成同样格式的文件」。
 */
data class ExportBundle(
    val generatedAtMillis: Long,
    val zoneId: String,
    /** 按日期升序。 */
    val days: List<ExportDay>,
    val categories: List<ExportCategory>,
    val items: List<ExportItem>,
    val tags: List<ExportTag>,
    val stats: ExportStats,
)

data class ExportDay(
    val day: String,
    /** 按起点升序，**包含空白段**（渲染 Markdown 要用，写 JSON 时再过滤掉）。 */
    val segments: List<ExportSegment>,
)

data class ExportSegment(
    val startMin: Int,
    val endMin: Int,
    /** 事项名。空白段和孤儿记录都是 `null`。 */
    val itemName: String?,
    val categoryName: String?,
    val note: String,
    val tagNames: List<String>,
    val chainId: String?,
    val isGap: Boolean,
) {
    /** 事项被删过、但记录还在 —— 它不算空白，也不该被当成空白统计。 */
    val isOrphan: Boolean get() = !isGap && itemName == null
}

data class ExportCategory(
    val id: String,
    val name: String,
    val colorHex: String,
    val position: Int,
)

data class ExportItem(
    val id: String,
    val name: String,
    val categoryId: String,
    val categoryName: String?,
    val colorHex: String,
    val orderInCat: Int,
)

data class ExportTag(
    val id: String,
    val name: String,
    val bucketId: String,
    val colorHex: String,
    val description: String,
)

data class ExportStats(
    val days: Int,
    val recordedSegments: Int,
    val recordedMinutes: Int,
    val gapSegments: Int,
    val gapMinutes: Int,
)
