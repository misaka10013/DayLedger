package com.misaka.timelog.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.misaka.timelog.domain.CalendarGrid

/**
 * 月历 —— 点时间线顶部那个日期标题弹出来的东西。
 *
 * ## 它解决的是哪件事
 *
 * 御坂的原话：「我没有办法像打开日历一样去选中一个月或者几个月之前的某一天，
 * 现在往回翻以前的历史某一天，都只能不断地点击今天左边那个箭头。」
 *
 * 所以这个弹窗的**唯一职责就是把「翻到某一天」从 N 次点击压到 2~3 次**：
 * 翻年（`«` `»`）→ 翻月（`‹` `›`）→ 点那一天。
 *
 * ## 为什么不复用系统的日期选择器
 *
 * 因为要画「这天有记录」的小圆点，而系统 `DatePicker` 不让往里塞自定义内容。
 * 而小圆点是这个弹窗**真正有用**的地方 —— 没有它，用户还是得一天一天点着找。
 *
 * ## ⚠️ 格子一个都不在这里算
 *
 * 全部来自 `CalendarGrid.of()`（纯函数、有单测）。这里只负责按顺序铺 42 格。
 * 偏移算错一格的话，每一格仍然是合法日期、仍然点得动 —— 截图完全看不出来，
 * 只有跳到错误的日子时才会发现。所以那部分逻辑不许出现在界面层。
 *
 * @param daysWithData 这个月里**有真实记录**的天，画白点
 * @param daysWithGap  这个月里**存在「未记录」空白块**的天，画黄点
 *                     （两个维度独立：一天可以既有记录又有缝，那种天两个点都亮）
 * @param today         今天的键 —— 今天那格画个圈；比它晚的日子不可点
 */
/**
 * 「有未记录」那颗点的颜色。
 *
 * 用固定琥珀色而不是主题色：它表达的是**状态**（有缺漏），
 * 跟主题的 primary/secondary 无关。换主题时它不该跟着变，否则就没有固定含义了。
 */
private val GapAmber = Color(0xFFF2A93B)

@Composable
fun MonthCalendarDialog(
    year: Int,
    month: Int,
    daysWithData: Set<String>,
    daysWithGap: Set<String>,
    today: String,
    onShift: (Int) -> Unit,
    onPick: (String) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val monthData = CalendarGrid.of(year, month, today, daysWithData, daysWithGap)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                // ------------------------------------------------ 头部：年 / 月
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowButton(label = "«", desc = "上一年") { onShift(-12) }
                    ArrowButton(label = "‹", desc = "上个月") { onShift(-1) }
                    Text(
                        text = monthData.title,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ArrowButton(label = "›", desc = "下个月") { onShift(1) }
                    ArrowButton(label = "»", desc = "下一年") { onShift(12) }
                }

                Spacer(Modifier.height(6.dp))

                // ------------------------------------------------ 星期表头（周一起）
                Row(Modifier.fillMaxWidth()) {
                    CalendarGrid.WEEK_HEADER.forEach { w ->
                        Text(
                            text = w,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ------------------------------------------------ 42 格
                // ⚠️ 恒定 6 行：翻月时弹窗高度不跳，手正点在日期上时界面不会自己缩走
                monthData.cells.chunked(CalendarGrid.COLS).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { cell ->
                            DayCell(
                                cell = cell,
                                onClick = { onPick(cell.key) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // ------------------------------------------------ 底部
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onToday) { Text("回到今天") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 一颗日期格。
 *
 * 三种态各自靠**形状**区分，不靠颜色深浅 —— 色弱也要能看出来：
 * - 今天 → 实心圈（主色）
 * - 这一天 → 透明
 * - 有记录 → 底下一个小圆点
 * - 未来 → 字变淡、点不动
 */
@Composable
private fun DayCell(
    cell: CalendarGrid.Cell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 前后月补位的日子画淡，但仍然能点 —— 它是真实存在的一天，点了就该跳过去
    val enabled = !cell.future
    val textColor = when {
        cell.isToday -> scheme.onPrimaryContainer
        !cell.inMonth -> scheme.onSurface.copy(alpha = 0.30f)
        cell.future -> scheme.onSurface.copy(alpha = 0.30f)
        else -> scheme.onSurface
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .size(width = 40.dp, height = 44.dp)
                .clip(CircleShape)
                .then(
                    if (cell.isToday) {
                        Modifier.background(scheme.primaryContainer)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = enabled, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = cell.day.toString(),
                color = textColor,
                fontSize = 14.sp,
                fontWeight = if (cell.isToday) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(3.dp))
            // 小圆点。⚠️ 位置**恒占**（没记录也留出 5dp 的空高），
            // 否则有没有点的格子字会上下跳一行，整张月历看起来歪歪扭扭。
            //
            // 两个点各管一件事，互不替换：
            //   白点 = 这天有真实记录   黄点 = 这天里还有「未记录」的缝
            // 一天可能两个都有（记了大半天、中间漏了一段），那种天必须两个都亮，
            // 否则「有记录」会把「有缝」盖掉，而后者恰恰是最该提醒的。
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .then(
                            if (cell.hasData) {
                                Modifier.background(if (cell.isToday) scheme.primary else scheme.onSurfaceVariant)
                            } else {
                                Modifier
                            }
                        )
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .then(
                            if (cell.hasGap) Modifier.background(GapAmber) else Modifier
                        )
                )
            }
        }
    }
}

/** 头部那四个翻页箭头。用文字而不是图标：`«` `»` 在 WGL4 里，任何系统字体都有。 */
@Composable
private fun ArrowButton(label: String, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            // 箭头本身没有可读语义，读屏时要靠这一句说清「这是干嘛的」
            .clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
