package com.misaka.timelog.domain

/**
 * 竖着的 24 小时色条上，「点在哪个段」应该怎么算。
 *
 * ## 为什么不能交给每个段自己
 *
 * 模板段长短差得极远：有的 8 小时，有的 10 分钟。10 分钟在 30dp/小时的缩放下
 * 只有 5dp 高，为了还能看见，它会被**抬到 22dp 显示** —— 于是它和邻居
 * 在视觉上重叠 17dp。
 *
 * 如果让每个段各自 `clickable`，重叠区域会被**后画的那个**吃掉。
 * 用户点的是「13:51 那根粉条」，实际命中的却是盖在它上面的下一段 ——
 * 不报错、不崩，只是每次点都进错面板，最难受的那种 bug。
 *
 * ## 解法：按分钟反查，而不是按矩形反查
 *
 * 模板段**首尾相接覆盖 0 → 1440**（这是模板的硬规则），所以：
 * 像素位置 → 分钟 → 找 `[startMin, endMin)` 包含它的那一段，**必然唯一命中**。
 * 视觉上多细都不影响，因为判定跟视觉高度完全无关。
 *
 * 这两个函数抽出来是为了能写单测 —— 它们在手势回调里，测不到就等于没验。
 */
object MinuteHit {

    /**
     * 色条上的纵向位置（dp，从色条顶端往下）→ 当天的第几分钟。
     *
     * 超出两端会被夹住：点在最上面一行算 0:00，点在最下面算 23:59。
     * **不返回 null** —— 用户点在色条范围内就该有结果，让点击「石沉大海」不可接受。
     *
     * @param dpPerMinute 一分钟多少 dp（模板页是 `HOUR_DP / 60` = 0.5）
     */
    fun minuteAt(yDp: Float, dpPerMinute: Float): Int {
        if (dpPerMinute <= 0f) return 0
        return (yDp / dpPerMinute).toInt().coerceIn(0, MINUTES_PER_DAY - 1)
    }

    /**
     * 这一分钟落在哪一段里。
     *
     * 区间是**左闭右开** `[start, end)` —— 这样 24:00 那一刻不会同时命中两段。
     *
     * 万一数据有缝（理论上不会，模板必须首尾相接），退化成「找起点最近的那段」，
     * 而不是返回 null。让用户点了没反应比点错更糟。
     */
    fun <T> pick(
        spans: List<T>,
        minute: Int,
        startOf: (T) -> Int,
        endOf: (T) -> Int,
    ): T? {
        if (spans.isEmpty()) return null
        spans.firstOrNull { minute >= startOf(it) && minute < endOf(it) }?.let { return it }
        // ⚠️ 兜底的「最近」必须按**到区间的距离**算，不能按「到起点」——
        //    按起点算会严重偏向前一段：段 A=[0,100)、B=[200,300)，
        //    第 110 分钟离 A 只差 10 分钟，但离 B 的起点还有 90 分钟，
        //    按起点排会选 B，等于把缝左边的点判给了右边。
        return spans.minByOrNull { s ->
            val a = startOf(s)
            val b = endOf(s)
            when {
                minute < a -> a - minute        // 在它前面
                minute >= b -> minute - b + 1   // 在它后面
                else -> 0
            }
        }
    }
}
