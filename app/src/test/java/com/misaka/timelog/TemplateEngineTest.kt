package com.misaka.timelog

import com.misaka.timelog.domain.TemplateEngine
import com.misaka.timelog.domain.TemplateLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板「懒结算」引擎 —— **纯 JVM，不碰 Android、不碰数据库**。
 *
 * ## 这里测的到底是哪句话
 *
 * > **今天还剩哪些「空白」该按模板填上？**
 *
 * 而这个问题的答案牵动的是整个 App 的地基：填错了会让同一段时间被两条记录占着，
 * 或者凭空造出一条点不进去的幽灵记录 —— 两种都**不报错、不崩**，
 * 只在几个月后以「统计数字对不上」的形式浮出来。
 *
 * ## 为什么这个文件一个 Robolectric 注解都没有
 *
 * 因为引擎的输入输出全是朴素数据类。所以这一整个文件的测试是**毫秒级的**，
 * 边界组合可以随便穷举 —— 段边界正好压在记录边界上、一段跨了三条记录、
 * 锁定的段刚跑完、模板缺了一块……
 *
 * 这些场景要是都得起 Room + Robolectric 才测得了，就一定会有人「先跳过、回头补」，
 * 然后就没有回头了。**能秒级跑的测试才会真的被写。**
 */
class TemplateEngineTest {

    // ---------------------------------------------------------------- 造数据

    /** 一条「占了时间的东西」。默认是「可被模板填的空白」。 */
    private fun span(id: String, a: Int, b: Int, fillable: Boolean = true) =
        TemplateEngine.Span(id = id, startMin = a, endMin = b, fillable = fillable)

    private fun seg(a: Int, b: Int, item: String?, cat: String? = null) =
        TemplateEngine.Seg(startMin = a, endMin = b, itemId = item, categoryId = cat)

    /** 把一条空白切成什么样，压成一行可读的字符串便于断言。 */
    private fun fmt(block: TemplateEngine.Block): String =
        "${block.from}-${block.to}:${block.itemId ?: "空白"}"

    private fun fmt(plan: TemplateEngine.Plan): List<List<String>> =
        plan.rewrites.map { rw -> rw.blocks.map(::fmt) }

    /**
     * 内置那套「工作日」模板 —— 跟 `SeedData` 里的逐字一致。
     *
     * ⚠️ 这里**故意抄一份**而不是从 `SeedData` 读：引擎的测试不该依赖种子的实现，
     * 两边一起改才是正常的（改的是同一套规则）。
     * 种子本身对不对，由 `TimelineTemplateTest` 端到端盯。
     */
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

    // ================================================================ 基本盘

