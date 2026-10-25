package com.misaka.timelog.domain

/**
 * 四张图的**画布几何** —— 纯数，不依赖 Compose。
 *
 * ## 为什么单独抠出来
 *
 * 这里踩过一次真机才会露头的坑：图上的**点击热区**（一排透明的等宽格子）
 * 是跟横轴那一列一一对应的，所以格子的四边留白必须跟画布上的 `PAD_*` **严格同比例**。
 * 当时图省事写成：
 *
 * ```kotlin
 * Spacer(Modifier.weight(PAD_L))                    // 30
 * repeat(n) { Box(Modifier.weight(1f)) }            // 7
 * Spacer(Modifier.weight(PAD_R))                    // 8
 * ```
 *
 * 横向的权重之和是 `30 + 7 + 8 = 45`，于是左边距吃掉整行的 **67%**，
 * 七个格子里最后 15% 宽的一条才有效。症状是「**图上点哪都没反应**」——
 * 不报错、不崩、截图也看不出来（热区是透明的），只能靠真机一点点试出来。
 *
 * 正确的分母是 **`VW`（画布宽度 350）**：左边距 `PAD_L`、每列 `(VW-PAD_L-PAD_R)/n`、右边距 `PAD_R`，
 * 加起来正好 350。
 *
 * ⚠️ 纵向**一直是对的**（`12 + 166 + 22 = 200 = VH`）—— 一模一样的两段代码，
 * 一段对一段错。所以这里把「权重加起来必须等于画布尺寸」做成了一条**不变量**，
 * 由 `ChartGeomTest` 穷举列数钉住。改留白、改列数都不可能再悄悄错位。
 *
 * 坐标系沿用原型的 SVG `viewBox`（350×200），这样这一层的每个数字都能跟原型逐行对照。
 */
object ChartGeom {

    /** 画布宽（原型 `viewBox` 的宽度）。 */
    const val VW = 350f

    /** 画布高（原型 `viewBox` 的高度）。 */
    const val VH = 200f

    /** 左边距 —— 给纵轴刻度那几个字（「24时」/「8天」）留的。 */
    const val PAD_L = 30f

    /** 右边距。 */
    const val PAD_R = 8f

    /** 上边距。 */
    const val PAD_T = 12f

    /** 下边距 —— 给横轴那一列标签（「一」/「15日」）留的。 */
    const val PAD_B = 22f

    /** 横轴上相邻两列的距离。 */
    fun colStep(n: Int): Float = (VW - PAD_L - PAD_R) / n

    /** 第 `i` 列的中心（画布坐标）。热区中心必须等于它。 */
    fun colCenterX(i: Int, n: Int): Float = PAD_L + colStep(n) * i + colStep(n) / 2f

    /**
     * 一行格子的**横向权重**：左边距 + 每列的权重 + 右边距必须加起来等于 [VW]。
     *
     * 纵向同理，见 [PAD_T] + [rowWeight] + [PAD_B] = [VH]。
     */
    fun colWeight(n: Int): Float = (VW - PAD_L - PAD_R) / n

    /** 中间那一行（就是热区那一行）的纵向权重。 */
    fun rowWeight(): Float = VH - PAD_T - PAD_B

    /** 热区的左边（占整块画布宽度的比例，0..1）。 */
    fun tapLeftFraction(): Float = PAD_L / VW

    /** 热区的右边（比例）。 */
    fun tapRightFraction(): Float = (VW - PAD_R) / VW

    /** 单个热区的宽度（比例）。 */
    fun tapWidthFraction(n: Int): Float = colWeight(n) / VW

    /** 热区的上边（比例）。 */
    fun tapTopFraction(): Float = PAD_T / VH

    /** 热区的下边（比例）。 */
    fun tapBottomFraction(): Float = (VH - PAD_B) / VH
}
