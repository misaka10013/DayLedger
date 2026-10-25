package com.misaka.timelog.data

import java.util.UUID

/**
 * 生成一个新的实体 id。
 *
 * ## 为什么用 UUID 而不是自增数字
 *
 * 将来要和云端 `tl_days` 做双向同步。同步的经典难题是：
 * **本地新建了一条记录，云端也新建了一条，两边各自编号都从 1 开始 —— 合并时必撞。**
 * UUID 由本地直接生成、全局唯一，多端各建各的也不会冲突。
 *
 * 代价是 id 长一点（36 字符），但记录一天也就十几条，可忽略。
 */
fun newId(): String = UUID.randomUUID().toString()
