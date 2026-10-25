package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.misaka.timelog.data.entity.CategoryEntity

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY position")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: String): CategoryEntity?

    /**
     * 分类下还有几个事项 —— 删除守卫用。
     *
     * ⚠️ 这是「先搬空、才可删」那条规则的**查询半边**。
     * 光在界面上把删除按钮灰着不算数，数据层必须真的拦住。
     */
    @Query("SELECT COUNT(*) FROM items WHERE categoryId = :categoryId")
    suspend fun itemCount(categoryId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM categories")
    suspend fun nextPosition(): Int

    @Insert
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE categories SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: String, position: Int)
}
