package com.misaka.timelog.domain

/**
 * 模板的「懒结算」—— **纯函数，不碰数据库、不碰时钟**。
 *
 * ## 它在算什么
 *
 * 模板是这么一句话：
 *
 * > 「工作日 08:30–12:00 我在打工」——**这是默认值，不是命令**。
 *
 * 所以每次刷新时，引擎要回答的问题是：
 *
 * > **今天还剩哪些「空白」该按模板填上？**
 *
 * ## ★ 三条铁律怎么落在这里
 *
 * | 铁律 | 落法 |
 * |---|---|
 * | **只填空白，绝不覆盖你记过的** | 只有 `fillable` 的段才动；手动记的一律原样跳过 |
 * | **只补已经过去的时间** | 块的终点被 `cut` 卡死，未来一分钟都不铺 |
 * | **模板是兜底，不是命令** | 事项被删掉的段**不铺**，落回空白 —— 绝不凭空造「幽灵记录」 |
 *
 * ## ⚠️ 为什么必须做成纯函数
 *
 * 这是**第二次**用这条经验（第一次是 [TimelineAssembler]，第三次是 [Overlap]）：
 *
 * > 凡是「会牵动密铺不变式」的规则，都应该抠成纯函数。
 *
 * 好处是两个实实在在的东西：
 *
 * 1. **边界组合可以穷举** —— 不用起 Room、不用起 Robolectric，纯 JVM 秒级跑完。
 *    而这个引擎的边界特别多：段边界正好落在记录边界上、段跨了三条记录、
 *    锁定的段跑完之后、模板缺了一块（没密铺）……
 * 2. **规则变成一段能读的话**，而不是散在事务里的十几行 `if`。
 *
 * ## ⚠️⚠️ 真机版比原型多出来的一条规则：`TEMPLATE` 也能被重结算
 *
 * 原型是**每次把整个数组重建一遍**的（`applyTemplate()` 里 `out = []` 重新堆），
 * 所以「模板自己铺的那条会不会被再切一次」根本不是个问题。
 *
 * 真机是**按行存**的，就必须把话说明白，否则会出这个 bug：
 * 12:00 模板铺出「午休 [12:00, null)」（还在进行中），14:10 刷新时
 * 它应该被切成「午休 [12:00,14:00) + 打工 [14:00,14:10)」——
 * 但如果引擎只认 `GAP`，这条 `TEMPLATE` 就永远是一整段，**越滚越长，切不开**。
 *
 * 所以 [TplSpan.fillable] 的定义是：
 *
 * ```
 * 空白（GAP 且没被手动清过）        → 可填
 * 模板自己铺的、还「正在进行中」的那条 → 可重切（它还在长）
 * 其它（手动 / 跨天接续 / 已结束的模板段 / 手动清过的空白）→ 一根汗毛都不动
 * ```
 *
 * 「已结束的模板段」不再重切，是因为它**上一次刷新时就已经切对了** ——
 * 反复重算没有任何收益，只会让 `updatedAt` 天天变、云同步白跑。
 */
object TemplateEngine {

    /**
     * 切出来的计划。
     *
     * @param rewrites 要改写的空白段（**第一块沿用原记录的 id**，一条行都不许删）
     * @param lockExpired 锁定段已经跑完了 → 调用方该把它清掉，此后一律读最新模板
     */
    data class Plan(
        val rewrites: List<Rewrite>,
        val lockExpired: Boolean,
    ) {
        val isEmpty: Boolean get() = rewrites.isEmpty()

        companion object {
            val NONE = Plan(emptyList(), false)
        }
    }

    /**
     * 一条空白被切成若干块。**第一块沿用原记录**（原地改写），其余的才新插 ——
     * 这是「删除一律原地改写、禁止 filter」那条铁律在模板上的同一套做法。
     */
    data class Rewrite(val originId: String, val blocks: List<Block>)

    /** @param itemId `null` = 这一段填不上（模板指的事项被删了）→ 保持空白。 */
    data class Block(val from: Int, val to: Int, val itemId: String?, val categoryId: String?)

    /**
     * 引擎看到的一条「占了时间的东西」—— 只关心它在哪、能不能被填。
     *
     * ⚠️ 刻意**不带 `RecordEntity`**：带上它，这个文件就再也测不成纯 JVM 的了。
     */
    data class Span(
        val id: String,
        val startMin: Int,
        val endMin: Int,
        val fillable: Boolean,
    )

    /**
     * 模板里的一段（事项已经解析好了）。
     *
     * @param itemId `null` = 这一段指的事项**已被删除**（`template_segments.item_id`
     *   被外键 `SET_NULL` 置空了），或者引用的 id 在字典里查不到。
     *   ⚠️ 这种段**必须不铺** —— 铺出来会是一条「名字还挂着、点进去什么都不是」
     *   的幽灵记录，比不铺更糟（统计里还算它一份）。
     */
    data class Seg(
        val startMin: Int,
        val endMin: Int,
        val itemId: String?,
        val categoryId: String? = null,
    )

