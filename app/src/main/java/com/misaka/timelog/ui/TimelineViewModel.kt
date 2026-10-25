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
import com.misaka.timelog.data.entity.TagBucketEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.data.repo.UndoToken
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.CalendarGrid
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.Density
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.blockedReason
import com.misaka.timelog.domain.valueOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.misaka.timelog.widget.WidgetRefresh

/**
 * 底部那一堆面板各自在干什么。
 *
 * ## ⚠️ 为什么用一个「可空的状态」而不是几个布尔开关
 *
 * 原型用 `changing` / `fillingGap` / `tplPicking` 三个布尔量记「这个面板是为谁弹的」，
 * 于是**每个出口都得记得复位它们**（原型的 `closePicker()` 里就专门写了这一句，
 * 还专门标了「否则下次正常打开面板选事项时，会被误当成上一次的变更」）。
 *
 * 真机版用一个可空的请求对象：**关面板 = 置 null**，
 * 模式、目标记录一起消失，不可能出现「上次的模式还挂着」这种状态。
 * 而且「什么时候该复位」不再是需要记住的事 —— 它没有第二个出口。
 */
sealed interface Sheet {

    /** 关着。 */
    data object None : Sheet

    /** 选事项面板，以及它**为什么**弹出来。 */
    data class Picker(val mode: PickerMode, val target: RecordRow?) : Sheet

    /** 点了一张卡之后的操作菜单。 */
    data class Menu(val row: RecordRow) : Sheet

    /** 改时间。 */
    data class TimeEdit(val row: RecordRow) : Sheet

    /** 编辑备注和标签。 */
    data class ContentEdit(val row: RecordRow) : Sheet

    /**
     * 「退出模板记录？」的确认面板。
     *
     * 唯一由它弹的场景 = 点时间线顶部那根绿横条。
     * （另一个退出入口是「点结束 → 亲手选别的事项」，那个**不弹窗**，
     * 因为动作本身就是答案，再问一句就是多一次打断。）
     */
    data object TplExit : Sheet

    /**
     * 「用某天覆盖这一天？」的确认面板 —— 粘贴那一步。
     *
     * ⚠️ 它**不是** Sheet 里那种「针对某一条记录」的面板，但它确实要弹窗、
     * 确实要在翻天时被关掉（`go()` 会 `closeSheets()`），所以放这里最省事：
     * 用户翻到别的日子之后再确认「覆盖」是很危险的事，直接被关掉正合适。
     *
     * @param existing 目标日现在有几段 —— 确认文案里要说清「会清掉多少」
     */
    data class PasteConfirm(
        val clip: TimelineRepository.DayClip,
        val target: String,
        val existing: Int,
    ) : Sheet
}

/** 选事项面板的三种用途 —— 选了之后落到完全不同的地方。 */
enum class PickerMode {
    /** 结束现在这件事、从此刻开始记新的（只有今天能用）。 */
    StartNew,

    /** 把某条记录换成另一件事，时间不变。 */
    ChangeItem,

    /** 把某段「未记录」补记成一件事，时间不变。 */
    FillGap,
}

/** 撤销条的状态。 */
data class UndoState(val token: UndoToken)

/**
 * 月历弹窗的状态。
 *
 * ⚠️ 这里存的是「哪年哪月 + 哪些天有记录」这种**原料**，不是算好的格子。
 * 格子由 `CalendarGrid.of()` 现算 —— 否则「今天是几号」「翻到哪个月」一变，
 * 存着的格子就过期了，而它过期之后看起来仍然完全正常（每格都是合法日期）。
 *
 * @param daysWithData 这个月里有真实记录的天（`'2026-10-05'` 形式），画小圆点用
 * @param loading 这个月的圆点还在查。不影响点日期，只是先不画点。
 */
data class CalendarState(
    val year: Int,
    val month: Int,
    val daysWithData: Set<String>,
    /** 有「未记录」空白块的天 —— 月历上的小黄点。 */
    val daysWithGap: Set<String> = emptySet(),
    val loading: Boolean = false,
)

