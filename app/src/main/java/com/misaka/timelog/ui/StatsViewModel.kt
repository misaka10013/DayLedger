package com.misaka.timelog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misaka.timelog.TimeLogApp
import com.misaka.timelog.data.repo.StatsRepository
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.StatsCalc
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 统计页的状态机。
 *
 * ## 它有多薄
 *
 * 所有口径（列怎么切、四个数怎么算、灰带在哪、序列什么序）都在 [StatsCalc] 里 ——
 * 那是**纯函数，66 条测试盯着**。这里只做三件事：
 *
 * 1. 记住「在看哪个口径、哪个区间」（就是左上角 `日周月年自` 和右上角 `‹ ›` 的产物）
 * 2. 把 `口径 → 列` 交给仓库去取数据，再交给纯函数去算
 * 3. 把算出来的东西摆成界面直接能读的字段
 *
 * ⚠️ **这里不许出现任何除法**。只要有一处百分比是在界面或 ViewModel 里现算的，
 * 「分母到底是 1440 还是此刻」这条口径就有了第二个定义处 —— 而这个错
 * 不报错、不崩，只会让你觉得「今天记录得特别差」。
 *
 * ## 一个刻意的设计：切口径 = 从今天看起
 *
 * 点「周」的时候，锚点无条件重置成今天，而不是保留上一个口径停在哪。
 * 不重置的话会出现「我刚从 3 月翻回来，切到周怎么还在 3 月」这种莫名其妙的位置。
 */