    /**
     * 算出「要改写哪几段空白、每段切成什么样」。
     *
     * @param spans 这一天**当时**的全部记录（顺序随意，内部会排）
     * @param segs  模板的段，**必须密铺 0..1440**（首尾相接、不重叠）
     * @param lock  ★ 启动那一刻锁住的「正在执行的那一段」。
     *   运行中你去改模板，这一段**先按【旧】的跑完**，之后才用新模板。
     * @param cut   这一天铺到第几分钟（未来不铺）
     */
    fun settle(
        spans: List<Span>,
        segs: List<Seg>,
        lock: Seg? = null,
        cut: Int,
    ): Plan {
        if (cut <= 0) return Plan.NONE

        val rewrites = mutableListOf<Rewrite>()

        spans.sortedBy { it.startMin }.forEach { span ->
            if (!span.fillable) return@forEach
            if (span.endMin <= span.startMin) return@forEach

            // ⚠️ 正常流程下不会发生（ensureDense 保证所有记录都停在 cut 之内）。
            //    真发生了也**宁可不动** —— 因为「只铺到 cut」意味着要把这条记录截断，
            //    而被截掉的那一截就凭空消失了。那是拿一个坏状态换另一个更坏的。
            if (span.endMin > cut) return@forEach

            val blocks = split(span, segs, lock)

            // 一块都填不上（模板引的事项全被删了）→ 白改一场，跳过
            if (blocks.all { it.itemId == null }) return@forEach

            rewrites += Rewrite(span.id, blocks)
        }

        return Plan(rewrites, lockExpired = lock != null && cut > lock.endMin)
    }

    /** 把一条空白按**段边界**切开。 */
    private fun split(span: Span, segs: List<Seg>, lock: Seg?): List<Block> {
        val blocks = mutableListOf<Block>()
        var cur = span.startMin

        while (cur < span.endMin) {
            val seg = segAt(cur, segs, lock)
            var to = if (seg != null) minOf(seg.endMin, span.endMin) else span.endMin

            // ⚠️ 防死循环：万一某一段自己就是坏的（`end <= start`，比如模板没密铺），
            //    就一口气铺到这条记录的终点，绝不原地打转。
            if (to <= cur) to = span.endMin

            val itemId = seg?.itemId
            blocks += Block(
                from = cur,
                to = to,
                itemId = itemId,
                categoryId = if (itemId != null) seg.categoryId else null,
            )
            cur = to
        }
        return blocks
    }

    /**
     * 第 [min] 分钟归哪一段。
     *
     * **锁定的段优先** —— 这就是「当前正在执行的那一段先按旧模板跑完」那句话。
     *
     * ⚠️ 查不到就返回 `null`（保持空白），**不是**「兜到最近的一段」。
     * 模板本来必须密铺到 24:00，查不到就说明模板自己坏了；
     * 这种时候宁可留白（你能看见），也不能拿旁边那段的名字硬套上去（你看不见）。
     */
    fun segAt(min: Int, segs: List<Seg>, lock: Seg? = null): Seg? {
        if (lock != null && min >= lock.startMin && min < lock.endMin) return lock
        return segs.firstOrNull { min >= it.startMin && min < it.endMin }
    }
}

/**
 * 「锁定段」的编解码 —— 存进 `template_run.lockedSegSnapshot` 那一列。
 *
 * ## ⚠️ 为什么手写字符串而不用 `org.json`
 *
 * `org.json` 是 Android 提供的，**纯 JVM 单元测试里拿到的是一份会抛
 * `RuntimeException("not mocked")` 的空壳**。这个编解码会被 [TemplateEngine] 的
 * 纯 JVM 测试直接用到 —— 用 `org.json` 就等于把那批测试一起拖进 Robolectric（慢十倍）。
 *
 * 而这里真的没什么好序列化的：四个标量。用 `|` 拼一下就完了，
 * 还顺带**天生容错**（列里是脏数据就返回 `null`，退回「读最新模板」，不会崩）。
 */
object TemplateLock {

    /** 分隔符用 `|`：它不会出现在 uuid 里，也不用转义。 */
    private const val SEP = '|'

    fun encode(seg: TemplateEngine.Seg): String = listOf(
        seg.startMin.toString(),
        seg.endMin.toString(),
        seg.itemId.orEmpty(),
        seg.categoryId.orEmpty(),
    ).joinToString(SEP.toString())

    /** 解析失败 / 空的 / 形状不对 → `null`（= 没有锁定，读最新模板）。 */
    fun decode(raw: String?): TemplateEngine.Seg? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SEP)
        if (parts.size < 4) return null

        val start = parts[0].toIntOrNull() ?: return null
        val end = parts[1].toIntOrNull() ?: return null
        if (end <= start) return null

        return TemplateEngine.Seg(
            startMin = start,
            endMin = end,
            itemId = parts[2].ifEmpty { null },
            categoryId = parts[3].ifEmpty { null },
        )
    }
}
