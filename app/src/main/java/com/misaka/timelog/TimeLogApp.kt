package com.misaka.timelog

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用入口 —— 做两件事：把手写的依赖容器挂起来；提供一个**不随界面消失**的协程作用域。
 *
 * ⚠️ 必须在 `AndroidManifest.xml` 的 `<application>` 上用
 * `android:name=".TimeLogApp"` 注册，否则这个类根本不会被创建。
 */
class TimeLogApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * 应用级协程作用域。
     *
     * ## 为什么必须有它（M3-C 加的）
     *
     * 桌面悬浮选事项窗（`QuickSwitchActivity`）的用法是
     * **「点一件事 → 立刻把窗关掉」** —— 用户不希望每次切换都多看一眼动画。
     *
     * 但那个写操作如果用 `lifecycleScope` / `viewModelScope` 跑，
     * **一关窗就会被一起取消**：于是「结束上一件、开始记新的」有概率没写完，
     * 而用户已经回到桌面了。症状是**偶尔点了没反应**，
     * 而且只在关得快的时候出现 —— 属于最难查的那一类（不报错、不崩、无法稳定复现）。
     *
     * 挂在这里的协程**不随任何界面消失**，写完为止。
     *
     * ⚠️ 它只跑「用户已经明确点过」的那一下，不会自己冒出来做事 ——
     * 不要往这里塞定时任务、轮询之类的活儿（那些有自己的调度器，见 `WidgetAlarm`）。
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
