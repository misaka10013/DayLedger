package com.misaka.timelog

import com.misaka.timelog.domain.SegGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板色条的**几何**：每一段占多高、相邻两段会不会叠在一起。
 *
 * ## 这一组为什么必须存在
 *
 * 2026-10-18 装机验出一条只有几何才能暴露的 bug：18:45→19:07（22 分钟）
 * 和 19:07→19:30（23 分钟）两段的真实高度各约 11dp，为了让手指好点，
 * 它们被抬到 22dp 显示 → **互相重叠 11dp** → 两行名字直接糊成一团。
 *
 * 这类问题的共同点：
 * - 不崩溃、不报错、数据全对（时长、段数、首尾相接都没问题）
 * - 截图上「看起来像有两行字」，但光靠眼看判断不了重叠了几 dp
 * - 只有把坐标抠出来算差值，才知道到底叠没叠
 *
 * 所以这里只断言**坐标**，不碰任何业务数据。
 */
class SegGeometryTest {

    /** 模板页现在的缩放：30dp / 小时。 */
    private val minDp = 0.5f

    /**
     * 可见性下限 —— 直接用 domain 里那个常量，**不在这里另写一份**。
     *
     * 各写一份的话，谁把 UI 那份从 2dp 调到 22dp，下面那条「相邻段不重叠」
     * 会拿旧的小值继续绿着，等于断言什么都没锁住。
     */
    private val minVisibleDp = SegGeometry.VISIBLE_MIN_DP

    /**
     * 御坂真机上的那份模板（13 段，首尾相接覆盖 00:00 → 24:00）。
     *
     * 用真实数据而不是编的，因为要复现的正是「22 分钟和 23 分钟挨在一起」
     * 这种真实排布 —— 随手编一组等长的段是测不出来的。
     */
    private val realTemplate = listOf(
        Triple(0, 510, "睡觉"),
        Triple(510, 30, "洗护"),
        Triple(540, 180, "打工"),
        Triple(720, 30, "做饭"),
        Triple(750, 25, "吃饭"),
        Triple(775, 7, "吃饭"),
        Triple(782, 49, "睡觉"),
        Triple(831, 249, "打工"),
        Triple(1080, 45, "做饭"),
        Triple(1125, 22, "清洁家务"),
        Triple(1147, 23, "洗护"),
        Triple(1170, 210, "恋爱相伴"),
        Triple(1380, 60, "玩手机"),
    )

    private fun rangeOf(seg: Triple<Int, Int, String>) =
        SegGeometry.rangeOf(seg.first, seg.second, minDp, minVisibleDp)

    // ------------------------------------------------------------ 单段高度

    @Test
    fun `一小时的段正好 30dp 减一道缝`() {
        // 60 分钟 × 0.5 - 1 = 29dp。缝是给「能看出两段边界」留的。
        assertEquals(29f, SegGeometry.heightDp(60, minDp, minVisibleDp), 0.001f)
    }

    @Test
    fun `22 分钟的段按真实高度画`() {
        // 22 × 0.5 - 1 = 10dp。以前这里会被抬到 22dp，正是重叠的根源。
        assertEquals(10f, SegGeometry.heightDp(22, minDp, minVisibleDp), 0.001f)
    }

    @Test
    fun `七分钟的段缩到可见下限`() {
        // 7 × 0.5 - 1 = 2.5dp，本来就比 2dp 高，不该被改动
        assertEquals(2.5f, SegGeometry.heightDp(7, minDp, minVisibleDp), 0.001f)
    }

    @Test
    fun `一分钟的段兜到可见下限`() {
        // 1 × 0.5 - 1 = -0.5 → 负数，必须兜到 2dp，否则画不出来
        assertEquals(2f, SegGeometry.heightDp(1, minDp, minVisibleDp), 0.001f)
    }

    // ------------------------------------------------------ ★ 相邻段不重叠