/**
 * 改时间面板的本地状态（还没落库）。
 *
 * 为什么放 ViewModel 而不是面板内部 `remember`：
 * 面板是「打开 → 微调 → 保存」，中途如果配置变了（转屏）状态得还在。
 * 而且保存时要拿它跟原值比，判断「用户到底改了没有」。
 */
data class TimeEditState(
    val recordId: String,
    val name: String,
    val start: Int,
    val end: Int,
    /** 进行中的记录**只允许改开始时间** —— 终点归「结束」按钮管。 */
    val isLive: Boolean,
    /** 这一天最多能到哪 —— 不许改到未来去。 */
    val cut: Int,
)

/**
 * 时间线的状态。
 *
 * ## 数据流
 *
 * ```
 *   TimelineRepository（全库唯一能写 records 的口）
 *        ↓  ensureDense + dayRecords
 *   TimelineAssembler（纯函数：实体 + 字典 → 照着画就行的东西）
 *        ↓
 *   TimelineScreen（一行业务判断都没有，纯画）
 * ```
 *
 * 这个类**故意做得很薄**：取数据、交给装配器、存进 state。凡是能判断的
 * （这条是不是空白、时长怎么算、事项删了显示什么）都在装配器里 —— 那边是纯函数，
 * 测试不用起 Android 环境，跑起来是秒级的。
 *
 * M2-B 加进来的「写操作」也守着同一条纪律：**这里一行业务规则都不写**，
 * 只负责「把面板的输入交给仓库」「把仓库的话原样抛给界面」。
 * 比如「删除后那段时间会变成未记录」这句话，是数据层给的，这里一个字都没改。
 */
