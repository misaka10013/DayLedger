package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.misaka.timelog.data.entity.RecordTagEntity
import com.misaka.timelog.data.entity.TagEntity

/**
 * 二级标签的访问口 —— 真正挂到记录上的那一层。
 *
 * ## 改名 / 换桶为什么不用动记录
 *
 * 因为记录上挂的是 **tagId**（`record_tags` 表），不是名字。
 * 改个名只是 `tags` 表里一行的事，几百条历史记录一个字都不用动。
 *
 * ⚠️ 这正是原型与真机的**关键差异**：原型存的是 `'#标签名'` 字符串，
 * 所以改名和删除都**必须扫一遍所有记录**去同步（否则留下"孤儿标签"——
 * 列表里已经没有它了，记录卡片上却还挂着、点也点不进去）。
 * 真机存 id 之后这层同步逻辑不需要了 —— 但**删除仍然要处理引用**，
 * 由 `record_tags.tagId` 上的 CASCADE 外键自动完成（已拍板：级联摘掉）。
 */
@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun all(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE bucketId = :bucketId ORDER BY name")
    suspend fun byBucket(bucketId: String): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: String): TagEntity?

    /** 这些 id 里有几个真的存在 —— 防止往记录上灌孤儿标签。 */
    @Query("SELECT COUNT(*) FROM tags WHERE id IN (:ids)")
    suspend fun countExisting(ids: List<String>): Int

    @Insert
    suspend fun insert(tag: TagEntity)

    @Update
    suspend fun update(tag: TagEntity)

    /** 换桶：只是改一行 `bucketId`，标签本身和记录上的引用都不动。 */
    @Query("UPDATE tags SET bucketId = :bucketId WHERE id = :id")
    suspend fun moveToBucket(id: String, bucketId: String)

    /**
     * 删标签。`record_tags` 上的 CASCADE 会自动把记录上的引用摘干净
     * （已拍板：级联摘掉，而不是拒绝删）。
     */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    // ------------------------------------------------------ 记录 ↔ 标签

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachToRecord(ref: RecordTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachToRecord(refs: List<RecordTagEntity>)

    @Query("DELETE FROM record_tags WHERE recordId = :recordId")
    suspend fun detachAllFromRecord(recordId: String)

    @Query("SELECT tagId FROM record_tags WHERE recordId = :recordId")
    suspend fun tagIdsOfRecord(recordId: String): List<String>

    /**
     * 一次取回多条记录的标签 —— 避免渲染列表时一条一条查（N+1 查询）。
     */
    @Query("SELECT * FROM record_tags WHERE recordId IN (:recordIds)")
    suspend fun refsOfRecords(recordIds: List<String>): List<RecordTagEntity>

    /**
     * 有多少条**还活着**的记录挂着这个标签。
     *
     * 用途：删标签之前把后果说清楚。删完这些引用会被 CASCADE 静默摘掉，
     * 用户事后翻历史只会觉得「标签怎么不见了」。
     * ⚠️ 必须 join records 过滤 `deleted = 0` —— 否则把已经软删的记录也算进去，
     * 提示里那个数字会虚高（这个库有 6500 多段记录，误差很显眼）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM record_tags rt
        JOIN records r ON r.id = rt.recordId
        WHERE rt.tagId = :tagId AND r.deleted = 0
        """
    )
    suspend fun countRecordRefs(tagId: String): Int

    /**
     * **整张 `record_tags` 一次取回** —— 只给「导出全部数据」用。
     *
     * ## 为什么不用上面那个 [refsOfRecords]
     *
     * 那个按 id 列表查，适合「当前这一屏几十条」。而导出要的是几千条：
     * 真实库里 `record_tags` 有 **11760 行**，一条一条查是 N+1，
     * 攒成一个大 `IN (:ids)` 又会撞 SQLite 的绑定参数上限
     * （旧版默认 999，新版 32766 —— 库一大就越界，而且报错只在数据多的机器上出现）。
     *
     * 导出本来就是「全量」，所以直接全取最省事也最快。
     */
    @Query("SELECT * FROM record_tags")
    suspend fun allRefs(): List<RecordTagEntity>
}
