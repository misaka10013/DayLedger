package com.misaka.timelog.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.misaka.timelog.ui.ImportViewModel
import com.misaka.timelog.ui.component.ConfirmDialog
import com.misaka.timelog.ui.component.MessageHost
import kotlinx.coroutines.delay

/**
 * 「从旧版时间日志导入」。
 *
 * ## 这一页只有一件事要做对：**别让用户稀里糊涂按下去**
 *
 * 它是全 App 唯一一个会动到一整年记录的操作。所以顺序是：
 *
 * 1. 先把**会发生什么**摆出来（多少天、多少段、会清掉现在几条）
 * 2. 按钮按下去 → 再问一遍「确定吗」（只有这一个操作配得上确认框）
 * 3. 干完把**账**摆出来，并留一个「撤销」
 *
 * ⚠️ 这里一个数字都不自己算 —— 全是 `ImportPreview` / `ImportReport` 里带过来的。
 * 界面现算一个「大概多少小时」，就等于把口径复制到了第二个地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageHost(vm.messages, snackbar)

    // SAF 选文件：不需要任何存储权限 —— 用户选中那一份，App 拿到的是**那一个** Uri 的临时读权限。
    //
    // ⚠️ MIME 用 `*/*` 而不是 `application/json`：国产 ROM 的文件选择器对自定义 MIME
    //    过滤得很随意，写死 `application/json` 时用户在「下载」目录里可能根本看不到
    //    自己刚导出的 .json（灰着、或者干脆不列出来）。反正 `LegacyImport` 会拒绝
    //    任何不像备份的文件，放宽选择范围不会带来风险。
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.useFile(it) } }

    val scroll = rememberScrollState()

    // ★ 导入完成后自动滚到底。
    //
    //   不滚的话有个很坑的后果：结果卡和「撤销」按钮被顶到屏幕下面去了，
    //   用户看到的是上面那张「将要写入」的预览卡 —— 会以为「点了没反应」。
    //   真机第一次就踩到了（`uiautomator dump` 里那两行压根不存在，因为不在可视区）。
    //
    //   ⚠️ 那个 `delay` 不是凑数：滚动的目标 `maxValue` 要等这张新卡片量完高度
    //   才更新，立刻滚会滚到旧的最大值、停在半路。
    LaunchedEffect(vm.report) {
        if (vm.report != null) {
            delay(150)
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从旧版导入") },
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
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "可以把旧版「时间日志」的历史搬进来，也可以导入自己导出的备份。" +
                    "事项和标签会按名字对上，App 里没有的会新建。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            /* ★ 来源卡片。

               为什么把它放在最上面：这一页原本只有「内置文件」一个来源，
               现在多了一份「用户自己挑的文件」—— 而**把哪个导进去**是这一页
               唯一的前提。用户必须一眼看清"我现在要导的是哪份"，
               否则就会出现"我明明选了备份，怎么导进去的是演示数据"这种事。 */
            CardBlock {
                Text("要从哪份数据导入", style = MaterialTheme.typography.titleSmall)
                HLine()
                KeyValue("当前", vm.sourceLabel)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = !vm.busy,
                    ) {
                        Text("选择备份文件")
                    }
                    if (vm.usingFile) {
                        OutlinedButton(onClick = { vm.useBuiltin() }, enabled = !vm.busy) {
                            Text("换回内置")
                        }
                    }
                }
            }

            vm.problem?.let { why ->
                CardBlock {
                    // ⚠️ 这里**不加前缀**：`ImportViewModel.reload()` 已经按来源
                    //    把话写全了（内置那边是"读不出内置的旧数据文件：…"，
                    //    外部文件那边直接就是 `LegacyImport` 返回的那句）。
                    //    在这儿再加一句就变成"读不出…：读不出…"。
                    Text(
                        text = why,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            vm.preview?.let { p ->
                CardBlock {
                    Text("将要写入", style = MaterialTheme.typography.titleSmall)
                    HLine()
                    KeyValue("天数", "${p.days} 天")
                    KeyValue("记录", "${p.segments} 段")
                    KeyValue("空白", "预计补出 ${p.gapCount} 段 · ${hm(p.gapMinutes)}")
                    KeyValue("时间跨度", "${p.firstDay} → ${p.lastDay}")
                }

                if (p.toCreate.isNotEmpty()) {
                    CardBlock {
                        Text("会新建 ${p.toCreate.size} 个事项", style = MaterialTheme.typography.titleSmall)
                        HLine()
                        Text(
                            text = p.toCreate.joinToString("、"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "它们会挂到同名或原本对应的分类下。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                /* 自建分类会顺带被重建 —— 只有"从另一台设备导回来"时才可能出现。
                   必须写出来：用户看到分类列表里多了一个，得能回想起它是哪来的。 */
                if (p.toCreateCategories.isNotEmpty()) {
                    CardBlock {
                        Text(
                            text = "会新建 ${p.toCreateCategories.size} 个分类",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        HLine()
                        Text(
                            text = p.toCreateCategories.joinToString("、"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "这几个分类在这台手机的 App 里没有（通常是你自己建的），" +
                                "导入时会按备份里的名字和颜色重建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                if (p.hardMissingItems.isNotEmpty()) {
                    CardBlock {
                        Text(
                            text = "有 ${p.hardMissingItems.size} 个事项在 App 里彻底找不到：" +
                                p.hardMissingItems.joinToString("、") +
                                "\n这一包不会被导入 —— 先去把映射修好，重新生成内置文件。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (p.missingTags.isNotEmpty()) {
                    CardBlock {
                        Text(
                            text = "有 ${p.missingTags.size} 个标签在 App 里找不到（" +
                                p.missingTags.joinToString("、") +
                                "），这些标签会被丢掉，但记录本身照样导入。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // ★ 这一块是重点：把「会清掉什么」说在最显眼的地方
                CardBlock(emphasis = true) {
                    Text("⚠ 导入会替换掉 App 现在所有的记录", style = MaterialTheme.typography.titleSmall)
                    HLine()
                    Text(
                        text = if (p.existingRecords == 0) {
                            "App 现在一条记录都没有，不会清掉任何东西。"
                        } else {
                            "App 现在有 ${p.existingRecords} 条记录，它们会被清掉。" +
                                "（导入前会自动留一份快照，导完可以撤销。）"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    onClick = { vm.askConfirm() },
                    enabled = !vm.busy && p.hardMissingItems.isEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text(if (vm.running) "正在导入…" else "开始导入")
                }
            }

            // ---------------------------------------------------------- 账 + 撤销
            vm.report?.let { r ->
                Text(
                    text = "导入完成",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
                )
                CardBlock {
                    KeyValue("天数", "${r.days} 天")
                    KeyValue("记录", "${r.segments} 段")
                    KeyValue("空白", "${r.gaps} 段 · ${hm(r.gapMinutes)}")
                    KeyValue("合计", "${hm(r.totalMinutes)}（= ${r.days} × 24 小时）")
                    if (r.skippedDays.isNotEmpty()) {
                        HLine()
                        Text(
                            text = "跳过 ${r.skippedDays.size} 天没有导入（今天和以后的日子" +
                                "在 App 里另有记录）：${r.skippedDays.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                reportHint()
            }

            if (vm.canUndo) {
                OutlinedButton(
                    onClick = { vm.undo() },
                    enabled = !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text("撤销这次导入（回到导入前的记录）")
                }
            }

            Text(
                text = "原始的那份表格没有被改动，App 里也只是把记录换了一份 —— " +
                    "任何时候都能用这里撤销回去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 40.dp),
            )
        }
    }

    if (vm.confirmOpen) {
        val p = vm.preview
        ConfirmDialog(
            title = "确定要导入吗",
            text = buildString {
                append("会写入 ${p?.days ?: 0} 天、${p?.segments ?: 0} 段历史记录。")
                if ((p?.existingRecords ?: 0) > 0) {
                    append("\nApp 里现有的 ${p?.existingRecords} 条记录会被清掉（可以撤销）。")
                }
            },
            confirmLabel = "导入",
            onDismiss = { vm.dismissConfirm() },
            onConfirm = { vm.confirmRun() },
        )
    }
}

/** 结果后面跟一句「去哪儿看得到」。导入完了不知道去哪看，等于没导。 */
@Composable
private fun reportHint() {
    Text(
        text = "现在可以退回「时间线」翻翻 2025 年那几天，" +
            "或者在「统计」里选「年」口径看看有没有内容。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// ---------------------------------------------------------------- 小零件

@Composable
private fun CardBlock(emphasis: Boolean = false, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (emphasis) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

@Composable
private fun HLine() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 分钟数说成人话：「1 天 3 时 5 分」/「2 时 7 分」/「13 分」。 */
private fun hm(minutes: Int): String {
    if (minutes <= 0) return "0 分"
    val d = minutes / 1440
    val h = (minutes % 1440) / 60
    val m = minutes % 60
    return buildString {
        if (d > 0) append("$d 天 ")
        if (d > 0 || h > 0) append("$h 时 ")
        append("$m 分")
    }
}
