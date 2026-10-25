package com.misaka.timelog.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.TemplateEdit
import com.misaka.timelog.ui.TemplateViewModel
import com.misaka.timelog.ui.TemplateViewModel.SegUi
import com.misaka.timelog.domain.MinuteHit
import com.misaka.timelog.domain.SegGeometry
import com.misaka.timelog.ui.component.ActionItem
import com.misaka.timelog.ui.component.MessageHost
import com.misaka.timelog.ui.component.Sep
import com.misaka.timelog.ui.component.inkFor
import com.misaka.timelog.ui.component.toColor
import com.misaka.timelog.ui.sheet.PickerHost

/**
 * 生活规律模板的配置页。
 *
 * ## 一页装完（原型第 17 轮定）
 *
 * 原型的结构本来是「列表页（两张卡：工作日 / 周末）→ 编辑页」两级。
 * 后来合并成一页，因为 **V1 只有一套模板** —— 为一张卡专门做一层导航，
 * 进去之后发现「就一个东西」，那层导航纯属白走。
 *
 * ## 色条为什么是 480dp 高
 *
 * **1 小时 = 20dp**，24 小时正好 480dp。这个比例是从原型调出来的：
 * 早期用 26dp/小时（624dp），一屏装不下色条 + 星期指派，得来回滚。
 *
 * 一屏能装下的意义不只是好看 —— 你调段边界的时候，眼睛得同时看着
 * **上面那段的终点**和**下面那段的起点**，滚来滚去根本对不准。
 *
 * ## ⚠️ 超短的段会在色条上视觉重叠（已知取舍，跟原型一致）
 *
 * 段的最小显示高度是 15dp（见 [SegBlock]）—— 不这么做的话，一个 10 分钟的段
 * 只有 3dp 高，**点都点不到**，而它又必须能被点到（否则你没法删它、也没法改它）。
 *
 * 代价是：相邻两段都短于 45 分钟时，它们画出来的高度会超过实际位置，**互相叠一点**。
 * 原型就是这个行为，这里照搬 —— 因为「点得到」比「比例绝对精确」重要得多。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(
    vm: TemplateViewModel,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageHost(vm.message, snackbar)

    // ★ 每次**进入**这一页都重读一次库。
    //
    //   ViewModel 是 Activity 级的（设置首页要拿它的摘要），所以第二次点进模板页时
    //   它还是上一次那个实例、状态也是旧的。而中间这段时间你完全可能
    //   在「事项」页删过一个事项（那会让某一段变成悬空）。
    //   ⚠️ 这里读的是纯数据，没有副作用，重复读一次没有代价。
    LaunchedEffect(Unit) { vm.refresh() }

    // 三个瞬时面板。同一时刻只会有一个。
    var menuIndex by remember { mutableStateOf<Int?>(null) }   // 点了一段 → 操作菜单
    var timeIndex by remember { mutableStateOf<Int?>(null) }   // 改起止时间
    var pickIndex by remember { mutableStateOf<Int?>(null) }   // 改事项（复用 6 列网格）

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生活规律模板") },
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
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            StatePanel(vm)

            Spacer(Modifier.height(18.dp))
            HourBar(segs = vm.segs, onSegClick = { menuIndex = it })

            Spacer(Modifier.height(22.dp))
            WeekdayPicker(mask = vm.weekdaysMask, onToggle = vm::toggleWeekday)

            Spacer(Modifier.height(22.dp))
            UsageNote()

            Spacer(Modifier.height(40.dp))
        }
    }

    // ---------------------------------------------------------- 段操作菜单

    // ⚠️ 每次都从 vm.segs 现取，不缓存 —— 段可能刚被改过（挪了边界、被删了）
    val menuSeg = menuIndex?.let { vm.segs.getOrNull(it) }
    if (menuSeg != null) {
        SegMenuSheet(
            seg = menuSeg,
            onlyOneLeft = vm.segs.size <= 1,
            onDismiss = { menuIndex = null },
            onChangeItem = {
                pickIndex = menuSeg.index
                menuIndex = null
            },
            onEditTime = {
                timeIndex = menuSeg.index
                menuIndex = null
            },
            onSplit = {
                vm.splitSeg(menuSeg.index)
                menuIndex = null
            },
            onDelete = {
                vm.deleteSeg(menuSeg.index)
                menuIndex = null
            },
        )
    }

    // ---------------------------------------------------------- 改起止时间

    val timeSeg = timeIndex?.let { vm.segs.getOrNull(it) }
    if (timeSeg != null) {
        TimeEditSheet(
            seg = timeSeg,
            templateName = vm.name,
            firstSeg = timeSeg.index == 0,
            lastSeg = timeSeg.index == vm.segs.lastIndex,
            onShift = { edge, delta -> vm.moveEdge(timeSeg.index, edge, delta) },
            onDismiss = { timeIndex = null },
        )
    }

    // ---------------------------------------------------------- 改事项
    //
    // ★ 复用时间线那套 6 列彩色网格，一个字都不用改 ——
    //   「改成别的事项」这个动作在时间线和模板页是同一件事，
    //   做成两个长得不一样的面板只会让你每换一个入口重新认一次界面。

    val pickSeg = pickIndex?.let { vm.segs.getOrNull(it) }
    if (pickSeg != null) {
        PickerHost(
            title = "这一段记什么",
            subtitle = "${pickSeg.startLabel} → ${pickSeg.endLabel} · ${pickSeg.durationLabel}" +
                " · 只换内容，时间一点不动",
            categories = vm.categories,
            items = vm.items,
            onPick = { itemId ->
                vm.setItem(pickSeg.index, itemId)
                pickIndex = null
            },
            onDismiss = { pickIndex = null },
        )
    }
}

// ============================================================================
// 状态行
// ============================================================================

/**
 * 顶部那块「现在是什么状况」。
 *
 * ## 为什么会写三行字
 *
 * 因为这里有**三件互相独立**的事，任何一件不说清都会让你怀疑是不是坏了：
 *
 * 1. **配了几段、指派了哪几天** —— 改完的反馈
 * 2. **此刻在不在跑** —— 不写的话，改完模板看到时间线没变化会以为没生效
 * 3. **有没有悬空段 / 密铺坏了** —— 这两种都是"安静的故障"，必须主动喊
 */
