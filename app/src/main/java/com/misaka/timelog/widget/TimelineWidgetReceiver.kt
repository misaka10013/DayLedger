package com.misaka.timelog.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 小部件的广播入口 —— 系统的「小部件被加了 / 该更新了 / 全都被删了」都落在这儿。
 *
 * ⚠️ `glanceAppWidget` 必须是 `val` 且**返回同一个实例**（Glance 内部靠它对应
 * 桌面上的实例，每次 new 一个会认不出来）。
 *
 * ## 闹钟的生命周期就挂在这儿
 *
 * - 第一个小部件被放到桌面 → 排上「每分钟刷新」
 * - 最后一个被删掉 → 停掉闹钟（不留一个每分钟醒一次的孤儿）
 *
 * ★ **重启手机也走 `onUpdate`** —— 系统开机后会重新广播一遍，
 * 于是闹钟自己就回来了。所以「重启后小部件不再跳动」这个坑，
 * 在这里顺手就堵上了，不必额外注册 `BOOT_COMPLETED`
 * （那是 M3-D 里「恢复进行中记录和模板模式」要干的事，跟这个不是一回事）。
 */
class TimelineWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TimelineWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.schedule(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // ⚠️ 重复排是安全的（同一个 PendingIntent 覆盖），别在这里加「排没排过」的判断 ——
        //    那种标记一旦跟真实状态不同步，小部件就永远不跳了。
        WidgetRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.cancel(context)
    }
}
