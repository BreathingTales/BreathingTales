package com.breathingtales.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breathingtales.domain.model.BlowSensitivity
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.ui.UiTexts
import com.breathingtales.ui.components.ButtonSize
import com.breathingtales.ui.components.ChildNameField
import com.breathingtales.ui.components.PleosButton
import com.breathingtales.ui.components.PleosCard
import com.breathingtales.ui.components.SectionTitle
import com.breathingtales.ui.components.SegmentedSelector
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens
import com.breathingtales.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(PleosDimens.screenPadding), verticalArrangement = Arrangement.spacedBy(PleosDimens.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("부모 설정", style = Pleos.type.headlineSmall, color = Pleos.colors.textPrimary, modifier = Modifier.weight(1f))
            PleosButton("닫기", onClick = onBack, size = ButtonSize.Medium)
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PleosDimens.gap),
        ) {
            PleosCard(Modifier.fillMaxWidth()) {
                SectionTitle("아이 이름", "이야기 속에서 이름을 불러 줘요")
                // 저장한 이름이 바뀌면 입력 칸도 새 값으로 시작한다
                key(settings.childName) { ChildNameField(initial = settings.childName, onSave = viewModel::setChildName) }
            }
            PleosCard(Modifier.fillMaxWidth()) {
                SectionTitle("연출 강도", "바람·온도·빛 변화의 크기예요. ‘강’도 안전 범위를 넘지 않아요")
                SegmentedSelector(Intensity.entries, settings.intensity, UiTexts::intensity, viewModel::setIntensity, Modifier.fillMaxWidth())
            }
            PleosCard(Modifier.fillMaxWidth()) {
                SectionTitle(
                    "숨 불기 민감도",
                    "아이가 “후~” 불어도 반응이 없으면 ‘예민하게’, 말소리에도 반응하면 ‘둔하게’로 바꿔 주세요",
                )
                SegmentedSelector(
                    BlowSensitivity.entries,
                    settings.blowSensitivity,
                    UiTexts::blowSensitivity,
                    viewModel::setBlowSensitivity,
                    Modifier.fillMaxWidth(),
                )
            }
            PleosCard(Modifier.fillMaxWidth()) {
                SectionTitle(
                    "시트 들썩임",
                    "아이가 바람에 붕 떠오르는 장면에서 조수석을 위로 살짝 올렸다 내려요. " +
                        "조수석에 어른이 앉아 안전벨트를 맸을 때만, 한 번, 1초 안에 움직여요. " +
                        "뒷좌석 아이의 발이 조수석 아래에 있지 않은지 확인해 주세요",
                )
                SegmentedSelector(
                    listOf(false, true),
                    settings.seatMotion,
                    { if (it) "켜기" else "끄기" },
                    viewModel::setSeatMotion,
                    Modifier.fillMaxWidth(),
                )
            }
            if (viewModel.demoAvailable) {
                PleosCard(Modifier.fillMaxWidth()) {
                    SectionTitle("시연 모드 (개발용)", "재생 화면에 안전 장치가 보낸 차량 값(요청 → 허용)과 정차 상태를 띄워요")
                    SegmentedSelector(
                        listOf(false, true),
                        settings.demoHud,
                        { if (it) "켜기" else "끄기" },
                        viewModel::setDemoHud,
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            PleosCard(Modifier.fillMaxWidth()) {
                SectionTitle("앱 정보")
                UiTexts.credits.forEach { Text(it, style = Pleos.type.labelMedium, color = Pleos.colors.textSecondary) }
            }
        }
    }
}
