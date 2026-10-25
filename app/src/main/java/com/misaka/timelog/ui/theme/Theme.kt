package com.misaka.timelog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    secondary = SlateSecondary,
    background = LightBackground,
    surface = LightSurface,
)

private val DarkColors = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = BlueOnPrimaryDark,
    primaryContainer = BluePrimaryContainerDark,
    secondary = SlateSecondaryDark,
    background = DarkBackground,
    surface = DarkSurface,
)

/**
 * 全局主题。
 *
 * 关于「动态取色」（跟随壁纸变色）：这里**特意关掉**。
 * 原因：本 App 里「颜色」是有语义的 —— 用户给每个事项挑的颜色就是它的身份标识。
 * 如果让系统壁纸去改变整个界面的主色调，会和这些语义色打架，反而更难辨认。
 */
@Composable
fun TimeLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
