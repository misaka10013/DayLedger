package com.misaka.timelog.ui

import com.misaka.timelog.data.entity.CategoryEntity
import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.TagEntity
import com.misaka.timelog.data.entity.isGap
import com.misaka.timelog.domain.DayKey

/**
 * 把「实体 + 字典」拼成「界面照着画就行的东西」——
 * ★ **纯函数，不碰数据库、不碰 Android、不碰协程**。
 *
 * ## 为什么要把这段从 ViewModel 里抠出来
 *
 * 它原本是 ViewModel 的私有方法，看起来挺自然。但那样一来说「空白段该怎么显示」
 * 就得起一个 Robolectric+Room 环境、造一堆数据、等协程跑完 —— 一条断言要花好几秒。
 *
 * 抠成纯函数之后：给一串记录、给几张字典表，返回值是确定的。
 * 测试纯 JVM 秒级跑完，而且**覆盖得了那些极难在真机上复现的分支**
 * （事项被删了、零长度记录、进行中的那条终点怎么算）。
 *
 * 剩下的 ViewModel 只做「取数据 → 交给它 → 存进 state」这三步，薄到几乎不会错。
 */
object TimelineAssembler {

    /**
     * 空白段的显示名。
     *
     * ⚠️ **这里只有字，没有符号** —— 那个「▨」由界面自绘（`GapMark`）。
     *
     * 为什么不用字符：原型里的 `▨` 是 U+25A8，**安卓系统字体普遍没有这个字形**
     * （它不在 WGL4 字符集里），真机上直接渲染成一个空心豆腐块。
     * 而且字体回退谁也说不准，今天能显示明天换个 ROM 就未必。
     * 自绘一个方块反而既稳、又能做到形状完全可控。
     */
    const val GAP_NAME = "未记录"

    /** 事项被删掉后剩下的记录。理论上不该出现（删事项时会擦成空白），纯属兜底。 */
    const val ORPHAN_NAME = "（事项已删除）"

    /** 空白段的灰。特意挑的中性色 —— 不跟任何分类色撞。 */
    const val GAP_COLOR = "#94A3B8"

    /**
     * 装配一天。
     *
     * @param records 这一天的全部记录（顺序随意，内部会排）
     * @param cut 这一天该铺到哪 —— **进行中的那一条要用它当终点**
     * @param itemsById 事项字典（一次查完配成 map，别在循环里一条条查）
     * @param catsById 分类字典
     * @param tagsByRec 记录 id → 它挂的标签
     */
    fun day(
        records: List<RecordEntity>,
        cut: Int,
        itemsById: Map<String, ItemEntity>,
        catsById: Map<String, CategoryEntity>,
        tagsByRec: Map<String, List<TagEntity>>,
    ): DayView {
        var recorded = 0
        var gap = 0
        val rows = ArrayList<RecordRow>(records.size)
        var live: RecordRow? = null

        // 倒序：越晚的越靠上。
        // 你打开时间线最常想确认的是「我现在在干什么、刚干了什么」——
        // 最新的那条必须落在最容易看到的位置。
        records.sortedByDescending { it.startMin }.forEach { r ->
            val item = r.itemId?.let { itemsById[it] }
            val row = row(
                record = r,
                cut = cut,
                itemName = item?.name,
                itemColor = item?.colorHex,
                categoryName = item?.let { catsById[it.categoryId]?.name },
                tags = tagsByRec[r.id].orEmpty(),
            )

            // ⚠️ 统计必须用 row.durationMin，不要在别处再算一遍 ——
            //    两处算法一旦分岔，顶部那两个数字就会跟卡片上的对不上。
            if (row.isGap) gap += row.durationMin else recorded += row.durationMin
            if (row.isLive) live = row
            rows += row
        }

        return DayView(rows = rows, recordedMin = recorded, gapMin = gap, liveRow = live)
    }

    /** 半成品 → 一张可以直接画的卡。所有「这条该显示成什么样」的判断都收在这里。 */
    fun row(
        record: RecordEntity,
        cut: Int,
        itemName: String?,
        itemColor: String?,
        categoryName: String?,
        tags: List<TagEntity>,
    ): RecordRow {
        // 进行中的那条 `endMin` 是 null —— 它的「终点」就是此刻
        val endForCalc = record.endMin ?: cut
        val duration = (endForCalc - record.startMin).coerceAtLeast(0)
        val isLive = record.endMin == null

        // ⚠️ 判据以 `source == GAP` 为准，另加一条兜底：事项为空的记录也只能当空白显示。
        //    绝不能只判 `itemId == null` 来区分 —— 删事项时 itemId 会被清空，
        //    但**正常记录的 itemId 也可能为空**（那就是异常状态，也该显示成空白）。
        //
        //    ★ 这条判据本身定义在 `RecordEntity.isGap` —— 统计页用的是**同一条**。
        //      两处各写一遍的话，迟早会出现「卡片上不是空白、图上算成空白」这种对不上。
        val isGap = record.isGap

        return RecordRow(
            id = record.id,
            name = if (isGap) GAP_NAME else (itemName ?: ORPHAN_NAME),
            colorHex = if (isGap) GAP_COLOR else (itemColor ?: GAP_COLOR),
            startMin = record.startMin,
            endMin = record.endMin,
            startLabel = DayKey.minuteLabel(record.startMin),
            endLabel = if (isLive) "现在" else DayKey.minuteLabel(endForCalc),
            durationLabel = DayKey.duration(duration),
            durationMin = duration,
            isGap = isGap,
            isLive = isLive,
            fromTemplate = record.source == RecordSource.TEMPLATE,
            categoryName = if (isGap) null else categoryName,
            tags = if (isGap) emptyList() else tags.map { TagChip(it.id, it.name, it.colorHex) },
            note = if (isGap) "" else record.note,
        )
    }

    /**
     * 拼周条。
     *
     * @param viewingHasGap 正在看的那一天**实时**有没有空白（不靠查库，见下）
     * @param gapDays 这一周里「有空白记录」的日期集合
     *
     * ⚠️ 「正在看的那一天」必须用 [viewingHasGap] 而不是查 [gapDays] ——
     * 因为别的天没有经过 `ensureDense`，查出来的只是「已经落库的空白」。
     * 今天刚刚打开、还没补密时，查库会说「没有空白」，而实际上补密后一定有。
     * 原型也是这么分的（今天用实时值，其余查存储）。
     */
    fun week(
        dayKey: String,
        todayKey: String,
        viewingHasGap: Boolean,
        gapDays: Set<String>,
    ): List<WeekCell> = DayKey.weekOf(dayKey).map { key ->
        WeekCell(
            key = key,
            weekdayLabel = DayKey.weekdayCn(key),
            isViewing = key == dayKey,
            isToday = key == todayKey,
            isFuture = key > todayKey,
            hasGap = if (key == dayKey) viewingHasGap else key in gapDays,
        )
    }
}
