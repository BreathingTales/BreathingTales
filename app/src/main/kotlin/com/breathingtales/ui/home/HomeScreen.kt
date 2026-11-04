package com.breathingtales.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.ui.UiTexts
import com.breathingtales.ui.components.ButtonKind
import com.breathingtales.ui.components.ButtonSize
import com.breathingtales.ui.components.PleosButton
import com.breathingtales.ui.components.PleosCard
import com.breathingtales.ui.components.SegmentedSelector
import com.breathingtales.ui.components.rememberAssetImage
import com.breathingtales.ui.components.ChildNameField
import com.breathingtales.ui.park.ParkGuideBanner
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens
import com.breathingtales.viewmodel.HomeViewModel
import com.breathingtales.viewmodel.StoryCard
import com.breathingtales.viewmodel.StoryList

@Composable
fun HomeScreen(
    onStart: (storyId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val parked by viewModel.parked.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val cards = (list as? StoryList.Loaded)?.cards.orEmpty()
    LaunchedEffect(cards) {
        if (selectedId == null || cards.none { it.id == selectedId }) selectedId = cards.firstOrNull()?.id
    }

    val reasons = (parked as? ParkedState.NotParked)?.reasons
    val parkGuide = when {
        parked == null -> "차량 상태를 확인하고 있어요"
        reasons != null -> UiTexts.parkGuide(reasons)
        else -> null
    }
    val canStart = parked == ParkedState.Parked && settings.hasChildName && selectedId != null

    Column(
        modifier = Modifier.fillMaxSize().padding(PleosDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("숨쉬는 동화", style = Pleos.type.headlineSmall, color = Pleos.colors.textPrimary)
                Text("온 가족이 함께 느끼는 오감 동화", style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
            }
            PleosButton("설정", onClick = onOpenSettings, size = ButtonSize.Medium)
        }

        if (parkGuide != null) ParkGuideBanner(parkGuide)

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(PleosDimens.gapLarge)) {
            // 왼쪽: 동화 목록
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall)) {
                Text("어떤 이야기를 들을까요?", style = Pleos.type.titleSmall, color = Pleos.colors.textPrimary)
                when (val l = list) {
                    StoryList.Loading -> Text("동화를 불러오는 중이에요", style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
                    is StoryList.Loaded -> {
                        if (l.cards.isEmpty() && l.errors.isEmpty()) {
                            Text("설치된 동화가 없어요", style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall)) {
                            items(l.cards, key = { it.id }) { card ->
                                StoryCardItem(card, selected = card.id == selectedId, onClick = { selectedId = card.id })
                            }
                            items(l.errors) { Text(it, style = Pleos.type.bodySmallNormal, color = Pleos.colors.warning) }
                        }
                    }
                }
            }

            // 오른쪽: 아이 이름, 강도, 시작
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleosDimens.gap)) {
                ChildNameCard(settings.childName, onSave = viewModel::setChildName)
                PleosCard(Modifier.fillMaxWidth()) {
                    Text("연출 강도", style = Pleos.type.bodyLarge, color = Pleos.colors.textPrimary)
                    Text("바람·온도·빛이 얼마나 크게 변할지 골라요", style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
                    SegmentedSelector(
                        options = Intensity.entries,
                        selected = settings.intensity,
                        label = UiTexts::intensity,
                        onSelect = viewModel::setIntensity,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!settings.hasChildName) {
                    Text("아이 이름을 먼저 알려 주세요", style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
                }
                PleosButton(
                    text = "이야기 시작",
                    onClick = { selectedId?.let(onStart) },
                    kind = ButtonKind.Filled,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StoryCardItem(card: StoryCard, selected: Boolean, onClick: () -> Unit) {
    val colors = Pleos.colors
    val shape = RoundedCornerShape(PleosDimens.radiusCard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceHigh)
            .then(if (selected) Modifier.border(BorderStroke(4.dp, colors.textPrimary), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(PleosDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PleosDimens.gap),
    ) {
        // 표지 그림(2:1). 없으면 해님(주황)과 바람(파랑) 색으로 대신한다
        val cover = rememberAssetImage(card.coverPath)
        Box(
            Modifier
                .width(300.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(PleosDimens.radiusLarge))
                .background(Brush.linearGradient(listOf(Color(0xFF4A90E2), Color(0xFFFFA94D)))),
        ) {
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, style = Pleos.type.titleSmall, color = colors.textPrimary)
            Text(
                "${card.ageRange.replace("-", "~")}세 · ${UiTexts.duration(card.durationSec)}",
                style = Pleos.type.bodySmallNormal,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun ChildNameCard(childName: String, onSave: (String) -> Unit) {
    var editing by remember(childName) { mutableStateOf(childName.isBlank()) }
    PleosCard(Modifier.fillMaxWidth()) {
        Text("아이 이름", style = Pleos.type.bodyLarge, color = Pleos.colors.textPrimary)
        if (editing) {
            Text("이야기 속에서 이름을 불러 줘요", style = Pleos.type.bodySmallNormal, color = Pleos.colors.textTertiary)
            ChildNameField(initial = childName, onSave = { onSave(it); editing = false })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(childName, style = Pleos.type.titleMedium, color = Pleos.colors.textPrimary, modifier = Modifier.weight(1f))
                PleosButton("바꾸기", onClick = { editing = true }, size = ButtonSize.Small)
            }
        }
    }
}
