package com.misaka.timelog.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.ui.PickerPrefs
import com.misaka.timelog.ui.component.inkFor
import com.misaka.timelog.ui.component.toColor

/**
 * 选一件事 —— 底部弹出一屏**六列彩色网格**。
 *
 * ## 这个面板一个屏幕里塞三件事
 *
 * 1. 结束现在这件事、开始记新的（底部那个「结束」按钮）
 * 2. 把某条记录**换成**另一件事（时间不变）
 * 3. 把某段「未记录」**补记**成一件事（时间不变）
 *
 * 三处的落地完全不同，但**选的动作一模一样**，所以共用这一张网格。
 * 不需要三个长得差不多的面板 —— 那会让用户每换一个入口就要重新认一次界面。
 *
 * ## 为什么没有分类筛选、没有搜索
 *
 * 这是原型里反复调过的一处（调过好几轮才定下来的）：
 *
 * > 一屏平铺 6 列，**颜色就是唯一的大类区分**。
 *
 * 加一个「全部/工作/生活」的筛选条，看着更专业，实际上会让每次记录从
 * 「一眼扫到、点一下」变成「先选类、再找、再点」—— 而记一笔这件事
 * 一天要做十几次，多出来的每一步都是成本。
 *
 * 搜索同理：事项是用户自己起的名字、一共几十个，扫一眼比打字快。
 *
 * ## 分组靠什么
 *
 * **同色聚成一块**（一个分类一块，块内是它的事项），而且**块间距明显大于格间距**
 * （12dp vs 4dp）—— 靠这个空当看出「这是两组」。空分类不占位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemPickerSheet(
    title: String,
    subtitle: String?,
    categories: List<CategoryEntity>,
    items: List<ItemEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 左下角那行模板入口要用的状态。
     *
     * ★ **只在「结束/开始」这个用途下显示**（`templateOffer = true`）——
     * 换个事项、补记一件事时把它摆在旁边，等于在一个已经能完成的任务旁边
     * 又开一条岔路，人还得先想「这俩有啥区别」。
     * 模板是「今天要不要交给它」的决定，那个时机就是**点结束的时候**。
     */
    templateOffer: Boolean = false,
    templateOn: Boolean = false,
    templateName: String = "",
    onTemplateClick: () -> Unit = {},
    /**
     * 聚合视图：**去掉分类标题**，只靠颜色分块，块间空隙收窄。
     *
     * 御坂的原话：「我们用颜色就已经区分得了事项属于哪个分类」。
     * 这个开关本身由宿主持久化（见 `PickerPrefs`），这里只负责画。
     */
    compact: Boolean = false,
    onToggleCompact: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 按分类分块：分类顺序决定块的顺序，分类内按 orderInCat 排。
    // 空分类**不占位置** —— 一个空的分类块会让网格中间裂开一道莫名其妙的缝。
    val blocks = remember(categories, items) {
        categories.mapNotNull { c ->
            val inCat = items.filter { it.categoryId == c.id }.sortedBy { it.orderInCat }
            if (inCat.isEmpty()) null else c to inCat
        }
    }

    // 聚合视图用：把块**拍平**成一串「事项 + 它那一类的颜色」。
    // 顺序沿用分类顺序 + 分类内 orderInCat，所以拍平后你看到的先后跟分组视图一致，
    // 只是不再有「一块一块」的断开。
    val flatCells = remember(blocks, categories) {
        val colorOf = categories.associate { it.id to toColor(it.colorHex) }
        blocks.flatMap { (_, inCat) ->
            // 分类查不到色（历史脏数据）→ 用跟 toColor 同一个兜底灰，
            // 别让它变成「透明格子」，那看起来像界面坏了
            inCat.map { it to (colorOf[it.categoryId] ?: Color(0xFF9E9E9E)) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // ⚠️ 按钮上写的是**点它会发生什么**（「聚合」= 变成聚合视图），
            //    不是「现在是什么」。跟左下角「切到模板模式」同一个说法。
            //    写成当前状态的话，人每次都要反问一句「那我现在是哪个」。
            LayoutToggle(compact = compact, onClick = onToggleCompact)
        }
        Spacer(Modifier.height(14.dp))

        if (blocks.isEmpty()) {
            // 一个事项都没有 —— 说清去哪儿建，别让人对着空面板发愣
            Text(
                text = "还没有事项。\n先去「设置 → 事项」建几个，再来记。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp, horizontal = 32.dp),
            )
        } else if (compact) {
            // ---- 聚合视图：所有事项**拍成一个连续流**，每行 6 个，不在分类边界断行 ----
            //
            // ⚠️ 2026-10-19 御坂纠正过一次：第一版做成「去掉标题但保留块间距」，
            //    他的原话是「我对按钮聚合在一起的理解是，按钮紧挨着按钮，
            //    **不会按分类分开排列**」。
            //    也就是说他要的不是「紧凑一点的分组」，而是**根本不分块**：
            //    一格挨着一格填满每一行，颜色自己说明它属于哪一类。
            //
            // ⚠️ 代价说清：两个颜色接近的分类会挨在一起，中间没有空隙提示边界。
            //    这是他自己拍板接受的（「我们用颜色就已经区分得了」）。
            FlatGrid(cells = flatCells, onPick = onPick)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = blocks, key = { it.first.id }) { (cat, inCat) ->
                    CatBlock(cat = cat, inCat = inCat, onPick = onPick)
                }
            }
        }

        if (templateOffer) {
            TemplateEntry(
                on = templateOn,
                name = templateName,
                onClick = onTemplateClick,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 带状态的选事项面板 —— **三个入口都走这一个**。
 *
 * ## 为什么要这个包装，而不是让三处各传 compact
 *
 * 这个面板有三个调用点：时间线的「结束/换事项/补记」、模板页的「改这一段记什么」、
 * 以及**桌面小部件的快速切换窗**。聚合开关的状态必须在三处**完全一致**，
 * 而且切换后要落盘。
 *
 * 如果让每个调用点自己 `remember { mutableStateOf(...) }` + 自己读写 prefs，
 * 就是三份「读写同一个 SharedPreferences 字段」的代码 —— 只要有一处忘了落盘，
 * 症状就是「在小部件里切换了，打开 App 又变回去」，而且**不报错、不崩**。
 * 这类「同一个东西有多个副本」的坑在这个项目里已经踩过八次了（见 MEMORY 的「唯一入口」）。
 *
 * 所以：状态只在这里有一份，调用点只管传内容。
 */
@Composable
fun PickerHost(
    title: String,
    subtitle: String?,
    categories: List<CategoryEntity>,
    items: List<ItemEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    templateOffer: Boolean = false,
    templateOn: Boolean = false,
    templateName: String = "",
    onTemplateClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember(context) { PickerPrefs(context) }
    // 初值只读一次；之后由点击驱动，不再回头查 prefs（避免「读到旧值把刚切的改回去」）
    var compact by remember { mutableStateOf(prefs.compact) }

    ItemPickerSheet(
        title = title,
        subtitle = subtitle,
        categories = categories,
        items = items,
        onPick = onPick,
        onDismiss = onDismiss,
        templateOffer = templateOffer,
        templateOn = templateOn,
        templateName = templateName,
        onTemplateClick = onTemplateClick,
        compact = compact,
        onToggleCompact = {
            val next = !compact
            compact = next
            prefs.compact = next
        },
    )
}

/**
 * 右上角的「聚合 / 分组」切换。
 *
 * ## 为什么是文字胶囊，不是图标
 *
 * 项目只依赖了 Material 基础图标集，里面**没有 grid 那个图标**（只有 List / Menu 之类）。
 * 为一个按钮去引 `material-icons-extended`（几千个矢量）不值当。
 * 而且文字比图标更不容易猜错 —— 「聚合」两个字没人会理解成别的。
 *
 * ## 为什么两个不同的字，不是一个开关控件
 *
 * 一个拨动开关（Switch）省地方，但它只能表达「开/关」，
 * 而这里的两个状态叫「分组」和「聚合」——这两个词本身才是信息。
 * 一个写着「分组 / 聚合」的 SegmentedButton 又要占掉整行宽度，
 * 而这一行得留给标题。所以做成**一个按钮，字面就是点下去的结果**。
 */
@Composable
private fun LayoutToggle(compact: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = if (compact) "分组" else "聚合",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 面板左下角那行模板入口。
 *
 * ## 为什么会藏在这么一个角落里
 *
 * 因为它是**低频但关键**的一个决定：一天最多按一两次，但按下去之后
 * 接下来十几个小时的空白就都有主了。它不该占底部导航、也不该进设置页
 * （进设置就是每天多走三步），最合适的位置就是**你手已经放在上面的那个面板里**。
 *
 * ## 两种形态
 *
 * - **没在跑** → 可点的「切到模板模式」
 * - **在跑** → 一行**不可点**的状态（「模板运行中 · 工作日」）。
 *   ⚠️ 这里刻意**不**做成「点一下退出」：退出的动作只有一个
 *   （点结束 + 亲手选别的事项），开第二个出口会让「我不小心点了模板」变成
 *   「我到底退没退出」的猜谜。要退出就去点上面那格事项。
 *
 * ## ⚠️ 齿轮是矢量图标，不是「⚙」这个字符
 *
 * 真机字体对 U+2699 的覆盖靠不住，而 `Icons.Filled.Settings` 是矢量路径，
 * 任何设备上都一样。这个坑在 `▨`（U+25A8）身上已经踩过一次了。
 */
@Composable
private fun TemplateEntry(
    on: Boolean,
    name: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (on) Modifier else Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (on) {
                "模板运行中 · ${name.ifEmpty { "—" }}"
            } else {
                "切到模板模式"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (on) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 聚合视图的网格 —— **一个连续流，不在分类边界断开**。
 *
 * 每行 6 格、格间距 4dp、行间距 4dp，跟分组视图里「同一个分类内部」的间距完全一致。
 * 所以拍平之后，视觉上就是「原来那一块块的间距消失了，格子连成一片」。
 *
 * ⚠️ 每格的颜色来自**它自己的分类**（不是统一色），这就是「颜色即分类」唯一的载体。
 *
 * ⚠️ 每行末尾用 `Spacer(weight(1f))` 补空格子：不补的话最后一行会被拉宽，
 * 整片看着就歪了 —— 这条在分组视图里已经踩过一次。
 */
@Composable
private fun FlatGrid(
    cells: List<Pair<ItemEntity, Color>>,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = cells.chunked(COLS)) { rowCells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowCells.forEach { (item, color) ->
                    ItemCell(
                        name = item.name,
                        color = color,
                        ink = inkFor(color),
                        onClick = { onPick(item.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(COLS - rowCells.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 一个分类那一整块 —— 同色的一格一格，外加头顶一行极小的小字说明它是哪一类。 */
@Composable
private fun CatBlock(
    cat: CategoryEntity,
    inCat: List<ItemEntity>,
    onPick: (String) -> Unit,
) {
    val color = toColor(cat.colorHex)
    val ink = inkFor(color)

    Column(modifier = Modifier.fillMaxWidth()) {
        // ⚠️ 这行分类名原型里没有（它纯靠颜色分组）。真机上加它是必要的：
        //    两三个分类颜色接近时（比如都是蓝），光看色块分不出来。
        //    做得极小、极淡 —— 它是路标，不是内容。
        //
        // ⚠️ 聚合视图不要这一行（御坂要求「颜色就是分类」）。那边走的是 [FlatGrid]，
        //    连「一块一块」都不存在了，所以这个参数也不存在了。
        Text(
            text = cat.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )

        // 六列。不满一行时**用空格子补齐**，这样每一格宽度都一样 ——
        // 不然最后一行会被拉宽，整块看着就歪了。
        inCat.chunked(COLS).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowItems.forEach { item ->
                    ItemCell(
                        name = item.name,
                        color = color,
                        ink = ink,
                        onClick = { onPick(item.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(COLS - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 网格里的一格。
 *
 * 格子自己有颜色 —— 那个颜色是**分类的颜色**，事项不单独存色。
 * 所以改一次分类色，整块一起换，网格和统计跟着走。
 */
@Composable
private fun ItemCell(
    name: String,
    color: Color,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            // 字号压到 11sp 是为了让「写代码」这种四字事项在一格里放得下。
            // 再小就看不清了，再大就得砍列数 —— 六列是原型定下来的底线。
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val COLS = 6