    @Test
    fun `真实模板的相邻段一个都不重叠`() {
        // ★ 这是本文件的核心断言。段画得比真时间长 → 盖住邻居 →
        //   「看到的」和「点到的」脱节，而这正是抬高方案的原罪。
        val overlaps = mutableListOf<String>()
        realTemplate.zipWithNext().forEach { (a, b) ->
            val endOfA = rangeOf(a).endInclusive
            val startOfB = rangeOf(b).start
            if (endOfA > startOfB + 0.001f) {
                overlaps += "${a.third}(至 ${endOfA}dp) 压住了 ${b.third}(从 ${startOfB}dp 起)"
            }
        }
        assertTrue("相邻段重叠了：$overlaps", overlaps.isEmpty())
    }

    @Test
    fun `22分钟与23分钟这两段不再互相压`() {
        // 就是截图里糊成一团的那两段。真实高度 10dp 和 10.5dp，起始相隔 11dp。
        val a = SegGeometry.rangeOf(1125, 22, minDp, minVisibleDp)
        val b = SegGeometry.rangeOf(1147, 23, minDp, minVisibleDp)
        assertTrue("清洁家务的下沿 ${a.endInclusive} 越过了洗护的上沿 ${b.start}", a.endInclusive <= b.start)
    }

    @Test
    fun `把抬高改回 22dp 会让这两段重叠 9dp 以上`() {
        // 回归断言：钉住「为什么不能用 22dp」。
        // 谁哪天想把 MIN_VISIBLE_DP 调大，这条会先变红。
        val lifted = 22f
        val a = SegGeometry.rangeOf(1125, 22, minDp, lifted)
        val b = SegGeometry.rangeOf(1147, 23, minDp, lifted)
        val overlap = a.endInclusive - b.start
        assertTrue("抬高后应该重叠，实测重叠 $overlap dp", overlap > 9f)
    }

    @Test
    fun `段的下沿不超过它真实结束的那一刻`() {
        // 换一种说法守同一条底线：除了兜底的极小段，
        // 任何段的显示下沿都不能超过「它结束的分钟 × 每分钟 dp」。
        realTemplate.forEach { seg ->
            val (start, dur, name) = seg
            val endDp = (start + dur) * minDp
            val shown = rangeOf(seg).endInclusive
            assertTrue(
                "$name 显示到 ${shown}dp，但它 ${start + dur} 分钟就该结束了（${endDp}dp）",
                shown <= endDp + 0.001f,
            )
        }
    }

    // ------------------------------------------------------ 满 1 小时写时间

    @Test
    fun `整点一小时的事项要显示时间`() {
        // 坑在这里：60 分钟的段画出来是 29dp（扣掉 1dp 段间缝），
        // 如果拿「显示高度 >= 30dp」去判就会漏掉「做饭」「玩手机」这类整点一小时的事项。
        assertEquals(29f, SegGeometry.heightDp(60, minDp, minVisibleDp), 0.001f)
        assertTrue("60 分钟必须显示时间行", SegGeometry.showsTimeLine(60))
    }

    @Test
    fun `不到一小时的不显示时间行`() {
        assertEquals(false, SegGeometry.showsTimeLine(59))
        assertEquals(false, SegGeometry.showsTimeLine(22))
        assertEquals(false, SegGeometry.showsTimeLine(7))
    }

    @Test
    fun `真实模板里该显示时间的就是那五段`() {
        // 睡觉 510 / 打工 180 / 打工 249 / 恋爱相伴 210 / 玩手机 60
        val withTime = realTemplate.filter { SegGeometry.showsTimeLine(it.second) }.map { it.third }
        assertEquals(listOf("睡觉", "打工", "打工", "恋爱相伴", "玩手机"), withTime)
    }

    // ---------------------------------------------------------------- 总量

    @Test
    fun `最末一段的下沿就是 24 小时的刻度`() {
        // 最后一段 23:00→24:00，下沿应该正好压在 720dp 的刻度线内侧
        val last = realTemplate.last()
        val bottom = rangeOf(last).endInclusive
        assertEquals(720f - SegGeometry.GAP_DP, bottom, 0.001f)
    }

    @Test
    fun `24 小时的刻度高度是 720dp`() {
        // 跟 TemplateScreen 的 BAR_DP 对齐：1440 分钟 × 0.5
        assertEquals(720f, 1440 * minDp, 0.001f)
    }
}
