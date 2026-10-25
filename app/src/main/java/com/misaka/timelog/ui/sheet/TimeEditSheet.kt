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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.DurationWindow
import com.misaka.timelog.ui.TimeEditState
import kotlin.math.roundToInt

/**
 * 改时间 —— **微调**，不是「选一个日期时间」。
 *
 * ## 为什么全是 ±1 / ±5 的小按钮，而不是时钟控件
 *
 * 因为真实的修改几乎都是「差几分钟」：刚点错了早结束十分钟、
 * 想起来开始的时候其实在干别的。时间选择器（滚轮/日历）为这种修改提供的
 * 精度是**反的** —— 得滚半天才能挪 5 分钟。
 *
 * ## ★ 改完之后会发生什么（面板里必须写出来）
 *
 * 两件在用户看起来「莫名其妙」的事，都得提前说：
 *
 * 1. **留出的空隙会自动补成一条「未记录」** —— 这是这个 App 的地基，
 *    不是 bug。用户自己改的时间留下的空白，照样是「未记录」。
 * 2. **和邻居撞车时邻居会让位（被压短），不会弹窗问你** ——
 *    改完用一条提示告诉他「谁被压短了、压到几点」。
 *
 * 把这两句写在面板里，比事后解释便宜得多。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeEditSheet(
    state: TimeEditState,
    onNudge: (Char, Int) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val minutes = (state.end - state.start).coerceAtLeast(0)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = "修改时间",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = state.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(18.dp))

            TimeRow(
                label = "开始",
                minute = state.start,
                onNudge = { onNudge('a', it) },
            )

            Spacer(Modifier.height(10.dp))

            // ⚠️ 进行中的记录，终点不归用户管 —— 那是「结束」按钮的事。
            //    让它在这里可改的话，会出现「一条记录既说自己 18:00 结束、又还在计时」
            //    这种自相矛盾的状态。
            TimeRow(
                label = "结束",
                minute = state.end,
                enabled = !state.isLive,
                onNudge = { onNudge('b', it) },
            )

            Spacer(Modifier.height(16.dp))

            // ---- 时长 + 大步长 ±15 ----
            //
            // ⚠️ ±15 **不能挤进上面那两行**：一行已经有 5 个元素（4 个微调按钮 + 时间），
            //    再加两个就要把按钮从 38dp 压到 30dp —— 而「按钮太小点不准」
            //    正是这次改版的起因，压小是反方向。
            //    放在这里零成本：这行本来只有「时长 41 分」几个字，右边全是空的。
            //
            // ⚠️ 语义：±15 改的是**结束时间**（开始不动），所以它跟「时长」是同一件事。
            //    上面那两行的 -5 -1 +1 +5 一个位置都不动（按钮位置固定铁律）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.isLive) "到现在" else "时长",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = DayKey.durationSpaced(minutes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                // 进行中的记录终点不归用户管（那是「结束」按钮的事），所以这两个也一起变灰。
                //
                // ⚠️ 减号用 ASCII 的 `-`，不用全角 `−`（U+2212）：
                //    上面那两行的 `-5` 就是 ASCII，混着用会看出字形不一样；
                //    而且这个项目的字体覆盖已经在 `⚙`(U+2699) 和 `▨`(U+25A8) 上踩过两次。
                StepButton("-15", enabled = !state.isLive) { onNudge('b', -15) }
                StepButton("+15", enabled = !state.isLive) { onNudge('b', 15) }
            }

            Spacer(Modifier.height(12.dp))

            DurationSlider(
                start = state.start,
                end = state.end,
                cut = state.cut,
                enabled = !state.isLive,
                onNudge = { onNudge('b', it) },
            )

            Spacer(Modifier.height(14.dp))

            TipBox(
                text = if (state.isLive) {
                    "进行中的记录只改开始时间；结束由「结束」按钮决定。"
                } else {
                    "留出的空隙会自动补成一条「未记录」；若和邻居撞车，邻居会让位（被压短），改完会告诉你。"
                },
                emphasize = !state.isLive,
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onSave) { Text("保存") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 一行时间：`开始  −5 −1 09:30 +1 +5`。
 *
 * ⚠️ **五个按钮的位置永远固定**（这是御坂强调过三次的铁律）。
 * 到了边界（比如已经 00:00 还按 −1）按钮**变灰但不挪位置** ——
 * 位置一旦会动，肌肉记忆就废了。
 */
