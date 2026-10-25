package com.misaka.timelog.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小部件什么时候刷新。
 *
 * ## 两种触发，缺一不可
 *
 * | 触发 | 干什么 | 为什么需要 |
 * |---|---|---|
 * | [now] | 写完记录/切完事项**立刻**刷 | 不然你点完「结束」回到桌面，小部件还挂着上一件事，像没点中 |
 * | 每分钟的闹钟 | 让小部件上「已持续 2时13分」自己往上走 | 「时长」是个**会自己变的数**，没人写库它也得变 |
 *
 * ## ⚠️ 为什么用「非精确」闹钟（`setInexactRepeating`）
 *
 * - **不需要 `SCHEDULE_EXACT_ALARM` 权限**。那个权限在 Android 12+ 要用户去设置里
 *   手动开，而且 13+ 默认不给新装应用 —— 为了一个小部件上跳动的数字去要它，
 *   代价太大（用户可能直接拒绝，然后小部件变成死的）。
 * - **不进 Doze 白名单**、不拿 wake lock —— 设备睡着的时候不唤醒它。
 *   这会儿小部件本来就看不见（屏幕黑着），刷新没有意义。
 *
 * 代价是：**息屏很久之后刚点亮的一瞬间，数字可能还是旧的**，
 * 要等下一个闹钟（最多一分钟）才追平。这是刻意的取舍 ——
 * 用「偶尔慢一分钟」换「不要权限、不偷电」。
 *
 * ⚠️ 另外 `appwidget-provider` 里的 `updatePeriodMillis` 也留着（30 分钟），
 * 作为「闹钟被系统清掉」时的兜底 —— 那是 Android 规定的最小值。
 */
object WidgetRefresh {

    /** 闹钟 tick 用的 action。自定义的（不是系统广播），所以静态注册没问题。 */
    const val ACTION_TICK = "com.misaka.timelog.action.WIDGET_TICK"

    /** 一分钟。小部件上显示的时长精确到分钟，所以这是它的天然节拍。 */
    private const val INTERVAL_MS = 60_000L

    /** 闹钟的身份证。同一份 PendingIntent 重复排 = 覆盖，不会堆起来。 */
    private const val REQUEST_TICK = 0x544C

    /**
     * 小部件刷新用的作用域。
     *
     * ⚠️ 不复用 `TimeLogApp.appScope`（那个在主线程，是给「用户点过的那一下」用的）。
     * 刷新是后台活儿，走 Default。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 请求刷新**所有**小部件实例。
     *
     * 调用点：写完记录、切换完事项、切了模板模式、改完字典。
     * ⚠️ 它是「发射后不管」的 —— 调用方不必等，也不该等。
     *
     * ## ★★ 为什么要「先读库、再改状态、最后推」（M3-C 验收 D 项挖出来的）
     *
     * 原来这里只有一句 `TimelineWidget().updateAll(app)`。实测发现它**经常什么都不做**：
     * 2~6ms 就返回，`provideGlance` 一次都没进 —— Glance 把上一帧的 RemoteViews
     * 原样重推了一遍。于是「在 App 里切完事项 → 回桌面」看到的还是上一件事，
     * 要等下一分钟的闹钟才纠正（最长 60 秒）。约七成是这种「假刷新」。
     *
     * 能保证触发重组的只有**状态变化**（组合里读了 `rev`，见 `TimelineWidget`）。
     * 但重组只重跑组合、不重跑 `provideGlance` —— 所以数据得由这里先读好塞进缓存。
     *
     * 三步缺一不可：
     * ① 读库（含四步刷新链）→ 结果进 `WidgetSnapshot` 缓存
     * ② `rev` +1（唯一的「重组触发器」）
     * ③ `updateAll` 把新树推给桌面
     */
    fun now(context: Context) {
        val app = context.applicationContext
        scope.launch {
            // ① 数据：跟打开 App、跟桌面悬浮窗走同一套刷新链（在 WidgetSnapshot 里）
            WidgetSnapshot.load(app)

            // ② 状态：每个实例都把 rev 改一下。⚠️ `rev` 不参与任何计算，
            //    它存在的意义就是「让组合依赖的值发生变化」。
            val ids = GlanceAppWidgetManager(app).getGlanceIds(TimelineWidget::class.java)
            ids.forEach { id ->
                updateAppWidgetState(app, PreferencesGlanceStateDefinition, id) { prefs ->
                    prefs.toMutablePreferences().also { it[WidgetSnapshot.REV] = System.currentTimeMillis() }
                }
            }

            // ③ 推给桌面
            TimelineWidget().updateAll(app)
        }
    }

    /**
     * 排上「每分钟刷新」。小部件第一次被放到桌面时调（见 `TimelineWidgetReceiver`）。
     *
     * ⚠️ 重复调用是安全的：同一个 [REQUEST_TICK] 的 PendingIntent 会被覆盖，不会堆闹钟。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // RTC（不是 ELAPSED_REALTIME）：跟着挂钟走。设备睡着时不会唤醒它 —— 这是想要的。
        am.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + INTERVAL_MS,
            INTERVAL_MS,
            tickIntent(app),
        )
    }

    /** 桌面上一个我们的小部件都不剩了 —— 把闹钟停掉，别白醒。 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(tickIntent(app))
    }

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_TICK,
        Intent(context, WidgetTickReceiver::class.java).setAction(ACTION_TICK),
        // ⚠️ FLAG_IMMUTABLE 是 Android 12+ 的硬要求（可变 PendingIntent 必须显式声明），
        //    这里本来就不需要改内容，直接 immutable。
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 「该刷新了」这个广播的落点 —— 什么都不判断，直接刷新。
 *
 * ★ 两个来源都落在这儿：**每分钟的闹钟**、以及代码里各处 `WidgetRefresh.now()`。
 *
 * ⚠️ 单开一个 receiver 而不是挂在 `TimelineWidgetReceiver` 上：
 * 那个是系统的 `AppWidgetProvider`，只认 `APPWIDGET_*` 那几个 action；
 * 往里塞私活会让「小部件被添加/删除」和「定时刷新」两件事糊在一起，不好查。
 */
class WidgetTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetRefresh.ACTION_TICK) return
        WidgetRefresh.now(context)
    }
}
