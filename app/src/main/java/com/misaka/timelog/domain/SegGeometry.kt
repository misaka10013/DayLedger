package com.misaka.timelog.domain

/**
 * 模板色条上一段占多高（dp）。
 *
 * 抽成纯函数是为了**能被断言锁住**：色条是几何渲染，光靠肉眼看截图判断不了
 * 「相邻两段有没有重叠」，而这正是踩过的坑（见下）。
 *
 * ## ★ 为什么这里只有「可见性下限」、没有「可点性下限」
 *
 * 2026-10-18 一度把细段抬到 22dp，理由是「手指得有 22dp 的目标才点得中」。
 * 装机后被御坂的模板打脸：18:45→19:07（22 分钟）和 19:07→19:30（23 分钟）
 * 两段真实高度各 11dp，抬到 22dp 后**互相重叠 11dp**，两行名字糊成一团。
 *
 * 教训是两层：
 * 1. 段画得比真时间长 = 在时间轴上撒谎。而这个 App 的地基就是「时间轴不能说谎」
 *    （「缝隙可见」那条铁律的同一个根）。
 * 2. 「点不中」根本不该靠加粗来治 —— 点击已经改成**按 y 反算分钟再找覆盖段**
 *    （见 [MinuteHit]），整条色条都是热区，所以段只需要「看得见」。
 *
 * @param durationMin 这段真实占多少分钟
 * @param minDp 每分钟多少 dp（= 1 小时的高度 / 60）
 * @param minVisibleDp 可见性下限，通常 2dp —— 只保证「有像素」，不保证好点
 */
object SegGeometry {

    /**
     * 段的**可见性**下限（dp）——「有像素」的底线，不是「好点」的底线。
     *
     * ⚠️ 这个值 UI 和测试**共用同一个定义**。故意不各自写一份：
     * 分开写的话，把 UI 那份调大会让色条重叠，而测试因为拿着旧的小值照样绿 ——
     * 等于断言完全没锁住东西。共用才能做到「调大 → 分段重叠断言立刻变红」。
     *
     * 想调大之前先读 [SegGeometryTest] 里那条「把抬高改回 22dp 会重叠 9dp 以上」。
     */
    const val VISIBLE_MIN_DP = 2f

    /**
     * 段与段之间留的缝（dp）。
     *
     * 不留缝的话相邻两段同色时会糊成一整条，看不出边界 ——
     * 而「能看出边界」正是这个色条存在的意义。
     */
    const val GAP_DP = 1f

    fun heightDp(durationMin: Int, minDp: Float, minVisibleDp: Float = VISIBLE_MIN_DP): Float =
        maxOf(durationMin * minDp - GAP_DP, minVisibleDp)

    /**
     * 这一段的文字要不要带上「起止时间」那一行。
     *
     * ⚠️ **按时长判，不按显示高度判。** 踩过一次：写成 `heightDp >= 一小时的高度` 时，
     * 60 分钟的段算出来是 `60 × 0.5 - 1 = 29dp`（那 1dp 是段间缝），
     * 29 < 30 就判成 false —— 于是「做饭」「玩手机」这种整点一小时的事项不显示时间，
     * 而 249 分钟的「打工」显示。看着像随机漏了几个，实际差 1dp。
     *
     * 「满 1 小时」是个**时间**概念，就别拿像素去表达它。
     */
    fun showsTimeLine(durationMin: Int): Boolean = durationMin >= 60

    /** 某一段在色条上的显示区间 `[top, top + height)`，单位 dp。 */
    fun rangeOf(
        startMin: Int,
        durationMin: Int,
        minDp: Float,
        minVisibleDp: Float = VISIBLE_MIN_DP,
    ): ClosedFloatingPointRange<Float> {
        val top = startMin * minDp
        return top..(top + heightDp(durationMin, minDp, minVisibleDp))
    }
}
