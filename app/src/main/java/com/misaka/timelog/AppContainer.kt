package com.misaka.timelog

import android.content.Context
import com.misaka.timelog.cloud.SyncEngine
import com.misaka.timelog.cloud.SyncPrefs
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.import.LegacyImport
import com.misaka.timelog.data.repo.CatalogRepository
import com.misaka.timelog.data.repo.StatsRepository
import com.misaka.timelog.data.repo.TemplateRepository
import com.misaka.timelog.data.repo.TimelineRepository
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.SystemClock
import com.misaka.timelog.ui.PickerPrefs

/**
 * 应用级依赖容器 —— **手写的依赖注入**。
 *
 * ## 为什么不引 Hilt
 *
 * Hilt 会带来一个注解处理器、几十个生成类、一套编译期检查。
 * 而这个 App 总共就 4 个需要共享的对象（数据库、时钟、两个仓库），
 * 手写一个容器 30 行就够了，编译更快、出问题更好查。
 *
 * 等依赖真的变多（云同步、小部件、前台服务都要进来）再考虑换也不迟。
 *
 * ## 时钟为什么在这里
 *
 * 它是**全局唯一**的「现在几点」来源。生产环境是 [SystemClock]；
 * 想在真机上演示「换一天看看」这类场景时，只要在这里换一个实现即可，
 * 其余代码一行都不用改 —— 这也是当初把时钟抽成接口的意义。
 */
class AppContainer(context: Context) {

    val clock: Clock = SystemClock()

    val db: TimeLogDatabase = TimeLogDatabase.get(context)

    val timeline: TimelineRepository = TimelineRepository(db, clock)

    val catalog: CatalogRepository = CatalogRepository(db, timeline)

    /**
     * 模板的**配置**（改段 / 改星期指派）。
     *
     * ⚠️ 模板的**运行**（启动、退出、铺写）在 [timeline] 里，不在这个仓库 ——
     * 「配置 ≠ 启动」那条铁律在数据层的落法就是这两个类。
     */
    val template: TemplateRepository = TemplateRepository(db, clock)

    /**
     * 统计的**只读**数据源。
     *
     * ⚠️ 它跟 [timeline] 是**反着**的：那个写 `records`、满脑子密铺和让位；
     * 这个一行都不写，只负责把素材一次捞齐。分开是为了让
     * 「统计页会不会动我的数据」这个问题，看类名就有答案。
     */
    val stats: StatsRepository = StatsRepository(db)

    /**
     * 旧版「时间日志」的历史数据导入。
     *
     * ⚠️ 它**自己不写 `records`** —— 真正的替换走 [timeline]（密铺不变式的唯一维护者），
     * 这个类只负责「读文件 + 把名字翻成 id」。见 `LegacyImport` 的职责表。
     */
    val legacyImport: LegacyImport =
        LegacyImport(db, timeline, context.applicationContext.assets)

    /**
     * 云同步的本地设置（开关 / token / 水位线 / 上次结果）。
     *
     * ⚠️ 它是**唯一**读写这几个值的地方 —— 界面只拿它当展示源，
     * 不自己碰 SharedPreferences，否则「开关显示开着但实际没开」这种
     * 不一致会永远查不出来。
     */
    val syncPrefs: SyncPrefs = SyncPrefs(context)

    /**
     * 选事项面板的显示方式（分组 / 聚合）。
     *
     * 单独一个类而不是塞进 [syncPrefs]：那个是「怎么连云端」，
     * 这个是「面板怎么排」。两件毫不相干的事混在一个 sharedPreferences 里，
     * 将来想清空云端配置会连带把界面偏好一起清掉。
     */
    val pickerPrefs: PickerPrefs = PickerPrefs(context)

    /**
     * 把本地记录推到云端 —— M4-B 的写通道。
     *
     * 与 [timeline] 的分工：那边是**权威源**（记录只在手机上产生），
     * 这边只是把成品搬一份出去，**从不改本地数据**。
     * 所以同步失败最多是「云端旧了」，绝不会坏掉你记的东西。
     */
    val syncEngine: SyncEngine = SyncEngine(db, clock, syncPrefs)
}