@Composable
private fun StatePanel(vm: TemplateViewModel) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "${vm.segs.size} 段 · 指派 ${vm.weekdayLabel}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(5.dp))
        Text(
            text = runStateLine(vm),
            style = MaterialTheme.typography.bodySmall,
            color = if (vm.runOn) scheme.primary else scheme.onSurfaceVariant,
        )

        if (vm.orphanCount > 0) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = "有 ${vm.orphanCount} 段的事项已经被删掉了 —— 点那一段重选一个",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }

        // ⚠️ 正常情况下这一行**永远不该出现**。它出现就说明密铺破了 ——
        //    而密铺破了之后模板会从那道缝开始静静地不再铺写，不报错、不崩。
        //    写入口硬拦 + 这里显示，是同一件事的两道保险。
        if (vm.issue.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = vm.issue,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }
    }
}

/** 运行态那句话 —— **三种**，不是两种（M2-D 加的那一种）。 */
private fun runStateLine(vm: TemplateViewModel): String = when {
    !vm.runOn -> "未启动 —— 到时间线点「结束」，在面板左下角选「切到模板模式」"
    !vm.coveredToday -> "模板开着，但今天不在指派的日子 —— 今天不铺，只记空白"
    else -> "正在按模板记录 · 点时间线上那条绿横条可以退出"
}

// ============================================================================
// 24 小时色条
// ============================================================================

/**
 * 1 小时 = 30dp。改这个数，色条、刻度、段文字阈值会一起跟着变。
 *
 * ## 为什么从 20 提到 30（2026-10-18，御坂反馈「十几分钟的模板太细了，我点不到」）
 *
 * 20dp 时 10 分钟的段只有 3.3dp，一根肉眼几乎看不见的线，手指更不可能瞄得准。
 * 提到 30dp 后 24 小时 = 720dp，10 分钟的段有 5dp、30 分钟有 15dp、
 * 1 小时有 30dp。页面本来就能滚，代价只是多点一次滚动。
 */
