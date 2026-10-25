package com.misaka.timelog.domain

/**
 * 调色板 —— **分类是色系，事项是色系里的渐变档**。
 *
 * ## 为什么需要这个东西
 *
 * 原来的模型是「一个事项一个颜色」，但 seed 之后**同一分类下所有事项都照抄了分类色**，
 * 于是 10 个分类、63 个事项实际上只有 10 种颜色。在时间线和统计图里：
 *
 * - 「看书」和「AI讨论」同属「精神输入」→ **颜色完全一样，分不出来**；
 * - 「打工」「兼职」「加班」同属「赚钱营生」→ 同上。
 *
 * 统计图是按事项切片的，一屏里同色块挨在一起，等于没有信息。
 *
 * ## 规则
 *
 * ```
 * 12 个色系（hue 均匀 30°）
 *   └─ 每个色系 8 档
 *        ├─ 主色（L=0.56）      → 分类用
 *        └─ 8 档渐变（L 0.74→0.28）→ 事项按 orderInCat 顺序取
 * ```
 *
 * ★ **12 个而不是 10 个**：御坂现有 10 个分类，留 2 个空槽给以后新增，
 * 免得「加第 11 个分类时又要重排一遍」。
 *
 * ★ **事项颜色仍然可以手动指定**（`items.colorHex` 非空即视为手选，不再派生）。
 * 所以这里只负责「没手选时该是什么颜色」，不剥夺手选能力。
 *
 * ## 为什么 hue 从 0° 开始均匀 30° 排
 *
 * 分类之间要能一眼分开，靠的就是**色相拉开**。原来 10 个分类色里有 4 个挤在
 * 红紫区（`#DE2E63` / `#DC3545` / `#F062A0` / `#C77DBB`），统计图上根本分不出是
 * 两个分类。均匀 30° 之后，相邻两个分类的色相至少差 30°，肉眼一定能分开。
 *
 * ## 单位约定
 *
 * 输出的都是 `#RRGGBB` 大写十六进制字符串 —— 跟数据库里 `colorHex` 字段的格式一致，
 * 这样「派生出来的颜色」和「手选的颜色」在库里长得一样，界面层不用区分。
 */
object ColorSeries {

    /** 色系数量。**改这个会让所有分类的色相重新排一遍** —— 历史记录不受影响（它们只存 id）。 */
    const val SERIES = 12

    /** 每个色系有几档渐变。最大的分类目前 8 个事项，正好铺满。 */
    const val SHADES = 8

    /** 分类主色的饱和度 / 明度。12 个色系统一用这两个值，保证「12 个分类视觉份量一样重」。 */
    private const val CAT_S = 0.62f
    private const val CAT_L = 0.56f

    /**
     * 12 个色系的色相（度）。
     *
     * ⚠️ 顺序就是「分类分配顺序」：第 0 个分类拿 0°（红），第 1 个拿 30°（橙）……
     *    迁移时会按**就近匹配 + 冲突顺延**重新分配（见 `SeriesAssign`），不是死按 position。
     */
    private val HUES = floatArrayOf(0f, 30f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f)

    /**
     * 8 档渐变的明度序列 —— **严格单调递减**，第 0 档最亮。
     *
     * 为什么是「明度单调整条」而不是「亮→暗→亮」：
     * 统计图里事项的堆叠顺序是固定的（按分类次序 + 组内次序），
     * 相邻两档的差距必须**稳定**，否则「哪块是排在前面的那个事项」就看不出来了。
     *
     * 跨度取 0.74 → 0.28：
     * - 最亮那档在**浅色**卡片上还能看见（对比度 ≈ 1.33）;
     * - 最暗那档在**深色**卡片上还能看见（对比度 ≈ 2.06）。
     *
     * ⚠️ 别为了「更好看」把这些值都挤到中间 —— 挤中间等于同分类事项又分不出来了。
     */
    private val SHADE_L = floatArrayOf(0.74f, 0.67f, 0.60f, 0.53f, 0.46f, 0.40f, 0.34f, 0.28f)

