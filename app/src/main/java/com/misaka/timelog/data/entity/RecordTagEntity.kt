package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 记录 ↔ 它挂的标签（多对多）。
 *
 * ## 两个外键的删除策略**故意不一样**
 *
 * | 外键 | 策略 | 为什么 |
 * |---|---|---|
 * | → [RecordEntity] | `CASCADE` | 「用户删记录」是原地改成 gap，**不删行**，所以这条平时不触发。但「邻居被新记录完全覆盖」时行是真的会没的 —— 那时它的标签关联也该跟着清掉 |
 * | → [TagEntity] | `CASCADE` | ★ 拍板结果：**删标签级联摘掉引用**。否则记录上会留下悬空 id，卡片上显示一个点不进去的标签 |
 *
 * ⚠️ [tagId] 上的索引是必需的 —— 删标签时数据库要按 tagId 反查所有引用，
 * 没索引就得全表扫。
 */
@Entity(
    tableName = "record_tags",
    primaryKeys = ["recordId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class RecordTagEntity(
    val recordId: String,
    val tagId: String,
)
