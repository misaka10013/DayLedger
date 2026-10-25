package com.misaka.timelog

import com.misaka.timelog.domain.TemplateEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板段的编辑规则 —— **纯 JVM，不碰 Android、不碰数据库**。
 *
 * ## 这个文件真正在守的那一句话
 *
 * > **模板必须首尾相接、严丝合缝地铺满 `00:00 → 24:00`。**
 *
 * 破了它不会崩、不会报错，只会让模板**从那道缝开始静静地不再铺写** ——
 * 你要过好几天，某天发现"今天怎么少了一块"，才会往回找。
 * 这类错误只能靠断言盯着，人眼看色条是看不出来的（一条 3 分钟的缝 = 1dp 高）。
 *
 * ## 两处专门钉死的旧伤
 *
 * 1. **`挪起点_退15再进15_回到原位`** —— 原型第 17 轮在这里栽过，
 *    症状是 `12:00 → 11:45 → 11:44`，回不去。根因是夹范围时用了「下一步要改的那个值」。
 * 2. **`穷举每一步挪动`** —— 单点测几组容易漏掉夹逼的边界组合（顶到邻居、只剩 1 分钟），
 *    所以干脆把「每一段 × 每条边界 × 一组正负距离」全跑一遍。
 */
class TemplateEditTest {

    // ---------------------------------------------------------------- 造数据

    private fun seg(a: Int, b: Int, item: String? = "打工") = TemplateEdit.Seg(a, b, item)

    /** 内置那套「工作日」模板 —— 跟 `SeedData` 里逐字一致（段边界是它的真实形状）。 */
    private val workday = listOf(
        seg(0, 510, "睡觉"),
        seg(510, 540, "洗脸洗漱"),
        seg(540, 720, "打工"),
        seg(720, 810, "午休"),
        seg(810, 1050, "打工"),
        seg(1050, 1110, "开车通勤"),
        seg(1110, 1170, "做饭"),
        seg(1170, 1380, "陪伴伴侣"),
        seg(1380, 1440, "编程"),
    )

    /** 压成一行可读的字符串，断言失败时一眼看得懂。 */
    private fun fmt(segs: List<TemplateEdit.Seg>): String =
        segs.joinToString(" | ") { "${it.startMin}-${it.endMin}:${it.itemId ?: "空"}" }

    private fun check(segs: List<TemplateEdit.Seg>) = TemplateEdit.check(segs)

    // ================================================================ 密铺不变式

    @Test
    fun `内置的工作日模板_本身是密铺的`() {
        assertEquals("", check(workday))
    }

    @Test
    fun `check_能认出空模板`() {
        assertEquals("模板是空的", check(emptyList()))
    }

    @Test
    fun `check_能认出第一段不从零点开始`() {
        assertEquals("第一段不从 00:00 开始", check(listOf(seg(10, 100), seg(100, 1440))))
    }

    @Test
    fun `check_能认出最后一段不到24点`() {
        assertEquals("最后一段不到 24:00", check(listOf(seg(0, 100), seg(100, 1400))))
    }

    @Test
    fun `check_能认出中间接不上`() {
        // 100 之后直接跳到 120 —— 中间那 20 分钟不属于任何一段
        assertEquals("第 2 段和上一段接不上", check(listOf(seg(0, 100), seg(120, 1440))))
    }

    @Test
    fun `check_能认出重叠`() {
        // 第 2 段从 50 开始，但第 1 段到 100 才结束 → 50..100 被两段同时占着
        assertEquals("第 2 段和上一段接不上", check(listOf(seg(0, 100), seg(50, 1440))))
    }

    @Test
    fun `check_能认出零长度段`() {
        // ⚠️ 这一条特别值：`[0,0] + [0,1440]` 首尾相接得完美无缺，
        //    「接不上」那道检查根本拦不住它，但第一段在色条上**画不出来** ——
        //    模型里有、眼睛看不见，等于凭空少一段。
        assertEquals("第 1 段是零长度的", check(listOf(seg(0, 0), seg(0, 1440))))
    }