    /** 高档位稍微降一点饱和度，避免浅色档发白（像「掉色」而不是「浅色」）。 */
    private fun shadeS(k: Int): Float = CAT_S - k * 0.02f

    // ---------------------------------------------------------------- 对外

    /** 第 [index] 个色系的色相（0..359）。越界会绕回来。 */
    fun seriesHue(index: Int): Float = HUES[Math.floorMod(index, SERIES)]

    /** 第 [index] 个色系的**主色** —— 分类用这个。 */
    fun categoryColor(index: Int): String = hslToHex(seriesHue(index), CAT_S, CAT_L)

    /** 第 [index] 个色系的 8 档渐变（不含主色）。事项按 `orderInCat` 取。 */
    fun shades(index: Int): List<String> {
        val h = seriesHue(index)
        return (0 until SHADES).map { k -> hslToHex(h, shadeS(k), SHADE_L[k]) }
    }

    /**
     * 事项该是什么颜色。
     *
     * @param categoryHex 所属分类的颜色。**只取它的色相** —— 饱和度和明度由本调色板统一给，
     *                    这样「用户把分类色改成别的色系」时，组内 8 档仍然是协调的一套。
     * @param orderInCat  在本分类内的次序（0 起）。超过 8 会**循环回第 0 档**
     *                    （分类里加到第 9 个事项时会和第一个同色 —— 可接受，因为
     *                    真正超过 8 个事项的分类很少，且用户还能手动指定）。
     */
    fun itemColor(categoryHex: String, orderInCat: Int): String {
        val h = hueOf(categoryHex) ?: seriesHue(0)
        val k = Math.floorMod(orderInCat, SHADES)
        return hslToHex(h, shadeS(k), SHADE_L[k])
    }

    /**
     * 一个任意颜色最接近哪个色系（0..SERIES-1）。
     *
     * 迁移时用它把「现有的 10 个分类色」尽量留在原来的色相附近 ——
     * 能不动就不动，只有挤在一起的才被拉开。
     */
    fun nearestSeries(hex: String): Int {
        val h = hueOf(hex) ?: return 0
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until SERIES) {
            val d = hueDistance(h, HUES[i])
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 两个色相之间的最短距离（考虑环绕，0..180）。 */
    fun hueDistance(a: Float, b: Float): Float {
        val d = Math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** `#RRGGBB` → 色相（0..360）。解析不了返回 null。 */
    fun hueOf(hex: String): Float? {
        val rgb = parseHex(hex) ?: return null
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (d < 1e-6f) return 0f // 纯灰，没有色相 → 归到第 0 系
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return if (h < 0) h + 360f else h
    }

    /** `#RRGGBB` 或 `#AARRGGBB` → 0xRRGGBB。解析不了返回 null。 */
    internal fun parseHex(hex: String): Int? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6 && s.length != 8) return null
        val v = s.toLongOrNull(16) ?: return null
        return if (s.length == 8) (v and 0xFFFFFF).toInt() else v.toInt()
    }

    /**
     * 新建分类时该默认用哪个色系 —— **第一个还没被占用的**。
     *
     * ## 为什么不让用户一上来就挑 12 个里的某一个
     *
     * 可以挑（新建对话框里 12 个色块都摆着），但**默认值得是空的**：
     * 用户点「新建分类」时脑子里想的是名字，不是颜色。给个已占用的色系会让他
     * 建完发现两个分类一个颜色，再回头改 —— 白跑一趟。
     *
     * 全占满了（分类数 ≥ 12）就按数量取模，反正也躲不开重复，但至少要稳定（不是随机的）。
     */
    fun nextFreeSeries(usedHex: List<String>): Int {
        val taken = usedHex.map { it.trim().uppercase() }.toSet()
        return (0 until SERIES).firstOrNull { categoryColor(it).uppercase() !in taken }
            ?: (usedHex.size % SERIES)
    }

    /** 一组分类该用哪些色块（新建/改色对话框里摆的）。 */
    fun allCategoryColors(): List<String> = (0 until SERIES).map { categoryColor(it) }

