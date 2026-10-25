package com.misaka.timelog.ui.screen

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.misaka.timelog.widget.TimelineWidgetReceiver

/**
 * 设置页。
 *
 * ## 为什么「分类 / 事项 / 标签」在这里而不是底部导航
 *
 * 低频操作不占常驻位。底部三个 tab 留给天天要点的；
 * 而「改个分类颜色」这种事可能一个月才想起一次。
 *
 * ## 一个教训（写下来免得以后又加回去）
 *
 * 设置项只有在「用户真的可能改它、且改了会改变行为」时才有意义。
 * 「一天从几点开始」「要不要允许缝隙」这类要么是产品铁律（不给改）、
 * 要么根本没有第二个选项 —— 放进设置页就是摆着好看，只会让人误以为能调。
 *
 * ## 「生活规律模板」为什么紧跟在事项/分类/标签后面
 *
 * 因为它们都是**内容管理**：改的是「我有哪些事、我的一天长什么样」。
 * 而且模板页的入口**只有这一个** —— 时间线那个「切到模板模式」是**启用**，
 * 不是配置。两个入口各管一件事，不会让人站在时间线上想「我是要改还是要用」。
 */
@Composable
fun SettingsScreen(
    categoryCount: Int,
    itemCount: Int,
    bucketCount: Int,
    tagCount: Int,
    /** 「工作日 · 运行中」或者「工作日 · 工作日」—— 跟原型设置页那一行的口径一致。 */
    templateLabel: String,
    onOpenCategories: () -> Unit,
    onOpenItems: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenTemplate: () -> Unit,
    onOpenImport: () -> Unit,
    /** 云同步（M4-B）：开关 / 地址 / token / 状态文案 / 是否正在同步。 */
    syncEnabled: Boolean,
    syncBaseUrl: String,
    syncToken: String,
    syncStatus: String,
    syncing: Boolean,
    onSyncToggle: (Boolean) -> Unit,
    onSyncBaseUrlChange: (String) -> Unit,
    onSyncTokenChange: (String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    /* ⚠️ 必须能滚（M4-B 加）：加了「云同步」一节之后，内容在多数机型上已经超过一屏，
       不给滚动的话最后那几行会被底部导航栏压住 —— 而它看起来只是"没显示"，
       不会报错，也不会有人想到「原来要滑一下」。 */
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp),
        )

        SectionLabel("内容管理")

        SettingsRow(
            icon = Icons.Filled.Category,
            title = "分类",
            subtitle = "$categoryCount 个 · 决定颜色和统计口径",
            onClick = onOpenCategories,
        )
        SettingsRow(
            icon = Icons.Filled.Sell,
            title = "事项",
            subtitle = "$itemCount 个 · 记录时点的那一格",
            onClick = onOpenItems,
        )
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.Label,
            title = "标签",
            subtitle = "$bucketCount 组 · $tagCount 个 · 横切在事项之外",
            onClick = onOpenTags,
        )
        SettingsRow(
            icon = Icons.Filled.Repeat,
            title = "生活规律模板",
            subtitle = templateLabel,
            onClick = onOpenTemplate,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        SectionLabel("数据")

        SettingsRow(
            icon = Icons.Filled.Restore,
            title = "从旧版时间日志导入",
            // ⚠️ 副标题故意不写「可导入 652 天」那种数字：那要在 App 一启动就解析
            //    一份 474KB 的 JSON（为了设置页一行小字，不划算）。
            //    真数字摆在导入页里，那里才是要下决心的地方。
            subtitle = "把旧 App 的历史记录搬进来",
            onClick = onOpenImport,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        /* ---------------------------------------------------------
         * 云同步（M4-B）
         * ---------------------------------------------------------
         * 为什么这一节值得放进设置页（明知「设置项要有意义」那条铁律）：
         *   它真的有两个状态、真的改变行为、而且用户**必须能自己关掉** ——
         *   往外传数据这件事，不给关就是耍流氓。
         * --------------------------------------------------------- */
        SectionLabel("云同步")

        ListItem(
            headlineContent = { Text("同步到云端（给 AI 读）") },
            supportingContent = {
                Text(syncStatus, style = MaterialTheme.typography.bodySmall)
            },
            leadingContent = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
            trailingContent = {
                Switch(checked = syncEnabled, onCheckedChange = onSyncToggle)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (syncEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = syncBaseUrl,
                    onValueChange = onSyncBaseUrlChange,
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://你的环境-你的appid.ap-shanghai.app.tcloudbase.com/api") },
                    singleLine = true,
                    // 地址也一样关掉自动纠正 —— 域名手滑一个字母就 404，而且看不出是哪里错
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = syncToken,
                    onValueChange = onSyncTokenChange,
                    label = { Text("同步 token") },
                    placeholder = { Text("后端给出的一串写权限凭证") },
                    singleLine = true,
                    // ⚠️ 必须关掉自动大写与自动纠错：token 是大小写敏感的随机串，
                    //    输入法把开头字母变成大写，就会变成「同步一直 401」而看不出原因。
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onSyncNow,
                        // 两样都填齐才能点 —— 少一样都会在发请求之前就被拦下来
                        enabled = !syncing && syncToken.isNotBlank() && syncBaseUrl.isNotBlank(),
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("  同步中…")
                        } else {
                            Text("立即同步")
                        }
                    }
                    Text(
                        text = "只在打开 App 或点这里时上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "传的是「哪段时间在做什么」和事项分类清单，不含备注正文；" +
                        "云端那份只有带 token 的地址能读。换掉服务端的 token 即可让旧地址全部失效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        SectionLabel("桌面")

        SettingsRow(
            icon = Icons.Filled.Widgets,
            title = "把「时间日志」放到桌面",
            subtitle = "不用打开 App —— 桌面上直接看现在在记什么，点一下换下一件",
            onClick = { requestWidgetPin(context) },
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        SectionLabel("说明")
        Text(
            text = "分类是「归属」：改一个事项的分类，历史统计会跟着一起重算。\n" +
                "标签是「横切」：它跟分类互不干涉，一个记录可以有多个标签，也可以一个都没有。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/**
 * 请系统把桌面小部件「钉」到桌面上。
 *
 * ## 为什么要这个入口
 *
 * Android 添加小部件的标准路径是「长按桌面空白处 → 小部件 → 找应用」——
 * 四步，而且不少人根本不知道有这个菜单。`requestPinAppWidget`（Android 8.0+）
 * 能直接弹一个系统确认框，点一下就好。
 *
 * ⚠️ 部分桌面（尤其是国产 ROM 自带的）没有实现这个能力，这时
 * `isRequestPinAppWidgetSupported` 会返回 false —— **不能因此什么都不做**，
 * 要告诉用户手动那条路怎么走。静静什么都不发生是最糟的处理。
 *
 * ⚠️ 不用判断 SDK 版本：`minSdk = 26` 就是 Android 8.0，这两个 API 正好从 26 开始有。
 */
private fun requestWidgetPin(context: Context) {
    val mgr = context.getSystemService(AppWidgetManager::class.java)

    if (mgr == null || !mgr.isRequestPinAppWidgetSupported) {
        Toast.makeText(
            context,
            "这个桌面不支持一键添加 —— 长按桌面空白处，在「小部件」里找「时间日志」",
            Toast.LENGTH_LONG,
        ).show()
        return
    }

    mgr.requestPinAppWidget(
        ComponentName(context, TimelineWidgetReceiver::class.java),
        null,
        null,
    )
}