    // ================================================================ 挪边界

    @Test
    fun `挪起点_上一段的终点跟着走`() {
        var segs = listOf(seg(0, 720, "上午"), seg(720, 1440, "下午"))
        segs = TemplateEdit.moveEdge(segs, 1, TemplateEdit.Edge.Start, -30)!!

        assertEquals("0-690:上午 | 690-1440:下午", fmt(segs))
        assertEquals("", check(segs))
    }

    @Test
    fun `挪终点_下一段的起点跟着走`() {
        var segs = listOf(seg(0, 720, "上午"), seg(720, 1440, "下午"))
        segs = TemplateEdit.moveEdge(segs, 0, TemplateEdit.Edge.End, 30)!!

        assertEquals("0-750:上午 | 750-1440:下午", fmt(segs))
        assertEquals("", check(segs))
    }

    /**
     * ★ 这条是**旧伤复发检测**。
     *
     * 原型第 17 轮在这里栽过：挪起点时拿「上一段的终点」当上界夹范围，
     * 而那个值**正是这一步要改的东西** —— 于是第二下从被改小的值出发，
     * 实测 `12:00 → 11:45 → 11:44`，退 15 分钟之后再也进不去。
     *
     * 现在上界用的是**自己的终点**（本函数不碰它），所以来回一趟必须回到原位。
     */
    @Test
    fun `挪起点_退15再进15_回到原位`() {
        var segs = listOf(seg(0, 720, "上午"), seg(720, 1440, "下午"))

        segs = TemplateEdit.moveEdge(segs, 1, TemplateEdit.Edge.Start, -15)!!
        assertEquals("退了 15 分钟", 705, segs[1].startMin)
        assertEquals("上一段的终点跟着退", 705, segs[0].endMin)

        segs = TemplateEdit.moveEdge(segs, 1, TemplateEdit.Edge.Start, 15)!!
        assertEquals("再进 15 分钟应该回到 720", 720, segs[1].startMin)
        assertEquals("上一段的终点也跟着回来", 720, segs[0].endMin)
    }

    @Test
    fun `挪终点_进15再退15_回到原位`() {
        var segs = listOf(seg(0, 720, "上午"), seg(720, 1440, "下午"))

        segs = TemplateEdit.moveEdge(segs, 0, TemplateEdit.Edge.End, 15)!!
        assertEquals(735, segs[0].endMin)
        assertEquals(735, segs[1].startMin)

        segs = TemplateEdit.moveEdge(segs, 0, TemplateEdit.Edge.End, -15)!!
        assertEquals(720, segs[0].endMin)
        assertEquals(720, segs[1].startMin)
    }

    @Test
    fun `第一段的起点挪不动`() {
        assertNull(TemplateEdit.moveEdge(workday, 0, TemplateEdit.Edge.Start, -15))
        assertNull(TemplateEdit.moveEdge(workday, 0, TemplateEdit.Edge.Start, 15))
    }

    @Test
    fun `最后一段的终点挪不动`() {
        val last = workday.lastIndex
        assertNull(TemplateEdit.moveEdge(workday, last, TemplateEdit.Edge.End, -15))
        assertNull(TemplateEdit.moveEdge(workday, last, TemplateEdit.Edge.End, 15))
    }

    @Test
    fun `挪起点_顶到上一段时返回空`() {
        // 两段各 1 分钟：第 1 段 0-1、第 2 段 1-1440。
        // 第 2 段的起点往左挪 1 分钟就顶到第 1 段的起点（0）了 —— 上一段会变成零长度。
        val segs = listOf(seg(0, 1, "甲"), seg(1, 1440, "乙"))
        assertNull(TemplateEdit.moveEdge(segs, 1, TemplateEdit.Edge.Start, -1))
    }

