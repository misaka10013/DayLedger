package com.misaka.timelog.domain

import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.isGap
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 手机端统计的**全部算法** —— ★ **纯函数，不碰数据库、不碰 Android、不碰协程**。
 *
 * 这是第五次把「牵动不变式的规则」抠成纯函数（前四次：`Density`、`Overlap`、
 * `TemplateEdit`、`TemplateEngine`）。理由一样：这类错误的共同点是
 * **不报错、不崩溃**，只会在几个月后以「统计数字对不上」的形式浮出来，
 * 而那时候已经查不出是哪一次操作弄破的。做成纯函数，测试就能穷举边界、秒级跑完。
 *
 * ## 定位：不是「分析你时间花得值不值」，而是「发现你哪天漏记了」
 *
 * 深度分析留给网页 + AI（`§9` 那个只读接口）。手机端只做「一眼看见漏在哪」。
 * 所以整套口径都围绕一个判读：**柱子矮 = 那天没记全**。
 *
 * ## 四张图 × 五个口径
 *
 * | | 图形 | 日 | 周 | 月 | 年 | 自 | 同一事项多段 |
 * |---|---|---|---|---|---|---|---|
 * | ① | 时长对比（堆叠面积） | | ✓ | ✓ | ✓ | ✓ | **合并** |
 * | ② | 时长汇总（堆叠柱·不含未记录） | | ✓ | ✓ | ✓ | ✓ | **合并** |
 * | ③ | 时间分布（堆叠柱·按真实时序） | | ✓ | ✓ | | | ★ **不合并** |
 * | ④ | 总时长汇总（甜甜圈） | ✓ | ✓ | ✓ | ✓ | ✓ | **合并** |
 *
 * ⚠️ **③ 只在周 / 月出现**：年 / 自 口径里横轴的一列是「一个月」，
 * 三十天压不成一根 24 小时的时间轴。
 *
 * ## ★★ 两套聚合规则，严禁共用一处实现
 *
 * | 用于 | 规则 |
 * |---|---|
 * | ①②④ | 同一事项在**同一列**内出现多段 → **求和合并**（[agg] 按名字累加） |
 * | **③** | **不合并** —— 用 [dayTimeline] 直接拿那天按 `startMin` 排好的原始区段，逐段画 |
 *
 * 御坂【原话】：
 * > 「①②可以合并计，③不行，因为它横轴是 0-24 的真实时间轴」
 *
 * ③ 里「打工」上午一段、下午一段，本来就在时间轴的不同**位置**上，合并就没意义了。
 *
 * ## ★★ 未记录的口径（每张图都不一样，这是刻意的）
 *
 * | 图 | 未记录 | 为什么 |
 * |---|---|---|
 * | ① 面积图 | **含**，灰带排**最顶** | 下面实色带子仍看得清趋势，顶上灰带厚度 = 那天漏了多少，一眼两用 |
 * | ② 汇总柱 | **不含** | 含了每根柱都是满的 24 时，「漏记」就被藏起来了 —— 而 ② 存在的意义就是暴露它 |
 * | ③ 时间分布 | **含** | 那就是那天真实的一段灰 |
 * | ④ 甜甜圈 | **含**，灰扇区排**最后** | |
 *
 * ## ★ 三条日期语义（混了就会把「还没到」当成「漏记」）
 *
 * - 过去的某天没记录 → 全天 1440 分钟都是「未记录」（**真漏了**）
 * - 今天 `[0, 此刻]` 之外 → 「**还没到**」，不算漏记，也不画
 * - 未来的某天 → 整天不画（还没发生）
 *
 * ## ★ 序列顺序与颜色 = 固定，不按时长排
 *
 * 顺序走「分类次序 → 组内次序」（跟选择面板的网格、跟时间线的颜色完全一致）。
 * 按时长排的话，每天的颜色都在换位置，趋势图就花了。
 * **甜甜圈的图例是唯一按时长降序的**（那里要的就是「谁最大」）。
 */
object StatsCalc {

    /** 「未记录」在统计里的显示名。跟时间线卡片上写的是同一个词。 */
    const val GAP_NAME = "未记录"

    /** 空白段的灰。跟 `TimelineAssembler.GAP_COLOR` 是同一个色。 */
    const val GAP_COLOR = "#94A3B8"

    /** 事项被删掉后剩下的记录（理论上不该出现，纯属兜底）。 */
    const val ORPHAN_NAME = "（事项已删除）"

    // ==================================================================== 模型

    /** 统计口径 —— 左上角那五个字。 */
    enum class Mode { DAY, WEEK, MONTH, YEAR, CUSTOM }

