package com.breathingtales.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Pleos Connect 색상 토큰 (docs/design/Pleos_Connect_design_assets_215.fig, Color Token).
 * 이름은 Figma 토큰 이름을 따르고, 값은 라이트/다크 모드의 실제 값이다.
 */
@Immutable
data class PleosColors(
    /** background/primary: 앱 바탕 (Z축 가장 아래) */
    val background: Color,
    /** surface/basic: 카드·패널 */
    val surface: Color,
    /** surface/high: 카드 안의 한 단계 높은 면 */
    val surfaceHigh: Color,
    /** surface/accent */
    val surfaceAccent: Color,
    /** background/popup */
    val popup: Color,
    /** text/dark/primary ~ quaternary */
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    /** Line/Divider/Regular */
    val divider: Color,
    /** button/basic: 투명한 기본 버튼 */
    val buttonBasic: Color,
    val buttonBasicPressed: Color,
    /** button/filled: 강조 버튼 (다크에서는 밝은 면 + 어두운 글자) */
    val buttonFilled: Color,
    val buttonFilledPressed: Color,
    val onButtonFilled: Color,
    val buttonDisabled: Color,
    /** controller/tab_background: 세그먼트 바탕 */
    val tabBackground: Color,
    /** informative/active · positive · negative */
    val active: Color,
    val positive: Color,
    val negative: Color,
    /** system/regulation/orange: 경고 */
    val warning: Color,
    /** system/dim/gray_tertiary: 화면 위 어둡게 덮기 */
    val dim: Color,
)

val PleosDarkColors = PleosColors(
    background = Color(0xFF1D1E21),
    surface = Color(0xFF1D1E21),
    surfaceHigh = Color(0xFF313236),
    surfaceAccent = Color(0xFF44464E),
    popup = Color(0xFF313236),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xD6FFFFFF),
    textTertiary = Color(0xA3FFFFFF),
    textQuaternary = Color(0x52FFFFFF),
    divider = Color(0xFF2F3B57),
    buttonBasic = Color(0x0DFFFFFF),
    buttonBasicPressed = Color(0x1AFFFFFF),
    buttonFilled = Color(0xFFE0E1E6),
    buttonFilledPressed = Color(0xFFC7C9CE),
    onButtonFilled = Color(0xFF131417),
    buttonDisabled = Color(0x33131417),
    tabBackground = Color(0x0DFFFFFF),
    active = Color(0xFF00E074),
    positive = Color(0xFF0082FF),
    negative = Color(0xFFFF4C28),
    warning = Color(0xFFFF8A00),
    dim = Color(0xB3131417),
)

val PleosLightColors = PleosColors(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2),
    surfaceAccent = Color(0xFFE0E1E6),
    popup = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF131417),
    textSecondary = Color(0xD6131417),
    textTertiary = Color(0xA3131417),
    textQuaternary = Color(0x52131417),
    divider = Color(0xFFDDE1EB),
    buttonBasic = Color(0x0D131417),
    buttonBasicPressed = Color(0x1A131417),
    buttonFilled = Color(0xFF44464E),
    buttonFilledPressed = Color(0xFF313236),
    onButtonFilled = Color(0xFFFFFFFF),
    buttonDisabled = Color(0x0D131417),
    tabBackground = Color(0x0D131417),
    active = Color(0xFF02C265),
    positive = Color(0xFF0064FF),
    negative = Color(0xFFFE3D16),
    warning = Color(0xFFFF8A00),
    dim = Color(0x99131417),
)
