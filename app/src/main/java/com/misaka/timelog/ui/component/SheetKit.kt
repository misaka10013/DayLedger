package com.misaka.timelog.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 底部操作菜单（sheet）的两块积木 —— 「一项」和「一道分隔线」。
 *
 * ## 为什么从记录卡那个菜单里抽出来
 *
 * M2-B 做记录卡菜单时，这两块是写在 `RecordActionSheet` 里的私有函数。
 * M3-A 做模板段菜单时，需要的**一字不差是同一个东西**：
 * 一样的图标 + 标题 + 副标题、一样的「禁用就变淡」、一样的分组线。
 *
 * 复制一份的代价不是多那 40 行，而是**以后改一处忘一处**：
 * 想调整菜单项的间距时，你会改到其中一份，然后两个菜单看起来像两个人做的。
 * 这个项目在「同一条工程教训」上已经栽过五次了（凡声称「跟 X 走」的，
 * 必须有唯一入口负责同步）。
 *
 * ## ⚠️ 图标一律用矢量，不用 emoji
 *
 * 原型用的是 emoji（🔄 ⏱ ✂ 🔗），真机上不能这么干：
 * emoji 的样式完全随 ROM 变（同一项在不同手机上长得不一样），
 * 而像 `⑂`（U+2442）这种冷僻符号**安卓字体里根本没有**，会渲染成空心豆腐块。
 * 这个坑本项目已经踩过两次（`▨` 和 `⑂`）。
 */
@Composable
internal fun ActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
    primary: Boolean = false,
) {
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        danger -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = titleColor,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(15.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                color = titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 分组之间的那道线。菜单长了以后，靠它分成「改内容」「改结构」「删」三段。 */
@Composable
internal fun Sep() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
