package com.misaka.timelog.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.misaka.timelog.data.entity.BackupSnapshotEntity

/**
 * 本地备份快照的访问口。
 *
 * ## 只有「一份」 —— 这是设计，不是偷懒
 *
 * 它保存的是**上一次高危操作之前的样子**，不是一串历史版本。
 * 所以写新快照时要先把旧的删掉（[clear] → [insert]）：多留几份会让「回退」这件事
 * 变成「我该回退到哪一份」，而用户根本无从判断。
 * （御坂的原话是「本地备份一次就行」。）
 *
 * 存的时候在**独立事务**里先落盘 —— 这一点很关键：
 * 如果它跟「清空 + 写入」挤在同一个事务里，那么只要那个事务回滚，
 * **这份后悔药会跟着一起消失**，也就白留了。见
 * `TimelineRepository.replaceAllRecords()` 的三阶段写法。
 */
@Dao
interface BackupDao {

    /** 存一份新快照 —— **旧的会被丢掉**（只保留「上一次的样子」）。 */
    @Insert
    suspend fun insert(snapshot: BackupSnapshotEntity): Long

    /** 全清。留着给测试和「删掉快照」用。 */
    @Query("DELETE FROM backup_snapshots")
    suspend fun clear()

    /** 取最新的一份 —— 目前最多也只有一份。 */
    @Query("SELECT * FROM backup_snapshots ORDER BY id DESC LIMIT 1")
    suspend fun latest(): BackupSnapshotEntity?
}
