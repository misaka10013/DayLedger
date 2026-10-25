package com.misaka.timelog.ui.theme

import androidx.compose.material3.Typography

/**
 * 字体规格。
 *
 * M0 先用 Material 3 的默认字阶，不自定义。
 * 后面做时间线时，会针对「时间数字」单独调一版等宽数字，避免分钟数跳动时宽度抖动。
 */
internal val Typography = Typography()
