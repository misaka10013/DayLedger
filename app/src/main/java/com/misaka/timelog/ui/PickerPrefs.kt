package com.misaka.timelog.ui

import android.content.Context

/**
 * 选事项面板的**显示方式**记忆。
 *
 * ## 为什么这个开关要记住
 *
 * 御坂要的是「我习惯哪天就一直是哪天」：既然已经有颜色区分分类，
 * 那么去掉分类标题、让按钮紧凑成块，就是他长期想要的看板方式。
 * 如果每次打开面板都退回分组视图，这个开关就等于不存在（一天要开十几次）。
 *
 * ## 为什么不用 Room
 *
 * 它是**这个人怎么看这个面板**的偏好，不属于「时间日志」的业务数据。
 * 进 Room 就要为它加表、升库版本，而这张表全部内容就是一个布尔值。
 * 跟 [com.misaka.timelog.cloud.SyncPrefs] 同一个判断。
 *
 * ## 默认值 = 分组视图
 *
 * 不动默认值。聚合视图是**主动选择**的结果 —— 分组视图有分类标题，
 * 第一次用的人靠它建立「颜色 = 分类」的认知；等他熟了再自己关掉标题。
 */
class PickerPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("picker", Context.MODE_PRIVATE)

    /**
     * true = 聚合视图（无分类标题、块间更紧）。
     *
     * ⚠️ 只影响**画法**，不影响内容：两种视图里格子完全一样，
     * 点击行为也完全一样。所以它坏了最多是「排得难看」，不会是「记错事」。
     */
    var compact: Boolean
        get() = sp.getBoolean(KEY_COMPACT, false)
        set(value) = sp.edit().putBoolean(KEY_COMPACT, value).apply()

    private companion object {
        const val KEY_COMPACT = "compact_grid"
    }
}
