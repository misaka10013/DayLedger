package com.misaka.timelog.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misaka.timelog.data.entity.TagBucketEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.ui.RecordRow
import com.misaka.timelog.ui.component.inkFor
import com.misaka.timelog.ui.component.toColor

/**
 * 编辑一条记录的**内容和标签**。
 *
 * ## ★ 这里持的是一份「草稿」
 *
 * 面板有「取消」按钮 —— 如果点一下标签就立刻写库，那个取消就成了骗人的东西
 * （原型第 19 轮真踩过这个坑，代码里还留着注释）。所以：
 * **改动只落在本地的 `note` / `picked` 上，点「保存」才交给数据层。**
 *
 * ## ⚠️ 这里跟原型有一处不同：标签是平铺的，不再套一层选择面板
 *
 * 原型点「+ 添加」会再弹一层标签选择页（点一个加一个、再返回）。
 * 真机版直接把**全部标签**铺在这儿，点一下切换选中/取消。
 *
 * 理由：那两层结构里，「返回」这一步是纯粹的成本 —— 反正用户加完标签
 * 还是要回到这个面板点保存。而且平铺之后「这条记录挂了哪些标签」和
 * 「还有哪些能挂」在同一个屏幕上，不会出现「加完才发现加错了要再来一遍」。
 *
 * 代价是标签多的时候面板会长 —— 所以它可滚动。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContentEditSheet(
    row: RecordRow,
    buckets: List<TagBucketEntity>,
    allTags: List<TagEntity>,
    onDismiss: () -> Unit,
    onSave: (note: String, tagIds: List<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ★ 草稿。`remember(row.id)` 的 key 是记录 id —— 换了一条记录就重新起稿，
    //   不然会拿上一条的备注去填这一条。
    var note by remember(row.id) { mutableStateOf(row.note) }
    var picked by remember(row.id) { mutableStateOf(row.tags.map { it.id }.toSet()) }

    // 按桶分组（桶是纯容器：换桶、删桶都不动任何记录）。
    // 空桶不显示 —— 它只是个空标题，白占一行。
    val groups = remember(buckets, allTags) {
        buckets
            .map { b -> b to allTags.filter { it.bucketId == b.id }.sortedBy { it.name } }
            .filter { it.second.isNotEmpty() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "编辑内容、标签",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${row.name} · ${row.startLabel} → ${row.endLabel}" +
                    (row.categoryName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                placeholder = { Text("这条记录的一句话说明") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "标签",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "点一下就挂上／摘掉 —— 要按「保存」才真正写进这条记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (groups.isEmpty()) {
                Text(
                    text = "还没有标签。去「设置 → 标签」建几个，就能给记录分类打标了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                groups.forEach { (bucket, tags) ->
                    Text(
                        text = "${bucket.icon} ${bucket.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            TagToggle(
                                name = tag.name,
                                colorHex = tag.colorHex,
                                on = tag.id in picked,
                                onClick = {
                                    picked = if (tag.id in picked) {
                                        picked - tag.id
                                    } else {
                                        picked + tag.id
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { onSave(note, picked.toList()) }) {
                    Text("保存" + if (picked.isNotEmpty()) "（${picked.size} 个标签）" else "")
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 一颗可以点的标签。
 *
 * 选中 = 用标签自己的颜色填实；没选 = 只描一圈那个颜色。
 * **颜色是标签自己的** —— 一眼能认出是「哪一类标记」，而不是一堆长得一样的灰片。
 */
@Composable
private fun TagToggle(
    name: String,
    colorHex: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val color = toColor(colorHex)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (on) {
                    Modifier.background(color)
                } else {
                    Modifier
                        .background(color.copy(alpha = 0.10f))
                        .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (on) "✓ $name" else name,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) inkFor(color) else color,
            maxLines = 1,
        )
    }
}
