package com.misaka.timelog.ui

/**
 * 时间线上要画的那几样东西。
 *
 * 它们都放在这里而不是塞进 ViewModel 文件，是因为 [TimelineAssembler]（纯函数）
 * 也要产出它们 —— 纯函数的测试不该被拖进 Android 环境。
 */

/**
 * 时间线上「一张卡片」需要的一切。
 *
 * ## 为什么要有这么一个类，而不是让界面直接读 `RecordEntity`
 *
 * 界面如果直接拿实体，就得自己回答一堆问题：这条是空白吗？它的时长怎么算？
 * 进行中的那条终点算到哪？事项被删了显示什么？颜色从哪来？标签去哪查？
 *
 * 这些问题**每一条都是业务规则**。一旦散在 Compose 代码里，
 * 将来做统计页、桌面小部件、云同步时就得再答一遍，而且大概率答得不一样。
 *
 * 所以这里的规矩是：**界面层里一行 `if` 都不该有**。
 * 这个类里的每个字段都是「已经定好、照着画就行」的。
 *
 * ⚠️ [startMin] / [endMin] 的原始值也带上 —— M2-B 做「改时间」时要拿它们做基准，
 * 到时候就不用再回数据层查一遍。
 */
data class RecordRow(
    val id: String,
    /** 要显示的名字。空白段就是「▨ 未记录」。 */
    val name: String,
    /** 左侧色条的颜色。空白段是灰的。 */
    val colorHex: String,
    val startMin: Int,
    val endMin: Int?,
    /** `'21:47'`。 */
    val startLabel: String,
    /** `'22:59'`；进行中的那条是「现在」。 */
    val endLabel: String,
    /** `'1时12分'`。 */
    val durationLabel: String,
    val durationMin: Int,
    /** 是不是一段「▨ 未记录」。 */
    val isGap: Boolean,
    /** 正在计时的那一条（只有今天可能有）。 */
    val isLive: Boolean,
    /** 模板替你铺的 —— 界面上要能看出来（不然记录自己冒出来会以为见鬼了）。 */
    val fromTemplate: Boolean,
    /** 分类名（显示在时间范围右边的小字）。空白段没有。 */
    val categoryName: String?,
    val tags: List<TagChip>,
    val note: String,
)

/** 卡片上挂着的一颗小标签。 */
data class TagChip(val id: String, val name: String, val colorHex: String)

/**
 * 模板的运行态 —— 界面照着显示就行。
 *
 * ⚠️ 「今天该不该跑」**不在这里判断**，`coveredToday` 是数据层算好的。
 * 真机版比原型多这么一个类，是因为原型里同一句判断
 * （`TPL_RUN.day !== TODAY_KEY` ／ `tplCoversWeekday`）散在两个文件里，
 * 读代码时会以为它们互相矛盾。**一个判据只能有一个定义处。**
 */
data class TemplateView(
    /** 在不在跑。 */
    val on: Boolean = false,
    /** 模板名（「工作日」）。没配过模板时是空串。 */
    val name: String = "",
    /** 今天被指派到了吗 —— 不满足就启用不了，界面要把原因说出来。 */
    val coveredToday: Boolean = false,
    /** 配过模板吗（配过但没启用也是 true）。 */
    val exists: Boolean = false,
)

/**
 * 周条上的一格。
 *
 * ⚠️ 多带一个 [isFuture]：**未来的格子不可点**。
 * 未来的时间还没发生，既没有记录也无法补记 —— 点过去只会看到一片空白，
 * 用户会以为「数据丢了」。不如直接让它看起来就是不可点的。
 */
data class WeekCell(
    val key: String,
    /** `'三'`。 */
    val weekdayLabel: String,
    val isViewing: Boolean,
    val isToday: Boolean,
    val isFuture: Boolean,
    /** 这一天有「未记录」—— 周条上那个小红点。 */
    val hasGap: Boolean,
)

/** 「装配好的一天」—— 界面拿到的就是这么一个完整快照。 */
data class DayView(
    /** 卡片列表，**倒序**（越晚越靠上）。 */
    val rows: List<RecordRow>,
    /** 这一天「已记录」合计（分钟）。 */
    val recordedMin: Int,
    /** 这一天「未记录」合计（分钟）。0 = 记录完整。 */
    val gapMin: Int,
    /** 正在进行的那条。`null` = 空闲中。 */
    val liveRow: RecordRow?,
)
