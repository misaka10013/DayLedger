package com.misaka.timelog.domain

/**
 * 撞车让位 —— 改一条记录的时间，撞到邻居时怎么办。
 *
 * ## 规则（从原型移植，一个字没改）
 *
 * > **改的那条优先，被压住的邻居自动缩短** —— 不弹窗问「要不要覆盖」。
 *
 * 为什么不让用户选：时间线上一天要改好几次时间，每次都弹一个「这条要覆盖别人哦」
 * 就是一秒钟的打断乘以十几次。而这件事的正确处理**几乎没有歧义**：
 * 你把新时间划出来，那段时间就是你的，原来在那儿的东西自然要让开。
 * 改完用一条提示告诉用户「谁被压短了、压到几点」——事后可查，比事前拦一道好。
 *
 * ## 三种让位
 *
 * | 情况 | 处理 |
 * |---|---|
 * | 邻居**被完全吞掉** | 删掉它 —— 那段时间已经被改动的那条完整接管，没有哪一段时间失去代表 |
 * | 邻居在你**前面**（它的头伸进了你的地盘） | 把它的**尾巴切到你的起点** |
 * | 邻居在你**后面**（它的尾伸进了你的地盘） | 把它的**头切到你的终点** |
 *
 * ## ⚠️ 为什么「被完全吞掉」可以真的删，这不违反「删除一律原地改写」
 *
 * 那条铁律的理由是：**删掉一行会让那段时间失去代表**，密铺里出现一个洞。
 * 而这里恰恰相反 —— 它的那段时间**已经有人代表了**（就是改动的那条），
 * 所以删它是安全的、也是唯一正确的选择。
 *
 * 反过来，如果把它原地改成空白，那段时间会**同时**被空白和改动的那条覆盖，
 * 那是重叠，比留个洞更糟。
 *
 * ## ⚠️ 一个已知的简化：邻居把改动的那条整个包住时
 *
 * 极端情况（把一条记录挪进另一条的内部），代码只会把邻居的尾巴切到你的起点，
 * 于是你终点到邻居原终点那一截会暂时没有代表 —— 这一截由 `ensureDense()` 补成
 * **一条空白**。语义恰好是对的（那段时间确实没人记），所以不额外处理。
 *
 * ## 纯函数
 *
 * 这里不碰数据库、不碰 Android。因为这个函数一旦错了，症状是**几个月后统计数字对不上**，
 * 而那时候已经查不出是哪次操作弄的。所以它必须能被穷举测试 —— 输入输出都是值，
 * 测起来是毫秒级的。
 */
object Overlap {

    /**
     * 一段时间 —— 只带让位需要的最小信息。
     *
     * 特意**不用 `RecordEntity`**：那样这个函数就得 import 数据层的类型，
     * 测试也会被拖进 Room。这里只要「谁、从哪、到哪」。
     */
    data class Span(
        val id: String,
        val startMin: Int,
        /** `null` = 正在进行（终点 = 此刻，由 [resolve] 的 `cut` 给出）。 */
        val endMin: Int?,
    )

    /** 要对某条邻居做的事。 */
    sealed interface Effect {
        val id: String

        /** 把它的尾巴切到 [newEnd]。 */
        data class TrimEnd(override val id: String, val newEnd: Int) : Effect

        /** 把它的头切到 [newStart]。 */
        data class TrimStart(override val id: String, val newStart: Int) : Effect

        /** 它被完整接管了 —— 删掉它。 */
        data class Drop(override val id: String) : Effect
    }

    /**
     * 算出改动 [me] 之后，其它记录各自要让位成什么样。
     *
     * @param me 改动**之后**的那条（起点/终点都是新值）
     * @param others 当天**其它**记录（含 `me` 也没关系，内部按 id 跳过）
     * @param cut 这一天铺到哪 —— 进行中的记录（`endMin == null`）的终点按它算
     * @return 要执行的副作用。**空列表 = 没撞到任何人。**
     */
    fun resolve(me: Span, others: List<Span>, cut: Int): List<Effect> {
        val ma = me.startMin
        val mb = me.endMin ?: cut

        // 长度为零或倒挂：这条记录不代表任何时间，没资格挤开别人
        if (mb <= ma) return emptyList()

        val out = ArrayList<Effect>(2)
        others.forEach { o ->
            if (o.id == me.id) return@forEach

            val oa = o.startMin
            val ob = o.endMin ?: cut

            // 不重叠（首尾相接不算重叠 —— 密铺里正常情况就是首尾相接）
            if (oa >= mb || ob <= ma) return@forEach

            when {
                // 完全在 me 的区间里 → 被接管，删掉
                oa >= ma && ob <= mb -> out += Effect.Drop(o.id)

                // 它的头伸进 me 的地盘 → 头切到 me 的终点
                oa >= ma -> out += Effect.TrimStart(o.id, mb)

                // 它的尾伸进 me 的地盘 → 尾切到 me 的起点
                else -> out += Effect.TrimEnd(o.id, ma)
            }
        }
        return out
    }

    /**
     * 把一天里「首尾相接的相邻空白」找出来 —— 合并用。
     *
     * 不合并不会破坏不变式，但时间线上会并排出现一串长得一模一样的「▨ 未记录」，
     * 看着就像界面出了问题。
     *
     * @return 一对对「保留哪条、吞掉哪条」。**方向是（保留，吞掉）**，
     *   调用方要把前者的终点拉到后者的终点、再把后者删掉。
     */
    fun adjacentGapPairs(list: List<Span>, isGap: (String) -> Boolean): List<Pair<String, String>> {
        val sorted = list.sortedBy { it.startMin }
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val joined = isGap(a.id) && isGap(b.id) &&
                a.endMin != null && a.endMin == b.startMin
            if (joined) {
                out += a.id to b.id
                i += 2 // 吞掉 b —— 别让它跟下一条再配一次
            } else {
                i += 1
            }
        }
        return out
    }
}
