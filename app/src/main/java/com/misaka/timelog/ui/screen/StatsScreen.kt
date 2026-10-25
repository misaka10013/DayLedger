package com.misaka.timelog.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.StatsCalc
import com.misaka.timelog.ui.StatsViewModel
import com.misaka.timelog.ui.component.MessageHost
import com.misaka.timelog.ui.component.toColor
import com.misaka.timelog.ui.stats.ChartEmpty
import com.misaka.timelog.ui.stats.Donut
import com.misaka.timelog.ui.stats.OverlayArea
import com.misaka.timelog.ui.stats.StackedBar
import com.misaka.timelog.ui.stats.TimeDist
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 简化统计页。
 *
 * ## 这一页有多薄
 *
 * **它一次除法都没有。** 列怎么切、四个数怎么算、灰带在哪、序列什么序、占比的分母是谁
 * —— 全部在 [StatsCalc]（纯函数，66 条测试）。这里只做两件事：
 *
 * 1. 把 ViewModel 算好的东西**按顺序摆出来**
 * 2. 把点击原样转给 ViewModel
 *
 * 只要有一处百分比是在这里现算的，「分母到底是 1440 还是此刻」就有了第二个定义处
 * —— 而这个错**不报错、不崩**，只会让你觉得「今天记录得特别差」。
 *
 * ## 顶部为什么是两行（而不是原型的一行）
 *
 * 原型一行里左半边是「日周月年自」、右半边是「‹ 2026.09.15-2026.10.14 ›」。
 * 那是浏览器宽度下不挤。手机上那个区间文字有 21 个字符，
 * 挤一行的话分段控件会被压成五个小黑块 —— 两行反而更清楚，也不用做响应式。
 *
 * ## 四张图怎么来的
 *
 * 由 [StatsCalc.chartsFor] 说了算（**这里不重判一次**）：
 * 「日」口径只有 ④（一天就一列，画不出趋势）；
 * 「③ 时间分布」只在**周 / 月**出现（年 / 自 的一列是「一个月」，三十天压不成 24 小时）。
 */
