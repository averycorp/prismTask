package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.LeisureBudgetRepository
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

data class StepState(
    val stepId: String,
    val label: String,
    val completedToday: Boolean,
    val timeOfDay: String
)

data class RoutineCardState(
    val routineType: String,
    val displayName: String,
    val steps: List<StepState>
) {
    val allComplete: Boolean get() = steps.isNotEmpty() && steps.all { it.completedToday }
}

data class HabitCardState(
    val habitId: Long,
    val name: String,
    val icon: String,
    val color: String,
    val completedToday: Boolean
)

data class AssignmentSummary(
    val id: Long,
    val title: String,
    val courseId: Long,
    val courseName: String,
    val courseColor: Int,
    val completed: Boolean
)

data class CourseCompletionStatus(
    val courseId: Long,
    val name: String,
    val code: String,
    val icon: String,
    val color: Int,
    val completedToday: Boolean
)

data class SchoolworkCardState(
    val courses: List<CourseCompletionStatus>,
    val assignmentsDueToday: List<AssignmentSummary>
) {
    val hasContent: Boolean get() = courses.isNotEmpty() || assignmentsDueToday.isNotEmpty()
}

/**
 * Today screen leisure card state. Replaces the v1.x music/flex-slot
 * model with a single minutes-vs-minimum display.
 */
data class LeisureBudgetCardState(
    val minutesLogged: Int,
    val targetMinutes: Int,
    val poolIsEmpty: Boolean,
    val suggestionName: String?,
    val suggestionCategory: String?
) {
    val progressFraction: Float
        get() = if (targetMinutes <= 0) 0f else (minutesLogged.toFloat() / targetMinutes).coerceIn(0f, 1f)

    val targetHit: Boolean get() = targetMinutes > 0 && minutesLogged >= targetMinutes

    companion object {
        fun empty(): LeisureBudgetCardState = LeisureBudgetCardState(
            minutesLogged = 0,
            targetMinutes = LeisureBudgetPreferences.DEFAULT_TARGET,
            poolIsEmpty = true,
            suggestionName = null,
            suggestionCategory = null
        )
    }
}

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
    val leisureBudget: LeisureBudgetCardState,
    val hasSeenHint: Boolean
) {
    val isEmpty: Boolean
        get() = morning == null &&
            bedtime == null &&
            housework == null &&
            houseworkRoutine == null &&
            (schoolwork == null || !schoolwork.hasContent) &&
            leisureBudget.minutesLogged == 0 &&
            leisureBudget.poolIsEmpty

    companion object {
        fun empty(): DailyEssentialsUiState = DailyEssentialsUiState(
            morning = null,
            bedtime = null,
            housework = null,
            houseworkRoutine = null,
            schoolwork = null,
            leisureBudget = LeisureBudgetCardState.empty(),
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
    private val leisureBudgetRepository: LeisureBudgetRepository,
    private val leisureBudgetPreferences: LeisureBudgetPreferences,
    private val dailyEssentialsPreferences: DailyEssentialsPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val advancedTuningPreferences: AdvancedTuningPreferences,
    private val localDateFlow: LocalDateFlow
) {
    fun observeToday(): Flow<DailyEssentialsUiState> =
        combine(
            localDateFlow.observe(taskBehaviorPreferences.getStartOfDay()),
            taskBehaviorPreferences.getStartOfDay()
        ) { date, sod -> date to sod }
            .flatMapLatest { (date, sod) ->
                val zone = java.time.ZoneId.systemDefault()
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
                    observeSchoolworkCard(todayStart, windowStart, windowEnd),
                    observeLeisureBudgetCard(date),
                    dailyEssentialsPreferences.hasSeenHint
                ) { args -> combineDailyEssentials(args) }
            }

    @Suppress("UNCHECKED_CAST")
    private fun combineDailyEssentials(args: Array<Any?>): DailyEssentialsUiState {
        val morning = args[0] as RoutineCardState?
        val bedtime = args[1] as RoutineCardState?
        val housework = args[2] as HabitCardState?
        val houseworkRoutine = args[3] as RoutineCardState?
        val schoolwork = args[4] as SchoolworkCardState?
        val leisureBudget = args[5] as LeisureBudgetCardState
        val seenHint = args[6] as Boolean

        return DailyEssentialsUiState(
            morning = morning,
            bedtime = bedtime,
            housework = housework,
            houseworkRoutine = houseworkRoutine,
            schoolwork = schoolwork,
            leisureBudget = leisureBudget,
            hasSeenHint = seenHint
        )
    }

    private fun observeLeisureBudgetCard(
        date: java.time.LocalDate
    ): Flow<LeisureBudgetCardState> = combine(
        leisureBudgetRepository.observeMinutesLoggedToday(),
        leisureBudgetPreferences.observeSnapshot(),
        leisureBudgetRepository.getActivities()
    ) { minutes, snap: LeisureBudgetSnapshot, activities ->
        val enabledPool = activities.filter { activity ->
            activity.enabled &&
                com.averycorp.prismtask.domain.model.LeisureCategory.fromStringOrNull(activity.category)
                    ?.let { it in snap.enabledCategories } == true
        }
        val suggestion = enabledPool.firstOrNull() // ViewModel resamples lazily via repository
        LeisureBudgetCardState(
            minutesLogged = minutes,
            targetMinutes = snap.targetForDate(date),
            poolIsEmpty = enabledPool.isEmpty(),
            suggestionName = suggestion?.name,
            suggestionCategory = suggestion?.category
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
        todayStart: Long,
        windowStart: Long,
        windowEnd: Long
    ): Flow<SchoolworkCardState?> {
        val coursesFlow: Flow<List<CourseCompletionStatus>> = combine(
            schoolworkDao.getActiveCourses(),
            schoolworkDao.getCompletionsForDate(todayStart)
        ) { courses, completions ->
            val doneIds = completions.filter { it.completed }.map { it.courseId }.toSet()
            courses.map { course ->
                CourseCompletionStatus(
                    courseId = course.id,
                    name = course.name,
                    code = course.code,
                    icon = course.icon,
                    color = course.color,
                    completedToday = course.id in doneIds
                )
            }
        }

        val assignmentsFlow: Flow<List<AssignmentSummary>> = combine(
            schoolworkDao.getAssignmentsDueBetween(windowStart, windowEnd),
            schoolworkDao.getActiveCourses()
        ) { assignments, courses ->
            val courseById: Map<Long, CourseEntity> = courses.associateBy { it.id }
            assignments.map { it.toSummary(courseById) }
        }

        return combine(coursesFlow, assignmentsFlow) { courses, assignments ->
            if (courses.isEmpty() && assignments.isEmpty()) {
                null
            } else {
                SchoolworkCardState(courses = courses, assignmentsDueToday = assignments)
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
            courseId = courseId,
            courseName = course?.name.orEmpty(),
            courseColor = course?.color ?: 0,
            completed = completed
        )
    }

    companion object {
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
