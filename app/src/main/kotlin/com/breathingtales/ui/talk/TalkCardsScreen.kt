package com.breathingtales.ui.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breathingtales.ui.components.ButtonKind
import com.breathingtales.ui.components.PleosButton
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens
import com.breathingtales.viewmodel.TalkCardsViewModel
import kotlinx.coroutines.launch

/** 가족 대화 카드 (기획서 7장 장면 5). 부모가 읽고 아이에게 묻는 질문을 한 장씩 넘긴다 */
@Composable
fun TalkCardsScreen(onDone: () -> Unit, viewModel: TalkCardsViewModel = hiltViewModel()) {
    val talk by viewModel.cards.collectAsStateWithLifecycle()
    val cards = talk?.cards.orEmpty()
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(PleosDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gap),
    ) {
        Column {
            Text("가족 대화 카드", style = Pleos.type.headlineSmall, color = Pleos.colors.textPrimary)
            Text(
                "이야기를 떠올리며 아이에게 물어봐 주세요. 카드를 옆으로 넘길 수 있어요",
                style = Pleos.type.bodySmallNormal,
                color = Pleos.colors.textTertiary,
            )
        }

        if (talk != null && cards.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("이 동화에는 대화 카드가 없어요", style = Pleos.type.titleSmall, color = Pleos.colors.textTertiary)
            }
        } else {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 160.dp),
                pageSpacing = PleosDimens.gapLarge,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(PleosDimens.radiusCard))
                        .background(Pleos.colors.surfaceHigh)
                        .padding(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${page + 1}",
                        style = Pleos.type.titleSmall,
                        color = Pleos.colors.textQuaternary,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    Text(cards[page], style = Pleos.type.headlineMedium, color = Pleos.colors.textPrimary, textAlign = TextAlign.Center)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall)) {
            PleosButton(
                "이전",
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0,
                modifier = Modifier.widthIn(min = 220.dp),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(cards.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 8.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (i == pager.currentPage) Pleos.colors.textPrimary else Pleos.colors.textQuaternary),
                    )
                }
            }
            if (pager.currentPage < cards.size - 1) {
                PleosButton(
                    "다음",
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.widthIn(min = 220.dp),
                )
            }
            PleosButton("처음으로", onClick = onDone, kind = ButtonKind.Filled, modifier = Modifier.widthIn(min = 300.dp))
        }
    }
}
