package com.misaka.timelog.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misaka.timelog.TimeLogApp
import com.misaka.timelog.data.export.DataExport
import com.misaka.timelog.data.export.ExportBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 导出的两种产物。`ext` / `mime` 决定系统文件选择器的默认文件名和分享出去的类型。 */
enum class ExportKind(
    val ext: String,
    val mime: String,
    /** 界面上那个按钮写什么 —— **写「点了会发生什么」**，不是「这是什么」。 */
    val actionLabel: String,
    /** 文件名中间那一段。 */
    val fileLabel: String,
) {
    /** 完整备份：能被 App 自己读回去。 */
    Backup(ext = "json", mime = "application/json", actionLabel = "保存备份文件", fileLabel = "备份"),

    /**
     * 给 AI 读：摘要 + 逐天明细。
     *
     * ## ⚠️ MIME 必须跟后缀**对得上**，这是真机上踩出来的
     *
     * 一开始这里写的是 `text/plain`，理由是「`text/markdown` 在部分国产 ROM 里
     * 没有对应项，会报无法保存」—— 这是个**没验证过的猜测**，而且猜错了方向。
     *
     * 真机（HyperOS / Android 16）实测：`text/plain` 的规范后缀是 `.txt`，
     * 系统文件选择器发现我们给的文件名结尾是 `.md`（不是它认识的 `text/plain` 后缀），
     * 就**在后面又补了一个 `.txt`** —— 存出来是 `一天账本-给AI-2026-10-29.md.txt`。
     * 内容没错，但名字看着像 bug。
     *
     * 换成 `text/markdown` 之后系统认这个后缀，名字就是原样的 `.md`。
     *
     * 教训：**MIME 不是「随便标个大概的」**，它决定系统替你补什么后缀。
     * 要么 MIME 和后缀一致，要么就别带后缀让系统自己补。
     */
    Ai(ext = "md", mime = "text/markdown", actionLabel = "保存给 AI 的文件", fileLabel = "给AI"),
}

/**
 * 「把数据导出成文件」这一页的状态机。
 *
 * ## 它只做三件事
 *
 * 1. 进页面时算一次**概况**（多少天、多少段、多少分钟）—— 让用户知道「我要导出的是什么」
 * 2. 点「保存」时把内容写到系统给的 `Uri`（SAF，**不需要任何权限**）
 * 3. 点「分享」时先写到 `cacheDir`，再把 `FileProvider` 的 `content://` 递出去
 *
 * ## ★ 为什么每次导出都重新读一遍库，而不是缓存
 *
 * 用户可能在这一页停很久（先看看概况，再切回时间线改两笔，再回来导出）。
 * 缓存的话导出的是「进页面那一刻」的旧数据，而且**没有任何迹象**表明它是旧的 ——
 * 这种错最难发现。多读一次库几十毫秒，换「导出的永远是最新」这个确定性，值。
 *
 * ## 为什么用 `applicationContext` 而不是 Activity
 *
 * `viewModelScope` 里的协程可能比界面活得久（用户点了保存立刻返回）。
 * 抱着一个 Activity 引用不放，就是在赌「它不会被回收」。
 */
