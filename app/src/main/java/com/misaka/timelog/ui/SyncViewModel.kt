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
import com.misaka.timelog.cloud.SyncEngine
import com.misaka.timelog.cloud.SyncOutcome
import com.misaka.timelog.cloud.SyncPrefs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步在界面上的那一小块状态。
 *
 * ## 为什么同步要单独一个 ViewModel
 *
 * 它管的东西跟时间线/统计/字典**完全无关**：一个开关、一个 token、
 * 一句「上次同步是什么时候」。硬塞进任何一个现有 ViewModel 里，
 * 都会让那个类多一个和它职责无关的字段，而同步失败时你也分不清
 * 该去哪个 ViewModel 里找原因。
 */
class SyncViewModel(
    private val prefs: SyncPrefs,
    private val engine: SyncEngine,
) : ViewModel() {

    var enabled by mutableStateOf(prefs.enabled)
        private set

    var token by mutableStateOf(prefs.token)
        private set

    /** 后端地址 —— 默认空，必须自己填（详见 [SyncPrefs.baseUrl] 的注释）。 */
    var baseUrl by mutableStateOf(prefs.baseUrl)
        private set

    /** 正在同步 —— 按钮转圈、防连点。 */
    var busy by mutableStateOf(false)
        private set

    /** 上次同步的一句话结果。 */
    var status by mutableStateOf(statusText())
        private set

    /** 一次性提示（成功/失败），显示完就清掉。 */
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * 开 / 关同步。
     *
     * ⚠️ **关掉不清 token、也不清水位线**。理由：关掉通常只是「暂时不想传」，
     *    下次打开时从上次断的地方接着传，比从零开始合理。
     *    真要换凭证，改 token 那一格就行（旧 token 从此再也上不去）。
     */
    fun toggle(on: Boolean) {
        enabled = on
        prefs.enabled = on
        status = statusText()
    }

    /** 边输边存 —— 多一步「保存」按钮只会多一个「忘了点」的失败原因。 */
    fun updateToken(v: String) {
        token = v
        prefs.token = v
        status = statusText()
    }

    /** 服务器地址同样边输边存。 */
    fun updateBaseUrl(v: String) {
        baseUrl = v
        prefs.baseUrl = v
        status = statusText()
    }

    /**
     * 立即同步。
     *
     * @param silent true = 打开 App 时的后台同步：**失败不弹任何东西**。
     *        这条很关键 —— 每次冷启动弹一个红条说「同步失败」，
     *        用户很快就会把它当成噪音进而关掉同步，那才是真正的损失。
     *        静默失败的信息仍然落在设置页那一行里，想查的时候能看到。
     */
    fun syncNow(silent: Boolean = false) {
        if (busy) return
        if (!enabled) {
            if (!silent) notice = "先打开上面的开关"
            return
        }
        busy = true
        viewModelScope.launch {
            val out = try {
                engine.sync()
            } catch (e: Exception) {
                SyncOutcome.Failed("意外错误：${e.message}")
            }
            busy = false
            status = statusText()
            when (out) {
                is SyncOutcome.Ok -> {
                    if (!silent) {
                        notice = if (out.days > 0) {
                            "已上传 ${out.days} 天 / ${out.records} 段（${out.ms} 毫秒）"
                        } else {
                            "没有要传的变化"
                        }
                    }
                }
                is SyncOutcome.Skipped -> {
                    if (!silent) notice = out.reason
                }
                is SyncOutcome.Failed -> {
                    if (!silent) notice = "同步失败：${out.reason}"
                }
            }
        }
    }

    fun clearNotice() { notice = null }

    /** 设置页那一行的副标题。 */
    private fun statusText(): String = when {
        !enabled -> "关着 · 数据只在本机"
        baseUrl.isBlank() -> "开着，但还没填服务器地址"
        token.isBlank() -> "开着，但还没填 token"
        prefs.lastSyncAt <= 0L -> "还没同步过"
        else -> "上次同步 ${formatTime(prefs.lastSyncAt)}" +
            (prefs.lastResult.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }

    private fun formatTime(ms: Long): String = try {
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))
    } catch (e: Exception) {
        "未知时间"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                SyncViewModel(app.container.syncPrefs, app.container.syncEngine)
            }
        }
    }
}