private const val HOUR_DP = 30f

/** 每分钟多少 dp。 */
private const val MIN_DP = HOUR_DP / 60f

/** 整根色条的高度：24 小时 × 30dp = 720dp。 */
private const val BAR_DP = 24 * HOUR_DP

/**
 * 段的最小**可见**高度不再在这里定义 —— 它是 [SegGeometry.VISIBLE_MIN_DP]。
 *
 * ## ★ 这里曾经是 22f，然后被撤掉了（2026-10-18 装机验出问题）
 *
 * 当时的想法是「把细段抬到 22dp，手指就有 22dp 的目标」。实测下来是错的：
 * 御坂的模板里有 18:45→19:07（22 分钟，真实高 11dp）和 19:07→19:30（23 分钟，11.5dp）
 * 两段紧挨着，各自被抬到 22dp 之后**互相重叠 11dp**，两行名字直接叠在一起糊成一团。
 *
 * 更根本的问题是：**抬高会让「看到的」和「点到的」脱节**。段画得比真时间长，
 * 就是在图上撒谎；而时间轴图表撒谎 = 这个 App 的地基（「缝隙可见」）塌了。
 *
 * 那「点不到」怎么解决？**点击压根不该靠段自己的高度。** 见 [HourBar]：
 * 整条色条接管点击，按 y 坐标反算分钟、再找覆盖这一分钟的段，任何一点都命中唯一一段。
 * 所以段只需要「看得见」，不需要「够大」。
 *
 * 这个值放在 domain 里，是为了让几何断言能跟着一起动 —— 见 [SegGeometry]。
 */
private val MIN_VISIBLE_DP = SegGeometry.VISIBLE_MIN_DP

/**
 * 显示高度到多少才值得写名字。
 *
 * 10dp 是「一行 9.5sp 字勉强放得下」的临界值 —— 再矮就会溢出色块太多。
 * 定在 10 而不是 14，是为了让 22 分钟这种段（30dp/小时制下 10dp）仍然有名字。
 */
private const val NAME_ONLY_DP = 10f

/** 低于这个高度算「细段」，给它描一圈边，让它在满屏色块里能被认出来。 */
private const val THIN_SEG_DP = 8f

/** 刻度列要多留一点 —— 「24:00」那个标签画在 720dp 处，得给它一行字的地方。 */
private const val SCALE_DP = BAR_DP + 14f

/** 悬空段的灰色。跟原型一致。 */
private val OrphanGray = Color(0xFF8B93A5)

@Composable
private fun HourBar(segs: List<SegUi>, onSegClick: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // --- 左边的时间刻度：每 2 小时一个标签 ---
        //
        // 用 offset 而不是 Column 排（Row/Column 里放不下「半分格」的偏移）——
        // offset 只影响画在哪，不影响布局尺寸，所以这些标签不会把父容器撑大。
        Box(modifier = Modifier.width(36.dp).height(SCALE_DP.dp)) {
            var hh = 0
            while (hh <= 24) {
                Text(
                    text = if (hh == 24) "24:00" else "%02d:00".format(hh),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = (hh * HOUR_DP).dp),
                )
                hh += 2
            }
        }

        Spacer(Modifier.width(6.dp))

        // --- 右边：段的色块 ---
        //
        // ★ 点击由这个 Box 统一接管，**不交给每个段自己**。
        //
        // 为什么：模板段最小只有几分钟（御坂的模板里有一段 12:55→13:02，7 分钟，
        // 在 30dp/小时制下只有 3.5dp 高）。这么细的条子手指不可能精准命中，
        // 而如果把每段各自做成 clickable，命中判定就退化成「按矩形算」——
        // 手指一歪就落到隔壁段，用户点「13:51 那根粉条」却进了上一段的面板。
        //
        // 改成按 y 坐标反算分钟，再找**覆盖这一分钟的段**：模板段首尾相接覆盖
        // 0→1440，所以任何一点都必然命中唯一一段，永远不会落空、也不会错位。
        // 这也正是「段不用为了好点而画高」的前提 —— 见 [MIN_VISIBLE_DP] 的注释。
        Box(
            modifier = Modifier
                .weight(1f)
                .height(BAR_DP.dp)
                .pointerInput(segs) {
                    detectTapGestures { offset ->
                        if (segs.isEmpty()) return@detectTapGestures
                        val minute = MinuteHit.minuteAt(offset.y / density, MIN_DP)
                        MinuteHit.pick(segs, minute, { it.startMin }, { it.endMin })
                            ?.let { onSegClick(it.index) }
                    }
                },
        ) {
            segs.forEach { seg -> SegBlock(seg = seg) }
        }
    }
}

