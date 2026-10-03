package com.meatsuitdiagnostics.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meatsuitdiagnostics.app.AppContainer
import com.meatsuitdiagnostics.app.ui.answer.AnswerNowScreen
import com.meatsuitdiagnostics.app.ui.answer.QuestionAnswerScreen
import com.meatsuitdiagnostics.app.ui.checkin.CheckinScreen
import com.meatsuitdiagnostics.app.ui.home.HomeScreen
import com.meatsuitdiagnostics.app.ui.settings.SettingsScreen
import com.meatsuitdiagnostics.app.ui.setup.SetupScreen
import kotlinx.coroutines.flow.StateFlow

/** Something the app was opened for: a notification tap or a setup link. */
sealed interface AppLink {
    data class OpenCheckin(val instanceId: String) : AppLink
    data object Setup : AppLink
}

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ANSWER_NOW = "answer"
    const val CHECKIN = "checkin/{instanceId}"
    const val ANSWER_QUESTION = "answer/{questionId}"

    fun checkin(instanceId: String) = "checkin/$instanceId"
    fun answerQuestion(questionId: Int) = "answer/$questionId"
}

@Composable
fun AppNavHost(container: AppContainer, links: StateFlow<AppLink?>, onLinkHandled: () -> Unit) {
    val configured by produceState<Boolean?>(initialValue = null) {
        value = container.settings.credentials() != null
    }
    val isConfigured = configured ?: return // blank for the moment it takes to read settings

    val nav = rememberNavController()
    val link by links.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        when (val current = link) {
            is AppLink.OpenCheckin -> nav.navigate(Routes.checkin(current.instanceId)) { launchSingleTop = true }
            AppLink.Setup -> nav.navigate(Routes.SETUP) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        onLinkHandled()
    }

    NavHost(navController = nav, startDestination = if (isConfigured) Routes.HOME else Routes.SETUP) {
        composable(Routes.SETUP) {
            val back: () -> Unit = { nav.popBackStack() }
            SetupScreen(
                container = container,
                onBack = if (nav.previousBackStackEntry != null) back else null,
                onDone = { nav.goHomeClearingBackStack() },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpenCheckin = { nav.navigate(Routes.checkin(it)) },
                onAnswerNow = { nav.navigate(Routes.ANSWER_NOW) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onRescan = { nav.navigate(Routes.SETUP) },
            )
        }
        composable(Routes.CHECKIN, arguments = listOf(navArgument("instanceId") { type = NavType.StringType })) { entry ->
            CheckinScreen(
                container = container,
                instanceId = entry.arguments?.getString("instanceId").orEmpty(),
                onClose = { nav.closeTo(Routes.HOME) },
            )
        }
        composable(Routes.ANSWER_NOW) {
            AnswerNowScreen(
                container = container,
                onPick = { nav.navigate(Routes.answerQuestion(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ANSWER_QUESTION, arguments = listOf(navArgument("questionId") { type = NavType.IntType })) { entry ->
            QuestionAnswerScreen(
                container = container,
                questionId = entry.arguments?.getInt("questionId") ?: -1,
                onBack = { nav.popBackStack() },
                onDone = { nav.closeTo(Routes.HOME) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onRescan = { nav.navigate(Routes.SETUP) },
            )
        }
    }
}

private fun NavHostController.goHomeClearingBackStack() = navigate(Routes.HOME) {
    popUpTo(graph.id) { inclusive = true }
}

/** Back to [route] if it's on the back stack (normal case), otherwise start fresh there. */
private fun NavHostController.closeTo(route: String) {
    if (!popBackStack(route, inclusive = false)) goHomeClearingBackStack()
}