    @Test
    fun `一整天的空白会被按段切开`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 1440)),
            segs = workday,
            cut = 1440,
        )

        assertEquals(1, plan.rewrites.size)
        assertEquals("g1", plan.rewrites[0].originId)
        assertEquals(
            listOf(
                "0-510:睡觉",
                "510-540:洗脸洗漱",
                "540-720:打工",
                "720-810:午休",
                "810-1050:打工",
                "1050-1110:开车通勤",
                "1110-1170:做饭",
                "1170-1380:陪伴伴侣",
                "1380-1440:编程",
            ),
            plan.rewrites[0].blocks.map(::fmt),
        )
    }

    @Test
    fun `分块必须首尾相接_中间不许留缝也不许重叠`() {
        val blocks = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 1440)),
            segs = workday,
            cut = 1440,
        ).rewrites[0].blocks

        assertEquals(0, blocks.first().from)
        assertEquals(1440, blocks.last().to)
        blocks.zipWithNext().forEach { (a, b) ->
            assertEquals("${a.to} 之后必须正好接着 ${b.from}", a.to, b.from)
        }
    }

    @Test
    fun `手动记过的那一段一根汗毛都不动`() {
        // 0-510 空白（睡觉时段）｜510-720 手动记的「打工」｜720-1440 空白
        val plan = TemplateEngine.settle(
            spans = listOf(
                span("gap-first", 0, 510),
                span("mine", 510, 720, fillable = false),
                span("gap-last", 720, 1440),
            ),
            segs = workday,
            cut = 1440,
        )

        assertEquals(listOf("gap-first", "gap-last"), plan.rewrites.map { it.originId })
        assertEquals(listOf("0-510:睡觉"), plan.rewrites[0].blocks.map(::fmt))
        // 720 起用「午休」接上 —— 注意它不该被并进上面那条手动记录里
        assertEquals("720-810:午休", fmt(plan.rewrites[1].blocks.first()))
    }

    @Test
    fun `只铺到 cut_未来一分钟都不铺`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 750)),
            segs = workday,
            cut = 750,
        )

        // 750 落在「720-810 午休」这一段里 → 最后一块只到 750，不许铺到 810
        assertEquals(
            listOf("0-510:睡觉", "510-540:洗脸洗漱", "540-720:打工", "720-750:午休"),
            plan.rewrites[0].blocks.map(::fmt),
        )
        assertEquals("最后一块必须停在 cut 上", 750, plan.rewrites[0].blocks.last().to)
    }

    @Test
    fun `cut 为零时什么都不做`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 1440)),
            segs = workday,
            cut = 0,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.lockExpired)
    }

    @Test
    fun `没有可填的段时一条 rewrite 都不产出`() {
        // 「整条记录都是空白，但模板段全指向被删掉的事项」→ 白改一场，跳过
        val broken = listOf(seg(0, 1440, null))
        val plan = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 1440)),
            segs = broken,
            cut = 1440,
        )

        assertTrue("一段都填不上就不该动它，否则只是白改 updatedAt", plan.isEmpty)
    }

    // ================================================================ 幽灵记录

    @Test
    fun `事项被删掉的段留成空白_绝不铺出幽灵记录`() {
        // 模板：[0,510) 睡觉（还在）｜[510,1440) 那件事被删了
        val segs = listOf(seg(0, 510, "睡觉", "rest"), seg(510, 1440, null))

        val plan = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 1440)),
            segs = segs,
            cut = 1440,
        )

        assertEquals(
            listOf("0-510:睡觉", "510-1440:空白"),
            plan.rewrites[0].blocks.map(::fmt),
        )
    }

    @Test
    fun `事项查不到时分类快照也必须一起清空`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("g1", 0, 1440)),
            segs = listOf(seg(0, 1440, null, cat = "rest")),
            cut = 1440,
        )

        // 全填不上 → 不该有 rewrite
        assertTrue(plan.isEmpty)
    }

    // ================================================================ 边界压在边界上

    @Test
    fun `段的边界正好压在记录的边界上时两边各归各的`() {
        // 记录：0-540 空白｜540-1440 空白。段边界正好落在 540。
        val plan = TemplateEngine.settle(
            spans = listOf(span("a", 0, 540), span("b", 540, 1440)),
            segs = workday,
            cut = 1440,
        )

        assertEquals("0-510:睡觉", fmt(plan.rewrites[0].blocks[0]))
        assertEquals("510-540:洗脸洗漱", fmt(plan.rewrites[0].blocks[1]))
        assertEquals("540-720:打工", fmt(plan.rewrites[1].blocks[0]))
    }

    @Test
    fun `一条记录可以横跨好几个段`() {
        // 720-1050 这一段里含 午休(720-810) + 打工(810-1050)
        val plan = TemplateEngine.settle(
            spans = listOf(span("mid", 720, 1050)),
            segs = workday,
            cut = 1440,
        )

        assertEquals(listOf("720-810:午休", "810-1050:打工"), plan.rewrites[0].blocks.map(::fmt))
    }

    @Test
    fun `记录停在段中间时最后一块只到记录终点`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("g", 600, 1000)),
            segs = workday,
            cut = 1440,
        )

        assertEquals(listOf("600-720:打工", "720-810:午休", "810-1000:打工"), plan.rewrites[0].blocks.map(::fmt))
    }

    @Test
    fun `模板自己铺的、已经结束的那条不会被重切`() {
        // 由调用方决定 fillable —— 引擎只认这个位
        val plan = TemplateEngine.settle(
            spans = listOf(span("settled", 0, 540, fillable = false), span("g", 540, 1440)),
            segs = workday,
            cut = 1440,
        )

        assertEquals(listOf("g"), plan.rewrites.map { it.originId })
    }

    @Test
    fun `进行中的模板段会被切开_因为它还在长`() {
        // 这条 endMin 是 cut（调用方给的），fillable = true（模板自己的、还在进行中）
        val plan = TemplateEngine.settle(
            spans = listOf(span("live", 700, 900, fillable = true)),
            segs = workday,
            cut = 900,
        )

        assertEquals(listOf("700-720:打工", "720-810:午休", "810-900:打工"), plan.rewrites[0].blocks.map(::fmt))
    }

    // ================================================================ 越界保护

    @Test
    fun `记录越过 cut 时宁可不动_绝不把它截断`() {
        // 正常流程不该出现（ensureDense 保证所有记录都停在 cut 之内）。
        // 真出现时如果按「只铺到 cut」处理，就要把它 UPDATE 短 ——
        // 被砍掉的那一截就凭空消失了。拿一个坏状态换另一个更坏的，不干。
        val plan = TemplateEngine.settle(
            spans = listOf(span("weird", 0, 1440)),
            segs = workday,
            cut = 600,
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `零长度的空白不会被处理`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("zero", 600, 600)),
            segs = workday,
            cut = 1440,
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `模板自己坏了_某段起止反了_也不会原地打转`() {
        // 段 [600, 600) 是坏的；[600,1440) 归最后一段
        val broken = listOf(seg(0, 600, "打工"), seg(600, 600, "坏段"), seg(600, 1440, "睡觉"))

        val plan = TemplateEngine.settle(
            spans = listOf(span("g", 0, 1440)),
            segs = broken,
            cut = 1440,
        )

        // 不卡死就是胜利；具体切法按「查不到就保持空白」来
        assertEquals(1440, plan.rewrites[0].blocks.last().to)
    }

    @Test
    fun `模板没密铺时查不到的那一段留成空白`() {
        // 模板只覆盖了 0-600，后面全是洞
        val plan = TemplateEngine.settle(
            spans = listOf(span("g", 0, 1440)),
            segs = listOf(seg(0, 600, "睡觉")),
            cut = 1440,
        )

        assertEquals(listOf("0-600:睡觉", "600-1440:空白"), plan.rewrites[0].blocks.map(::fmt))
    }

    // ================================================================ 锁定段

    @Test
    fun `锁定的那一段优先_运行中改模板不影响它`() {
        // 启动时锁住「12:00-14:00 午休」；之后你把模板改成了「开会」
        val lock = seg(720, 840, "午休")
        val changed = workday.map { if (it.startMin == 720) seg(720, 840, "开会") else it }

        val plan = TemplateEngine.settle(
            spans = listOf(span("g", 700, 860)),
            segs = changed,
            lock = lock,
            cut = 860,
        )

        assertEquals(
            listOf("700-720:打工", "720-840:午休", "840-860:打工"),
            plan.rewrites[0].blocks.map(::fmt),
        )
    }

    @Test
    fun `锁定段跑完之后才用新模板`() {
        val lock = seg(720, 840, "午休")
        val changed = workday.map { if (it.startMin == 720) seg(720, 840, "开会") else it }

        // 现在 15:00（900 分钟），锁定段在 840 就结束了 → 之后的空白用新模板
        val plan = TemplateEngine.settle(
            spans = listOf(span("g", 840, 900)),
            segs = changed,
            lock = lock,
            cut = 900,
        )

        assertEquals(listOf("840-900:打工"), plan.rewrites[0].blocks.map(::fmt))
    }

    @Test
    fun `cut 越过锁定段的终点就报告它已失效`() {
        val lock = seg(720, 840, "午休")

        assertFalse(
            TemplateEngine.settle(listOf(span("g", 0, 800)), workday, lock, cut = 800).lockExpired
        )
        assertTrue(
            TemplateEngine.settle(listOf(span("g", 0, 900)), workday, lock, cut = 900).lockExpired
        )
    }

    @Test
    fun `没有锁定段时永远不报失效`() {
        assertFalse(TemplateEngine.settle(listOf(span("g", 0, 1440)), workday, null, cut = 1440).lockExpired)
    }

    @Test
    fun `锁定段自己也可以指向一个被删掉的事项`() {
        val plan = TemplateEngine.settle(
            spans = listOf(span("g", 700, 860)),
            segs = workday,
            lock = seg(720, 840, null),
            cut = 860,
        )

        assertEquals(
            listOf("700-720:打工", "720-840:空白", "840-860:打工"),
            plan.rewrites[0].blocks.map(::fmt),
        )
    }

    // ================================================================ 找段

    @Test
    fun `找段_边界值归右边那一段`() {
        assertEquals("睡觉", TemplateEngine.segAt(0, workday)?.itemId)
        assertEquals("睡觉", TemplateEngine.segAt(509, workday)?.itemId)
        assertEquals("洗脸洗漱", TemplateEngine.segAt(510, workday)?.itemId)
        assertEquals("编程", TemplateEngine.segAt(1439, workday)?.itemId)
    }

    @Test
    fun `找段_查不到就给 null 而不是兜到最近的一段`() {
        assertNull(TemplateEngine.segAt(700, listOf(seg(0, 600, "睡觉"))))
    }

    // ================================================================ 锁定段编解码

    @Test
    fun `锁定段编解码能原样往返`() {
        val s = seg(720, 840, "午休", "rest")

        val back = TemplateLock.decode(TemplateLock.encode(s))

        assertEquals(s, back)
    }

    @Test
    fun `锁定段里的事项为空也能往返`() {
        val s = seg(720, 840, null, null)

        assertEquals(s, TemplateLock.decode(TemplateLock.encode(s)))
    }

    @Test
    fun `锁定段的脏数据一律退回没有锁定_而不是崩`() {
        // 列里可能是空的、可能是老版本格式、可能是别的什么
        assertNull(TemplateLock.decode(null))
        assertNull(TemplateLock.decode(""))
        assertNull(TemplateLock.decode("   "))
        assertNull(TemplateLock.decode("abc"))
        assertNull(TemplateLock.decode("720|840"))
        assertNull(TemplateLock.decode("x|840|打工|work"))
        assertNull(TemplateLock.decode("840|720|打工|work")) // 起止反了
    }
}