/**
 * 色条上的一段。
 *
 * ## 高度一律照实画
 *
 * `heightDp` = 这段真实占的时间 × [MIN_DP]，只对「不到 2dp 的极小段」兜个底。
 * **绝不为了好点而放大** —— 放大必然盖住邻居，进而「点到的不是看到的」。
 * 好点这件事由 [HourBar] 的整条接管 + 按分钟反查负责，跟段的高度无关。
 *
 * ## 段里什么时候写字
 *
 * - 高度 ≥ [NAME_ONLY_DP]（10dp）→ 写名字
 * - **时长满 60 分钟** → 再加一行时间段
 *
 * ⚠️ 「满 1 小时」用**时长**判，不用显示高度判。踩过一次：写 `heightDp >= HOUR_DP`
 * 时，60 分钟的段算出来是 `60×0.5-1 = 29dp`（那 1dp 是段间缝），
 * 29 < 30 → 「做饭」「玩手机」这种整点一小时的事项**不显示时间**，
 * 而 249 分钟的「打工」显示 —— 看起来像随机漏了几个，实际是差 1dp。
 *
 * 10dp 以下不写字：那几个段之间只隔几分钟，硬塞名字必然和邻居的行重叠
 * （22 分钟和 23 分钟挨着的时候尤其明显）。想知道那根细线是什么，
 * 点一下就出面板，面板标题就写着事项名和时间段。
 */
