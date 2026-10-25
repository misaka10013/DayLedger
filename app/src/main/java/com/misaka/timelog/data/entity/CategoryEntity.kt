package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一级分类。
 *
 * 它是「决定颜色 + 统计口径」的那一层：网格里同一分类的所有事项格子共用这个底色，
 * 统计里的分类口径也是按它算的。
 *
 * ⚠️ 只有分类有 [position]。事项没有 —— 事项在网格里的位置是由
 * 「分类的 position + 事项自己在本分类内的 orderInCat」推出来的。
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    /** 分类名，用户可改。改成什么都不会影响历史记录（记录挂的是 id）。 */
    val name: String,
    /** 颜色，#RRGGBB 形式。用户可改，改完网格里该分类整块换色。 */
    val colorHex: String,
    /** 排序位置，用户手动 ↑↓ 调。同时决定网格里这一整块放在上面还是下面。 */
    val position: Int,
)