class StatsViewModel(
    private val repo: StatsRepository,
    private val clock: Clock,
) : ViewModel() {

    // ------------------------------------------------------------ 状态（口径）

    /** 左上角选中的口径。默认「周」—— 七天一屏装得下，也最容易看出哪天漏了。 */
    var mode by mutableStateOf(StatsCalc.Mode.WEEK)
        private set

    /** 日 / 周 / 月 / 年 口径的锚点（默认今天）。 */
    var anchor by mutableStateOf(clock.now().dayKey)
        private set

    /** 自定义区间的起止（只在 [StatsCalc.Mode.CUSTOM] 下有意义）。 */
    var from by mutableStateOf<String?>(null)
        private set

    var to by mutableStateOf<String?>(null)
        private set

    // ------------------------------------------------------------ 状态（算出来的）

    var loading by mutableStateOf(true)
        private set

    /** 聚合结果。`null` = 这个口径还没有列（只有「自」没选区间时会发生）。 */
    var agg by mutableStateOf<StatsCalc.Agg?>(null)
        private set

    /** ① 面积图的序列 —— **含**未记录（灰带排最顶）。 */
    var area by mutableStateOf<List<StatsCalc.Slice>>(emptyList())
        private set

    /** ② 汇总柱的序列 —— **不含**未记录（含了柱子全是满的，缺漏就被藏起来了）。 */
    var bar by mutableStateOf<List<StatsCalc.Slice>>(emptyList())
        private set

    /** 整个区间一条记录都没有 —— 界面给一句人话，别让人对着一堆空图猜。 */
    var empty by mutableStateOf(false)
        private set

    /** 右上角那串区间文字。 */
    var rangeLabel by mutableStateOf("")
        private set

    /** 这个口径该显示哪几张图（**不在渲染器里再判一次**）。 */
    var charts by mutableStateOf(StatsCalc.chartsFor(StatsCalc.Mode.WEEK))
        private set

    /**
     * 折叠起来的卡片。
     *
     * 它是**跨口径保留**的（跟原型一致）—— 你把「时间分布」收起来，
     * 换个口径它还是收着的，不用每次重新收一遍。
     */
    var folded by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 点某一列之后弹出的明细。`null` = 关着。 */
    var detail by mutableStateOf<StatsCalc.Detail?>(null)
        private set

    // ------------------------------------------------------------ 状态（自定义区间面板）

    var customOpen by mutableStateOf(false)
        private set

    /** 面板里那两个输入框的草稿（开着的时候才用）。 */
    var draftFrom by mutableStateOf("")
        private set

    var draftTo by mutableStateOf("")
        private set

    /** 给界面看的提示条。内容由这里拼好，界面一个字不改。 */
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages = _messages.asSharedFlow()

    /** 当前口径下横轴一列的粒度 —— ② 的纵轴满刻度跟着它走。 */
    val grain: StatsCalc.Grain get() = agg?.grain ?: StatsCalc.Grain.DAY

    /** 最近一次取回来的素材，点列看明细时要用它取颜色。 */
    private var snapshot: StatsRepository.Snapshot? = null

    /** 今天（点列明细里判「还没到」要用）。 */
    private val today: String get() = clock.now().dayKey

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    // ------------------------------------------------------------ 交互

    /**
     * 切口径。
     *
     * ⚠️ 名字叫 `pickMode` 而不是 `setMode` —— 后者会跟 `mode` 属性的 setter
     * 撞同一个 JVM 签名（`setMode(Mode)`），编译期直接报
     * 「Platform declaration clash」。这类错误看着莫名其妙，但原因很单纯。
     *
     * ⚠️ 「日 / 周 / 月 / 年」一律**把锚点重置到今天**；「自」则给一个默认区间
     * （最近 30 天），省得先弹一张空表单让人填。
     */
    fun pickMode(m: StatsCalc.Mode) {
        mode = m
        if (m == StatsCalc.Mode.CUSTOM) {
            if (from == null || to == null) {
                val (f, t) = StatsCalc.defaultCustom(today)
                from = f
                to = t
            }
        } else {
            anchor = today
        }
        refresh()
    }

    /** 点右上角 `‹` / `›`。自定义区间挪的是**一整段**，不是一天。 */
    fun nav(dir: Int) {
        if (mode == StatsCalc.Mode.CUSTOM) {
            val f = from ?: return
            val t = to ?: return
            val (nf, nt) = StatsCalc.navCustom(f, t, dir)
            from = nf
            to = nt
        } else {
            anchor = StatsCalc.navAnchor(mode, anchor, dir)
        }
        refresh()
    }

    fun fold(key: String) {
        folded = if (key in folded) folded - key else folded + key
    }

    /** 点图上某一列 → 那一天的明细。 */
    fun tapColumn(index: Int) {
        val col = agg?.cols?.getOrNull(index) ?: return
        val snap = snapshot ?: return
        detail = StatsCalc.detail(col, snap::color)
    }

    fun closeDetail() {
        detail = null
    }

    // ------------------------------------------------------------ 自定义区间

    fun openCustom() {
        draftFrom = from ?: StatsCalc.defaultCustom(today).first
        draftTo = to ?: today
        customOpen = true
    }

    fun closeCustom() {
        customOpen = false
    }

    /** 面板里手动改了草稿（日期选择器回写）。 */
    fun setDraft(f: String, t: String) {
        draftFrom = f
        draftTo = t
    }

    /** 「最近 N 天」快捷键 —— 只改草稿，还是要按「应用」才生效（跟原型一致）。 */
    fun quickDays(days: Int) {
        draftTo = today
        draftFrom = DayKey.plus(today, -(days - 1))
    }

    fun applyCustom() {
        val f = draftFrom
        val t = draftTo
        if (f.isBlank() || t.isBlank()) {
            say("起止日期都要选")
            return
        }
        val ok = runCatching { DayKey.parse(f); DayKey.parse(t) }.isSuccess
        if (!ok) {
            say("日期格式不对")
            return
        }
        from = minOf(f, t)
        to = maxOf(f, t)
        mode = StatsCalc.Mode.CUSTOM
        customOpen = false
        refresh()
        val span = StatsCalc.spanDays(from!!, to!!)
        val bucket = if (span <= StatsCalc.DAY_MAX_FOR_DAILY) "按天" else "按月"
        say("已切到自定义区间 · $span 天（$bucket 画）")
    }

    // ------------------------------------------------------------ 取数与计算

    private suspend fun reload() {
        loading = true
        detail = null

        val cols = StatsCalc.cols(mode, anchor, from, to)
        charts = StatsCalc.chartsFor(mode)
        rangeLabel = StatsCalc.rangeLabel(mode, anchor, from, to)

        if (cols.isEmpty()) {
            // 只有「自」还没选区间会走到这儿
            snapshot = null
            agg = null
            area = emptyList()
            bar = emptyList()
            empty = true
            loading = false
            return
        }

        // ★ 一次把整段区间查回来（不是每列查一次 —— 年口径就是 365 趟）
        val snap = repo.load(cols.first().days.first(), cols.last().days.last())
        snapshot = snap

        val a = snap.agg(cols, clock.now())

        agg = a
        // ①② 用的是**同一份聚合**、只是对「未记录」的处理相反。
        // 这正是那个口径的关键：② 不含灰带，柱子矮才是「没记全」。
        area = StatsCalc.series(a, snap.order, snap::color, includeGap = true)
        bar = StatsCalc.series(a, snap.order, snap::color, includeGap = false)
        empty = a.cols.none { it.rec > 0 }
        loading = false
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                StatsViewModel(
                    repo = app.container.stats,
                    clock = app.container.clock,
                )
            }
        }
    }
}
