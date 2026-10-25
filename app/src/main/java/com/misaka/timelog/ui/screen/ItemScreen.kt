package com.misaka.timelog.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.domain.ColorSeries
import com.misaka.timelog.ui.CatalogViewModel
import com.misaka.timelog.ui.component.ColorDialog
import com.misaka.timelog.ui.component.ColorDot
import com.misaka.timelog.ui.component.ConfirmDialog
import com.misaka.timelog.ui.component.MessageHost
import com.misaka.timelog.ui.component.NameColorDialog
import com.misaka.timelog.ui.component.TextInputDialog
import com.misaka.timelog.ui.component.toColor
import kotlinx.coroutines.launch

/**
 * 事项管理页 —— 按分类分组列出所有事项。
 *
 * ## 删除是这里唯一有确认弹窗的操作
 *
 * 因为它是**唯一不可逆**的：删掉一个事项，历史上所有记过它的时间会
 * 全部变成「未记录」的空白块。所以点删除时先去数据层问一句
 * 「这个事项记过多少条、加起来多久」，把后果摆出来再让用户决定。
 *
 * 顺带说：这是这个 App 里唯一需要用户想一秒的操作。
 * 其他地方（删记录、删标签、删分类）都是「删错了马上能改回来」，不需要拦。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemScreen(
    vm: CatalogViewModel,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageHost(vm.message, snackbar)
    val scope = rememberCoroutineScope()

    var pickingCategory by remember { mutableStateOf(false) }
    var creatingIn by remember { mutableStateOf<CategoryEntity?>(null) }
    var renaming by remember { mutableStateOf<ItemEntity?>(null) }
    var recoloring by remember { mutableStateOf<ItemEntity?>(null) }
    var moving by remember { mutableStateOf<ItemEntity?>(null) }
    var tagging by remember { mutableStateOf<ItemEntity?>(null) }
    var deleting by remember { mutableStateOf<ItemEntity?>(null) }
    var impactText by remember { mutableStateOf("") }
    var openMenu by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("事项") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // 事项必须有地方放。没分类就先别开始，直接说清楚
                    when {
                        vm.categories.isEmpty() ->
                            scope.launch { snackbar.showSnackbar("先建一个分类，事项需要有地方放。") }

                        vm.categories.size == 1 -> creatingIn = vm.categories.first()

                        else -> pickingCategory = true
                    }
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建事项")
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier.padding(padding),
        ) {
            if (vm.categories.isEmpty()) {
                item { EmptyHint("还没有分类。事项必须归到一个分类下面，先去「分类」建一个。") }
            }

            vm.categories.forEach { category ->
                val siblings = vm.items.filter { it.categoryId == category.id }

                item(key = "head-${category.id}") {
                    GroupHeader(category = category, count = siblings.size)
                }

                if (siblings.isEmpty()) {
                    item(key = "empty-${category.id}") {
                        Text(
                            text = "（这个分类下还没有事项）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 56.dp, bottom = 8.dp),
                        )
                    }
                }

                siblings.forEachIndexed { index, item ->
                    item(key = item.id) {
                        ItemRow(
                            item = item,
                            tags = vm.defaultTags[item.id].orEmpty(),
                            index = index,
                            lastIndex = siblings.lastIndex,
                            openMenu = openMenu == item.id,
                            onOpenMenu = { openMenu = item.id },
                            onCloseMenu = { openMenu = null },
                            onRename = { renaming = item; openMenu = null },
                            onRecolor = { recoloring = item; openMenu = null },
                            onMoveCategory = { moving = item; openMenu = null },
                            onDefaults = {
                                tagging = item
                                openMenu = null
                            },
                            onUp = { vm.moveItem(item.id, -1); openMenu = null },
                            onDown = { vm.moveItem(item.id, 1); openMenu = null },
                            onDelete = {
                                openMenu = null
                                scope.launch {
                                    impactText = vm.impactOf(item.id).describe()
                                    deleting = item
                                }
                            },
                        )
                    }
                }

                item(key = "div-${category.id}") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }

    // ------------------------------------------------------------ 对话框

    if (pickingCategory) {
        CategoryPickerDialog(
            title = "新建到哪个分类下",
            categories = vm.categories,
            currentId = "",
            note = "建好之后还能改归属。",
            onDismiss = { pickingCategory = false },
            onConfirm = { id ->
                creatingIn = vm.categories.first { it.id == id }
                pickingCategory = false
            },
        )
    }

    creatingIn?.let { category ->
        NameColorDialog(
            title = "新建事项",
            label = "事项名",
            support = "会归到「${category.name}」下面，颜色自动跟着这个分类的色系走。",
            // ★ 默认「自动」（空串）—— 不是随便挑一个色。
            //   挑一个具体色等于一建出来就是「手选」，以后分类换色系它不会跟着变。
            initialColor = "",
            swatches = ColorSeries.shadesOfCategory(category.colorHex),
            onDismiss = { creatingIn = null },
            onConfirm = { name, color ->
                vm.addItem(category.id, name, color)
                creatingIn = null
            },
        )
    }

    renaming?.let { item ->
        TextInputDialog(
            title = "给事项改名",
            label = "事项名",
            initial = item.name,
            supportingText = "改名不影响历史记录 —— 记录上挂的是它的编号，不是名字。",
            onDismiss = { renaming = null },
            onConfirm = { name -> vm.renameItem(item.id, name); renaming = null },
        )
    }

    recoloring?.let { item ->
        // 只摆**这个分类色系的 8 档** —— 事项不许跳到别的色系，
        // 否则「颜色 = 这是哪一类」这个含义就废了（统计图上又会糊）。
        val catHex = vm.categories.firstOrNull { it.id == item.categoryId }?.colorHex.orEmpty()
        ColorDialog(
            title = "选个颜色",
            initial = item.colorHex,
            swatches = ColorSeries.shadesOfCategory(catHex),
            autoLabel = "跟随分类（自动）",
            onDismiss = { recoloring = null },
            // 回调空串 = 恢复自动（以后分类换色系它会跟着变）
            onConfirm = { hex -> vm.recolorItem(item.id, hex); recoloring = null },
        )
    }

    moving?.let { item ->
        CategoryPickerDialog(
            title = "换到哪个分类",
            categories = vm.categories,
            currentId = item.categoryId,
            note = "⚠️ 换归属会让历史统计跟着一起重算 —— 过去记在旧分类下的时间，" +
                "会全部挪到新分类的账上。",
            onDismiss = { moving = null },
            onConfirm = { categoryId ->
                vm.moveItemToCategory(item.id, categoryId)
                moving = null
            },
        )
    }

    tagging?.let { item ->
        DefaultTagsDialog(
            vm = vm,
            item = item,
            onDismiss = { tagging = null },
        )
    }

    deleting?.let { item ->
        ConfirmDialog(
            title = "删掉「${item.name}」？",
            text = impactText,
            confirmLabel = "删除",
            onDismiss = { deleting = null },
            onConfirm = { vm.deleteItem(item.id); deleting = null },
        )
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun GroupHeader(category: CategoryEntity, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        ColorDot(category.colorHex, size = 14)
        Text(
            text = category.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

/**
 * 事项名下面那一行默认标签。
 *
 * ## 为什么要显示出来
 *
 * 默认标签原来只能点开弹窗才看得到（而且弹窗列表默认只露 6 个、还要滚），
 * 于是「这个事项记的时候到底会带上什么」变成一个看不见的状态。
 * 现在直接摊在列表里。
 *
 * ## 最多三个 + 「+N」
 *
 * 一个事项最多能设 8 个标签，全排出来会把行撑得很长、还会把事项名挤没。
 * 三个够覆盖绝大多数情况（真库里 29 个事项最多也就 3 个），
 * 剩下的用 `+N` 表达「还有，自己点开看」。
 */
