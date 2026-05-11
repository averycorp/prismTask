package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.LeisureLogEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.LeisureSlotConfig
import com.averycorp.prismtask.data.preferences.LeisureSlotId
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.LeisureRepository
import com.averycorp.prismtask.domain.model.SelfCareRoutines
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class LeisureKind { MUSIC, FLEX }

data class StepState(val stepId: String, val label: String, val completedToday: Boolean, val timeOfDay: String)

data class RoutineCardState(val routineType: String, val displayName: String, val steps: List<StepState>) {
    val allComplete: Boolean get() = steps.isNotEmpty() && steps.all { it.completedToday }
}

data class HabitCardState(val habitId: Long, val name: String, val icon: String, val color: String, val completedToday: Boolean)

data class AssignmentSummary(val id: Long, val title: String, val courseName: String, val courseColor: Int, val completed: Boolean)

data class SchoolworkCardState(val habit: HabitCardState?, val assignmentsDueToday: List<AssignmentSummary>) {
    val hasContent: Boolean get() = habit != null || assignmentsDueToday.isNotEmpty()
}

data class LeisureCardState(
    val kind: LeisureKind,
    val pickedForToday: String?,
    val doneForToday: Boolean,
    val label: String = when (kind) {
        LeisureKind.MUSIC -> "Music"
        LeisureKind.FLEX -> "Flex Leisure"
    },
    val enabled: Boolean = true
)

/**
 * Aggregated state for the Daily Essentials section. Any field may be null
 * when the corresponding card is unconfigured / empty and should be hidden.
 * The section itself is hidden when [isEmpty] AND the user has already
 * dismissed the onboarding hint.
 */
