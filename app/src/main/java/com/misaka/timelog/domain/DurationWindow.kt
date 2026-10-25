package com.misaka.timelog.domain

/**
 * 时长滑块的**可拖动范围**。
 *
 * ## 这个类存在的唯一理由：把「精度」变成一个能断言的数
 *
 * 「滑块好不好用」这句话没法测，但「41 分钟的记录，手指移 1dp 会跳几分钟」可以。
 * 抽出来之后就能写一条断言把它钉死，而不是装到手机上用手指试。
 *
 * ## 为什么不是 1 分钟 ~ 全天
 *
 * 见 `TimeEditSheet.DurationSlider` 的注释：全天跨度下 5dp ≈ 27 分钟，
 * 一条 41 分钟的记录离最左边只有 7dp，**物理上拖不到 45 分钟**。
 * 所以范围按「当前时长的 1/4 ~ 4 倍」缩放 —— 短记录自动精细、长记录自动粗放。
 */
object DurationWindow {

    /** 窗口相对锚点的缩放倍数。1/4 ~ 4 倍覆盖了「大幅缩短」和「大幅延长」两种需求。 */
    const val SCALE = 4

    /**
     * 算出一条记录能拖动的时长范围 `[lo, hi]`（单位分钟）。
     *
     * @param anchor 打开面板那一刻的时长 —— **窗口锚在这里，不跟着当前值跑**。
     *   跟着当前值跑的话，滑块会永远居中在过去的位置上，变成「怎么拖都拖不到头」的橡皮筋。
     * @param cur 当前时长。它被 ± 按钮推到窗口外时，窗口要扩大去容纳它，
     *   否则会出现「数字变了但滑块纹丝不动」。
     * @param maxLen 物理上限 = `cut - start`（不许拖到未来去）。
     */
    fun of(anchor: Int, cur: Int, maxLen: Int): IntRange {
        val max = maxLen.coerceAtLeast(1)
        val a = anchor.coerceIn(1, max)
        val c = cur.coerceIn(1, max)

        val lo = maxOf(1, minOf(a / SCALE, c))
        val hi = minOf(max, maxOf(a * SCALE.toLong(), c.toLong()).toInt())
        // 滑块两端不能重合 —— 重合的 Slider 没有可拖区间，会退化成一个死控件
        return lo..hi.coerceAtLeast(lo + 1)
    }

    /**
     * 在这个窗口里，手指移动 1dp 会让时长跳几分钟（越小越准）。
     *
     * @param widthDp 滑块实际能拖动的宽度。真机上大约 260dp。
     */
    fun minutesPerDp(range: IntRange, widthDp: Float): Float {
        if (widthDp <= 0f) return Float.MAX_VALUE
        val span = (range.last - range.first).coerceAtLeast(1)
        return span / widthDp
    }
}
