package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 模板里的一段（「08:30–12:00 打工」）。
 *
 * ## ⚠️ 必须密铺 0..1440
 *
 * 段与段之间**不能有缝、不能重叠**：首段 [startMin] 恒为 0、末段 [endMin] 恒为 1440。
 * 想留白就**选一个「未记录」类的事项塞进去** —— **不许挖洞**。
 * 落库时跟 `records` 用同一套密铺校验。
 *
 * ## ⚠️ 为什么 [itemId] 是「可空 + SET_NULL」而不是 RESTRICT
 *
 * 这里有个**文档内部冲突**，按真实意图处理：
 *
 * - 直觉上应该用 RESTRICT（「事项被删了，模板段就不该存在」）——
 *   但那样 **删事项会被数据库直接挡住**。
 * - 而需求文档 §8.4.9 明确要求：删事项后模板段里引用的 itemId 变成**悬空**，
 *   段渲染成灰底「去重选事项」，模板页主动报「有 N 段事项已删」。
 *
 * 所以真正的设计意图是「**允许悬空、并提示用户重选**」。
 * 用 `SET_NULL` 让数据库自动完成这件事 —— 段还在（密铺不破），只是暂时没有事项，
 * 铺写时跳过它（**绝不能铺出「名字还挂着、点进去什么都没有」的幽灵记录**）。
 *
 * ## ⚠️ [itemId] 存的是 id，不是事项名
 *
 * 原型里存的是名字（因为原型的事项还没有 id）。真机**必须**用 id ——
 * 否则事项一改名，模板段就全变孤儿。这跟「记录上挂标签名」是同一个坑（第 14 轮的教训）。
 */
@Entity(
    tableName = "template_segments",
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("templateId"), Index("itemId")],
)
data class TemplateSegmentEntity(
    @PrimaryKey val id: String,

    val templateId: String,

    /** 当天第几分钟开始。首段恒为 0。 */
    val startMin: Int,

    /** 当天第几分钟结束。末段恒为 1440。 */
    val endMin: Int,

    /** 这一段对应哪个事项。`null` = 原来那个事项被删了，**等用户重选**。 */
    val itemId: String?,

    /** 段在列表里的次序。 */
    val sort: Int,
)
