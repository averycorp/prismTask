package com.averycorp.prismtask.ui.screens.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.ui.components.RichEmptyState
import com.averycorp.prismtask.ui.components.sync.SyncIndicatorHost
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.screens.habits.components.ActivityLogDialog
import com.averycorp.prismtask.ui.screens.habits.components.BookableHabitItem
import com.averycorp.prismtask.ui.screens.habits.components.BookingDialog
import com.averycorp.prismtask.ui.screens.habits.components.BuiltInHabitCard
import com.averycorp.prismtask.ui.screens.habits.components.HabitItem
import com.averycorp.prismtask.ui.screens.habits.components.HabitLogDialog
import com.averycorp.prismtask.ui.screens.habits.components.SelfCareRoutineCard
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.gridFloor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitListScreen(
    navController: NavController,
    viewModel: HabitListViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var habitToDelete by remember { mutableStateOf<HabitWithStatus?>(null) }
    var loggingHabit by remember { mutableStateOf<HabitWithStatus?>(null) }
    var bookingHabit by remember { mutableStateOf<HabitWithStatus?>(null) }
    var activityLogHabit by remember { mutableStateOf<HabitWithStatus?>(null) }
    var filter by remember { mutableStateOf("daily") }
    val recurringPeriods = setOf("weekly", "fortnightly", "monthly", "bimonthly", "quarterly")
    val filteredItems = remember(items, filter) {
        if (filter == "daily") {
            items.filter { item ->
                when (item) {
                    is HabitListItem.HabitItem -> item.habitWithStatus.habit.frequencyPeriod == "daily"
                    is HabitListItem.SelfCareItem -> true
                    is HabitListItem.BuiltInHabitItem -> true
                }
            }
        } else {
            items.filter { item ->
                item is HabitListItem.HabitItem && item.habitWithStatus.habit.frequencyPeriod in recurringPeriods
            }
        }
    }

    val baseTitle = "Habits"
    val prismColors = LocalPrismColors.current
    val displayFont = LocalPrismFonts.current.display
    val prismAttrs = com.averycorp.prismtask.ui.theme.LocalPrismAttrs.current

    // Per-theme title: Matrix uppercases and prefixes; Void adds a colored dot;
    // Cyberpunk uppercases; Synthwave uses the base title with glow.
    val screenTitle = when {
        prismAttrs.terminal -> baseTitle.uppercase()
        prismAttrs.displayUpper -> baseTitle.uppercase()
        else -> baseTitle
    }

    Scaffold(
        containerColor = prismColors.background,
        topBar = {
            TopAppBar(
                title = {
                    if (prismAttrs.editorial) {
                        // Void: "Habits." — trailing dot in primary color
                        androidx.compose.foundation.text.BasicText(
                            text = androidx.compose.ui.text.buildAnnotatedString {
                                append(screenTitle)
                                pushStyle(
                                    androidx.compose.ui.text.SpanStyle(color = prismColors.primary)
                                )
                                append(".")
                                pop()
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = displayFont,
                                fontWeight = FontWeight.Bold,
                                color = prismColors.onBackground
                            )
                        )
                    } else {
                        Text(
                            screenTitle,
                            fontFamily = displayFont,
                            fontWeight = FontWeight.Bold,
                            color = prismColors.onBackground,
                            letterSpacing = prismAttrs.displayTracking.sp
                        )
                    }
                },
                actions = {
                    SyncIndicatorHost(modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = prismColors.background,
                    titleContentColor = prismColors.onBackground
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(PrismTaskRoute.AddEditHabit.createRoute()) },
                containerColor = prismColors.primary
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Habit",
                    tint = prismColors.background
                )
            }
        }
    ) { padding ->
        val lazyListState = rememberLazyListState()
        val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val fromItemIndex = items.indexOfFirst { it.key == from.key }
            val toItemIndex = items.indexOfFirst { it.key == to.key }
            if (fromItemIndex >= 0 && toItemIndex >= 0) {
                viewModel.onReorderItems(fromItemIndex, toItemIndex)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .gridFloor()
                .padding(padding)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = filter == "daily",
                    onClick = { filter = "daily" },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Daily") }
                SegmentedButton(
                    selected = filter == "recurring",
                    onClick = { filter = "recurring" },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Recurring") }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    RichEmptyState(
                        icon = "\u2728",
                        title = if (filter == "daily") "Start Building Habits" else "No Recurring Habits",
                        description = if (filter == "daily") {
                            "Track daily routines at your own pace."
                        } else {
                            "Add a weekly, monthly, or other recurring habit."
                        },
                        actionLabel = "Create Habit",
                        onAction = { navController.navigate(PrismTaskRoute.AddEditHabit.createRoute()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }

                    items(filteredItems, key = { it.key }) { listItem ->
                        ReorderableItem(reorderableLazyListState, key = listItem.key) { isDragging ->
                            val elevation = if (isDragging) 8.dp else 0.dp
                            when (listItem) {
                                is HabitListItem.HabitItem -> {
                                    if (listItem.habitWithStatus.habit.isBookable) {
                                        BookableHabitItem(
                                            habitWithStatus = listItem.habitWithStatus,
                                            onClick = {
                                                navController.navigate(
                                                    PrismTaskRoute.HabitDetail.createRoute(listItem.habitWithStatus.habit.id)
                                                )
                                            },
                                            onBook = { bookingHabit = listItem.habitWithStatus },
                                            onLog = { activityLogHabit = listItem.habitWithStatus },
                                            onEdit = {
                                                navController.navigate(
                                                    PrismTaskRoute.AddEditHabit.createRoute(listItem.habitWithStatus.habit.id)
                                                )
                                            },
                                            onDelete = { habitToDelete = listItem.habitWithStatus },
                                            modifier = Modifier
                                                .shadow(elevation, MaterialTheme.shapes.medium)
                                                .longPressDraggableHandle()
                                        )
                                    } else {
                                        HabitItem(
                                            habitWithStatus = listItem.habitWithStatus,
                                            onToggle = {
                                                val hws = listItem.habitWithStatus
                                                if (hws.habit.hasLogging && !hws.isCompletedToday) {
                                                    loggingHabit = hws
                                                } else {
                                                    viewModel.onToggleCompletion(hws.habit.id, hws.isCompletedToday)
                                                }
                                            },
                                            onDecrement = {
                                                val hws = listItem.habitWithStatus
                                                if (hws.completionsToday > 0 && hws.dailyTarget > 1) {
                                                    viewModel.onDecrementCompletion(hws.habit.id)
                                                }
                                            },
                                            onClick = {
                                                if (listItem.habitWithStatus.habit.hasLogging) {
                                                    loggingHabit = listItem.habitWithStatus
                                                } else {
                                                    navController.navigate(
                                                        PrismTaskRoute.HabitAnalytics.createRoute(listItem.habitWithStatus.habit.id)
                                                    )
                                                }
                                            },
                                            onEdit = {
                                                navController.navigate(
                                                    PrismTaskRoute.AddEditHabit.createRoute(listItem.habitWithStatus.habit.id)
                                                )
                                            },
                                            onDelete = { habitToDelete = listItem.habitWithStatus },
                                            onLog = { loggingHabit = listItem.habitWithStatus },
                                            modifier = Modifier
                                                .shadow(elevation, MaterialTheme.shapes.medium)
                                                .longPressDraggableHandle()
                                        )
                                    }
                                }
                                is HabitListItem.SelfCareItem -> {
                                    SelfCareRoutineCard(
                                        routineType = listItem.routineType,
                                        cardData = listItem.cardData,
                                        onClick = {
                                            when (listItem.routineType) {
                                                "medication" -> navController.navigate(PrismTaskRoute.Medication.route)
                                                else -> navController.navigate(
                                                    PrismTaskRoute.SelfCare.createRoute(listItem.routineType)
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .shadow(elevation, MaterialTheme.shapes.medium)
                                            .longPressDraggableHandle()
                                    )
                                }
                                is HabitListItem.BuiltInHabitItem -> {
                                    BuiltInHabitCard(
                                        type = listItem.type,
                                        habitWithStatus = listItem.habitWithStatus,
                                        onClick = {
                                            when (listItem.type) {
                                                "school" -> navController.navigate(PrismTaskRoute.Schoolwork.route)
                                            }
                                        },
                                        progress = listItem.progress,
                                        modifier = Modifier
                                            .shadow(elevation, MaterialTheme.shapes.medium)
                                            .longPressDraggableHandle()
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    habitToDelete?.let { hws ->
        AlertDialog(
            onDismissRequest = { habitToDelete = null },
            title = { Text("Delete Habit") },
            text = { Text("Delete \"${hws.habit.name}\"? All completion history will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDeleteHabit(hws.habit.id)
                    habitToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { habitToDelete = null }) { Text("Cancel") }
            }
        )
    }

    loggingHabit?.let { hws ->
        HabitLogDialog(
            habitWithStatus = hws,
            viewModel = viewModel,
            onDismiss = { loggingHabit = null }
        )
    }

    bookingHabit?.let { hws ->
        BookingDialog(
            habitWithStatus = hws,
            onConfirm = { date, note ->
                viewModel.onSetBooked(hws.habit.id, true, date, note)
                bookingHabit = null
            },
            onUnbook = {
                viewModel.onSetBooked(hws.habit.id, false, null, null)
                bookingHabit = null
            },
            onDismiss = { bookingHabit = null }
        )
    }

    activityLogHabit?.let { hws ->
        ActivityLogDialog(
            habitWithStatus = hws,
            onConfirm = { date, notes ->
                viewModel.onLogActivity(hws.habit.id, date, notes)
                activityLogHabit = null
            },
            onDismiss = { activityLogHabit = null }
        )
    }
}
