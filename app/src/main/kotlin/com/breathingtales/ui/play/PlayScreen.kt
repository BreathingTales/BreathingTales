package com.breathingtales.ui.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breathingtales.domain.model.Breath
import com.breathingtales.domain.model.BreathPhase
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.Story
import com.breathingtales.domain.usecase.PauseReason
import com.breathingtales.domain.usecase.PlaybackState
import com.breathingtales.ui.UiTexts
import com.breathingtales.ui.components.rememberAssetImage
import com.breathingtales.ui.components.ButtonKind
import com.breathingtales.ui.components.PleosButton
import com.breathingtales.ui.park.FullScreenNotice
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens
import com.breathingtales.viewmodel.PlayScreenState
import com.breathingtales.viewmodel.PlayViewModel

@Composable
fun PlayScreen(
    onFinished: () -> Unit,
    onExit: () -> Unit,
    viewModel: PlayViewModel = hiltViewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    when (val s = screen) {
        PlayScreenState.Loading -> FullBleedMessage("이야기를 준비하는 중이에요")
        is PlayScreenState.Failed -> FullScreenNotice(title = s.message, buttonText = "처음으로", onButton = onExit)
        is PlayScreenState.Running -> RunningStory(
            story = s.story,
            childName = s.childName,
            playback = playback,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onStop = viewModel::stop,
            onFallback = viewModel::pressFallback,
            onSkip = viewModel::skipScene,
            hud = if (viewModel.demoHud) {
                { DemoHudFrom(viewModel) }
            } else {
                null
            },
        )
        is PlayScreenState.Ended -> {
            val pending by viewModel.pendingRestore.collectAsStateWithLifecycle()
            EndedStory(s.result, s.waitingForPark, pending, onFinished, onExit)
        }
    }
}

@Composable
private fun EndedStory(
    result: PlaybackState,
    waitingForPark: Set<String>,
    pending: List<String>,
    onFinished: () -> Unit,
    onExit: () -> Unit,
) {
    val failures = when (result) {
        is PlaybackState.Finished -> result.restoreFailures
        is PlaybackState.Aborted -> result.restoreFailures
        else -> emptyList()
    }
    // 기어를 바꿔 멈춘 경우 조명은 다시 P가 되면 되돌린다. 되돌리면 안내가 바뀐다
    val failureText = UiTexts.restoreFailure(failures, waitingForPark, pending)

    when {
        result is PlaybackState.Finished && failureText == null -> LaunchedEffect(Unit) { onFinished() }
        result is PlaybackState.Finished ->
            FullScreenNotice(title = "이야기가 끝났어요", detail = failureText, buttonText = "가족 대화 카드 보기", onButton = onFinished)
        result is PlaybackState.Aborted -> {
            val message = UiTexts.abortMessage(result.reason)
            if (message == null && failureText == null) {
                LaunchedEffect(Unit) { onExit() }
            } else {
                val detail = listOfNotNull(
                    "바람·온도·빛은 처음 상태로 되돌렸어요".takeIf { failureText == null },
                    failureText,
                ).joinToString("\n")
                FullScreenNotice(title = message ?: "이야기를 멈췄어요", detail = detail, buttonText = "처음으로", onButton = onExit)
            }
        }
        else -> LaunchedEffect(Unit) { onExit() }
    }
}

@Composable
private fun RunningStory(
    story: Story,
    childName: String,
    playback: PlaybackState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onFallback: () -> Unit,
    onSkip: () -> Unit,
    hud: (@Composable BoxScope.() -> Unit)?,
) {
    val paused = playback as? PlaybackState.Paused
    val active: PlaybackState.Active? = paused?.during ?: playback as? PlaybackState.Active
    val sceneIndex = active?.sceneIndex ?: 0
    val sceneColors = remember(story) { sceneColors(story) }

    Column(
        modifier = Modifier.fillMaxSize().padding(PleosDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall)) {
            Column(Modifier.weight(1f)) {
                Text(story.title, style = Pleos.type.titleMedium, color = Pleos.colors.textPrimary)
                SceneProgress(current = sceneIndex, total = story.scenes.size)
            }
            if (paused != null) {
                PleosButton("다시 듣기", onClick = onResume, kind = ButtonKind.Filled, modifier = Modifier.widthIn(min = 260.dp))
            } else {
                PleosButton("일시정지", onClick = onPause, enabled = active != null, modifier = Modifier.widthIn(min = 260.dp))
            }
            // 원하는 만큼 줄여 듣도록 (10). 멈춘 동안은 엔진도 무시한다
            PleosButton("다음 장면", onClick = onSkip, enabled = active != null && paused == null, modifier = Modifier.widthIn(min = 200.dp))
            PleosButton("그만하기", onClick = onStop, kind = ButtonKind.Danger, modifier = Modifier.widthIn(min = 260.dp))
        }

        SceneStage(
            story = story,
            sceneIndex = sceneIndex,
            tint = sceneColors.getOrElse(sceneIndex) { DEFAULT_TINT },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when (active) {
                null -> CenterMessage("준비하는 중이에요")
                is PlaybackState.Playing -> Subtitle(
                    speaker = UiTexts.speakerName(active.line.speaker),
                    text = UiTexts.fillChildName(active.line.text, childName),
                )
                is PlaybackState.WaitingInteraction -> InteractionPanel(
                    interaction = active.interaction,
                    successes = active.successes,
                    breathPhase = active.breathPhase,
                    enabled = paused == null,
                    onFallback = onFallback,
                )
            }
            hud?.invoke(this)
            if (paused != null) PausedOverlay(paused.reason)
        }
    }
}

