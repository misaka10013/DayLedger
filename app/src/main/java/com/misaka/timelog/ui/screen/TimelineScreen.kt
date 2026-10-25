package com.misaka.timelog.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.ui.PickerMode
import com.misaka.timelog.ui.RecordRow
import com.misaka.timelog.ui.Sheet
import com.misaka.timelog.ui.TimelineViewModel
import com.misaka.timelog.ui.UndoState
import com.misaka.timelog.ui.WeekCell
import com.misaka.timelog.ui.component.ConfirmDialog
import com.misaka.timelog.ui.component.MonthCalendarDialog
import com.misaka.timelog.ui.component.toColor
import com.misaka.timelog.ui.sheet.ContentEditSheet
import com.misaka.timelog.ui.sheet.PickerHost
import com.misaka.timelog.ui.sheet.RecordActionSheet
import com.misaka.timelog.ui.sheet.TimeEditSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 时间线 —— 这个 App 的**主页面**，也是那面「镜子」。
 *
 * M2-A 它只有只读的一只眼；M2-B 给它装上了手。
 *
 * ## 界面层的纪律
 *
 * 这里**一行业务判断都不该有**。所有「这条是不是空白」「时长怎么算」
 * 「删除之后会变成什么」都已经在 [TimelineViewModel] / 数据层里答完了，
 * 这一层只负责把 [RecordRow] 画出来、把点击转给 ViewModel。
 *
 * 连提示条上那句话都不是这里拼的 —— 「「学习」让位，压短到 11:40–12:00」
 * 是数据层给的。改口径时只改一处，界面不会跟它说岔。
 */
