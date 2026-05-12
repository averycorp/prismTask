package com.averycorp.prismtask.ui.navigation.routes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.leisure.LeisurePoolScreen
import com.averycorp.prismtask.ui.screens.medication.MedicationLogScreen
import com.averycorp.prismtask.ui.screens.medication.MedicationRefillScreen
import com.averycorp.prismtask.ui.screens.medication.MedicationScreen
import com.averycorp.prismtask.ui.screens.schoolwork.AddEditCourseScreen
import com.averycorp.prismtask.ui.screens.schoolwork.SchoolworkScreen
import com.averycorp.prismtask.ui.screens.schoolwork.SyllabusReviewScreen
import com.averycorp.prismtask.ui.screens.selfcare.SelfCareScreen

/**
 * Life-mode route definitions: leisure, schoolwork, self-care, and
 * medication tracking (including step logs and refills).
 */
internal fun NavGraphBuilder.modeRoutes(navController: NavHostController) {
    simpleSlideComposable(PrismTaskRoute.Leisure.route) {
        LeisurePoolScreen(navController)
    }

    // Leisure Budget v2.0: the v1.x LeisureSettings sub-route is folded
    // back into LeisurePoolScreen (pool management + settings live on
    // one screen). The LeisureSettings route is kept for backward-compat
    // with deep links / cached nav entries — it just bounces to the
    // pool screen.
    horizontalSlideComposable(PrismTaskRoute.LeisureSettings.route) {
        LeisurePoolScreen(navController)
    }

    simpleSlideComposable(PrismTaskRoute.Schoolwork.route) {
        SchoolworkScreen(navController)
    }

    horizontalSlideComposable(
        route = PrismTaskRoute.SyllabusReview.route,
        arguments = listOf(
            navArgument("uri") {
                type = NavType.StringType
                defaultValue = ""
            }
        )
    ) {
        SyllabusReviewScreen(navController)
    }

    horizontalSlideComposable(
        route = PrismTaskRoute.SelfCare.route,
        arguments = listOf(
            navArgument("routineType") {
                type = NavType.StringType
                defaultValue = "morning"
            }
        )
    ) {
        SelfCareScreen(navController)
    }

    horizontalSlideComposable(PrismTaskRoute.Medication.route) {
        MedicationScreen(navController)
    }

    horizontalSlideComposable(PrismTaskRoute.MedicationLog.route) {
        MedicationLogScreen(navController)
    }

    composable(route = PrismTaskRoute.MedicationRefill.route) {
        MedicationRefillScreen(navController)
    }

    horizontalSlideComposable(
        route = PrismTaskRoute.AddEditCourse.route,
        arguments = listOf(
            navArgument("courseId") {
                type = NavType.LongType
                defaultValue = -1L
            }
        )
    ) {
        AddEditCourseScreen(navController)
    }
}