@Composable
fun StatsScreen(vm: StatsViewModel) {
    val snackbar = remember { SnackbarHostState() }
    MessageHost(vm.messages, snackbar)

    // ★ 每次**进入**这一页都重读一次。
    //   ViewModel 是 Activity 级的（tab 切来切去不会重建），所以第二次点开统计时
    //   它还是上一次那个实例。而中间你完全可能在时间线那边记了好几笔。
    //   ⚠️ 读的是纯数据、没有副作用，重复读一次没有代价。
    LaunchedEffect(Unit) { vm.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatTop(vm)

            val agg = vm.agg
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    vm.loading -> Hint("算着呢…")
                    agg == null -> Hint("点右上角「自」选一个区间")
                    else -> {
                        // 全空时先说一句人话，别让人对着一堆空图猜是坏了还是没数据
                        if (vm.empty) EmptyBanner()
                        ChartCards(vm, agg)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    vm.detail?.let { d ->
        DetailSheet(detail = d, onClose = vm::closeDetail)
    }

    if (vm.customOpen) {
        CustomSheet(vm)
    }
}

// ==================================================================== 顶部

/**
 * 左上「日周月年自」+ 右上「‹ 区间 ›」。
 *
 * 那串区间文字**也可以点** —— 点了直接开自定义区间面板（跟原型一致）。
 * 不然「自」这个字跟那串文字是两件事，人会不知道该点哪个。
 */
@Composable
private fun StatTop(vm: StatsViewModel) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(3.dp),
        ) {
            Mode.entries.forEach { m ->
                val on = vm.mode == m.mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) scheme.surface else Color.Transparent)
                        .clickable { vm.pickMode(m.mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = m.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Arrow("‹") { vm.nav(-1) }
            Text(
                text = vm.rangeLabel.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.openCustom() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Arrow("›") { vm.nav(1) }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 左右那两个翻区间的小箭头。 */
@Composable
private fun Arrow(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 口径枚举 + 它显示的那个字。顺序就是界面顺序，别在别处再排一遍。 */
private enum class Mode(val mode: StatsCalc.Mode, val label: String) {
    Day(StatsCalc.Mode.DAY, "日"),
    Week(StatsCalc.Mode.WEEK, "周"),
    Month(StatsCalc.Mode.MONTH, "月"),
    Year(StatsCalc.Mode.YEAR, "年"),
    Custom(StatsCalc.Mode.CUSTOM, "自"),
}

// ==================================================================== 卡片

/**
 * 按 [StatsCalc.chartsFor] 给的顺序把卡片摆出来。
 *
 * ⚠️ 「哪几张图」这件事**只有 chartsFor 一个定义处**。这里要是再 `if (mode == WEEK)` 判一次，
 * 哪天口径改了（比如「③ 也出现在年口径」），就会漏改一处 —— 而症状是
 * 「图上多了一张空的」或者「少了一张」，都不报错。
 */
@Composable
private fun ChartCards(vm: StatsViewModel, agg: StatsCalc.Agg) {
    val wholeMonth = agg.grain == StatsCalc.Grain.MONTH

    vm.charts.forEach { chart ->
        when (chart) {
            StatsCalc.Chart.AREA -> StatCard(
                title = if (wholeMonth) "单月时长对比" else "单日时长对比",
                // 叠加图的读法跟堆叠完全不同：一条线 = 一个事项自己。
                // 不写这句的话「怎么有 20 多条线乱缠在一起」是个必然的困惑。
                sub = "一条线 = 一个事项 · 点某天看明细",
                folded = AREA_KEY in vm.folded,
                onToggle = { vm.fold(AREA_KEY) },
            ) {
                if (vm.area.isEmpty()) ChartEmpty()
                else OverlayArea(
                    cols = agg.cols,
                    slices = vm.area,
                    onTapColumn = vm::tapColumn,
                )
            }

            StatsCalc.Chart.BAR -> StatCard(
                // 副标题就是这张图的读法。不写的话「柱子怎么都这么矮」是个必然的困惑。
                title = if (wholeMonth) "单月时长汇总" else "单日时长汇总",
                sub = "不含未记录 · 矮 = 那天没记全",
                folded = BAR_KEY in vm.folded,
                onToggle = { vm.fold(BAR_KEY) },
            ) {
                if (vm.bar.isEmpty()) ChartEmpty()
                else StackedBar(
                    cols = agg.cols,
                    slices = vm.bar,
                    grain = agg.grain,
                    onTapColumn = vm::tapColumn,
                )
            }

            StatsCalc.Chart.DIST -> StatCard(
                title = "时间分布",
                sub = "每根柱 = 那一天压扁的 24 小时",
                folded = DIST_KEY in vm.folded,
                onToggle = { vm.fold(DIST_KEY) },
            ) {
                TimeDist(cols = agg.cols, onTapColumn = vm::tapColumn)
            }

            StatsCalc.Chart.DONUT -> StatCard(
                title = "总时长汇总",
                sub = if (vm.mode == StatsCalc.Mode.DAY) "这一天各事项占比" else "含未记录（灰色）",
                folded = DONUT_KEY in vm.folded,
                onToggle = { vm.fold(DONUT_KEY) },
            ) {
                Donut(slices = vm.area)
            }
        }
    }
}

/** 折叠的键。跟原型一致，是**跨口径保留**的（收起来就一直是收着的）。 */
private const val AREA_KEY = "area"
private const val BAR_KEY = "bar"
private const val DIST_KEY = "dist"
private const val DONUT_KEY = "pie"

/**
 * 一张可折叠的统计卡。
 *
 * 折叠状态**不在这里存** —— 它存在 ViewModel 里（跨口径保留，跟原型一致）。
 * 这里只负责画和回调。
 */
@Composable
private fun StatCard(
    title: String,
    sub: String,
    folded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (folded) "展开" else "收起",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (folded) -90f else 0f),
            )
        }

        if (!folded) {
            Box(modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp)) {
                content()
            }
        }
    }
}

/** 页面上的一句人话（算着 / 还没选区间）。 */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

/**
 * 整个区间一条记录都没有时的提示。
 *
 * ★ 它出现在**图上面**，不是**代替图** —— 图照样画（横轴、网格都在），
 * 这样你一眼能看出「框架是好的，只是没有数据」，而不是「这页是不是坏了」。
 */
@Composable
private fun EmptyBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "这段时间一条记录都没有",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "记满一周就能看出趋势了",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ==================================================================== 点一列看明细

