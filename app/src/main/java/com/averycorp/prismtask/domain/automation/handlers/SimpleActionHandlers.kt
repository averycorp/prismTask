package com.averycorp.prismtask.domain.automation.handlers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.automation.ActionResult
import com.averycorp.prismtask.domain.automation.AutomationAction
import com.averycorp.prismtask.domain.automation.AutomationActionHandler
import com.averycorp.prismtask.domain.automation.ExecutionContext
import com.averycorp.prismtask.notifications.PomodoroTimerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `notify` handler — posts a notification via a dedicated automation
 * channel. Intentionally bypasses the profile-aware [NotificationHelper]
 * machinery: automation notifications don't need per-profile sound /
 * vibration / lock-screen handling, and the simpler path keeps the
 * channel ID stable across rule edits.
 */
@Singleton
class NotifyActionHandler @Inject constructor(@ApplicationContext private val context: Context) : AutomationActionHandler {
    override val type: String = "notify"

    override suspend fun execute(
        action: AutomationAction,
        ctx: ExecutionContext
    ): ActionResult {
        val notify = action as? AutomationAction.Notify
            ?: return ActionResult.Error(type, "wrong action shape")
        ensureChannel()
        val notificationId = (ctx.rule.id.hashCode() xor ctx.event.occurredAt.toInt())
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notify.title)
            .setContentText(notify.body)
            .setPriority(notify.priority.coerceIn(-2, 2))
            .setAutoCancel(true)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, builder.build())
        return ActionResult.Ok(type, "posted notification id=$notificationId")
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automation Rules",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Notifications posted by automation rules" }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "prismtask_automation"
    }
}

/**
 * `mutate.task` handler. Supports field updates — title, description,
 * priority, dueDate, isFlagged, lifeCategory, projectId — by translating
 * the [updates] map into a [TaskRepository.updateTask] call against a copy
 * of the trigger event's task. Also supports two list-shaped keys —
 * `tagsAdd` and `tagsRemove` — which mirror the case-insensitive
 * find-or-create logic from [BatchOperationsRepository.applyTagDelta]
 * (`docs/audits/AUTOMATION_STARTER_LIBRARY_ARCHITECTURE.md` § A0).
 *
 * Unknown keys, type mismatches, and wrong-shape `tagsAdd`/`tagsRemove`
 * values fail loud with [ActionResult.Error] so authors see the broken
 * rule in the firing log instead of a silent no-op
 * (`docs/audits/D_AUTOMATION_ACTION_SILENT_FAILURE_AUDIT.md` § A2).
 */
@Singleton
class MutateTaskActionHandler @Inject constructor(private val taskRepository: TaskRepository, private val tagDao: TagDao) :
    AutomationActionHandler {
    override val type: String = "mutate.task"

    override suspend fun execute(
        action: AutomationAction,
        ctx: ExecutionContext
    ): ActionResult {
        val mutate = action as? AutomationAction.MutateTask
            ?: return ActionResult.Error(type, "wrong action shape")
        val task = ctx.evaluation.task
            ?: return ActionResult.Skipped(type, "no task on event")
        var next = task
        val applied = mutableListOf<String>()
        for ((field, value) in mutate.updates) {
            when (field) {
                "title" -> {
                    val v = value as? String
                        ?: return ActionResult.Error(
                            type,
                            "wrong type for title: expected String, got ${value?.javaClass?.simpleName ?: "null"}"
                        )
                    next = next.copy(title = v)
                    applied += field
                }
                "description" -> {
                    if (value != null && value !is String) {
                        return ActionResult.Error(type, "wrong type for description: expected String?, got ${value.javaClass.simpleName}")
                    }
                    next = next.copy(description = value as? String)
                    applied += field
                }
                "priority" -> {
                    val v = (value as? Number)?.toInt()
                        ?: return ActionResult.Error(
                            type,
                            "wrong type for priority: expected Number, got ${value?.javaClass?.simpleName ?: "null"}"
                        )
                    next = next.copy(priority = v)
                    applied += field
                }
                "dueDate" -> {
                    if (value != null && value !is Number) {
                        return ActionResult.Error(type, "wrong type for dueDate: expected Number?, got ${value.javaClass.simpleName}")
                    }
                    next = next.copy(dueDate = (value as? Number)?.toLong())
                    applied += field
                }
                "isFlagged" -> {
                    val v = value as? Boolean
                        ?: return ActionResult.Error(
                            type,
                            "wrong type for isFlagged: expected Boolean, got ${value?.javaClass?.simpleName ?: "null"}"
                        )
                    next = next.copy(isFlagged = v)
                    applied += field
                }
                "lifeCategory" -> {
                    if (value != null && value !is String) {
                        return ActionResult.Error(type, "wrong type for lifeCategory: expected String?, got ${value.javaClass.simpleName}")
                    }
                    next = next.copy(lifeCategory = value as? String)
                    applied += field
                }
                "projectId" -> {
                    if (value != null && value !is Number) {
                        return ActionResult.Error(type, "wrong type for projectId: expected Number?, got ${value.javaClass.simpleName}")
                    }
                    next = next.copy(projectId = (value as? Number)?.toLong())
                    applied += field
                }
                "tagsAdd", "tagsRemove" -> {
                    if (value !is List<*>) {
                        return ActionResult.Error(
                            type,
                            "wrong type for $field: expected List<String>, got ${value?.javaClass?.simpleName ?: "null"}"
                        )
                    }
                    // Real handling happens in applyTagDelta below; tracking
                    // the key here so the success message stays honest.
                    applied += field
                }
                else -> return ActionResult.Error(type, "unknown field: $field")
            }
        }
        val wrote = next != task
        if (wrote) taskRepository.updateTask(next)

        applyTagDelta(
            taskId = task.id,
            addRaw = mutate.updates["tagsAdd"] as? List<*>,
            removeRaw = mutate.updates["tagsRemove"] as? List<*>
        )

        return if (applied.isEmpty()) {
            ActionResult.Skipped(type, "no updates")
        } else {
            ActionResult.Ok(
                type,
                "task ${task.id}: ${if (wrote) "updated" else "no-op"} ($applied)"
            )
        }
    }

    private suspend fun applyTagDelta(
        taskId: Long,
        addRaw: List<*>?,
        removeRaw: List<*>?
    ) {
        if (addRaw.isNullOrEmpty() && removeRaw.isNullOrEmpty()) return
        val nameToId = tagDao.getAllTagsOnce().associate { it.name.lowercase() to it.id }
        addRaw?.forEach { raw ->
            val name = (raw as? String)?.removePrefix("#")?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val tagId = nameToId[name.lowercase()]
                ?: tagDao.insert(TagEntity(name = name))
            tagDao.addTagToTask(TaskTagCrossRef(taskId = taskId, tagId = tagId))
        }
        removeRaw?.forEach { raw ->
            val name = (raw as? String)?.removePrefix("#")?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val tagId = nameToId[name.lowercase()] ?: return@forEach
            tagDao.removeTagFromTask(taskId, tagId)
        }
    }
}

