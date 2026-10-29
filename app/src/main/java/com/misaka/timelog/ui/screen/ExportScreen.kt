package com.misaka.timelog.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.misaka.timelog.ui.ExportKind
import com.misaka.timelog.ui.ExportViewModel
import kotlinx.coroutines.launch

/**
 * 「导出数据」—— 把本机记录变成两个文件：**能读回去的备份** 和 **给 AI 读的 Markdown**。
 *
 * ## 这一页的设计取向：给「不联云的人」一条完整的路
 *
 * 云同步那一节是**可选**的（要自己填服务器地址和 token）。不填的人，
 * 数据就只活在手机里 —— 换手机、重装、或者想让 AI 看看自己的时间去哪了，
 * 原本一点办法都没有（只能 `adb` 抠）。这一页补的就是这个缺口。
 *
 * ## 两个动作的区别（界面上要让人一眼看懂，不然会点错）
 *
 * | 动作 | 会发生什么 | 适合什么场景 |
 * |---|---|---|
 * | **保存** | 系统弹文件选择器，你自己挑位置（下载 / 文档 / 网盘） | 要长期留一份、要拷到电脑 |
 * | **分享** | 系统弹分享面板，直接发给别的 App（微信 / QQ / 任一 AI App） | 马上要发给谁 |
 *
 * ## ★ 为什么一个权限都不加
 *
 * 「保存」走 `ACTION_CREATE_DOCUMENT`（SAF）——**用户自己选位置**，App 拿到的只是
 * 那一个文件的临时写权限，「保存」完就失效。
 * 「分享」把文件写进 `cacheDir` 再用 `FileProvider` 递出去，同样不碰外部存储。
 *
 * 所以这个功能加完之后，`AndroidManifest.xml` 里的 `uses-permission` **还是只有 INTERNET 一条**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    vm: ExportViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 结果提示统一走 Snackbar，并且**立即清空状态** ——
    // 留着不清的话，旋转屏幕/重进页面会把它再弹一遍。
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    // SAF：输入是「建议的文件名」，输出是用户选中的 Uri（取消则为 null）。
    // ⚠️ 两个格式要两个 launcher —— contract 的 MIME 在 remember 时就定死了，
    //    想用一个 launcher 加个变量切换是不行的（它不会重建 contract）。
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportKind.Backup.mime),
    ) { uri -> uri?.let { vm.saveTo(ExportKind.Backup, it) } }

    val saveAi = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportKind.Ai.mime),
    ) { uri -> uri?.let { vm.saveTo(ExportKind.Ai, it) } }

    /**
     * 分享：先把内容落到 `cacheDir`，再把 `content://` 递出去。
     *
     * ⚠️ `FLAG_GRANT_READ_URI_PERMISSION` 必须加 —— 不加的话对方 App
     * 拿到的 Uri 打得开文件名、读内容时抛 `SecurityException`，
     * 表现是「分享出去是个空文件 / 对方提示文件损坏」。
     */
    fun share(kind: ExportKind) {
        scope.launch {
            val payload = vm.prepareShare(kind) ?: return@launch
            val send = Intent(Intent.ACTION_SEND).apply {
                type = payload.mime
                putExtra(Intent.EXTRA_STREAM, payload.uri)
                putExtra(Intent.EXTRA_SUBJECT, payload.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享这份数据"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "数据全在本机。这里可以把它变成一个文件——" +
                    "留一份备份，或者发给 AI 让它帮你看看时间都去哪了。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            vm.problem?.let { why ->
                CardBlock {
                    Text(
                        text = "读不出数据：$why",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val s = vm.summary
            if (s != null) {
                if (s.days == 0) {
                    CardBlock {
                        Text("还没有任何记录", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "先记上一两笔再回来导出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CardBlock {
                        Text("要导出的内容", style = MaterialTheme.typography.titleSmall)
                        HLine()
                        KeyValue("覆盖区间", "${s.firstDay} ~ ${s.lastDay}")
                        KeyValue("天数", "${s.days} 天")
                        KeyValue("已记录", "${s.recordedSegments} 段 · ${hours(s.recordedMinutes)}")
                        if (s.gapSegments > 0) {
                            KeyValue("未记录", "${s.gapSegments} 段 · ${hours(s.gapMinutes)}")
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 备份
            CardBlock(emphasis = true) {
                Text("① 完整备份", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "换手机、重装、或者想留个底的时候用。格式和 App 的导入功能完全一致，能原样读回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                HLine()
                RowButtons(
                    primaryLabel = ExportKind.Backup.actionLabel,
                    enabled = !vm.busy,
                    onPrimary = { saveBackup.launch(vm.fileName(ExportKind.Backup)) },
                    onShare = { share(ExportKind.Backup) },
                )
            }

            // ---------------------------------------------------------- 给 AI
            CardBlock {
                Text("② 给 AI 读的记录", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Markdown 文本：先是一张按分类/事项的汇总表，再是每天的明细。" +
                        "「未记录」也会写出来——那段时间是没记，不是缺数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                HLine()
                RowButtons(
                    primaryLabel = ExportKind.Ai.actionLabel,
                    enabled = !vm.busy,
                    onPrimary = { saveAi.launch(vm.fileName(ExportKind.Ai)) },
                    onShare = { share(ExportKind.Ai) },
                )
            }

            // ---------------------------------------------------------- 提醒
            // ⚠️ 这里不能用 Markdown 的 `**加粗**` —— Compose 的 Text 不解析 Markdown，
            //    原样显示出来就是两个星号。要强调就换行、单独说。
            Text(
                text = "⚠ 今天的记录要等到明天才能导回来。\n" +
                    "本 App 只写「已经过去的时间」，导入时会跳过今天和以后的日子 —— " +
                    "免得和此刻正在跑的那一段打架。所以如果是要换手机，隔一天再导最稳。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

/** 一行两个按钮：保存（实心）/ 分享（描边）。**主次固定，不随状态换位置**。 */
@Composable
private fun RowButtons(
    primaryLabel: String,
    enabled: Boolean,
    onPrimary: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(primaryLabel, maxLines = 1)
        }
        OutlinedButton(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("分享给其他 App", maxLines = 1)
        }
    }
}

@Composable
private fun CardBlock(
    emphasis: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (emphasis) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun HLine() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** `937075 分钟` → `15617 小时 55 分`。给了一个人看得懂的量纲。 */
private fun hours(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m 分"
        m == 0 -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}
