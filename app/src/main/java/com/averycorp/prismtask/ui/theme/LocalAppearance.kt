package com.averycorp.prismtask.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether the user has enabled compact mode, which reduces vertical padding and
 * list item heights across the app.
 */
val LocalCompactMode = staticCompositionLocalOf { false }

/**
 * User-selected corner radius for task/project/habit cards.
 * Ranges from 0dp to 24dp.
 */
val LocalCardCornerRadius = compositionLocalOf<Dp> { 12.dp }

/**
 * Whether task card borders are visible. When false, cards render without an
 * outline stroke.
 */
val LocalShowCardBorders = staticCompositionLocalOf { true }

/**
 * Alpha (0f..1f) applied to the outer border of habit-style cards on both
 * the Habits tab (HabitItem, BuiltInHabitCard, BookableHabitItem,
 * SelfCareRoutineCard) and the Today screen (TodayHabitCheckItem,
 * BookableHabitReminderCard). Default 0.4f preserves the value used before
 * this knob was added.
 */
val LocalHabitBorderBrightness = compositionLocalOf { 0.4f }

/**
 * Standard vertical padding value that respects compact mode.
 */
val Dp.compactAware: Dp
    get() = this

/** Returns half the supplied dp if compact mode is enabled, otherwise the original value. */
fun Dp.compactOr(compact: Boolean, compactDp: Dp): Dp = if (compact) compactDp else this
