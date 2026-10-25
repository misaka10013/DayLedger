package com.misaka.timelog

import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.domain.MINUTES_PER_DAY
import com.misaka.timelog.domain.Now
import com.misaka.timelog.domain.StatsCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计口径的穷举测试 —— **纯 JVM，不碰数据库、不碰 Android**。
 *
 * ## 为什么这一层值得写这么多条
 *
 * 统计的错误有一模一样的性格：**不报错、不崩溃**，只会在某天你盯着图觉得
 * 「怎么少了两个小时」时才浮出来，而那时已经查不出是哪条口径歪的。
 *
 * 所以这里盯的都是**口径本身**，而且尽量用「穷举 + 恒等式」的方式钉：
 *
 * | 盯什么 | 怎么钉 |
 * |---|---|
 * | 「今天」和「漏记」别混 | 恒等式 `rec + gap == total`、`total + notYet == 1440`，逐分钟扫一遍 |
 * | ① 和 ③ 别打架 | 断言「① 的灰带厚度 == ③ 的灰柱高度」（原型踩过一次） |
 * | ③ 别被顺手合并 | 断言上午下午两段「打工」是**两段** |
 * | 顺序别按时长排 | 故意造「时间短的那个排前面」 |
 * | 曲线别过冲 | 穷举 3^3..3^6 共 1080 条陡变序列，采样贝塞尔，断言不越界 |
 */
class StatsCalcTest {

    // ============================================================ 造数据的小工具

    /** 固定「今天是 2026-10-14」—— 那天是周三，所以它那一周是 10/12(一)..10/18(日)。 */
    private val today = "2026-10-14"
    private val yesterday = "2026-10-13"

    private fun nowAt(minute: Int = 14 * 60 + 30) = Now(epochMillis = 0L, dayKey = today, minuteOfDay = minute)

    /**
     * 事项字典。测试里**「事项 id 就是名字」**，省掉一层映射。
     *
     * ⚠️ 查不到的名字（比如「不存在的事项」）会走到「事项已删除」那条分支 —— 这是故意的。
     */
    private val colors = mapOf(
        "打工" to "#8A9A3C",
        "看书" to "#DE2E63",
        "睡觉" to "#6C7AE0",
    )

    /**
     * 「这条记录在统计里长什么样」—— 生产代码里由 ViewModel 拿事项目录拼，
     * 这里用一条假字典替掉。写成 `val` 而不是 `fun` 是为了能直接当参数传。
     */
    private val info: (RecordEntity) -> StatsCalc.Info = { r ->
        StatsCalc.info(
            r,
            nameOf = { id -> if (id in colors) id else null },
            colorOf = { id -> colors[id] },
        )
    }

    private fun rec(
        day: String,
        a: Int,
        b: Int?,
        item: String? = null,
        source: RecordSource = if (item == null) RecordSource.GAP else RecordSource.MANUAL,
    ) = RecordEntity(
        id = "r-$day-$a",
        day = day,
        startMin = a,
        endMin = b,
        itemId = item,
        categoryIdSnap = null,
        source = source,
        chainId = null,
        noTemplate = false,
        note = "",
        updatedAt = 0L,
        deleted = false,
    )

    /**
     * 铺满一天：按顺序给若干段「(多少分钟, 什么事)」，自动首尾相接从 0 铺到底。
     * `item = null` 的那段就是空白。
     */
    private fun tiled(day: String, vararg segs: Pair<Int, String?>): List<RecordEntity> {
        var at = 0
        return segs.map { (len, item) ->
            rec(day, at, at + len, item).also { at += len }
        }
    }

    /** 一整天的密铺数据，包成一个「日期 → 记录」的表（[StatsCalc.agg] 要的形状）。 */
    private fun daysOf(vararg days: Pair<String, List<RecordEntity>>): Map<String, List<RecordEntity>> =
        days.toMap()

    // ============================================================ ① 口径 → 列

