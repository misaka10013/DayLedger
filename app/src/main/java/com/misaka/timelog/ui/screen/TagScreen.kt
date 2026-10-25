package com.misaka.timelog.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.misaka.timelog.data.entity.TagBucketEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.ui.CatalogViewModel
import com.misaka.timelog.ui.component.ColorDialog
import com.misaka.timelog.ui.component.ConfirmDialog
import com.misaka.timelog.ui.component.ColorDot
import com.misaka.timelog.ui.component.MessageHost
import com.misaka.timelog.ui.component.NameColorDialog
import com.misaka.timelog.ui.component.TextInputDialog
import kotlinx.coroutines.launch

/**
 * 标签管理页 —— 两级结构：**桶 → 标签**。
 *
 * ## 这一页最容易被做错的地方
 *
 * 它和「分类 → 事项」看起来一模一样（都是两层、都有删除守卫），
 * 但**语义完全不同**：
 *
 * | | 分类 → 事项 | 桶 → 标签 |
 * |---|---|---|
 * | 关系 | **归属** | **纯容器** |
 * | 换归属 / 换桶 | 历史统计跟着重算 | **一个字都不动**（记录上根本不挂桶） |
 * | 删 | 先搬空 | 先搬空（但删完没任何连带影响） |
 *
 * 所以这一页的「换桶」提示语里**不该出现**任何关于统计的字眼 ——
 * 那是在照抄分类页的话术，会让用户误以为改个桶会影响数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    vm: CatalogViewModel,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageHost(vm.message, snackbar)
    val scope = rememberCoroutineScope()

    var fabMenu by remember { mutableStateOf(false) }
    var newBucket by remember { mutableStateOf(false) }
    var newTagInBucket by remember { mutableStateOf<TagBucketEntity?>(null) }
    var pickingBucket by remember { mutableStateOf(false) }

    var renamingBucket by remember { mutableStateOf<TagBucketEntity?>(null) }
    var renamingTag by remember { mutableStateOf<TagEntity?>(null) }
    var recoloringTag by remember { mutableStateOf<TagEntity?>(null) }
    var movingTag by remember { mutableStateOf<TagEntity?>(null) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    // 待确认删除的标签 + 它会被连带影响的东西（文案是异步查出来的）
    var deletingTag by remember { mutableStateOf<TagEntity?>(null) }
    var tagImpactText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标签") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建")
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("新建标签") },
                        onClick = {
                            fabMenu = false
                            if (vm.buckets.isEmpty()) {
                                scope.launch { snackbar.showSnackbar("标签要先放进一个「桶」里，先建个桶吧。") }
                            } else if (vm.buckets.size == 1) {
                                newTagInBucket = vm.buckets.first()
                            } else {
                                pickingBucket = true
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("新建标签桶") },
                        onClick = { fabMenu = false; newBucket = true },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Text(
                    text = "标签是**横切**在分类之外的：一条记录可以挂多个标签，也可以一个都没有。" +
                        "桶只是用来把标签分组的抽屉，跟统计口径没有关系。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }

            if (vm.buckets.isEmpty()) {
                item {
                    Text(
                        text = "还没有标签桶。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            vm.buckets.forEach { bucket ->
                val bucketTags = vm.tags.filter { it.bucketId == bucket.id }
                val bucketIndex = vm.buckets.indexOf(bucket)

                item(key = "bh-${bucket.id}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Text(bucket.icon, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = bucket.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                        )
                        Text(
                            text = "${bucketTags.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            IconButton(onClick = { openMenu = "b:${bucket.id}" }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.padding(4.dp))
                            }
                            DropdownMenu(
                                expanded = openMenu == "b:${bucket.id}",
                                onDismissRequest = { openMenu = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("改名") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { renamingBucket = bucket; openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("上移") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                                    enabled = bucketIndex > 0,
                                    onClick = { vm.moveBucket(bucket.id, -1); openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("下移") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                                    enabled = bucketIndex < vm.buckets.lastIndex,
                                    onClick = { vm.moveBucket(bucket.id, 1); openMenu = null },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除这个桶", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = { vm.deleteBucket(bucket.id); openMenu = null },
                                )
                            }
                        }
                    }
                }

                if (bucketTags.isEmpty()) {
                    item(key = "be-${bucket.id}") {
                        Text(
                            text = "（这个桶还是空的）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 56.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                }

                bucketTags.forEach { tag ->
                    item(key = tag.id) {
                        ListItem(
                            leadingContent = { ColorDot(tag.colorHex, size = 20) },
                            headlineContent = { Text(tag.name) },
                            supportingContent = {
                                if (tag.description.isNotBlank()) {
                                    Text(
                                        text = tag.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { openMenu = "t:${tag.id}" }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                    }
                                    DropdownMenu(
                                        expanded = openMenu == "t:${tag.id}",
                                        onDismissRequest = { openMenu = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("改名") },
                                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                            onClick = { renamingTag = tag; openMenu = null },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("改颜色和说明") },
                                            leadingIcon = { Icon(Icons.Filled.Palette, null) },
                                            onClick = { recoloringTag = tag; openMenu = null },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("换到别的桶") },
                                            leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                            onClick = { movingTag = tag; openMenu = null },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = {
                                                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                            },
                                            onClick = {
                                                openMenu = null
                                                deletingTag = tag
                                                // 先给一句「正在查」，避免弹窗空着抖一下
                                                tagImpactText = "正在看这个标签牵连了多少东西…"
                                                scope.launch {
                                                    tagImpactText = vm.tagImpactOf(tag.id).describe()
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                item(key = "bd-${bucket.id}") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }

    // ------------------------------------------------------------ 对话框

    deletingTag?.let { tag ->
        ConfirmDialog(
            title = "删掉「${tag.name}」？",
            text = tagImpactText,
            confirmLabel = "删除",
            onDismiss = { deletingTag = null },
            onConfirm = { vm.deleteTag(tag.id); deletingTag = null },
        )
    }

    if (newBucket) {
        NameColorDialog(
            title = "新建标签桶",
            label = "桶的名字",
            support = "桶只是分组用的抽屉，不影响统计口径。",
            onDismiss = { newBucket = false },
            onConfirm = { name, _ -> vm.addBucket("🏷", name); newBucket = false },
        )
    }

    if (pickingBucket) {
        BucketPickerDialog(
            title = "标签放进哪个桶",
            buckets = vm.buckets,
            currentId = "",
            onDismiss = { pickingBucket = false },
            onConfirm = { id ->
                newTagInBucket = vm.buckets.first { it.id == id }
                pickingBucket = false
            },
        )
    }

    newTagInBucket?.let { bucket ->
        TagEditorDialog(
            title = "新建标签",
            support = "会放进「${bucket.name}」这个桶。",
            onDismiss = { newTagInBucket = null },
            onConfirm = { name, color, desc ->
                vm.addTag(bucket.id, name, color, desc)
                newTagInBucket = null
            },
        )
    }

    renamingBucket?.let { bucket ->
        TextInputDialog(
            title = "给桶改名",
            label = "桶的名字",
            initial = bucket.name,
            onDismiss = { renamingBucket = null },
            onConfirm = { name -> vm.renameBucket(bucket.id, name); renamingBucket = null },
        )
    }

    renamingTag?.let { tag ->
        TextInputDialog(
            title = "给标签改名",
            label = "标签名",
            initial = tag.name,
            supportingText = "改名不需要动任何一条记录 —— 记录上挂的是标签编号。",
            onDismiss = { renamingTag = null },
            onConfirm = { name -> vm.renameTag(tag.id, name); renamingTag = null },
        )
    }

    recoloringTag?.let { tag ->
        TagEditorDialog(
            title = "改标签",
            initialName = tag.name,
            initialColor = tag.colorHex,
            initialDesc = tag.description,
            nameEditable = false,
            onDismiss = { recoloringTag = null },
            onConfirm = { _, color, desc ->
                vm.editTag(tag.id, color, desc)
                recoloringTag = null
            },
        )
    }

    movingTag?.let { tag ->
        BucketPickerDialog(
            title = "换到哪个桶",
            buckets = vm.buckets,
            currentId = tag.bucketId,
            onDismiss = { movingTag = null },
            onConfirm = { id -> vm.moveTagToBucket(tag.id, id); movingTag = null },
        )
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun BucketPickerDialog(
    title: String,
    buckets: List<TagBucketEntity>,
    currentId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var picked by remember { mutableStateOf(currentId.ifBlank { buckets.firstOrNull()?.id.orEmpty() }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                buckets.forEach { bucket ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = bucket.id }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = picked == bucket.id, onClick = { picked = bucket.id })
                        Text(bucket.icon)
                        Text(bucket.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }, enabled = picked.isNotBlank()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 标签编辑器：名字 + 颜色 + 一行说明。
 *
 * 「说明」这一格不是装饰 —— 它的作用是**防止标签越加越含糊**：
 * 过几个月回头看「低价值消耗」这种词，自己都想不起来当初指什么。
 */
@Composable
private fun TagEditorDialog(
    title: String,
    initialName: String = "",
    initialColor: String = com.misaka.timelog.ui.component.Palette.first(),
    initialDesc: String = "",
    nameEditable: Boolean = true,
    support: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String, desc: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var desc by remember { mutableStateOf(initialDesc) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (nameEditable) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("标签名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                androidx.compose.material3.OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("说明（给几个月后的自己看）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    text = "颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                com.misaka.timelog.ui.component.ColorPickerRow(selected = color, onPick = { color = it })
                if (support != null) {
                    Text(
                        text = support,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), color, desc.trim()) },
                enabled = !nameEditable || name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
