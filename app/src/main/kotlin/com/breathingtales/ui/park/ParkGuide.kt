package com.breathingtales.ui.park

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.breathingtales.ui.components.ButtonKind
import com.breathingtales.ui.components.PleosButton
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens

/** 홈 위쪽 정차 안내 띠. 정차 조건을 채우기 전에는 시작 버튼이 꺼져 있다 */
@Composable
fun ParkGuideBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PleosDimens.radiusLarge))
            .background(Pleos.colors.warning.copy(alpha = 0.16f))
            .padding(horizontal = PleosDimens.gapLarge, vertical = PleosDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PleosDimens.gap),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Pleos.colors.warning))
        Text(message, style = Pleos.type.bodyLarge, color = Pleos.colors.textPrimary)
    }
}

/** 화면 가득 안내 (이야기가 중간에 멈췄을 때 등) */
@Composable
fun FullScreenNotice(
    title: String,
    detail: String? = null,
    buttonText: String,
    onButton: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(PleosDimens.screenPadding), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 1100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PleosDimens.gapLarge),
        ) {
            Text(title, style = Pleos.type.headlineSmall, color = Pleos.colors.textPrimary, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, style = Pleos.type.bodyLargeNormal, color = Pleos.colors.textSecondary, textAlign = TextAlign.Center)
            }
            PleosButton(buttonText, onClick = onButton, kind = ButtonKind.Filled, modifier = Modifier.widthIn(min = 480.dp))
        }
    }
}