/**
 * `mutate.habit` handler — supports `isArchived` toggle for v1. Other
 * habit fields can land later without a schema change.
 *
 * Unknown keys fail loud with [ActionResult.Error] so a future template
 * author who reaches for `name` / `streakCount` etc. sees a clear log
 * row instead of a silent no-op
 * (`docs/audits/D_AUTOMATION_ACTION_SILENT_FAILURE_AUDIT.md` § A3).
 */
@Singleton
class MutateHabitActionHandler @Inject constructor(private val habitRepository: HabitRepository) : AutomationActionHandler {
    override val type: String = "mutate.habit"

    override suspend fun execute(
        action: AutomationAction,
        ctx: ExecutionContext
    ): ActionResult {
        val mutate = action as? AutomationAction.MutateHabit
            ?: return ActionResult.Error(type, "wrong action shape")
        val habit = ctx.evaluation.habit
            ?: return ActionResult.Skipped(type, "no habit on event")
        val unknown = mutate.updates.keys - SUPPORTED_KEYS
        if (unknown.isNotEmpty()) {
            return ActionResult.Error(
                type,
                "unknown field(s): $unknown (supported: $SUPPORTED_KEYS)"
            )
        }
        val rawArchived = mutate.updates["isArchived"]
        if (rawArchived != null && rawArchived !is Boolean) {
            return ActionResult.Error(
                type,
                "wrong type for isArchived: expected Boolean, got ${rawArchived.javaClass.simpleName}"
            )
        }
        val nextArchived = rawArchived as? Boolean
        if (nextArchived != null) {
            if (nextArchived) {
                habitRepository.archiveHabit(habit.id)
            } else {
                habitRepository.unarchiveHabit(habit.id)
            }
            return ActionResult.Ok(type, "habit ${habit.id} archived=$nextArchived")
        }
        return ActionResult.Skipped(type, "no supported updates")
    }

    companion object {
        val SUPPORTED_KEYS = setOf("isArchived")
    }
}

/**
 * `log` handler — pure observability. Returns the message verbatim so
 * the engine can write it to the firing's `actions_executed_json`.
 */
@Singleton
class LogActionHandler @Inject constructor() : AutomationActionHandler {
    override val type: String = "log"

    override suspend fun execute(
        action: AutomationAction,
        ctx: ExecutionContext
    ): ActionResult {
        val log = action as? AutomationAction.LogMessage
            ?: return ActionResult.Error(type, "wrong action shape")
        return ActionResult.Ok(type, log.message)
    }
}