    @Test
    fun `挪终点_顶到下一段时返回空`() {
        val segs = listOf(seg(0, 1439, "甲"), seg(1439, 1440, "乙"))
        assertNull(TemplateEdit.moveEdge(segs, 0, TemplateEdit.Edge.End, 1))
    }

    @Test
    fun `挪起点_顶到自己的终点时返回空_段不会变成零长度`() {
        // 第 2 段只有 1 分钟（720-721）：起点往右挪就和自己终点重合了
        val segs = listOf(seg(0, 720, "上午"), seg(720, 721, "一秒"), seg(721, 1440, "下午"))
        assertNull(TemplateEdit.moveEdge(segs, 1, TemplateEdit.Edge.Start, 1))
    }

    @Test
    fun `挪一个不存在的段_返回空而不是崩`() {
        assertNull(TemplateEdit.moveEdge(workday, 99, TemplateEdit.Edge.Start, 15))
        assertNull(TemplateEdit.moveEdge(workday, -1, TemplateEdit.Edge.End, 15))
    }

    @Test
    fun `超出范围的距离会被夹到极限_而不是把段挪坏`() {
        // 想让第 2 段起点一路退到负数 —— 应该停在极限（上一段留 1 分钟），
        // 而不是真的算出负数
        val segs = listOf(seg(0, 720, "上午"), seg(720, 1440, "下午"))
        val moved = TemplateEdit.moveEdge(segs, 1, TemplateEdit.Edge.Start, -9999)!!

        assertEquals("第 2 段停在 1（上一段留 1 分钟）", 1, moved[1].startMin)
        assertEquals(1, moved[0].endMin)
        assertEquals("", check(moved))
    }

    // ================================================================ 拆

    @Test
    fun `拆段_从中间切开_后半段沿用同一事项`() {
        val segs = listOf(seg(0, 100, "上午"), seg(100, 1440, "下午"))
        val split = TemplateEdit.split(segs, 0)!!

        assertEquals("0-50:上午 | 50-100:上午 | 100-1440:下午", fmt(split))
        assertEquals("", check(split))
        assertEquals("拆出来的新段沿用同一个事项", "上午", split[1].itemId)
    }

    @Test
    fun `拆段_奇数分钟_前短后长`() {
        // 5 分钟 → 2 + 3（`(5)/2 = 2`，整数除法对正数就是 floor）
        val segs = listOf(seg(0, 5, "甲"), seg(5, 1440, "乙"))
        val split = TemplateEdit.split(segs, 0)!!

        assertEquals("0-2:甲 | 2-5:甲 | 5-1440:乙", fmt(split))
        assertEquals("", check(split))
    }

    @Test
    fun `拆段_刚好两分钟_拆成两个一分钟`() {
        val segs = listOf(seg(0, 2, "甲"), seg(2, 1440, "乙"))
        val split = TemplateEdit.split(segs, 0)!!

        assertEquals("0-1:甲 | 1-2:甲 | 2-1440:乙", fmt(split))
        assertEquals("", check(split))
    }

    @Test
    fun `拆段_只有一分钟_拆不开`() {
        val segs = listOf(seg(0, 1, "甲"), seg(1, 1440, "乙"))
        assertNull(TemplateEdit.split(segs, 0))
    }

    // ================================================================ 删

    @Test
    fun `删中间那段_时长并给上一段`() {
        val segs = listOf(seg(0, 100, "甲"), seg(100, 200, "乙"), seg(200, 1440, "丙"))
        val after = TemplateEdit.delete(segs, 1)!!

        assertEquals("0-200:甲 | 200-1440:丙", fmt(after))
        assertEquals("", check(after))
    }

    @Test
    fun `删第一段_时长并给下一段`() {
        val segs = listOf(seg(0, 100, "甲"), seg(100, 200, "乙"), seg(200, 1440, "丙"))
        val after = TemplateEdit.delete(segs, 0)!!

        assertEquals("0-200:乙 | 200-1440:丙", fmt(after))
        assertEquals("", check(after))
    }