    /**
     * 一列的粒度。
     *
     * ★ 它不只是显示上的事：② 汇总柱的纵轴满刻度跟着它走 ——
     * 按天时是 `0..24 时`，按月时是 `0..31 天`。
     */
    enum class Grain { DAY, MONTH }

    /**
     * 一条记录在统计里长什么样 —— **由调用方注入**（它握有事项目录）。
     *
     * 为什么不做成内部查表：统计是纯函数，不该知道「事项表」长什么样。
     * 界面那边一次把字典备好，传一个 lambda 进来就行，测试里给个假的也极方便。
     */
    data class Info(val name: String, val color: String, val isGap: Boolean)

    /** 横轴上的**一格**。`days.size > 1` 表示这一格是一整个月。 */
    data class Column(val label: String, val days: List<String>, val grain: Grain)

    /**
     * 某一个口径下一列的**四个数** —— 口径必须分清，混了图就错了。
     *
     * ⚠️ **恒等式**：`rec + gap == total`，且 `total + notYet == days × 1440`。
     * `rec` **不包含** `gap`。
     */
    data class DayStat(
        /** 事项名 → 分钟数。**已合并**同一事项的多段。 */
        val items: Map<String, Int>,
        /** 未记录分钟数。 */
        val gap: Int,
        /** 已记录分钟数（**不含**未记录）。 */
        val rec: Int,
        /** 一天至今的分钟数：过去的整天 = 1440，今天 = 此刻。 */
        val total: Int,
        /** 还没到的分钟数（只有今天有；过去 = 0、未来 = 0）。 */
        val notYet: Int,
        /** 这一天/这一列整个都还没到。 */
        val future: Boolean,
    ) {
        companion object {
            val EMPTY_FUTURE = DayStat(emptyMap(), 0, 0, 0, 0, future = true)
        }
    }

    /** ③ 时间分布用的一段 —— **不合并**，每段就是原始记录里的一段。 */
    data class Seg(
        val from: Int,
        val to: Int,
        val name: String,
        val color: String,
        val isGap: Boolean,
    ) {
        val minutes: Int get() = to - from
    }

    /** 聚合之后的一列 —— 图上就是「一根柱」或「一条边的采样点」。 */
    data class Col(
        val label: String,
        /** 这一列第一天的键。点列看明细要用它。 */
        val firstKey: String,
        val days: List<String>,
        val grain: Grain,
        /** 事项名 → 分钟数（本列内已合并）。 */
        val items: Map<String, Int>,
        val gap: Int,
        val rec: Int,
        val total: Int,
        val notYet: Int,
        /** 这一列**整个**都还没到。 */
        val future: Boolean,
        /** 「今天」落在这一列里。 */
        val isToday: Boolean,
        /** ③ 专用：这一天按真实时序排好的区段。**只有「一天一列」才有值。** */
        val timeline: List<Seg>,
    ) {
        /**
         * 这一列是**一整个月**（而不是一天）吗。
         *
         * ⚠️ **别把它跟 [Mode.MONTH] 混了** —— 月口径的每一列是**一天**（只不过有 28–31 列），
         * 这个属性在月口径下全是 `false`。它是 `true` 的场合只有两个：
         * **年口径**（12 列，每列一整月）和**自定义区间 >31 天**（每列一整月）。
         */
        val spansWholeMonth: Boolean get() = days.size > 1
    }

    /** 一次聚合的完整结果。 */
    data class Agg(val cols: List<Col>, val names: Set<String>) {
        val grain: Grain get() = cols.firstOrNull()?.grain ?: Grain.DAY
    }

    /**
     * 堆叠图里的**一条带子** —— `vals[i]` 是它在第 `i` 列上的分钟数。
     *
     * `isGap = true` 的那条是「未记录」灰带。
     */
    data class Slice(
        val name: String,
        val color: String,
        val vals: List<Int>,
        val isGap: Boolean = false,
    ) {
        val total: Int get() = vals.sum()
    }

    /** 一个点（画图用，故意用 `Float` 而不是 `Offset` —— 这一层不许依赖 Compose）。 */
    data class Pt(val x: Float, val y: Float)

    /** 一段三次贝塞尔。 */
    data class Cubic(val from: Pt, val c1: Pt, val c2: Pt, val to: Pt) {

        /** 取曲线上的点 —— 测试靠它采样，断言曲线不越界。 */
        fun at(t: Float): Pt {
            val u = 1f - t
            val a = u * u * u
            val b = 3f * u * u * t
            val c = 3f * u * t * t
            val d = t * t * t
            return Pt(
                x = a * from.x + b * c1.x + c * c2.x + d * to.x,
                y = a * from.y + b * c1.y + c * c2.y + d * to.y,
            )
        }
    }

