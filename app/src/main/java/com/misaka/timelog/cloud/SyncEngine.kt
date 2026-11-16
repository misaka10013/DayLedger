package com.misaka.timelog.cloud

import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.isGap
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一次同步的结果 —— 三态，没有第四种。 */
sealed interface SyncOutcome {
    /** 有东西被推上去了（或没东西可推）。 */
    data class Ok(val days: Int, val records: Int, val ms: Long) : SyncOutcome

    /** 没做（开关没开 / 没填 token）—— **不是错误**，不该报红。 */
    data class Skipped(val reason: String) : SyncOutcome

    /** 真失败。 */
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * 把本地记录推到云端 —— 「哪些天变了 → 拼 JSON → 分批发 → 记水位线」。
 *
 * ## 只有它一个地方负责「哪几天要传」
 *
 * 这条是项目里已经吃过 8 次亏的教训：凡声称「跟 X 走」的，必须有唯一入口。
 * 所以判断逻辑全部收在这个类里，UI 只负责「调一下」和「显示结果」。
 *
 * ## 与云端的分工
 *
 * App 是**唯一权威源**（记录只在手机上产生），云端是一份**只读副本**。
 * 所以同步永远是「整份覆盖一天」，不做双向合并、不做冲突解决。
 * 好处是幂等：同一天推一百次和推一次的结果一模一样。
 */
class SyncEngine(
    private val db: TimeLogDatabase,
    private val clock: Clock,
    private val prefs: SyncPrefs,
) {

    suspend fun sync(): SyncOutcome = withContext(Dispatchers.IO) {
        if (!prefs.enabled) return@withContext SyncOutcome.Skipped("同步没开")
        val baseUrl = SyncClient.normalizeBase(prefs.baseUrl)
        if (baseUrl.isBlank()) return@withContext SyncOutcome.Skipped("没填服务器地址")
        val token = prefs.token
        if (token.isBlank()) return@withContext SyncOutcome.Skipped("没填同步 token")

        val startedAt = System.currentTimeMillis()
        val now = clock.now()

        /* ★ 水位线用「开始那一刻」，不是结束那一刻。
           用结束时刻的话，同步期间新写的记录（时间戳落在两者之间）
           会被下一轮的 `updatedAt > watermark` 漏掉 —— 而那种漏是**永久**的：
           除非那天再被改一次，否则再也不会被传上去。 */
        val since = prefs.watermark
        val days = try {
            if (since <= 0L) {
                // 首次：只补最近这段（云上更早的那批本来就是同一份数据）
                db.recordDao().recentDays(FIRST_SYNC_DAYS).filter { it <= now.dayKey }.sorted()
            } else {
                db.recordDao().daysChangedSince(since).filter { it <= now.dayKey }
            }
        } catch (e: Exception) {
            return@withContext SyncOutcome.Failed("读本地记录失败：${e.message}")
        }

        var sentDays = 0
        var sentRecords = 0

        if (days.isNotEmpty()) {
            // 事项 id → 名字（云端 segs 里 `i` 存的是**名字**，与历史数据保持一致）
            val itemName = try {
                db.itemDao().all().associate { it.id to it.name }
            } catch (e: Exception) {
                emptyMap()
            }

            /* 记录 id → 它挂的标签名（云端 `segs[].t` 也是名字）。
               ★ v1.0.2 修：这一段以前**根本没有**，于是上传的每一段都是「裸的」——
                 只有 a/b/i/g，没有 t。云端 2026-08-19 之后那 241 天就是这么裸着的，
                 直到 2026-11 才发现（用户的 AI 说「找不到标签数据」）。
               ★ 一次查完要传的所有天，而不是在 buildDay 里一天查一遍：
                 首次同步 60 天 = 60 次全表扫，没必要。 */
            val tagNames = try {
                val idToName = db.tagDao().all().associate { it.id to it.name }
                groupTagNames(db.tagDao().pairsOfDays(days), idToName)
            } catch (e: Exception) {
                emptyMap()
            }

            // 一天一天拼，按字节预算切批 —— 云端请求体上限约 100 KB，这里给 70 KB 留余量
            val batches = mutableListOf<MutableList<JSONObject>>()
            val lengths = mutableListOf<Int>()
            var cur = mutableListOf<JSONObject>()
            var curLen = 0

            for (day in days) {
                val obj = try {
                    buildDay(day, now, itemName, tagNames)
                } catch (e: Exception) {
                    null
                } ?: continue
                val len = obj.toString().length
                if (cur.isNotEmpty() && (cur.size >= MAX_DAYS_PER_BATCH || curLen + len > MAX_BYTES_PER_BATCH)) {
                    batches.add(cur); lengths.add(curLen)
                    cur = mutableListOf(); curLen = 0
                }
                cur.add(obj)
                curLen += len
            }
            if (cur.isNotEmpty()) { batches.add(cur); lengths.add(curLen) }

            val device = android.os.Build.MODEL.orEmpty()
            for ((i, batch) in batches.withIndex()) {
                val body = JSONObject().apply {
                    put("device", device)
                    put("days", JSONArray(batch as List<Any>))
                    // meta（事项/分类字典）随**每一批**都带：它很小（几 KB），
                    // 而「只在最后一批带」会让中途失败时云端的分类名对不上，
                    // 那种偏差在 AI 那边会表现为「你 rest 花了 8 小时」。
                    put("meta", buildMeta())
                }
                val r = SyncClient.postSync(baseUrl, body.toString(), token)
                if (!r.ok) {
                    // 已经成功了的前几批不回滚 —— 它们是幂等的，下一轮会整体重来
                    return@withContext SyncOutcome.Failed(
                        "第 ${i + 1}/${batches.size} 批失败：${r.message()}" +
                            if (sentDays > 0) "（已同步 $sentDays 天，下次会重传）" else ""
                    )
                }
                sentDays += batch.size
                sentRecords += batch.sumOf { it.optJSONArray("segs")?.length() ?: 0 }
            }
        }

        prefs.watermark = startedAt
        prefs.lastSyncAt = System.currentTimeMillis()
        prefs.lastResult = if (sentDays > 0) "已上传 $sentDays 天 / $sentRecords 段" else "没有变化"

        SyncOutcome.Ok(days = sentDays, records = sentRecords, ms = System.currentTimeMillis() - startedAt)
    }

    /**
     * 拼一天。
     *
     * 返回 null = 这天不用传（未来日 / 没有记录）。
     *
     * ⚠️ **必须保证铺满到 `cut`** —— 云端的守门人会核对这一条，
     *    不满足就是 400。所以这里宁可自己先算一遍，也不要指望「库里的记录一定对」。
     */
    private suspend fun buildDay(
        day: String,
        now: com.misaka.timelog.domain.Now,
        itemName: Map<String, String>,
        tagNames: Map<String, List<String>>,
    ): JSONObject? {
        val cut = Density.cutFor(day, now)
        if (cut <= 0) return null                       // 未来的天一条都不传

        val recs = db.recordDao().byDay(day)            // 已按 startMin 排序、已排除软删
        if (recs.isEmpty()) return null

        val arr = JSONArray()
        var prevEnd = 0
        for (r in recs) {
            val a = r.startMin
            // 进行中的那条（endMin == null）按「铺到 cut」算 —— 与 Density 的口径一致
            val b = r.endMin ?: cut
            if (b <= a) continue
            if (a != prevEnd) {
                // 库里出现了缝隙（理论上不该发生）。补一段空白而不是让它憋成 400 ——
                // 空白是「一等公民」，补上它既满足密铺，也如实表达了「这段没记」。
                if (a > prevEnd) arr.put(gapSeg(prevEnd, a))
            }
            arr.put(segJson(r, a, b, itemName, tagNames))
            prevEnd = b
        }
        if (prevEnd == 0) return null
        if (prevEnd < cut) arr.put(gapSeg(prevEnd, cut))   // 末尾没铺满 → 补空白

        val o = JSONObject()
        o.put("day", day)
        o.put("segs", arr)
        return o
    }

    private fun segJson(
        r: RecordEntity,
        a: Int,
        b: Int,
        itemName: Map<String, String>,
        tagNames: Map<String, List<String>>,
    ): JSONObject =
        JSONObject().apply {
            put("a", a)
            put("b", b)
            if (r.isGap) {
                put("g", 1)
            } else {
                /* 事项被删了（孤儿记录）时不能传空 —— 云端会显示成「（未命名）」，
                   而 App 里明明写着「（事项已删除）」。两处名字不一样，正是
                   最让人不信任数据的那种偏差。 */
                put("i", itemName[r.itemId] ?: "（事项已删除）")

                /* 标签。云端 `routeTlSync` 每段最多收 12 个（超了它自己会 slice），
                   这里先截断，免得「App 里挂着 15 个、云端只有 12 个」这种静默偏差。 */
                val tags = tagNames[r.id]
                if (!tags.isNullOrEmpty()) {
                    val arr = JSONArray()
                    tags.take(12).forEach { arr.put(it) }
                    put("t", arr)
                }

                /* 备注。⚠️ 云端这一版的 `routeTlSync` 字段白名单里**没有** n，
                   传上去会被丢掉 —— 但照样传：
                     1. 不传的话将来云端补上白名单，还得再改一次 App；
                     2. 云端的 `checkDaySegs` 只校验 a/b/g，多余的键不会触发 400。 */
                val note = r.note.trim()
                if (note.isNotEmpty()) put("n", note)
            }
        }

    private fun gapSeg(a: Int, b: Int): JSONObject = JSONObject().apply {
        put("a", a)
        put("b", b)
        put("g", 1)
    }

    /** 事项与分类字典 —— 云端靠它把 `categoryId` 翻成中文分类名。 */
    private suspend fun buildMeta(): JSONObject = JSONObject().apply {
        put("items", JSONArray().apply {
            db.itemDao().all().forEach { it ->
                put(JSONObject().apply {
                    put("name", it.name)
                    put("categoryId", it.categoryId)
                })
            }
        })
        put("categories", JSONArray().apply {
            db.categoryDao().all().forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                })
            }
        })
    }

    private companion object {
        /** 首次同步补最近多少天。云端已有的那 652 天不用重传。 */
        const val FIRST_SYNC_DAYS = 60

        /** 单批最多几天（云端上限 60）。 */
        const val MAX_DAYS_PER_BATCH = 60

        /** 单批 days 部分最多多少字符（云端请求体上限约 100 KB，这里留余量）。 */
        const val MAX_BYTES_PER_BATCH = 70_000
    }
}

