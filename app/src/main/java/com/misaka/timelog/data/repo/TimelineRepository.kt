package com.misaka.timelog.data.repo

import androidx.room.withTransaction
import com.misaka.timelog.data.TimeLogDatabase
import com.misaka.timelog.data.entity.BackupSnapshotEntity
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.RecordTagEntity
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.data.entity.TemplateRunEntity
import com.misaka.timelog.data.import.RecordSnapshot
import com.misaka.timelog.data.newId
import com.misaka.timelog.domain.Clock
import com.misaka.timelog.domain.DayKey
import com.misaka.timelog.domain.Density
import com.misaka.timelog.domain.ImportDay
import com.misaka.timelog.domain.ImportReport
import com.misaka.timelog.domain.MINUTES_PER_DAY
import com.misaka.timelog.domain.Outcome
import com.misaka.timelog.domain.Overlap
import com.misaka.timelog.domain.SystemClock
import com.misaka.timelog.domain.TemplateEngine
import com.misaka.timelog.domain.TemplateLock
import com.misaka.timelog.domain.valueOrNull

/**
 * 时间线仓库 —— ★★ **全库唯一允许写 `records` 表的地方**。
 *
 * ## 为什么必须有这么一层
 *
 * 这个 App 的地基是一条不变式：
 *
 * > **同一天的记录必须首尾相接、完整覆盖 `[0, cut]`，一天恒 = 1440 分钟。**
 *
 * 这条不变式有个要命的特点 —— **破了它不会报错、不会崩**，只会在几个月后
 * 让统计数字对不上，而且那时候已经查不出是哪一次操作弄破的。
 *
 * 所以规矩是：**任何对记录的写，都必须经过这一层**。分散在界面里各写各的，
 * 迟早会有一处忘了补空白，于是那天就永远缺了一块。
 *
 * ## 三条铁律怎么落在这里
 *
 * | 铁律 | 落法 |
 * |---|---|
 * | **缝隙可见** | 空白一律具象成一条 `source = GAP` 的**真实记录**，有自己的 id、参与统计 |
 * | **删除 = 原地改写** | [deleteRecord] 从不 `DELETE` —— 它把那一条 `UPDATE` 成 GAP。真空掉行会在密铺里留一个洞 |
 * | **正在进行的只有今天** | [pick] 和 [closeLive] 进门第一件事就是核对「这天是不是今天」（第 19 轮那个「昨天凭空长出 24 小时记录」的 bug 就出在这上面） |
 *
 * ## 时钟是注入的
 *
 * 所有「现在几点」都走 [clock]。生产传 [SystemClock]，测试传一个固定在
 * 「今天 14:30」的假时钟 —— 否则「今天记到一半」这条最容易出 bug 的路径根本没法测。
 */