    // ============================================================ 某一天的统计

    /**
     * 把一条记录翻译成统计要的形状 —— ★ **判据跟时间线卡片共用同一条
     * [RecordEntity.isGap]**，别在这里再写一遍「是不是空白」。
     *
     * @param nameOf 事项 id → 名字。查不到（事项被删了）就显示「（事项已删除）」
     * @param colorOf 事项 id → 颜色。查不到退回空白灰
     */
    fun info(
        r: RecordEntity,
        nameOf: (String) -> String?,
        colorOf: (String) -> String?,
    ): Info {
        if (r.isGap) return Info(GAP_NAME, GAP_COLOR, isGap = true)
        val id = r.itemId ?: return Info(ORPHAN_NAME, GAP_COLOR, isGap = false)
        return Info(nameOf(id) ?: ORPHAN_NAME, colorOf(id) ?: GAP_COLOR, isGap = false)
    }

    /**
     * 某一天的**四个数** —— ★ 唯一入口，别在别处再算一遍。
     *
     * ## ★★ 第 21 轮修正的口径（两张图必须同源）
     *
     * `0 → cut` 这一段里，**只要没被事项记录盖住，就一律算「未记录」** ——
     * 不管是空白记录，还是「压根没有记录盖着」的数据洞。因为那段时间
     * **已经过去了**，只是没记。
     *
     * ⚠️ 原型早期在这里对「今天」网开一面，把没盖住的部分算成 `notYet`，
     * 结果 ① 图说「今天漏了 0」、③ 图却画出一段灰 —— **同一天两个数**。
     *
     * `notYet` 现在只有一个意思：**此刻之后的那一段**（只有今天有）。
     */
    fun dayStat(
        records: List<RecordEntity>,
        key: String,
        now: Now,
        info: (RecordEntity) -> Info,
    ): DayStat {
        // 未来的一天：一整个「还没到」，什么都不算
        if (key > now.dayKey) return DayStat.EMPTY_FUTURE

        val isToday = key == now.dayKey
        val cut = if (isToday) now.minuteOfDay else MINUTES_PER_DAY

        val items = LinkedHashMap<String, Int>()
        var gap = 0
        var rec = 0
        var covered = 0

        records.forEach { r ->
            val a = r.startMin
            var b = endOf(r, key, now)
            if (a >= cut) return@forEach          // 整段都在此刻之后 → 还没到
            if (b > cut) b = cut
            if (b <= a) return@forEach            // 零长度 / 被压没了
            val d = b - a
            val i = info(r)
            if (i.isGap) gap += d else {
                items[i.name] = (items[i.name] ?: 0) + d
                rec += d
            }
            covered += d
        }

        // ★ 「过去了但没记」的兜底：没被任何记录盖住的那一段，一律算未记录。
        //   少了这一句就会出现「① 图说今天漏了 8 小时、③ 图一根灰柱都没有」。
        val left = cut - covered
        if (left > 0) gap += left

        return DayStat(
            items = items,
            gap = gap,
            rec = rec,
            total = cut,
            notYet = if (isToday) MINUTES_PER_DAY - cut else 0,
            future = false,
        )
    }

    /**
     * 某一天的**原样**记录 —— ★ ③ 时间分布专用，**绝不合并**。
     *
     * ## 为什么它必须跟 [dayStat] 分开实现
     *
     * ③ 的横轴是 `0-24` 的**真实时间轴**：早上 9 点那段「打工」在柱子底下、
     * 下午 2 点那段在中间偏上。合并了就没法画了。
     *
     * ## ★★ 它必须跟 [dayStat] 的口径**完全一致**
     *
     * 尤其那一步「把没被任何记录盖住的一段补成灰」——少了它就会出现
     * 「① 图那条灰带明说『这天漏了 12 小时』，③ 图却只画了半根柱」，
     * 而它们本该是**同一份数据的两种投影**。
     *
     * （真机上 `ensureDense()` 保证每天密铺，本不会出现「记到一半就没记录」；
     * 但统计页要读历史，历史里未必每天都跑过补密 —— 所以这里必须有兜底。）
     *
     * ## 跟原型的唯一一处偏差（故意的）
     *
     * 原型把重叠的记录**原样重叠画**；这里会**裁掉被前一段盖住的头**。
     * 密铺正常时两者结果一模一样，而真有重叠（历史脏数据）时，
     * 裁剪保证柱子**恰好覆盖 `[0, cut]`、总长恒等于 cut** —— 测试里钉着这条。
     *
     * @return 首尾相接、完整覆盖 `[0, cut]` 的区段；未来的一天返回空列表。
     */
    fun dayTimeline(
        records: List<RecordEntity>,
        key: String,
        now: Now,
        info: (RecordEntity) -> Info,
    ): List<Seg> {
        if (key > now.dayKey) return emptyList()
        val cut = Density.cutFor(key, now)
        if (cut <= 0) return emptyList()

        val out = ArrayList<Seg>(records.size + 2)
        var at = 0

        records.sortedBy { it.startMin }.forEach { r ->
            val a0 = r.startMin
            val b0 = minOf(endOf(r, key, now), cut)
            if (a0 >= cut || b0 <= at) return@forEach   // 还轮不到它 / 被前面盖住 / 零长度

            if (a0 > at) out += Seg(at, a0, GAP_NAME, GAP_COLOR, isGap = true)
            val a = maxOf(a0, at)                        // ★ 裁掉被盖住的头
            if (b0 > a) {
                val i = info(r)
                out += Seg(a, b0, i.name, i.color, i.isGap)
            }
            at = b0
        }

        // 尾部兜底：`cut` 之前没被任何记录盖住的一段
        if (at < cut) out += Seg(at, cut, GAP_NAME, GAP_COLOR, isGap = true)
        return out
    }

