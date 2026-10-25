package com.misaka.timelog.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.misaka.timelog.TimeLogApp
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.ui.sheet.PickerHost
import com.misaka.timelog.ui.theme.TimeLogTheme
import kotlinx.coroutines.launch

/**
 * 桌面悬浮选事项窗 —— **点小部件上那个 ■ 时浮出来的这一层**。
 *
 * ## 它凭什么算「悬浮」
 *
 * Activity 的主题是 `Theme.TimeLog.QuickSwitch`（窗口完全透明、无系统转场动画），
 * 所以启动的一瞬间**看不到任何「页面」**，只有 Compose 画的两层东西：
 * 一层薄薄的黑色遮罩（桌面暗下来）+ 底部那张六列彩色网格。
 * 关掉的时候窗口直接消失，桌面原样回来。
 *
 * 这正是旧版「时间日志」用惯的那个手感：**不进 App，就在桌面上切一下**。
 *
 * ## ★★ 为什么是「再抽一层界面」而不是「打开 App 然后弹面板」
 *
 * 御坂的原话是「点了就会在桌面弹悬浮下一个事项的悬浮窗口。我之前就一直这样用，
 * 很少进 app」。把主界面拉起来再弹面板，哪怕只闪一下，也已经「进了 App」——
 * 那一下的代价是他每次切换都要看着整个时间线重建一遍。
 *
 * ## ★★★ 这里一行业务逻辑都没有
 *
 * - 网格 = **同一个 `ItemPickerSheet`**（跟 App 里点「结束」弹的是同一个组件，
 *   颜色分组、六列、模板入口全都一致，不存在「两份界面」）
 * - 落库 = **同一个 `TimelineRepository.pick()`**（「结束上一件 + 开始新的 +
 *   退出模板」这套规则在数据层，这里一个字都不加）
 * - 刷新链 = **同一个 `TimelineRepository.maintain()`**
 *
 * 所以「从小部件切换」和「在 App 里点结束」在**数据层是同一件事**，
 * 不可能出现两边行为不一致。
 */
class QuickSwitchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 画到状态栏底下 —— 不然悬浮层顶部会留一条系统栏颜色的边，露馅
        enableEdgeToEdge()

        setContent {
            TimeLogTheme {
                val vm: QuickSwitchViewModel = viewModel(factory = QuickSwitchViewModel.Factory)

                if (vm.ready) {
                    PickerHost(
                        // 文案跟 App 里 `Sheet.Picker(StartNew)` 那一支保持一致
                        title = "选一件事开始记",
                        subtitle = vm.liveName?.let {
                            "现在这一刻开始记它，前一件「$it」会在此刻结束"
                        } ?: "现在这一刻开始记它",
                        categories = vm.categories,
                        items = vm.items,
                        onPick = { id -> commit(id) },
                        onDismiss = { finish() },
                        // 模板入口跟 App 里一样露出来：模板是「今天要不要交给它」的决定，
                        // 而御坂很少进 App —— 不放在这儿，他就没地方按了。
                        templateOffer = true,
                        templateOn = vm.tpl.on,
                        templateName = vm.tpl.name,
                        onTemplateClick = { turnOnTemplate() },
                    )
                } else {
                    // 字典还没读完（冷启动 + 开数据库，通常一两百毫秒）。
                    // ★ 先铺一层薄遮罩，让「点了」这件事立刻有视觉回应；
                    //   什么都不画的话，用户会以为没点中，然后再点一次。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                    )
                }
            }
        }
    }

    /**
     * 选了下一个事项 —— 结束现在这件、从此刻开始记它。
     *
     * ★★ 为什么用 `appScope` 而不是 `lifecycleScope`：
     *
     * 这个方法跑完就 `finish()`。挂在 Activity/ViewModel 上的协程会**跟着一起被取消**，
     * 于是「结束上一件」和「插入新一件」有概率只做了一半，而用户已经回桌面了。
     * 症状是**偶尔点了没反应**、且只在关得快的时候出现 —— 不报错、不崩、无法稳定复现。
     * `appScope` 不随任何界面消失，写完为止。
     */
    private fun commit(itemId: String) {
        val app = application as TimeLogApp
        val day = app.container.clock.now().dayKey

        app.appScope.launch {
            when (val out = app.container.timeline.pick(itemId, day)) {
                // 成功**不弹提示** —— 桌面小部件下一瞬就变成新那件事了，那本身就是反馈。
                // 每次切换都弹一句「已开始记「打工」」纯属噪音（一天要切十几次）。
                is Outcome.Ok -> Unit

                // 被规则拦下（比如「这个事项已经不在了」）才需要开口 ——
                // 这条消息是数据层给的中文，直接展示，界面一个字不改。
                is Outcome.Blocked -> toast(out.message)
            }
            // 别等下一分钟的闹钟 —— 立刻把桌面刷成新状态
            WidgetRefresh.now(app)
        }

        finish()
    }

    /** 切到模板模式。「配置 ≠ 启动」：配置好了什么都不发生，必须显式切一下。 */
    private fun turnOnTemplate() {
        val app = application as TimeLogApp

        app.appScope.launch {
            when (val out = app.container.timeline.startTemplate()) {
                is Outcome.Ok -> toastLong(
                    "已切到模板模式 · 之后的空白按模板自动记；点「结束」选别的事项即退出"
                )

                is Outcome.Blocked -> toast(out.message)
            }
            WidgetRefresh.now(app)
        }

        finish()
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun toastLong(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }
}
