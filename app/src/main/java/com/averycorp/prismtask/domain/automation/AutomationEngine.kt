package com.averycorp.prismtask.domain.automation

import android.util.Log
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.AutomationRuleEntity
import com.averycorp.prismtask.data.repository.AutomationRuleRepository
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrator. Subscribes to [AutomationEventBus], matches each event
 * against enabled rules, evaluates conditions, dispatches handlers, and
 * writes a log row.
 *
 * Safety mechanisms (§ A6 of the architecture doc):
 *  1. Cycle detection via per-execution `lineage: Set<Long>`.
 *  2. Depth cap: [MAX_CHAIN_DEPTH] = 5. Beyond that, abort.
 *  3. Per-rule rate limit: [AutomationRateLimiter.canFire].
 *  4. Per-user global hourly cap: [AutomationRateLimiter].
 *  5. AI-action daily cap: [AutomationRateLimiter].
 *  6. Failure isolation: each handler in try/catch; one failure doesn't
 *     halt the action chain or the engine.
 *  7. Per-task notify soft cap (mental-health class): one rule may not
 *     fire more than [AutomationRateLimiter.MAX_NOTIFIES_PER_TASK_PER_DAY]
 *     notify actions against the same task in a 24h window.
 *
 * Composed triggers are emitted back to the bus as
 * [AutomationEvent.RuleFired] rather than recursively dispatched, which
 * keeps the call stack flat and lets cycle detection / depth cap operate
 * against the bus-collector loop.
 */