@Composable
private fun TimeRow(
    label: String,
    minute: Int,
    onNudge: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.width(40.dp),
        )

        StepButton("-5", enabled) { onNudge(-5) }
        StepButton("-1", enabled) { onNudge(-1) }

        Box(
            modifier = Modifier.width(74.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = DayKey.minuteLabel(minute),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }

        StepButton("+1", enabled) { onNudge(1) }
        StepButton("+5", enabled) { onNudge(5) }
    }
}

/**
 * 时长滑块 —— 「快速把这一段调长/调短」，精确微调归上面的 ±1 / ±5 / ±15。
 *
 * ## 为什么滑块的刻度是「相对当前时长的百分比」而不是「绝对分钟」
 *
 * 试过直接铺 `1 分钟 ~ 到今天的最后一个分钟`（最长 1440）。算一下就知道不行：
 *
 * > 手指能稳定移动的最小距离大约是 5dp。滑块宽 260dp、范围 1440 分钟，
 * > 于是 1dp ≈ 5.5 分钟、**5dp ≈ 27 分钟**。
 *
 * 也就是说：一条 41 分钟的记录，想拖成 45 分钟**在物理上做不到** ——
 * 它离最左边只有 7dp，手指一碰就跳到半小时以外。
 *
 * 换算成比例就没这个问题：范围是「当前时长的 1/4 ~ 4 倍」。
 * 41 分钟 → 10~164 分钟，260dp 上 1dp ≈ 0.6 分钟（5dp ≈ 3 分钟，够用）；
 * 510 分钟 → 128~1440 分钟，1dp ≈ 5 分钟（5dp ≈ 26 分钟，长记录本来也不需要那么准）。
 * **短记录自动变精细、长记录自动变粗放**，正好符合直觉。
 *
 * ## 为什么窗口要以「打开面板那一刻」为中心
 *
 * 如果窗口跟着当前值走，那滑块每次都自动居中在过去的位置上，
 * 就会变成「怎么拖都拖不到头」的橡皮筋 —— 那不是用户对滑块的心智模型。
 * 所以窗口锚在打开时的时长上；只有当前值被 ± 按钮推到窗口外时，窗口才扩大去容纳它。
 *
 * ## ⚠️ 这个控件治不了的病
 *
 * 拖的时候只看得见自己的时长在变，看不见**右边邻居被压到几点**。
 * 这是密铺时间轴的固有代价（一天恒 1440 分钟，你长它就短），
 * 不是滑块能修的 —— 松手后底部提示条会逐条说清谁被压短了。
 */
@Composable
private fun DurationSlider(
    start: Int,
    end: Int,
    cut: Int,
    enabled: Boolean,
    onNudge: (Int) -> Unit,
) {
    val maxLen = (cut - start).coerceAtLeast(1)
    val cur = (end - start).coerceIn(1, maxLen)

    // 锚点：只在这一段记录被打开时取一次（start 变了说明是另一段，重新锚）
    val anchor = remember(start) { cur.coerceIn(1, maxLen) }

    // 范围计算是纯逻辑，抽到 domain 里了 —— 那边有条断言专门盯着「1dp 跳几分钟」
    val range = DurationWindow.of(anchor = anchor, cur = cur, maxLen = maxLen)
    val lo = range.first
    val hi = range.last

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DayKey.durationSpaced(lo),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Slider(
            value = cur.toFloat().coerceIn(lo.toFloat(), hi.toFloat()),
            onValueChange = { v ->
                // Slider 给的是浮点；这里换算成「比现在多/少几分钟」再交给同一个 nudgeTime，
                // 于是边界（最早到 0、最晚到 cut）跟按钮走的是同一段代码，不会有两套规则。
                val target = v.roundToInt().coerceIn(lo, hi)
                if (target != cur) onNudge(target - cur)
            },
            valueRange = lo.toFloat()..hi.toFloat(),
            enabled = enabled && hi > lo,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = DayKey.durationSpaced(hi),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** 微调按钮。做成小圆角方块，尺寸固定 —— 不因为标签是两个字就变宽。 */
@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width = 38.dp, height = 34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}

/** 提示条。所有面板共用它 —— 免得每处各写一个颜色不一样的框。 */
@Composable
internal fun TipBox(text: String, emphasize: Boolean = false) {
    val accent = if (emphasize) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}