class TimelineViewModel(
    private val timeline: TimelineRepository,
    private val catalog: CatalogRepository,
    private val clock: Clock,
    /**
     * 「今天的数据可能变了」—— 由外面接上桌面小部件的刷新。
     *
     * ⚠️ 传一个 lambda 而不是把 Context 塞进来：ViewModel 不该认识
     * Android 的桌面小部件。它只负责喊一声「变了」，谁听、怎么刷是外面的事。
     * 默认空实现 —— 单元测试里不用关心它。
     */
    private val onDataChanged: () -> Unit = {},
) : ViewModel() {

    /** 正在看的那一天（ISO 键）。 */
    var viewing by mutableStateOf(clock.now().dayKey)
        private set

    /** 真正的今天。翻页时它不动 —— 它是判断「是今天吗」「能往后翻吗」的基准。 */
    var todayKey by mutableStateOf(clock.now().dayKey)
        private set

    /** 卡片列表，倒序。 */
    var rows by mutableStateOf<List<RecordRow>>(emptyList())
        private set

    /** 这一天的「已记录」合计（分钟）。 */
    var recordedMin by mutableStateOf(0)
        private set

    /** 这一天的「未记录」合计（分钟）。0 表示记录完整。 */
    var gapMin by mutableStateOf(0)
        private set

    /** 正在进行的那条。`null` = 空闲中。 */
    var liveRow by mutableStateOf<RecordRow?>(null)
        private set

    /** 周条：正在看的那一天所在那一周的 7 格，周一起。 */
    var week by mutableStateOf<List<WeekCell>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    // -------------------------------------------------- 面板与字典（M2-B）

    /** 当前打开的面板。`null` 等价于 [Sheet.None]。 */
    var sheet by mutableStateOf<Sheet>(Sheet.None)
        private set

    /** 撤销条。非 null 时界面底部挂一条「已删除…· 撤销」。 */
    var undo by mutableStateOf<UndoState?>(null)
        private set

    /** 改时间面板的草稿。 */
    var timeEdit by mutableStateOf<TimeEditState?>(null)
        private set

    /** 选事项面板要画的东西。 */
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
        private set

    var items by mutableStateOf<List<ItemEntity>>(emptyList())
        private set

    var buckets by mutableStateOf<List<TagBucketEntity>>(emptyList())
        private set

    var allTags by mutableStateOf<List<TagEntity>>(emptyList())
        private set

    // -------------------------------------------------- 模板运行态（M2-C）

    /**
     * 模板状态。
     *
     * ⚠️ 界面**不许自己判断**「今天该不该跑模板」—— 那个判据只有一个定义处
     * （`DayKey.coversWeekday`），这里拿到的已经是算好的结果。
     * 原型第 17/18 轮吃过这个亏：同一句判断散在两个文件里，读起来像互相矛盾。
     */
    var tpl by mutableStateOf(TemplateView())
        private set

    /**
     * 给界面看的提示条文字。
     *
     * 内容**全部来自数据层** —— 比如「「学习」让位，压短到 11:40–12:00」。
     * 界面一个字都不拼，改口径时只改一处。
     */
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages = _messages.asSharedFlow()

    /** 现在看的是不是今天（决定底部状态条显示什么、要不要每分钟刷新）。 */
    val isToday: Boolean get() = viewing == todayKey

    /** 还能不能往后翻。⚠️ **未来不可去** —— 那里既没有记录也无法补记。 */
    val canGoNext: Boolean get() = viewing < todayKey

    init {
        refresh()
    }

    // ------------------------------------------------------------ 翻页

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun goPrev() = go(DayKey.plus(viewing, -1))

    fun goNext() {
        if (canGoNext) go(DayKey.plus(viewing, 1))
    }

    fun goToday() = go(clock.now().dayKey)

    /**
     * 翻到某一天。
     *
     * ⚠️ 这里**兜底夹住未来** —— 就算将来某个入口漏了校验（周条、日历、快捷方式、通知），
     * 也不会真的翻到未来去。判断集中在这一处，比在每个调用点各写一遍可靠。
     *
     * 顺手关掉所有面板：面板是**针对某一天的某一条记录**打开的，
     * 翻了天之后它指向的东西已经不在眼前了。
     */
    fun go(key: String) {
        val today = clock.now().dayKey
        viewing = if (key > today) today else key
        closeSheets()
        refresh()
    }

    // ------------------------------------------------------------ 月历跳转（M5-1）

    /**
     * 月历弹窗的状态。`null` = 关着。
     *
     * 跟 [Sheet] 分开放，不是偷懒：Sheet 那一套是「针对某一天的某一条记录」的操作面板，
     * 翻天后必须全部关掉（见 [go]）。月历恰恰相反 —— 它是**用来翻天的那个东西**，
     * 点一天就该自己消失，放同一个枚举里会绕成「关掉自己再打开自己」。
     */
    var calendar by mutableStateOf<CalendarState?>(null)
        private set

    /** 点顶部日期标题 → 弹月历，**先摆到正在看的那个月**。 */
    fun openCalendar() {
        val (y, m) = CalendarGrid.ymOf(viewing)
        calendar = CalendarState(year = y, month = m, daysWithData = emptySet(), loading = true)
        loadMonth(y, m)
    }

    fun closeCalendar() {
        calendar = null
    }

    /** 翻月 / 翻年。`delta` 是「挪几个月」，`±12` 就是翻年。 */
    fun shiftMonth(delta: Int) {
        val c = calendar ?: return
        val (y, m) = CalendarGrid.shift(c.year, c.month, delta)
        // 先把小圆点清空再查 —— 否则翻到新月份时会先看到上个月的圆点闪一下
        calendar = c.copy(
            year = y,
            month = m,
            daysWithData = emptySet(),
            daysWithGap = emptySet(),
            loading = true,
        )
        loadMonth(y, m)
    }

    /**
     * 查这个月哪些天有记录（月历上的小圆点）。
     *
     * ⚠️ **回来晚了的应答不许写回**：连着点翻月会并发三次查询，
     * 先发的可能后回来 —— 不管的话就会出现「停在 3 月，圆点却是 1 月的」。
     * 这种错不崩不报错，只是点了不存在的日子，最难查。
     */
    private fun loadMonth(year: Int, month: Int) {
        viewModelScope.launch {
            val from = "%04d-%02d-01".format(year, month)
            val (ny, nm) = CalendarGrid.shift(year, month, 1)
            val to = "%04d-%02d-01".format(ny, nm)
            val days = runCatching { timeline.daysWithContent(from, to) }.getOrDefault(emptyList())
            val gaps = runCatching { timeline.daysWithGap(from, to) }.getOrDefault(emptyList())

            val c = calendar ?: return@launch
            if (c.year != year || c.month != month) return@launch
            calendar = c.copy(
                daysWithData = days.toSet(),
                daysWithGap = gaps.toSet(),
                loading = false,
            )
        }
    }

    /** 月历上点了某一天 —— 关掉月历、翻过去。 */
    fun jumpTo(key: String) {
        calendar = null
        go(key)
    }

    // ------------------------------------------------------------ 整天复制 / 粘贴（M5-2）

    /**
     * ★ 剪贴板 —— **只存在内存里**。
     *
     * 不给它落盘，是因为「复制某一天」是个临时动作：关掉 App 之后再粘，
     * 粘过来的东西多半已经跟你手上正在做的事对不上了。
     * 落盘反而会造出一个「上次复制的还在」的幽灵状态。
     */
    var clip by mutableStateOf<TimelineRepository.DayClip?>(null)
        private set

    /** 有没有东西可粘 —— 菜单里「粘贴」那一项靠它灰着。 */
    val canPaste: Boolean get() = clip != null

    /** 复制正在看的这一天。 */
    fun copyDay() {
        viewModelScope.launch {
            val c = timeline.clipDay(viewing)
            clip = c
            if (c.count == 0) {
                say("${DayKey.display(viewing)} 还是空的，没什么可复制的。")
            } else {
                say("已复制 ${DayKey.display(c.from)} · ${c.count} 段。翻到另一天再点「粘贴到这一天」")
            }
        }
    }

    /**
     * 点「粘贴」→ 先数清目标日现有几段，再弹确认。
     *
     * 为什么非要弹一次：粘贴是**覆盖整天**，目标日原有记录会全部消失。
     * 这跟「删一条记录」不是一个量级 —— 删错了 4 秒撤销条还够用，
     * 覆盖错了是一整天几十段一起没。所以确认 + 撤销条两个都要。
     */
    fun askPaste() {
        val c = clip
        if (c == null) {
            say("还没有复制过哪一天。先翻到那一天点「复制这一天」。")
            return
        }
        if (c.from == viewing) {
            say("正在看的这天就是复制的那天，不用贴。")
            return
        }
        viewModelScope.launch {
            val existing = timeline.dayRecords(viewing).size
            sheet = Sheet.PasteConfirm(clip = c, target = viewing, existing = existing)
        }
    }

    /** 确认覆盖。 */
    fun doPaste() {
        val s = sheet as? Sheet.PasteConfirm ?: return
        viewModelScope.launch {
            val out = timeline.pasteDay(s.target, s.clip)

            val token = out.valueOrNull()
            if (token == null) {
                closeSheets()
                say(out.blockedReason().orEmpty())
                refresh()
                return@launch
            }

            closeSheets()
            // 覆盖整天不可逆 —— 撤销条是最后一道保险
            undo = UndoState(token)
            say(
                "已用 ${DayKey.display(s.clip.from)} 覆盖 ${DayKey.display(s.target)}" +
                    " · ${s.clip.count} 段"
            )
            refresh()
        }
    }

    // ------------------------------------------------------------ 取数并装配

    private suspend fun reload() {
        // 时钟只取一次 —— 分几次取可能在午夜前后拿到自相矛盾的一组值
        val now = clock.now()
        todayKey = now.dayKey

        // 兜底：万一 viewing 落在未来（比如跨过午夜之后）
        val day = if (viewing > now.dayKey) now.dayKey else viewing
        if (day != viewing) viewing = day

        // ★★ 刷新链（模板跨天 → 跨天接续 → 补密 → 按模板铺）的顺序是死的。
        //
        //   M3-C 之前这四行就写在这里。加了桌面小部件和悬浮选事项窗之后，
        //   三个入口要跑同一套 —— 所以抠成了 `TimelineRepository.maintain()`，
        //   顺序的理由、以及「为什么只能有一个定义处」都在那边写着。
        timeline.maintain(day)

        val cut = Density.cutFor(day, now)
        val records = timeline.dayRecords(day)

        // 字典一次全取配成 map —— 别在循环里一条条查（N+1）
        val cats = catalog.categories()
        val allItems = catalog.items()
        categories = cats
        items = allItems
        buckets = catalog.buckets()
        allTags = catalog.tags()

        val view = TimelineAssembler.day(
            records = records,
            cut = cut,
            itemsById = allItems.associateBy { it.id },
            catsById = cats.associateBy { it.id },
            tagsByRec = timeline.tagsOn(records.map { it.id }),
        )

        rows = view.rows
        recordedMin = view.recordedMin
        gapMin = view.gapMin
        liveRow = view.liveRow

        // 周条：一次把整周捞回来，在内存里挑出「有空白」的那几天
        val monday = DayKey.mondayOf(day)
        val gapDays = timeline
            .recordsBetween(monday, DayKey.plus(monday, 7))
            .filter { it.source == RecordSource.GAP }
            .map { it.day }
            .toSet()

        week = TimelineAssembler.week(
            dayKey = day,
            todayKey = now.dayKey,
            viewingHasGap = view.gapMin > 0,
            gapDays = gapDays,
        )

        // 模板状态：查库（跑没跑、叫什么、今天被指派没）
        tpl = timeline.templateState().let {
            TemplateView(on = it.on, name = it.name, coveredToday = it.coveredToday, exists = it.exists)
        }

        loading = false

        // ★★ 数据可能变了 → 叫小部件重画一次。
        //
        // 为什么挂在这儿而不是「每个写操作各调一次」：
        // 写操作有十几处（记一笔 / 改时间 / 删 / 撤销 / 合并空白 / 模板…），
        // 每处都加一行的话，**漏掉任何一处，那种操作就永远不同步**，
        // 而且不报错、不崩，只是桌面上那个数字停在旧值 —— 最难查的一类 bug。
        // 而所有写操作的共同归宿就是 `reload()`（改完必须重画界面），
        // 所以这里是天然的唯一入口。
        //
        // ⚠️ 只在「看的是今天」时刷：小部件只显示今天，
        //    翻到去年某天改记录不该去动它（省一次组合 + 一次 RemoteViews 推送）。
        if (day == now.dayKey) onDataChanged()
    }

    // ============================================================ 模板（M2-C）

    /**
     * 切到模板模式 —— 「结束」面板左下角那个按钮。
     *
     * ## 为什么这个入口不放在设置里
     *
     * 因为模板是**「今天要不要交给它」的决定**，不是一种全局设置。
     * 天天要用，而且用的时机就是你手正放在「结束」按钮上的那一刻。
     * 藏进设置页 = 每天多走三步，最后的结果一定是**这功能没人用**。
     *
     * ## 配置 ≠ 启动
     *
     * 把模板配好**什么都不发生**。必须显式切一下 ——
     * App 绝不猜你今天想不想用模板（想睡懒觉那天凭什么按规律来）。
     */
    fun startTemplate() {
        viewModelScope.launch {
            val out = timeline.startTemplate()
            if (out.isOk) {
                closeSheets()
                // 说清「怎么退出」—— 这是唯一一件用户猜不到的事
                say("已切到模板模式 · 之后的空白按「${tpl.name}」自动记；点「结束」选别的事项即退出")
            } else {
                say(out.blockedReason().orEmpty())
            }
            refresh()
        }
    }

    /** 点时间线顶部那根横条 → 先问一句（这个退出入口不是「动作本身就是答案」的那种）。 */
    fun askStopTemplate() {
        if (tpl.on) sheet = Sheet.TplExit
    }

    /** 确认退出。**已经按模板记下的那些留着** —— 之后新出现的空白不再自动填。 */
    fun stopTemplate() {
        viewModelScope.launch {
            timeline.stopTemplate()
            closeSheets()
            say("已退出模板记录 · 之后的时间你自己记")
            refresh()
        }
    }

    // ============================================================ 面板开关

    fun closeSheets() {
        sheet = Sheet.None
        timeEdit = null
    }

    /** 底部「结束/开始」按钮 —— 弹面板选一件事开始记。 */
    fun openPickerForNow() {
        if (!isToday) {
            // 过去的一天没法「从现在开始记」—— 那会拿今天的此刻当起点插进去，
            // 于是昨天凭空长出一条怪物记录（原型第 19 轮身上真实发生过）
            say("这是过去的一天。想补记，点时间线上那段「未记录」。")
            return
        }
        sheet = Sheet.Picker(PickerMode.StartNew, null)
    }

    /** 点了一张卡片 —— 弹它自己的操作菜单。 */
    fun openMenu(row: RecordRow) {
        sheet = Sheet.Menu(row)
    }

    /** 选了「变更事项」—— 面板复用同一张网格，只是落地的地方不一样。 */
    fun startChangeItem(row: RecordRow) {
        sheet = Sheet.Picker(PickerMode.ChangeItem, row)
    }

    /** 选了「补记一件事」—— 空白唯一的出口。 */
    fun startFillGap(row: RecordRow) {
        sheet = Sheet.Picker(PickerMode.FillGap, row)
    }

    /**
     * 网格里点了一个事项 —— 按面板为什么弹出来分派。
     *
     * ⚠️ 关面板**必须**走 [closeSheets]（一并把模式清掉）。这正是原型里
     * 「关了面板还带着上次的模式」那个坑的解法。
     */
    fun pick(itemId: String) {
        val req = sheet as? Sheet.Picker ?: return
        val target = req.target
        closeSheets()

        viewModelScope.launch {
            when (req.mode) {
                PickerMode.StartNew -> {
                    val name = items.firstOrNull { it.id == itemId }?.name ?: "它"
                    val wasLive = liveRow?.name
                    when (val out = timeline.pick(itemId, viewing)) {
                        is Outcome.Ok -> say(
                            if (out.value) {
                                "已结束「${wasLive ?: "空闲"}」· 现在开始记「$name」"
                            } else {
                                "已经在记「$name」了"
                            }
                        )

                        is Outcome.Blocked -> say(out.message)
                    }
                }

                PickerMode.ChangeItem -> {
                    if (target == null) return@launch
                    timeline.changeItem(target.id, itemId).let { out ->
                        out.valueOrNull()?.let { say("已改为「${itemName(itemId)}」· 时间不变") }
                        out.blockedReason()?.let { say(it) }
                    }
                }

                PickerMode.FillGap -> {
                    if (target == null) return@launch
                    timeline.fillGap(target.id, itemId).let { out ->
                        out.valueOrNull()?.let {
                            say(
                                "已把 ${DayKey.minuteLabel(target.startMin)} → ${target.endLabel} " +
                                    "记成「${itemName(itemId)}」"
                            )
                        }
                        out.blockedReason()?.let { say(it) }
                    }
                }
            }
            reload()
        }
    }

    private fun itemName(itemId: String): String =
        items.firstOrNull { it.id == itemId }?.name ?: "那件事"

    // ============================================================ 改时间

    fun openTimeEdit(row: RecordRow) {
        if (row.isGap) {
            say("空白的时间不能这样改 —— 补记一件事，或者把它并进邻居。")
            return
        }
        val now = clock.now()
        timeEdit = TimeEditState(
            recordId = row.id,
            name = row.name,
            start = row.startMin,
            // 进行中的那条没有终点 —— 面板里显示到此刻，但保存时只写开始时间
            end = row.endMin ?: Density.cutFor(viewing, now),
            isLive = row.isLive,
            cut = Density.cutFor(viewing, now),
        )
        sheet = Sheet.TimeEdit(row)
    }

    /**
     * 微调一个时间端点。
     *
     * `which = 'a'` 是起点、`'b'` 是终点。夹在 `[0, cut]` 里，
     * 而且**始终保证终点在起点之后**（否则面板上会出现「14:30 → 14:20」这种没法保存的值）。
     */
    fun nudgeTime(which: Char, delta: Int) {
        val t = timeEdit ?: return
        timeEdit = if (which == 'a') {
            val s = (t.start + delta).coerceIn(0, t.cut - 1)
            t.copy(start = s, end = maxOf(t.end, s + 1).coerceAtMost(t.cut))
        } else {
            val e = (t.end + delta).coerceIn(t.start + 1, t.cut)
            t.copy(end = e)
        }
    }

    fun saveTime() {
        val t = timeEdit ?: return
        closeSheets()
        viewModelScope.launch {
            when (val out = timeline.setTime(t.recordId, t.start, if (t.isLive) null else t.end)) {
                is Outcome.Ok -> {
                    val msgs = out.value
                    say(
                        "时间已改" + if (msgs.isEmpty()) " · 没碰到别的记录"
                        else " · " + msgs.joinToString("；")
                    )
                }

                is Outcome.Blocked -> say(out.message)
            }
            reload()
        }
    }

    // ============================================================ 编辑内容

    fun openContentEdit(row: RecordRow) {
        if (row.isGap) {
            say("空白没有备注和标签 —— 先补记一件事。")
            return
        }
        sheet = Sheet.ContentEdit(row)
    }

    fun saveContent(row: RecordRow, note: String, tagIds: List<String>) {
        closeSheets()
        viewModelScope.launch {
            timeline.editContent(row.id, note, tagIds).let { out ->
                out.valueOrNull()?.let {
                    say(if (tagIds.isEmpty()) "已保存" else "已保存 · 挂着 ${tagIds.size} 个标签")
                }
                out.blockedReason()?.let { say(it) }
            }
            reload()
        }
    }

    // ============================================================ 合并 / 拆分

    /** [dir] = -1 时间线上面那条，+1 下面那条。 */
    fun mergeWith(row: RecordRow, dir: Int) {
        closeSheets()
        viewModelScope.launch {
            report(timeline.mergeWith(row.id, dir))
            reload()
        }
    }

    fun split(row: RecordRow) {
        closeSheets()
        viewModelScope.launch {
            report(timeline.split(row.id))
            reload()
        }
    }

    fun mergeGapInto(row: RecordRow, dir: Int) {
        closeSheets()
        viewModelScope.launch {
            report(timeline.mergeGapInto(row.id, dir))
            reload()
        }
    }

    // ============================================================ 删除 + 撤销

    /**
     * ★ 删除 = 原地擦成空白 + 4 秒后悔药。
     *
     * ⚠️ **不弹「你确定吗」**。这条规矩跟删标签是同一套：删除的影响范围只在
     * 这一条记录上，而且立刻能看见结果、还能撤销 —— 拦一道反而添堵。
     * （真正该弹窗的是「删事项」，那会一次性改掉它名下几百条历史。）
     */
    fun delete(row: RecordRow) {
        closeSheets()
        viewModelScope.launch {
            when (val out = timeline.deleteRecord(row.id)) {
                is Outcome.Ok -> {
                    undo = UndoState(out.value)
                    undoTimer?.cancel()
                    undoTimer = viewModelScope.launch {
                        delay(UNDO_MS)
                        undo = null
                    }
                }

                is Outcome.Blocked -> say(out.message)
            }
            reload()
        }
    }

    fun doUndo() {
        val pending = undo ?: return
        undo = null
        undoTimer?.cancel()
        viewModelScope.launch {
            timeline.undo(pending.token)
            say("已撤销")
            reload()
        }
    }

    private var undoTimer: Job? = null

    // ============================================================ 小工具

    private suspend fun report(out: Outcome<String>) {
        out.valueOrNull()?.let { say(it) }
        out.blockedReason()?.let { say(it) }
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        /** 撤销条的寿命 —— 4 秒，跟原型一致。 */
        const val UNDO_MS = 4_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                TimelineViewModel(
                    timeline = app.container.timeline,
                    catalog = app.container.catalog,
                    clock = app.container.clock,
                    // 桌面小部件跟时间线**同源**：数据一变就叫它重画（见 reload() 末尾）
                    onDataChanged = { WidgetRefresh.now(app) },
                )
            }
        }
    }
}