/**
 * 点某一列 → 那一天的明细。
 *
 * ⚠️ 这里和 [DetailSheet] 都**不算任何数** —— 标题、副标题、占比、行序
 * 全是 [StatsCalc.detail] 算好的。加一行「还没到」的判断都不行，
 * 那正是「分母用 1440 还是此刻」最容易漏出去的口子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(detail: StatsCalc.Detail, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp)
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail.sub,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            if (detail.rows.isEmpty() && detail.notYet <= 0) {
                Text(
                    text = "一条记录都没有",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            detail.rows.forEach { row ->
                DetailRowLine(
                    name = row.name,
                    color = row.color,
                    minutes = row.minutes,
                    percent = "%.1f%%".format(row.percent),
                    dim = row.isGap,
                )
            }

            // ★ 「还没到」不混进未记录那一行 —— 它在语义上**不是漏记**。
            //   今天上午看统计，剩下的半天本来就没到，算进「未记录」会让人一阵心慌。
            if (detail.notYet > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                )
                DetailRowLine(
                    name = "还没到（今天剩余）",
                    color = "#D7DEE8",
                    minutes = detail.notYet,
                    percent = "—",
                    dim = true,
                )
            }
        }
    }
}

/** 明细里的一行：色点 · 名字 · 时长 · 占比。 */
@Composable
private fun DetailRowLine(
    name: String,
    color: String,
    minutes: Int,
    percent: String,
    dim: Boolean,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(toColor(color))
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dim) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = DayKey.durationMultiDayCompact(minutes),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = percent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (dim) scheme.onSurfaceVariant else scheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

// ==================================================================== 自定义区间

/**
 * 自定义区间面板。
 *
 * 三个快捷键**只改草稿**，还是要按「确定」才生效 —— 跟原型一致。
 * 点一下就立刻切区间的话，你想试「最近 90 天」跟「最近一年」哪个好看，
 * 每点一下都真的重算一遍，还得点回来。
 *
 * ⚠️ 「跨度 ≤ 31 天按天画、超过按月画」这条也在副标题里写出来 ——
 * 它是用户唯一需要知道的口径，不写的话切到 60 天会发现
 * 「怎么突然变成按月了」，像出了 bug。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSheet(vm: StatsViewModel) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 正在挑「从」还是「到」。null = 没在挑。
    var picking by remember { mutableStateOf<Boolean?>(null) }

    ModalBottomSheet(onDismissRequest = vm::closeCustom, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp)
        ) {
            Text(
                text = "自定义统计区间",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "跨度 ≤ 31 天按「天」画，超过 31 天自动按「月」画",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField(
                    label = "从",
                    value = vm.draftFrom,
                    modifier = Modifier.weight(1f),
                ) { picking = true }
                DateField(
                    label = "到",
                    value = vm.draftTo,
                    modifier = Modifier.weight(1f),
                ) { picking = false }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quick("最近 7 天", Modifier.weight(1f)) { vm.quickDays(7) }
                Quick("最近 30 天", Modifier.weight(1f)) { vm.quickDays(30) }
                Quick("最近 90 天", Modifier.weight(1f)) { vm.quickDays(90) }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Quick("最近一年", Modifier.weight(1f)) { vm.quickDays(365) }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = vm::closeCustom, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                TextButton(onClick = vm::applyCustom, modifier = Modifier.weight(1f)) {
                    Text("确定", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // ★ 用 `key(picking)` 包一层：`rememberDatePickerState` 的初始值只在第一次生效，
    //   不加这个的话先点「从」再点「到」，日历会还停在上一次那个日期上。
    picking?.let { isFrom ->
        key(isFrom) {
            val initial = runCatching {
                DayKey.parse(if (isFrom) vm.draftFrom else vm.draftTo)
            }.getOrNull() ?: LocalDate.now()

            val state = rememberDatePickerState(
                // ⚠️ `selectedDateMillis` 是 **UTC 零点**的毫秒数，
                //    所以两边都要用 UTC 换算 —— 用系统时区的话，
                //    东八区下午打开日历，选中的会是昨天。
                initialSelectedDateMillis = initial
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
            )

            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val picked = Instant.ofEpochMilli(ms)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            if (isFrom) {
                                vm.setDraft(DayKey.of(picked), vm.draftTo)
                            } else {
                                vm.setDraft(vm.draftFrom, DayKey.of(picked))
                            }
                        }
                        picking = null
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { picking = null }) { Text("取消") }
                },
            ) {
                DatePicker(state = state)
            }
        }
    }
}

/** 「从 / 到」那个能点的日期框。 */
@Composable
private fun DateField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value.ifBlank { "选择日期" },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一个快捷键小药丸。 */
@Composable
private fun Quick(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
        )
    }
}
