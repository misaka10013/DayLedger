package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地备份快照 —— 替代「归档」的后悔药。
 *
 * ## ⚠️ 必须与主数据分开存
 *
 * 「清空全部数据」不能把快照一起清掉 —— 否则**刚要清空前存的那份后悔药自己先没了**，
 * 这个功能就完全没意义了。所以它是一张独立的表（原型里也是独立的 storage key）。
 *
 * ## 什么时候存
 * **只在高危操作之前**自动存：
 * - 删事项（一次改几百条历史，不可逆）
 * - 清空全部数据
 *
 * ❌ 不做自动定期备份、❌ 不做多版本时间机器（御坂：本地备份一次就行）。
 * 所以这里只保留**一份**「上一次的样子」—— 存新的时把旧的删掉。
 */
@Entity(tableName = "backup_snapshots")
data class BackupSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 存这份快照的时刻（毫秒）。 */
    val at: Long,

    /** 为什么存的，给用户看，如「删除事项『玩手机』之前」。 */
    val why: String,

    /**
     * 整包数据的 JSON。
     *
     * 格式照需求文档 §8.4.10：`{v, at, why, store, cats, items, tag1, tags, tpl}`
     * —— 和「导出 .json」用的是**同一套格式**，三处共用。
     */
    val payload: String,
)
