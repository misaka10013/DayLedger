package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 生活规律模板。V1 只有**一套**（「工作日」）。
 *
 * 多套模板 / 例外日 / 跨夜段 → V2 再说。
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,

    val name: String,

    /**
     * 哪些天生效，**位掩码**：bit0 = 周一 … bit6 = 周日。
     *
     * 例：工作日 = `0b0011111` = 31；只在周末 = `0b1100000` = 96。
     */
    val weekdaysMask: Int,

    /**
     * 模板内容的版本号，每次改段就给 +1。
     *
     * ⚠️ 用途只有一个：判断「**运行中改模板**」这件事。
     * 规则是「当前正在执行的那一段先按【旧】模板跑完，之后才采用新模板」——
     * 靠 `template_run.lockedSegSnapshot` 锁住那一段，revision 用来确认它有没有被改过。
     */
    val revision: Long,

    val updatedAt: Long,
)
