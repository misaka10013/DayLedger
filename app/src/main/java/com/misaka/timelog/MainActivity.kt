package com.misaka.timelog

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.misaka.timelog.domain.TemplateEdit
import com.misaka.timelog.ui.CatalogViewModel
import com.misaka.timelog.ui.ImportViewModel
import com.misaka.timelog.ui.StatsViewModel
import com.misaka.timelog.ui.SyncViewModel
import com.misaka.timelog.ui.TemplateViewModel
import com.misaka.timelog.ui.TimelineViewModel
import com.misaka.timelog.ui.screen.CategoryScreen
import com.misaka.timelog.ui.screen.ImportScreen
import com.misaka.timelog.ui.screen.ItemScreen
import com.misaka.timelog.ui.screen.SettingsScreen
import com.misaka.timelog.ui.screen.StatsScreen
import com.misaka.timelog.ui.screen.TagScreen
import com.misaka.timelog.ui.screen.TemplateScreen
import com.misaka.timelog.ui.screen.TimelineScreen
import com.misaka.timelog.ui.theme.TimeLogTheme
import com.misaka.timelog.widget.WidgetRefresh
import kotlinx.coroutines.delay

/**
 * 底部导航的三个一级页面。
 *
 * 为什么只有三个：**低频操作不占常驻位**。
 * 管理「分类 / 事项 / 标签」属于偶尔才进一次的设置类操作，
 * 所以收进「设置」里，而不是在底栏再挤一个位置。
 */
private enum class MainTab(val label: String, val icon: ImageVector) {
    Timeline("时间线", Icons.Filled.Schedule),
    Stats("统计", Icons.Filled.BarChart),
    Settings("设置", Icons.Filled.Settings),
}

/** 设置页内部的二级页面。 */
private enum class SettingsRoute { Home, Categories, Items, Tags, Template, Import }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让内容画到状态栏/导航栏底下，由 Compose 自己处理内边距
        enableEdgeToEdge()
        setContent {
            TimeLogTheme {
                MainScreen()
            }
        }
    }

    /**
     * ★ 离开 App 的那一刻，把桌面小部件刷成最新的。
     *
     * ## 为什么挑这个时机（M3-C）
     *
     * 「在 App 里改完记录 → 按 Home 回桌面」是最常见的一条动线，而小部件自己
     * 是被闹钟驱动的（最多一分钟才轮到一次）。不在这里主动刷的话，用户回到桌面
     * 看到的是**上一分钟的样子**—— 明明刚改完，小部件还挂着旧事项，
     * 看着就像「改了没生效」。
     *
     * ⚠️ 为什么不放在「每次写操作之后」：那要改十几个方法，而漏掉任何一个，
     *    那种操作就永远不同步。「离开时刷一次」天然覆盖全部写操作 ——
     *    你总得离开 App 才看得见桌面。
     *
     * ⚠️ 也**不能**放进 `TimelineRepository.maintain()`：小部件自己刷新时
     *    也会走 maintain，那样就成了「刷新触发刷新」的死循环。
     */
    override fun onStop() {
        super.onStop()
        WidgetRefresh.now(this)
    }
}