    @Test
    fun `删最后一段_时长并给上一段`() {
        val segs = listOf(seg(0, 100, "甲"), seg(100, 200, "乙"), seg(200, 1440, "丙"))
        val after = TemplateEdit.delete(segs, 2)!!

        assertEquals("0-100:甲 | 100-1440:乙", fmt(after))
        assertEquals("", check(after))
    }

    @Test
    fun `只剩一段时删不掉`() {
        assertNull(TemplateEdit.delete(listOf(seg(0, 1440, "整天")), 0))
    }

    // ================================================================ 换事项

    @Test
    fun `换事项_时间一点不动`() {
        val after = TemplateEdit.setItem(workday, 2, "开会")

        assertEquals("打工", workday[2].itemId)
        assertEquals("开会", after[2].itemId)
        // ⚠️ 只比**时间**，不比事项名 —— 换的就是事项，名字当然不一样。
        //    拿 fmt 比整个列表会把 itemId 也比进去，那条断言自相矛盾。
        assertEquals(
            "每一段的起止分钟都必须和原来一模一样",
            workday.map { it.startMin to it.endMin },
            after.map { it.startMin to it.endMin },
        )
        assertEquals("", check(after))
    }

    @Test
    fun `换事项_可以换回未指派_用于标记悬空`() {
        val after = TemplateEdit.setItem(workday, 3, null)

        assertEquals(null, after[3].itemId)
        assertEquals("时间仍然原样", "720-810", "${after[3].startMin}-${after[3].endMin}")
        assertEquals("", check(after))
    }

    // ================================================================ 穷举

    /**
     * ★ 把「每一段 × 每条边界 × 一组正负距离」全跑一遍。
     *
     * 单点测几组容易漏掉夹逼的边界（顶到邻居、只剩 1 分钟、邻居只剩 1 分钟）——
     * 而那些恰恰是最容易把密铺弄破的地方。这个循环跑一遍是毫秒级的，
     * 没有任何理由不跑。
     */
    @Test
    fun `穷举每一步挪动_没有任何一步能把密铺弄破`() {
        for (i in workday.indices) {
            for (edge in TemplateEdit.Edge.entries) {
                for (d in listOf(-1440, -300, -60, -15, -1, 1, 15, 60, 300, 1440)) {
                    val next = TemplateEdit.moveEdge(workday, i, edge, d) ?: continue
                    assertEquals(
                        "第 $i 段 ($edge) 挪 $d 分钟之后密铺破了：${fmt(next)}",
                        "",
                        check(next),
                    )
                }
            }
        }
    }

    @Test
    fun `穷举每一段的拆分和删除_密铺都保持`() {
        for (i in workday.indices) {
            TemplateEdit.split(workday, i)?.let {
                assertEquals("第 $i 段拆分后密铺破了", "", check(it))
                assertEquals("拆完应该多一段", workday.size + 1, it.size)
            }
            TemplateEdit.delete(workday, i)?.let {
                assertEquals("第 $i 段删除后密铺破了", "", check(it))
                assertEquals("删完应该少一段", workday.size - 1, it.size)
            }
        }
    }

    /**
     * ★ 连点两下（模拟用户疯狂点 ＋15）之后，段的总数、总时长、密铺都不许变。
     *
     * 配置页最常见的手势就是连点，而「挪边界」是唯一一个**同时改两段**的操作 ——
     * 它出错的方式是「有的段被改了、邻居没跟上」，而那种错在色条上看着很正常。
     */
    @Test
    fun `连点十下同一方向_段数和总时长都不变`() {
        var segs = workday
        repeat(10) {
            segs = TemplateEdit.moveEdge(segs, 2, TemplateEdit.Edge.End, 15) ?: segs
        }

        assertEquals(workday.size, segs.size)
        assertEquals("一天的分钟数必须还是 1440", 1440, segs.sumOf { it.endMin - it.startMin })
        assertEquals("", check(segs))
        assertTrue("相邻两段仍然首尾相接", segs.zipWithNext().all { (a, b) -> a.endMin == b.startMin })
    }

