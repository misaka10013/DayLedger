package com.misaka.timelog.widget

import android.content.Context
import com.misaka.timelog.TimeLogApp
import androidx.datastore.preferences.core.longPreferencesKey
import com.misaka.timelog.domain.Density
import com.misaka.timelog.ui.TimelineAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小部件取数的**唯一入口** —— 给它一个 Context，还你一个 [WidgetModel]。
 *
 * ## ★★ 为什么小部件刷新也要跑那四步刷新链
 *
 * 这条是本轮最容易做错的地方。
 *
 * 直觉上小部件只该「读一下、画出来」—— 它是个展示件，凭什么写库？
 * 但御坂明确说过「**很少进 App**」。那么现实就是：
 *
 * > 他可能一整天都不打开 App，只在桌面上点小部件切换事项。
 *
 * 而那四步（模板跨天、跨天接续、补密、按模板铺）**原本只挂在打开 App 时**。
 * 如果小部件不跑，结果就是：
 *
 * - 昨晚 23:50 记的「打工」一直挂着不结束，今天也不接续；
 * - 模板开着却一整天不铺 —— 而模板恰恰是「不用管它、它自己记」的那个功能；
 * - 时间线上的空白没人补，用户点桌面切换时压短邻居的依据都没有。
 *
 * 所以小部件刷新时**必须走同一套** —— 也就是 [TimelineRepository.maintain]，
 * 「打开 App」「桌面悬浮窗」走的都是它。
 *
 * ⚠️ 代价是每分钟刷新时会做几次查询。稳定状态下四步都是**只读**的
 * （没缺口就不写、没空白就不铺），所以开销在查询这一级；
 * 真机上如果发现拖累，再考虑加「跟上次快照一样就跳过」的短路。
 *
 * ## ⚠️ 不查标签
 *
 * [TimelineAssembler.day] 要求 `tagsByRec`，但小部件根本不显示标签 ——
 * 传空 map，省掉一天几十条记录的标签查询。
 */
object WidgetSnapshot {

    /**
     * 「刚才读到的那一份」，进程内缓存。
     *
     * ## ★★ 为什么需要它（M3-C 验收 D 项实测挖出来的）
     *
     * Glance 的 `updateAll()` **不保证重新组合**。实测：从切完事项到回桌面，
     * `updateAll()` 2~6ms 就返回了，`provideGlance` 一次都没进 —— 它只是把
     * **上一帧的 RemoteViews 原样又推了一遍**。于是桌面上还挂着上一件事，
     * 要等下一分钟的闹钟才纠正（最长 60 秒）。同一份代码偶尔又会真的重组，
     * 大约三成 —— 所以这个 bug 看上去像「时灵时不灵」。
     *
     * 唯一被 Glance 保证会触发重组的，是**状态变了**（`updateAppWidgetState`）：
     * 组合里读的那个键一变，`provideContent` 就会重新跑一遍。
     *
     * 但重组只重跑组合、**不会重跑 `provideGlance`** —— 也就是说重组的时候
     * 没法再去读一次数据库（那是 suspend 的，组合里不能调）。
     *
     * 所以分成两半：
     * - **读库**：刷新时在协程里读好，答案放进这个缓存；
     * - **画**：组合里从缓存拿，同时读一下 `rev` 这个状态键建立依赖。
     *
     * 这样「状态一变 → 重组 → 拿到的是刚读好的那一份」，两步之间不会错位。
     */
    @Volatile
    private var latest: WidgetModel? = null

    /** 状态里那个「第几次刷新」的键。值本身没人看，作用就是让组合依赖它。 */
    const val KEY_REV = "rev"

    /** [KEY_REV] 的类型化写法 —— 两边（写状态 / 读状态）必须用同一个，别各自 new。 */
    val REV = longPreferencesKey(KEY_REV)

    /** 组合里取当前该画的那份。进程被回收过（缓存空）时退回 `[fallback]`。 */
    fun current(fallback: WidgetModel?): WidgetModel? = latest ?: fallback

    /** 读一次快照。**必须在 IO 线程**（要开数据库）。顺手更新缓存。 */
    suspend fun load(context: Context): WidgetModel = withContext(Dispatchers.IO) {
        val container = (context.applicationContext as TimeLogApp).container
        val now = container.clock.now()
        val day = now.dayKey

        // ★★ 跟打开 App、跟桌面悬浮窗走**同一套刷新链**（顺序的理由写在
        //    `TimelineRepository.maintain()` 上）。
        //    小部件不是「只读的展示件」—— 御坂很少进 App，模板和跨天接续
        //    得靠它兜底，不然「一整天不开 App 模板就不铺」这种毛病会真实发生。
        container.timeline.maintain(day)

        val cut = Density.cutFor(day, now)
        val records = container.timeline.dayRecords(day)
        val items = container.catalog.items()
        val cats = container.catalog.categories()

        val view = TimelineAssembler.day(
            records = records,
            cut = cut,
            itemsById = items.associateBy { it.id },
            catsById = cats.associateBy { it.id },
            tagsByRec = emptyMap(),
        )

        WidgetModel.from(view, cut).also { latest = it }
    }
}
