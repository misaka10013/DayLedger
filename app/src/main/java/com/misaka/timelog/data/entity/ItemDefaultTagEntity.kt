package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 事项 ↔ 它的默认标签（多对多）。
 *
 * 语义：记这个事项时，这些标签会**自动带上**，记录页里还能当场加减。
 * ⚠️ 删掉事项的默认标签**不影响已经记下的记录** —— 记录上那份是当时挂上去的快照。
 */
@Entity(
    tableName = "item_default_tags",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
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
data class ItemDefaultTagEntity(
    val itemId: String,
    val tagId: String,
)
