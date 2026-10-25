package com.misaka.timelog.data.repo

import com.misaka.timelog.data.entity.ItemEntity
import com.misaka.timelog.domain.ColorSeries

/**
 * 事项颜色的**唯一解析入口**。
 *
 * ## 库里 `items.colorHex` 有两种含义
 *
 * | 值 | 含义 | 谁负责算 |
 * |---|---|---|
 * | `""`（空） | **自动** —— 跟随所属分类的色系，按 `orderInCat` 取第 N 档渐变 | [ColorSeries.itemColor] |
 * | `#RRGGBB` | **手选** —— 用户自己挑的，任何地方都不许改写 | 用户 |
 *
 * ## ★★ 为什么必须收成一个函数
 *
 * 取事项颜色的地方有十几处：时间线色块、选择网格、统计图、小部件、模板页、
 * 桌面快捷窗……如果每处都自己写 `if (colorHex.isEmpty()) ColorSeries.itemColor(...)`，
 * **必然漏掉一处**。而漏掉的样子是「那个页面上这个事项是黑块/灰块」——
 * 不报错、不崩、单测也绿，只有肉眼能发现。
 *
 * 这是项目里栽过 8 次的那类坑（周条不跟翻页、模板页不跟配置、小部件各抄一份刷新链…），
 * 所以规矩定死：**调用方永远看不到空字符串**。
 *
 * ## 谁会绕过它（都要改掉）
 *
 * - 直接 `db.itemDao().all()` 的地方 —— 统计仓库曾经就是（已改）
 * - `SeedData` / `LegacyImport` 只**写**不读，不受影响
 *
 * @param catColorById 分类 id → 分类色 `#RRGGBB`。查不到就退回第 0 色系的渐变
 *                     （宁可颜色不对，也不能画出黑的）。
 */
internal fun resolveItemColors(
    items: List<ItemEntity>,
    catColorById: Map<String, String>,
): List<ItemEntity> {
    if (items.none { it.colorHex.isBlank() }) return items
    return items.map { i ->
        if (i.colorHex.isNotBlank()) {
            i
        } else {
            i.copy(
                colorHex = ColorSeries.itemColor(
                    catColorById[i.categoryId].orEmpty(),
                    i.orderInCat,
                ),
            )
        }
    }
}
