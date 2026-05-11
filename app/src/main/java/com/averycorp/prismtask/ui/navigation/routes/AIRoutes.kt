package com.averycorp.prismtask.ui.navigation.routes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.balance.WeeklyBalanceReportScreen
import com.averycorp.prismtask.ui.screens.batch.BatchHistoryScreen
import com.averycorp.prismtask.ui.screens.batch.BatchPreviewScreen
import com.averycorp.prismtask.ui.screens.briefing.DailyBriefingScreen
import com.averycorp.prismtask.ui.screens.chat.ChatScreen
import com.averycorp.prismtask.ui.screens.checkin.MorningCheckInScreen
import com.averycorp.prismtask.ui.screens.eisenhower.EisenhowerScreen
import com.averycorp.prismtask.ui.screens.extract.PasteConversationScreen
import com.averycorp.prismtask.ui.screens.mood.MoodAnalyticsScreen
import com.averycorp.prismtask.ui.screens.multicreate.MultiCreateBottomSheet
import com.averycorp.prismtask.ui.screens.planner.WeeklyPlannerScreen
import com.averycorp.prismtask.ui.screens.pomodoro.SmartPomodoroScreen
import com.averycorp.prismtask.ui.screens.projects.ProjectImportPreviewScreen
import com.averycorp.prismtask.ui.screens.review.WeeklyReviewDetailScreen
import com.averycorp.prismtask.ui.screens.review.WeeklyReviewScreen
import com.averycorp.prismtask.ui.screens.review.WeeklyReviewsListScreen
import com.averycorp.prismtask.ui.screens.screenshotimport.ScreenshotImportScreen

/**
 * AI and productivity-tool route definitions: Eisenhower matrix,
 * smart pomodoro, daily briefing, morning check-in, weekly planner
 * and review, balance and mood analytics, AI chat, and paste-import.
 */
internal fun NavGraphBuilder.aiRoutes(
    navController: NavHostController,
    initialSharedText: String? = null
) {
    horizontalSlideComposable(PrismTaskRoute.EisenhowerMatrix.route) {
        EisenhowerScreen(navController)
    }

    horizontalSlideComposable(PrismTaskRoute.SmartPomodoro.route) {
        SmartPomodoroScreen(navController)
    }

    horizontalSlideComposable(PrismTaskRoute.DailyBriefing.route) {
        DailyBriefingScreen(navController)
    }

    horizontalSlideComposable(PrismTaskRoute.MorningCheckIn.route) {
        MorningCheckInScreen(navController)
    }

    composable(route = PrismTaskRoute.MoodAnalytics.route) {
        MoodAnalyticsScreen(navController)
    }

    composable(route = PrismTaskRoute.WeeklyBalanceReport.route) {
        WeeklyBalanceReportScreen(navController)
    }

    composable(route = PrismTaskRoute.PasteConversation.route) {
        PasteConversationScreen(
            navController = navController,
            sharedText = initialSharedText
        )
    }

    composable(route = PrismTaskRoute.ScreenshotImport.route) {
        ScreenshotImportScreen(navController = navController)
    }

    composable(route = PrismTaskRoute.WeeklyReview.route) {
        WeeklyReviewScreen(navController)
    }

    composable(route = PrismTaskRoute.WeeklyReviewsList.route) {
        WeeklyReviewsListScreen(navController)
    }

    composable(
        route = PrismTaskRoute.WeeklyReviewDetail.route,
        arguments = listOf(
            navArgument("reviewId") { type = NavType.StringType }
        )
    ) {
        WeeklyReviewDetailScreen(navController)
    }

    horizontalSlideComposable(PrismTaskRoute.WeeklyPlanner.route) {
        WeeklyPlannerScreen(navController)
    }

    horizontalSlideComposable(
        route = PrismTaskRoute.AiChat.route,
        arguments = listOf(
            navArgument("taskId") {
                type = NavType.LongType
                defaultValue = -1L
            }
        )
    ) {
        ChatScreen(navController)
    }

    // Modal preview-then-pop flow: skip the slide transition so the
    // destination feels like a sheet over its caller, not a forward
    // navigation. Same convention as other modal-style entries across
    // the route files (NotificationRoutes, FeedbackRoutes, etc.).
    composable(
        route = PrismTaskRoute.BatchPreview.route,
        arguments = listOf(
            navArgument("command") {
                type = NavType.StringType
                defaultValue = ""
            }
        )
    ) { backStackEntry ->
        val command = backStackEntry.arguments?.getString("command").orEmpty()
        BatchPreviewScreen(
            navController = navController,
            commandText = command,
            onApproved = { _, _, _ ->
                navController.popBackStack()
            },
            onCancelled = { navController.popBackStack() }
        )
    }

    composable(route = PrismTaskRoute.BatchHistory.route) {
        BatchHistoryScreen(navController)
    }

    // Project import preview — same modal-style registration as
    // BatchPreview (no slide transition, feels like a sheet over caller).
    composable(
        route = PrismTaskRoute.ProjectImportPreview.route,
        arguments = listOf(
            navArgument("uri") {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument("asProject") {
                type = NavType.BoolType
                defaultValue = true
            }
        )
    ) { backStackEntry ->
        val uriArg = backStackEntry.arguments?.getString("uri").orEmpty()
        val asProject = backStackEntry.arguments?.getBoolean("asProject") ?: true
        ProjectImportPreviewScreen(
            navController = navController,
            uriString = uriArg.ifBlank { null },
            asProject = asProject,
            onApproved = { navController.popBackStack() },
            onCancelled = { navController.popBackStack() }
        )
    }

    composable(
        route = PrismTaskRoute.MultiCreate.route,
        arguments = listOf(
            navArgument("text") {
                type = NavType.StringType
                defaultValue = ""
            }
        )
    ) { backStackEntry ->
        val initialText = backStackEntry.arguments?.getString("text").orEmpty()
        MultiCreateBottomSheet(
            navController = navController,
            initialText = initialText
        )
    }
}
