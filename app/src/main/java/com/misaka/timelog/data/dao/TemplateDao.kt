package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.misaka.timelog.data.entity.TemplateEntity
import com.misaka.timelog.data.entity.TemplateRunEntity
import com.misaka.timelog.data.entity.TemplateSegmentEntity

/**
 * 模板三张表的访问口。
 *
 * ## 为什么它比别的 DAO 小这么多
 *
 * 因为**规则不在这里**。这里只有 SQL 的搬运工：
 * 「今天该不该跑」「哪些空白该填」「锁定的段什么时候失效」全在
 * [com.misaka.timelog.domain.TemplateEngine]（纯函数）和
 * [com.misaka.timelog.data.repo.TimelineRepository] 里。
 *
 * 这个划分不是洁癖 —— 模板的边界组合多到爆炸（段边界压在记录边界上、
 * 段跨了三条记录、锁定的段刚跑完……），**只有纯函数才测得动**。
 *
 * ## ⚠️ `template_run` 永远只有一行
 *
 * 主键写死 `1`（[TemplateRunEntity.SINGLETON_ID]）。所以 [run] 不接参数、
 * [putRun] 用 `REPLACE` —— 一行表不需要「先查再决定插还是改」。
 */
@Dao
interface TemplateDao {

    /** V1 只有**一套**模板（「工作日」）。多套 / 例外日 → V2。 */
    @Query("SELECT * FROM templates ORDER BY id LIMIT 1")
    suspend fun template(): TemplateEntity?

    /** 模板的段，按次序排好。⚠️ 它们必须密铺 0..1440，由写入口保证。 */
    @Query("SELECT * FROM template_segments WHERE templateId = :templateId ORDER BY sort")
    suspend fun segments(templateId: String): List<TemplateSegmentEntity>

    /** 运行态（0 行 = 从没启用过，等同于「没在跑」）。 */
    @Query("SELECT * FROM template_run WHERE id = ${TemplateRunEntity.SINGLETON_ID}")
    suspend fun run(): TemplateRunEntity?

    /** 把运行态整体写回去 —— 启停、跨天换日、锁定段失效都走它。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRun(run: TemplateRunEntity)

    @Update
    suspend fun putTemplate(template: TemplateEntity)

    /** 整批覆盖段（M3 配置页的「保存」会用到）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSegments(segments: List<TemplateSegmentEntity>)

    /**
     * 清掉某个模板的所有段 —— 配置页保存时「先清后插」的前半步。
     *
     * ## ⚠️ 这里为什么可以 `DELETE`，而记录表绝对不行
     *
     * 「删除一律原地改写成空白、禁止 `DELETE`」那条铁律针对的是 **`records`**：
     * 记录必须密铺 `[0, cut]`，删掉一行就在时间轴上凿出一个洞，谁也看不出来。
     *
     * 而 `template_segments` 是**配置**，不是记录 —— 它的「密铺」是逻辑约束
     * （首尾相接覆盖 0..1440），由 [com.misaka.timelog.domain.TemplateEdit] 保证，
     * 不靠「行数」承载任何信息。整批换掉是安全的：要么全换成新的（校验通过才写），
     * 要么一行不动（校验没过直接返回）。中间态永远不会被别的地方读到 ——
     * 整个替换在一个事务里。
     */
    @Query("DELETE FROM template_segments WHERE templateId = :templateId")
    suspend fun deleteSegmentsOf(templateId: String)

    @Query("DELETE FROM template_segments WHERE id = :id")
    suspend fun deleteSegment(id: String)

    /** 撞号时用来生成新 id（跟记录表同一套扫号法，见 [com.misaka.timelog.data.newId]）。 */
    @Query("SELECT id FROM template_segments")
    suspend fun allSegmentIds(): List<String>
}