    /**
     * 一条记录在**它自己那一天**该结束在哪。
     *
     * ⚠️ 统计要跨天遍历，**不能直接用「正在看的那一天」的 cut** ——
     * 翻到别的日子时那个 cut 就不对了。这里传进来的 `key` 恒等于
     * 「这条记录所属的那一天」，所以 `Density.cutFor` 算的正是它自己的上界。
     *
     * 进行中的那条（`endMin == null`）在今年今天就是「此刻」，在历史里
     * 只能当「铺满整天」看待（`ensureDense` 的自愈本该早就把它封口了）。
     */
    private fun endOf(r: RecordEntity, key: String, now: Now): Int =
        r.endMin ?: Density.cutFor(key, now)

    // ============================================================ 口径 → 列

    /** 自定义区间的跨度（天，**含首尾**）。 */
    fun spanDays(from: String, to: String): Int =
        (ChronoUnit.DAYS.between(DayKey.parse(from), DayKey.parse(to)) + 1).toInt()

    /**
     * 口径 + 锚点 → 横轴上有哪几列。
     *
     * ★ **粒度的选择写死在这里**，渲染器里不许再判一次 ——
     * 两处各判一次，迟早会出现「图按天画、轴按天标、但纵轴满刻度按天算」这种半身不遂。
     *
     * | 口径 | 一列 = | 列数 |
     * |---|---|---|
     * | 日 | 一天 | 1 |
     * | 周 | 一天（周一 → 周日） | 7 |
     * | 月 | 一天 | 28–31 |
     * | 年 | 一个月 | 12 |
     * | 自 | 跨度 ≤31 天 → 一天；>31 天 → 一个月 | 按跨度算 |
     *
     * ⚠️ **自定义区间 >31 天时，首尾两个月只取落在区间内的那些天**
     * （原型取的是整月，于是表头写着「1.15-3.10」、数字却是整月的 —— 对不上）。
     */
    fun cols(mode: Mode, anchor: String, from: String? = null, to: String? = null): List<Column> {
        when (mode) {
            Mode.DAY -> return listOf(Column(shortMd(anchor), listOf(anchor), Grain.DAY))

            Mode.WEEK -> return DayKey.weekOf(anchor).map {
                Column(DayKey.weekdayCn(it), listOf(it), Grain.DAY)
            }

            Mode.MONTH -> {
                val first = DayKey.parse(anchor).withDayOfMonth(1)
                return daysOfMonth(first).map { k ->
                    Column("${DayKey.parse(k).dayOfMonth}日", listOf(k), Grain.DAY)
                }
            }

            Mode.YEAR -> {
                val y = DayKey.parse(anchor).year
                return (1..12).map { m ->
                    Column("${m}月", daysOfMonth(LocalDate.of(y, m, 1)), Grain.MONTH)
                }
            }

            Mode.CUSTOM -> {
                if (from == null || to == null) return emptyList()
                // 起始日填反了也当正的算 —— 比弹一句「起止日期填反了」友好得多
                val f = minOf(from, to)
                val t = maxOf(from, to)

                if (spanDays(f, t) <= DAY_MAX_FOR_DAILY) {
                    return generateSequence(f) { DayKey.plus(it, 1) }
                        .takeWhile { it <= t }
                        .map { Column(shortMd(it), listOf(it), Grain.DAY) }
                        .toList()
                }
                return monthsCovering(f, t).map { (y, m) ->
                    // ★ 只留落在区间内的天（首尾两个月会被裁短）
                    val inRange = daysOfMonth(LocalDate.of(y, m, 1)).filter { it >= f && it <= t }
                    Column("${m}月", inRange, Grain.MONTH)
                }
            }
        }
    }

