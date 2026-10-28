package com.jigong.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 字号全部用 sp —— 老人常把系统字体调到最大，用 sp 才会跟着变大。
 * 下面这些数值是规格，不是感觉；改小之前先想想"在阳台阳光下能不能看清"。
 */
object Dimen {
    val bigNumber = 48.sp          // 本月 22 天
    val title = 22.sp              // 顶部年月
    val dayCell = 20.sp            // 日历里的日期数字
    val weekday = 15.sp            // 一 二 三 …
    val primaryButton = 24.sp      // 今天记一天
    val sheetButton = 22.sp        // 半天 / 一天 / 删除
    val body = 18.sp

    val cellHeight = 68.dp         // 触控区，不是视觉尺寸
    val cellSize = 48.dp
    /**
     * 按钮高度在这里是**最小高度**，用的时候是 `heightIn(min = ...)` 而不是 `height(...)`。
     *
     * 原因：系统字号可以调到 200%（Android 14 起还改成非线性放大）。固定高度装不下放大后的
     * 文字时，文字会被上下裁掉一半 —— 实测 200% 下「分享这个月的工天」只剩「分享这个月的工」，
     * 而且字的顶部和底部都被切平。
     *
     * 官方《文字缩放》明确指出：不要给文字内容设固定高度，应该用能自适应的布局。
     * 换成 heightIn(min) 后，正常字号下高度和以前一模一样，放大字号时按钮自己长高。
     */
    val primaryButtonHeight = 76.dp
    val sheetButtonHeight = 60.dp
    val pagePadding = 16.dp
}

object Palette {
    val bg = Color.White
    val ink = Color(0xFF1A1A1A)         // 正文，不要更浅
    val inkSoft = Color(0xFF555555)     // 次要文字，最低到这里
    /**
     * 最浅的一档**文字**颜色。
     *
     * 原来是 #BDBDBD，对白底只有 1.88:1 —— 连「大字 3:1」都不够（WCAG 1.4.3：
     * 正文 4.5:1，18pt 以上或 14pt 粗体 3:1）。工地上大太阳底下这块会直接糊成白纸，
     * 而这档颜色正好用在「未来日期」的数字上 —— 那是老人最需要看清的地方。
     *
     * 定在 #767676：对白底 4.54:1，是能过正文线的最浅灰。再浅一档 #777777 就是 4.48:1，
     * 卡在线上（WCAG 不允许四舍五入）。#959595 是 3:1 的大字边界，只有 20sp 以上才敢用。
     */
    val hint = Color(0xFF767676)
    val accent = Color(0xFF1565C0)      // 已记工：实心深蓝
    val accentSoft = Color(0xFFE3F0FC)  // 今天未记：浅蓝底
    /** 只用于分隔线、禁用底的填充，**不要拿它写字** —— 见上面 hint 的说明 */
    val disabled = Color(0xFFBDBDBD)
    val line = Color(0xFFE6E6E6)
    val danger = Color(0xFFC62828)
    val sheetBg = Color.White
    val btnIdle = Color(0xFFF2F2F2)
}

@Composable
fun JigongTheme(content: @Composable () -> Unit) {
    // 固定浅色：工人多数在户外用，深色模式反而看不清
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.bg,
            onBackground = Palette.ink,
            surface = Palette.bg,
            onSurface = Palette.ink
        ),
        typography = Typography(),
        content = content
    )
}

/** 1.0 -> "1"，0.5 -> "0.5"，22.5 -> "22.5" */
fun fmtDays(d: Double): String =
    if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

private val WEEK_CN = listOf("一", "二", "三", "四", "五", "六", "日")

fun weekdayCn(d: java.time.LocalDate): String = "周" + WEEK_CN[d.dayOfWeek.value - 1]
