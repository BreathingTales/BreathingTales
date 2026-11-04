package com.breathingtales.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Pleos Connect 디자인 가이드 기반 테마 (docs/design). 라이트/다크는 차량 시스템 설정을 따른다.
 * 수치는 Connect-S(2560×1440, 앱 영역 1700×1184) Figma 값 그대로다. Pleos 에뮬레이터는 density 160이라 1dp = 1px.
 */
object Pleos {
    val colors: PleosColors
        @Composable get() = LocalPleosColors.current
    val type: PleosTypography
        @Composable get() = LocalPleosType.current
}

/** 간격·모서리·버튼 높이 (form_factor 토큰, connect-s) */
object PleosDimens {
    /** window/apps/padding 기준 화면 안쪽 여백 */
    val screenPadding = 40.dp
    val gapSmall = 16.dp
    val gap = 24.dp
    val gapLarge = 40.dp

    /** Radius/m, Radius/l, radius level9 */
    val radiusMedium = 16.dp
    val radiusLarge = 24.dp
    val radiusCard = 32.dp

    /** buttons/basic/large·medium·small height, radius */
    val buttonLarge = 100.dp
    val buttonLargeRadius = 32.dp
    val buttonLargePadding = 40.dp
    val buttonLargeMinWidth = 140.dp
    val buttonMedium = 80.dp
    val buttonMediumRadius = 24.dp
    val buttonMediumPadding = 32.dp
    val buttonSmall = 64.dp
    val buttonSmallRadius = 16.dp
    val buttonSmallPadding = 24.dp
}

private val LocalPleosColors = staticCompositionLocalOf { PleosDarkColors }
private val LocalPleosType = staticCompositionLocalOf { PleosType }

@Composable
fun BreathingTalesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) PleosDarkColors else PleosLightColors
    // Material 컴포넌트(텍스트 입력, 페이저 등)도 같은 색을 쓰도록 맞춘다
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.buttonFilled, onPrimary = colors.onButtonFilled,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.surface, onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceHigh, onSurfaceVariant = colors.textSecondary,
            outline = colors.divider, error = colors.negative,
        )
    } else {
        lightColorScheme(
            primary = colors.buttonFilled, onPrimary = colors.onButtonFilled,
            background = colors.background, onBackground = colors.textPrimary,
            surface = colors.surface, onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceHigh, onSurfaceVariant = colors.textSecondary,
            outline = colors.divider, error = colors.negative,
        )
    }
    CompositionLocalProvider(LocalPleosColors provides colors, LocalPleosType provides PleosType) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
