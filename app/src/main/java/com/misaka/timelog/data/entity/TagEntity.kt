package com.misaka.timelog.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 二级标签 —— **真正挂到记录上的那一层**。桶本身不挂记录。
 *
 * ## 删除守卫
 * 桶里还有标签时，桶删不掉（先转移搬空）。这里用 RESTRICT 外键兜底，
 * Repository 层负责先说人话。
 *
 * ## 删标签本身怎么办
 * 已拍板：**级联摘掉引用** —— 删标签时把记录上对它的引用一起清掉
 * （由 `record_tags` 表的 CASCADE 外键完成）。
 * 代价是历史记录少一个标记，但「删标签」是用户的明确意图。
 */
@Entity(
    tableName = "tags",
    foreignKeys = [
        ForeignKey(
            entity = TagBucketEntity::class,
            parentColumns = ["id"],
            childColumns = ["bucketId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("bucketId")],
)
data class TagEntity(
    @PrimaryKey val id: String,
    /** 所属的桶。换桶只是「换个抽屉」，不影响标签本身、更不影响记录上的引用。 */
    val bucketId: String,
    val name: String,
    /** 记录卡片上那个小圆点的颜色，#RRGGBB。 */
    val colorHex: String,
    /**
     * 一行说明，用户选这个标签时会显示出来。
     *
     * 作用是**防止标签越加越含糊** —— 过几个月自己都忘了「低价值消耗」当初指什么。
     *
     * ⚠️ 列名用 `description` 而不是 `desc`：`DESC` 是 SQLite 的保留字
     * （`ORDER BY x DESC`），拿它当列名会让 Room 生成的 SQL 出错。
     */
    @ColumnInfo(name = "description") val description: String,
)