/**
 * 把「记录↔标签」的关联行整理成 `记录 id → 标签名列表`。
 *
 * ## 为什么单独拎出来做顶层函数
 *
 * 这段一旦返回空 Map，上传就会**静默**退化成「每一段都不带标签」——
 * 不报错、不崩溃、App 里看着一切正常，只有云端的 AI 会在两个月后说「找不到标签数据」。
 * 2026-08-19 到 2026-11-16 那 241 天就是这么丢的。
 *
 * 抽成纯函数之后可以单测：给定关联行和 id→名字映射，必须得到正确的分组。
 * `it` 不用问「为什么这也要测」，问的是「这个 bug 复发时谁来报错」。
 *
 * ## 两条静默失败的防线
 *
 * 1. `idToName[tagId]` 查不到（标签被删了但关联还在，理论上 CASCADE 会清掉）
 *    → 过滤掉 null，而不是塞一个空字符串上去 —— 空标签名会让云端多出一个「」标签。
 * 2. 一个标签都没有的记录**不进 Map**（`filterValues`），
 *    这样 `segJson` 里 `tagNames[r.id]` 为 null 就自然跳过 `t` 字段，
 *    不会写出一堆 `"t": []` 空数组把报文撑大。
 */
internal fun groupTagNames(
    pairs: List<com.misaka.timelog.data.dao.RecordTagPair>,
    idToName: Map<String, String>,
): Map<String, List<String>> =
    pairs
        .groupBy({ it.recordId }, { idToName[it.tagId] })
        .mapValues { (_, names) -> names.filterNotNull().filter { it.isNotBlank() } }
        .filterValues { it.isNotEmpty() }
