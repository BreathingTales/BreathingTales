package com.breathingtales.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.breathingtales.ui.debug.DebugOverlay
import com.breathingtales.ui.home.HomeScreen
import com.breathingtales.ui.play.PlayScreen
import com.breathingtales.ui.settings.SettingsScreen
import com.breathingtales.ui.talk.TalkCardsScreen
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.viewmodel.PlayViewModel

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PLAY = "play/{${PlayViewModel.ARG_STORY_ID}}"
    const val TALK = "talk/{${PlayViewModel.ARG_STORY_ID}}"

    fun play(storyId: String) = "play/$storyId"
    fun talk(storyId: String) = "talk/$storyId"
}

/** 홈 → 재생 → 가족 대화 카드 → 홈. 재생 화면을 떠나면 재생이 취소되고 실내가 복원된다 */
@Composable
fun AppNavHost(nav: NavHostController = rememberNavController()) {
    val storyArg = listOf(navArgument(PlayViewModel.ARG_STORY_ID) { type = NavType.StringType })
    Box(Modifier.fillMaxSize().background(Pleos.colors.background)) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onStart = { nav.navigate(Routes.play(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.PLAY, arguments = storyArg) { entry ->
                val storyId = entry.arguments?.getString(PlayViewModel.ARG_STORY_ID).orEmpty()
                PlayScreen(
                    // 대화 카드에서 뒤로 가도 재생 화면으로 돌아오지 않는다
                    onFinished = { nav.navigate(Routes.talk(storyId)) { popUpTo(Routes.HOME) } },
                    onExit = { nav.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
            composable(Routes.TALK, arguments = storyArg) {
                TalkCardsScreen(onDone = { nav.popBackStack(Routes.HOME, inclusive = false) })
            }
        }
        DebugOverlay()
    }
}
