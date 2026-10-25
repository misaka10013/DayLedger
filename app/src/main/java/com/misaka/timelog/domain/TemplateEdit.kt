package com.misaka.timelog.domain

/**
 * 模板段的编辑规则 —— **纯函数**，不碰数据库、不碰时钟、不碰 UI。
 *
 * ## 它在守什么
 *
 * 模板是「预定的一天」，所以它有一条别的数据结构都没有的硬约束：
 *
 * > **段与段必须首尾相接、严丝合缝地铺满 `00:00 → 24:00`。**
 * > 不能有缝、不能重叠。想留白就塞一段「未记录」性质的事项进去，**不许挖洞**。
 *
 * 这条约束破掉的症状是**安静的**：色条上多出一条几像素的窄缝，你根本看不出来，
 * 但那段时间从此不属于任何一段 —— 模板铺到那里就不铺了，
 * 你只会觉得「模板今天怎么漏了一块」，而它不报错、不崩。
 *
 * 所以**每一次编辑都不是「改一个数」，而是「挪一段的同时把邻居补偿回去」**：
 *
 * | 编辑动作 | 邻居怎么补偿 |
 * |---|---|
 * | 挪起点 | 上一段的**终点**跟着走 |
 * | 挪终点 | 下一段的**起点**跟着走 |
 * | 拆两段 | 不涉及邻居（自己一刀两半，天然不破） |
 * | 删一段 | 时长**并给相邻段**，不留洞 |
 *
 * ## 为什么又是纯函数（第四次了）
 *
 * 前三次是 [TimelineAssembler] / [Overlap] / [TemplateEngine]，理由一模一样：
 *
 * > 凡是「会牵动密铺不变式」的规则，都该抠成纯函数。
 *
 * 这里的边界组合看着不多，其实很密：第一段 / 最后一段（两端锚死）、
 * 只剩一段时不许删、挪到「自己只剩 1 分钟」或「邻居只剩 1 分钟」时该停手、
 * 拆 1 分钟 / 2 分钟 / 奇数分钟的段……**只有纯函数才穷举得动**。
 *
 * ## ⚠️ 从原型搬过来时特意保留的一个「坑」（第 17 轮踩过，值钱）
 *
 * 挪边界时用来夹住范围的上下限，**必须取本函数不会改到的量**。
 * 原型在这上面栽过：挪起点时上界误用了「上一段的终点」—— 而那正是这一步要改的东西，
 * 于是「退 15 分、再进 15 分」回不到原位，实测 `12:00 → 11:45 → 11:44`。
 * 详见 [moveEdge]。
 */
object TemplateEdit {

    /**
     * 编辑视角的一段：只关心「占了哪段时间」和「记什么」。
     *
     * ⚠️ 刻意**不带分类**：分类是从 `itemId` 现查出来的，不是段自己的一部分。
     * 段里存一份分类的副本，就等于给「改事项归属分类、历史统计跟着重算」这条规则
     * 埋一颗定时炸弹（那正是第 11 轮推翻过的做法）。
     *
     * @param itemId `null` = 这一段指的事项**已被删除**，等用户重选。
     *   ⚠️ 它**不是**「未记录」的意思 —— 想留白要显式选一个「未记录」性质的事项，
     *   这是「不许挖洞」那条规矩在数据形状上的体现。
     */
    data class Seg(
        val startMin: Int,
        val endMin: Int,
        val itemId: String?,
    )

    /** 哪一条边界。 */
    enum class Edge { Start, End }

    // ------------------------------------------------------------ 星期指派

    /** 七天全亮（`0b1111111`）。 */
    const val FULL_WEEK = 0b1111111

    /**
     * 工作日（周一~周五，`0b0011111` = 31）。
     *
     * ⚠️ 位序跟 [DayKey.weekdayBit] 是同一套：**bit0 = 周一** … **bit6 = 周日**。
     * 别跟 `java.util.Calendar` 那套（周日 = 0）混了。
     */
    const val WORKDAYS = 0b0011111

    /** 显示顺序里的第 [index] 天（`0` = 周一 … `6` = 周日）占哪一位。 */
    fun weekdayBit(index: Int): Int = 1 shl index

    /** 点一下某一天：亮 → 灭、灭 → 亮。 */
    fun toggleWeekday(mask: Int, index: Int): Int = mask xor weekdayBit(index)

    /** 指派了几天。 */
    fun weekdayCount(mask: Int): Int = Integer.bitCount(mask and FULL_WEEK)