class ExportViewModel(
    private val exporter: DataExport,
    private val context: Context,
) : ViewModel() {

    /** 正在读库 / 正在写文件。界面靠它把按钮灰掉，防连点。 */
    var busy by mutableStateOf(true)
        private set

    /** 上次导出的概况。`null` = 还没读出来。 */
    var summary by mutableStateOf<Summary?>(null)
        private set

    /** 读不出来时把原因摆在页面上（而不是闪一下就没了）。 */
    var problem by mutableStateOf<String?>(null)
        private set

    /** 一句话结果提示（保存完了 / 失败了）。界面消费完要调 [clearNotice]。 */
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * 导出概况 —— 只放「能拿来核对」的量。
     *
     * ⚠️ 故意不放事项数量、分类数量这种：它们在设置页已经有了，
     * 而这一页要回答的是「导出的这份东西有多大、覆盖多久」。
     */
    data class Summary(
        val days: Int,
        val recordedSegments: Int,
        val recordedMinutes: Int,
        val gapSegments: Int,
        val gapMinutes: Int,
        val firstDay: String,
        val lastDay: String,
    )

    init {
        viewModelScope.launch { reload() }
    }

    fun clearNotice() {
        notice = null
    }

    private suspend fun reload() {
        busy = true
        try {
            val b = exporter.collect()
            problem = null
            summary = Summary(
                days = b.stats.days,
                recordedSegments = b.stats.recordedSegments,
                recordedMinutes = b.stats.recordedMinutes,
                gapSegments = b.stats.gapSegments,
                gapMinutes = b.stats.gapMinutes,
                firstDay = b.days.firstOrNull()?.day ?: "—",
                lastDay = b.days.lastOrNull()?.day ?: "—",
            )
        } catch (t: Throwable) {
            summary = null
            problem = "读不出数据：${t.message ?: t.javaClass.simpleName}"
        } finally {
            // ⚠️ 这个收尾不能忘：忘了 `busy` 永远 true，按钮全部灰着而且**不报错**
            busy = false
        }
    }

    /**
     * 存到用户选的位置（系统文件选择器已经给了 [uri]）。
     *
     * ⚠️ 用 `openOutputStream(uri, "wt")` 而不是默认模式：
     * 某些文件提供方（尤其是「下载」目录）会对已存在的同名文件**追加**而不是覆盖，
     * 表现是导出两次之后 JSON 变成两份拼在一起 —— 打不开，而且看不出原因。
     */
    fun saveTo(kind: ExportKind, uri: Uri) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val text = render(kind)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("系统没给这个位置写权限")
                }
                notice = "已保存（${kind.fileLabel}，${kb(text)}）"
            } catch (t: Throwable) {
                notice = "保存失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * 生成一份放到缓存目录里的文件，并把可分享的 `content://` Uri 交回去。
     *
     * ## 为什么不能直接把 `file://` 丢给别的 App
     *
     * Android 7 起禁止跨应用传 `file://`（`FileUriExposedException`）。
     * `FileProvider` 把 `cacheDir` 下的文件包成 `content://` 并临时授权，
     * 这是分享文件的**唯一**正路，而且**不需要任何存储权限**。
     *
     * ## 为什么每次分享都覆盖同名文件
     *
     * 同名 = 同一份内容的语义。让它按日期堆积（`-1`、`-2`）只会把缓存目录塞满，
     * 而用户下一次分享的永远是最新那份。
     */
    suspend fun prepareShare(kind: ExportKind): SharePayload? = withContext(Dispatchers.IO) {
        try {
            val text = render(kind)
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val file = File(dir, fileName(kind))
            file.writeText(text, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            SharePayload(uri = uri, mime = kind.mime, name = file.name, sizeText = kb(text))
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                notice = "分享失败：${t.message ?: t.javaClass.simpleName}"
            }
            null
        }
    }

    /** 分享用的那个「已经写好的文件」。 */
    data class SharePayload(
        val uri: Uri,
        val mime: String,
        val name: String,
        val sizeText: String,
    )

    // ---------------------------------------------------------------- 内部

    /**
     * 读库 + 渲染。
     *
     * ⚠️ 渲染切到 [Dispatchers.Default]：真实数据 793 天 / 6804 段，
     * Markdown 那份要拼几十万字符，放主线程会卡出一个可见的停顿。
     * 读库本身是 Room 的 suspend 方法（自己会切 IO），不用再包一层。
     */
    private suspend fun render(kind: ExportKind): String {
        val bundle: ExportBundle = exporter.collect()
        return withContext(Dispatchers.Default) {
            when (kind) {
                ExportKind.Backup -> exporter.backupJson(bundle)
                ExportKind.Ai -> exporter.aiMarkdown(bundle)
            }
        }
    }

    /** `一天账本-备份-2026-10-29.json` —— 中文名在分享到微信/QQ 时看得懂。 */
    fun fileName(kind: ExportKind): String = "一天账本-${kind.fileLabel}-${todayStamp()}.${kind.ext}"

    private fun todayStamp(): String =
        Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun kb(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return if (bytes < 1024) "$bytes 字节" else "${bytes / 1024} KB"
    }

    companion object {
        /** `cacheDir` 下的子目录，必须跟 `res/xml/file_paths.xml` 里的 `<cache-path>` 对上。 */
        const val SHARE_DIR = "exports"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TimeLogApp
                ExportViewModel(
                    exporter = app.container.dataExport,
                    context = app.applicationContext,
                )
            }
        }
    }
}