    /**
     * 自定义区间里，把「一天一列」换成「一月一列」的那条线。
     *
     * 31 = 一个月最多几天。超过它就按月 —— 否则横轴会挤成一条黑边。
     */
    const val DAY_MAX_FOR_DAILY = 31

    /** 某个整月的全部日期。 */
    private fun daysOfMonth(first: LocalDate): List<String> {
        val n = first.lengthOfMonth()
        return (0 until n).map { DayKey.of(first.withDayOfMonth(1 + it)) }
    }

    /** `from → to` 覆盖到的每一个月，返回 `(年, 月)` 序列。 */
    private fun monthsCovering(from: String, to: String): List<Pair<Int, Int>> {
        val f = DayKey.parse(from)
        val t = DayKey.parse(to)
        val out = ArrayList<Pair<Int, Int>>()
        var y = f.year
        var m = f.monthValue
        while (y < t.year || (y == t.year && m <= t.monthValue)) {
            out += y to m
            m++
            if (m > 12) { m = 1; y++ }
        }
        return out
    }

    // ============================================================ 聚合

    /**
     * 把列聚合出来 —— 每列一个 [Col]，顺手收齐区间里出现过的事项名。
     *
     * ⚠️ **未来的一列不参与求和**（`total`/`gap` 都按「已经发生的那部分」算），
     * 但仍然会出现在横轴上 —— ② 图会给它一根不画的空位，让人看出「后面还有」。
     */
    fun agg(
        cols: List<Column>,
        recordsByDay: Map<String, List<RecordEntity>>,
        now: Now,
        info: (RecordEntity) -> Info,
    ): Agg {
        val out = ArrayList<Col>(cols.size)
        val names = LinkedHashSet<String>()

        cols.forEach { c ->
            val items = LinkedHashMap<String, Int>()
            var gap = 0
            var rec = 0
            var total = 0
            var notYet = 0
            var futureDays = 0
            var timeline: List<Seg> = emptyList()

            c.days.forEach { k ->
                val recs = recordsByDay[k].orEmpty()
                val s = dayStat(recs, k, now, info)
                s.items.forEach { (n, v) ->
                    items[n] = (items[n] ?: 0) + v
                    names += n
                }
                if (s.future) {
                    futureDays++
                } else {
                    gap += s.gap
                    rec += s.rec
                    total += s.total
                    notYet += s.notYet
                    // ③ 只对「一天一列」的口径有意义
                    if (c.days.size == 1) timeline = dayTimeline(recs, k, now, info)
                }
            }

            val first = c.days.first()
            val last = c.days.last()
            out += Col(
                label = c.label,
                firstKey = first,
                days = c.days,
                grain = c.grain,
                items = items,
                gap = gap,
                rec = rec,
                total = total,
                notYet = notYet,
                future = futureDays == c.days.size,
                isToday = now.dayKey >= first && now.dayKey <= last,
                timeline = timeline,
            )
        }
        return Agg(cols = out, names = names)
    }

    /**
     * ★ 堆叠系列：**顺序与颜色都是固定的，不按时长排**。
     *
     * 御坂：按时长排的话每天顺序都在变，趋势图就花了。
     * 顺序 = **分类次序 → 组内次序**（跟选择面板的网格、跟时间线的颜色完全一致）。
     *
     * @param order 事项目录里排好的名字（分类 position → 组内 orderInCat）
     * @param colorOf 事项名 → 颜色。**取的是事项自己的色** ——
     *   种子数据里「事项的初始颜色 = 所属分类的颜色」，所以入眼就是一块块分类色，
     *   跟时间线卡片上那条色条也是同一套（原型当年直接存的就是分类色）。
     * @param includeGap ①②③④ 里只有 ② 传 `false`（灰带会把它「漏记」这个重点盖住）
     */
    fun series(
        agg: Agg,
        order: List<String>,
        colorOf: (String) -> String,
        includeGap: Boolean,
    ): List<Slice> {
        val seen = LinkedHashSet<String>()
        val names = ArrayList<String>(order.size + 4)
        order.forEach { if (seen.add(it)) names += it }

        // 区间里出现过、但事项目录里已经没有的（被删掉的事项）—— 按名字排序附在最后。
        // 排在最后而不是插在中间，是为了「图例顺序=网格顺序」这条在前半段仍然成立。
        names += agg.names.filter { it !in seen }.sorted()

        val out = ArrayList<Slice>(names.size + 1)
        names.forEach { n ->
            val vals = agg.cols.map { it.items[n] ?: 0 }
            // 整个区间一分钟都没出现 → 不占图例、不占颜色（否则图例会被几十个 0 撑爆）
            if (vals.any { it > 0 }) out += Slice(n, colorOf(n), vals)
        }

        if (includeGap) {
            out += Slice(GAP_NAME, GAP_COLOR, agg.cols.map { it.gap }, isGap = true)
        }
        return out
    }