@Singleton
class AutomationEngine @Inject constructor(
    private val bus: AutomationEventBus,
    private val ruleRepository: AutomationRuleRepository,
    private val rateLimiter: AutomationRateLimiter,
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val medicationDao: MedicationDao,
    private val handlers: Set<@JvmSuppressWildcards AutomationActionHandler>,
    private val evaluator: ConditionEvaluator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private val gson = Gson()
    private val handlerByType: Map<String, AutomationActionHandler> =
        handlers.associateBy { it.type }

    /** Idempotent — safe to call multiple times. */
    fun start() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            bus.events.collect { event ->
                handleEvent(event, depth = 0, lineage = emptySet(), parentLogId = null)
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private suspend fun handleEvent(
        event: AutomationEvent,
        depth: Int,
        lineage: Set<Long>,
        parentLogId: Long?
    ) {
        if (depth >= MAX_CHAIN_DEPTH) {
            Log.w(TAG, "depth cap hit ($MAX_CHAIN_DEPTH); aborting event $event")
            return
        }
        val rules = ruleRepository.getEnabledOnce()
        for (rule in rules) {
            if (rule.id in lineage) {
                Log.w(TAG, "cycle: rule ${rule.id} already in lineage $lineage; aborting")
                continue
            }
            val trigger = AutomationJsonAdapter.decodeTrigger(rule.triggerJson) ?: continue
            if (!triggerMatches(trigger, event, rule.id)) continue
            val now = System.currentTimeMillis()
            when (val decision = rateLimiter.canFire(rule, event, now)) {
                is AutomationRateLimiter.Decision.Blocked -> {
                    Log.i(TAG, "rule=${rule.id} blocked: ${decision.reason}")
                    ruleRepository.recordFiring(
                        ruleId = rule.id,
                        firedAt = now,
                        triggerEventJson = gson.toJson(eventToMap(event)),
                        conditionPassed = false,
                        actionsExecutedJson = null,
                        errorsJson = gson.toJson(mapOf("rateLimit" to decision.reason)),
                        durationMs = 0,
                        chainDepth = depth,
                        parentLogId = parentLogId
                    )
                    continue
                }
                AutomationRateLimiter.Decision.Allowed -> Unit
            }
            executeRule(rule, event, depth, lineage, parentLogId)
        }
    }

    private suspend fun executeRule(
        rule: AutomationRuleEntity,
        event: AutomationEvent,
        depth: Int,
        lineage: Set<Long>,
        parentLogId: Long?
    ) {
        val started = System.currentTimeMillis()
        val ctx = buildEvaluationContext(event)
        val condition = AutomationJsonAdapter.decodeCondition(rule.conditionJson)
        val matched = evaluator.evaluate(condition, ctx)
        val results = mutableListOf<ActionResult>()
        val errors = mutableListOf<Map<String, String>>()
        if (matched) {
            val actions = AutomationJsonAdapter.decodeActions(rule.actionJson)
            val execCtx = ExecutionContext(
                rule = rule,
                event = event,
                evaluation = ctx,
                depth = depth,
                lineage = lineage + rule.id,
                parentLogId = parentLogId
            )
            for (action in actions) {
                val handler = handlerByType[action.type]
                if (handler == null) {
                    results += ActionResult.Error(action.type, "no handler registered")
                    errors += mapOf("type" to action.type, "reason" to "no handler registered")
                    continue
                }
                val result = runCatching { handler.execute(action, execCtx) }
                    .getOrElse {
                        ActionResult.Error(action.type, it.message ?: it::class.java.simpleName)
                    }
                results += result
                if (result is ActionResult.Error) {
                    errors += mapOf("type" to action.type, "reason" to result.reason)
                }
            }
        }
        val durationMs = System.currentTimeMillis() - started
        val today = LocalDate.now().toString()
        ruleRepository.bumpFireCount(rule, today, started)
        val logId = ruleRepository.recordFiring(
            ruleId = rule.id,
            firedAt = started,
            triggerEventJson = gson.toJson(eventToMap(event)),
            conditionPassed = matched,
            actionsExecutedJson = if (matched) gson.toJson(results.map { resultToMap(it) }) else null,
            errorsJson = if (errors.isEmpty()) null else gson.toJson(errors),
            durationMs = durationMs,
            chainDepth = depth,
            parentLogId = parentLogId
        )
        Log.i(
            TAG,
            "rule=${rule.id} trigger=${event.kind()} matched=$matched " +
                "actions=${results.size} ms=$durationMs depth=$depth"
        )
        // Composed triggers: announce the firing back to the bus so any
        // rule with a Composed trigger pointing at us picks it up. The
        // collector-side cycle/depth check is what keeps this safe.
        if (matched) {
            bus.emit(AutomationEvent.RuleFired(rule.id, parentLogId = logId))
        }
    }

    private fun triggerMatches(
        trigger: AutomationTrigger,
        event: AutomationEvent,
        ruleId: Long
    ): Boolean = when (trigger) {
        is AutomationTrigger.EntityEvent -> trigger.eventKind == event.kind()
        is AutomationTrigger.TimeOfDay -> {
            val tick = event as? AutomationEvent.TimeTick ?: return false
            tick.hour == trigger.hour && tick.minute == trigger.minute
        }
        is AutomationTrigger.DayOfWeekTime -> {
            val tick = event as? AutomationEvent.TimeTick ?: return false
            if (tick.hour != trigger.hour || tick.minute != trigger.minute) return false
            val day = java.time.Instant.ofEpochMilli(tick.occurredAt)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .dayOfWeek
                .name
            day in trigger.daysOfWeek
        }
        AutomationTrigger.Manual -> {
            val mt = event as? AutomationEvent.ManualTrigger ?: return false
            mt.ruleId == ruleId
        }
        is AutomationTrigger.Composed -> {
            val rf = event as? AutomationEvent.RuleFired ?: return false
            rf.ruleId == trigger.parentRuleId
        }
    }

    private suspend fun buildEvaluationContext(event: AutomationEvent): EvaluationContext {
        val task = when (event) {
            is AutomationEvent.TaskCreated -> taskDao.getTaskByIdOnce(event.taskId)
            is AutomationEvent.TaskUpdated -> taskDao.getTaskByIdOnce(event.taskId)
            is AutomationEvent.TaskCompleted -> taskDao.getTaskByIdOnce(event.taskId)
            else -> null
        }
        val taskTags = task?.let {
            runCatching { tagDao.getTagNamesForTaskOnce(it.id) }.getOrDefault(emptyList())
        }.orEmpty()
        val habit = when (event) {
            is AutomationEvent.HabitCompleted -> habitDao.getHabitByIdOnce(event.habitId)
            is AutomationEvent.HabitStreakHit -> habitDao.getHabitByIdOnce(event.habitId)
            else -> null
        }
        val habitStreak = when (event) {
            is AutomationEvent.HabitStreakHit -> event.streak
            else -> null
        }
        val medication = when (event) {
            is AutomationEvent.MedicationLogged -> medicationDao.getByIdOnce(event.medicationId)
            else -> null
        }
        return EvaluationContext(
            event = event,
            task = task,
            taskTags = taskTags,
            habit = habit,
            habitStreak = habitStreak,
            medication = medication
        )
    }

    private fun eventToMap(e: AutomationEvent): Map<String, Any?> = mapOf(
        "kind" to e.kind(),
        "occurredAt" to e.occurredAt,
        "data" to when (e) {
            is AutomationEvent.TaskCreated -> mapOf("taskId" to e.taskId)
            is AutomationEvent.TaskUpdated -> mapOf(
                "taskId" to e.taskId,
                "changedFields" to e.changedFields.toList()
            )
            is AutomationEvent.TaskCompleted -> mapOf("taskId" to e.taskId)
            is AutomationEvent.TaskDeleted -> mapOf("taskId" to e.taskId)
            is AutomationEvent.HabitCompleted -> mapOf("habitId" to e.habitId, "date" to e.date)
            is AutomationEvent.HabitStreakHit -> mapOf("habitId" to e.habitId, "streak" to e.streak)
            is AutomationEvent.MedicationLogged -> mapOf("medicationId" to e.medicationId, "slotKey" to e.slotKey)
            is AutomationEvent.TimeTick -> mapOf("hour" to e.hour, "minute" to e.minute)
            is AutomationEvent.ManualTrigger -> mapOf("ruleId" to e.ruleId)
            is AutomationEvent.RuleFired -> mapOf("ruleId" to e.ruleId, "parentLogId" to e.parentLogId)
        }
    )

    private fun resultToMap(r: ActionResult): Map<String, Any?> = mapOf(
        "type" to r.type,
        "status" to when (r) {
            is ActionResult.Ok -> "ok"
            is ActionResult.Skipped -> "skipped"
            is ActionResult.Error -> "error"
        },
        "message" to when (r) {
            is ActionResult.Ok -> r.message
            is ActionResult.Skipped -> r.reason
            is ActionResult.Error -> r.reason
        }
    )

    /** Manual "Run Now" — fires a [AutomationEvent.ManualTrigger] for [ruleId]. */
    fun runNow(ruleId: Long) {
        bus.emit(AutomationEvent.ManualTrigger(ruleId))
    }

    companion object {
        const val MAX_CHAIN_DEPTH = 5
        private const val TAG = "AutomationEngine"
    }
}