class TimelineRepository(
    private val db: TimeLogDatabase,
    private val clock: Clock = SystemClock(),
) {

    private val records = db.recordDao()
    private val items = db.itemDao()
    private val tags = db.tagDao()
    private val templates = db.templateDao()
    private val backups = db.backupDao()

    // ------------------------------------------------------------------ 读

    /** 某天的全部记录，按开始时间排好。 */
    suspend fun dayRecords(day: String): List<RecordEntity> = records.byDay(day)

    /** 某天「正在进行」的那条。⚠️ 只有今天可能有。 */
    suspend fun liveRecord(day: String): RecordEntity? = records.liveOn(day)

    /**
     * 某个区间内所有天的记录（`[from, to)`）。
     *
     * 目前给周条用 —— 一次把一周捞回来，在内存里判断「哪几天有空白」，
     * 而不是循环 7 次查 7 遍。
     *
     * ⚠️ 它**不补密**（补密是按天做的，有明确的目标日）。所以拿到的只能反映
     * 「已经落库的空白」，不代表「这一天补密后一定有空白」——
     * 这正是周条上「今天」那一格要单独用实时值判断的原因。
     */
    suspend fun recordsBetween(from: String, to: String): List<RecordEntity> =
        records.byDayRange(from, to)

    /**
     * `[from, to)` 之间**真的有记录**的天 —— 月历上的小圆点。
     *
     * 只回 `'2026-10-01'` 这种键，界面拿去做集合比对，不关心里面有多少条。
     * 「什么叫有记录」的口径写在 `RecordDao.daysWithContent` 上（排除 GAP）。
     */
    suspend fun daysWithContent(from: String, to: String): List<String> =
        records.daysWithContent(from, to)

    /** 哪些天里存在「未记录」的空白块 —— 月历上的小黄点。 */
    suspend fun daysWithGap(from: String, to: String): List<String> =
        records.daysWithGap(from, to)

    /**
     * 一次取回多条记录各自的标签。
     *
     * 时间线上一天十几条记录，每条都去查一次标签就是十几趟查询（N+1），
     * 在真机上会变成肉眼可见的卡顿。所以这里一次拿关系表、一次拿字典、内存里拼。
     *
     * @return 记录 id → 它的标签（顺序按标签名字典序，稳定）。没有标签的记录不出现在 Map 里。
     */
    suspend fun tagsOn(recordIds: List<String>): Map<String, List<TagEntity>> {
        if (recordIds.isEmpty()) return emptyMap()

        val refs = tags.refsOfRecords(recordIds)
        if (refs.isEmpty()) return emptyMap()

        val byId = tags.all().associateBy { it.id }
        return refs
            .groupBy({ it.recordId }, { it.tagId })
            .mapValues { (_, ids) -> ids.mapNotNull { byId[it] }.sortedBy { it.name } }
    }

    /** 某天该铺到哪（今天 = 此刻｜过去 = 1440｜未来 = 0）。 */
    suspend fun cutOf(day: String): Int = Density.cutFor(day, clock.now())

    /**
     * 自检：这天密铺了吗？
     *
     * **空列表 = 没问题。** 生产代码里不调它（有开销），测试里天天调 ——
     * 它就是这套设计的「断言」。
     */
    suspend fun verify(day: String): List<Density.Problem> =
        Density.check(records.byDay(day), cutOf(day))

    // -------------------------------------------------- ★★ 密铺维护（地基）

    /**
     * 把某一天补到密铺 —— **只补空白，绝不覆盖你记过的**。
     *
     * ## 挂在唯一的刷新入口第一步
     *
     * 顺序是 `ensureDense()` → `applyTemplate()`，**反了会打架**：
     * 模板铺过的段会被当成「缝」，又补一条空白上去。
     *
     * ## 两个细节，写错都会出问题
     *
     * 1. **尾部空白是「延长」不是「新插」** —— 现在 14:30，尾部空白是 `[870, 870)` 即空。
     *    但如果每刷新一次就新插一条 1 分钟的空白，一天下来会攒出几百条碎片。
     *    所以尾部那条如果本来就是 GAP，直接把它延长到此刻。
     * 2. **未来不铺** —— `cut == 0` 时一条都不动。未来的时间还没发生，
     *    铺上空白等于凭空断言「你那时什么都没干」。
     *
     * @return 补了几段空白（0 = 本来就密铺）
     */
    suspend fun ensureDense(day: String): Int = db.withTransaction {
        val now = clock.now()
        val ts = now.epochMillis

        // ① 自愈：非今天不该有「正在进行」的记录。
        //    这是第 19 轮那个 bug 的残留物 —— 出现了就说明历史上有过写入漏洞，
        //    与其让它继续污染，不如就地封口。
        if (day != now.dayKey) {
            records.liveOn(day)?.let { stale ->
                records.closeRecord(stale.id, Density.cutFor(day, now), ts)
            }
        }

        val cut = Density.cutFor(day, now)
        if (cut <= 0) return@withTransaction 0 // 未来：一条都不铺

        val list = records.byDay(day)
        val gaps = Density.gapRanges(list, cut)
        if (gaps.isEmpty()) return@withTransaction 0

        val tail = list.lastOrNull()
        val inserts = mutableListOf<RecordEntity>()

        gaps.forEach { gap ->
            // ⚠️ 用 start / endInclusive，不是 first / last ——
            //    gapRanges 声明返回的是 List<ClosedRange<Int>>，而 ClosedRange 上
            //    只有 start / endInclusive（first / last 是 Iterable 才有的）
            val from = gap.start
            val to = gap.endInclusive + 1
            // ⚠️ ★ noTemplate 的尾部空白**不许延长**（2026-10-19 真机事故）。
            //
            // 那个标记的意思是「这**几分钟**我不想要模板填」（删记录 / 删事项擦出来的）。
            // 一旦允许延长，它就会跟着「此刻」一起往前爬 ——
            // 御坂中午删掉一小段，到下午就变成了「模板运行中，但整个下午都是未记录」，
            // 而模板本身完全正常、日志里一个错都没有。
            //
            // 正确做法：让受保护的那段**冻在原处**，从它的终点另起一条干净的空白。
            // 于是「保护 3 分钟」就真的只保护 3 分钟，之后的时间照常归模板管。
            val tailIsThisGap = tail != null &&
                tail.source == RecordSource.GAP &&
                !tail.noTemplate &&
                tail.endMin == from &&
                to == cut
            if (tailIsThisGap) {
                // 尾部空白：延长，别新插（否则碎片化）
                records.update(tail.copy(endMin = to, updatedAt = ts))
            } else {
                inserts += gapRecord(day, from, to, ts)
            }
        }
        if (inserts.isNotEmpty()) records.insertAll(inserts)

        gaps.size
    }

    // ============================================================ ★★ 模板（M2-C）

    /**
     * 界面需要的模板状态快照。
     *
     * `locked` 也带上 —— 设置页那行要能显示「运行中」，而这只有查库才知道；
     * 界面**不许自己判断**「今天该不该跑」（那个判据只有一个定义处，见 [DayKey.coversWeekday]）。
     */
    data class TemplateState(
        /** 模板在不在跑（`template_run.isOn`）。 */
        val on: Boolean,
        /** 模板名。没有模板时是空串。 */
        val name: String,
        /** 今天被指派到了吗。跑得起来的前提。 */
        val coveredToday: Boolean,
        /** 有没有配过模板（配过但没启用也是 true）。 */
        val exists: Boolean,
    )

    /**
     * ★★ 一套固定的刷新链 —— **所有入口共用这一个**，顺序一个字都不能动。
     *
     * ```
     *  ① syncTemplateDay —— 「过了 24:00，这天还跑不跑模板」。
     *                       必须在补密之前定下来，因为它决定了 ③④ 要不要干活。
     *  ② carryOver       —— 「昨晚那件事没按结束，要不要续到今天」。
     *                       ⚠️ 也必须在 ③ 之前：`ensureDense` 会把「非今天的进行中
     *                       记录」就地封口。一封信封下去，接续的依据（那条 live）
     *                       就再也找不回来了 —— 而且不报错，表现是「时灵时不灵」。
     *  ③ ensureDense     —— 把 `[0, cut]` 补到密铺（空白具象成 GAP 记录）。
     *  ④ applyTemplate   —— **只往空白里填**，绝不覆盖你手动记的。
     *                       ⚠️ 跟 ③ 反过来会打架：模板铺过的段会被当成「缝」，
     *                       又补一条空白上去 —— 同一段时间被两条记录占着，密铺当场破。
     * ```
     *
     * ## ★ 为什么必须有这个函数，而不是各处抄这四行
     *
     * 这四行原本写在 `TimelineViewModel.reload()` 里 —— 那时只有「打开 App」
     * 一个入口。M3-C 一次加进来两个：**桌面小部件刷新**、**桌面悬浮选事项窗**。
     * 三处各抄一遍的话，将来谁改了一处顺序、或加了第 ⑤ 步，
     * 另外两处**不会报错、不会崩**，只在真机上表现成「模板有时候不生效」——
     * 属于这个项目里最贵的那一类 bug（见记忆里的「唯一入口」条）。
     *
     * ⚠️ 顺序约束是**真实存在**的（② 必须在 ③ 前、③ 必须在 ④ 前），不是风格问题，
     * 所以它只能有一个定义处。要加步骤，改这里。
     *
     * @param day 要维护的那一天 —— **用户正看着的那天**（可能是过去），不必是今天。
     */
    suspend fun maintain(day: String) {
        syncTemplateDay()
        carryOver()
        ensureDense(day)
        applyTemplate(day)
    }

    /** 当前模板状态 —— 纯读，顺手把「跨天了 / 今天没指派」这些收尾掉。 */
    suspend fun templateState(): TemplateState {
        val t = templates.template()
        val run = templates.run()
        val today = clock.now().dayKey
        return TemplateState(
            on = run?.isOn == true,
            name = t?.name.orEmpty(),
            coveredToday = t != null && DayKey.coversWeekday(t.weekdaysMask, today),
            exists = t != null,
        )
    }

    /**
     * ★★ 跨天守卫 —— 挂在刷新链**最前面**，在 [carryOver] 和 [ensureDense] 之前。
     *
     * 规则（M2-D 定稿，两句话）：
     *
     * 1. **没跨天 → 什么都不做**
     * 2. **跨天了 → 把「跑在哪一天」挪到新的一天。运行态保持开着，
     *    不管新的一天有没有被指派。**
     *
     * ## ⚠️ 这里推翻了 M2-C 的一条老规矩（2026-10-15 御坂裁定）
     *
     * 老规矩是「进入没指派的一天 → **自动退出**」。御坂当场否掉：
     *
     * > 「模板只设置了工作日……到了休息日，因为用户没有设定休息日的模板内容，
     * >  **但用户也没有退出模板**，所以休息日还是运行在模板模式中，
     * >  并且**全部按照空白时间块进行记录**。」
     *
     * 为什么这个改动是对的：周五晚上你切了模板，周六周日它自己悄悄关掉 ——
     * 那**周一你还得记得手动再开一次**。一个小小的静默退出，代价是每周一多一道手续。
     * 而保持开启、只是「今天不铺」，效果一样（休息日全空白），却不用你惦记。
     *
     * ⚠️ 两条都**零提示零弹窗**。睡觉本来就有 8–9 小时，天天弹一句
     * 「要不要继续用模板」等于天天给你一道噪音题 —— 御坂明确不要。
     *
     * ⚠️ 顺序不能挪到 [ensureDense] 后面：`ensureDense` 里那条「非今天不该有进行中记录」
     * 的自愈，跟模板的跨天判断是**同一件事的两面**，先让模板把「这天还跑不跑」定下来，
     * 补密才知道该按什么铺。
     */
    suspend fun syncTemplateDay() {
        val run = templates.run() ?: return
        if (!run.isOn) return

        val now = clock.now()

        // 没跨天 → 什么都不用做
        if (run.day == now.dayKey) return

        // 跨天了 → 只挪「跑在哪一天」。
        // ★ 锁定段顺手清掉：它的语义是「**启用那一刻**正在执行的那一段」，
        //   跨天说明那一段铁定跑完了（24:00 就是它的终点）。
        //   不清的话会出这个 bug —— 今天 cut 才几十，`cut > lock.endMin` 判不出来，
        //   于是今天一整天都在拿【昨天】的旧段铺，而你改的模板一点不生效。
        templates.putRun(run.copy(day = now.dayKey, lockedSegSnapshot = null))
    }

    /**
     * ★★★ 跨天接续（`CARRY`）—— 挂在 [syncTemplateDay] 之后、[ensureDense] **之前**。
     *
     * ## 它在回答什么
     *
     * > 昨晚 23:50 你开始记「看小说」，然后就睡了，一直没按「结束」。
     * > 今天打开 App —— 从零点到现在这段时间，该记成什么？
     *
     * 答案（v1.8 定稿的跨天规则 + M2-D 御坂复述）：
     *
     * > 「跨天就正常结算，然后自动接续记录就行。**如果我几天都忘记打开，
     * >  那就连续几天接续同一件事情就行。** 我看得到大段的事件异常，会自己去处理。」
     *
     * 所以是：昨天那条在 `24:00` 收尾，**次日 `00:00` 续上同一件事，缝为 0**。
     * ⚠️ **不是**「次日从『未记录』重新开始」—— 那等于把一件连续的事硬生生截成两件，
     * 而且「昨晚看到几点」这个信息就丢了。
     *
     * ## ⚠️ 为什么必须排在 [ensureDense] **之前**（这条最容易写错）
     *
     * `ensureDense` 里有一条自愈：**非今天的「进行中」记录会被就地封口**。
     * 那条自愈先跑的话，这里就永远查不到「昨晚那条还在进行中」了 ——
     * 接续的依据被它自己抹掉。而且**不报错、不崩**，只是安静地不接续，
     * 你要等到某天发现「昨晚的事怎么没接上」才会察觉。
     *
     * ## 只续「你自己记的」
     *
     * | 昨晚末尾是 | 接不接 | 为什么 |
     * |---|---|---|
     * | 手动记的（`MANUAL`） | ✅ 接 | 「看小说」还是「看小说」 |
     * | 之前接续来的（`CARRY`） | ✅ 接着接 | 一条链可以连着好几天不断 |
     * | 空白（`GAP`） | ❌ 不接 | 空白没有「同一件事」可续 |
     * | 模板铺的（`TEMPLATE`） | ❌ 不接 | **模板自己会铺今天**，两边一起动手会打架 |
     *
     * ⚠️ 判据写成**白名单**（只认 `MANUAL` / `CARRY`）而不是黑名单：
     * 将来加新的 `source` 时默认**不接续**，比默认接续安全得多。
     *
     * ## 幂等 —— 刷新多少次都只接一次
     *
     * 判据就一条：**「还有没有在进行中的、不属于今天的记录」**。
     * 接续完那条立刻被封口到 `24:00`，第二次进来看见的已经是「已结束」。
     * 所以不需要额外的「接过了」标记 —— 少一个能对不上的状态。
     *
     * ## 顺带：跨天的那条 live，不管接不接，**一律先结算**
     *
     * 原来「昨晚那条还挂着进行中」要等你**翻到昨天**才被 [ensureDense] 的自愈修好。
     * 既然这里已经把它查出来了，就顺手收尾 —— 一句 `UPDATE` 的事，
     * 却省掉一个「10-14 那条怎么显示成记到后天了」的怪现象。
     *
     * @return 真的续上了吗（`false` = 没什么可接的）
     */
    suspend fun carryOver(): Boolean = db.withTransaction {
        val now = clock.now()
        val today = now.dayKey
        val ts = now.epochMillis

        val live = records.lastLive() ?: return@withTransaction false
        if (live.day >= today) return@withTransaction false // 就是今天在记的那条，照常

        // 「你自己记的」才接续（白名单：将来加新 source 时默认不接，比默认接安全）
        val carryable = live.itemId != null &&
            (live.source == RecordSource.MANUAL || live.source == RecordSource.CARRY)

        // ① 跨天结算：那条**就地收尾到 24:00**（原地 UPDATE，一行都不删）。
        //    ⚠️ 是 1440 不是 1439 —— 写 1439 会凭空丢 1 分钟，
        //    而这一分钟会以「那天的总时长少了一分钟」的形式永远留在库里。
        records.closeRecord(live.id, MINUTES_PER_DAY, ts)

        // 模板铺的 / 空白 → 只结算，不接续（模板自己会管今天；空白没有「同一件事」）
        if (!carryable) return@withTransaction false

        // ② 一条链用同一个 chainId 串起来（V1 只在数据里存着，界面不显示）
        //
        // ⚠️⚠️ 这里的 `endMin = MINUTES_PER_DAY` **不能省**。
        //    `@Update` 是**整行覆盖**，拿 `live.copy(...)`（它的 `endMin` 还是 `null`）
        //    去写，会把上面刚结算好的 1440 又擦回「进行中」——
        //    症状特别阴：接续看着"成功"了，但**昨天那条永远停在进行中**，
        //    于是你翻回昨天会看见一件 24 小时还没结束的事。
        val chain = live.chainId ?: newId()
        if (live.chainId == null) {
            records.update(
                live.copy(endMin = MINUTES_PER_DAY, chainId = chain, updatedAt = ts)
            )
        }

        // ③ 从它的次日一路续到今天。
        //    中间隔了好几天也照续 ——「几天没开 App 就连续几天接续同一件事」是定稿的规矩。
        //    御坂：【原话】「我看得到大段的事件异常，会自己去处理。」
        var day = DayKey.plus(live.day, 1)
        var wrote = 0
        while (day <= today) {
            // 这一天已经有东西了 → 说明你那天其实开过 App，别拿接续盖掉真记录
            if (records.countOn(day) == 0) {
                records.insert(
                    RecordEntity(
                        id = newId(),
                        day = day,
                        startMin = 0,
                        // 今天那条是**进行中**的 —— 底部才会显示「看小说 · 已持续 8时10分」，
                        // 而且点「结束」能当场收尾。中间那些天则是整天记满。
                        endMin = if (day == today) null else MINUTES_PER_DAY,
                        itemId = live.itemId,
                        categoryIdSnap = live.categoryIdSnap,
                        source = RecordSource.CARRY,
                        chainId = chain,
                        // ⚠️ 不带备注：原备注是用户给【昨晚那一条】写的，
                        //    硬粘到今天这一条上会变成一句张冠李戴的话
                        note = "",
                        noTemplate = false,
                        updatedAt = ts,
                        deleted = false,
                    )
                )
                wrote++
            }
            day = DayKey.plus(day, 1)
        }
        wrote > 0
    }

    /**
     * ★ 切到模板模式 —— 「结束」面板左下角那个按钮。
     *
     * ## 为什么入口在「结束」面板里，而不是设置页
     *
     * 因为**模板是一种「今天要不要交给它」的决定，不是一种全局设置**。
     * 它天天要用、而且用的时机就是「我该睡了/我该上班了，接下来照着规律走」——
     * 那个时机你手正放在「结束」按钮上。藏到设置里等于每天都得多走三步。
     *
     * ## 三件必须按顺序做的事
     *
     * 1. **先结束此刻正在记的那条** —— 原地截断到此刻（不吞并邻居、不留缝）。
     *    截出来是零长度（同一分钟内点开又点）就把它改写成零长度空白：
     *    ⚠️ **不能 `DELETE`**，那是「删除一律原地改写」那条铁律。
     * 2. **锁住「此刻所在的那一段」** —— 运行中你要是去改了模板，
     *    这一段照样按【旧的】跑完，跑完之后才采用新模板。
     * 3. 写运行态。**铺写不在这里做** —— 它由 [applyTemplate] 在刷新链里统一负责，
     *    这样「启动」和「日常每分钟的刷新」走的是同一条路，只有一份代码。
     */
    suspend fun startTemplate(): Outcome<Unit> {
        val now = clock.now()
        return db.withTransaction {
            val t = templates.template()
                ?: return@withTransaction Outcome.Blocked("还没有模板。")

            if (!DayKey.coversWeekday(t.weekdaysMask, now.dayKey)) {
                return@withTransaction Outcome.Blocked(
                    "今天是周${DayKey.weekdayCn(now.dayKey)}，模板没指派到这一天 —— " +
                        "先去「设置 → 生活规律模板」把它勾上。"
                )
            }

            val cut = Density.cutFor(now.dayKey, now)
            val ts = now.epochMillis

            // ① 结束此刻正在记的那条
            records.liveOn(now.dayKey)?.let { live ->
                if (cut - live.startMin >= 1) {
                    records.closeRecord(live.id, cut, ts)
                } else {
                    // 零长度：它不代表任何一段时间。原地擦成空白（不删行）
                    records.update(
                        live.copy(
                            endMin = cut,
                            itemId = null,
                            categoryIdSnap = null,
                            source = RecordSource.GAP,
                            note = "",
                            noTemplate = false,
                            updatedAt = ts,
                        )
                    )
                    tags.detachAllFromRecord(live.id)
                }
            }

            // ② 清掉「紧贴此刻的那条空白」上的 noTemplate 标记。
            //
            // 这是给「模板看起来开着却一直不铺」留的一条自救路径（2026-10-19）。
            // 那个标记的来历是「你删过某一段，那几分钟别填」，但用户点启用模板时
            // 表达的是**从现在起照模板来** —— 两句话不冲突，后者应该覆盖前者。
            //
            // ⚠️ 只清 endMin == cut 的那一条（紧贴此刻的尾部空白），
            //    不去翻今天更早的空白：那些是用户对**已经过去的时间**的态度，不该被这次启用推翻。
            records.byDay(now.dayKey)
                .lastOrNull()
                ?.takeIf {
                    it.source == RecordSource.GAP && it.noTemplate && it.endMin == cut
                }
                ?.let { records.update(it.copy(noTemplate = false, updatedAt = ts)) }

            // ③ 锁住此刻所在的那一段（用编码后的字符串存，见 TemplateLock）
            val lock = TemplateEngine.segAt(
                min = cut,
                segs = resolvedSegments(t.id),
            )

            templates.putRun(
                TemplateRunEntity(
                    isOn = true,
                    day = now.dayKey,
                    lockedSegSnapshot = lock?.let { TemplateLock.encode(it) },
                )
            )
            Outcome.Ok(Unit)
        }
    }

    /**
     * 退出模板记录。之后新出现的空白不再自动填 —— **已经按模板记下的那些会留着**。
     *
     * ⚠️ 光把开关关掉是不够的：模板在跑时末尾那条挂着「进行中」（见 [applyTemplate] 末尾）。
     * 不就地收尾的话，模板明明关了，底部却还显示「打工 · 已持续 1时12分」——
     * 看着像「关了没关上」。收尾之后新出现的时间会走 [ensureDense] 变成「未记录」，
     * 正是「之后的时间你自己记」的意思。
     */
    suspend fun stopTemplate() {
        val now = clock.now()
        db.withTransaction {
            records.liveOn(now.dayKey)
                ?.takeIf { it.source == RecordSource.TEMPLATE }
                ?.let { records.closeRecord(it.id, Density.cutFor(it.day, now), now.epochMillis) }
            templates.putRun(runOff())
        }
    }

    /**
     * ★★★ 懒结算铺写 —— 挂在 [ensureDense] **之后**，顺序反了会打架。
     *
     * 反了会怎样：模板铺过的段会被 [ensureDense] 当成「缝」，又给它补一条空白上去，
     * 于是同一条时间被两条记录同时占着 —— 密铺当场破掉。原型第 17 轮专门标过这一句。
     *
     * ## 「懒结算」到底懒在哪
     *
     * **只补写已经过去的时间、不预写未来，也不依赖后台保活。**
     * App 被系统杀掉三天，重开时照样按分钟精确补上 —— 因为「该铺什么」是
     * 从「现在几点」和「你这天的记录」现算出来的，不需要有个进程一直在后台跑。
     *
     * 这是这个设计里最省电、也最不容易出鬼故事的一条：**没有常驻的定时器，
     * 就没有「定时器在后台被冻住、结果少铺了一段」这种问题**。
     *
     * ## ★ 今天没被指派 → 今天不铺（M2-D）
     *
     * 模板只排了「工作日」，周六周日你也没退出模板 —— 那这两天就是**什么都不铺**，
     * 全按空白块记。**运行态保持开着**，周一它会自己接着铺，不用你重新启用。
     *
     * ⚠️ 这个判断**必须在这里**，不能只靠 [syncTemplateDay]：后者只在「跨天那一刻」
     * 动手，而「今天是没指派的一天」是个**每天都成立**的事实，每次刷新都得判一次。
     *
     * @return 改写了几段（0 = 没什么可补的）
     */
    suspend fun applyTemplate(day: String): Int {
        val run = templates.run() ?: return 0
        // ⚠️ 模板只对「它跑的那一天」负责。翻看别的日子时不该铺写。
        if (!run.isOn || run.day != day) return 0

        val t = templates.template() ?: return 0
        if (!DayKey.coversWeekday(t.weekdaysMask, day)) return 0

        val now = clock.now()
        val cut = Density.cutFor(day, now)
        if (cut <= 0) return 0

        val lock = TemplateLock.decode(run.lockedSegSnapshot)

        return db.withTransaction {
            val rows = records.byDay(day)
            val plan = TemplateEngine.settle(
                spans = rows.map { r ->
                    TemplateEngine.Span(
                        id = r.id,
                        startMin = r.startMin,
                        endMin = r.endMin ?: cut, // 进行中那条：按「铺到此刻」看待
                        fillable = r.fillableByTemplate(),
                    )
                },
                segs = resolvedSegments(t.id),
                lock = lock,
                cut = cut,
            )

            val ts = now.epochMillis
            plan.rewrites.forEach { rw ->
                val origin = rows.firstOrNull { it.id == rw.originId } ?: return@forEach

                // 第一块**沿用原记录**（原地改写，行不动）——
                // 这是「删除一律原地改写、禁止 filter」那条铁律在模板上的同一套做法
                val head = rw.blocks.first()
                records.update(
                    origin.copy(
                        startMin = head.from,
                        endMin = head.to,
                        itemId = head.itemId,
                        categoryIdSnap = head.categoryId,
                        source = head.sourceOf(),
                        // 模板铺出来的段不带备注：原备注是用户给【那一条】写的，
                        // 现在这一条被切成了几块，留在头上会让「备注」挂到不相关的时间上
                        note = if (head.itemId == null) "" else origin.note,
                        noTemplate = false,
                        updatedAt = ts,
                    )
                )

                // 其余的才新插
                rw.blocks.drop(1).forEach { b ->
                    records.insert(
                        gapRecord(day, b.from, b.to, ts).copy(
                            itemId = b.itemId,
                            categoryIdSnap = b.categoryId,
                            source = b.sourceOf(),
                        )
                    )
                }
            }

            // ★ 锁定段跑完了 → 清掉，此后一律读最新模板
            if (plan.lockExpired) {
                templates.putRun(run.copy(lockedSegSnapshot = null))
            }

            // ★ 模板在跑 → 末尾那一段继续保持「正在进行中」。
            //   两个理由：① dock 上才会显示「模板正在替你记睡觉」而不是「空闲中」
            //            ② 它就是「点结束 → 选别的事项」那个退出动作的入口，
            //              没有它就没有任何地方能退出模板
            //   ⚠️ 只认 `TEMPLATE`：万一末尾那段填不上（模板指的事项被删光了），
            //      就让它老实待着当空白，不假装在计时。
            records.byDay(day).lastOrNull()
                ?.takeIf { it.source == RecordSource.TEMPLATE && it.endMin == cut }
                ?.let { records.update(it.copy(endMin = null, updatedAt = ts)) }

            plan.rewrites.size
        }
    }

    /**
     * 模板铺出来的这一段，该记成什么来源。
     *
     * ⚠️ 填不上的块（事项被删）**必须落回 `GAP`**，不能留成 `TEMPLATE` ——
     * 后者会是一条「名字还挂着、点进去什么都不是」的幽灵记录，统计里还算它一份。
     */
    private fun TemplateEngine.Block.sourceOf(): RecordSource =
        if (itemId != null) RecordSource.TEMPLATE else RecordSource.GAP

    /**
     * 这一条**允许被模板重新结算**吗。
     *
     * ★★ 这是 M2-C 最容易被写错的一处，把话说明白：
     *
     * | 这条是什么 | 能不能动 | 为什么 |
     * |---|---|---|
     * | 空白（`GAP`）、没被手动清过 | ✅ 能填 | 这就是模板要干的活 |
     * | 模板自己铺的、**还正在进行中**的那条 | ✅ 能重切 | 它还在长，随时要按段边界切开 |
     * | 模板自己铺的、已经结束的 | ❌ 不动 | 上一次刷新就切对了，重算只会白改 `updatedAt` |
     * | 手动记的（`MANUAL`） | ❌ 不动 | **模板是兜底，不是命令** |
     * | 手动删记录清出来的空白（`noTemplate`） | ❌ 不动 | 不然你看着就像「删不掉」 |
     * | 跨天接续的（`CARRY`） | ❌ 不动 | 它是「昨晚那件事还在继续」，不是默认值 |
     *
     * ⚠️ 第二行是真机版**比原型多出来**的一条。原型每次把整个数组重建一遍，
     * 所以「模板自己铺的那条会不会被再切一次」根本不是问题；
     * 真机按行存就必须写明 —— 漏了它，「午休」会从 12:00 一路滚到 20:00 都切不开。
     */
    private fun RecordEntity.fillableByTemplate(): Boolean = !noTemplate && when {
        source == RecordSource.GAP -> true
        source == RecordSource.TEMPLATE && endMin == null -> true
        else -> false
    }

    /** 模板的段 → 引擎要的形状（顺手把事项和它的分类查出来）。 */
    private suspend fun resolvedSegments(templateId: String): List<TemplateEngine.Seg> =
        templates.segments(templateId).map { s ->
            // ⚠️ 双保险：段上挂了 id、但字典里查不到（外键没拦住的历史脏数据）也要当「没有」，
            //    否则会铺出一条点不进去的幽灵记录
            val item = s.itemId?.let { items.byId(it) }
            TemplateEngine.Seg(
                startMin = s.startMin,
                endMin = s.endMin,
                itemId = s.itemId?.takeIf { item != null },
                categoryId = item?.categoryId,
            )
        }

    /** 「没在跑」的运行态。三处地方要写它，抽出来免得漏掉某一列。 */
    private fun runOff() = TemplateRunEntity(isOn = false, day = null, lockedSegSnapshot = null)

    // ------------------------------------------------------------ 记录操作

    /**
     * 点一个事项 = 从现在开始记它。
     *
     * ## 邻居怎么办（撞车 = 让位，不是弹窗）
     *
     * 如果现在正记着别的事，**把它封口到此刻**（而不是弹窗问「要结束前一个吗」）——
     * 这是时间线类 App 的标准手感，多问一句就是多一次打断。
     *
     * ## ⚠️ 只有今天能这么干
     *
     * 过去的一天早就过完了、未来的一天还没到，都不存在「从现在开始记」。
     * 第 19 轮的 bug 就是漏了这一句：翻到 13 号点事项，代码拿「今天的分钟数」
     * 当起点插进去，于是 13 号凭空长出一条从 9:37 铺到 24:00 的怪物记录。
     *
     * @return `Ok(true)` = 真的开始记了；`Ok(false)` = 本来就在记它，什么也没做
     */
    suspend fun pick(itemId: String, day: String): Outcome<Boolean> {
        val now = clock.now()
        if (day != now.dayKey) {
            return Outcome.Blocked("只能记「现在」。想补以前的时间，请点时间线上的「▨ 未记录」段。")
        }
        val item = items.byId(itemId) ?: return Outcome.Blocked("这个事项已经不在了")

        return db.withTransaction {
            ensureDense(day) // 先把 [0, 此刻] 补齐，保证待会儿「压短邻居」有东西可压

            val ts = now.epochMillis
            val nowMin = now.minuteOfDay
            val live = records.liveOn(day)

            when {
                // 本来就一条都没有（今天第一笔）
                live == null -> {
                    // 找出盖住此刻的那条，把它压短到此刻
                    records.byDay(day)
                        .lastOrNull { (it.endMin ?: Int.MAX_VALUE) > nowMin }
                        ?.let { records.trimEnd(it.id, nowMin, ts) }
                    // ★ 模板正在跑而你亲手记了别的 → 退出模板（见下方注释）
                    if (templates.run()?.isOn == true) templates.putRun(runOff())
                }

                // 已经在记它了 —— 不用重复插，避免密铺里出现零长度记录
                live.itemId == itemId -> return@withTransaction Outcome.Ok(false)

                // 在记别的 → 把前一条封口到此刻（邻居让位，不打扰用户）
                else -> {
                    records.closeRecord(live.id, nowMin, ts)
                    // ★★ 「退出模板」**唯一认的动作就是这里**：
                    //    点「结束」、并亲手选了别的事项。
                    //    ⚠️ 改记录 / 删记录 / **改模板** 都**不算**退出（御坂第 17 轮定的原话）。
                    //    落在这里而不是界面里，是因为它是**一条业务规则**，
                    //    将来桌面小部件、通知栏那个「结束」也走同一个 pick()，天然一致。
                    //    上面那个 `live.itemId == itemId`（点了同一件事）提前返回了，
                    //    所以「点结束又选回原样」不会退出 —— 你说的是「别的事项」才算。
                    if (live.source == RecordSource.TEMPLATE) templates.putRun(runOff())
                }
            }

            val freshId = newId()
            records.insert(
                RecordEntity(
                    id = freshId,
                    day = day,
                    startMin = nowMin,
                    endMin = null, // ★ null = 进行中
                    itemId = itemId,
                    categoryIdSnap = item.categoryId,
                    source = RecordSource.MANUAL,
                    chainId = null,
                    noTemplate = false,
                    note = "",
                    updatedAt = ts,
                    deleted = false,
                )
            )

            // 事项预设的默认标签，记的时候自动带上（记录页里还能当场加减）
            val defaults = items.defaultTagIds(itemId)
            if (defaults.isNotEmpty()) {
                tags.attachToRecord(
                    defaults.map { RecordTagEntity(recordId = freshId, tagId = it) }
                )
            }

            Outcome.Ok(true)
        }
    }

    /** 结束当前正在记的事（不指定下一件，就让它留成空白）。 */    suspend fun closeLive(day: String): Outcome<Unit> {
        val now = clock.now()
        if (day != now.dayKey) return Outcome.Blocked("过去的一天已经结束了。")

        return db.withTransaction {
            val live = records.liveOn(day)
                ?: return@withTransaction Outcome.Blocked("现在没有正在记的事。")
            records.closeRecord(live.id, now.minuteOfDay, now.epochMillis)

            // ⚠️ 如果刚结束的这条是**模板替你记的**，必须顺手退出模板模式。
            //    不退的话，下一次刷新 applyTemplate 会把末尾那条重新标成「进行中」——
            //    你刚按下的那一下等于没发生，看着像按钮失灵。
            //    （目前 `closeLive` 只有测试在调；这条是给 M4 的桌面小部件/通知栏预留的，
            //     先立在这儿，免得将来那条路径漏掉。）
            if (live.source == RecordSource.TEMPLATE) templates.putRun(runOff())

            Outcome.Ok(Unit)
        }
    }

    /**
     * ★ 用户删掉一条记录。
     *
     * **不是删除，是「擦成空白」** —— 那行留着，`itemId` 清空、`source` 改成 GAP。
     *
     * 为什么不真删：删了的话这段时间在密铺里就成了一个洞，
     * 而「洞」是这个设计里不该存在的东西。空白必须是**一条看得见的记录**
     * （用户才能点它补记），所以它得有自己的行。
     *
     * 顺手打上 `noTemplate` —— 否则模板下次刷新又把这段按模板填满，用户看着像「删不掉」。
     *
     * ## 返回值是「后悔药」
     *
     * 它返回一份 [UndoToken] —— **改动之前这一天的完整快照**，[undo] 凭它把一切还原。
     *
     * 为什么拍整天而不是只记这一条：紧接着的 [mergeAdjacentGaps] 会把相邻空白
     * **真的删掉行**。只记一条的话，撤销回来会缺几行，那天就少了一块。
     * 宁可多存几十行 —— 一天也就几十条记录。
     */
    suspend fun deleteRecord(recordId: String): Outcome<UndoToken> {
        val now = clock.now()
        return db.withTransaction {
            val r = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这条记录已经不在数据库里了。")

            if (r.source == RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("这里本来就是一段空白。")
            }

            // ★ 拍照必须在任何改动之前 —— 拍完它，下面怎么改都能原路退回来
            val snap = snapshot(r.day, "已删除「${nameOf(r)}」· 那段时间变成「未记录」")

            val ts = now.epochMillis
            // 正在记的那条被删 → 先封口到此刻，否则会留下「endMin = null 的空白」这种怪状态
            val closed = if (r.endMin == null) r.copy(endMin = Density.cutFor(r.day, now)) else r

            // 零长度的记录（同一分钟内点开又删掉 —— "点错了想撤回"最常见的一种）
            // 它**不代表任何一段时间**，所以直接清掉行是安全的：没有哪段时间会失去代表。
            // 留成一条 [x, x) 的空白虽然不破密铺，但会脏数据里多一条看不见的记录。
            if (closed.endMin != null && closed.startMin >= closed.endMin) {
                records.deleteById(recordId)
                tags.detachAllFromRecord(recordId)
                mergeAdjacentGaps(r.day, ts)
                ensureDense(r.day)
                return@withTransaction Outcome.Ok(snap)
            }

            records.update(
                closed.copy(
                    itemId = null,
                    categoryIdSnap = null,
                    source = RecordSource.GAP,
                    note = "",
                    noTemplate = true, // ★ 别让模板把它填回来
                    updatedAt = ts,
                )
            )
            tags.detachAllFromRecord(recordId)

            // 擦成空白后，前后可能都是空白 → 连成一片（否则时间线上会出现一串一模一样的「▨ 未记录」）
            mergeAdjacentGaps(r.day, ts)
            ensureDense(r.day)
            Outcome.Ok(snap)
        }
    }

    // -------------------------------------------------- 记录操作（M2-B）

    /**
     * ★ 撤销一次删除 —— 把那一天**整体还原**成操作之前的样子。
     *
     * ## 怎么还原
     *
     * 三步，缺一不可：
     * 1. 现在有、快照里没有的行 → 删掉（这次操作新造出来的）
     * 2. 快照里的每一条 → 原样写回（不在的插回去，在的覆盖掉）
     * 3. 标签也照快照重挂（删除时被摘掉了）
     *
     * ## ⚠️ 两个边界
     *
     * - 撤销不是「时光倒流」：如果这 4 秒里你还在别的页面干了别的（比如又记了一笔），
     *   撤销会把它一起抹掉。所以撤销条上写的必须是你**刚才做的那件事**，
     *   而且只有 4 秒。
     * - 撤销之后那天可能又缺一块（时间在这 4 秒里往前走了一点）——
     *   不用管，界面刷新时的 `ensureDense()` 会补上。
     */
    suspend fun undo(token: UndoToken): Outcome<Unit> = db.withTransaction {
        val keep = token.records.map { it.id }.toSet()

        // ① 抹掉这次操作新造出来的行
        records.byDay(token.day)
            .filter { it.id !in keep }
            .forEach {
                records.deleteById(it.id)
                tags.detachAllFromRecord(it.id)
            }

        // ② 把动过的那几条按原样写回，顺手把标签也恢复
        token.records.forEach { before ->
            val exists = records.byId(before.id) != null
            if (exists) records.update(before) else records.insert(before)

            tags.detachAllFromRecord(before.id)
            val ids = token.tags[before.id].orEmpty()
            if (ids.isNotEmpty()) {
                tags.attachToRecord(ids.map { RecordTagEntity(recordId = before.id, tagId = it) })
            }
        }

        // 撤销之前时间可能已经往前走了几秒 —— 补一下，别让这天缺一块
        ensureDense(token.day)
        Outcome.Ok(Unit)
    }

    /**
     * 换一件事，**时长一点不动**。
     *
     * ## 两个容易漏的地方
     *
     * 1. **摘掉模板标记**（`source` 从 TEMPLATE 改成 MANUAL）。
     *    你亲手改过的段就不再是「模板记的」了 —— 不摘的话卡片会一直显示「模板」，
     *    而且将来做「今日偏差」时永远认不出哪些段是被你修正过的。
     * 2. **换上新事项的默认标签**（但只在新事项**有**默认标签时才换）。
     *    「记它时自动带上」这句承诺，换事项时也得算数 —— 否则「学习 → 玩手机」
     *    之后还挂着「#高价值投资」，看着自相矛盾。新事项没设默认标签就保留原来的，
     *    不给你清空。
     */
    suspend fun changeItem(recordId: String, itemId: String): Outcome<Unit> {
        val now = clock.now()
        return db.withTransaction {
            val r = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这条记录已经不在数据库里了。")
            if (r.source == RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("这是一段空白 —— 用「补记一件事」来填它。")
            }
            val item = items.byId(itemId)
                ?: return@withTransaction Outcome.Blocked("这个事项已经不在了。")

            records.update(
                r.copy(
                    itemId = itemId,
                    categoryIdSnap = item.categoryId,
                    source = RecordSource.MANUAL, // ★ 你亲手改的，不再算模板记的
                    updatedAt = now.epochMillis,
                )
            )

            val defaults = items.defaultTagIds(itemId)
            if (defaults.isNotEmpty()) {
                tags.detachAllFromRecord(recordId)
                tags.attachToRecord(defaults.map { RecordTagEntity(recordId = recordId, tagId = it) })
            }
            Outcome.Ok(Unit)
        }
    }

    /**
     * 改时间 —— 顺带处理「撞上邻居」。
     *
     * ## 邻居让位，不弹窗
     *
     * 规则全在 [Overlap.resolve]（纯函数，穷举测过）。这里只负责把它的判决
     * 落到数据库上，并把「谁让位了、压到几点」翻译成一句人话带回去。
     *
     * ## ⚠️ 进行中的记录只能改开始时间
     *
     * 它的终点不归你管 —— 那是「结束」按钮的事。允许改的话，就会出现
     * 「一条记录既说自己到 18:00 结束、又还在计时」这种自相矛盾的状态。
     *
     * @return 成功时带回一串**让位说明**（空列表 = 没碰到别的记录）。
     */
    suspend fun setTime(recordId: String, newStart: Int, newEnd: Int?): Outcome<List<String>> {
        val now = clock.now()
        return db.withTransaction {
            val r = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这条记录已经不在数据库里了。")
            if (r.source == RecordSource.GAP) {
                return@withTransaction Outcome.Blocked(
                    "空白的时间不能这样改 —— 直接补记一件事，或者把它并进邻居。"
                )
            }

            val ts = now.epochMillis
            val cut = Density.cutFor(r.day, now)
            val isLive = r.endMin == null

            // 未来还没发生，谁也不许把记录伸进去
            val s = newStart.coerceIn(0, cut)
            val e = if (isLive) {
                null
            } else {
                val raw = newEnd ?: return@withTransaction Outcome.Blocked("缺一个结束时间。")
                raw.coerceIn(0, cut)
            }
            if (e != null && e <= s) {
                return@withTransaction Outcome.Blocked("结束时间得在开始之后。")
            }

            records.update(r.copy(startMin = s, endMin = e, updatedAt = ts))

            // ★ 撞上邻居 → 邻居让位
            val others = records.byDay(r.day).filter { it.id != recordId }
            val effects = Overlap.resolve(
                me = Overlap.Span(r.id, s, e),
                others = others.map { Overlap.Span(it.id, it.startMin, it.endMin) },
                cut = cut,
            )

            val byId = others.associateBy { it.id }
            val msgs = ArrayList<String>(effects.size)
            effects.forEach { eff ->
                val o = byId[eff.id] ?: return@forEach
                val label = nameOf(o)
                when (eff) {
                    is Overlap.Effect.TrimEnd -> {
                        // 它的尾巴伸进了我的地盘 → 切到我的起点
                        records.trimEnd(o.id, eff.newEnd, ts)
                        msgs += "「$label」让位，压短到 ${DayKey.minuteLabel(o.startMin)}–${DayKey.minuteLabel(eff.newEnd)}"
                    }

                    is Overlap.Effect.TrimStart -> {
                        // 它的头伸进了我的地盘 → 切到我的终点
                        records.trimStart(o.id, eff.newStart, ts)
                        val endLabel = DayKey.minuteLabel(o.endMin ?: cut)
                        msgs += "「$label」让位，压短到 ${DayKey.minuteLabel(eff.newStart)}–$endLabel"
                    }

                    is Overlap.Effect.Drop -> {
                        // 被完整接管了 —— 见 Overlap 的说明：这里真删是安全且唯一正确的
                        records.deleteById(o.id)
                        tags.detachAllFromRecord(o.id)
                        msgs += "「$label」被完全覆盖，已删除"
                    }
                }
            }

            // ★ 收尾：把密铺补回来。
            //   改短会留出空隙 —— 而空隙在这个设计里不存在，必须变成一条看得见的空白。
            //   为什么不交给调用方（ViewModel）刷新时补：那样「忘了补」会漏一辈子，
            //   而漏了不报错、只在几个月后让统计对不上。仓库自己保证才靠谱。
            ensureDense(r.day)
            Outcome.Ok(msgs)
        }
    }

    /**
     * 与时间线上方（[dir] = -1）或下方（[dir] = +1）那条合并成一段。
     *
     * ## 方向别搞反（这里流过一次血）
     *
     * 时间线是**倒序**显示的，而这里是**升序**列表。所以：
     * - 「上一条」（视觉上方 = 时间更晚）= 升序里的 `index + 1`
     * - 「下一条」（视觉下方 = 时间更早）= 升序里的 `index - 1`
     *
     * 代码里统一写成 `index - dir` —— 就这一个地方换算，别在别处再算一遍。
     *
     * ## 合并结果以「你点的那条」为宿主
     *
     * 名字、分类、标签、备注都留宿主的，邻居被吸收掉（真的删行 —— 它的那段时间
     * 已经被宿主完整接管，没有哪一段时间失去代表）。
     */
    suspend fun mergeWith(recordId: String, dir: Int): Outcome<String> {
        val now = clock.now()
        return db.withTransaction {
            val host = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这条记录已经不在数据库里了。")
            if (host.source == RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("空白请用「并入上一条 / 下一条」。")
            }

            val list = records.byDay(host.day)
            val i = list.indexOfFirst { it.id == recordId }
            val nb = list.getOrNull(i - dir)
                ?: return@withTransaction Outcome.Blocked(
                    if (dir < 0) "上面没有可以合并的记录了。" else "下面没有可以合并的记录了。"
                )

            val ts = now.epochMillis
            val from = minOf(host.startMin, nb.startMin)
            val to = maxOf(host.endMin ?: Int.MAX_VALUE, nb.endMin ?: Int.MAX_VALUE)
            // 任一条在计时，合并后还在计时 —— 但宿主得是留下的那条
            val stillLive = host.endMin == null || nb.endMin == null

            records.update(
                host.copy(
                    startMin = from,
                    endMin = if (stillLive) null else to,
                    updatedAt = ts,
                )
            )
            records.deleteById(nb.id)
            tags.detachAllFromRecord(nb.id)

            val where = if (dir < 0) "上一条" else "下一条"
            val endLabel = if (stillLive) "现在" else DayKey.minuteLabel(to)
            ensureDense(host.day)
            Outcome.Ok("「${nameOf(host)}」已与$where「${nameOf(nb)}」合并 → ${DayKey.minuteLabel(from)} → $endLabel")
        }
    }

    /**
     * 对半拆成两段 —— 从正中间切一刀。
     *
     * ## 为什么会有这个功能
     *
     * 很常见的场景：早上 9 点开始「写代码」，中间 11 点其实改去开会了，
     * 但你一上午没碰手机。与其把时间改来改去，不如切一刀，
     * 再把后半段「变更事项」成开会 —— 三步搞定。
     *
     * ## 拆分出来的那半**连备注和标签一起复制**（⚠️ 这里跟原型不同）
     *
     * 原型拆出来的新段只有名字和标签，备注丢了。理由说不通 ——
     * 拆开的两半本来就是**同一件事**，凭什么后半段的备注没了？
     * 所以真机版把备注也复制过去。改错了没关系，两半都能单独编辑。
     */
    suspend fun split(recordId: String): Outcome<String> {
        val now = clock.now()
        return db.withTransaction {
            val r = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这条记录已经不在数据库里了。")
            if (r.source == RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("空白拆开还是空白，没什么意义。")
            }

            val a = r.startMin
            val b = r.endMin ?: Density.cutFor(r.day, now)
            if (b - a < 2) return@withTransaction Outcome.Blocked("这条太短，拆不开。")

            val ts = now.epochMillis
            val mid = a + (b - a) / 2
            val wasLive = r.endMin == null

            // 前半段：宿主自己，终点收到中间
            records.update(r.copy(endMin = mid, updatedAt = ts))

            // 后半段：新的一条。计时状态跟着搬过去（不能两条都在计时）
            val tailId = newId()
            records.insert(
                r.copy(
                    id = tailId,
                    startMin = mid,
                    endMin = if (wasLive) null else b,
                    updatedAt = ts,
                )
            )
            val tagIds = tags.tagIdsOfRecord(recordId)
            if (tagIds.isNotEmpty()) {
                tags.attachToRecord(tagIds.map { RecordTagEntity(recordId = tailId, tagId = it) })
            }

            ensureDense(r.day)
            Outcome.Ok("已对半拆成两段 · 各约 ${b - mid} 分钟")
        }
    }

    /**
     * 把一段空白并入上/下邻居 —— 邻居把这段时长吃掉，空白记录消失。
     *
     * 方向语义跟 [mergeWith] 一样（`index - dir`）。
     *
     * 「上面」的那条更晚，所以是把它的**起点往前拉到空白的起点**；
     * 「下面」的那条更早，所以是把它的**终点往后推到空白的终点**。
     */
    suspend fun mergeGapInto(recordId: String, dir: Int): Outcome<String> {
        val now = clock.now()
        return db.withTransaction {
            val gr = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这段空白已经不在数据库里了。")
            if (gr.source != RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("这条不是空白。")
            }

            val list = records.byDay(gr.day)
            val i = list.indexOfFirst { it.id == recordId }
            val nb = list.getOrNull(i - dir)
                ?: return@withTransaction Outcome.Blocked(
                    if (dir < 0) "上面没有可以并入的记录。" else "下面没有可以并入的记录。"
                )

            val ts = now.epochMillis
            val merged = if (dir < 0) {
                nb.copy(startMin = gr.startMin, updatedAt = ts)
            } else {
                // ⚠️ 邻居在计时的话不能给它补终点 —— 它会继续长到此刻，本就包含了这段
                if (nb.endMin == null) nb else nb.copy(endMin = gr.endMin, updatedAt = ts)
            }

            records.update(merged)
            records.deleteById(gr.id)
            tags.detachAllFromRecord(gr.id)

            ensureDense(gr.day)
            Outcome.Ok(
                "这段空白并给了${if (dir < 0) "上一件" else "下一件"}「${nameOf(nb)}」· 现在是 " +
                    "${DayKey.minuteLabel(merged.startMin)} → " +
                    (merged.endMin?.let { DayKey.minuteLabel(it) } ?: "现在")
            )
        }
    }

    /**
     * 编辑备注和标签 —— **一次写清，中间不改数据库**。
     *
     * ## 为什么要「草稿」
     *
     * 这个面板有「取消」按钮。如果每点一下标签就立刻落库，那个取消就成了骗人的东西
     * （原型第 19 轮踩过这个坑）。所以界面持有一份草稿，点「保存」才走这里。
     *
     * ## 标签是整体替换
     *
     * 不搞增删 diff —— 直接「全摘掉、再按参数挂上」。两条记录的标签最多十几个，
     * 这点开销换来的好处是**不需要考虑中间态**（比如某个标签刚好被删了）。
     */
    suspend fun editContent(recordId: String, note: String, tagIds: List<String>): Outcome<Unit> {
        val now = clock.now()
        return db.withTransaction {
            val r = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这条记录已经不在数据库里了。")
            if (r.source == RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("空白没有备注和标签 —— 先补记一件事。")
            }

            records.update(r.copy(note = note.trim(), updatedAt = now.epochMillis))

            tags.detachAllFromRecord(recordId)
            if (tagIds.isNotEmpty()) {
                // 只挂真正存在、且没被同时删掉的标签 —— 否则会留下悬空 id
                val alive = tags.all().map { it.id }.toSet()
                val ok = tagIds.filter { it in alive }.distinct()
                if (ok.isNotEmpty()) {
                    tags.attachToRecord(ok.map { RecordTagEntity(recordId = recordId, tagId = it) })
                }
            }
            Outcome.Ok(Unit)
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 一条记录该显示成什么名字 —— 提示文案里要用。 */
    private suspend fun nameOf(r: RecordEntity): String = when (r.source) {
        RecordSource.GAP -> "未记录"
        else -> items.byId(r.itemId ?: "")?.name ?: "（事项已删除）"
    }

    /**
     * 给这一天拍张照。
     *
     * 记录 + 每条的标签 id 都收进来 —— 删除会把标签摘掉，只记记录的话
     * 撤销回来标签就没了，而用户眼里「撤销」应该是**什么都没发生过**。
     */
    private suspend fun snapshot(day: String, label: String): UndoToken {
        val recs = records.byDay(day)
        val refs = tags.refsOfRecords(recs.map { it.id })
        return UndoToken(
            day = day,
            label = label,
            records = recs,
            tags = refs.groupBy({ it.recordId }, { it.tagId }),
        )
    }

    /**
     * 补记 —— 把一段「▨ 未记录」填上事项。
     *
     * 这是空白存在的**唯一理由**：它是补记的入口。
     */
    suspend fun fillGap(recordId: String, itemId: String): Outcome<Unit> {
        val now = clock.now()
        return db.withTransaction {
            val r = records.byId(recordId)
                ?: return@withTransaction Outcome.Blocked("这段空白已经不在数据库里了。")
            if (r.source != RecordSource.GAP) {
                return@withTransaction Outcome.Blocked("这段已经有内容了，不是空白。")
            }
            val item = items.byId(itemId)
                ?: return@withTransaction Outcome.Blocked("这个事项已经不在了。")

            records.update(
                r.copy(
                    itemId = itemId,
                    categoryIdSnap = item.categoryId,
                    source = RecordSource.MANUAL,
                    updatedAt = now.epochMillis,
                )
            )

            val defaults = items.defaultTagIds(itemId)
            if (defaults.isNotEmpty()) {
                tags.attachToRecord(defaults.map { RecordTagEntity(recordId = recordId, tagId = it) })
            }
            Outcome.Ok(Unit)
        }
    }

    // ============================================================ 整天复制 / 粘贴（M5-2）

    /**
     * 某一天的「复制快照」。
     *
     * ## ⚠️ 它故意**不带日期**
     *
     * 存的只是原料：第几分钟到第几分钟、哪件事、什么标签、是不是模板铺的。
     * 「贴到哪一天」是粘贴那一刻才决定的 —— 所以同一份快照可以连着粘好几天，
     * 不用每贴一次就重新复制一次。
     *
     * 也不带 `id` —— 粘贴时全部重新生成（见 [pasteDay]）。
     */
    data class DayClip(
        val from: String,
        val segs: List<Seg>,
    ) {
        data class Seg(
            val startMin: Int,
            val endMin: Int?,
            val itemId: String?,
            val categoryIdSnap: String?,
            val source: RecordSource,
            val chainId: String?,
            val noTemplate: Boolean,
            val note: String,
            val tagIds: List<String>,
        )

        /** 几段 —— 提示条上念给用户听的那个数。 */
        val count: Int get() = segs.size
    }

    /**
     * 把某一天整条抄下来。
     *
     * 读的是**已经落库的那一天**，所以空白段（GAP）也会被一起抄走 ——
     * 这是对的：复制的是「这一天长什么样」，不是「我记了哪几件事」。
     * 粘贴时那段照样是空白，一天仍然是 1440 分钟。
     */
    suspend fun clipDay(day: String): DayClip {
        val recs = records.byDay(day)
        val tagIds = tags.refsOfRecords(recs.map { it.id })
            .groupBy({ it.recordId }, { it.tagId })

        return DayClip(
            from = day,
            segs = recs.map { r ->
                DayClip.Seg(
                    startMin = r.startMin,
                    endMin = r.endMin,
                    itemId = r.itemId,
                    categoryIdSnap = r.categoryIdSnap,
                    source = r.source,
                    chainId = r.chainId,
                    noTemplate = r.noTemplate,
                    note = r.note,
                    tagIds = tagIds[r.id].orEmpty(),
                )
            },
        )
    }

    /**
     * ★ **整天覆盖粘贴** —— 御坂拍板的语义：
     * 「就是把复制这天从早到晚上的事项和时间粘贴到另一天去」。
     *
     * ## 三条必须守住的东西
     *
     * 1. **密铺不破**。所以它是「整天替换」（清掉目标日的行，写进一份新的完整数据），
     *    而不是「往空档里插」—— 插入一定会跟原有记录撞车，处理撞车就要做让位，
     *    而这里根本不需要让位：那天本来就要被整个盖掉。
     *    清空的理由和边界写在 `RecordDao.deleteByDay` 的注释里。
     * 2. **不铺未来**。目标是今天时，只贴到「此刻」为止；越过后半段的段落直接丢掉，
     *    尾巴交给 `ensureDense()` 补成空白。目标是过去的一天则整段贴上（那天已经过完了）。
     * 3. **可撤销**。先 [snapshot] 再动手，返回的 token 交给撤销条 ——
     *    覆盖整天是**不可逆**的操作，没有撤销就等于逼用户先备份。
     *
     * ## ⚠️ 「进行中」那一段不能原样搬过去
     *
     * 复制的那天如果结尾是一条 `endMin == null`（正在记），贴到今天它仍然是进行中（合理），
     * 但**贴到过去就成了「昨天还有一条正在记」** —— 那正是第 19 轮那个
     * 「昨天凭空长出一条 24 小时记录」的同一种毒。所以贴到过去时它就地在一天末尾收尾。
     */
    suspend fun pasteDay(target: String, clip: DayClip): Outcome<UndoToken> {
        val now = clock.now()
        val cut = Density.cutFor(target, now)
        if (cut <= 0) return Outcome.Blocked("未来的日期没有可以覆盖的东西。")
        if (clip.segs.isEmpty()) return Outcome.Blocked("复制的那天是空的，没什么可贴的。")

        val isToday = target == now.dayKey
        val ts = now.epochMillis

        // ★ 先把要写的行**全部算好**，再进事务。
        //   算的过程中一旦发现「一段都贴不过来」，直接返回，**不会**留下一个空掉的日子 ——
        //   在事务里提前 return 是会提交前面那些 delete 的。
        val plan = clip.segs.mapNotNull { s ->
            val a = s.startMin.coerceIn(0, cut)
            if (a >= cut) return@mapNotNull null

            val b = when {
                s.endMin == null && isToday -> null   // 今天：原样保留「进行中」
                s.endMin == null -> cut               // 过去：就地收在这一天末尾
                else -> s.endMin.coerceIn(a, cut)
            }
            if (b != null && b <= a) return@mapNotNull null   // 被今天的时间截没了

            RecordEntity(
                id = newId(),
                day = target,
                startMin = a,
                endMin = b,
                itemId = s.itemId,
                categoryIdSnap = s.categoryIdSnap,
                source = s.source,
                chainId = s.chainId,
                noTemplate = s.noTemplate,
                note = s.note,
                updatedAt = ts,
                deleted = false,
            ) to s.tagIds
        }
        if (plan.isEmpty()) return Outcome.Blocked("复制的那天没有能贴过来的段落。")

        val token = db.withTransaction {
            val t = snapshot(target, "粘贴「${DayKey.display(clip.from)}」")
            records.deleteByDay(target)
            records.insertAll(plan.map { it.first })

            // 标签按原样挂回去（标签字典是全库共享的，不会跟日期一起被清掉）
            plan.forEach { (row, tagIds) ->
                if (tagIds.isNotEmpty()) {
                    tags.attachToRecord(tagIds.map { RecordTagEntity(recordId = row.id, tagId = it) })
                }
            }
            t
        }

        // 尾巴补齐：今天贴到「此刻」，剩下的交给空白；过去的一天贴满 1440
        ensureDense(target)
        return Outcome.Ok(token)
    }

    /**
     * ★ **删事项时对历史做的事**。
     *
     * 用户说得很清楚：「删就完事了……最多提醒用户谨慎操作，删除事项会导致
     * 历史所有该事项的时间数据均变成空白块。」
     *
     * 所以这里做的是**原地擦成空白**，一条记录都不删。同样打 `noTemplate`，
     * 否则模板明天又把它填回来。
     *
     * ⚠️ 记录上的 `itemId` 是**不建外键的**（见 `RecordEntity` 的注释），
     * 所以这一步**必须真的执行** —— 没有数据库帮忙兜底。
     *
     * @return 被擦掉的记录条数
     */
    suspend fun eraseItemFromHistory(itemId: String): Outcome<Int> = db.withTransaction {
        val affected = records.byItem(itemId)
        if (affected.isEmpty()) return@withTransaction Outcome.Ok(0)

        val ts = clock.now().epochMillis
        records.updateAll(
            affected.map {
                it.copy(
                    itemId = null,
                    categoryIdSnap = null,
                    source = RecordSource.GAP,
                    note = "",
                    noTemplate = true,
                    updatedAt = ts,
                )
            }
        )
        affected.forEach { tags.detachAllFromRecord(it.id) }
        affected.map { it.day }.distinct().forEach { mergeAdjacentGaps(it, ts) }

        Outcome.Ok(affected.size)
    }

    // ============================================ ★★ 整库替换（旧数据导入 / 回退）

    /**
     * ★★ 用一整份「已经算好的一天一天」把 `records` **换掉** —— 旧数据导入走这里。
     *
     * ## 为什么这件事配得上在这儿开一个「清空」的口子
     *
     * 这个类的铁律是「**永远别 `DELETE` 记录，删除一律原地改写成空白**」——
     * 因为真空掉一行会在密铺里留一个永久的洞。
     *
     * 但「换整份数据」是**另一个动作**：它换完之后那一刻，整张表是新的一份
     * **完整**数据（每天 `0 → 1440` 铺满）。洞里没有洞，因为整片地都换了。
     * 所以这里允许 `deleteAll()`，代价是**紧接着必须写进一份密铺的数据** ——
     * 而这一点由逐天的 [Density.check] 把关：任何一天接不上，整包不写。
     *
     * ## 三个阶段，顺序一个字都不能动
     *
     * ① **校验 + 装配**（纯内存 + 只读查询）——出问题就地返回，**一个字都还没写**
     * ② **留后悔药**（[writeSnapshot] 自己开一个事务）
     * ③ **替换**（一个事务：清空 → 插入 → 挂标签）
     *
     * ⚠️ ②**必须自己一个事务**。要是把它并进 ③，那么 ③ 一失败回滚，
     * **这份后悔药会跟着一起消失** —— 正好在你最需要它的时候没有。
     *
     * ## 两个刻意的决定
     *
     * - **不早于今天的日子直接跳过**（记在报告的 `skippedDays` 里，不报错）。
     *   这份数据是「导出那一刻」的快照，导出当天的半天在 App 里另有实时记录，
     *   硬塞进去只会跟今天的运行态打架。
     * - 导进来的行一律打 `noTemplate = true`。**历史是既成事实，模板只管以后** ——
     *   这样即便将来模板的作用范围变了，也咬不动这一年的记录。
     *
     * @param spec 一天一天的段，事项/标签**已经解析成 id**（见 [ImportDay]）
     * @param why  为什么替换的，会存进快照里给用户看
     */
    suspend fun replaceAllRecords(spec: List<ImportDay>, why: String): Outcome<ImportReport> {
        val now = clock.now()
        val ts = now.epochMillis

        // ---------------------------------------------- ① 校验 + 装配（只读）
        // ⚠️ 日期用 ISO 存，字典序恰好等于时间序，所以能直接比字符串。
        val skipped = spec.filter { it.day >= now.dayKey }.map { it.day }.sorted()
        val alive = spec.filter { it.day < now.dayKey }
        if (alive.isEmpty()) {
            return Outcome.Blocked("这份数据里没有比今天更早的日子，没什么可导入的。")
        }

        // 事项 id → 它当前的归属分类。一次查完，别在循环里查几千遍（N+1）
        val categoryOf = items.all().associate { it.id to it.categoryId }

        val everything = mutableListOf<RecordEntity>()
        val allRefs = mutableListOf<RecordTagEntity>()
        var segmentCount = 0
        var gapCount = 0
        var gapMinutes = 0

        alive.forEach { d ->
            val sorted = d.segments.sortedBy { it.startMin }

            // 先整段体检，别插到一半才发现有问题
            var probe = 0
            sorted.forEach { s ->
                if (s.startMin < 0 || s.endMin > MINUTES_PER_DAY || s.startMin >= s.endMin) {
                    return Outcome.Blocked("${d.day} 有一段的时间不对（${s.startMin} → ${s.endMin}），整包都没有导入。")
                }
                if (s.startMin < probe) {
                    return Outcome.Blocked("${d.day} 有两段重叠了，整包都没有导入。")
                }
                probe = s.endMin
            }

            val rows = mutableListOf<RecordEntity>()

            // 一路走一路在缝里补空白：真实段和空白段**交替产出**，天然密铺
            var cursor = 0
            sorted.forEach { s ->
                if (cursor < s.startMin) {
                    rows += gapRecord(d.day, cursor, s.startMin, ts).copy(noTemplate = true)
                    gapCount++
                    gapMinutes += s.startMin - cursor
                }
                val id = newId()
                rows += RecordEntity(
                    id = id,
                    day = d.day,
                    startMin = s.startMin,
                    endMin = s.endMin,
                    itemId = s.itemId,
                    categoryIdSnap = categoryOf[s.itemId],
                    source = RecordSource.MANUAL,
                    chainId = s.chainId,
                    noTemplate = true,
                    note = s.note,
                    updatedAt = ts,
                    deleted = false,
                )
                s.tagIds.forEach { allRefs += RecordTagEntity(recordId = id, tagId = it) }
                segmentCount++
                cursor = s.endMin
            }
            if (cursor < MINUTES_PER_DAY) {
                rows += gapRecord(d.day, cursor, MINUTES_PER_DAY, ts).copy(noTemplate = true)
                gapCount++
                gapMinutes += MINUTES_PER_DAY - cursor
            }

            // ★ 这一句就是整件事的守门人：接不上就整包不写。
            val problems = Density.check(rows, MINUTES_PER_DAY)
            if (problems.isNotEmpty()) {
                return Outcome.Blocked(
                    "${d.day} 这天的记录接不上（${problems.size} 处），整包都没有导入。"
                )
            }

            everything += rows
        }

        // ---------------------------------------------- ② 后悔药（独立事务）
        writeSnapshot(why, ts)

        // ---------------------------------------------- ③ 替换（一个事务）
        return db.withTransaction {
            records.deleteAll() // record_tags 由外键 CASCADE 跟着走
            records.insertAll(everything)
            if (allRefs.isNotEmpty()) tags.attachToRecord(allRefs)

            Outcome.Ok(
                ImportReport(
                    days = alive.size,
                    segments = segmentCount,
                    gaps = gapCount,
                    gapMinutes = gapMinutes,
                    totalMinutes = alive.size * MINUTES_PER_DAY,
                    skippedDays = skipped,
                )
            )
        }
    }

    /** 现在有没有可回退的快照。界面靠它决定「撤销」那个按钮显不显示。 */
    suspend fun hasSnapshot(): Boolean = backups.latest() != null

    /**
     * ★ 回退到「上一次整库替换之前」的样子。
     *
     * 一次整库替换之外用不上它 —— 所以它**一次性**：回退完就把快照删掉。
     * 留着的话，用户会以为还能再回退一次，其实不能。
     *
     * ⚠️ 它同样走「清空 → 写入」，理由跟 [replaceAllRecords] 一样：
     * 快照本身就是一份完整数据，换回去之后表里照样没有洞。
     *
     * @return 恢复回来几条记录
     */
    suspend fun restoreSnapshot(): Outcome<Int> {
        val snap = backups.latest() ?: return Outcome.Blocked("没有可以回退的快照了。")
        val payload = RecordSnapshot.decode(snap.payload)
            ?: return Outcome.Blocked("快照读不出来了，没法回退。")

        return db.withTransaction {
            records.deleteAll()
            if (payload.records.isNotEmpty()) records.insertAll(payload.records)
            if (payload.refs.isNotEmpty()) tags.attachToRecord(payload.refs)
            backups.clear()
            Outcome.Ok(payload.records.size)
        }
    }

    /**
     * 给「现在这整张表」拍张照。
     *
     * ⚠️ **它自己一个事务**，不跟替换挤在一起 —— 理由见 [replaceAllRecords] 的 ②。
     */
    private suspend fun writeSnapshot(why: String, ts: Long) {
        db.withTransaction {
            val rows = records.allAlive()
            // ⚠️ 必须分批：这些 id 会被拼成 SQL 的 `IN (?, ?, …)`，
            //    而 SQLite 的占位符上限是 **999**（`SQLITE_MAX_VARIABLE_NUMBER`，
            //    Android 上一直是这个老值）。一次性传几千个 id 会直接报
            //    「too many SQL variables」—— 而且只有「记录攒多了之后再导入」
            //    才碰得到，第一次导入永远测不出来。
            val refs = rows.map { it.id }.chunked(SQL_VARS_CHUNK).flatMap { tags.refsOfRecords(it) }
            backups.clear() // 只留一份「上一次的样子」
            backups.insert(
                BackupSnapshotEntity(
                    at = ts,
                    why = why,
                    payload = RecordSnapshot.encode(ts, why, rows, refs),
                )
            )
        }
    }

    private companion object {
        /**
         * 一次塞进 `IN (…)` 的 id 上限。
         *
         * SQLite 的默认上限是 999 个占位符，取 900 留一点余量（有些语句还会用到别的参数）。
         */
        const val SQL_VARS_CHUNK = 900
    }

    // ---------------------------------------------------------------- 内部

    private fun gapRecord(day: String, from: Int, to: Int, ts: Long) = RecordEntity(
        id = newId(),
        day = day,
        startMin = from,
        endMin = to,
        itemId = null,
        categoryIdSnap = null,
        // ★ 空白是一等公民：它是一条**真实存在的记录**，不是「缺失」
        source = RecordSource.GAP,
        chainId = null,
        // ⚠️ 留 false —— 模板**可以**填空白（模板本来就是兜底）。
        //    只有「用户手动删掉的那一段」才打 true 把模板挡在外面。
        noTemplate = false,
        note = "",
        updatedAt = ts,
        deleted = false,
    )

    /**
     * 把首尾相接的相邻空白合并成一条。
     *
     * 不合并也不会破坏不变式，但时间线上会并排出现一串长得一模一样的
     * 「▨ 未记录」，看着就像界面出了问题。
     */
    private suspend fun mergeAdjacentGaps(day: String, ts: Long) {
        var list = records.byDay(day)
        var guard = 0
        while (guard++ < 64) {
            // 「哪两段算相邻、该合」的规则在 Overlap 里 —— 和别的区间判断放一处，只有一份。
            // 这里只负责「反复用到再也不动为止」。
            val byId = list.associateBy { it.id }
            // ⚠️ 2026-10-19 试过「noTemplate 不同的空白不合并」，**撤回**：
            //    删记录产生的空白必然带锁，而它紧邻的空白不带锁 → 一删就碎成一串
            //    「▨ 未记录」，把 `TimelineEditTest` 和 `TimelineRepositoryTest` 三条打红了。
            //
            //    而且这个方向本来就不必要：锁之所以会长到「一下午」，
            //    根子在 [ensureDense] 把尾部空白**无限延长**（见那里的注释）。
            //    锁住那一条之后，合并的影响范围就固定在这次删除的那一小段上，传染不了。
            val pair = Overlap
                .adjacentGapPairs(list.map { Overlap.Span(it.id, it.startMin, it.endMin) }) { id ->
                    byId[id]?.source == RecordSource.GAP
                }
                .firstOrNull() ?: return

            val a = byId.getValue(pair.first)
            val b = byId.getValue(pair.second)

            records.update(
                a.copy(
                    endMin = b.endMin ?: a.endMin,
                    // ★ 只要被合并的两段里有一段是「用户明确删过的」，合并后整段都要免疫模板。
                    //   不这么做的话：被删掉的内容会被并进一条 noTemplate=false 的空白里，
                    //   模板下次刷新又把它填满 —— 用户看着就像「删不掉」。这条踩过一次。
                    noTemplate = a.noTemplate || b.noTemplate,
                    updatedAt = ts,
                )
            )
            records.deleteById(b.id)
            tags.detachAllFromRecord(b.id)
            list = records.byDay(day)
        }
    }
}

/**
 * 一次操作的「后悔药」—— **改动之前这一天的完整样子**。
 *
 * ## 为什么要整天的快照，而不是只记被删的那一条
 *
 * 因为删一条记录会**连带动到别的行**：擦成空白后，前后如果都是空白，
 * 它们会合并（后一条的行被真的删掉）。只记一条的话，撤销回来会缺几行，
 * 那天就少了一块 —— 而「少一块」在这个设计里是不存在的状态。
 *
 * 一天也就几十条记录，全量拍下来的代价可以忽略。
 *
 * ## 它是有寿命的
 *
 * 只在界面上那条 4 秒的撤销条里存在。过期就丢，不存在「翻出三天前的操作撤销」
 * 这种事 —— 那种需求真正要的是「编辑历史」，是另一个东西。
 */
data class UndoToken(
    /** 哪一天 `'2026-10-14'`。 */
    val day: String,
    /** 撤销条上给用户看的那句话（「已删除「吃饭」· 那段时间变成「未记录」」）。 */
    val label: String,
    /** 这一天当时**全部**的记录。只给 [TimelineRepository.undo] 用。 */
    val records: List<RecordEntity>,
    /** 记录 id → 它当时挂着的标签 id。删除会把标签摘掉，不记就恢复不了。 */
    val tags: Map<String, List<String>>,
)