    /** 甜甜圈的图例：**唯一按时长降序**的地方（这里要的就是「谁最大」）。 */
    fun bySize(slices: List<Slice>): List<Slice> =
        slices.filter { it.total > 0 }.sortedByDescending { it.total }

    // ============================================================ 点某一列看明细

    /** 明细里的一行。 */
    data class DetailRow(
        val name: String,
        val color: String,
        val minutes: Int,
        /** 占比（0–100）。 */
        val percent: Float,
        val isGap: Boolean,
    )

    /** 点某一列弹出的明细。 */
    data class Detail(
        val title: String,
        val sub: String,
        val rows: List<DetailRow>,
        /** 今天此刻之后还没到的那一段（它不是未记录，单独一行）。 */
        val notYet: Int,
    )

    /**
     * 点一列 → 那一天的明细。
     *
     * ## 三条口径，写错了都看着挺正常
     *
     * 1. **列表按时长降序** —— 这里跟饼图一个口径（要的是「谁最多」），
     *    跟堆叠图那套固定色序**不是一回事**。
     * 2. ★ **占比的分母是 `rec + gap`**（= 这一天到此刻），**不是** 1440。
     *    拿 1440 当分母的话，今天上午看会得到一堆永远偏小的百分比，
     *    看着像「记录得很差」，其实只是还没到晚上。
     * 3. **未记录单列一行**，不混进事项里；「还没到」另占一行（它在语义上不是漏记）。
     *
     * ⚠️ 跟原型的一处差别：**「一列 = 一个月」时的副标题改成人话**。
     * 原型写的是「31 天 · 合计 31 天 0 时」—— 因为那一列的 `total` 是
     * 「这些天已经过去的分钟数之和」，对完全过去的一个月来说就是 31×1440。
     * 那样写会让人以为「这个月我一共记了 31 天」，所以这里改成跟单日一致的说法。
     */
    fun detail(col: Col, colorOf: (String) -> String): Detail {
        val denom = (col.rec + col.gap).coerceAtLeast(1)

        val rows = col.items.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { (n, v) -> DetailRow(n, colorOf(n), v, v * 100f / denom, isGap = false) }
            .toMutableList()

        if (col.gap > 0) {
            rows += DetailRow(GAP_NAME, GAP_COLOR, col.gap, col.gap * 100f / denom, isGap = true)
        }

        val title = if (col.spansWholeMonth) {
            val d = DayKey.parse(col.firstKey)
            "${d.year} 年 ${d.monthValue} 月"
        } else {
            DayKey.displayFull(col.firstKey)
        }

        val span = if (col.spansWholeMonth) "${col.days.size} 天 · " else ""
        val sub = when {
            col.future -> "还没到"
            col.rec > 0 -> span +
                "已记录 ${DayKey.durationMultiDay(col.rec)} · 未记录 ${DayKey.durationMultiDay(col.gap)}"
            else -> span + "一条记录都没有"
        }

        return Detail(title, sub, rows, col.notYet)
    }

    // ============================================================ 四张图的口径

    /** 四张图。 */
    enum class Chart { AREA, BAR, DIST, DONUT }

    /**
     * ★ 这个口径该显示哪几张图 —— **别在渲染器里再判一次**。
     *
     * 「日」口径只有 ④：一天就一列，画不出趋势，只能看占比。
     * 「③ 时间分布」只在**周 / 月**出现 —— 年 / 自 口径里横轴的一列是「一个月」，
     * 三十天压不成一根 24 小时的时间轴。
     */
    fun chartsFor(mode: Mode): List<Chart> = when (mode) {
        Mode.DAY -> listOf(Chart.DONUT)
        Mode.WEEK, Mode.MONTH -> listOf(Chart.AREA, Chart.BAR, Chart.DIST, Chart.DONUT)
        Mode.YEAR, Mode.CUSTOM -> listOf(Chart.AREA, Chart.BAR, Chart.DONUT)
    }