@Composable
private fun BoxScope.DemoHudFrom(viewModel: PlayViewModel) {
    val monitor by viewModel.safetyMonitor.collectAsStateWithLifecycle()
    val vehicle by viewModel.vehicleState.collectAsStateWithLifecycle()
    val avd by viewModel.avd.collectAsStateWithLifecycle()
    DemoHud(monitor, vehicle, avd)
}

@Composable
private fun SceneProgress(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(width = if (i == current) 40.dp else 16.dp, height = 16.dp)
                    .clip(CircleShape)
                    .background(if (i <= current) Pleos.colors.textPrimary else Pleos.colors.textQuaternary),
            )
        }
        Text("  ${current + 1} / $total", style = Pleos.type.labelMedium, color = Pleos.colors.textTertiary)
    }
}

/** 장면 그림. 일러스트가 없으면 그 장면의 앰비언트 색으로 칠한다 (실내 조명과 같은 색) */
@Composable
private fun SceneStage(
    story: Story,
    sceneIndex: Int,
    tint: Color,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val image = rememberAssetImage(story.scenes.getOrNull(sceneIndex)?.image?.let { "stories/${story.id}/$it" })
    val animatedTint by animateColorAsState(tint, tween(1_500), label = "sceneTint")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PleosDimens.radiusCard))
            .background(Brush.verticalGradient(listOf(animatedTint, animatedTint.copy(alpha = 0.35f).compositeOver(Color(0xFF131417))))),
    ) {
        AnimatedContent(image, transitionSpec = { fadeIn(tween(800)) togetherWith fadeOut(tween(800)) }, label = "sceneImage") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        content()
    }
}

@Composable
private fun BoxScope.Subtitle(speaker: String, text: String) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC131417))))
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall),
    ) {
        Text(
            speaker,
            style = Pleos.type.bodySmall,
            color = Color(0xFF131417),
            modifier = Modifier.clip(CircleShape).background(Color(0xD6FFFFFF)).padding(horizontal = 24.dp, vertical = 6.dp),
        )
        AnimatedContent(text, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "line") {
            Text(it, style = Pleos.type.titleLarge, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}

/** 참여 안내 + 성공 표시 + 대체 버튼. 인식이 안 되면 부모가 버튼을 누른다 */
@Composable
private fun BoxScope.InteractionPanel(
    interaction: Interaction,
    successes: Int,
    breathPhase: BreathPhase?,
    enabled: Boolean,
    onFallback: () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6131417))))
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleosDimens.gap),
    ) {
        interaction.breath?.let { BreathCircle(breathPhase, it) }
        Text(UiTexts.interactionGuide(interaction, breathPhase), style = Pleos.type.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
        if (interaction.repeat > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(interaction.repeat) { i ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (i < successes) Pleos.colors.active else Color(0x52FFFFFF)),
                    )
                }
            }
        }
        PleosButton(
            interaction.fallbackButton,
            onClick = onFallback,
            kind = ButtonKind.Filled,
            enabled = enabled,
            modifier = Modifier.widthIn(min = 480.dp),
        )
        if (interaction.type.listens) {
            Text("소리가 잘 안 들리면 버튼을 눌러 주세요", style = Pleos.type.labelMedium, color = Color(0xA3FFFFFF))
        }
    }
}

/** 숨 맞추기 박자: 들이쉬는 동안 커지고 내쉬는 동안 작아지는 원 */
@Composable
private fun BreathCircle(phase: BreathPhase?, breath: Breath) {
    val inhaling = phase == BreathPhase.INHALE
    val scale by animateFloatAsState(
        targetValue = if (inhaling) 1f else 0.45f,
        animationSpec = tween(((if (inhaling) breath.inhaleSec else breath.exhaleSec) * 1000).toInt(), easing = LinearEasing),
        label = "breath",
    )
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(160.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(Color(0x66FFFFFF)),
        )
    }
}

@Composable
private fun BoxScope.PausedOverlay(reason: PauseReason) {
    Box(Modifier.matchParentSize().background(Pleos.colors.dim), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall)) {
            Text(UiTexts.pauseMessage(reason), style = Pleos.type.headlineSmall, color = Color.White)
            Text("위의 ‘다시 듣기’를 누르면 이어서 들려줘요", style = Pleos.type.bodyLargeNormal, color = Color(0xD6FFFFFF))
        }
    }
}

@Composable
private fun BoxScope.CenterMessage(text: String) {
    Text(text, style = Pleos.type.titleLarge, color = Color.White, modifier = Modifier.align(Alignment.Center))
}

@Composable
private fun FullBleedMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Pleos.type.titleLarge, color = Pleos.colors.textSecondary)
    }
}

private val DEFAULT_TINT = Color(0xFF44464E)

/** 장면마다 마지막으로 정한 앰비언트 색. 정하지 않은 장면은 앞 장면 색을 이어 쓴다 */
private fun sceneColors(story: Story): List<Color> {
    var last = DEFAULT_TINT
    return story.scenes.map { scene ->
        scene.effects.map { it.effect }.filterIsInstance<Effect.Ambient>().lastOrNull()?.let {
            last = Color(0xFF000000 or it.colorRgb.toLong())
        }
        last
    }
}
