package com.misaka.timelog.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.domain.ColorSeries
import com.misaka.timelog.ui.CatalogViewModel
import com.misaka.timelog.ui.component.ColorDialog
import com.misaka.timelog.ui.component.ColorDot
import com.misaka.timelog.ui.component.MessageHost
import com.misaka.timelog.ui.component.NameColorDialog
import com.misaka.timelog.ui.component.TextInputDialog

/**
 * 分类管理页。
 *
 * ## 删除为什么不需要确认弹窗
 *
 * 因为「**先搬空、才可删**」这条规则已经在数据层保证了：
 * 分类下面还有事项时，这个删除请求**根本不会被受理**，会带着一句人话回来。
 * 所以能删掉的分类，必然已经是个空壳 —— 删错了也什么都没损失。
 *
 * ⚠️ 删除按钮**故意不灰**。灰着的话用户只知道「点不了」，
 * 不知道「为什么」；让它可点、点了弹出「还有 3 个事项得先搬走」，
 * 用户下一次就记住这条规则了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    vm: CatalogViewModel,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageHost(vm.message, snackbar)

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CategoryEntity?>(null) }
    var recoloring by remember { mutableStateOf<CategoryEntity?>(null) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建分类")
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier.padding(padding),
        ) {
            itemsIndexed(vm.categories, key = { _, c -> c.id }) { index, category ->
                val itemCount = vm.items.count { it.categoryId == category.id }

                ListItem(
                    leadingContent = { ColorDot(category.colorHex, size = 24) },
                    headlineContent = { Text(category.name) },
                    supportingContent = {
                        Text(
                            text = if (itemCount == 0) "还没有事项" else "$itemCount 个事项",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { openMenu = category.id }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = openMenu == category.id,
                                onDismissRequest = { openMenu = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("改名") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { renaming = category; openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("改颜色") },
                                    leadingIcon = { Icon(Icons.Filled.Palette, null) },
                                    onClick = { recoloring = category; openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("上移") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                                    enabled = index > 0,
                                    onClick = { vm.moveCategory(category.id, -1); openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("下移") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                                    enabled = index < vm.categories.lastIndex,
                                    onClick = { vm.moveCategory(category.id, 1); openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = { vm.deleteCategory(category.id); openMenu = null },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    if (creating) {
        NameColorDialog(
            title = "新建分类",
            label = "分类名",
            support = "分类决定一个色系（12 个色系轮流用），它下面的事项会自动分到这个色系的深浅色。",
            // ★ 默认给「第一个还没被占用的色系」—— 见 ColorSeries.nextFreeSeries 的注释
            initialColor = ColorSeries.categoryColor(
                ColorSeries.nextFreeSeries(vm.categories.map { it.colorHex }),
            ),
            swatches = ColorSeries.allCategoryColors(),
            onDismiss = { creating = false },
            onConfirm = { name, color -> vm.addCategory(name, color); creating = false },
        )
    }

    renaming?.let { category ->
        TextInputDialog(
            title = "给分类改名",
            label = "分类名",
            initial = category.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> vm.renameCategory(category.id, name); renaming = null },
        )
    }

    recoloring?.let { category ->
        ColorDialog(
            title = "选个颜色",
            initial = category.colorHex,
            // ★ 分类只能在 12 个色系里挑 —— 换色系 = 这个分类下所有「自动」的事项
            //   一起换成新色系的深浅色（手选过颜色的那几个不动）。
            swatches = ColorSeries.allCategoryColors(),
            onDismiss = { recoloring = null },
            onConfirm = { hex -> vm.recolorCategory(category.id, hex); recoloring = null },
        )
    }
}