@Composable
fun TimelineScreen(
    vm: TimelineViewModel,
    modifier: Modifier = Modifier,
) {
    // 界面上唯一允许出现的「时间」：把刷新对齐到下一个整分钟。
    //
    // 为什么需要它：时间线尾部那段空白的长度、底部「已持续 1时12分」，
    // 都是「到现在为止」的量。停在页面上不动的话，它们会一直停在打开那一刻。
    LaunchedEffect(vm.isToday) {
        if (!vm.isToday) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            delay(MINUTE_MS - (now % MINUTE_MS) + 200)
            vm.refresh()
        }
    }

    // 提示条。⚠️ 用自己画的一条，不用 Snackbar ——
    // 因为撤销条也得占这个位置，两套机制会互相盖住。
    var tip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.messages) {
        vm.messages.collect { msg ->
            tip = msg
            launch {
                delay(TIP_MS)
                // 只有「还是这一条」才清 —— 否则会把后面来的那条提前清掉
                if (tip == msg) tip = null
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TimelineHead(vm)
        WeekBar(vm)

        // ★ 模板运行中那条横条。
        //   ⚠️ **只在看今天时出现** —— 模板只对「今天」负责（applyTemplate 里就拦着），
        //      翻到周二还挂一根「运行中」，人会以为周二那些空白也会被自动填。
        //      这正是原型第 19 轮修过的那类 bug 的同一种错觉。
        //   ★ 今天没被指派（休息日）横条也留着 —— 模板**没有被退出**，只是今天不铺。
        //      悄悄收起来的话，你周一打开会以为「模板怎么没了」，还得重新启用一次。
        if (vm.tpl.on && vm.isToday) {
            TplRunningBar(
                name = vm.tpl.name,
                covered = vm.tpl.coveredToday,
                onClick = { vm.askStopTemplate() },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                vm.loading -> Loading()
                vm.rows.isEmpty() -> EmptyHint(vm)
                else -> RecordList(rows = vm.rows, onOpen = { vm.openMenu(it) })
            }
        }

        // ★ 底部这两条共用同一个位置：撤销条优先。
        //   删完立刻点撤销是最常见的手势，它必须离拇指最近、且不被别的东西挤开。
        vm.undo?.let { UndoBar(state = it, onUndo = { vm.doUndo() }) }
            ?: tip?.let { TipBar(it) }

        DockBar(vm)
    }

    Sheets(vm)

    // 月历。⚠️ 放在 Sheets 之外 —— 它不是「针对某一条记录」的面板，
    //    翻页时也不该被 `closeSheets()` 连带关掉（那是 Sheet 的规矩，见 vm.go）。
    vm.calendar?.let { c ->
        MonthCalendarDialog(
            year = c.year,
            month = c.month,
            daysWithData = c.daysWithData,
            daysWithGap = c.daysWithGap,
            today = vm.todayKey,
            onShift = { vm.shiftMonth(it) },
            onPick = { vm.jumpTo(it) },
            onToday = { vm.jumpTo(vm.todayKey) },
            onDismiss = { vm.closeCalendar() },
        )
    }
}

private const val MINUTE_MS = 60_000L
private const val TIP_MS = 2_600L

// ============================================================ 面板

/**
 * 把当前打开的面板挂上去。
 *
 * ⚠️ 这里**没有「面板自己的开关状态」** —— 开不开、为谁开、以什么模式开，
 * 全部由 `vm.sheet` 一个值说了算。「关闭」就是把它置成 [Sheet.None]，
 * 模式随之消失，不可能出现「上次的操作模式还挂着」这种状态。
 */
@Composable
private fun Sheets(vm: TimelineViewModel) {
    when (val s = vm.sheet) {
        Sheet.None -> Unit

        is Sheet.Picker -> PickerHost(
            title = when (s.mode) {
                PickerMode.StartNew -> "选一件事开始记"
                PickerMode.ChangeItem -> "换成哪件事？"
                PickerMode.FillGap -> "补记成哪件事？"
            },
            subtitle = when (s.mode) {
                PickerMode.StartNew -> s.target?.let { "选一个事项 —— 替换「${it.name}」，时间不变" }
                    ?: "现在这一刻开始记它，前一件会在此刻结束"

                PickerMode.ChangeItem -> "只换事项，时间一点不动"
                PickerMode.FillGap -> "时间段不变，只把内容填上"
            },
            categories = vm.categories,
            items = vm.items,
            onPick = { vm.pick(it) },
            onDismiss = { vm.closeSheets() },
            // ★ 模板入口**只在「结束/开始」这一个用途下**露出来。
            //   换个事项 / 补记一件事时把它摆旁边，等于在一个已经能完成的任务旁
            //   又开一条岔路 —— 人还得先想「这俩有啥区别」。
            templateOffer = s.mode == PickerMode.StartNew && vm.isToday,
            templateOn = vm.tpl.on,
            templateName = vm.tpl.name,
            onTemplateClick = { vm.startTemplate() },
        )

        is Sheet.Menu -> {
            // 面板可能开着的时候后台刷新过（每分钟一次），所以拿最新的那一版来显示。
            // 找不到（比如那条记录刚被删掉）就用打开时的那份 —— 它不会崩，只是略旧。
            val fresh = vm.rows.firstOrNull { it.id == s.row.id } ?: s.row
            val index = vm.rows.indexOfFirst { it.id == fresh.id }
            RecordActionSheet(
                row = fresh,
                // rows 是倒序（越晚越靠上），所以「上面」= index - 1。
                // 这个方向跟仓库里 `index - dir` 的算法是同一个意思，两处必须一致。
                neighborAbove = if (index > 0) vm.rows[index - 1].name else null,
                neighborBelow = if (index >= 0 && index < vm.rows.size - 1) {
                    vm.rows[index + 1].name
                } else {
                    null
                },
                onDismiss = { vm.closeSheets() },
                onFillGap = { vm.startFillGap(fresh) },
                onEndAndPick = { vm.openPickerForNow() },
                onChangeItem = { vm.startChangeItem(fresh) },
                onEditTime = { vm.openTimeEdit(fresh) },
                onEditContent = { vm.openContentEdit(fresh) },
                onMerge = { vm.mergeWith(fresh, it) },
                onSplit = { vm.split(fresh) },
                onMergeGapInto = { vm.mergeGapInto(fresh, it) },
                onDelete = { vm.delete(fresh) },
            )
        }

        is Sheet.TimeEdit -> vm.timeEdit?.let { state ->
            TimeEditSheet(
                state = state,
                onNudge = { which, delta -> vm.nudgeTime(which, delta) },
                onDismiss = { vm.closeSheets() },
                onSave = { vm.saveTime() },
            )
        }

        is Sheet.ContentEdit -> ContentEditSheet(
            row = s.row,
            buckets = vm.buckets,
            allTags = vm.allTags,
            onDismiss = { vm.closeSheets() },
            onSave = { note, tagIds -> vm.saveContent(s.row, note, tagIds) },
        )

        // 退出模板的确认。
        // ⚠️ 另一个退出入口（点结束 + 亲手选别的事项）**不弹窗** ——
        //    那个动作本身就是答案，再问一句就是白多一次打断。
        //    这里要问，是因为「点横条」只表达了「点了一下」，没说清要干什么。
        Sheet.TplExit -> ConfirmDialog(
            title = "退出模板记录？",
            text = "已经按模板记下的那些会留着 —— 之后的时间你自己记。",
            confirmLabel = "退出模板记录",
            onDismiss = { vm.closeSheets() },
            onConfirm = { vm.stopTemplate() },
        )

        // 粘贴 = **覆盖整天**（御坂拍板的语义）。
        // ⚠️ 这一句确认不能省：删一条记录错了还有 4 秒撤销条兜着，
        //    覆盖一整天是几十段一起没，先问清「会清掉多少」才对得起这个动作。
        is Sheet.PasteConfirm -> ConfirmDialog(
            title = "覆盖这一整天？",
            text = buildString {
                // ⚠️ 这里用 `displayFull`（带年份）而不是 `display`：
                //    跨年粘贴时「10月1日」在两个年份里都存在，只写月日等于没说清盖的是哪一天。
                //    这是不可逆操作的最后一道文字提示，宁可啰嗦。
                append("把 ${DayKey.displayFull(s.clip.from)} 的 ${s.clip.count} 段，")
                append("盖到 ${DayKey.displayFull(s.target)} 上。\n\n")
                append("这一天现有的 ${s.existing} 段会全部被清掉。")
                if (s.target == vm.todayKey) {
                    append("\n今天只贴到此刻为止，之后的时间仍然是空白。")
                }
                append("\n\n做完有 4 秒可以撤销。")
            },
            confirmLabel = "覆盖",
            onDismiss = { vm.closeSheets() },
            onConfirm = { vm.doPaste() },
        )
    }
}

// ============================================================ 顶部：日期 + 统计

/**
 * 顶部那块。
 *
 * 左边是「这是哪一天 + 这一天记得怎么样」，右边是翻天的三个按钮。
 *
 * ⚠️ **按钮位置固定、永不自动重排**（这是御坂强调过三次的铁律）：
 * 所以右箭头在最右、今天在中间、左箭头在左 —— 这个顺序不因任何状态改变，
 * 右箭头不能翻的时候只是变灰，**不会消失、不会挪位置**。
 * 位置一旦会动，肌肉记忆就废了。
 */
@Composable
private fun TimelineHead(vm: TimelineViewModel) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // ★ 点这一整块（日期 + 那个小箭头）= 弹月历。
                    //   御坂的原话：「没法像打开日历一样选中几个月之前的某一天，
                    //   只能不断点今天左边那个箭头」。入口必须明显到不用猜，
                    //   所以后面挂了一个 ▾ —— 光靠「日期文字能点」没人会去试。
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.openCalendar() }
                        .padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    Text(
                        text = DayKey.display(vm.viewing),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "选日期",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ⚠️ 这里原来挂着一个「今天」小标签，加了 ⋮ 之后**被挤成了零宽** ——
                    //   只剩一颗圆角背景，看着像标题旁边凭空多了一条蓝竖线（不报错、不崩）。
                    //   去掉它不影响信息：右边那颗「今天」按钮本来就在说同一件事，
                    //   而且你在今天时它是灰的（`enabled = !vm.isToday`）——
                    //   反倒是更可靠的身份提示。
                }
                Spacer(Modifier.height(4.dp))
                StatLine(vm)
            }

            // 翻天：‹ 今天 › —— 三个位置都固定
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.goPrev() }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天")
                }
                TextButton(
                    onClick = { vm.goToday() },
                    enabled = !vm.isToday,
                ) {
                    Text("今天")
                }
                IconButton(onClick = { vm.goNext() }, enabled = vm.canGoNext) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "后一天",
                        // 灰掉但**留在原地**（只是变暗，不是消失）
                        tint = if (vm.canGoNext) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                        },
                    )
                }

                // ★ 更多菜单（⋮）—— 复制 / 粘贴整天。
                //   位置是御坂指定的：「在今天右侧那个箭头的右边」。
                //   ⚠️ 它排在最后，所以前面三个按钮的位置一个都没动 ——
                //      按「按钮位置固定、永不自动重排」那条铁律，新东西只能往后加。
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多：复制或粘贴这一整天",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("复制这一天") },
                            onClick = {
                                menuOpen = false
                                vm.copyDay()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                // 把「复制的是哪天」写在菜单里 —— 隔了几分钟再回来，
                                // 不写的话根本想不起剪贴板里是哪一天
                                val c = vm.clip
                                Text(
                                    if (c == null) "粘贴到这一天"
                                    else "粘贴「${DayKey.display(c.from)}」到这里"
                                )
                            },
                            enabled = vm.canPaste,
                            onClick = {
                                menuOpen = false
                                vm.askPaste()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「已记录 3 时 50 分 · 未记录 0 分」这一行。
 *
 * ⚠️ 口径来自原型：一整天的「已记录 + 未记录」**恒等于**从 0 到此刻的分钟数。
 * 这两个数字加起来对不上，就说明密铺破了 —— 所以它们不只给用户看，也是自检。
 */
@Composable
private fun StatLine(vm: TimelineViewModel) {
    val complete = vm.gapMin == 0 && vm.recordedMin > 0

    Row {
        Text(
            text = "已记录 ${DayKey.durationSpaced(vm.recordedMin)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = " · ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (complete) {
            // 没有未记录 = 一整天都记满了。这是值得表扬的状态，给个绿字。
            Text(
                text = "记录完整",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = CompleteGreen,
            )
        } else {
            Text(
                text = "未记录 ${DayKey.durationSpaced(vm.gapMin)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = GapAmber,
            )
        }
    }
}

// ============================================================ 周条

/**
 * 一周七格，周一起。
 *
 * ⚠️ 它会**自动跟着正在看的那一天走**（翻到上周任何一天，周条就切到上周）。
 * 这不是额外状态，是 [DayKey.weekOf] 直接算出来的 —— 所以不存在「忘了同步」。
 *
 * 小红点 = 这天有「未记录」。它的作用是让你一眼看出**哪几天是残缺的**，
 * 好回去补记。这正是时间日志能长期记下去的关键。
 */
@Composable
private fun WeekBar(vm: TimelineViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        vm.week.forEach { cell ->
            WeekCellView(cell = cell, onClick = { vm.go(cell.key) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WeekCellView(cell: WeekCell, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 未来的日子不能点 —— 那里既没有记录也无法补记，点过去只会以为「数据丢了」
    val clickable = !cell.isFuture

    val background = when {
        cell.isViewing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        cell.isViewing -> MaterialTheme.colorScheme.onPrimary
        cell.isFuture -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = if (cell.isViewing) background else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(11.dp),
            )
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = cell.weekdayLabel,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !cell.hasGap -> Color.Transparent
                        cell.isViewing -> MaterialTheme.colorScheme.onPrimary
                        else -> GapAmber
                    }
                )
        )
    }
}

// ============================================================ 记录列表

/**
 * 记录卡列表 —— **倒序**，越晚的越靠上。
 *
 * 为什么倒序：你打开时间线最常想确认的是「我现在在干什么、刚干了什么」，
 * 所以最新的那一条必须落在拇指最容易够到的地方。
 */
@Composable
private fun RecordList(rows: List<RecordRow>, onOpen: (RecordRow) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = rows, key = { it.id }) { row ->
            RecordCard(row = row, onClick = { onOpen(row) })
        }
    }
}

