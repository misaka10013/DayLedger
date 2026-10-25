package com.misaka.timelog.data.repo

import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.Now
import com.misaka.timelog.domain.StatsCalc

/**
 * 统计的**只读**数据源。
 *
 * ## 为什么单独一个仓库，不并进 [TimelineRepository]
 *
 * 因为这两边的职责**正好相反**：
 *
 * | | 时间线仓库 | 统计仓库 |
 * |---|---|---|
 * | 写不写库 | **全库唯一允许写 `records` 的地方** | **一行都不写** |
 * | 脑子里装着 | 密铺、让位、撤销、模板铺写 | 只读、聚合、口径 |
 *
 * 混在一起的话，「统计页到底会不会动我的数据」就变成一个**要读代码才能回答**的问题。
 * 而它应该是个一眼就有答案的问题：**不会**。分开放，这个答案放在类名上就成立了。
 *
 * ## 它只负责一件事：把素材一次捞齐
 *
 * 口径、聚合、平滑全在 [StatsCalc]（纯函数，66 条测试盯着）。
 * 这里做的事只有两件：**一次查完**（别在循环里查库）、**把字典拼成回调**。
 */
class StatsRepository(private val db: TimeLogDatabase) {

    /**
     * 一次把某个区间要用的东西全捞回来。
     *
     * ⚠️ **必须一次查完，不能按列查** —— 年口径有 365 天，一天查一次就是 365 趟，
     * 真机上会变成肉眼可见的卡顿。所以这里拿的是整个区间的记录，在内存里按天分好。
     */
    suspend fun load(from: String, to: String): Snapshot {
        // ⚠️ `byDayRange` 的区间是**半开**的 `[from, to)`，所以右端要 +1 天。
        //    少加这一天的症状是「最后一天的统计永远是空的」，而且不报错。
        val rows = db.recordDao().byDayRange(from, DayKey.plus(to, 1))

        val cats = db.categoryDao().all()
        // ⚠️ 必须走 resolveItemColors —— 事项色可能是「自动」（库里存空串），
        //    直接拿 db.itemDao().all() 会让统计图里那一块变成黑的。
        //    （这条曾经就是 bug：统计页自己查库，绕过了字典仓库的解析。）
        val items = resolveItemColors(
            items = db.itemDao().all(),
            catColorById = cats.associate { it.id to it.colorHex },
        )

        val byId = items.associateBy { it.id }
        val catRank = HashMap<String, Int>(cats.size)
        cats.forEachIndexed { i, c -> catRank[c.id] = i }

        // 堆叠图的**固定色序**就是它：分类次序 → 组内次序
        // （跟选择面板的网格、跟时间线卡片上的颜色完全一致）。
        // `ItemDao.all()` 给的是按 categoryId 字符串排的，那不是网格顺序 —— 所以这里重排一遍。
        val ordered = items.sortedWith(
            compareBy<ItemEntity>(
                { catRank[it.categoryId] ?: Int.MAX_VALUE },
                { it.orderInCat },
                { it.id },
            )
        )

        val colorByName = LinkedHashMap<String, String>(ordered.size)
        ordered.forEach { colorByName[it.name] = it.colorHex }

        return Snapshot(
            recordsByDay = rows.groupBy { it.day },
            nameOf = { id -> byId[id]?.name },
            colorOf = { id -> byId[id]?.colorHex },
            order = ordered.map { it.name },
            colorByName = colorByName,
        )
    }

    /**
     * 一个区间的**全部素材** —— 记录、事项目录、色序，一次给齐。
     *
     * 为什么不直接把 `Map<String, List<RecordEntity>>` 丢给界面自己去拼字典：
     * 那样「形状对不对」就散在界面里了。收在这里，界面只需要
     * `snap.agg(cols, now)` 一句，剩下全在测过的纯函数里。
     */
    class Snapshot(
        /** 日期键 → 那天的记录（已按天分好）。 */
        val recordsByDay: Map<String, List<RecordEntity>>,
        private val nameOf: (String) -> String?,
        private val colorOf: (String) -> String?,
        /** 事项名，按「分类次序 → 组内次序」排好 —— 堆叠图的固定色序就是它。 */
        val order: List<String>,
        private val colorByName: Map<String, String>,
    ) {

        /**
         * 交给 [StatsCalc] 的那个 lambda。
         *
         * ★ 「是不是空白」的判据在 `StatsCalc.info` 里，跟时间线卡片共用同一条
         * （`RecordEntity.isGap`）—— 两边各写一遍的话，迟早出现
         * 「卡片上不是空白、图上算成空白」这种对不上。
         */
        val info: (RecordEntity) -> StatsCalc.Info = { r -> StatsCalc.info(r, nameOf, colorOf) }

        /** 事项名 → 颜色。查不到（事项被删了）退回空白灰。 */
        fun color(name: String): String = colorByName[name] ?: StatsCalc.GAP_COLOR

        /** 口径 → 列 → 聚合。一句话，剩下的全在纯函数里。 */
        fun agg(cols: List<StatsCalc.Column>, now: Now): StatsCalc.Agg =
            StatsCalc.agg(cols, recordsByDay, now, info)
    }
}
