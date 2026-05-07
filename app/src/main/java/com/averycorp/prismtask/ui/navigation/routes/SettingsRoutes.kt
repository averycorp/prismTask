package com.averycorp.prismtask.ui.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.automation.AutomationLogScreen
import com.averycorp.prismtask.ui.screens.automation.AutomationRuleEditScreen
import com.averycorp.prismtask.ui.screens.automation.AutomationRuleListScreen
import com.averycorp.prismtask.ui.screens.automation.library.AutomationTemplateLibraryScreen
import com.averycorp.prismtask.ui.screens.settings.AccessibilityScreen
import com.averycorp.prismtask.ui.screens.settings.AccountSyncScreen
import com.averycorp.prismtask.ui.screens.settings.AdvancedTuningScreen
import com.averycorp.prismtask.ui.screens.settings.AppearanceScreen
import com.averycorp.prismtask.ui.screens.settings.BetaCodeRedemptionScreen
import com.averycorp.prismtask.ui.screens.settings.BrainModeScreen
import com.averycorp.prismtask.ui.screens.settings.CalendarScreen
import com.averycorp.prismtask.ui.screens.settings.DataBackupScreen
import com.averycorp.prismtask.ui.screens.settings.FocusTimerScreen
import com.averycorp.prismtask.ui.screens.settings.HabitsStreaksScreen
import com.averycorp.prismtask.ui.screens.settings.LayoutScreen
import com.averycorp.prismtask.ui.screens.settings.LifeModesScreen
import com.averycorp.prismtask.ui.screens.settings.MedicationSlotsScreen
import com.averycorp.prismtask.ui.screens.settings.NotificationsScreen
import com.averycorp.prismtask.ui.screens.settings.SubscriptionScreen
import com.averycorp.prismtask.ui.screens.settings.TaskDefaultsScreen
import com.averycorp.prismtask.ui.screens.settings.WellbeingScreen

/**
 * Settings category sub-screens. Extracted from [featureRoutes] so
 * adding another sub-screen doesn't grow that file, and so the
 * transition lambdas here infer the correct navigation receiver.
 */
internal fun NavGraphBuilder.settingsSubScreenRoutes(navController: NavHostController) {
    listOf<Pair<String, @Composable () -> Unit>>(
        "settings/account_sync" to { AccountSyncScreen(navController) },
        "settings/subscription" to { SubscriptionScreen(navController) },
        "settings/beta_code" to { BetaCodeRedemptionScreen(navController) },
        "settings/appearance" to { AppearanceScreen(navController) },
        "settings/layout" to { LayoutScreen(navController) },
        "settings/task_defaults" to { TaskDefaultsScreen(navController) },
        "settings/habits_streaks" to { HabitsStreaksScreen(navController) },
        "settings/life_modes" to { LifeModesScreen(navController) },
        "settings/focus_timer" to { FocusTimerScreen(navController) },
        "settings/brain_mode" to { BrainModeScreen(navController) },
        "settings/wellbeing" to { WellbeingScreen(navController) },
        "settings/calendar" to { CalendarScreen(navController) },
        "settings/notifications" to { NotificationsScreen(navController) },
        "settings/medication_slots" to { MedicationSlotsScreen(navController) },
        "settings/accessibility" to { AccessibilityScreen(navController) },
        "settings/data_backup" to { DataBackupScreen(navController) },
        "settings/advanced_tuning" to { AdvancedTuningScreen(navController) }
    ).forEach { (route, content) ->
        composable(
            route = route,
            enterTransition = horizontalSlideEnter,
            exitTransition = horizontalSlideExit,
            popEnterTransition = horizontalSlidePopEnter,
            popExitTransition = horizontalSlidePopExit
        ) { content() }
    }

    composable(
        route = PrismTaskRoute.Automation.route,
        enterTransition = horizontalSlideEnter,
        exitTransition = horizontalSlideExit,
        popEnterTransition = horizontalSlidePopEnter,
        popExitTransition = horizontalSlidePopExit
    ) { AutomationRuleListScreen(navController) }

    composable(
        route = PrismTaskRoute.AutomationTemplateLibrary.route,
        enterTransition = horizontalSlideEnter,
        exitTransition = horizontalSlideExit,
        popEnterTransition = horizontalSlidePopEnter,
        popExitTransition = horizontalSlidePopExit
    ) { AutomationTemplateLibraryScreen(navController) }

    composable(
        route = PrismTaskRoute.AutomationLog.route,
        arguments = listOf(
            navArgument("ruleId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        ),
        enterTransition = horizontalSlideEnter,
        exitTransition = horizontalSlideExit,
        popEnterTransition = horizontalSlidePopEnter,
        popExitTransition = horizontalSlidePopExit
    ) { backStack ->
        val ruleId = backStack.arguments?.getString("ruleId")?.toLongOrNull()
        AutomationLogScreen(navController, ruleIdFilter = ruleId)
    }

    composable(
        route = PrismTaskRoute.AutomationEdit.route,
        arguments = listOf(
            navArgument("ruleId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        ),
        enterTransition = horizontalSlideEnter,
        exitTransition = horizontalSlideExit,
        popEnterTransition = horizontalSlidePopEnter,
        popExitTransition = horizontalSlidePopExit
    ) { AutomationRuleEditScreen(navController) }
}
