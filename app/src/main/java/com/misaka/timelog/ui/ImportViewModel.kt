package com.misaka.timelog.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「导入」这一页的状态机。
 *
 * ## 为什么它有自己的 ViewModel
 *
 * 因为这一页干的事**会动到一整年的数据**，跟设置页别的行完全不是一个量级：
 * 它要有个「看一眼会发生什么」（`preview`，只读）和「真的动手」（`run`）的分界，
 * 中间隔一道确认。把这种状态塞进 `CatalogViewModel` 只会让那个类变得不敢改。
 *
 * ## 两个来源，同一条路径
 *
 * | 来源 | 从哪来 | 什么时候用 |
 * |---|---|---|
 * | **内置** | `assets/legacy_timelog.json`（随包出厂） | 从旧版「时间日志」搬历史 |
 * | **外部文件** | 用户用系统文件选择器挑的那份 | **换手机 / 重装 / 换签名版本后导回来** |
 *
 * ⚠️ 两者走的是 `LegacyImport` 里同一条 `ensureItems → plan → replaceAllRecords` 路径，
 * 只是「文字从哪来」不同。所以不存在「备份导回来和内置导入规则不一样」这种可能。
 *
 * ## 三件事它**不**做
 *
 * - 不解析 JSON（`LegacyImport` 管）
 * - 不写 `records`（`TimelineRepository` 管）
 * - 不自己拼提示文案里的事实（数字都来自数据层的报告，这里只负责把它们串成一句话）
 */
class ImportViewModel(
    private val importer: LegacyImport,
    private val context: Context,
) : ViewModel() {

    /** 现在要导的是哪一份。 */
    private sealed interface Source {
        data object Builtin : Source
        class File(val text: String) : Source
    }

    private var source: Source = Source.Builtin

    /** 界面上那句「现在选的是哪份」。 */
    var sourceLabel by mutableStateOf("随 App 内置的旧版数据")
        private set

    /**
     * 现在用的是外部文件吗？
     *
     * ⚠️ 单独存一个 `mutableStateOf` 而不是写成 `val usingFile get() = source is Source.File`：
     *    `source` 是普通字段，它的变化不会触发 Compose 重组，
     *    界面上那个「换回内置」按钮就会永远不出现（或者永远出现）—— 不报错，只是点不到。
     */
    var usingFile by mutableStateOf(false)
        private set

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

    /** 重新读一遍当前来源（从别处回到这一页时也会跑）。 */
    fun refresh() {
        if (busy) return
        viewModelScope.launch { reload() }
    }

    // ---------------------------------------------------------------- 换来源

    /**
     * 换成用户挑的外部文件。
     *
     * ⚠️ 读文件放在 [Dispatchers.IO]：真实备份有 400~500 KB，
     *    在主线程 `readBytes()` 会卡出一个可见的停顿（而且是在点完按钮之后立刻卡，
     *    看起来像"点了没反应"）。
     *
     * ⚠️ 有大小上限：用户可能选中了一张照片或者一段视频。
     *    没有上限的话，视频会被整个读进内存再当 JSON 解析 —— 直接 OOM 崩掉。
     */
    fun useFile(uri: Uri) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            problem = null

            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // 只读前 MAX_BYTES 个字节做判断，避免把大文件整个吞进内存
                        val buf = ByteArray(MAX_BYTES + 1)
                        var total = 0
                        while (total < buf.size) {
                            val n = input.read(buf, total, buf.size - total)
                            if (n <= 0) break
                            total += n
                        }
                        if (total > MAX_BYTES) null else String(buf, 0, total, Charsets.UTF_8)
                    }
                }.getOrNull()
            }

            if (text == null) {
                problem = "这个文件读不出来，或者它太大了（超过 ${MAX_BYTES / 1024 / 1024} MB，" +
                    "不像是时间记录的备份）。"
                busy = false
                return@launch
            }

            source = Source.File(text)
            sourceLabel = displayName(uri)
            usingFile = true
            report = null // 换了来源，上一份的账就不算数了
            reload()      // reload 自己管 busy 的收尾
        }
    }

    /** 换回随包内置的那份。 */
    fun useBuiltin() {
        if (busy) return
        if (source is Source.Builtin) return
        source = Source.Builtin
        sourceLabel = "随 App 内置的旧版数据"
        usingFile = false
        report = null
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

            val result = when (val s = source) {
                Source.Builtin -> importer.run()
                is Source.File -> importer.runOf(s.text)
            }

            when (result) {
                is Outcome.Ok -> {
                    report = result.value
                    _messages.tryEmit(reportLine(result.value))
                }
                // 被拦下不是意外，是正常业务 —— 那句话说给用户听就是了
                is Outcome.Blocked -> _messages.tryEmit(result.message)
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
        val result = when (val s = source) {
            Source.Builtin -> importer.preview()
            is Source.File -> importer.previewOf(s.text)
        }
        when (result) {
            is Outcome.Ok -> {
                preview = result.value
                problem = null
            }
            is Outcome.Blocked -> {
                preview = null
                // 内置文件读不出来 → 说"内置"；外部文件读不出来 → 直接说那句话
                problem = if (source is Source.Builtin) {
                    "读不出内置的旧数据文件：${result.message}"
                } else {
                    result.message
                }
            }
        }
        canUndo = importer.hasSnapshot()
        // ⚠️ 这个收尾**不能忘**。忘了的话 `busy` 永远停在 true：
        //    按钮 `enabled = !busy` 一直是灰的、`askConfirm()` 也一直提前返回 ——
        //    症状是「页面看着好好的，按钮按不动」，而且**不报错、不崩**。
        //    单元测试抓不到它（那是界面接线），真机第一次点就露出来了。
        busy = false
    }

    /**
     * 从 Uri 里问出用户看得见的文件名。
     *
     * ⚠️ 不能拿 `uri.lastPathSegment` 充数：SAF 给的 Uri 长这样
     * `content://com.android.providers.downloads.documents/document/msf%3A1000000042`，
     * 显示出来是一串编号，用户根本认不出自己选的是哪个文件。
     */
    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull() ?: "你选的文件"

    /** 把报告串成一句话 —— 数字全部来自数据层，这里只是加标点。 */
    private fun reportLine(r: ImportReport): String {
        val hours = r.totalMinutes / 60
        return "导入完成 · ${r.days} 天 / ${r.segments} 段历史记录（共 $hours 小时）" +
            if (r.gaps > 0) "，补出 ${r.gaps} 段空白" else ""
    }

    companion object {
        /**
         * 备份文件的上限。
         *
         * 真实数据 793 天约 500 KB；留 16 MB 给「很多年的重度用户」绰绰有余，
         * 同时挡掉「误选了一段视频」这种情况。
         */
        private const val MAX_BYTES = 16 * 1024 * 1024

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                ImportViewModel(
                    importer = app.container.legacyImport,
                    context = app.applicationContext,
                )
            }
        }
    }
}
