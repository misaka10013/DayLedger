package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一级标签 —— 「桶」，纯容器。
 *
 * ## ⚠️ 它和分类长得像，但语义完全不同（最容易做错的地方）
 *
 * | | 分类 → 事项 | 一级标签 → 二级标签 |
 * |---|---|---|
 * | 关系性质 | **归属** | **纯容器** |
 * | 改动牵连 | 换归属 → 历史统计**跟着重算** | 换桶 / 删桶 → **一个字都不动**（记录上根本不挂桶） |
 *
 * 两者的删除守卫长得一模一样（都是「先搬空、才可删」），
 * **但连带影响完全不同** —— 所以实现时不要照着分类那一套抄标签的换桶逻辑。
 */
@Entity(tableName = "tag_buckets")
data class TagBucketEntity(
    @PrimaryKey val id: String,
    /** 桶的图标（emoji 或图标名）。 */
    val icon: String,
    val name: String,
    /** 桶之间的排序位置。 */
    val position: Int,
)