    /**
     * 指派了哪几天 → 中文（模板页状态行用）。
     *
     * 「每天」「工作日」给短说法 —— 它们是两种最常见的情况，
     * 逐天列出来会变成「周一 / 周二 / 周三 / 周四 / 周五」这么长一串。
     * ⚠️ 这里跟 [weekdaysShort] 的判断必须**同一套**，否则会出现
     * 「状态行写工作日、设置页写一二三四五」这种两个地方各说各的。
     */
    fun weekdaysText(mask: Int): String = when (val m = mask and FULL_WEEK) {
        0 -> "没有任何一天"
        FULL_WEEK -> "每天"
        WORKDAYS -> "工作日"
        else -> litDays(m).joinToString(" / ") { "周${WEEKDAY_CN[it]}" }
    }

    /** 设置页那一行用的极短版（右边还有个 `›`，写太长会把箭头挤掉）。 */
    fun weekdaysShort(mask: Int): String = when (val m = mask and FULL_WEEK) {
        0 -> "没指派"
        FULL_WEEK -> "每天"
        WORKDAYS -> "工作日"
        else -> litDays(m).joinToString("") { WEEKDAY_CN[it] }
    }

    private fun litDays(mask: Int): List<Int> = (0..6).filter { (mask shr it) and 1 == 1 }

    /**
     * 星期几的单字（`0` = 周一 … `6` = 周日）。
     *
     * ⚠️ 公开出来是为了让配置页那七个方块用它 —— 「一 二 三 四 五 六 日」
     * 这个顺序如果界面自己再写一份，迟早会出现色条和方块各排各的。
     */
    fun weekdayCn(index: Int): String = WEEKDAY_CN[index.coerceIn(0, 6)]

    /** ⚠️ 索引是 `0` = 周一，跟 [DayKey] 的口径一致（不是 `Calendar` 那套）。 */
    private val WEEKDAY_CN = listOf("一", "二", "三", "四", "五", "六", "日")

    // ------------------------------------------------------------ 段编辑

    /**
     * 查第 [min] 分钟落在第几段。
     *
     * 跟 [TemplateEngine.segAt] 同一个口径（左闭右开），`24:00` 整点归最后一段。
     * 查不到也返回最后一段 —— 这是给「色条上点到边界」用的容错，
     * 配置页的点击不该因为差了 1 分钟就没反应。
     */
    fun segIndexAt(segs: List<Seg>, min: Int): Int =
        segs.indexOfFirst { min >= it.startMin && min < it.endMin }
            .takeIf { it >= 0 }
            ?: segs.lastIndex

    /**
     * 挪一条边界，**相邻那段自动伸缩** —— 这就是「模板永远严丝合缝」的落法。
     *
     * @return 改好的新列表；`null` = **挪不动**（顶到底了 / 顶到邻居了），
     *   调用方该给一句提示，而不是静默什么都不做 —— 点了没反应时，
     *   用户分不清是「到极限了」还是「按钮坏了」。
     *
     * ## 两端锚死
     *
     * 第一段的起点恒为 `00:00`、最后一段的终点恒为 `24:00`。
     * 动了它们就不成「一天」了，所以直接返回 `null`。
     *
     * ## ⚠️ 上下限只许取「本函数不会改到的量」（原型第 17 轮踩的坑）
     *
     * - 挪**起点**：下界用**上一段的起点**、上界用**自己的终点**。
     *   这两个值本函数都不碰。
     *   ⚠️ **绝不能**用上一段的终点当上界 —— 那正是这一步要改的东西：
     *   夹一下之后 `cur` 被改小，下一轮 `+delta` 就从新的 `cur` 出发，
     *   于是「退 15 分再进 15 分」回不到原位（原型实测 `12:00 → 11:45 → 11:44`）。
     * - 挪**终点**：下界用自己的起点、上界用**下一段的终点**，同理。
     *
     * 另外两侧都留 1 分钟余地：**段不允许变成零长度** ——
     * 零长度段在色条上画不出来，你就再也点不到它，等于凭空少了一段，
     * 而模型里它还在，于是「模板明明铺满 24 小时」和「色条上有东西看不见」同时成立。
     */
    fun moveEdge(segs: List<Seg>, index: Int, edge: Edge, delta: Int): List<Seg>? {
        val seg = segs.getOrNull(index) ?: return null

        return when (edge) {
            Edge.Start -> {
                if (index == 0) return null                      // 第一段起点永远是 00:00
                val prev = segs[index - 1]
                val lo = prev.startMin + 1                       // 上一段至少留 1 分钟
                val hi = seg.endMin - 1                          // 自己至少留 1 分钟
                if (lo > hi) return null                         // 段已经贴着邻居了
                // ⚠️ 用 max(min(...)) 而不是 coerceIn：coerceIn 在 lo > hi 时会抛异常，
                //    而这是「数据本来就坏」的场景，不该让界面崩。
                val target = maxOf(lo, minOf(hi, seg.startMin + delta))
                if (target == seg.startMin) return null          // 原地不动 = 没挪动
                segs.toMutableList().apply {
                    this[index] = seg.copy(startMin = target)
                    this[index - 1] = prev.copy(endMin = target) // ★ 上一段的终点跟着走
                }
            }

            Edge.End -> {
                if (index == segs.lastIndex) return null         // 最后一段终点永远是 24:00
                val next = segs[index + 1]
                val lo = seg.startMin + 1
                val hi = next.endMin - 1                         // 下一段至少留 1 分钟
                if (lo > hi) return null
                val target = maxOf(lo, minOf(hi, seg.endMin + delta))
                if (target == seg.endMin) return null
                segs.toMutableList().apply {
                    this[index] = seg.copy(endMin = target)
                    this[index + 1] = next.copy(startMin = target) // ★ 下一段的起点跟着走
                }
            }
        }
    }