@Composable
private fun SegBlock(seg: SegUi) {
    val bg = seg.colorHex?.let { toColor(it) } ?: OrphanGray
    val ink = inkFor(bg)

    val topDp = seg.startMin * MIN_DP
    // 高度 = 真实时间 × 每分钟 dp，只兜「有像素」这个底。
    // 段之间的那道缝和下限都在 SegGeometry 里，那边有几何断言锁着（见它的注释）。
    val heightDp = SegGeometry.heightDp(seg.durationMin, MIN_DP, MIN_VISIBLE_DP)

    // 细段描边：满屏色块里，2dp 的一条线很容易被当成「两段的缝」而忽略掉。
    // 描边把它和缝区分开 —— 有边 = 这是一个真实存在的段。
    val thin = heightDp < THIN_SEG_DP

    // 满 1 小时 → 名字 + 时间两行。按时长判，不按高度判（见 SegGeometry 的注释）。
    val full = SegGeometry.showsTimeLine(seg.durationMin)

    Box(
        modifier = Modifier
            .offset(y = topDp.dp)
            .fillMaxWidth()
            .height(heightDp.dp)
            // background(color, shape) 而不是 clip + background：clip 会把子元素一起裁掉，
            // 10dp 的段放 11sp 行高时字会被切掉上下各半个像素。
            // 这样只让**背景**按圆角画，文字允许溢出一点点，反而更清楚。
            .background(bg, RoundedCornerShape(5.dp))
            .then(
                if (thin) {
                    Modifier.border(
                        width = 1.dp,
                        color = ink.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(5.dp),
                    )
                } else {
                    Modifier
                }
            ),
        // ⚠️ 这里**故意没有 clickable**：细段和邻居在视觉上挨得极近，
        //    每个段各自可点会退化成「按矩形命中」，手指一歪就落到隔壁。
        //    点击统一由 HourBar 按分钟反算（见那里的注释）。
        contentAlignment = Alignment.Center,
    ) {
        if (heightDp >= NAME_ONLY_DP) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = seg.itemName,
                    // 三档字号：两行的 12sp、能用 10sp 的、只剩 10dp 高的挤到 9.5sp
                    fontSize = when {
                        full -> 12.sp
                        heightDp >= 14f -> 10.sp
                        else -> 9.5.sp
                    },
                    lineHeight = when {
                        full -> 14.sp
                        heightDp >= 14f -> 12.sp
                        else -> 10.sp
                    },
                    fontWeight = FontWeight.Medium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (full) {
                    Text(
                        text = "${seg.startLabel} → ${seg.endLabel}",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = ink.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

// ============================================================================
// 星期指派
// ============================================================================

/**
 * 七个方块，点亮哪几天生效。
 *
 * 显示顺序**永远是「一 二 三 四 五 六 日」** —— 跟 `DayKey` 和位掩码的口径一致。
 * 不跟手机的区域设置走（某些地区是周日开头），因为你脑子里那一天就是「周末」，
 * 换个顺序会让「点亮周一到周五」这件事每次都要重新数。
 */
@Composable
private fun WeekdayPicker(mask: Int, onToggle: (Int) -> Unit) {
    Column {
        Text(
            text = "指派到哪几天",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "点亮的那些天模板才会自动记；一天都不点 = 模板搁着不跑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (0..6).forEach { i ->
                val on = (mask shr i) and 1 == 1
                DayCell(
                    label = TemplateEdit.weekdayCn(i),
                    on = on,
                    onClick = { onToggle(i) },
                    modifier = Modifier.weight(1f),
                )
                // ⚠️ 不用 `Arrangement.spacedBy` 之外的额外间隔：
                //    七个格子等宽 + 6 个缝，在 390dp 的手机上每格正好 ~44dp，
                //    是手能轻松点到的尺寸。再多加间距就会把格子压到 40dp 以下。
            }
        }
    }
}

@Composable
private fun DayCell(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================================
// 用法说明
// ============================================================================

@Composable
private fun UsageNote() {
    Column {
        Text(
            text = "怎么用",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        listOf(
            "模板是「预定的一天」，必须严丝合缝铺满 00:00 → 24:00。" +
                "想留白就选一个「未记录」性质的事项，不要挖洞。",
            "点色条上任意一段：改成别的事项 / 改起止时间 / 拆成两段 / 删掉并入相邻。",
            "配好模板什么都不发生。要启用，到时间线点「结束」，" +
                "在面板左下角选「切到模板模式」。",
            "启用之后，模板只往空白里填 —— 你手动记过的一条都不动。",
        ).forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

// ============================================================================
// 段操作菜单
// ============================================================================

/**
 * 点一段之后弹出来的菜单。
 *
 * ## 四项，跟原型一字不差
 *
 * 这四项的顺序和文案是原型调过的：**改事项（最常用）在最上面、
 * 删除在最下面并标红**。而且副标题都在解释「会发生什么」而不是复述标题 ——
 * 比如「删掉这段（并入相邻）」下面写的是「时长交给邻居，模板不留洞」，
 * 因为「并入相邻」这四个字本身不告诉你邻居会怎么样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegMenuSheet(
    seg: SegUi,
    onlyOneLeft: Boolean,
    onDismiss: () -> Unit,
    onChangeItem: () -> Unit,
    onEditTime: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(bottom = 24.dp),
        ) {
            // 头部：你现在点的是哪一段
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(seg.colorHex?.let { toColor(it) } ?: OrphanGray)
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = seg.itemName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${seg.startLabel} → ${seg.endLabel} · ${seg.durationLabel}" +
                            if (seg.orphan) " · 这个事项已经没有了，点「改成别的事项」重选一个" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            ActionItem(
                icon = Icons.Filled.SwapHoriz,
                title = "改成别的事项",
                subtitle = "只换这一段记什么，起止时间一点不动",
                primary = seg.orphan,
                onClick = onChangeItem,
            )
            ActionItem(
                icon = Icons.Filled.Schedule,
                title = "改起止时间",
                subtitle = "挪边界时相邻那段自动伸缩 —— 模板永远严丝合缝",
                onClick = onEditTime,
            )
            ActionItem(
                icon = Icons.Filled.ContentCut,
                title = "拆成两段",
                subtitle = if (seg.durationMin < 2) {
                    "这一段太短了，拆不开"
                } else {
                    "从中间切开，拆出来的新段先沿用同一件事项"
                },
                enabled = seg.durationMin >= 2,
                onClick = onSplit,
            )
            Sep()
            ActionItem(
                icon = Icons.Filled.Delete,
                title = "删掉这段（并入相邻）",
                subtitle = if (onlyOneLeft) {
                    "只剩一段了，删了就凑不成一天"
                } else {
                    "时长交给邻居，模板不留洞"
                },
                enabled = !onlyOneLeft,
                danger = true,
                onClick = onDelete,
            )
        }
    }
}

// ============================================================================
// 改起止时间
// ============================================================================

/**
 * 挪边界。
 *
 * ## ±15 是主按钮，±5 是精调
 *
 * 这个分工是从原型搬来的：挪边界时你心里想的通常是「提前一刻钟」，
 * 所以 15 分钟是大按钮、会连点；而最后对齐到 08:30 这种整点差 5 分钟的情况，
 * 用下面那排小按钮补一下。
 *
 * ## ★ 每一按就落库，没有「保存」
 *
 * 色条实时跟着变，你看着满意就是最终结果。加一个保存按钮反而危险：
 * 忘了按就等于白改，而这里**没有第二次机会想起来**（模板页不是天天进的）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEditSheet(
    seg: SegUi,
    templateName: String,
    firstSeg: Boolean,
    lastSeg: Boolean,
    onShift: (TemplateEdit.Edge, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "改起止时间",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$templateName · ${seg.itemName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))

            EdgeRow(
                label = "开始",
                value = seg.startLabel,
                lockedNote = if (firstSeg) "第一段的起点永远是 00:00" else null,
                onShift = { onShift(TemplateEdit.Edge.Start, it) },
            )
            EdgeRow(
                label = "结束",
                value = seg.endLabel,
                lockedNote = if (lastSeg) "最后一段的终点永远是 24:00" else null,
                onShift = { onShift(TemplateEdit.Edge.End, it) },
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "这一段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DayKey.durationSpaced(seg.durationMin),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(10.dp))

            // 精调那排：±5 分钟，四个小按钮平分一行
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FineButton("起 -5", enabled = !firstSeg, modifier = Modifier.weight(1f)) {
                    onShift(TemplateEdit.Edge.Start, -5)
                }
                FineButton("起 +5", enabled = !firstSeg, modifier = Modifier.weight(1f)) {
                    onShift(TemplateEdit.Edge.Start, 5)
                }
                FineButton("止 -5", enabled = !lastSeg, modifier = Modifier.weight(1f)) {
                    onShift(TemplateEdit.Edge.End, -5)
                }
                FineButton("止 +5", enabled = !lastSeg, modifier = Modifier.weight(1f)) {
                    onShift(TemplateEdit.Edge.End, 5)
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "挪边界时相邻那段会自动伸缩 —— 模板永远严丝合缝，不会留洞、也不会重叠。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/**
 * 一条边界的调节行。
 *
 * ⚠️ 锚死的两端（第一段的起点、最后一段的终点）**按钮直接不显示**，
 * 而不是灰着 —— 灰着会让人反复去点、试图弄明白为什么不能动。
 * 换成一句「第一段的起点永远是 00:00」，看一眼就懂了。
 */
@Composable
private fun EdgeRow(
    label: String,
    value: String,
    lockedNote: String?,
    onShift: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (lockedNote == null) {
            OutlinedButton(
                onClick = { onShift(-15) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) { Text("-15") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onShift(15) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) { Text("+15") }
        } else {
            Text(
                text = lockedNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FineButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}
