package com.fnmusic.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 「午夜流光」配色体系：深蓝黑底 + 珊瑚金主色 + 青绿辅助色。
 * 全局统一使用本调色板，保证各页面颜色一致、焦点明确、明暗层次清晰。
 */
object FnColors {
    /** 最深底色：近黑蓝，作为所有页面的基底。 */
    val Background = Color(0xFF070A10)

    /** 卡片/容器表面。 */
    val Surface = Color(0xFF10161F)

    /** 更高一级的表面（弹层、输入框、选中容器）。 */
    val SurfaceAlt = Color(0xFF161D29)

    /** 主文字。 */
    val Text = Color(0xFFF2F4F8)

    /** 次要文字。 */
    val Muted = Color(0xFF98A2B3)

    /** 主色：珊瑚橙，用于焦点、选中与品牌强调。 */
    val Coral = Color(0xFFFF7A5C)

    /** 主色渐变伴侣：暖金。 */
    val Amber = Color(0xFFFFB46B)

    /** 辅助色：青绿，用于随机漫游/播放相关。 */
    val Teal = Color(0xFF4FD1A5)

    /** 辅助色：湖蓝。 */
    val Cyan = Color(0xFF5FC8FF)

    /** 辅助色：紫罗兰，用于收藏/浪漫氛围。 */
    val Violet = Color(0xFF9B8CFF)

    /** 警示/歌单图标等。 */
    val Warning = Color(0xFFE8C36A)

    /** 焦点填充：主色向表面融合的柔和底。 */
    val FocusFill = lerp(Coral, SurfaceAlt, 0.55f)
}

/** 常用渐变笔刷：横向统一的“流光”渐变，用于强调与氛围。 */
object FnGradients {
    /** 珊瑚→暖金：主品牌/选中强调。 */
    val Primary = Brush.linearGradient(listOf(FnColors.Coral, FnColors.Amber))

    /** 青绿→湖蓝：随机漫游、播放氛围。 */
    val Calm = Brush.linearGradient(listOf(FnColors.Teal, FnColors.Cyan))

    /** 紫罗兰→珊瑚：收藏、浪漫氛围。 */
    val Twilight = Brush.linearGradient(listOf(FnColors.Violet, FnColors.Coral))

    /** 深空：表面→更深的背景。 */
    val Deep = Brush.verticalGradient(listOf(FnColors.Surface, FnColors.Background))
}

@Composable
fun FnMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FnColors.Coral,
            secondary = FnColors.Teal,
            tertiary = FnColors.Violet,
            background = FnColors.Background,
            surface = FnColors.Surface,
            onPrimary = FnColors.Background,
            onSecondary = FnColors.Background,
            onTertiary = FnColors.Background,
            onBackground = FnColors.Text,
            onSurface = FnColors.Text,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides FnColors.Text, content = content)
    }
}