data class DailyEssentialsUiState(
    val morning: RoutineCardState?,
    val bedtime: RoutineCardState?,
    val housework: HabitCardState?,
    val houseworkRoutine: RoutineCardState?,
    val schoolwork: SchoolworkCardState?,
    val musicLeisure: LeisureCardState,
    val flexLeisure: LeisureCardState,
    val hasSeenHint: Boolean
) {
    val isEmpty: Boolean
        get() = morning == null &&
            bedtime == null &&
            housework == null &&
            houseworkRoutine == null &&
            (schoolwork == null || !schoolwork.hasContent) &&
            musicLeisure.pickedForToday == null &&
            flexLeisure.pickedForToday == null

    companion object {
        fun empty(): DailyEssentialsUiState = DailyEssentialsUiState(
            morning = null,
            bedtime = null,
            housework = null,
            houseworkRoutine = null,
            schoolwork = null,
            musicLeisure = LeisureCardState(LeisureKind.MUSIC, null, false),
            flexLeisure = LeisureCardState(LeisureKind.FLEX, null, false),
            hasSeenHint = false
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DailyEssentialsUseCase
@Inject
constructor(
    private val selfCareDao: SelfCareDao,
    private val schoolworkDao: SchoolworkDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val leisureRepository: LeisureRepository,
    private val dailyEssentialsPreferences: DailyEssentialsPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val leisurePreferences: LeisurePreferences,
    private val advancedTuningPreferences: AdvancedTuningPreferences,
    private val localDateFlow: LocalDateFlow
) {
    /**
     * Composite feed for the Daily Essentials section. All time windows use
     * [TaskBehaviorPreferences.getDayStartHour] so the section respects the
     * user's configured rollover hour.
     */
    fun observeToday(): Flow<DailyEssentialsUiState> =
        combine(
            localDateFlow.observe(taskBehaviorPreferences.getStartOfDay()),
            taskBehaviorPreferences.getStartOfDay()
        ) { date, sod -> date to sod }
            .flatMapLatest { (date, sod) ->
                val zone = java.time.ZoneId.systemDefault()
                // Derive the four window epochs from the canonical reactive
                // logical date — re-emits at every SoD boundary crossing,
                // not just on preference change. Replaces the four
                // `DayBoundary.*` snapshot calls that were locked at upstream
                // emission time. Per UTIL_DAYBOUNDARY_SWEEP_AUDIT.md § 5.
                val todayStart = date.atTime(sod.hour, sod.minute)
                    .atZone(zone).toInstant().toEpochMilli()
                val todayLocal = date.toString()
                val windowStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val windowEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

                combine(
                    observeRoutineCard("morning", "Morning Routine", todayStart),
                    observeRoutineCard("bedtime", "Bedtime Routine", todayStart),
                    observeHouseworkCard(todayLocal),
                    observeRoutineCard("housework", "Housework", todayStart),
                    observeSchoolworkCard(todayLocal, windowStart, windowEnd),
                    leisureRepository.getTodayLog(),
                    dailyEssentialsPreferences.hasSeenHint,
                    leisurePreferences.getSlotConfig(LeisureSlotId.MUSIC),
                    leisurePreferences.getSlotConfig(LeisureSlotId.FLEX)
                ) { args -> combineDailyEssentials(args) }
            }

    @Suppress("UNCHECKED_CAST")
    private fun combineDailyEssentials(args: Array<Any?>): DailyEssentialsUiState {
        val morning = args[0] as RoutineCardState?
        val bedtime = args[1] as RoutineCardState?
        val housework = args[2] as HabitCardState?
        val houseworkRoutine = args[3] as RoutineCardState?
        val schoolwork = args[4] as SchoolworkCardState?
        val leisureLog = args[5] as LeisureLogEntity?
        val seenHint = args[6] as Boolean
        val musicConfig = args[7] as LeisureSlotConfig
        val flexConfig = args[8] as LeisureSlotConfig

        return DailyEssentialsUiState(
            morning = morning,
            bedtime = bedtime,
            housework = housework,
            houseworkRoutine = houseworkRoutine,
            schoolwork = schoolwork,
            musicLeisure = LeisureCardState(
                kind = LeisureKind.MUSIC,
                pickedForToday = if (musicConfig.enabled) leisureLog?.musicPick else null,
                doneForToday = musicConfig.enabled && leisureLog?.musicDone == true,
                label = musicConfig.label,
                enabled = musicConfig.enabled
            ),
            flexLeisure = LeisureCardState(
                kind = LeisureKind.FLEX,
                pickedForToday = if (flexConfig.enabled) leisureLog?.flexPick else null,
                doneForToday = flexConfig.enabled && leisureLog?.flexDone == true,
                label = flexConfig.label,
                enabled = flexConfig.enabled
            ),
            hasSeenHint = seenHint
        )
    }

    private fun observeRoutineCard(
        routineType: String,
        displayName: String,
        todayStart: Long
    ): Flow<RoutineCardState?> = combine(
        selfCareDao.getStepsForRoutine(routineType),
        selfCareDao.getLogForDate(routineType, todayStart),
        advancedTuningPreferences.getSelfCareTierDefaults()
    ) { steps, log, tierDefaults ->
        if (steps.isEmpty()) return@combine null
        val tierOrder = SelfCareRoutines.getTierOrder(routineType)
        val defaultTier = tierDefaults.forRoutine(routineType)
        val selectedTier = resolveSelectedTier(log?.selectedTier, tierOrder, defaultTier)
            ?: return@combine null
        val visibleSteps = steps.filter {
            SelfCareRoutines.tierIncludes(tierOrder, selectedTier, it.tier)
        }
        if (visibleSteps.isEmpty()) return@combine null
        val takenIds = parseStepIds(log?.completedSteps)
        RoutineCardState(
            routineType = routineType,
            displayName = displayName,
            steps = visibleSteps.map { step ->
                StepState(
                    stepId = step.stepId,
                    label = step.label,
                    completedToday = step.stepId in takenIds,
                    timeOfDay = step.timeOfDay
                )
            }
        )
    }

    private fun observeHouseworkCard(todayLocal: String): Flow<HabitCardState?> =
        dailyEssentialsPreferences.houseworkHabitId.flatMapLatest { habitId ->
            if (habitId == null) {
                flowOf(null)
            } else {
                habitDao.getHabitById(habitId).flatMapLatest { habit ->
                    if (habit == null || habit.isArchived) {
                        flowOf(null)
                    } else {
                        habitCompletionDao.isCompletedOnDateLocal(habit.id, todayLocal)
                            .map { completed -> habit.toHabitCardState(completed) }
                    }
                }
            }
        }

    private fun observeSchoolworkCard(
        todayLocal: String,
        windowStart: Long,
        windowEnd: Long
    ): Flow<SchoolworkCardState?> {
        val habitCardFlow: Flow<HabitCardState?> =
            dailyEssentialsPreferences.schoolworkHabitId.flatMapLatest { habitId ->
                if (habitId == null) {
                    flowOf(null)
                } else {
                    habitDao.getHabitById(habitId).flatMapLatest { habit ->
                        if (habit == null || habit.isArchived) {
                            flowOf(null)
                        } else {
                            habitCompletionDao.isCompletedOnDateLocal(habit.id, todayLocal)
                                .map { completed -> habit.toHabitCardState(completed) }
                        }
                    }
                }
            }

        val assignmentsFlow: Flow<List<AssignmentSummary>> = combine(
            schoolworkDao.getAssignmentsDueBetween(windowStart, windowEnd),
            schoolworkDao.getActiveCourses()
        ) { assignments, courses ->
            val courseById: Map<Long, CourseEntity> = courses.associateBy { it.id }
            assignments.map { it.toSummary(courseById) }
        }

        return combine(habitCardFlow, assignmentsFlow) { habit, assignments ->
            if (habit == null && assignments.isEmpty()) {
                null
            } else {
                SchoolworkCardState(habit = habit, assignmentsDueToday = assignments)
            }
        }
    }

    private fun HabitEntity.toHabitCardState(completed: Boolean): HabitCardState =
        HabitCardState(
            habitId = id,
            name = name,
            icon = icon,
            color = color,
            completedToday = completed
        )

    private fun AssignmentEntity.toSummary(
        courseById: Map<Long, CourseEntity>
    ): AssignmentSummary {
        val course = courseById[courseId]
        return AssignmentSummary(
            id = id,
            title = title,
            courseName = course?.name.orEmpty(),
            courseColor = course?.color ?: 0,
            completed = completed
        )
    }

    companion object {
        /**
         * Resolves the tier used to filter a routine's steps. Order of
         * precedence: today's persisted log → user-configured default from
         * [SelfCareTierDefaults] → penultimate-of-order. Mirrors
         * `SelfCareViewModel.getSelectedTier` so the Today / Daily Essentials
         * cards agree with the dedicated Self-Care screen on a fresh day.
         */
        @JvmStatic
        internal fun resolveSelectedTier(
            logTier: String?,
            tierOrder: List<String>,
            defaultTier: String? = null
        ): String? {
            if (!logTier.isNullOrBlank() && logTier in tierOrder) return logTier
            if (tierOrder.isEmpty()) return null
            if (!defaultTier.isNullOrBlank() && defaultTier in tierOrder) return defaultTier
            return tierOrder.getOrNull(tierOrder.size - 2) ?: tierOrder.last()
        }

        /**
         * Mirrors the medication log parser but only needs step IDs — handles
         * both the legacy string-array format and the richer object format
         * with `{id, note, at, timeOfDay}` entries. Exposed on the companion
         * so unit tests don't need to instantiate the use case.
         */
        @JvmStatic
        internal fun parseStepIds(json: String?): Set<String> {
            if (json.isNullOrBlank() || json == "[]") return emptySet()
            return try {
                val parsed = JsonParser.parseString(json)
                if (!parsed.isJsonArray) return emptySet()
                val array = parsed.asJsonArray as JsonArray
                array.mapNotNull { element ->
                    when {
                        element.isJsonPrimitive -> element.asString
                        element.isJsonObject -> element.asJsonObject.get("id")?.asString
                        else -> null
                    }
                }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }
}