/**
 * 一张记录卡。
 *
 * 信息层次（从最显眼到最次要）：
 * 左色条（哪一类）→ 名字 → 时长 → 时间范围 → 标签 → 备注。
 * 空白段是同一套骨架，只是**整体调灰**：它要看得见，但不该抢戏。
 *
 * ★ 整卡可点 —— 点开是它自己的操作菜单（改时间 / 换事项 / 加标签 / 合并 / 删除）。
 */
@Composable
private fun RecordCard(row: RecordRow, onClick: () -> Unit) {
    val accent = toColor(row.colorHex)

    val container = if (row.isGap) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    val nameColor = if (row.isGap) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min) // 让左侧色条能撑到整卡高度
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
    ) {
        // 左侧色条 —— 一整天扫下来，颜色就是「这一天都花在哪了」的缩略图
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(accent)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.isLive) {
                    // 进行中的那个小圆点 —— 一眼看出「这条还在长」
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (row.isGap) {
                    // 空白段前面的小标记。⚠️ 自绘的，不用字符 ——
                    // 「▨」那个字符安卓字体里没有，真机上是个豆腐块（踩过）
                    GapMark(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (row.isGap) FontWeight.Normal else FontWeight.SemiBold,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.durationLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                )
            }

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${row.startLabel} → ${row.endLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 模板铺的要说出来，不然记录自己冒出来会以为见鬼了
                if (row.fromTemplate) {
                    Text(
                        text = " · 模板",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (row.categoryName != null) {
                    Text(
                        text = " · ${row.categoryName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (row.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.tags.forEach { chip ->
                        TagPill(chip.name, chip.colorHex)
                    }
                }
            }

            if (row.note.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = row.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 空白段前面那个小标记 —— 方框 + 右上半个填满。
 *
 * 这就是原型里那个 `▨` 的形状。
 *
 * ## ⚠️ 为什么是自绘，而不是直接打一个 `▨` 字符
 *
 * `▨` 是 U+25A8，**安卓系统字体普遍没有这个字形**（它不在 WGL4 字符集里），
 * 真机上直接渲染成一个空心豆腐块 —— 已经在真机上撞见过一次。
 * 而且字体回退是谁也说不准的事，今天能显示、换个 ROM 就未必。
 *
 * 自绘不但不吃字体，形状还完全可控。代价只有十几行。
 */
@Composable
private fun GapMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(12.dp)) {
        val w = size.width
        val h = size.height

        // 右上那一半填满 —— 这是「半填充方块」的关键一笔
        val half = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, h)
            close()
        }
        drawPath(path = half, color = color)

        // 再描个外框，让它看起来是个「方块」而不是一团色块
        drawRect(color = color, style = Stroke(width = 1.4.dp.toPx()))
    }
}

