package com.misaka.timelog.domain

/**
 * 从历史记录反推「事项的默认标签」。
 *
 * ## 为什么要这么干
 *
 * 旧版时间日志的标签是**挂在每一条记录上**的（没有「事项预设」这个概念），
 * 所以新版本导入数据时，`record_tags`（11520 条）全进来了，
 * 但 `item_default_tags`（事项 → 默认标签）**没有任何来源可导**，一直是空的。
 *
 * 表现：点开事项的「默认标签」弹窗，一个勾都没有；记「睡觉」也不再自动带「日常必需」。
 *
 * 而真实数据里规律极其干净 —— 同一个事项的记录，标签组合几乎固定：
 *
 * | 事项 | 记录数 | 日常必需 | 能量消耗 |
 * |---|---|---|---|
 * | 睡觉 | 829 | 98% | — |
 * | 打工 | 537 | 99% | 60% |
 *
 * 所以直接从历史记录算回来，比让用户一个个手点准得多。
 *
 * ## 判据是「记录覆盖率」，不是「出现次数」
 *
 * 覆盖率 = `带这个标签的记录数 / 这个事项的总记录数`。
 *
 * ⚠️ 用**出现次数**判会出大错：「刷资讯」有 1196 条记录，
 * 就算某个标签只覆盖 10% 也有 120 次，比「摄影」的全部记录（3 条）还多得多。
 * 次数受事项使用频率影响，覆盖率才是「这个事项的固有属性」。
 *
 * ## 为什么要有「最多 3 个」的上限
 *
 * 覆盖率 ≥50% 时，「线下面基」会同时命中 4 个（96% / 96% / 69% / 64%）——
 * 全部塞成默认标签，用户每次记录都得手动去掉一个，反而更烦。
 * 取覆盖率最高的几个更符合「这是这个事项的典型标签」的语义。
 */
object DefaultTagInfer {

    /** 覆盖率门槛：这个事项的记录里，至少一半带了这个标签。 */
    const val MIN_COVERAGE = 0.5

    /** 每个事项最多给几个默认标签。 */
    const val MAX_PER_ITEM = 3

    /**
     * 少于这么多条记录就不推断。
     *
     * 3 条是个很低的门槛 —— 但「编程」只有 1 条记录、100% 带「高价值投资」，
     * 推出来其实是对的。真正要挡的是**0 条记录**（新建成的事项）。
     * 定成 3 是为了别让一条偶然的记录定义整个事项。
     */
    const val MIN_RECORDS = 3

    /** 某个 (事项, 标签) 组合在历史里出现了多少次。 */
    data class Usage(val itemId: String, val tagId: String, val count: Int)

    /** 反推出来的一条关联。 */
    data class Inferred(val itemId: String, val tagId: String, val coverage: Double)

    /**
     * @param usages          每条 `(事项, 标签)` 在多少条记录上出现过
     * @param recordCountByItem 每个事项总共有多少条记录
     */
    fun infer(
        usages: List<Usage>,
        recordCountByItem: Map<String, Int>,
    ): List<Inferred> {
        // 按事项分组
        val byItem = usages.groupBy { it.itemId }

        val out = ArrayList<Inferred>()
        for ((itemId, list) in byItem) {
            val total = recordCountByItem[itemId] ?: 0
            if (total < MIN_RECORDS) continue

            list.asSequence()
                .map { Inferred(itemId, it.tagId, it.count.toDouble() / total) }
                .filter { it.coverage >= MIN_COVERAGE }
                // ★ 排序必须**确定性**：覆盖率降序 → 次数降序 → tagId 升序。
                //   只按覆盖率排时，两个都 96% 的标签谁在前面取决于 map 的遍历顺序，
                //   同一份数据两次跑可能给出不同的默认标签（迁移不可重跑，这种不稳定很致命）。
                .sortedWith(
                    compareByDescending<Inferred> { it.coverage }
                        .thenByDescending { it.coverage * total } // 等价于次数，但避免再传 context
                        .thenBy { it.tagId }
                )
                .take(MAX_PER_ITEM)
                .forEach { out += it }
        }
        // 输出顺序也定死，方便测试与 diff
        return out.sortedWith(compareBy({ it.itemId }, { it.tagId }))
    }
}
