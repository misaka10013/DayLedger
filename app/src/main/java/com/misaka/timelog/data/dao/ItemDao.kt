package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.misaka.timelog.data.entity.ItemDefaultTagEntity
import com.misaka.timelog.data.entity.ItemEntity

/**
 * 事项表的访问口。
 *
 * ⚠️ **不提供「按名字查事项」** —— 名字是用户随手改的，只有 id 是稳定的。
 * 原型里出现过「按名字回写」的 bug（改名的第一个字还没敲完就找不到自己了）。
 */
@Dao
interface ItemDao {

    /** 全部事项。⚠️ 网格里的顺序 = 分类 position + 这里的 orderInCat，缺一不可。 */
    @Query("SELECT * FROM items ORDER BY categoryId, orderInCat, id")
    suspend fun all(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE categoryId = :categoryId ORDER BY orderInCat, id")
    suspend fun byCategory(categoryId: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: String): ItemEntity?

    /** 这些 id 里有几个真的存在 —— 防止往记录/模板上挂悬空 id。 */
    @Query("SELECT COUNT(*) FROM items WHERE id IN (:ids)")
    suspend fun countExisting(ids: List<String>): Int

    /** 分类下还有几个事项 —— 删除守卫用。 */
    @Query("SELECT COUNT(*) FROM items WHERE categoryId = :categoryId")
    suspend fun countInCategory(categoryId: String): Int

    /** 某个分类内下一个可用的组内次序。 */
    @Query("SELECT COALESCE(MAX(orderInCat), -1) + 1 FROM items WHERE categoryId = :categoryId")
    suspend fun nextOrderInCat(categoryId: String): Int

    @Insert
    suspend fun insert(item: ItemEntity)

    @Update
    suspend fun update(item: ItemEntity)

    @Update
    suspend fun updateAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE items SET orderInCat = :order WHERE id = :id")
    suspend fun updateOrderInCat(id: String, order: Int)

    // ---------------------------------------------------------------- 默认标签

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addDefaultTag(ref: ItemDefaultTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addDefaultTags(refs: List<ItemDefaultTagEntity>)

    @Query("DELETE FROM item_default_tags WHERE itemId = :itemId")
    suspend fun clearDefaultTags(itemId: String)

    @Query("DELETE FROM item_default_tags WHERE tagId = :tagId")
    suspend fun removeTagFromAllItems(tagId: String)

    @Query("SELECT tagId FROM item_default_tags WHERE itemId = :itemId")
    suspend fun defaultTagIds(itemId: String): List<String>

    /**
     * 一次取回**全库**的事项↔标签关联。
     *
     * 用途：事项列表要显示每个事项的默认标签。
     * ⚠️ 别在列表里对每行调一次 `defaultTagIds` —— 63 个事项就是 63 次查询，
     * 而且每次都跨线程，滚动时会明显卡。一次全拿回来在内存里分组。
     */
    @Query("SELECT * FROM item_default_tags")
    suspend fun allDefaultTagRefs(): List<ItemDefaultTagEntity>

    /**
     * 有多少个事项把这个标签当默认标签。
     *
     * 用途只有一个：删标签之前告诉用户「删了会连带影响什么」。
     * `item_default_tags.tagId` 上是 CASCADE —— 删标签会把这些行静默清掉，
     * 不提前说一声，用户只会觉得「我什么都没动，事项的默认标签就没了」。
     */
    @Query("SELECT COUNT(*) FROM item_default_tags WHERE tagId = :tagId")
    suspend fun countDefaultTagUsers(tagId: String): Int
}
