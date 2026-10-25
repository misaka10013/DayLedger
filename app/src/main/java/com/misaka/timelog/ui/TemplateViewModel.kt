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
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TemplateRepository
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.TemplateEdit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 模板配置页的状态与操作。
 *
 * ## 跟 [TimelineViewModel] 的分工
 *
 * 那个管「今天长什么样」，这个管「模板长什么样」。两边共享同一张
 * `template_segments` 表，但**一个只读、一个只写**：
 *
 * - [TimelineViewModel] 拿段去铺写今天的记录
 * - 这里拿段来给你改
 *
 * 所以在这里改完段，**不需要通知时间线任何事** —— 它下次刷新时自然读到新段。
 *
 * ## 界面层不做判断，这条规矩照旧
 *
 * 「能不能挪」「能不能拆」「能不能删」全在 [TemplateEdit] 里判（纯函数），
 * 界面只负责把结果画出来、把理由念出来。
 */
class TemplateViewModel(
    private val template: TemplateRepository,
    private val catalog: CatalogRepository,
    private val clock: Clock,
) : ViewModel() {

    /**
     * 色条上的一段 —— **已经查好了名字和颜色**，界面直接画。
     *
     * ⚠️ 名字和颜色是**查出来的**，不是段自己存的。段里只有一个 `itemId`。
     * 这让「改事项名」和「改分类颜色」在同一时刻对全部段生效，
     * 不会出现「色条上一段叫旧名字、记录里叫新名字」。
     */
    data class SegUi(
        val index: Int,
        val startMin: Int,
        val endMin: Int,
        val itemId: String?,
        /** 悬空段显示 [ORPHAN_TEXT]，不是空串 —— 空着你会以为是「未记录」。 */
        val itemName: String,
        /** 事项所属分类的颜色。`null` = 悬空，画灰底。 */
        val colorHex: String?,
        val orphan: Boolean,
    ) {
        val durationMin: Int get() = endMin - startMin
        val startLabel: String get() = DayKey.minuteLabel(startMin)
        val endLabel: String get() = DayKey.minuteLabel(endMin)
        val durationLabel: String get() = DayKey.duration(durationMin)

        /** 回到纯函数要的形状。 */
        fun toSeg(): TemplateEdit.Seg = TemplateEdit.Seg(startMin, endMin, itemId)
    }

    var loading by mutableStateOf(true)
        private set

    /** 库里还没有模板（理论上种子已种下，这里只是兜底）。 */
    var exists by mutableStateOf(true)
        private set

    var name by mutableStateOf("")
        private set

    var weekdaysMask by mutableStateOf(0)
        private set

    var segs by mutableStateOf<List<SegUi>>(emptyList())
        private set

    /** 此刻正在按模板记录吗（**只读展示** —— 启停在时间线那边）。 */
    var runOn by mutableStateOf(false)
        private set

    /** 今天是不是被指派到的日子。直接决定「今天铺不铺」。 */
    var coveredToday by mutableStateOf(false)
        private set

    /** 密铺自检的结果，空串 = 没问题。⚠️ 正常情况下应该**永远是空串**。 */
    var issue by mutableStateOf("")
        private set

    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
        private set

    var items by mutableStateOf<List<ItemEntity>>(emptyList())
        private set

    /** 事项被删掉、段变得悬空的个数。顶部主动报出来。 */
    val orphanCount: Int get() = segs.count { it.orphan }

    val weekdayLabel: String get() = TemplateEdit.weekdaysText(weekdaysMask)

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message: SharedFlow<String> = _message

    /** 模板的 id。写的时候要带上，用来挡「页面开着的时候模板被换掉了」。 */
    private var templateId by mutableStateOf("")

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    // ------------------------------------------------------------ 取数

    private suspend fun reload() {
        val cfg = template.load()
        val cats = catalog.categories()
        val allItems = catalog.items()

        exists = cfg != null
        loading = false
        if (cfg == null) {
            segs = emptyList()
            return
        }

        // ⚠️ 顺序有讲究：先把字典和模板 id 落下来，再算段
        //    （`mapSegs` 依赖 items / categories）
        categories = cats
        items = allItems
        templateId = cfg.templateId
        name = cfg.name
        weekdaysMask = cfg.weekdaysMask
        runOn = cfg.runOn
        coveredToday = DayKey.coversWeekday(cfg.weekdaysMask, clock.now().dayKey)
        segs = mapSegs(cfg.segs)
        issue = TemplateEdit.check(cfg.segs)
    }

    /**
     * 段 → 界面用的形状（顺手把事项名和分类色查出来）。
     *
     * ⚠️ 查的是**当前已加载的字典**，不再查库 —— 乐观更新时字典本来就没变，
     * 再查一次只是白等一次 I/O。
     */
    private fun mapSegs(raw: List<TemplateEdit.Seg>): List<SegUi> {
        val itemById = items.associateBy { it.id }
        val catById = categories.associateBy { it.id }
        return raw.mapIndexed { i, s ->
            val item = s.itemId?.let { itemById[it] }
            val cat = item?.let { catById[it.categoryId] }
            SegUi(
                index = i,
                startMin = s.startMin,
                endMin = s.endMin,
                itemId = s.itemId,
                itemName = item?.name ?: ORPHAN_TEXT,
                colorHex = cat?.colorHex,
                orphan = item == null,
            )
        }
    }

    // ------------------------------------------------------------ 操作

    /**
     * 所有写操作都走这一个口子 —— 「刷新」不可能被某处忘掉。
     * （忘了刷新 = 用户点了没反应，最烦人的那种 bug。这条抄自 [CatalogViewModel]。）
     */
    private fun write(success: String? = null, block: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            when (val r = block()) {
                is Outcome.Ok -> {
                    reload()
                    if (success != null) _message.emit(success)
                }
                // 数据层拦下的理由**原样念出来** —— 界面不重新表述一遍规则。
                // 顺手 reload 一次：界面刚才被乐观更新改过了，而库其实没改，得拉回来对齐。
                is Outcome.Blocked -> {
                    _message.emit(r.message)
                    reload()
                }
            }
        }
    }

    /**
     * ★ 乐观更新：**先把新段画到界面上，再落库**。
     *
     * ## 为什么非这样不可
     *
     * `moveEdge` 是**基于当前显示的段**算的。要是等落库 + 重新读库回来才更新界面，
     * 用户连点两下「＋15」时，第二下拿到的还是**没更新过的旧值**，
     * 算出来仍然是「＋15 一次」—— 表现出来就是**点快了只走一格**。
     *
     * 而这个页面最常见的手势恰恰就是连点（把边界从 09:00 挪到 09:45，
     * 没人会点一下等一秒）。
     *
     * ## 落库顺序为什么不会乱
     *
     * 每次保存都是**整批替换**，且 Room 的事务是串行的（按发起顺序执行）。
     * 所以「后发起的写覆盖先发起的写」正是我们要的语义。
     */
    private fun applySegs(
        raw: List<TemplateEdit.Seg>,
        success: String? = null,
        block: suspend () -> Outcome<Unit>,
    ) {
        segs = mapSegs(raw)
        issue = TemplateEdit.check(raw)
        write(success, block)
    }

    /** 点一下某个星期：亮 → 灭、灭 → 亮，**立刻落库**（没有「保存」按钮）。 */
    fun toggleWeekday(index: Int) {
        val next = TemplateEdit.toggleWeekday(weekdaysMask, index)
        weekdaysMask = next
        coveredToday = DayKey.coversWeekday(next, clock.now().dayKey)
        write(success = "已指派：${TemplateEdit.weekdaysText(next)}") {
            template.setWeekdays(templateId, next)
        }
    }

    /**
     * 挪一条边界。
     *
     * ⚠️ 「挪不动」**不是错误**，是正常的到顶 —— 所以走普通提示，不弹任何东西。
     * 但必须**说出来**：点了没反应时，人分不清是「到极限了」还是「按钮坏了」。
     */
    fun moveEdge(index: Int, edge: TemplateEdit.Edge, delta: Int) {
        val next = TemplateEdit.moveEdge(segs.map { it.toSeg() }, index, edge, delta)
        if (next == null) {
            say(
                when (edge) {
                    TemplateEdit.Edge.Start ->
                        "挪不动了 —— 已经顶到上一段了（第一段的起点永远是 00:00）"
                    TemplateEdit.Edge.End ->
                        "挪不动了 —— 已经顶到下一段了（最后一段的终点永远是 24:00）"
                }
            )
            return
        }
        applySegs(next) { template.saveSegments(templateId, next) }
    }

    fun splitSeg(index: Int) {
        val seg = segs.getOrNull(index) ?: return
        if (seg.durationMin < 2) {
            say("这一段太短了，拆不开")
            return
        }
        val next = TemplateEdit.split(segs.map { it.toSeg() }, index) ?: return
        applySegs(next, success = "已拆成两段 · 点新拆出来的那半段就能改它的事项") {
            template.saveSegments(templateId, next)
        }
    }

    fun deleteSeg(index: Int) {
        val seg = segs.getOrNull(index) ?: return
        if (segs.size <= 1) {
            say("只剩一段了，删了就凑不成一天")
            return
        }
        val text = "${seg.startLabel} → ${seg.endLabel}「${seg.itemName}」"
        val next = TemplateEdit.delete(segs.map { it.toSeg() }, index) ?: return
        applySegs(next, success = "已删掉 $text · 时长并给了相邻那段（模板不留洞）") {
            template.saveSegments(templateId, next)
        }
    }

    fun setItem(index: Int, itemId: String) {
        val seg = segs.getOrNull(index) ?: return
        val next = TemplateEdit.setItem(segs.map { it.toSeg() }, index, itemId)
        // 提示里的名字自己查一下 —— 乐观更新之后 reload 才会带上新名字，
        // 但这句话现在就要说对
        val newName = items.firstOrNull { it.id == itemId }?.name ?: "它"
        val range = "${seg.startLabel} → ${seg.endLabel}"
        applySegs(next, success = "「$range」已改成「$newName」") {
            template.saveSegments(templateId, next)
        }
    }

    private fun say(text: String) {
        _message.tryEmit(text)
    }

    companion object {
        /** 悬空段在色条上显示的字。⚠️ 它**不是**「未记录」。 */
        const val ORPHAN_TEXT = "（去重选事项）"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                TemplateViewModel(
                    template = app.container.template,
                    catalog = app.container.catalog,
                    clock = app.container.clock,
                )
            }
        }
    }
}