    /**
     * 某个分类色系下的 8 档渐变（给事项挑色用）。
     *
     * 传的是分类的颜色（不管它是不是标准主色），靠色相认亲 ——
     * 所以用户把分类色改成偏一点的颜色，事项的 8 档仍然跟着它走。
     */
    fun shadesOfCategory(categoryHex: String): List<String> {
        val h = hueOf(categoryHex) ?: seriesHue(0)
        return (0 until SHADES).map { k -> hslToHex(h, shadeS(k), SHADE_L[k]) }
    }

    // ---------------------------------------------------------------- 分配

    /**
     * 给一批分类分配色系序号 —— **尽量不动原有色相，只把挤在一起的拉开**。
     *
     * 算法：按传入顺序（调用方保证是 `position` 升序）逐个处理，
     * 每个分类在**还没被占用**的槽位里挑色相最接近自己的那个。
     *
     * ## 实测效果（御坂现有的 10 个分类）
     *
     * | 分类 | 原色 | 原 hue | 分到 | 变化 |
     * |---|---|---|---|---|
     * | 生活必需 | `#E8A33D` | 36 | 30 橙 | 几乎不变 |
     * | 赚钱营生 | `#8A9A3C` | 76 | 90 黄绿 | 接近 |
     * | 放松娱乐 | `#B7D96A` | 77 | 60 黄 | 略偏黄 |
     * | 杂事 | `#7CB342` | 100 | 120 绿 | 接近 |
     * | 生理休息 | `#6C7AE0` | 232 | 210 天蓝 | 接近 |
     * | 技能成长 | `#7B3FC4` | 264 | 270 靛 | 几乎不变 |
     * | 人际交往 | `#C77DBB` | 300 | 240 蓝 | **明显变**（红紫区太挤，被让出位置） |
     * | 精神输入 | `#DE2E63` | 337 | 330 品红 | 几乎不变 |
     * | 恋爱相伴 | `#F062A0` | 338 | 300 紫 | **明显变**（同上） |
     * | 动手输出 | `#DC3545` | 356 | 0 红 | 几乎不变 |
     *
     * 10 个里 7 个基本不动，只有挤在红紫区的两个被拉开 —— 这正是要的效果。
     * 剩下 150°（青绿）、180°（青）两个空槽留给以后新增的分类。
     *
     * ⚠️ **分类数超过 [SERIES]（12）时会有两个分类分到同一色系**（没有别的槽位了）。
     *    这时组内渐变还能区分事项，但两个分类之间会混 —— 真到那天得把 SERIES 调大，
     *    或者给分类加「手选色系」的入口。
     */
    fun assignSeries(existingHexes: List<String>): List<Int> {
        val n = existingHexes.size
        if (n == 0) return emptyList()
        val used = BooleanArray(SERIES)
        val out = IntArray(n)

        for (i in 0 until n) {
            val hue = hueOf(existingHexes[i])
            val order = (0 until SERIES).sortedBy { s ->
                if (hue == null) s.toFloat() else hueDistance(hue, HUES[s])
            }
            // 优先未占用；全占满了（分类数 > 12）就退回最近的
            val pick = order.firstOrNull { !used[it] } ?: order.first()
            out[i] = pick
            used[pick] = true
        }
        return out.toList()
    }

    // ---------------------------------------------------------------- HSL

    /**
     * HSL → `#RRGGBB`。
     *
     * 标准公式（CSS Color 3 那套）。手写而不是用 `android.graphics.ColorUtils`：
     * 那个是 Android 框架类，**在纯 JVM 单测里跑不起来** —— 而这个类是纯逻辑，
     * 必须能在 JVM 里被测到（它是整个调色板的唯一出口）。
     */
    private fun hslToHex(h: Float, s: Float, l: Float): String {
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        val c = (1f - Math.abs(2f * ll - 1f)) * ss
        val hp = (h % 360f + 360f) % 360f / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = ll - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(ch(r1), ch(g1), ch(b1))
    }
}