@Composable
private fun MainScreen() {
    // 当前选中的一级页面。不持久化 —— 重启回到时间线是合理行为。
    var current by remember { mutableStateOf(MainTab.Timeline) }

    // 设置页内部的二级导航。切走再切回来会回到设置首页，免得停在上次那个编辑页里
    var settingsRoute by remember { mutableStateOf(SettingsRoute.Home) }

    // 管理页共用一个 ViewModel：它管的是全局的字典数据，不是某个页面的局部状态
    val catalogViewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory)

    // 时间线自己一个 —— 它管的是「正在看哪一天、这一天长什么样」，
    // 跟字典数据是两套状态，硬塞进一个 ViewModel 只会互相拖累
    val timelineViewModel: TimelineViewModel = viewModel(factory = TimelineViewModel.Factory)

    // 模板配置页。放在这一层（而不是在 Template 那个分支里创建）是因为
    // **设置首页那一行要显示它的摘要**（「工作日 · 运行中」）——
    // 进设置页就得先有这个状态，不能等点进模板页才去读。
    val templateViewModel: TemplateViewModel = viewModel(factory = TemplateViewModel.Factory)

    // 统计页。它管的是「在看哪个口径、哪个区间」，跟时间线/字典都是两套状态。
    val statsViewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)

    // 云同步（M4-B）。它只有「开关 / token / 上次结果」三个状态，跟别的页面无关。
    val syncViewModel: SyncViewModel = viewModel(factory = SyncViewModel.Factory)

    val context = LocalContext.current

    /* ★ 打开 App 时静默同步一次（M4-B）。
       ## 为什么是「打开时」而不是「每次写完」
       写操作散在时间线、模板、导入、悬浮窗十几处，逐个挂钩必然漏。
       而「打开 App」天然覆盖全部写操作之后的间隙 —— 你总得回到 App 才看得见数据。
       （和小部件那条「离开时刷一次」是同一个思路，方向相反而已。）
       ## 为什么延 1.2 秒
       冷启动那一刻要建库、装配时间线、渲染首屏，同步会跟它们抢 IO。
       等首屏出来再传，用户完全感知不到。
       ## 为什么 silent
       同步失败**不弹任何东西**。每次都弹一个红条，用户很快会把它当噪音，
       进而把同步整个关掉 —— 那才是真的损失。失败信息仍在设置页那一行里。 */
    LaunchedEffect(Unit) {
        delay(1200)
        syncViewModel.syncNow(silent = true)
    }

    // 手动同步的结果用 Toast 报一次（success/fail 都报，因为那是用户主动点的）
    LaunchedEffect(syncViewModel.notice) {
        val msg = syncViewModel.notice
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            syncViewModel.clearNotice()
        }
    }

    // ★ 系统返回键：在「设置 → 分类/事项/标签」里按返回，要退一层回设置首页，
    //   而不是直接退出 App。
    //
    //   不加这个的话，这些二级页就只是 Compose 里换了个 state，
    //   系统完全不知道存在「上一层」，返回键会走 Activity 默认行为 → App 直接退到后台。
    //   Android 用户的肌肉记忆是「返回 = 退一层」，被弹出去会非常突兀。
    //   （真机实测踩到：从「分类」按返回，前台直接变成了别的 App。）
    //
    //   enabled 只在需要拦的时候为 true —— 设置首页和另外两个 tab 上不拦，
    //   那时按返回就该是系统的「退出 App」。
    BackHandler(enabled = current == MainTab.Settings && settingsRoute != SettingsRoute.Home) {
        settingsRoute = SettingsRoute.Home
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = {
                            if (tab == MainTab.Settings && current == MainTab.Settings) {
                                // 已经在设置里还点设置 = 回到设置首页（一个很自然的手势）
                                settingsRoute = SettingsRoute.Home
                            } else {
                                // ★ 从别的 tab 回到时间线 → 重算一次。
                                //
                                //   因为时间线是按「打开那一刻」装配的，而你可能刚在
                                //   模板配置页改过段、或者在事项页删了一个事项 ——
                                //   那些改动都会改变「今天该铺什么」，但时间线自己不知道。
                                //
                                //   刷新链是幂等的（读一次库、算一次、写回差异），
                                //   所以多刷一次没有副作用，只是别让它变成每次点击都跑。
                                if (tab == MainTab.Timeline && current != MainTab.Timeline) {
                                    timelineViewModel.refresh()
                                }
                                current = tab
                                if (tab == MainTab.Settings) settingsRoute = SettingsRoute.Home
                            }
                        },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (current) {
                MainTab.Timeline -> TimelineScreen(vm = timelineViewModel)

                MainTab.Stats -> StatsScreen(vm = statsViewModel)

                MainTab.Settings -> when (settingsRoute) {
                    SettingsRoute.Home -> SettingsScreen(
                        categoryCount = catalogViewModel.categories.size,
                        itemCount = catalogViewModel.items.size,
                        bucketCount = catalogViewModel.buckets.size,
                        tagCount = catalogViewModel.tags.size,
                        templateLabel = templateSummary(templateViewModel),
                        onOpenCategories = { settingsRoute = SettingsRoute.Categories },
                        onOpenItems = { settingsRoute = SettingsRoute.Items },
                        onOpenTags = { settingsRoute = SettingsRoute.Tags },
                        onOpenTemplate = { settingsRoute = SettingsRoute.Template },
                        onOpenImport = { settingsRoute = SettingsRoute.Import },
                        syncEnabled = syncViewModel.enabled,
                        syncBaseUrl = syncViewModel.baseUrl,
                        syncToken = syncViewModel.token,
                        syncStatus = syncViewModel.status,
                        syncing = syncViewModel.busy,
                        onSyncToggle = { syncViewModel.toggle(it) },
                        onSyncBaseUrlChange = { syncViewModel.updateBaseUrl(it) },
                        onSyncTokenChange = { syncViewModel.updateToken(it) },
                        onSyncNow = { syncViewModel.syncNow(silent = false) },
                    )

                    SettingsRoute.Categories -> CategoryScreen(
                        vm = catalogViewModel,
                        onBack = { settingsRoute = SettingsRoute.Home },
                    )

                    SettingsRoute.Items -> ItemScreen(
                        vm = catalogViewModel,
                        onBack = { settingsRoute = SettingsRoute.Home },
                    )

                    SettingsRoute.Tags -> TagScreen(
                        vm = catalogViewModel,
                        onBack = { settingsRoute = SettingsRoute.Home },
                    )

                    SettingsRoute.Template -> TemplateScreen(
                        vm = templateViewModel,
                        onBack = { settingsRoute = SettingsRoute.Home },
                    )

                    SettingsRoute.Import -> {
                        // ★ 这个 ViewModel **只在这一支里创建**。
                        //   它一建出来就会解析那份 474KB 的内置 JSON ——
                        //   挂在外面（跟 templateViewModel 一样）等于每次冷启动都白解析一遍，
                        //   而用户可能几个月都不会点进这一页。
                        val importViewModel: ImportViewModel =
                            viewModel(factory = ImportViewModel.Factory)
                        ImportScreen(
                            vm = importViewModel,
                            onBack = { settingsRoute = SettingsRoute.Home },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设置页那一行的模板摘要 —— 「工作日 · 运行中」/「工作日 · 工作日」。
 *
 * ⚠️ 只显示一个：**在跑就说在跑**，没跑就说指派了哪几天。
 * 两个都写会太长，右边那个 `›` 会被挤掉。
 */
private fun templateSummary(vm: TemplateViewModel): String {
    if (!vm.exists) return "还没配"
    val state = if (vm.runOn) "运行中" else TemplateEdit.weekdaysShort(vm.weekdaysMask)
    return "${vm.name} · $state"
}
