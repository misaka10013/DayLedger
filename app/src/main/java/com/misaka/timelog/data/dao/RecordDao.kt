package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.misaka.timelog.data.entity.RecordEntity

/**
 * 记录表的访问口。
 *
 * ⚠️ **不要绕过 `TimelineRepository` 直接调这里的写方法** ——
 * 密铺不变式（一天恒 = 1440 分钟）由 Repository 独家维护，
 * 任何一处直接 `insert` / `delete` 都可能在那里留下一个真正的「空隙」，
 * 而空隙是这个设计里**不该存在**的东西（空隙一律具象成一条 GAP 记录）。
 *
 * 这里的方法本身不做校验，它们只是 SQL 的搬运工。
 */
@Dao
interface RecordDao {

    /** 取某一天的全部有效记录，按开始时间排好。 */
    @Query("SELECT * FROM records WHERE day = :day AND deleted = 0 ORDER BY startMin")
    suspend fun byDay(day: String): List<RecordEntity>

    /** 按时间区间取（统计、导出用）。范围是 `[from, to)`。 */
    @Query(
        "SELECT * FROM records WHERE day >= :from AND day < :to AND deleted = 0 " +
            "ORDER BY day, startMin"
    )
    suspend fun byDayRange(from: String, to: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun byId(id: String): RecordEntity?

    /**
     * 取某天「正在进行」的那一条（`endMin IS NULL`）。
     *
     * ⚠️ **这个状态只有「今天」可能存在**。
     * 过去的一天早就过完了、未来的一天还没到，都不存在「从现在开始记」——
     * 第 19 轮那个「翻到昨天点事项，昨天凭空长出一条 24 小时记录」的 bug 就出在这上面。
     * 所以调用方必须先确认「这天就是今天」。
     */
    @Query("SELECT * FROM records WHERE day = :day AND endMin IS NULL AND deleted = 0 LIMIT 1")
    suspend fun liveOn(day: String): RecordEntity?

    /**
     * 全库**最近一条**「还在进行中」的记录 —— ★ 跨天接续（`CARRY`）的依据。
     *
     * ## ⚠️ 跟 [liveOn] 不是一回事
     *
     * [liveOn] 问的是「**某一天**有没有在进行的」，这个是问「**整个库里**有没有一条
     * 跨了天还没结算的」。前者的调用方拿着一份「我就在看今天」的上下文，
     * 后者什么都不假定 —— 它翻遍所有天，找出那条「忘了按结束」的记录。
     *
     * 正常情况下「进行中」只可能出现在今天（[liveOn] 的文档里写着这条），
     * 所以**查到一条不属于今天的，就说明跨天了还没结算** —— 那正是要接续的东西。
     *
     * ⚠️ 排序必须是 `day DESC, startMin DESC`：一天里理论上只有一条 live，
     * 但真出现两条（历史脏数据）时，取**最晚那条**，接续才不会接错人。
     */
    @Query(
        "SELECT * FROM records WHERE endMin IS NULL AND deleted = 0 " +
            "ORDER BY day DESC, startMin DESC LIMIT 1"
    )
    suspend fun lastLive(): RecordEntity?

    /** 有哪些天已经有数据了。跨天补切时要扫它。 */
    @Query("SELECT DISTINCT day FROM records WHERE deleted = 0 ORDER BY day")
    suspend fun allDays(): List<String>

    /**
     * `[from, to)` 之间**有真实记录**的天 —— 月历上那些小圆点。
     *
     * ⚠️ 条件里的 `source <> 'GAP'` 不能省：`ensureDense()` 会把每一天都补成密铺，
     * 所以「这天有没有记录」如果只按「有没有行」算，**每一天都算有** ——
     * 那 655 天全部点亮，等于没有标记。
     *
     * ⚠️ 范围查而不是全表 `GROUP BY day`：这条会随翻月反复调用，
     * 全表扫一遍在两年数据上是几十毫秒，翻月时会看得出来卡一下。
     */
    @Query(
        "SELECT DISTINCT day FROM records " +
            "WHERE day >= :from AND day < :to AND deleted = 0 AND source <> 'GAP' ORDER BY day"
    )
    suspend fun daysWithContent(from: String, to: String): List<String>

    /**
     * 这个区间里**有「未记录」空白块**的天（月历上的小黄点）。
     *
     * 判据就是 `source = 'GAP'` —— 那本身就是一条真实的空白记录，
     * 不需要再去算「0 到 1440 有没有断」。
     *
     * ⚠️ **只查过去和今天**：未来的天根本没有记录，不会命中；
     * 但今天如果只是「现在还没记」，也不会命中（`ensureDense` 只铺到此刻，
     * 铺的都是真实记录）。所以不用额外加时间条件。
     */
    @Query(
        "SELECT DISTINCT day FROM records " +
            "WHERE day >= :from AND day < :to AND deleted = 0 AND source = 'GAP' ORDER BY day"
    )
    suspend fun daysWithGap(from: String, to: String): List<String>

    /* ---------------------------------------------------------
     * 云同步用（M4-B，2026-10-17 加）
     * ---------------------------------------------------------
     * ⚠️ 两条都只读，**表结构一个字段都没加** —— 靠的是 `updatedAt`
     *   和 `deleted` 这两个字段本来就为同步预留好了（见 RecordEntity 的注释）。
     * --------------------------------------------------------- */

    /**
     * 自 `since`（毫秒时间戳）之后被改过的天 —— ★ 增量同步的唯一依据。
     *
     * 「改过」包括：新增、改起止、补记（GAP→正常）、软删除。
     * 所以用它算「哪几天需要重传」天然覆盖全部写路径，不用在每个写方法后面挂标记
     * （挂标记就得改十几个地方，漏一个那种改动就永远同步不上去）。
     */
    @Query("SELECT DISTINCT day FROM records WHERE updatedAt > :since ORDER BY day")
    suspend fun daysChangedSince(since: Long): List<String>

    /**
     * 最近 `n` 天里有数据的天（新的在前）—— 首次同步用。
     *
     * ⚠️ 首次**不是**全量重传：云上那 652 天本来就是同一份数据，
     *    全量推一遍要 11 批、几十秒，纯属白烧流量。只补最近这段就够，
     *    更早的如果真改过，`daysChangedSince` 到时会抓到。
     */
    @Query("SELECT DISTINCT day FROM records WHERE deleted = 0 ORDER BY day DESC LIMIT :n")
    suspend fun recentDays(n: Int): List<String>

    @Query("SELECT COUNT(*) FROM records WHERE day = :day AND deleted = 0")
    suspend fun countOn(day: String): Int

    /** 全库还剩几条记录（不含软删的）。导入前的「会清掉多少」用它报数。 */
    @Query("SELECT COUNT(*) FROM records WHERE deleted = 0")
    suspend fun countAll(): Int

    /**
     * 全库记录，**不做任何排序也不过滤** —— 只给「整库替换前拍快照」用。
     *
     * ⚠️ 别拿它当日常读取口：这个 App 所有的时间线/统计读取都是**按天**的，
     * 一次把几个月的记录全捞出来只会在真机上变成肉眼可见的卡顿。
     * 它的唯一正当用途是备份，而备份一年也就跑一两次。
     */
    @Query("SELECT * FROM records WHERE deleted = 0")
    suspend fun allAlive(): List<RecordEntity>

    /**
     * ★ 清空整张表 —— **只有「整库替换」和「回退快照」两个场景配用它**。
     *
     * ## ⚠️ 它跟「删除记录」是两件事，别混
     *
     * - **删除记录**（用户点删除）→ 原地 `UPDATE` 成 `GAP`，**行不许消失**
     * - **整库替换**（导入 / 回退）→ 这一整张表被另一份完整数据换掉，旧行才真的走
     *
     * 区别在「换完之后那一刻表里有没有洞」：前者换完必须密铺，
     * 后者换完也**必须密铺**（由 `replaceAllRecords` 的逐天 `Density.check` 保证），
     * 所以清空本身是安全的 —— 但前提是**紧接着一定写进一份完整的密铺数据**。
     *
     * `record_tags` 上的外键是 `ON DELETE CASCADE`，所以它不用单独清
     * （有一条测试专门断言这件事真的生效，不是我以为它生效）。
     */
    @Query("DELETE FROM records")
    suspend fun deleteAll()

    /**
     * 挂在这个事项上的全部记录 —— **删事项时扫它**。
     *
     * 删事项不是「删一条字典数据」那么简单：历史上所有挂它时间都要
     * **原地改写成 ▨ 未记录**（不能 `DELETE`，否则那段时间会在密铺里变成一个洞）。
     */
    @Query("SELECT * FROM records WHERE itemId = :itemId AND deleted = 0")
    suspend fun byItem(itemId: String): List<RecordEntity>

    @Insert
    suspend fun insert(record: RecordEntity)

    @Insert
    suspend fun insertAll(records: List<RecordEntity>)

    @Update
    suspend fun update(record: RecordEntity)

    @Update
    suspend fun updateAll(records: List<RecordEntity>)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 删掉某一天的全部记录 —— **只给「整天覆盖粘贴」用**。
     *
     * ## ⚠️ 跟「删除一条记录」是两件事
     *
     * 用户点删除 → 那条记录**原地变成 GAP**，行不许消失（否则密铺上留一个洞）。
     * 而这里是「这一整天被另一份完整的密铺数据整体换掉」，
     * 跟 [deleteAll]（整库替换）属于同一类：行真的走，但**紧接着必须写进一份密铺数据**。
     *
     * 所以调用方（`TimelineRepository.pasteDay`）的顺序是死的：
     * 先拍照 → 清这一天的行 → 写进新的密铺段 → 让 `ensureDense()` 补齐尾巴。
     * 中间任何一步失败都要整体回滚（它在事务里）。
     *
     * `record_tags` 的外键是 `CASCADE`，所以关系会自动清掉 —— 但标签**id 本身**还在，
     * 粘贴时按原样挂回去，不丢。
     */
    @Query("DELETE FROM records WHERE day = :day")
    suspend fun deleteByDay(day: String)

    /** 收尾一条进行中的记录。 */
    @Query("UPDATE records SET endMin = :endMin, updatedAt = :now WHERE id = :id")
    suspend fun closeRecord(id: String, endMin: Int, now: Long)

    /** 压短左边界（被新记录从右边切掉一块）。 */
    @Query("UPDATE records SET endMin = :newEnd, updatedAt = :now WHERE id = :id")
    suspend fun trimEnd(id: String, newEnd: Int, now: Long)

    /** 压短右边界（被新记录从左边切掉一块）。 */
    @Query("UPDATE records SET startMin = :newStart, updatedAt = :now WHERE id = :id")
    suspend fun trimStart(id: String, newStart: Int, now: Long)

    /** 软删除，只给云同步用。 */
    @Query("UPDATE records SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)
}
