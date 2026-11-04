package com.breathingtales.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens

enum class ButtonKind {
    /** button/filled: 화면에서 가장 중요한 동작 하나 */
    Filled,

    /** button/basic: 그 밖의 동작 */
    Basic,

    /** 그만하기처럼 되돌릴 수 없는 동작 */
    Danger,
}

enum class ButtonSize(val height: Dp, val radius: Dp, val padding: Dp) {
    Large(PleosDimens.buttonLarge, PleosDimens.buttonLargeRadius, PleosDimens.buttonLargePadding),
    Medium(PleosDimens.buttonMedium, PleosDimens.buttonMediumRadius, PleosDimens.buttonMediumPadding),
    Small(PleosDimens.buttonSmall, PleosDimens.buttonSmallRadius, PleosDimens.buttonSmallPadding),
}

/** Pleos basic 버튼 (buttons/basic 토큰). 차량 화면 기준이라 기본 높이가 100dp다 */
@Composable
fun PleosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Basic,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
) {
    val colors = Pleos.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (container, content) = when {
        !enabled -> colors.buttonBasic to colors.textQuaternary
        kind == ButtonKind.Filled -> (if (pressed) colors.buttonFilledPressed else colors.buttonFilled) to colors.onButtonFilled
        kind == ButtonKind.Danger -> (if (pressed) colors.negative.copy(alpha = 0.8f) else colors.negative) to Color.White
        // 바탕(background)과 카드(surfaceHigh) 어느 쪽에 놓여도 보이도록 surface/accent를 쓴다
        else -> (if (pressed) colors.buttonBasicPressed.compositeOver(colors.surfaceAccent) else colors.surfaceAccent) to colors.textPrimary
    }
    val textStyle = when (size) {
        ButtonSize.Large -> Pleos.type.bodyLarge
        ButtonSize.Medium -> Pleos.type.bodyMedium
        ButtonSize.Small -> Pleos.type.bodySmall
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = PleosDimens.buttonLargeMinWidth)
            .height(size.height)
            .clip(RoundedCornerShape(size.radius))
            .background(container)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = size.padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = textStyle, color = content, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** 여러 값 중 하나 고르기 (controller/tab_background) */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pleos.colors
    Row(
        modifier = modifier
            .height(PleosDimens.buttonMedium)
            .clip(RoundedCornerShape(PleosDimens.buttonMediumRadius))
            .background(colors.tabBackground),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(6.dp)
                    .clip(RoundedCornerShape(PleosDimens.radiusLarge))
                    .background(if (isSelected) colors.buttonFilled else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = Pleos.type.bodyMedium,
                    color = if (isSelected) colors.onButtonFilled else colors.textSecondary,
                )
            }
        }
    }
}

/** surface/basic 카드 */
@Composable
fun PleosCard(
    modifier: Modifier = Modifier,
    color: Color = Pleos.colors.surfaceHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PleosDimens.radiusCard))
            .background(color)
            .padding(PleosDimens.gapLarge),
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall),
        content = content,
    )
}

/** 설정 화면 같은 곳의 제목 + 설명 */
@Composable
fun SectionTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall / 2)) {
        Text(title, style = Pleos.type.titleSmall, color = Pleos.colors.textPrimary)
        if (description != null) Text(description, style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
    }
}