    // ================================================================ 星期指派

    @Test
    fun `位掩码的位号是_周一起_周日尾`() {
        // 跟 DayKey.weekdayBit 是同一套口径（bit0 = 周一）。
        // ⚠️ 别跟 java.util.Calendar 那套混了（那边周日 = 0）。
        assertEquals(1, TemplateEdit.weekdayBit(0))
        assertEquals(2, TemplateEdit.weekdayBit(1))
        assertEquals(64, TemplateEdit.weekdayBit(6))
    }

    @Test
    fun `工作日掩码就是31`() {
        var mask = 0
        (0..4).forEach { mask = TemplateEdit.toggleWeekday(mask, it) }
        assertEquals(31, mask)
        assertEquals(TemplateEdit.WORKDAYS, mask)
    }

    @Test
    fun `点一下亮_再点一下灭`() {
        val on = TemplateEdit.toggleWeekday(0, 2)
        assertEquals(4, on)
        assertEquals(0, TemplateEdit.toggleWeekday(on, 2))
    }

    @Test
    fun `七天全亮是127`() {
        val all = (0..6).fold(0) { acc, i -> TemplateEdit.toggleWeekday(acc, i) }
        assertEquals(127, all)
        assertEquals(TemplateEdit.FULL_WEEK, all)
        assertEquals(7, TemplateEdit.weekdayCount(all))
    }

    @Test
    fun `指派文案_三种常见情况给短说法`() {
        assertEquals("没有任何一天", TemplateEdit.weekdaysText(0))
        assertEquals("每天", TemplateEdit.weekdaysText(127))
        assertEquals("工作日", TemplateEdit.weekdaysText(31))
    }

    @Test
    fun `指派文案_其它组合逐天列出来`() {
        // 周一 + 周三 + 周日 = 1 + 4 + 64 = 69
        assertEquals("周一 / 周三 / 周日", TemplateEdit.weekdaysText(69))
    }

    @Test
    fun `设置页那个短版_不带周字且不换行`() {
        assertEquals("没指派", TemplateEdit.weekdaysShort(0))
        assertEquals("每天", TemplateEdit.weekdaysShort(127))
        assertEquals("工作日", TemplateEdit.weekdaysShort(31))
        assertEquals("一三", TemplateEdit.weekdaysShort(1 + 4))
    }

    @Test
    fun `高位垃圾会被掩掉`() {
        // 传进来一个带了第 8 位的掩码 —— 只保留低 7 位
        assertEquals("一", TemplateEdit.weekdaysShort(1 or 0b1000_0000))
    }

    @Test
    fun `星期单字_周一起`() {
        assertEquals("一", TemplateEdit.weekdayCn(0))
        assertEquals("六", TemplateEdit.weekdayCn(5))
        assertEquals("日", TemplateEdit.weekdayCn(6))
        assertEquals("越界会夹住而不是崩", "日", TemplateEdit.weekdayCn(99))
    }

    // ================================================================ 查段

    @Test
    fun `按分钟查段_左闭右开`() {
        assertEquals(0, TemplateEdit.segIndexAt(workday, 0))
        assertEquals(0, TemplateEdit.segIndexAt(workday, 509))
        assertEquals(1, TemplateEdit.segIndexAt(workday, 510))
        assertEquals(1, TemplateEdit.segIndexAt(workday, 539))
        assertEquals(2, TemplateEdit.segIndexAt(workday, 540))
    }

    @Test
    fun `24点整点归最后一段`() {
        assertEquals(workday.lastIndex, TemplateEdit.segIndexAt(workday, 1440))
    }
}
