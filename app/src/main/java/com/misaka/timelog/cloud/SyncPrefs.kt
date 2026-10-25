package com.misaka.timelog.cloud

import android.content.Context

/**
 * 云同步的本地设置 —— 四个值，全塞在一个 SharedPreferences 里。
 *
 * ## 为什么不用 Room 存
 *
 * 这四项跟「时间日志」的业务数据没关系：它们是**这台设备怎么连云端**的配置。
 * 放进 Room 就要为它们加一张表、升一次库版本 —— 而这张表的全部内容
 * 就是「一个布尔 + 一个字符串 + 两个 Long」。
 *
 * ## ⚠️ token 是明文存的
 *
 * 它是「只写时间日志」的专用 token（不能读、不能碰别的集合），
 * 泄漏的后果是「别人能往你的时间日志里写数据」，不是「别人能看你的账本」。
 * 为这个风险去引 Jetpack Security 加一层加密，会让「同步为什么失败」这件事
 * 多出一个完全无关的可能原因（密钥库在部分国产 ROM 上有坑），不值当。
 * 真正的防线在服务端：**一键换掉 tl_writetoken/app 里的字符串，旧 token 立刻全废**。
 */
class SyncPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    /**
     * 同步总开关。**默认关**。
     *
     * 默认关不是保守，是「不给用户在不知情的情况下往外传数据」。
     * 打开这个开关的动作本身就是一次知情同意。
     */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 写 token（`tl_writetoken/app` 里的那一串）。 */
    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * 后端地址 —— **默认空**，必须自己填。
     *
     * ⚠️ 为什么不像别家 App 那样预置一个「官方服务器」：
     *   这个项目是自部署的，作者没有、也不该替你托管数据。
     *   填地址这件事本身就是「我知道我要把数据传到哪」的声明。
     *
     * 填一半也没关系：[SyncClient.normalizeBase] 会补斜杠和 `/api`。
     * 空着点【立即同步】会直接提示「还没有填服务器地址」，不会白发一个请求。
     */
    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_BASE_URL, value.trim()).apply()

    /**
     * 增量水位线：上一次**成功**同步的起始时刻（毫秒）。
     *
     * 下一轮只传 `updatedAt > watermark` 的天。
     * 0 = 还没同步过 → 走「首次同步」那条路（只补最近几十天）。
     *
     * ⚠️ 必须用**同步开始那一刻**的时间，不能用结束时刻 ——
     * 否则同步过程中新写的记录（时间戳落在开始与结束之间）会被下一轮漏掉。
     */
    var watermark: Long
        get() = sp.getLong(KEY_WATERMARK, 0L)
        set(value) = sp.edit().putLong(KEY_WATERMARK, value).apply()

    /** 上一次同步完成的时间（毫秒），0 = 从没成功过。 */
    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_AT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_AT, value).apply()

    /** 上一次同步的结果文案，直接显示在设置页那一行。 */
    var lastResult: String
        get() = sp.getString(KEY_LAST_RESULT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_RESULT, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "token"
        const val KEY_BASE_URL = "base_url"
        const val KEY_WATERMARK = "watermark"
        const val KEY_LAST_AT = "last_at"
        const val KEY_LAST_RESULT = "last_result"
    }
}
