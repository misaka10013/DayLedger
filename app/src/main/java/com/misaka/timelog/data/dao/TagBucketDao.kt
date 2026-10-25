package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.misaka.timelog.data.entity.TagBucketEntity

/**
 * 一级标签（桶）的访问口。
 *
 * ⚠️ 桶是**纯容器** —— 记录上根本不挂它。所以改名 / 换图标 / 换位置 / 删桶
 * **一个字都不会牵连到任何记录**，也不改统计口径。
 *
 * 它和「分类」长得像（都有位置、都有删除守卫），但**连带影响完全不同**：
 * 分类 → 事项是**归属**关系，改归属历史统计要跟着重算；桶 → 标签只是换个抽屉。
 */
@Dao
interface TagBucketDao {

    @Query("SELECT * FROM tag_buckets ORDER BY position")
    suspend fun all(): List<TagBucketEntity>

    @Query("SELECT * FROM tag_buckets WHERE id = :id")
    suspend fun byId(id: String): TagBucketEntity?

    /** 桶里还有几个标签 —— 删除守卫用（先搬空、才可删）。 */
    @Query("SELECT COUNT(*) FROM tags WHERE bucketId = :bucketId")
    suspend fun tagCount(bucketId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tag_buckets")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(bucket: TagBucketEntity)

    @Update
    suspend fun update(bucket: TagBucketEntity)

    @Query("DELETE FROM tag_buckets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tag_buckets SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: String, position: Int)
}
