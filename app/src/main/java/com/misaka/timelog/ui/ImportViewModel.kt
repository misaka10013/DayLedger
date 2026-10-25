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
import com.misaka.timelog.data.import.ImportPreview
import com.misaka.timelog.data.import.LegacyImport
import com.misaka.timelog.domain.ImportReport
import com.misaka.timelog.domain.Outcome
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 「从旧版时间日志导入」这一页的状态机。
 *
 * ## 为什么它有自己的 ViewModel
 *
 * 因为这一页干的事**会动到一整年的数据**，跟设置页别的行完全不是一个量级：
 * 它要有个「看一眼会发生什么」（`preview`，只读）和「真的动手」（`run`）的分界，
 * 中间隔一道确认。把这种状态塞进 `CatalogViewModel` 只会让那个类变得不敢改。
 *
 * ## 三件事它**不**做
 *
 * - 不解析 JSON（`LegacyImport` 管）
 * - 不写 `records`（`TimelineRepository` 管）
 * - 不自己拼提示文案里的事实（数字都来自数据层的报告，这里只负责把它们串成一句话）
 */
class ImportViewModel(private val importer: LegacyImport) : ViewModel() {

    /** 正在读文件 / 正在导入。界面靠它把按钮灰掉，防连点。 */
    var busy by mutableStateOf(true)
        private set

    /** 正在跑真导入（跟 [busy] 分开，因为文案不一样）。 */
    var running by mutableStateOf(false)
        private set

    /** 导入前的那一屏字。`null` = 还没读出来。 */
    var preview by mutableStateOf<ImportPreview?>(null)
        private set

    /** 连文件都读不出来时，把原因摆在页面上（而不是弹一下就没了）。 */
    var problem by mutableStateOf<String?>(null)
        private set

    /** 导入完成后的账。 */
    var report by mutableStateOf<ImportReport?>(null)
        private set

    /** 有没有可以撤销的快照。 */
    var canUndo by mutableStateOf(false)
        private set

    /** 确认对话框开着没有。 */
    var confirmOpen by mutableStateOf(false)
        private set

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /** 重新读一遍那份内置文件（从别处回到这一页时也会跑）。 */
    fun refresh() {
        if (busy) return
        viewModelScope.launch { reload() }
    }

    // ---------------------------------------------------------------- 交互

    fun askConfirm() {
        if (busy || preview == null) return
        confirmOpen = true
    }

    fun dismissConfirm() {
        confirmOpen = false
    }

    fun confirmRun() {
        confirmOpen = false
        if (running) return

        viewModelScope.launch {
            running = true
            busy = true

            when (val r = importer.run()) {
                is Outcome.Ok -> {
                    report = r.value
                    _messages.tryEmit(reportLine(r.value))
                }
                // 被拦下不是意外，是正常业务 —— 那句话说给用户听就是了
                is Outcome.Blocked -> _messages.tryEmit(r.message)
            }

            busy = false
            running = false
            reload()
        }
    }

    fun undo() {
        if (busy) return

        viewModelScope.launch {
            busy = true
            when (val r = importer.undo()) {
                is Outcome.Ok -> {
                    report = null
                    _messages.tryEmit("已经退回导入前的样子（恢复 ${r.value} 条记录）。")
                }
                is Outcome.Blocked -> _messages.tryEmit(r.message)
            }
            busy = false
            reload()
        }
    }

    // ---------------------------------------------------------------- 取数

    private suspend fun reload() {
        busy = true
        when (val p = importer.preview()) {
            is Outcome.Ok -> {
                preview = p.value
                problem = null
            }
            is Outcome.Blocked -> {
                preview = null
                problem = p.message
            }
        }
        canUndo = importer.hasSnapshot()
        // ⚠️ 这个收尾**不能忘**。忘了的话 `busy` 永远停在 true：
        //    按钮 `enabled = !busy` 一直是灰的、`askConfirm()` 也一直提前返回 ——
        //    症状是「页面看着好好的，按钮按不动」，而且**不报错、不崩**。
        //    单元测试抓不到它（那是界面接线），真机第一次点就露出来了。
        busy = false
    }

    /** 把报告串成一句话 —— 数字全部来自数据层，这里只是加标点。 */
    private fun reportLine(r: ImportReport): String {
        val hours = r.totalMinutes / 60
        return "导入完成 · ${r.days} 天 / ${r.segments} 段历史记录（共 $hours 小时）" +
            if (r.gaps > 0) "，补出 ${r.gaps} 段空白" else ""
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                ImportViewModel(importer = app.container.legacyImport)
            }
        }
    }
}
