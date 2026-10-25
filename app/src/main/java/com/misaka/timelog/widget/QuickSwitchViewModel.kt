package com.misaka.timelog.widget

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
import com.misaka.timelog.ui.TemplateView
import com.misaka.timelog.ui.TimelineAssembler
import kotlinx.coroutines.launch

/**
 * 桌面悬浮选事项窗要显示的东西 —— 只有三样：网格、正在记的那件事、「开始记」。
 *
 * ## 为什么不用现成的 `TimelineViewModel`
 *
 * 那个 ViewModel 是给**整条时间线**用的：它会顺带装配一整天几十张卡片、
 * 算周条、拉标签。悬浮窗只存在一两秒，用户要的就是「一屏网格」，
 * 拉那些属于浪费 —— 而且它有个 `sheet` 状态机（`Sheet.Picker(mode, target)`），
 * 悬浮窗得先伪造一个 `StartNew` 请求才能拿到网格，属于绕路。
 *
 * ## 但业务规则一条都没重复
 *
 * - 刷新链 → `TimelineRepository.maintain()`（跟打开 App、跟小部件刷新是同一个）
 * - 选完之后 → `TimelineRepository.pick()`（跟 App 里点「结束」是同一个）
 * - 网格本身 → 同一个 `ItemPickerSheet` 组件
 *
 * 这里只负责「把字典捞出来给网格画」。
 */
class QuickSwitchViewModel(private val app: TimeLogApp) : ViewModel() {

    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
        private set

    var items by mutableStateOf<List<ItemEntity>>(emptyList())
        private set

    /**
     * 正在计时的那件事叫什么。`null` = 空闲中。
     *
     * ⚠️ 事项被删掉但记录还挂着时给 [TimelineAssembler.ORPHAN_NAME]
     * （「事项已删除」）—— **不能显示成「空闲中」**：那条记录还在跑，
     * 把它说成空闲等于篡改历史，而且用户接下去选的事项会跟它撞在一起。
     */
    var liveName by mutableStateOf<String?>(null)
        private set

    var tpl by mutableStateOf(TemplateView())
        private set

    /** 字典读完了没。没读完不画网格 —— 画一个空面板出来会让人以为「事项全没了」。 */
    var ready by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val c = app.container
            val day = c.clock.now().dayKey

            // ★ 跟打开 App、跟小部件刷新**同一套刷新链**。
            //   御坂很少进 App，这条路径很可能是一整天里唯一一次「维护」：
            //   跨天接续、补密、按模板铺都靠它。
            c.timeline.maintain(day)

            val all = c.catalog.items()
            items = all
            categories = c.catalog.categories()

            liveName = c.timeline.liveRecord(day)?.let { r ->
                r.itemId?.let { id -> all.firstOrNull { it.id == id }?.name }
                    ?: TimelineAssembler.ORPHAN_NAME
            }

            tpl = c.timeline.templateState().let {
                TemplateView(
                    on = it.on,
                    name = it.name,
                    coveredToday = it.coveredToday,
                    exists = it.exists,
                )
            }

            ready = true
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                QuickSwitchViewModel(app)
            }
        }
    }
}