    /**
     * ② 汇总柱的纵轴满刻度。
     *
     * ★ 按天时是 **24 时**、按月时是 **31 天** —— 固定满刻度是这张图的全部意义：
     * 柱子矮就是那天没记全。要是让它自适应，每根柱都会顶到天花板，缺漏就没了。
     */
    fun yMaxFor(grain: Grain): Int =
        if (grain == Grain.MONTH) 31 * MINUTES_PER_DAY else MINUTES_PER_DAY

    /** 纵轴刻度值（按天的口径单位是「时」，按月的口径单位是「天」）。 */
    fun ticksFor(grain: Grain): List<Int> =
        if (grain == Grain.MONTH) listOf(0, 8, 16, 24, 31) else listOf(0, 6, 12, 18, 24)

    /** 刻度换算成分钟。 */
    fun tickMinutes(grain: Grain, tick: Int): Int =
        tick * if (grain == Grain.MONTH) MINUTES_PER_DAY else 60

    /** 刻度旁边那个字：「6时」/「8天」。 */
    fun tickLabel(grain: Grain, tick: Int): String =
        "$tick${if (grain == Grain.MONTH) "天" else "时"}"

    /**
     * 横轴上第 `i` 个标签要不要画。
     *
     * 一屏最多挤下 12 个标签；再多就隔五个画一个，但**首尾两个永远画**
     * （不然横轴会像没头没尾）。
     */
    fun showLabel(i: Int, n: Int): Boolean =
        n <= 12 || i == 0 || i == n - 1 || (i + 1) % 5 == 0

    // ============================================================ 区间导航

    /** 点 `‹` / `›` 之后锚点落到哪。自定义区间不走它（改的是 from/to）。 */
    fun navAnchor(mode: Mode, anchor: String, dir: Int): String = when (mode) {
        Mode.DAY -> DayKey.plus(anchor, dir)
        Mode.WEEK -> DayKey.plus(anchor, dir * 7)
        Mode.MONTH -> DayKey.of(DayKey.parse(anchor).withDayOfMonth(1).plusMonths(dir.toLong()))
        Mode.YEAR -> DayKey.of(LocalDate.of(DayKey.parse(anchor).year + dir, 1, 1))
        Mode.CUSTOM -> anchor
    }

    /**
     * 自定义区间挪一整段（`dir` = ±1）。
     *
     * 挪「一个区间的长度」而不是「一天」—— 免得翻几次之后区间被挤成一天。
     */
    fun navCustom(from: String, to: String, dir: Int): Pair<String, String> {
        val span = spanDays(from, to)
        return DayKey.plus(from, dir * span) to DayKey.plus(to, dir * span)
    }

    /** 第一次点「自」的默认区间：最近 30 天（含今天）。省得先弹一个空表单。 */
    fun defaultCustom(today: String): Pair<String, String> = DayKey.plus(today, -29) to today

    /** 右上角那串区间文字。 */
    fun rangeLabel(mode: Mode, anchor: String, from: String? = null, to: String? = null): String =
        when (mode) {
            Mode.DAY -> dotted(anchor, withYear = true)
            Mode.WEEK -> {
                val mon = DayKey.mondayOf(anchor)
                "${dotted(mon, withYear = false)}-${dotted(DayKey.plus(mon, 6), withYear = false)}"
            }
            Mode.MONTH -> dotted(anchor, withYear = true).substringBeforeLast('.')
            Mode.YEAR -> DayKey.parse(anchor).year.toString()
            Mode.CUSTOM -> {
                if (from == null || to == null) "选区间"
                else "${dotted(from, withYear = true)}-${dotted(to, withYear = false)}"
            }
        }

    /** `'2026-10-14'` → `'2026.10.14'`（`withYear=false` 时 → `'10.14'`）。 */
    private fun dotted(key: String, withYear: Boolean): String {
        val d = DayKey.parse(key)
        val md = "%02d.%02d".format(d.monthValue, d.dayOfMonth)
        return if (withYear) "${d.year}.$md" else md
    }

    /** `'2026-10-14'` → `'10/14'`（横轴标签）。 */
    private fun shortMd(key: String): String {
        val d = DayKey.parse(key)
        return "${d.monthValue}/${d.dayOfMonth}"
    }

    // ============================================================ 曲线平滑