    /**
     * 从中间一刀两半 —— **完全不涉及邻居**（自己切自己，密铺天然不破）。
     *
     * 拆出来的后半段**先沿用同一个事项**：想换的话再去点它一下。
     * 因为「拆」的动机通常是「上午下午记的其实不是一回事」，
     * 而按下这一下的时候你脑子里只有「把时间切开」，还没想好第二段叫什么。
     *
     * @return `null` = 这一段太短，拆不出两个非空的段（至少要 2 分钟）。
     */
    fun split(segs: List<Seg>, index: Int): List<Seg>? {
        val seg = segs.getOrNull(index) ?: return null
        if (seg.endMin - seg.startMin < 2) return null
        val mid = seg.startMin + (seg.endMin - seg.startMin) / 2
        return segs.toMutableList().apply {
            this[index] = seg.copy(endMin = mid)
            add(index + 1, Seg(startMin = mid, endMin = seg.endMin, itemId = seg.itemId))
        }
    }

    /**
     * 删掉一段，**时长并给相邻段**。
     *
     * 第一段并给下一段（把它的起点让出去），其余并给上一段（把它的终点吃掉）。
     * 模板不留洞，所以「删」在这张表里不是 `DELETE`，是「**把这段时间交给邻居**」。
     *
     * ⚠️ 只剩一段时不许删 —— 删了就凑不成一天。
     *
     * @return `null` = 删不掉（只剩一段了）。
     */
    fun delete(segs: List<Seg>, index: Int): List<Seg>? {
        val seg = segs.getOrNull(index) ?: return null
        if (segs.size <= 1) return null
        return segs.toMutableList().apply {
            if (index > 0) {
                this[index - 1] = this[index - 1].copy(endMin = seg.endMin)
                removeAt(index)
            } else {
                this[1] = this[1].copy(startMin = seg.startMin)
                removeAt(0)
            }
        }
    }

    /**
     * 换这一段记什么 —— **起止时间一点都不动**。
     *
     * ⚠️ 为什么不顺手支持「改成未记录」：段上没有「未记录」这个状态
     * （那是 `itemId == null`，而它的含义是「事项被删了、等重选」）。
     * 想留白就选一个真的叫「未记录」的事项 —— 它在字典里是一个正常事项，
     * 有颜色、能统计、统计页上单独一行。这比给段加一个第二种类别的空值干净得多。
     */
    fun setItem(segs: List<Seg>, index: Int, itemId: String?): List<Seg> {
        val seg = segs.getOrNull(index) ?: return segs
        return segs.toMutableList().apply { this[index] = seg.copy(itemId = itemId) }
    }

    /**
     * 完整性自检 —— 返回一句中文，**空串 = 没问题**。
     *
     * 这是密铺不变式在模板侧的**唯一判据**：写入口拿它硬拦
     * （跟 [Density] 之于记录是同一个角色），界面上也直接显示这句话 ——
     * 规则只有一份，界面不重新表述一遍。
     */
    fun check(segs: List<Seg>): String {
        if (segs.isEmpty()) return "模板是空的"
        if (segs.first().startMin != 0) return "第一段不从 00:00 开始"
        if (segs.last().endMin != MINUTES_PER_DAY) return "最后一段不到 24:00"

        // 零长度段单独查：它们能骗过「接不上」那道检查
        // （`[0,0] + [0,1440]` 首尾相接得完美无缺，但第一段根本画不出来）
        segs.forEachIndexed { i, s ->
            if (s.endMin <= s.startMin) return "第 ${i + 1} 段是零长度的"
        }

        for (i in 1..segs.lastIndex) {
            if (segs[i].startMin != segs[i - 1].endMin) {
                return "第 ${i + 1} 段和上一段接不上"
            }
        }
        return ""
    }
}