/** 卡片上那颗标签。颜色是它自己的 —— 一眼能认出是哪一类标记。 */
@Composable
private fun TagPill(name: String, colorHex: String) {
    val color = toColor(colorHex)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

// ============================================================ 模板运行横条

/**
 * 时间线顶部那根「模板运行中 · 工作日」。
 *
 * ## 为什么必须有这么一根东西
 *
 * 因为**记录会自己往外长**。
 *
 * 你切到模板模式之后，什么都不用做，时间线上就会一段一段地冒出「打工」「午休」。
 * 如果界面上不给一个显眼的说法，这件事只有两种解释：**见鬼了**，或者
 * **「我明明没记，它凭什么算我记过」**。两种都会让人不敢用这个功能。
 *
 * 所以这根横条不是装饰，它是**「这些记录是谁写的」的署名**。
 *
 * ## 点它 = 退出
 *
 * 退出模板记录有两个入口，各有各的道理：
 * - **点这根横条** → 弹一句确认。因为「点横条」只表达了「我点了一下」，
 *   没说清要干什么，得问一句。
 * - **点「结束」并亲手选别的事项** → **不弹窗**。那个动作本身就是答案，
 *   再问一句就是白多一次打断（正常记笔记的一天要这么做十几次）。
 *
 * ## ★ 两种状态，颜色不一样（M2-D）
 *
 * | 今天 | 长什么样 | 意思 |
 * |---|---|---|
 * | 排进了模板（工作日） | 绿底 ·「模板运行中 · 工作日」 | 空白会被自己填上 |
 * | 没排进（周六周日） | 灰底 ·「模板开着 · 今天不在…里，不自动记」 | **只是不干活，开关还开着** |
 *
 * ⚠️ 灰的那一根**不能省**。省掉的话周一你打开 App 会以为「模板怎么没了」，
 * 然后重新去切一次 —— 而它其实一直在等着。
 */
@Composable
private fun TplRunningBar(name: String, covered: Boolean, onClick: () -> Unit) {
    val bg = if (covered) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (covered) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Autorenew,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (covered) {
                "模板运行中 · ${name.ifEmpty { "—" }}"
            } else {
                "模板开着 · 今天不在「${name.ifEmpty { "—" }}」里，不自动记"
            },
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "点此退出",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

// ============================================================ 底部状态条 / 撤销 / 提示

/**
 * 底部状态条。
 *
 * ### 今天
 * 显示此刻正在记的那件事 + 已持续多久。没有在记就老实说「空闲中」，
 * **不要挂个 00:00 在那儿跑** —— 那个数字是假的，会让人以为在计时。
 *
 * ### 别的日子
 * ⚠️ 绝不能还挂着「今天正在进行的那件事」——那条记录压根不属于你正看的这一天。
 * 这里换成**这一天的统计**，把日期写全（翻到上个月时，光看「12日」不知道是哪个月）。
 *
 * ⚠️ 右侧那个按钮**位置固定**（永远在同一个地方），但**文字会变**：
 * 今天在计时 =「■ 结束」、今天空闲 =「▶ 开始」、别的日子 =「↩ 回到今天」。
 * 「位置固定」是铁律，「文字固定」不是 —— 原型自己就写了这句话。
 */
@Composable
private fun DockBar(vm: TimelineViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (vm.isToday) {
                    val live = vm.liveRow
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (live != null) toColor(live.colorHex)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = live?.name ?: "空闲中",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (live != null) {
                            "已持续 ${live.durationLabel} · ${live.startLabel} 开始"
                        } else {
                            "未在记录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ★ 模板那一行（原型 `#dock-tpl`）。
                    //   ⚠️ 只在**看今天**、且**这个分支里**显示 —— 原型的教训是：
                    //      翻到别的日子时底部必须讲【这一天】的事，
                    //      挂着「模板：运行中」会让人以为昨天也在被自动填。
                    //   ★ 三种说法（M2-D）：没启用 / 运行中 / **开着但今天不铺**。
                    //      第三种（休息日）不能并进「未启用」—— 那会让你以为模板被关了。
                    if (vm.tpl.exists) {
                        val active = vm.tpl.on && vm.tpl.coveredToday
                        Text(
                            text = when {
                                !vm.tpl.on -> "模板：未启用"
                                active -> "模板：运行中 · ${vm.tpl.name}"
                                else -> "模板：开着 · 今天不铺"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                } else {
                    Text(
                        text = DayKey.displayFull(vm.viewing),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    StatLine(vm)
                }
            }

            Spacer(Modifier.width(12.dp))

            DockButton(vm)
        }
    }
}

/**
 * 底部那个按钮 —— **永远是同一个位置、同一个大小**。
 *
 * 用固定宽度（88dp）而不是让文字撑开：这样「开始 / 结束 / 回到今天」
 * 三种文字换来换去时，按钮的边界一点都不动。它旁边那行文字的长度会变，
 * 如果按钮跟着弹性伸缩，拇指每次都得重新找。
 */
@Composable
private fun DockButton(vm: TimelineViewModel) {
    // 图标也用矢量的 —— 按「开始/结束/回到今天」三种状态换，但**位置和大小一个像素都不动**。
    val (glyph, label, action) = when {
        !vm.isToday -> Triple(Icons.Filled.Undo, "回到今天", { vm.goToday() })
        vm.liveRow != null -> Triple(Icons.Filled.Stop, "结束", { vm.openPickerForNow() })
        else -> Triple(Icons.Filled.PlayArrow, "开始", { vm.openPickerForNow() })
    }

    // 只有「结束」是强调色 —— 它是这一天里最常按的那个。
    // 「开始」和「回到今天」用中性色，因为它们一个是状态提示、一个是导航。
    val emphasized = vm.isToday && vm.liveRow != null
    val bg = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (emphasized) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .widthIn(min = 88.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(onClick = action)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

/**
 * 撤销条 —— 删完那 4 秒里的后悔药。
 *
 * 为什么值得专门做一块 UI：**「删除」不弹「你确定吗」**这条规矩，
 * 全靠它兜底。没有撤销条的话，不弹确认就成了鲁莽；
 * 有了它，删除才能做到「快、且不怕」。
 */
@Composable
private fun UndoBar(state: UndoState, onUndo: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.token.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) {
                Text(
                    text = "撤销",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}

/** 普通的提示条（跟撤销条同一个位置，只是没有按钮）。 */
@Composable
private fun TipBar(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ============================================================ 空态与加载

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * 空列表的说明。
 *
 * ⚠️ 只在一种情况下出现：**今天刚开始**（0 点刚过那几分钟）。
 * 别的时候不该空 —— 过去的一天会被补密铺满一整天，今天会被铺到此刻。
 * 所以如果翻到过去的某天看到这里，那不是「没数据」，是密铺出问题了。
 */
@Composable
private fun EmptyHint(vm: TimelineViewModel) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (vm.isToday) "今天才刚开始，还没有记录" else "这一天还没有记录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「记录完整」的绿 —— 跟未记录的琥珀色成对，看的是颜色对比。 */
private val CompleteGreen = Color(0xFF2FB3A6)

/** 「未记录」的琥珀 —— 跟原型一致（#E8A33D）。它要显眼但不刺眼。 */
private val GapAmber = Color(0xFFE8A33D)