    @Test
    fun `口径_日就是一列`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        assertEquals("日口径就一列", 1, cols.size)
        assertEquals("标签是月日", "10/14", cols[0].label)
        assertEquals("粒度是按天", StatsCalc.Grain.DAY, cols[0].grain)
        assertEquals(listOf(today), cols[0].days)
    }

    @Test
    fun `口径_周七列且从周一起`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.WEEK, today)
        assertEquals("一周七列", 7, cols.size)
        assertEquals("第一列是周一 10-12", listOf("2026-10-12"), cols[0].days)
        assertEquals("最后一列是周日 10-18", listOf("2026-10-18"), cols[6].days)
        assertEquals("标签走中文单字", listOf("一", "二", "三", "四", "五", "六", "日"), cols.map { it.label })
    }

    @Test
    fun `口径_月按当月实际天数`() {
        assertEquals("10 月 31 天", 31, StatsCalc.cols(StatsCalc.Mode.MONTH, today).size)
        assertEquals("2 月 28 天", 28, StatsCalc.cols(StatsCalc.Mode.MONTH, "2026-02-10").size)
        assertEquals("闰年 2 月 29 天", 29, StatsCalc.cols(StatsCalc.Mode.MONTH, "2028-02-10").size)
        assertEquals("第一天是 1 日", "1日", StatsCalc.cols(StatsCalc.Mode.MONTH, today)[0].label)
    }

    @Test
    fun `口径_年十二列且每列是一整月`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.YEAR, today)
        assertEquals("十二列", 12, cols.size)
        assertEquals("标签是月份", "1月", cols[0].label)
        assertEquals("12月", cols[11].label)
        assertEquals("第一列是 1 月整月", 31, cols[0].days.size)
        assertEquals("粒度是按月", StatsCalc.Grain.MONTH, cols[0].grain)
        assertEquals("第二列从 2 月 1 日起", "2026-02-01", cols[1].days.first())
        assertEquals("第二列到 2 月 28 日", "2026-02-28", cols[1].days.last())
    }

    @Test
    fun `口径_自定义刚好31天仍然按天`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.CUSTOM, today, "2026-09-14", "2026-10-14")
        assertEquals("含首尾恰好 31 天", 31, cols.size)
        assertEquals("按天", StatsCalc.Grain.DAY, cols[0].grain)
        assertEquals("第一天是起点", "2026-09-14", cols[0].days.single())
        assertEquals("最后一天是终点", "2026-10-14", cols[30].days.single())
    }

    @Test
    fun `口径_自定义32天就换成按月`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.CUSTOM, today, "2026-09-13", "2026-10-14")
        assertEquals("跨两个月", 2, cols.size)
        assertEquals("按月", StatsCalc.Grain.MONTH, cols[0].grain)
        assertEquals("9月", cols[0].label)
        assertEquals("10月", cols[1].label)
    }

    @Test
    fun `口径_自定义按月时首尾两个月只取区间内的天`() {
        // ⚠️ 原型这里取的是整月 —— 于是表头写着「1.15-3.10」、数字却是整月的，对不上。
        val cols = StatsCalc.cols(StatsCalc.Mode.CUSTOM, today, "2026-01-15", "2026-03-10")
        assertEquals("横跨三个月", 3, cols.size)
        assertEquals("1 月从 15 日起", "2026-01-15", cols[0].days.first())
        assertEquals("1 月只到月底 → 17 天", 17, cols[0].days.size)
        assertEquals("中间那个是整月", 28, cols[1].days.size)
        assertEquals("3 月从 1 日起", "2026-03-01", cols[2].days.first())
        assertEquals("3 月只到 10 日 → 10 天", 10, cols[2].days.size)
        assertEquals("三天合计正好等于区间长度", 55, StatsCalc.spanDays("2026-01-15", "2026-03-10"))
    }

    @Test
    fun `口径_自定义起止填反了会自动摆正`() {
        val reversed = StatsCalc.cols(StatsCalc.Mode.CUSTOM, today, today, "2026-10-01")
        val normal = StatsCalc.cols(StatsCalc.Mode.CUSTOM, today, "2026-10-01", today)
        assertEquals("反着填也当正的算", normal.map { it.days }, reversed.map { it.days })
    }

    @Test
    fun `口径_自定义没给区间时是空的`() {
        assertTrue(StatsCalc.cols(StatsCalc.Mode.CUSTOM, today).isEmpty())
    }

    // ============================================================ ② 某一天的四个数

    @Test
    fun `一天_过去的整天记满了_rec1440_没有未记录`() {
        val recs = tiled(yesterday, 480 to "睡觉", 600 to "打工", 360 to "看书")
        val s = StatsCalc.dayStat(recs, yesterday, nowAt(), info)

        assertFalse("过去的一天不是 future", s.future)
        assertEquals("整天恒 1440", 1440, s.total)
        assertEquals("一分钟没漏", 0, s.gap)
        assertEquals("已记录 = 1440", 1440, s.rec)
        assertEquals("过去的日子没有「还没到」", 0, s.notYet)
        assertEquals("三个事项各就各位", 600, s.items["打工"])
    }

    @Test
    fun `一天_过去一整天没记录_1440 全算未记录`() {
        val recs = tiled(yesterday, 1440 to null)
        val s = StatsCalc.dayStat(recs, yesterday, nowAt(), info)

        assertEquals("全算未记录", 1440, s.gap)
        assertEquals("已记录是 0", 0, s.rec)
        assertEquals(1440, s.total)
        assertTrue("事项表是空的", s.items.isEmpty())
    }

    @Test
    fun `一天_今天只算到此刻`() {
        val recs = tiled(today, 300 to "睡觉", 600 to "打工", 540 to "看书")
        val s = StatsCalc.dayStat(recs, today, nowAt(870), info)

        assertEquals("今天截至 14:30 → 870 分钟", 870, s.total)
        assertEquals("此刻之后一律不算进 rec", 870, s.rec)
        assertEquals("到此刻为止没漏", 0, s.gap)
        assertEquals("今天还剩 570 分钟", 570, s.notYet)
    }

    @Test
    fun `一天_今天此刻之前没记的算未记录而不是还没到`() {
        // ★ 这条是原型踩过的坑：以前这里算成 notYet，于是 ① 图说「漏了 0」、③ 图却画出一段灰。
        val recs = tiled(today, 300 to "睡觉", 1140 to null)
        val s = StatsCalc.dayStat(recs, today, nowAt(870), info)

        assertEquals("已记录 300", 300, s.rec)
        assertEquals("过去但没记的 570 分钟 = 未记录", 570, s.gap)
        assertEquals("「还没到」只有此刻之后那一段", 570, s.notYet)
        assertEquals(870, s.total)
    }

    @Test
    fun `一天_恒等式逐分钟都成立`() {
        val recs = tiled(today, 300 to "睡觉", 570 to null, 570 to "看书")
        listOf(0, 1, 299, 300, 869, 870, 871, 1439, 1440).forEach { m ->
            val s = StatsCalc.dayStat(recs, today, nowAt(m), info)
            assertEquals("此刻=$m：rec + gap 应等于 total", s.total, s.rec + s.gap)
            assertEquals("此刻=$m：total + notYet 应等于 1440", 1440, s.total + s.notYet)
            assertEquals("此刻=$m：事项合计应等于 rec", s.rec, s.items.values.sum())
        }
    }

    @Test
    fun `一天_未来的一天整个是还没到`() {
        val s = StatsCalc.dayStat(emptyList(), "2026-10-20", nowAt(), info)

        assertTrue("标 future", s.future)
        assertEquals("一分钟都不算", 0, s.total)
        assertEquals(0, s.gap)
        assertEquals(0, s.rec)
        assertEquals("未来不算「还没到」—— 它压根还没发生", 0, s.notYet)
    }

    @Test
    fun `一天_进行中的那条按此刻结束`() {
        val recs = listOf(rec(today, 0, null, "打工"))
        val s = StatsCalc.dayStat(recs, today, nowAt(870), info)

        assertEquals("从 0 一路记到此刻", 870, s.rec)
        assertEquals("没有空白", 0, s.gap)
    }

    @Test
    fun `一天_零长度记录什么都盖不住`() {
        val recs = listOf(rec(today, 500, 500, "打工"))
        val s = StatsCalc.dayStat(recs, today, nowAt(870), info)

        assertEquals("整段仍算未记录", 870, s.gap)
        assertEquals(0, s.rec)
        assertTrue("零长度不占事项表", s.items.isEmpty())
    }

    @Test
    fun `一天_同一事项多段合并成一条`() {
        val recs = tiled(yesterday, 480 to null, 240 to "打工", 300 to null, 180 to "打工", 240 to null)
        val s = StatsCalc.dayStat(recs, yesterday, nowAt(), info)

        assertEquals("上午 240 + 下午 180", 420, s.items["打工"])
        assertEquals("事项合计 = rec", s.rec, s.items.values.sum())
    }

    @Test
    fun `一天_事项被删掉的记录算事项已删除而不是空白`() {
        val recs = listOf(rec(today, 0, 870, "不存在的事项"))
        val s = StatsCalc.dayStat(recs, today, nowAt(870), info)

        assertEquals("它是一条真实记录，不是空白", 0, s.gap)
        assertEquals(870, s.items[StatsCalc.ORPHAN_NAME])
    }

    @Test
    fun `一天_事项为空但来源不是空白_也当空白`() {
        // 异常状态：itemId 没了、source 还是 MANUAL —— 显示层也只能当空白，两边口径必须一致
        val recs = listOf(rec(today, 0, 870, null, RecordSource.MANUAL))
        val s = StatsCalc.dayStat(recs, today, nowAt(870), info)

        assertEquals("当空白算", 870, s.gap)
        assertEquals(0, s.rec)
    }

    // ============================================================ ③ 时间分布（不合并）

    @Test
    fun `时间分布_绝不合并_上午下午两段打工各画各的`() {
        // ★ 这是 ③ 跟 ①② 最本质的区别：横轴是 0-24 的真实时间轴，位置本身有含义
        val recs = tiled(yesterday, 480 to null, 120 to "打工", 300 to null, 180 to "打工", 360 to null)
        val tl = StatsCalc.dayTimeline(recs, yesterday, nowAt(), info)
        val work = tl.filter { it.name == "打工" }

        assertEquals("两段就是两段", 2, work.size)
        assertEquals("第一段 480→600", 480 to 600, work[0].from to work[0].to)
        assertEquals("第二段 900→1080", 900 to 1080, work[1].from to work[1].to)
    }

    @Test
    fun `时间分布_首尾相接完整覆盖0到cut`() {
        val recs = tiled(today, 300 to "睡觉", 570 to null, 570 to "看书")
        val tl = StatsCalc.dayTimeline(recs, today, nowAt(870), info)

        assertEquals("从 0 起", 0, tl.first().from)
        assertEquals("铺到此刻", 870, tl.last().to)
        tl.zipWithNext().forEach { (x, y) -> assertEquals("不许有缝", x.to, y.from) }
        assertEquals("总长恰好 = cut", 870, tl.sumOf { it.minutes })
    }

    @Test
    fun `时间分布_没被任何记录盖住的一段补成灰`() {
        // 历史里「记到一半就没记录了」—— 那天没跑过补密。不补这一段，③ 就只画半根柱。
        val recs = listOf(rec(yesterday, 0, 600, "打工"))
        val tl = StatsCalc.dayTimeline(recs, yesterday, nowAt(), info)

        assertEquals("前面是打工、后面补灰", listOf("打工", StatsCalc.GAP_NAME), tl.map { it.name })
        assertEquals("灰段 600→1440", 840, tl.last().minutes)
        assertTrue("补出来的那段标着 isGap", tl.last().isGap)
    }

    @Test
    fun `时间分布_今天只补到此刻_不补到24点`() {
        val recs = listOf(rec(today, 0, 300, "睡觉"))
        val tl = StatsCalc.dayTimeline(recs, today, nowAt(870), info)

        assertEquals("只补到此刻", 870, tl.last().to)
    }

    @Test
    fun `时间分布_未来的一天什么都不画`() {
        assertTrue(StatsCalc.dayTimeline(emptyList(), "2026-10-20", nowAt(), info).isEmpty())
    }

    @Test
    fun `时间分布_重叠的脏数据也要裁成恰好覆盖`() {
        // 密铺正常时不会出现；真出现了也得让柱子恰好覆盖 [0, cut]，不许叠出一截
        val recs = listOf(rec(yesterday, 0, 900, "打工"), rec(yesterday, 600, 1440, "看书"))
        val tl = StatsCalc.dayTimeline(recs, yesterday, nowAt(), info)

        assertEquals("总长仍是 1440", 1440, tl.sumOf { it.minutes })
        tl.zipWithNext().forEach { (x, y) -> assertEquals("不许重叠", x.to, y.from) }
        assertEquals("后一条的头被裁掉", 900 to 1440, tl.last().from to tl.last().to)
    }

    @Test
    fun `时间分布_灰的总和必须等于同一天口径算出的gap`() {
        // ★★ 「两张图互相打架」的直接断言 —— 原型第 21 轮就是这里出过 bug
        val cases = listOf(
            listOf(rec(yesterday, 0, 600, "打工")),
            tiled(yesterday, 480 to null, 600 to "打工", 360 to null),
            tiled(yesterday, 1440 to null),
            tiled(today, 300 to "睡觉", 1140 to null),
            tiled(today, 0 to "打工"),
        )
        cases.forEachIndexed { i, recs ->
            val key = if (i == 3 || i == 4) today else yesterday
            val s = StatsCalc.dayStat(recs, key, nowAt(870), info)
            val tl = StatsCalc.dayTimeline(recs, key, nowAt(870), info)
            assertEquals(
                "第 $i 组：① 的灰带厚度跟 ③ 的灰柱高度对不上",
                s.gap,
                tl.filter { it.isGap }.sumOf { it.minutes },
            )
        }
    }

    // ============================================================ ④ 聚合

    @Test
    fun `聚合_把区间里出现过的事项名收齐`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.WEEK, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf(
                "2026-10-12" to tiled("2026-10-12", 1440 to "打工"),
                "2026-10-13" to tiled("2026-10-13", 1440 to "看书"),
            ),
            nowAt(),
            info,
        )
        assertEquals(setOf("打工", "看书"), agg.names)
        assertEquals("七列都在", 7, agg.cols.size)
    }

    @Test
    fun `聚合_未来的一列不参与求和但仍然占着横轴`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.WEEK, today)
        val agg = StatsCalc.agg(cols, emptyMap(), nowAt(), info)

        assertTrue("周六还没到", agg.cols[5].future)
        assertTrue("周日还没到", agg.cols[6].future)
        assertEquals("未来的列 total 是 0", 0, agg.cols[5].total)
        assertFalse("周三（今天）不是未来的列", agg.cols[2].future)
        assertTrue("周三标着「是今天」", agg.cols[2].isToday)
        assertFalse("周一不是今天", agg.cols[0].isToday)
    }

    @Test
    fun `聚合_月的每一列都标着是不是今天`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.MONTH, today)
        val agg = StatsCalc.agg(cols, emptyMap(), nowAt(), info)

        assertEquals("14 号那列是今天", 1, agg.cols.count { it.isToday })
        assertTrue("就是下标 13", agg.cols[13].isToday)
        assertEquals("15 号往后 17 天都还没到", 17, agg.cols.count { it.future })
    }

    @Test
    fun `聚合_年的未记录等于该月天数乘1440再减去已记录`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.YEAR, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf("2026-01-01" to tiled("2026-01-01", 1440 to "打工")),
            nowAt(),
            info,
        )

        assertEquals("1 月只记了一天", 1440, agg.cols[0].rec)
        assertEquals("31 天里剩 30 天全是未记录", 30 * 1440, agg.cols[0].gap)
        assertEquals("1 月那一列整天数 = 31×1440", 31 * 1440, agg.cols[0].total)
        assertEquals("1 月那列是按月粒度", StatsCalc.Grain.MONTH, agg.cols[0].grain)
    }

    // ============================================================ ⑤ 堆叠系列

    @Test
    fun `序列_顺序固定_绝不按时长排`() {
        // 「打工」只记了 10 分钟、「看书」记了 860 分钟 —— 顺序仍按目录走
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf(today to listOf(rec(today, 0, 10, "打工"), rec(today, 10, 1440, "看书"))),
            nowAt(870),
            info,
        )
        val s = StatsCalc.series(agg, listOf("打工", "看书"), { colors[it] ?: "#999999" }, includeGap = false)

        assertEquals("顺序 = 事项目录顺序", listOf("打工", "看书"), s.map { it.name })
        assertTrue("时长短的那个排前面 —— 这就是「不许按时长排」", s[0].total < s[1].total)
    }

    @Test
    fun `序列_整段区间没出现的事项不占图例不占颜色`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(cols, daysOf(today to tiled(today, 1440 to "打工")), nowAt(), info)
        val s = StatsCalc.series(agg, listOf("打工", "看书", "睡觉"), { "#999999" }, includeGap = false)

        assertEquals("只留真正出现过的", listOf("打工"), s.map { it.name })
    }

    @Test
    fun `序列_事项被删掉的记录排在最后`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf(
                today to listOf(
                    rec(today, 0, 100, "打工"),
                    rec(today, 100, 200, "已经删掉的事项"),
                    rec(today, 200, 870, null),
                )
            ),
            nowAt(870),
            info,
        )
        val s = StatsCalc.series(agg, listOf("打工", "看书"), { "#999999" }, includeGap = false)

        assertEquals(listOf("打工", StatsCalc.ORPHAN_NAME), s.map { it.name })
    }

    @Test
    fun `序列_含未记录时灰带排在最末_也就是面积图的最顶`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(cols, daysOf(today to tiled(today, 300 to "打工", 1140 to null)), nowAt(870), info)
        val area = StatsCalc.series(agg, listOf("打工"), { "#999999" }, includeGap = true)

        // 堆叠是从第 0 条往上累加的 → 「最后一条」就是最顶那条
        assertEquals("灰带必须在最后 → 画出来在最顶", StatsCalc.GAP_NAME, area.last().name)
        assertEquals("这一天到此刻漏了 570 分钟", 570, area.last().total)
    }

    @Test
    fun `序列_不含未记录_这是②图专用的`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(cols, daysOf(today to tiled(today, 300 to "打工", 1140 to null)), nowAt(870), info)
        val bar = StatsCalc.series(agg, listOf("打工"), { "#999999" }, includeGap = false)

        assertFalse("② 图绝不能把未记录堆进去 —— 那样柱子全是满的，缺漏就被藏起来了", bar.any { it.isGap })
    }

    @Test
    fun `甜甜圈_图例按时长降序_未记录也参与排名`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf(today to tiled(today, 100 to "打工", 600 to "看书", 170 to null)),
            nowAt(870),
            info,
        )
        val s = StatsCalc.series(agg, listOf("打工", "看书"), { "#999999" }, includeGap = true)
        val ranked = StatsCalc.bySize(s)

        assertEquals(
            "饼图是唯一按时长排的地方",
            listOf("看书", StatsCalc.GAP_NAME, "打工"),
            ranked.map { it.name },
        )
    }

    // ============================================================ ⑥ 曲线平滑

    @Test
    fun `平滑_陡变序列不过冲_不会沉到画布外`() {
        // 原型第 21 轮截图报的那个形状：大部分是 0（没记 / 未来），中间突然一段满格
        val h = 178f
        val vals = listOf(0, 0, 0, 1440, 1440, 0, 0)
        val pts = vals.mapIndexed { i, v -> StatsCalc.Pt(i * 50f, h - v * h / 1440f) }

        StatsCalc.smooth(pts).forEach { c ->
            val lo = minOf(c.from.y, c.to.y)
            val hi = maxOf(c.from.y, c.to.y)
            for (k in 0..30) {
                val y = c.at(k / 30f).y
                assertTrue("曲线越出了本段范围 [$lo, $hi] → $y", y >= lo - 0.01f && y <= hi + 0.01f)
            }
        }
    }

    @Test
    fun `平滑_控制点被钳在两端点之间`() {
        val pts = listOf(
            StatsCalc.Pt(0f, 100f),
            StatsCalc.Pt(10f, 100f),
            StatsCalc.Pt(20f, 0f),
            StatsCalc.Pt(30f, 0f),
        )
        StatsCalc.smooth(pts).forEachIndexed { idx, c ->
            assertEquals("第 $idx 段的起点", pts[idx], c.from)
            assertEquals("第 $idx 段的终点", pts[idx + 1], c.to)
            val xlo = minOf(c.from.x, c.to.x)
            val xhi = maxOf(c.from.x, c.to.x)
            val ylo = minOf(c.from.y, c.to.y)
            val yhi = maxOf(c.from.y, c.to.y)
            for (p in listOf(c.c1, c.c2)) {
                assertTrue("控制点 x=${p.x} 跑出 [$xlo, $xhi]", p.x in xlo - 0.01f..xhi + 0.01f)
                assertTrue("控制点 y=${p.y} 跑出 [$ylo, $yhi]", p.y in ylo - 0.01f..yhi + 0.01f)
            }
        }
    }

    @Test
    fun `平滑_穷举各种陡变序列都不越界`() {
        // 用 0 / 半格 / 满格 三个值拼出所有长度 3..6 的序列，共 1080 条
        val h = 178f
        val levels = listOf(0, 720, 1440)
        var checked = 0

        for (n in 3..6) {
            val combos = (0 until n).fold(1) { acc, _ -> acc * 3 }
            for (c in 0 until combos) {
                var x = c
                val vals = ArrayList<Int>(n)
                repeat(n) { vals += levels[x % 3]; x /= 3 }

                val pts = vals.mapIndexed { i, v -> StatsCalc.Pt(i * 50f, h - v * h / 1440f) }
                StatsCalc.smooth(pts).forEach { cubic ->
                    val lo = minOf(cubic.from.y, cubic.to.y)
                    val hi = maxOf(cubic.from.y, cubic.to.y)
                    for (k in 0..10) {
                        val y = cubic.at(k / 10f).y
                        assertTrue("序列 $vals 越界：y=$y 不在 [$lo, $hi]", y >= lo - 0.01f && y <= hi + 0.01f)
                    }
                }
                checked++
            }
        }
        assertEquals("3^3 + 3^4 + 3^5 + 3^6", 27 + 81 + 243 + 729, checked)
    }

    @Test
    fun `平滑_两个点也能接上_少于两个点返回空`() {
        assertEquals(1, StatsCalc.smooth(listOf(StatsCalc.Pt(0f, 0f), StatsCalc.Pt(10f, 10f))).size)
        assertTrue(StatsCalc.smooth(emptyList()).isEmpty())
        assertTrue(StatsCalc.smooth(listOf(StatsCalc.Pt(0f, 0f))).isEmpty())
    }

    @Test
    fun `平滑_反向就是从右往左走_面积图的下边界靠它`() {
        val pts = listOf(StatsCalc.Pt(0f, 0f), StatsCalc.Pt(10f, 5f), StatsCalc.Pt(20f, 0f))
        val rev = StatsCalc.smoothReversed(pts)

        assertEquals("反向那根从最右端点出发", pts.last(), rev.first().from)
        assertEquals("一路走到最左端点", pts.first(), rev.last().to)
        assertEquals("段数一样", pts.size - 1, rev.size)
    }

    // ============================================================ ⑦ 自检

    @Test
    fun `自检_正常密铺数据报没有问题`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.WEEK, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf(
                "2026-10-12" to tiled("2026-10-12", 480 to "睡觉", 600 to "打工", 360 to null),
                "2026-10-13" to tiled("2026-10-13", 1440 to "看书"),
                today to tiled(today, 870 to "睡觉", 570 to null),
            ),
            nowAt(870),
            info,
        )
        assertEquals("空 = 没问题", emptyList<String>(), StatsCalc.check(agg))
    }

    @Test
    fun `自检_能抓到rec里混进了gap`() {
        val bad = StatsCalc.Agg(
            cols = listOf(badCol(rec = 100, gap = 50, total = 200, items = mapOf("打工" to 100))),
            names = setOf("打工"),
        )
        assertTrue(
            "rec + gap ≠ total 要被抓到",
            StatsCalc.check(bad).any { it.contains("≠ total") },
        )
    }

    @Test
    fun `自检_能抓到两张图的未记录对不上`() {
        val bad = StatsCalc.Agg(
            cols = listOf(
                badCol(
                    rec = 800, gap = 70, total = 870,
                    items = mapOf("打工" to 800),
                    timeline = listOf(
                        StatsCalc.Seg(0, 800, "打工", "#000000", isGap = false),
                        StatsCalc.Seg(800, 810, StatsCalc.GAP_NAME, StatsCalc.GAP_COLOR, isGap = true),
                    ),
                )
            ),
            names = setOf("打工"),
        )
        val problems = StatsCalc.check(bad)
        assertTrue("③ 没铺到 cut 要被抓到", problems.any { it.contains("没铺到 cut") })
        assertTrue("①③ 未记录对不上要被抓到", problems.any { it.contains("两张图对不上") })
    }

    @Test
    fun `自检_能抓到事项合计对不上rec`() {
        val bad = StatsCalc.Agg(
            cols = listOf(badCol(rec = 800, gap = 70, total = 870, items = mapOf("打工" to 700))),
            names = setOf("打工"),
        )
        assertTrue(
            "漏算了哪条要被抓到",
            StatsCalc.check(bad).any { it.contains("事项合计") },
        )
    }

    /** 造一个故意有毛病的列，专供 `check()` 的测试用。 */
    private fun badCol(
        rec: Int,
        gap: Int,
        total: Int,
        items: Map<String, Int>,
        timeline: List<StatsCalc.Seg> = emptyList(),
    ) = StatsCalc.Col(
        label = "坏",
        firstKey = today,
        days = listOf(today),
        grain = StatsCalc.Grain.DAY,
        items = items,
        gap = gap,
        rec = rec,
        total = total,
        notYet = 0,
        future = false,
        isToday = true,
        timeline = timeline,
    )

    // ============================================================ ⑧ 翻区间与文字

    @Test
    fun `翻页_日周月年各挪一格`() {
        assertEquals("2026-10-15", StatsCalc.navAnchor(StatsCalc.Mode.DAY, today, 1))
        assertEquals("2026-10-13", StatsCalc.navAnchor(StatsCalc.Mode.DAY, today, -1))
        assertEquals("整周往前推 7 天", "2026-10-07", StatsCalc.navAnchor(StatsCalc.Mode.WEEK, today, -1))
        assertEquals("月落到下月 1 日", "2026-11-01", StatsCalc.navAnchor(StatsCalc.Mode.MONTH, today, 1))
        assertEquals("年落到 1 月 1 日", "2025-01-01", StatsCalc.navAnchor(StatsCalc.Mode.YEAR, today, -1))
    }

    @Test
    fun `翻页_月跨年`() {
        assertEquals("2027-01-01", StatsCalc.navAnchor(StatsCalc.Mode.MONTH, "2026-12-20", 1))
        assertEquals("2026-12-01", StatsCalc.navAnchor(StatsCalc.Mode.MONTH, "2027-01-05", -1))
    }

    @Test
    fun `翻页_自定义区间挪一整段而不是挪一天`() {
        val (f, t) = StatsCalc.navCustom("2026-10-01", "2026-10-07", -1)
        assertEquals("往前挪 7 天", "2026-09-24", f)
        assertEquals("终点跟着走", "2026-09-30", t)
    }

    @Test
    fun `区间文字_五种口径各写各的`() {
        assertEquals("2026.10.14", StatsCalc.rangeLabel(StatsCalc.Mode.DAY, today))
        assertEquals("10.12-10.18", StatsCalc.rangeLabel(StatsCalc.Mode.WEEK, today))
        assertEquals("2026.10", StatsCalc.rangeLabel(StatsCalc.Mode.MONTH, today))
        assertEquals("2026", StatsCalc.rangeLabel(StatsCalc.Mode.YEAR, today))
        assertEquals(
            "2026.09.15-10.14",
            StatsCalc.rangeLabel(StatsCalc.Mode.CUSTOM, today, "2026-09-15", "2026-10-14"),
        )
        assertEquals("没选区间时的占位", "选区间", StatsCalc.rangeLabel(StatsCalc.Mode.CUSTOM, today))
    }

    @Test
    fun `自定义_默认给最近30天`() {
        val (f, t) = StatsCalc.defaultCustom(today)
        assertEquals("含今天一共 30 天", 30, StatsCalc.spanDays(f, t))
        assertEquals("从 9 月 15 日起", "2026-09-15", f)
        assertEquals("到今天是终点", today, t)
    }

    @Test
    fun `跨度_含首尾`() {
        assertEquals(1, StatsCalc.spanDays(today, today))
        assertEquals(31, StatsCalc.spanDays("2026-09-14", "2026-10-14"))
        assertEquals(32, StatsCalc.spanDays("2026-09-13", "2026-10-14"))
    }

    // ============================================================ ⑨ 图表的规则

    @Test
    fun `卡片_日口径只有甜甜圈`() {
        assertEquals(listOf(StatsCalc.Chart.DONUT), StatsCalc.chartsFor(StatsCalc.Mode.DAY))
    }

    @Test
    fun `卡片_时间分布只在周和月出现`() {
        assertTrue("周有 ③", StatsCalc.Chart.DIST in StatsCalc.chartsFor(StatsCalc.Mode.WEEK))
        assertTrue("月有 ③", StatsCalc.Chart.DIST in StatsCalc.chartsFor(StatsCalc.Mode.MONTH))
        assertFalse(
            "年口径一列就是一个月，三十天压不成 24 小时轴",
            StatsCalc.Chart.DIST in StatsCalc.chartsFor(StatsCalc.Mode.YEAR),
        )
        assertFalse("自也一样", StatsCalc.Chart.DIST in StatsCalc.chartsFor(StatsCalc.Mode.CUSTOM))
    }

    @Test
    fun `卡片_周和月是四张_年跟自是三张`() {
        assertEquals(4, StatsCalc.chartsFor(StatsCalc.Mode.WEEK).size)
        assertEquals(4, StatsCalc.chartsFor(StatsCalc.Mode.MONTH).size)
        assertEquals(3, StatsCalc.chartsFor(StatsCalc.Mode.YEAR).size)
        assertEquals(3, StatsCalc.chartsFor(StatsCalc.Mode.CUSTOM).size)
    }

    @Test
    fun `纵轴_按天是24小时_按月是31天`() {
        assertEquals(1440, StatsCalc.yMaxFor(StatsCalc.Grain.DAY))
        assertEquals(31 * 1440, StatsCalc.yMaxFor(StatsCalc.Grain.MONTH))
        assertEquals(listOf(0, 6, 12, 18, 24), StatsCalc.ticksFor(StatsCalc.Grain.DAY))
        assertEquals(listOf(0, 8, 16, 24, 31), StatsCalc.ticksFor(StatsCalc.Grain.MONTH))
        assertEquals(360, StatsCalc.tickMinutes(StatsCalc.Grain.DAY, 6))
        assertEquals(8 * 1440, StatsCalc.tickMinutes(StatsCalc.Grain.MONTH, 8))
        assertEquals("6时", StatsCalc.tickLabel(StatsCalc.Grain.DAY, 6))
        assertEquals("8天", StatsCalc.tickLabel(StatsCalc.Grain.MONTH, 8))
    }

    @Test
    fun `横轴_十二列以内全标_再多就隔五个标但首尾必标`() {
        assertTrue("7 列全标", (0 until 7).all { StatsCalc.showLabel(it, 7) })
        assertTrue("12 列也全标", (0 until 12).all { StatsCalc.showLabel(it, 12) })

        val n = 31
        assertTrue("第一个必标", StatsCalc.showLabel(0, n))
        assertTrue("最后一个必标", StatsCalc.showLabel(n - 1, n))
        assertTrue("第 5 个标", StatsCalc.showLabel(4, n))
        assertFalse("第 2 个不标", StatsCalc.showLabel(1, n))
    }

    @Test
    fun `纵轴_满刻度固定是这张图的全部意义`() {
        // 要是让它自适应，每根柱都会顶到天花板，「矮 = 没记全」这个判读就没了
        val cols = StatsCalc.cols(StatsCalc.Mode.WEEK, today)
        val agg = StatsCalc.agg(cols, emptyMap(), nowAt(), info)
        assertEquals("不管有没有数据，刻度都不动", 1440, StatsCalc.yMaxFor(agg.grain))
    }

    @Test
    fun `常量_一天恒1440`() {
        assertEquals(1440, MINUTES_PER_DAY)
        assertEquals("未记录这个名字跟时间线卡片上写的是同一个词", "未记录", StatsCalc.GAP_NAME)
    }

    // ============================================================ ⑩ 点列的明细

    @Test
    fun `明细_按时长降序_这里跟饼图一个口径`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf(today to tiled(today, 100 to "打工", 600 to "看书", 170 to null)),
            nowAt(870),
            info,
        )
        val d = StatsCalc.detail(agg.cols[0], { colors[it] ?: "#999999" })

        assertEquals(listOf("看书", "打工"), d.rows.filter { !it.isGap }.map { it.name })
        assertEquals("未记录单独一行排在最后", StatsCalc.GAP_NAME, d.rows.last().name)
        assertTrue("那一行标着 isGap", d.rows.last().isGap)
    }

    @Test
    fun `明细_占比的分母是到此刻而不是1440`() {
        // 上午 9:00（540 分）看：全部时间都记满了，未记录应该是 0%，显示得漂漂亮亮
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(cols, daysOf(today to tiled(today, 540 to "打工", 900 to null)), nowAt(540), info)
        val d = StatsCalc.detail(agg.cols[0], { colors[it] ?: "#999999" })

        assertEquals("分母是 540 而不是 1440", 100f, d.rows.first().percent, 0.01f)
        assertTrue("全是打工，没有未记录那一行", d.rows.none { it.isGap })
    }

    @Test
    fun `明细_今天多带一段还没到`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.DAY, today)
        val agg = StatsCalc.agg(cols, daysOf(today to tiled(today, 300 to "打工", 1140 to null)), nowAt(870), info)
        val d = StatsCalc.detail(agg.cols[0], { colors[it] ?: "#999999" })

        assertEquals("还没到的那 570 分钟单独带出去", 570, d.notYet)
        assertTrue("副标题里写清已记录和未记录", d.sub.contains("已记录") && d.sub.contains("未记录"))
        assertTrue("标题是完整的年月日+周几", d.title.startsWith("2026年10月14日"))
    }

    @Test
    fun `明细_月列标题写年月_副标题带天数`() {
        // ⚠️ 这里要用**年口径**（每列一整月）。月口径的每一列是「一天」，标题还是写具体日期。
        val cols = StatsCalc.cols(StatsCalc.Mode.YEAR, today)
        val agg = StatsCalc.agg(
            cols,
            daysOf("2026-01-01" to tiled("2026-01-01", 1440 to "打工")),
            nowAt(),
            info,
        )
        val d = StatsCalc.detail(agg.cols[0], { colors[it] ?: "#999999" })

        assertTrue("这一列是一整月", agg.cols[0].spansWholeMonth)
        assertEquals("2026 年 1 月", d.title)
        assertTrue("副标题说清这一列有几天", d.sub.startsWith("31 天 · "))
        assertTrue("写下明细：1 月记了 1 天", d.sub.contains("1 天 0 时"))
    }

    @Test
    fun `明细_月口径的每一列是一天_不是一整月`() {
        // ★ 这条专门钉那个容易混的地方：Mode.MONTH 的列都是「一天」
        val cols = StatsCalc.cols(StatsCalc.Mode.MONTH, today)
        val agg = StatsCalc.agg(cols, emptyMap(), nowAt(), info)

        assertEquals("月口径 31 列", 31, agg.cols.size)
        assertTrue("但每一列都不是「一整月」", agg.cols.none { it.spansWholeMonth })
    }

    @Test
    fun `明细_未来的一天直说还没到`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.MONTH, today)
        val agg = StatsCalc.agg(cols, emptyMap(), nowAt(), info)
        val d = StatsCalc.detail(agg.cols[30], { colors[it] ?: "#999999" })

        assertEquals("31 号还没到", "还没到", d.sub)
        assertTrue("没有事项行", d.rows.isEmpty())
    }

    @Test
    fun `明细_一条记录都没有的日子也说得明白`() {
        val cols = StatsCalc.cols(StatsCalc.Mode.MONTH, today)
        val agg = StatsCalc.agg(cols, emptyMap(), nowAt(), info)
        // 10-01 是过去的一天，一条都没记
        val d = StatsCalc.detail(agg.cols[0], { colors[it] ?: "#999999" })

        assertTrue("副标题末尾点明一条都没有", d.sub.endsWith("一条记录都没有"))
        assertEquals("1440 分钟全算未记录", 1440, d.rows.single().minutes)
        assertTrue(d.rows.single().isGap)
        assertEquals("占比 100%", 100f, d.rows.single().percent, 0.01f)
    }
}
