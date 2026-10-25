package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 二级事项 —— 选择网格里的一格，也是记录真正挂靠的对象。
 *
 * ## 为什么分类用 RESTRICT 外键
 *
 * 产品规则：**分类下还有事项时，不允删掉这个分类**（先搬空才能删）。
 * 这里的外键约束是**最后一道闸**，防的是「将来某天有人写错一条 SQL 直接删了分类，
 * 留下一堆指向不存在分类的事项」。
 *
 * ⚠️ 但它**不负责给出好报错** —— 那是 Repository 层的活儿（要先查计数，
 * 抛出「这个分类下还有 N 个事项，先把它们搬走」这种人话）。两处都要有。
 */
@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    /** 归属的一级分类。⚠️ 改这个 = 改归属，历史统计口径会跟着重算（v1.11 裁定）。 */
    val categoryId: String,
    /** 事项名。改名不影响任何历史记录（记录挂的是 id，不是名字）。 */
    val name: String,
    /** 事项自己的颜色，决定网格里这一格的底色。 */
    val colorHex: String,
    /**
     * 在本分类内部的次序（0 起）。
     *
     * ⚠️ 这里是**显式字段**，不是「靠查询结果的自然顺序」——
     * 因为 SQL 查询结果的顺序在没有 ORDER BY 时是**没有保障**的，
     * 一次 UPDATE 就可能让事项在网格里换位置。用户手动排好的顺序必须真的存下来。
     */
    val orderInCat: Int,
)