/**
 * `schedule.timer` handler — starts the existing [PomodoroTimerService]
 * foreground service via its canonical `start(...)` companion entry
 * point. [AutomationAction.ScheduleTimer.mode] maps to the service's
 * session-type constants (`FOCUS` / `WORK` → `SESSION_TYPE_WORK`,
 * `BREAK` → `SESSION_TYPE_BREAK`, `LONG_BREAK` → `SESSION_TYPE_LONG_BREAK`).
 *
 * Foreground-service start may throw `ForegroundServiceStartNotAllowedException`
 * on Android 12+ when the app is fully backgrounded (entity-event triggers
 * and time-based ticks both qualify). The handler catches that path and
 * returns [ActionResult.Error] so the engine logs a clean row rather than
 * crashing. Manual ("Run Now") triggers run while the rule list screen
 * is in the foreground and are unaffected.
 */
@Singleton
class ScheduleTimerActionHandler @Inject constructor(@ApplicationContext private val context: Context) : AutomationActionHandler {
    override val type: String = "schedule.timer"

    override suspend fun execute(
        action: AutomationAction,
        ctx: ExecutionContext
    ): ActionResult {
        val timer = action as? AutomationAction.ScheduleTimer
            ?: return ActionResult.Error(type, "wrong action shape")
        if (timer.durationMinutes <= 0) {
            return ActionResult.Error(type, "duration must be > 0 minutes")
        }
        val sessionType = when (timer.mode.uppercase()) {
            "FOCUS", "WORK" -> PomodoroTimerService.SESSION_TYPE_WORK
            "BREAK" -> PomodoroTimerService.SESSION_TYPE_BREAK
            "LONG_BREAK" -> PomodoroTimerService.SESSION_TYPE_LONG_BREAK
            else -> PomodoroTimerService.SESSION_TYPE_WORK
        }
        return runCatching {
            PomodoroTimerService.start(
                context = context,
                durationSeconds = timer.durationMinutes * 60,
                sessionIndex = 0,
                sessionType = sessionType
            )
            ActionResult.Ok(type, "started ${timer.mode} timer for ${timer.durationMinutes}m")
        }.getOrElse { e ->
            ActionResult.Error(
                type,
                "could not start timer: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }
}

/**
 * `mutate.medication` handler — supports the mutations that don't touch
 * dose / slot / tier-state: `isArchived` toggle and `name` rename. Mirrors
 * the [MutateHabitActionHandler] shape (boolean toggle) and the standard
 * [MedicationRepository.update] path. Dose-logging mutations (taking,
 * skipping) deliberately stay out of this handler because they have the
 * tier-state coherence risk surface called out in
 * [BatchOperationsRepository.applyMedicationMutation]; rules that need
 * those should use the `apply.batch` action with structured mutations
 * instead.
 */
@Singleton
class MutateMedicationActionHandler @Inject constructor(private val medicationRepository: MedicationRepository) : AutomationActionHandler {
    override val type: String = "mutate.medication"

    override suspend fun execute(
        action: AutomationAction,
        ctx: ExecutionContext
    ): ActionResult {
        val mutate = action as? AutomationAction.MutateMedication
            ?: return ActionResult.Error(type, "wrong action shape")
        val medication = ctx.evaluation.medication
            ?: return ActionResult.Skipped(type, "no medication on event")
        val unknown = mutate.updates.keys - SUPPORTED_KEYS
        if (unknown.isNotEmpty()) {
            return ActionResult.Error(
                type,
                "unknown field(s): $unknown (supported: $SUPPORTED_KEYS; " +
                    "dose mutations go through apply.batch)"
            )
        }
        val rawArchived = mutate.updates["isArchived"]
        if (rawArchived != null && rawArchived !is Boolean) {
            return ActionResult.Error(
                type,
                "wrong type for isArchived: expected Boolean, got ${rawArchived.javaClass.simpleName}"
            )
        }
        val rawName = mutate.updates["name"]
        if (rawName != null && rawName !is String) {
            return ActionResult.Error(
                type,
                "wrong type for name: expected String, got ${rawName.javaClass.simpleName}"
            )
        }
        var didSomething = false

        val nextArchived = rawArchived as? Boolean
        if (nextArchived != null) {
            if (nextArchived) {
                medicationRepository.archive(medication.id)
            } else {
                medicationRepository.unarchive(medication.id)
            }
            didSomething = true
        }

        val nextName = mutate.updates["name"] as? String
        if (nextName != null && nextName.isNotBlank() && nextName != medication.name) {
            medicationRepository.update(medication.copy(name = nextName))
            didSomething = true
        }

        return if (didSomething) {
            ActionResult.Ok(
                type,
                "updated medication ${medication.id} (${mutate.updates.keys})"
            )
        } else {
            ActionResult.Skipped(
                type,
                "no supported updates (only isArchived + name; dose mutations go through apply.batch)"
            )
        }
    }

    companion object {
        val SUPPORTED_KEYS = setOf("isArchived", "name")
    }
}
