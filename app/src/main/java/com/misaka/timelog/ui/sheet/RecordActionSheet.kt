package com.misaka.timelog.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misaka.timelog.ui.RecordRow
import com.misaka.timelog.ui.component.ActionItem
import com.misaka.timelog.ui.component.Sep
import com.misaka.timelog.ui.component.toColor

/**
 * 点一张卡片之后弹出来的**操作菜单**。
 *
 * ## 三张卡片，三套菜单
 *
 * 这是原型里最花心思的一处设计，也是「菜单该长什么样」这个问题的答案：
 *
 * | 卡片 | 菜单里有什么 | 为什么 |
 * |---|---|---|
 * | **一段「未记录」** | 补记一件事 ★ / 并入上一条 / 并入下一条 | 它没有内容可编辑、没有时长可拆；它存在的**唯一理由**就是被补记 |
 * | **正在进行** | 结束这件事 / 修改开始时间 / 变更事项 / 编辑内容 / 放弃 | 终点不归你管（那是「结束」按钮的事），所以只有「改开始时间」 |
 * | **一条普通记录** | 修改时间 / 变更事项 / 编辑内容 / 合并 / 拆分 / 删除 | 全套 |
 *
 * 把三套菜单合成一套、再靠禁用灰掉不适用的项，会让每张卡片点开都看到一堆
 * 「点了没反应」的项目 —— 那比菜单长一点糟糕得多。
 *
 * ## ⚠️ 「删除」不弹「你确定吗」
 *
 * 跟删标签同一套规矩：影响范围就在这一条上，删完立刻看得见、还有 4 秒可以撤销。
 * 拦一道确认反而是添堵 —— **真正该弹窗的是「删事项」**，那会一次性改掉
 * 它名下几百条历史（那个弹窗在「设置 → 事项」里）。
 *
 * ## 合并项为什么带上邻居的名字
 *
 * 「与上一条合并」这句本身不告诉你上一条是**什么**。而合并是会把邻居并进来的 ——
 * 如果邻居是那 10 小时的大空白，并完你会看到一条 10 小时的「打工」。
 * 所以菜单里直接把邻居名字写出来（`与上一个合并（吃饭）`），点之前就知道会发生什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordActionSheet(
    row: RecordRow,
    neighborAbove: String?,
    neighborBelow: String?,
    onDismiss: () -> Unit,
    onFillGap: () -> Unit,
    onEndAndPick: () -> Unit,
    onChangeItem: () -> Unit,
    onEditTime: () -> Unit,
    onEditContent: () -> Unit,
    onMerge: (Int) -> Unit,
    onSplit: () -> Unit,
    onMergeGapInto: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            HeadCard(row)

            Spacer(Modifier.height(6.dp))

            when {
                // ---------------------------------------------------- 一段空白
                row.isGap -> {
                    ActionItem(
                        icon = Icons.Filled.AddCircle,
                        title = "补记一件事",
                        subtitle = "把这段 ${row.durationLabel} 记成某件事 —— 最常用",
                        primary = true,
                        onClick = onFillGap,
                    )
                    ActionItem(
                        icon = Icons.Filled.VerticalAlignTop,
                        title = "并入上一条",
                        subtitle = neighborAbove?.let { "并进「$it」，不留缝" } ?: "上面没有可以并入的记录",
                        enabled = neighborAbove != null,
                        onClick = { onMergeGapInto(-1) },
                    )
                    ActionItem(
                        icon = Icons.Filled.VerticalAlignBottom,
                        title = "并入下一条",
                        subtitle = neighborBelow?.let { "并进「$it」，不留缝" } ?: "下面没有可以并入的记录",
                        enabled = neighborBelow != null,
                        onClick = { onMergeGapInto(1) },
                    )
                }

                // ------------------------------------------------ 正在计时的那条
                row.isLive -> {
                    ActionItem(
                        icon = Icons.Filled.Stop,
                        title = "结束这件事",
                        subtitle = "弹出面板选下一件",
                        primary = true,
                        onClick = onEndAndPick,
                    )
                    ActionItem(
                        icon = Icons.Filled.Schedule,
                        title = "修改开始时间",
                        subtitle = "当前 ${row.startLabel} · 结束由「结束」按钮决定",
                        onClick = onEditTime,
                    )
                    ActionItem(
                        icon = Icons.Filled.SwapHoriz,
                        title = "变更事项",
                        subtitle = "换一件事，时长不变",
                        onClick = onChangeItem,
                    )
                    ActionItem(
                        icon = Icons.Filled.Edit,
                        title = "编辑内容、标签",
                        subtitle = "备注 / 标签",
                        onClick = onEditContent,
                    )
                    Sep()
                    ActionItem(
                        icon = Icons.Filled.Delete,
                        title = "放弃这次记录",
                        subtitle = "那段时间变成「未记录」",
                        danger = true,
                        onClick = onDelete,
                    )
                }

                // -------------------------------------------------- 一条普通记录
                else -> {
                    ActionItem(
                        icon = Icons.Filled.Schedule,
                        title = "修改时间",
                        subtitle = "${row.startLabel} → ${row.endLabel}",
                        onClick = onEditTime,
                    )
                    ActionItem(
                        icon = Icons.Filled.SwapHoriz,
                        title = "变更事项",
                        subtitle = "换一件事，时长不变",
                        onClick = onChangeItem,
                    )
                    ActionItem(
                        icon = Icons.Filled.Edit,
                        title = "编辑内容、标签",
                        subtitle = "备注 / 标签",
                        onClick = onEditContent,
                    )
                    Sep()
                    ActionItem(
                        icon = Icons.Filled.VerticalAlignTop,
                        title = "与上一个合并",
                        subtitle = neighborAbove?.let { "并成一段，留你点的这条（$it）" }
                            ?: "上面没有可以合并的记录",
                        enabled = neighborAbove != null,
                        onClick = { onMerge(-1) },
                    )
                    ActionItem(
                        icon = Icons.Filled.VerticalAlignBottom,
                        title = "与下一个合并",
                        subtitle = neighborBelow?.let { "并成一段，留你点的这条（$it）" }
                            ?: "下面没有可以合并的记录",
                        enabled = neighborBelow != null,
                        onClick = { onMerge(1) },
                    )
                    ActionItem(
                        icon = Icons.Filled.ContentCut,
                        title = "对半拆分",
                        subtitle = if (row.durationMin < 2) {
                            "这条太短，拆不开"
                        } else {
                            "从中间切成两段，各约 ${row.durationMin / 2} 分钟"
                        },
                        enabled = row.durationMin >= 2,
                        onClick = onSplit,
                    )
                    Sep()
                    ActionItem(
                        icon = Icons.Filled.Delete,
                        title = "删除",
                        subtitle = "那段时间变成「未记录」· 4 秒内可撤销",
                        danger = true,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

/** 菜单顶上那块 —— 「你现在点的是哪一条」。少了它，长菜单容易点错行。 */
@Composable
private fun HeadCard(row: RecordRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(toColor(row.colorHex))
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = buildString {
                append("${row.startLabel} → ${row.endLabel}")
                append(" · ${row.durationLabel}")
                row.categoryName?.let { append(" · $it") }
                if (row.fromTemplate) append(" · 模板")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ⚠️ 菜单的「一项」和「分隔线」已经搬到 `ui.component.SheetKit` 了 ——
//    模板段的菜单用的是同一套积木（见 SheetKit 的注释）。
