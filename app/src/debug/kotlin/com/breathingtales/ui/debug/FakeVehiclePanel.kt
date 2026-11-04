package com.breathingtales.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.safety.ParkedRule
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.ui.components.ButtonKind
import com.breathingtales.ui.components.ButtonSize
import com.breathingtales.ui.components.PleosButton
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens
import com.breathingtales.viewmodel.FakeVehicleViewModel

/**
 * Fake 차량 조작 패널. `-PfakeVehicle=true` 빌드에서만 화면 오른쪽 가운데에 버튼이 생긴다.
 * 정차/주행/도어/주차 브레이크를 바꾸고, 엔진이 보낸 제어 명령과 현재 실내 상태를 본다.
 */
@Composable
fun BoxScope.FakeVehicleOverlay(viewModel: FakeVehicleViewModel = hiltViewModel()) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) {
        FakeVehiclePanel(viewModel, onClose = { open = false }, modifier = Modifier.align(Alignment.CenterEnd))
    } else {
        PleosButton(
            "Fake 차량",
            onClick = { open = true },
            size = ButtonSize.Small,
            // 화면 버튼들(시작, 그만하기, 처음으로)과 겹치지 않는 오른쪽 가운데
            modifier = Modifier.align(Alignment.CenterEnd).padding(PleosDimens.gapSmall),
        )
    }
}

@Composable
private fun FakeVehiclePanel(viewModel: FakeVehicleViewModel, onClose: () -> Unit, modifier: Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cabin by viewModel.cabin.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val parked = ParkedRule.evaluate(state)

    Column(
        modifier = modifier
            .width(560.dp)
            .fillMaxHeight()
            .padding(PleosDimens.gapSmall)
            .clip(RoundedCornerShape(PleosDimens.radiusLarge))
            .background(Pleos.colors.popup)
            .padding(PleosDimens.gap),
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall),
    ) {
        Text("Fake 차량", style = Pleos.type.titleSmall, color = Pleos.colors.textPrimary)
        Text(
            if (parked is ParkedState.NotParked) "정차 아님: ${parked.reasons.joinToString()}" else "정차 중",
            style = Pleos.type.labelMedium,
            color = if (parked is ParkedState.Parked) Pleos.colors.active else Pleos.colors.warning,
        )
        Text(state.describe(), style = Pleos.type.labelSmall, color = Pleos.colors.textTertiary)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PleosButton("정차 (P)", onClick = viewModel::park, size = ButtonSize.Small)
            PleosButton("주행 (D)", onClick = viewModel::drive, size = ButtonSize.Small)
            val doorOpen = state.doorsClosed == false
            PleosButton(if (doorOpen) "문 닫기" else "문 열기", onClick = { viewModel.setDoorOpen(!doorOpen) }, size = ButtonSize.Small)
            val brakeOn = state.parkingBrakeOn == true
            PleosButton(
                if (brakeOn) "브레이크 풀기" else "브레이크 걸기",
                onClick = { viewModel.setParkingBrake(!brakeOn) },
                size = ButtonSize.Small,
            )
        }

        Text("실내", style = Pleos.type.bodySmall, color = Pleos.colors.textPrimary)
        Text(cabin.describe(), style = Pleos.type.labelSmall, color = Pleos.colors.textSecondary)

        Text("제어 명령 (${log.size})", style = Pleos.type.bodySmall, color = Pleos.colors.textPrimary)
        val listState = rememberLazyListState()
        LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState) {
                items(log) {
                    Text(it, style = Pleos.type.labelSmall.copy(fontFamily = FontFamily.Monospace), color = Pleos.colors.textTertiary)
                }
            }
        }
        PleosButton("닫기", onClick = onClose, kind = ButtonKind.Filled, size = ButtonSize.Small)
    }
}

private fun VehicleState.describe() =
    "UX제한 $uxRestricted · 기어 $gear · 속도 $speedMps · 문닫힘 $doorsClosed · 주차브레이크 $parkingBrakeOn"

private fun CabinSnapshot.describe() = buildString {
    append("공조 ${if (hvacPowerOn == true) "켜짐" else "꺼짐"} · 풍량 $fanSpeed · 풍향 $airflow\n")
    append("온도 ${temperaturesC.values.joinToString { "%.1f℃".format(it) }}\n")
    append("조명 $ambientMode · #%06X · 밝기 $ambientBrightness\n".format(ambientColorRgb ?: 0))
    append("시트 열선 ${seatHeatLevels.values.joinToString()}")
}
