package com.misaka.timelog.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharedFlow

/**
 * 挑颜色的备选色板。
 *
 * ⚠️ **颜色在这个 App 里是有语义的**，不是装饰：
 * 一级分类的颜色决定选择网格里那一整块长什么样，是用户认事物的**第一眼线索**。
 *
 * 所以：
 * - 色板给的是一批**区分度高**的颜色，避免两个分类看着差不多
 * - 全局关掉了「动态取色」（跟壁纸走），否则用户精心挑的颜色会被壁纸主色冲掉
 */
val Palette: List<String> = listOf(
    "#EF4444", "#F97316", "#F59E0B", "#EAB308",
    "#84CC16", "#22C55E", "#10B981", "#14B8A6",
    "#06B6D4", "#3B82F6", "#6366F1", "#8B5CF6",
    "#A855F7", "#D946EF", "#EC4899", "#64748B",
)

/** 把 `#RRGGBB` 变成 Compose 的颜色。解析不了就退回灰色，不崩。 */
fun toColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF9E9E9E))

/**
 * 压在某个背景色上的字该用什么颜色。
 *
 * 用亮度判：亮底配深字、暗底配白字。
 *
 * ⚠️ **不能写死白色**。分类色板里有 `#EAB308` 这种明黄色 ——
 * 白字压上去几乎看不见（原型里专门有个 `inkFor()` 干这件事，
 * 因为它的 48 色板上更极端的情况都有）。
 */
fun inkFor(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF1A1A1A) else Color.White

/** 一个圆圆的色点。列表里用它做「这是哪一类」的视觉锚点。 */
@Composable
fun ColorDot(hex: String, size: Int = 18, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(toColor(hex))
    )
}

/**
 * 横向色板，点一下就选中。
 *
 * @param swatches 要摆哪些颜色。默认是 [Palette]（那 16 个自由色，标签在用）。
 *   **分类**传 `ColorSeries` 的 12 个主色，**事项**传所属分类色系的 8 档渐变 ——
 *   见 `ColorSeries` 的注释：分类决定色系，事项只能在这个色系里挑深浅。
 */
@Composable
fun ColorPickerRow(
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    swatches: List<String> = Palette,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        // 12 个色系要塞进一屏，间距收紧一点（16 色那套原来用 10dp）
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(swatches.size) { index ->
            val hex = swatches[index]
            val isSelected = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(toColor(hex))
                    .then(
                        if (isSelected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onPick(hex) }
            )
        }
    }
}

/**
 * 通用的「输入一行字」对话框 —— 新建和改名共用。
 *
 * ⚠️ 空名字在这里就挡住，不让请求发出去 —— 数据层也会挡（双保险），
 * 但在这里挡用户体验更好：按钮直接灰着，用户一眼就知道缺什么。
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    supportingText: String? = null,
    confirmLabel: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = valid,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 确认对话框 —— 只给「不可逆」的操作配（删事项）。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 把 ViewModel 推来的提示挂到 Snackbar 上。
 *
 * 那些文字都是**数据层直接给的中文**，这里一个字都不改 ——
 * 改就等于把业务规则在界面层又抄了一遍。
 */
@Composable
fun MessageHost(messages: SharedFlow<String>, hostState: SnackbarHostState) {
    LaunchedEffect(messages) {
        messages.collect { hostState.showSnackbar(it) }
    }
}

/**
 * 挑颜色的对话框 —— 改分类/事项/标签的颜色都用它。
 *
 * @param swatches 摆哪些色。**分类只有 12 个色系**、**事项只有本分类的 8 档**，
 *   标签继续用那 16 个自由色。
 * @param autoLabel 非空时，色板下面多一个按钮，点了直接回调空字符串 ——
 *   **空串 = 自动**（事项跟随分类色系）。⚠️ 别把 `""` 当成「没选颜色」，
 *   它在整条链路上是一个有明确含义的值，见 `resolveItemColors`。
 */
@Composable
fun ColorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    swatches: List<String> = Palette,
    autoLabel: String? = null,
) {
    var picked by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    ColorDot(picked, size = 28)
                    Text(
                        // 自动时 picked 是空串，显示成「跟随分类」比显示一个空字符串好
                        text = if (picked.isBlank()) "跟随分类（自动）" else picked,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                ColorPickerRow(selected = picked, onPick = { picked = it }, swatches = swatches)
                if (autoLabel != null) {
                    TextButton(
                        onClick = { onConfirm("") },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(autoLabel) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 「起个名 + 挑个色」的对话框 —— 新建分类和新建事项都用它。
 *
 * 为什么新建时就把颜色一起定了：**颜色是用户认事物的第一眼线索**，
 * 让它落到一个随机色再让用户回头改，等于制造一次「咦怎么是这个颜色」。
 */
@Composable
fun NameColorDialog(
    title: String,
    label: String,
    support: String? = null,
    initialColor: String = Palette.first(),
    confirmLabel: String = "创建",
    onDismiss: () -> Unit,
    onConfirm: (name: String, colorHex: String) -> Unit,
    swatches: List<String> = Palette,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                ColorPickerRow(selected = color, onPick = { color = it }, swatches = swatches)
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
                onClick = { onConfirm(name.trim(), color) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
