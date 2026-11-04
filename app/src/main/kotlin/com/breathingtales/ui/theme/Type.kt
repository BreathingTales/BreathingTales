package com.breathingtales.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect 글자 단계 (design assets, Typography). Connect-S 기준 크기/줄 간격.
 * Pleos 시스템 폰트(Asta Sans)는 차량 시스템 기본 폰트를 그대로 쓴다.
 * 가이드는 실제 글자 높이 18px 이상을 권장하므로 가장 작은 단계도 20sp다.
 */
@Immutable
data class PleosTypography(
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyLargeNormal: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val bodySmallNormal: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

val PleosType = PleosTypography(
    headlineLarge = style(60, 80, FontWeight.ExtraBold),
    headlineMedium = style(56, 72, FontWeight.ExtraBold),
    headlineSmall = style(48, 64, FontWeight.ExtraBold),
    titleLarge = style(40, 52, FontWeight.Bold),
    titleMedium = style(36, 44, FontWeight.Bold),
    titleSmall = style(32, 40, FontWeight.Bold),
    bodyLarge = style(30, 38, FontWeight.Bold),
    bodyLargeNormal = style(30, 38, FontWeight.Normal),
    bodyMedium = style(28, 36, FontWeight.Bold),
    bodySmall = style(26, 34, FontWeight.Bold),
    bodySmallNormal = style(26, 34, FontWeight.Normal),
    labelMedium = style(24, 28, FontWeight.Normal),
    labelSmall = style(20, 24, FontWeight.Normal),
)