    /**
     * ★★ Catmull-Rom → 三次贝塞尔，**控制点钳制在本段两端点之间**。
     *
     * ## 为什么必须钳制（第 21 轮那个 bug，写进规格防复发）
     *
     * 裸的 Catmull-Rom 会**过冲**：控制点是用「前一点 → 后一点」的差分算的，
     * 序列陡变时控制点会跑到两端点之外，曲线于是越过 0 轴沉到负数区
     * （御坂截图报过「曲线掉到 0 以下」）。
     *
     * 典型触发场景：粉色的序列是 `[0, 0, 有值, 0, 0, 0, 0]`（周一二没记、
     * 周三四五六日是空白/未来），在「0 ↔ 有值」交界处两侧各鼓出一坨波浪。
     *
     * ⚠️ **这不是数据错，是画法错** —— 所以「断数据对不对」的测试一条都不会红。
     *
     * 修法：两个控制点都钳进 `[两端点]`。三次贝塞尔是控制点的**凸组合**，
     * 钳制后曲线必定落在两端点范围内，**结构上不可能越界**（负数、超顶一并没了）。
     * x 也要钳：采样虽均匀，但端点重复时 x 控制点同样会外冲。
     */
    fun smooth(points: List<Pt>): List<Cubic> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Cubic>(points.size - 1)

        for (m in 0 until points.size - 1) {
            val p0 = if (m - 1 >= 0) points[m - 1] else points[m]
            val p1 = points[m]
            val p2 = points[m + 1]
            val p3 = if (m + 2 < points.size) points[m + 2] else p2

            var c1x = p1.x + (p2.x - p0.x) / 6f
            var c1y = p1.y + (p2.y - p0.y) / 6f
            var c2x = p2.x - (p3.x - p1.x) / 6f
            var c2y = p2.y - (p3.y - p1.y) / 6f

            c1x = clamp(c1x, p1.x, p2.x)
            c2x = clamp(c2x, p1.x, p2.x)
            val ylo = minOf(p1.y, p2.y)
            val yhi = maxOf(p1.y, p2.y)
            c1y = clamp(c1y, ylo, yhi)   // ★ 这一行就是修复本身
            c2y = clamp(c2y, ylo, yhi)

            out += Cubic(p1, Pt(c1x, c1y), Pt(c2x, c2y), p2)
        }
        return out
    }

    /** 反向平滑 —— 画面积图的**下边界**要从右往左走。 */
    fun smoothReversed(points: List<Pt>): List<Cubic> = smooth(points.asReversed())

    private fun clamp(v: Float, a: Float, b: Float): Float =
        if (v < a) a else if (v > b) b else v

    // ============================================================ 自检

    /**
     * 自检 —— **空列表 = 没问题**。
     *
     * 生产代码里不调它（有遍历开销），测试里天天调：它就是这套口径的「断言」。
     * 跟 `Density.check()` 是同一个套路。
     *
     * 盯着四条最容易错、而且**错了不报错**的：
     * 1. `rec + gap == total`（`rec` 混进了 gap 的话，② 图会凭空变高）
     * 2. 事项合计 == `rec`（漏了哪条没算进去）
     * 3. ③ 首尾相接、总长恰好 = `total`（有缝或超长，柱子就画歪了）
     * 4. ★ **① 灰带厚度 == ③ 灰柱高度**（同一天两个未记录数 —— 原型踩过一次）
     */
    fun check(agg: Agg): List<String> {
        val problems = ArrayList<String>()

        agg.cols.forEach { c ->
            val tag = "「${c.label}」"

            if (c.rec + c.gap != c.total) {
                problems += "$tag rec(${c.rec}) + gap(${c.gap}) ≠ total(${c.total})"
            }
            val sum = c.items.values.sum()
            if (sum != c.rec) {
                problems += "$tag 事项合计($sum) ≠ rec(${c.rec})"
            }
            if (c.total > c.days.size * MINUTES_PER_DAY) {
                problems += "$tag total(${c.total}) 超过 days×1440(${c.days.size * MINUTES_PER_DAY})"
            }

            val t = c.timeline
            if (t.isEmpty()) return@forEach

            if (t.first().from != 0) problems += "$tag ③ 没从 0 开始（${t.first().from}）"
            if (t.last().to != c.total) problems += "$tag ③ 没铺到 cut（${t.last().to} ≠ ${c.total}）"
            t.zipWithNext().forEach { (x, y) ->
                if (x.to != y.from) problems += "$tag ③ 有缝：${x.to} → ${y.from}"
            }
            if (t.sumOf { it.minutes } != c.total) {
                problems += "$tag ③ 总长(${t.sumOf { it.minutes }}) ≠ total(${c.total})"
            }
            // ★ 这条是「两张图互相打架」的直接断言
            val tlGap = t.filter { it.isGap }.sumOf { it.minutes }
            if (tlGap != c.gap) {
                problems += "$tag ① 灰带(${tlGap}) ≠ ③ 灰柱(${c.gap}) —— 两张图对不上"
            }
        }
        return problems
    }
}
