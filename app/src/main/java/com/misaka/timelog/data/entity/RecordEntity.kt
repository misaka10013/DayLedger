package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条记录的来源。
 *
 * ⚠️ 存的是**名字**不是序号（Room 对 enum 的默认行为就是存 name）——
 * 存 ordinal 的话，将来往中间插一个取值，所有历史数据的含义就全错位了。
 */
enum class RecordSource {
    /** 用户手动记的。 */
    MANUAL,

    /** 模板自动铺的。 */
    TEMPLATE,

    /**
     * ★★ 「▨ 未记录」。
     *
     * 这是**一等公民**，不是「缺失」。所以空白在数据里是一条**真实存在的记录**，
     * 有自己的 id、有自己的起止分钟，也参与统计（算进 gap）。
     *
     * 它也是**补记的唯一入口** —— 用户点时间线上的一段 ▨ 未记录 → 补记一件事。
     */
    GAP,

    /** 跨天接续出来的：次日 00:00 自动续上同一件事项，缝为 0。 */
    CARRY,
}

/**
 * 时间日志的一条记录 —— 全库最重要的一张表。
 *
 * ## 不变式（一天恒 = 1440 分钟）
 *
 * 同一天的记录按 [startMin] 排序后必须**首尾相接**，且完整覆盖 `[0, cut]`：
 * - `cut` = 这一天「该铺到哪」→ 今天 = 此刻｜过去 = 1440｜未来 = 不铺
 * - 相邻两条：前一条的 [endMin] **必须等于**后一条的 [startMin]
 * - 首条 [startMin] 恒为 0
 *
 * 由 `TimelineRepository.ensureDense()` 独家维护（**生产代码里唯一允许写这张表的地方**）。
 *
 * ## ⚠️ 为什么 [itemId] 不建外键
 *
 * 三个理由：
 * 1. 这张表数据量最大，外键检查有持续开销。
 * 2. 删事项的流程不是「简单置空」—— 它要同时改 `source`、清标签、打 `noTemplate`。
 *    用 `SET_NULL` 会自动把记录弄成「itemId 为空但 source 还是 manual」这种**说不通的状态**。
 * 3. 历史记录本来就**不要求**事项还存在（原型的做法也是这样）。
 *
 * 所以引用完整性由 `TimelineRepository` 负责，不靠数据库。
 */
@Entity(
    tableName = "records",
    indices = [
        Index("day"),
        Index(value = ["day", "startMin"]),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,

    /** 哪一天，`'2026-10-14'` 形式。 */
    val day: String,

    /** 当天第几分钟开始，`0..1440`。 */
    val startMin: Int,

    /**
     * 当天第几分钟结束，`0..1440`。
     *
     * **`null` = 这条还在进行中** —— 这个状态**只有「今天」可能存在**
     * （过去已经过完、未来还没到，都不存在「从现在开始记」）。
     */
    val endMin: Int?,

    /** 挂靠的事项。`source == GAP` 时恒为 `null`。 */
    val itemId: String?,

    /**
     * ★ **「当时」分类快照**。
     *
     * ⚠️ 统计**不按它算** —— 统计按事项**当前**归属的分类算（v1.11 裁定：
     * 改了归属，历史一起重算）。
     *
     * 保留它只是**为将来留一手**：若以后要加「按当时口径看历史」的开关，
     * 数据现成、不用回补。代价约 20 字节/条，10 年也就 100KB。
     */
    val categoryIdSnap: String?,

    val source: RecordSource,

    /**
     * 跨天切成多段时，用同一个 chainId 把这些段串起来。
     *
     * 场景：昨晚 19:39 开始「看书」，跨过 24:00 到今天 —— 两天各有一条记录，
     * 但它们其实是**同一件连续的事**。V1 只存字段、界面不显示；
     * V2 用来做「这件事从昨晚 19:39 起连续 16 小时」。
     */
    val chainId: String?,

    /**
     * ⚠️ **别用模板把这条填回来** 的标记。
     *
     * 用户手动删掉一条模板铺出来的记录后打上它。不加这个的话，
     * 下次 `applyTemplate()` 又会把这段空白按模板填满 —— 用户看着就像「删不掉」。
     */
    val noTemplate: Boolean,

    /** 备注，一行。 */
    val note: String,

    /** 最后修改时间（毫秒）。云端同步做增量用。 */
    val updatedAt: Long,

    /**
     * **软删除**，只给云端同步用。
     *
     * 多端同步时，如果本地真删了，另一台设备一 push 就把这条"复活"了。
     * 所以删除要留墓碑。⚠️ 它**不是**「用户删了记录」的表示 ——
     * 用户删记录是把它 `UPDATE` 成 `source = GAP`，行还在。
     */
    val deleted: Boolean,
)

/**
 * 这条记录在界面上算不算「▨ 未记录」—— ★ **全库唯一判据**。
 *
 * 时间线卡片和统计页都靠它。定义在实体旁边（而不是各写一遍）是因为
 * **这两处必须永远一致**：图上少算一段灰、卡片上不算空白，说到底是同一个判断
 * 分裂成了两份，而它错起来不报错，只会在几个月后变成「数字对不上」。
 *
 * 两个条件任一成立即为空白：
 * 1. 它就是一条空白记录（`source == GAP`）—— 「缝隙可见」那条铁律的落地形态
 * 2. `itemId` 为空 —— 正常记录不该这样，出现了就是异常状态，也只能当空白显示
 *
 * ⚠️ **不能只判 `itemId == null`**：删事项时 `itemId` 会被清空，但那时
 * `source` 也会被一并改成 `GAP`；反过来一条 `itemId` 有值、事项却查不到的记录
 * （孤儿）**不算空白**，它该显示成「（事项已删除）」。
 */
val RecordEntity.isGap: Boolean
    get() = source == RecordSource.GAP || itemId == null
