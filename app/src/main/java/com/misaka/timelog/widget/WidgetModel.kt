package com.misaka.timelog.widget

import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.ui.DayView
import com.misaka.timelog.ui.RecordRow
import com.misaka.timelog.ui.TimelineAssembler

/**
 * 桌面小部件上要画的东西 —— **已经算好的成品**，界面拿过去照着画就行。
 *
 * ## 为什么单独抠出这么一个文件
 *
 * 小部件的界面跑在**系统桌面的宿主进程**里，Robolectric 模拟不了，
 * 属于「只能真机手动验收」的那一类。所以守跟时间线一样的规矩：
 * **「该显示什么」和「怎么画」拆开** —— 前者是这个纯函数，后者只管照画。
 * 于是「空闲时显示什么」「事项被删了怎么办」「凌晨刚过一条记录都没有」
 * 这些分支能在 JVM 上秒级测完，不必去真机上摆弄桌面。
 *
 * ## ⚠️ 这里一行取数逻辑都没有
 *
 * 记录从哪来、要不要先补密、字典怎么查 —— 那是宿主（`WidgetSnapshot`）的事。
 * 纯函数只认「装配好的那一天」，跟 `TimelineAssembler` 是同一种分工。
 *
 * ## ⚠️ 时长文字也必须从这里出
 *
 * 小部件上的「2时13分」跟时间线卡片上的那一串**是同一句话**。
 * 只要有一处自己在小部件里拼字符串，两边迟早会出现
 * 「卡片上 2时13分、桌面上 2时14分」这种同一个时刻两个答案的情况。
 * 所以这里只做搬运：`RecordRow.durationLabel` 原样带过来。
 */
data class WidgetModel(
    /** 正在计时的那件事。`null` = 空闲中。 */
    val live: WidgetLive?,
    /** 今天「已记录」合计，`'10时43分'`。 */
    val recordedLabel: String,
    /** 今天「未记录」合计，`'3时17分'`。 */
    val gapLabel: String,
    /** 今天有没有「未记录」—— 决定那一行要不要标一个点。 */
    val hasGap: Boolean,
    /** 迷你色条：按**真实时序**铺满「今天从 0 点到此刻」。 */
    val bars: List<WidgetBar>,
    /** 今天一条记录都还没有（0 点刚过那几分钟）。 */
    val empty: Boolean,
) {

    companion object {

        /**
         * 装配好的那一天 → 小部件要画的东西。
         *
         * @param view [TimelineAssembler.day] 的产物
         * @param cut  这一天该铺到哪（分钟）—— **进行中的那条要用它算时长**，
         *             色条也是铺到它为止（不是铺到 1440：未来的时间还没发生）
         */
        fun from(view: DayView, cut: Int): WidgetModel = WidgetModel(
            live = view.liveRow?.let {
                WidgetLive(
                    name = it.name,
                    durationLabel = it.durationLabel,
                    startLabel = it.startLabel,
                    colorHex = it.colorHex,
                    fromTemplate = it.fromTemplate,
                )
            },
            recordedLabel = DayKey.duration(view.recordedMin),
            gapLabel = DayKey.duration(view.gapMin),
            hasGap = view.gapMin > 0,
            bars = barsOf(view.rows, cut),
            empty = view.rows.isEmpty(),
        )

        /**
         * 迷你色条。
         *
         * ## 两条规矩
         *
         * ① **按真实时序排、不合并同事项的多段** —— 跟统计页第 ③ 张图一个口径
         *    （「今天几点在干什么」要的是真实作息，不是汇总）。
         * ② **但相邻同色要合起来** —— 颜色一样、挨着，画成一段跟画成两段
         *    在屏幕上完全没区别，而小部件的 UI 树会被序列化进 RemoteViews，
         *    段数越少越省。一天几十条记录，合并完通常剩十来段。
         *
         * ⚠️ 「未记录」永远单独成段（灰色）—— 它跟任何分类色都不会撞，
         * 所以同色合并不会把它并掉；但显式判一次 `isGap` 更稳，
         * 免得将来有人改出一个灰色分类。
         */
        private fun barsOf(rows: List<RecordRow>, cut: Int): List<WidgetBar> {
            // 0 点整那一瞬 cut = 0，除法会炸 —— 这时候本来也没什么可画的
            if (cut <= 0) return emptyList()

            val out = ArrayList<WidgetBar>(rows.size)
            rows.sortedBy { it.startMin }.forEach { r ->
                // 零长度段跳过：`weight(0f)` 在 Compose 里是个非法值
                if (r.durationMin <= 0) return@forEach
                val color = if (r.isGap) TimelineAssembler.GAP_COLOR else r.colorHex
                val weight = r.durationMin.toFloat() / cut
                val last = out.lastOrNull()
                if (last != null && last.colorHex == color && last.isGap == r.isGap) {
                    out[out.size - 1] = last.copy(weight = last.weight + weight)
                } else {
                    out += WidgetBar(colorHex = color, weight = weight, isGap = r.isGap)
                }
            }
            return out
        }
    }
}

/**
 * 正在计时的那件事。
 *
 * ⚠️ 不带 `endLabel` —— 小部件不显示终点。它的终点就是「现在」，
 * 写个「现在」上去只是占地方；真正有意义的是**已经过去多久**。
 */
data class WidgetLive(
    val name: String,
    /** `'2时13分'`。 */
    val durationLabel: String,
    /** `'08:30'` —— 起点。跟时长一起看才知道「这一摊从什么时候开始的」。 */
    val startLabel: String,
    /** 分类色（色点、以及那行强调色用它）。 */
    val colorHex: String,
    /** 模板替你铺的 —— 跟时间线卡片一致，要看得出「这不是我亲手记的」。 */
    val fromTemplate: Boolean,
)

/** 迷你色条上的一段。 */
data class WidgetBar(
    val colorHex: String,
    /** 占「今天到此刻」的几分之几。相邻同色段的权重会被加在一起。 */
    val weight: Float,
    /** 是「未记录」那一段 —— 画成灰色，它是这个 App 里最该被看见的东西。 */
    val isGap: Boolean,
)