@Composable
private fun DefaultTagStrip(tags: List<TagEntity>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tags.take(MAX_TAGS_ON_ROW).forEach { tag ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(tag.colorHex, size = 7)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = tag.name,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tags.size > MAX_TAGS_ON_ROW) {
            Text(
                text = "+${tags.size - MAX_TAGS_ON_ROW}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 事项行里最多平铺几个默认标签（超出显示 `+N`）。 */
private const val MAX_TAGS_ON_ROW = 3

@Composable
private fun ItemRow(
    item: ItemEntity,
    tags: List<TagEntity>,
    index: Int,
    lastIndex: Int,
    openMenu: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onRename: () -> Unit,
    onRecolor: () -> Unit,
    onMoveCategory: () -> Unit,
    onDefaults: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        leadingContent = { ColorDot(item.colorHex, size = 22) },
        headlineContent = { Text(item.name) },
        // 名字下面一行小标签 —— 这个事项记的时候会自动带上哪几个。
        // 没有标签就传 null（而不是空 Row）：ListItem 会自己把行高压回去。
        supportingContent = if (tags.isEmpty()) null else {
            { DefaultTagStrip(tags) }
        },
        trailingContent = {
            Box {
                IconButton(onClick = onOpenMenu) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = openMenu, onDismissRequest = onCloseMenu) {
                    DropdownMenuItem(
                        text = { Text("改名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = onRename,
                    )
                    DropdownMenuItem(
                        text = { Text("改颜色") },
                        leadingIcon = { Icon(Icons.Filled.Palette, null) },
                        onClick = onRecolor,
                    )
                    DropdownMenuItem(
                        text = { Text("换到别的分类") },
                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                        onClick = onMoveCategory,
                    )
                    DropdownMenuItem(
                        text = { Text("默认标签") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                        onClick = onDefaults,
                    )
                    DropdownMenuItem(
                        text = { Text("上移") },
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                        enabled = index > 0,
                        onClick = onUp,
                    )
                    DropdownMenuItem(
                        text = { Text("下移") },
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                        enabled = index < lastIndex,
                        onClick = onDown,
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = onDelete,
                    )
                }
            }
        },
    )
}

/** 选一个分类的对话框 —— 新建时的归属、以及换归属都用它。 */
@Composable
private fun CategoryPickerDialog(
    title: String,
    categories: List<CategoryEntity>,
    currentId: String,
    note: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var picked by remember { mutableStateOf(currentId.ifBlank { categories.firstOrNull()?.id.orEmpty() }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                categories.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = category.id }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = picked == category.id,
                            onClick = { picked = category.id },
                        )
                        ColorDot(category.colorHex, size = 16)
                        Text(category.name, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }, enabled = picked.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 事项的默认标签：记这个事项时会自动带上。 */
@Composable
private fun DefaultTagsDialog(
    vm: CatalogViewModel,
    item: ItemEntity,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }

    // ⚠️ 加载现有默认标签是**副作用**，必须放在 LaunchedEffect 里。
    //    写成「if (!loaded) { scope.launch{...} }」在重组时会重复触发。
    LaunchedEffect(item.id) {
        selected.clear()
        selected.addAll(vm.defaultTagsOf(item.id))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${item.name}」的默认标签") },
        text = {
            if (vm.tags.isEmpty()) {
                Text("还没有标签。先去「标签」里建几个。")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "记这个事项时，勾上的标签会自动带上（记录页里还能当场加减）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    vm.tags.forEach { tag ->
                        val checked = selected.contains(tag.id)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selected.remove(tag.id) else selected.add(tag.id)
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                if (it) selected.add(tag.id) else selected.remove(tag.id)
                            })
                            ColorDot(tag.colorHex, size = 14)
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(tag.name, style = MaterialTheme.typography.bodyMedium)
                                if (tag.description.isNotBlank()) {
                                    Text(
                                        text = tag.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setDefaultTags(item.id, selected.toList())
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
