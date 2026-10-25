package com.misaka.timelog.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 模板的**运行态** —— 单例行（[id] 恒为 1）。
 *
 * ## ★ 核心：配置 ≠ 启动
 *
 * 把模板配好**什么都不发生**。必须先**显式启用**才开始自动铺写。
 * 这张表就是「今天到底启没启用」的那份状态。
 *
 * 启用入口 = 时间线「结束」面板左下角的「⚙ 切到模板模式」
 * （放在天天点的地方，**不另设设置项**）。
 *
 * ## 退出规则
 * **只认一个动作**：点「结束」并亲手选别的事项。⚠️
 * 改记录 / 删记录 / **改模板都不算退出**。
 *
 * ⚠️ **跨天不算退出**（2026-10-15 起）。原来「进了没指派的一天就自动退出」已被推翻：
 * 那不是退出，那是**今天不排班**。差别在横条上看得见 —— 绿色「运行中」vs 灰色「开着 · 今天不铺」。
 */
@Entity(tableName = "template_run")
data class TemplateRunEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,

    val isOn: Boolean,

    /**
     * 哪一天在跑，「`2026-10-14`」。
     *
     * ★ 跨天时**只把它挪到新的一天，不退出** —— 哪怕新的一天没被指派。
     * 2026-10-15 御坂裁定：休息日「**模板没有被退出**，只是今天不铺」。
     * 详见 `TimelineRepository.syncTemplateDay()`。
     */
    val day: String?,

    /**
     * ★ **锁定段的快照**（JSON）。
     *
     * 场景：模板正在跑，你这时去改模板 —— 规则是
     * 「**当前正在执行的那一段先按【旧】模板跑完**，之后才用新模板」
     * （御坂原话：「当前自动模板记录完正在执行的事项后，采用新的模板数据」）。
     *
     * 所以启用那一刻要把「此刻所在的那一段」存成快照锁住；铺到它的终点后清空，
     * 此后读最新模板。
     */
    val lockedSegSnapshot: String?,
) {
    companion object {
        /** 这张表永远只有一行，主键写死。 */
        const val SINGLETON_ID = 1
    }
}
