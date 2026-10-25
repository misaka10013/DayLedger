package com.misaka.timelog.data.repo

import androidx.room.withTransaction
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.TemplateSegmentEntity
import com.misaka.timelog.data.newId
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.SystemClock
import com.misaka.timelog.domain.TemplateEdit

/**
 * 模板的**配置** —— 段和星期指派。
 *
 * ## ★ 它跟 [TimelineRepository] 的分界线：配置 vs 运行
 *
 * 「配置 ≠ 启动」是这个模块的第一条铁律，所以数据层也照这条线切开：
 *
 * | | 归谁 | 碰什么 |
 * |---|---|---|
 * | **配置**（改段、改星期） | 这里 | `templates` / `template_segments` |
 * | **运行**（启动、退出、铺写、跨天） | [TimelineRepository] | `template_run` / `records` |
 *
 * 为什么值得切成两个类：这两件事的**触发时机和频率完全不同**。
 * 配置是「你想起来才去改一次」，运行是「每分钟刷新都可能在动」。
 * 混在一个类里，读的人很难一眼看出「这个方法会不会顺手把今天的记录铺了」。
 *
 * ## ⚠️ 这里唯一一个对运行态的接触是**只读**的
 *
 * [Config.runOn] 只是为了让配置页能显示「▶ 正在按模板记录」—— 配置页需要让你知道
 * 「你现在改的这一份，此刻正在生效中」，否则改完会怀疑「怎么没反应」。
 * 写运行态的方法一个都不在这里。
 *
 * ## ⚠️ 改完模板**不需要**额外做任何事
 *
 * 这是设计好的，不是漏了：
 *
 * - 今天**已经铺好**的记录不会变（模板是兜底，不追溯改写历史）
 * - 接下来铺的**自然读最新段**（[TimelineRepository.applyTemplate] 每次都重新查库）
 * - 正在执行的那一段如果被锁住了（`template_run.lockedSegSnapshot`），
 *   它会**先按旧模板跑完** —— 这条规则自动成立，不需要这里配合
 *
 * 所以 `saveSegments` 只写配置表，一个 `records` 字段都不碰。
 */
class TemplateRepository(
    private val db: TimeLogDatabase,
    private val clock: Clock = SystemClock(),
) {

    private val templates = db.templateDao()
    private val items = db.itemDao()

    /**
     * 配置页需要的全部数据。
     *
     * @param segs **已按 sort 排好** —— 段的次序是有意义的（邻接关系决定挪边界的行为）
     * @param runOn 只读的展示用状态：「此刻正在按模板记录吗」
     * @param revision 改过几次。⚠️ 目前**没有任何逻辑依赖它**，
     *   只作为「这份配置变过没有」的记录留着（M4 云同步会需要它判断该不该上传）。
     */
    data class Config(
        val templateId: String,
        val name: String,
        val weekdaysMask: Int,
        val segs: List<TemplateEdit.Seg>,
        val revision: Long,
        val runOn: Boolean,
    )

    /** 读当前模板。`null` = 库里还没有模板（理论上种子已种下，这里只是兜底）。 */
    suspend fun load(): Config? {
        val t = templates.template() ?: return null
        val rows = templates.segments(t.id)
        return Config(
            templateId = t.id,
            name = t.name,
            weekdaysMask = t.weekdaysMask,
            segs = rows.map { TemplateEdit.Seg(it.startMin, it.endMin, it.itemId) },
            revision = t.revision,
            runOn = templates.run()?.isOn == true,
        )
    }

    /**
     * 整批保存段。
     *
     * ## ★ 密铺校验在这里**硬拦**
     *
     * 界面上当然也会拦（挪不动就是挪不动），但**不能只在界面拦** ——
     * 这是本项目在「删除守卫」上定过的一条规矩的同一条：
     *
     * > 守卫必须在数据层硬拦，不能只在 UI 灰着。
     *
     * 理由也一样：界面是「方便」，数据层是「正确」。
     * 一个没密铺的模板不会立刻报错，只会让模板从那道缝开始**静静地不再铺写**，
     * 而你要过好几天才会发现今天怎么少了一块。
     *
     * ## 先清后插，整批替换
     *
     * 段的 id 没有别的东西引用（记录引用的是**事项**，不是段），
     * 所以整批换掉最省心，不必去算「哪几段是新的、哪几段是改的」。
     * 整个替换在一个事务里，外面永远读不到「清完了还没插」的中间态。
     *
     * @return 校验没过 → `Blocked`（中文原因，界面直接显示）；否则 `Ok`
     */
    suspend fun saveSegments(
        templateId: String,
        segs: List<TemplateEdit.Seg>,
    ): Outcome<Unit> = db.withTransaction {
        val t = templates.template()
            ?: return@withTransaction Outcome.Blocked("还没有模板。")
        if (t.id != templateId) {
            return@withTransaction Outcome.Blocked("模板已经变了，退出重进一次。")
        }

        val problem = TemplateEdit.check(segs)
        if (problem.isNotEmpty()) return@withTransaction Outcome.Blocked(problem)

        // ⚠️ 事项 id 要现查一遍再写。外键是 `SET_NULL`，插一个不存在的 itemId 会**抛异常**，
        //    而这条路径是真的走得到：配置页开着的时候，用户在别处删掉了那个事项。
        //    查不到就写成 null —— 那正是「悬空段」的正确表示，界面会显示灰底「去重选事项」，
        //    比崩溃或者静默改成别的事项都诚实。
        val alive = items.all().map { it.id }.toSet()

        val ts = clock.now().epochMillis
        templates.deleteSegmentsOf(t.id)
        templates.putSegments(
            segs.mapIndexed { i, s ->
                TemplateSegmentEntity(
                    id = newId(),
                    templateId = t.id,
                    startMin = s.startMin,
                    endMin = s.endMin,
                    itemId = s.itemId?.takeIf { it in alive },
                    sort = i,
                )
            }
        )
        templates.putTemplate(t.copy(revision = t.revision + 1, updatedAt = ts))
        Outcome.Ok(Unit)
    }

    /**
     * 保存星期指派。
     *
     * ⚠️ **允许一天都不指派** —— 那等于「我把模板搁着，但先别跑」，
     * 是个正当的用法（比「删掉整个模板」轻得多）。界面会明确写出
     * 「没有任何一天 → 模板不会生效」，不会让你以为它还在跑。
     */
    suspend fun setWeekdays(templateId: String, mask: Int): Outcome<Unit> = db.withTransaction {
        val t = templates.template()
            ?: return@withTransaction Outcome.Blocked("还没有模板。")
        if (t.id != templateId) {
            return@withTransaction Outcome.Blocked("模板已经变了，退出重进一次。")
        }
        templates.putTemplate(
            t.copy(
                // 夹掉高位垃圾：掩码只有 7 位有意义
                weekdaysMask = mask and TemplateEdit.FULL_WEEK,
                updatedAt = clock.now().epochMillis,
            )
        )
        Outcome.Ok(Unit)
    }
}
