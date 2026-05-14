package com.averycorp.prismtask.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.averycorp.prismtask.data.diagnostics.DiagnosticLogger
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotOverrideDao
import com.averycorp.prismtask.data.seed.BuiltInHabitVersionRegistry
import com.averycorp.prismtask.domain.usecase.RecentMoodSignal
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Unified broadcast receiver for habit and medication reminder alarms.
 *
 * **Legacy (habit) path** — intent carries a `"habitId"` extra. This was
 * the original path when the scheduler was named `MedicationReminderScheduler`
 * (pre v1.4 medication-top-level refactor). Still active — habits and
 * the transitional `MedicationPreferences.specificTimes` flow both use it.
 *
 * **Medication path** — intent carries a `"medicationId"` extra. Fires for
 * alarms scheduled by the v1.4+ [MedicationReminderScheduler] that operates
 * on [com.averycorp.prismtask.data.local.entity.MedicationEntity].
 *
 * **Slot-interval path** — intent carries an `"intervalSlotId"` extra and
 * `medicationId == -1L`. Fires for INTERVAL-mode slot alarms registered by
 * [MedicationIntervalRescheduler.registerAlarmForSlot]. Re-anchoring is
 * handled by the rescheduler's dose-change Flow observer; this branch only
 * surfaces the notification.
 *
 * **Slot-clock path** — intent carries a `"clockSlotId"` extra and
 * `medicationId == -1L`. Fires for CLOCK-mode slot-level alarms registered
 * by [MedicationClockRescheduler.registerAlarmForSlot]. Tomorrow's
 * occurrence is re-armed from the receiver before the notification is
 * shown, since AlarmManager exact alarms are one-shot.
 *
 * **Med-slot-clock path** — intent carries BOTH a `"medicationId"` (>=0)
 * AND a `"clockSlotId"` (>=0) extra. Fires for per-(medication, slot)
 * CLOCK alarms registered by
 * [MedicationClockRescheduler.registerAlarmForMedSlot] when a pair has
 * an idealTime override or the medication opts into CLOCK over a
 * non-CLOCK slot. The notification names the medication explicitly.
 *
 * Dispatch order is deliberate: med-slot-clock (both extras) >
 * medication > slot-clock > slot-interval > habit. The interval
 * rescheduler's per-medication-override path uses the medication branch
 * (it puts a real `medicationId` but no `clockSlotId`); the per-(med,
 * slot) clock path is distinguished by both extras being set.
 */
class MedicationReminderReceiver : BroadcastReceiver() {
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(SingletonComponent::class)
    interface MedReminderEntryPoint {
        fun habitReminderScheduler(): HabitReminderScheduler

        fun medicationReminderScheduler(): MedicationReminderScheduler

        fun medicationClockRescheduler(): MedicationClockRescheduler

        fun habitDao(): HabitDao

        fun medicationDao(): MedicationDao

        fun medicationSlotDao(): MedicationSlotDao

        fun medicationSlotOverrideDao(): MedicationSlotOverrideDao

        fun recentMoodSignal(): RecentMoodSignal

        fun diagnosticLogger(): DiagnosticLogger
    }

    override fun onReceive(context: Context, intent: Intent) {
        val medicationId = intent.getLongExtra("medicationId", -1L)
        val clockSlotId = intent.getLongExtra("clockSlotId", -1L)
        val intervalSlotId = intent.getLongExtra("intervalSlotId", -1L)
        val habitId = intent.getLongExtra("habitId", -1L)

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            MedReminderEntryPoint::class.java
        )

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                when (
                    val kind = classifyAlarm(
                        medicationId = medicationId,
                        clockSlotId = clockSlotId,
                        intervalSlotId = intervalSlotId,
                        habitId = habitId
                    )
                ) {
                    is AlarmKind.MedSlotClock ->
                        handleMedSlotClockAlarm(
                            context,
                            entryPoint,
                            kind.medicationId,
                            kind.slotId
                        )
                    is AlarmKind.Medication ->
                        handleMedicationAlarm(context, intent, entryPoint, kind.medicationId)
                    is AlarmKind.SlotClock ->
                        handleSlotClockAlarm(context, entryPoint, kind.slotId)
                    is AlarmKind.SlotInterval ->
                        handleSlotIntervalAlarm(context, entryPoint, kind.slotId)
                    is AlarmKind.Habit ->
                        handleHabitAlarm(context, intent, entryPoint, kind.habitId)
                    AlarmKind.Unknown -> Log.w(
                        "MedReminderReceiver",
                        "Alarm fired with no recognised id extra " +
                            "(medId=$medicationId clockSlot=$clockSlotId " +
                            "intervalSlot=$intervalSlotId habitId=$habitId)"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "MedReminderReceiver",
                    "Failed to process alarm " +
                        "medId=$medicationId clockSlot=$clockSlotId " +
                        "intervalSlot=$intervalSlotId habitId=$habitId",
                    e
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleHabitAlarm(
        context: Context,
        intent: Intent,
        entryPoint: MedReminderEntryPoint,
        habitId: Long
    ) {
        val name = intent.getStringExtra("habitName") ?: "Medication Reminder"
        val description = intent.getStringExtra("habitDescription")
        val intervalMillis = intent.getLongExtra("intervalMillis", 0L)
        val doseNumber = intent.getIntExtra("doseNumber", 0)
        val totalDoses = intent.getIntExtra("totalDoses", 1)
        val alarmKind = intent.getStringExtra(HabitReminderScheduler.EXTRA_ALARM_KIND)

        // Rest-day suppression (MH-First audit § G3). Habit alarms route
        // through this branch — including the daily-time path — and the
        // audit specifies non-medication notifications pause. The
        // re-register-tomorrow step below still runs so the chain
        // resumes naturally on the next logical day.
        val isRestDay = RestDayGate.shouldSuppress(context)

        val scheduler = entryPoint.habitReminderScheduler()
        val habit = entryPoint.habitDao().getHabitByIdOnce(habitId)

        // Daily-time alarms re-register tomorrow's occurrence as
        // soon as they fire, since AlarmManager exact alarms are
        // one-shot. If the habit was archived or had its reminder
        // cleared between scheduling and firing, the re-read
        // above surfaces the current state and we skip the
        // re-register.
        if (alarmKind == HabitReminderScheduler.ALARM_KIND_DAILY_TIME) {
            if (habit != null &&
                !habit.isArchived &&
                habit.reminderTime != null &&
                habit.reminderIntervalMillis == null
            ) {
                scheduler.scheduleDailyTime(habit)
            }
        }

        // Check if nag should be suppressed in favor of a delayed follow-up
        if (habit != null) {
            val followUpTime = scheduler.getFollowUpTimeIfSuppressed(habit)
            if (followUpTime != null) {
                scheduler.scheduleDelayedHabitFollowUp(habitId, name, followUpTime)
                return
            }
        }

        // Rest day (MH-first § G3) → drop the habit nag (the
        // re-register above already queued tomorrow). Medications never
        // route through this branch (they use AlarmKind.Medication /
        // SlotClock / etc.), so this never suppresses a real medication
        // reminder.
        if (isRestDay) {
            Log.d("MedReminderReceiver", "Rest day — suppressing habit nag habitId=$habitId")
            return
        }
        // Mental-Health-First § G7 — suppress habit-reminder cadence when
        // a low-mood log (≤2/5) lands within 48h. Medication-related
        // habits are exempt even though we already reach this branch
        // only for habit alarms: a Daily Essentials medication habit
        // routes here, and we never want to gate its nag on mood.
        if (habit != null && !isMedicationHabit(habit)) {
            if (entryPoint.recentMoodSignal().isLowMoodWithin()) {
                entryPoint.diagnosticLogger().info(
                    tag = "MoodGate",
                    message = "Habit reminder $habitId skipped — recent low mood within 48h"
                )
                return
            }
        }

        // No suppression — fire the nag notification as normal
        NotificationHelper.showMedicationReminder(
            context,
            habitId,
            name,
            description,
            intervalMillis,
            doseNumber,
            totalDoses
        )
    }

    private suspend fun handleMedicationAlarm(
        context: Context,
        intent: Intent,
        entryPoint: MedReminderEntryPoint,
        medicationId: Long
    ) {
        val slotKey = intent.getStringExtra("slotKey") ?: "anytime"
        val med = entryPoint.medicationDao().getByIdOnce(medicationId)
        if (med == null || med.isArchived) return

        // Per-medication alarms self-re-register on the legacy scheduler.
        // Alarms from MedicationIntervalRescheduler (slotKey
        // "interval-override") are re-armed by the rescheduler's own
        // dose-change Flow observer; routing them through the legacy
        // onAlarmFired risks interleaving the two interval-anchor sources.
        if (slotKey != "interval-override") {
            entryPoint.medicationReminderScheduler().onAlarmFired(medicationId, slotKey)
        }

        NotificationHelper.showMedicationReminder(
            context,
            medicationId,
            med.displayLabel ?: med.name,
            med.notes.ifBlank { null },
            0L,
            0,
            1
        )
    }

    private suspend fun handleSlotIntervalAlarm(
        context: Context,
        entryPoint: MedReminderEntryPoint,
        slotId: Long
    ) {
        val slot = entryPoint.medicationSlotDao().getByIdOnce(slotId)
        if (slot == null || !slot.isActive) return

        // No self-re-register: MedicationIntervalRescheduler observes
        // medicationDoseDao.observeMostRecentDoseAny() and re-anchors the
        // chain whenever a dose lands. The notification's "Log" tap is
        // what produces that next emission.
        NotificationHelper.showSlotIntervalReminder(
            context = context,
            slotId = slotId,
            slotName = slot.name
        )
    }

    private suspend fun handleSlotClockAlarm(
        context: Context,
        entryPoint: MedReminderEntryPoint,
        slotId: Long
    ) {
        val slot = entryPoint.medicationSlotDao().getByIdOnce(slotId)
        if (slot == null || !slot.isActive) return

        // Re-arm tomorrow's wall-clock alarm before showing the
        // notification. AlarmManager exact alarms are one-shot.
        entryPoint.medicationClockRescheduler().onAlarmFired(slotId)

        NotificationHelper.showSlotClockReminder(
            context = context,
            slotId = slotId,
            slotName = slot.name,
            idealTime = slot.idealTime
        )
    }

    private suspend fun handleMedSlotClockAlarm(
        context: Context,
        entryPoint: MedReminderEntryPoint,
        medicationId: Long,
        slotId: Long
    ) {
        val med = entryPoint.medicationDao().getByIdOnce(medicationId)
        if (med == null || med.isArchived) return
        val slot = entryPoint.medicationSlotDao().getByIdOnce(slotId)
        if (slot == null || !slot.isActive) return

        // Re-arm tomorrow's per-(med, slot) alarm before showing the
        // notification. AlarmManager exact alarms are one-shot.
        entryPoint.medicationClockRescheduler().onMedSlotAlarmFired(medicationId, slotId)

        // Read the override at fire time so a recent change is reflected
        // in the notification body even if the alarm itself was scheduled
        // against an older trigger.
        val override = entryPoint.medicationSlotOverrideDao()
            .getForPairOnce(medicationId, slotId)
        val effectiveTime = override?.overrideIdealTime ?: slot.idealTime

        NotificationHelper.showMedSlotClockReminder(
            context = context,
            medicationId = medicationId,
            slotId = slotId,
            medName = med.displayLabel ?: med.name,
            slotName = slot.name,
            idealTime = effectiveTime
        )
    }

    private fun isMedicationHabit(
        habit: com.averycorp.prismtask.data.local.entity.HabitEntity
    ): Boolean {
        if (habit.templateKey == BuiltInHabitVersionRegistry.KEY_MEDICATION) return true
        val cat = habit.category?.trim()?.lowercase()
        return cat == "medication"
    }

    companion object {
        @Suppress("MemberVisibilityCanBePrivate")
        internal sealed class AlarmKind {
            data class MedSlotClock(val medicationId: Long, val slotId: Long) : AlarmKind()

            data class Medication(val medicationId: Long) : AlarmKind()

            data class SlotClock(val slotId: Long) : AlarmKind()

            data class SlotInterval(val slotId: Long) : AlarmKind()

            data class Habit(val habitId: Long) : AlarmKind()

            object Unknown : AlarmKind()
        }

        /**
         * Pure dispatch helper. Routes by id-extra presence in priority
         * order so each alarm shape lands in exactly one branch:
         *  - both `medicationId` and `clockSlotId` set → per-(med, slot)
         *    CLOCK alarm (overrides + per-med-CLOCK-over-non-CLOCK-slot).
         *  - `medicationId` only → legacy per-med alarm or interval-mode
         *    per-med override.
         *  - `clockSlotId` only → slot-level CLOCK alarm.
         *  - `intervalSlotId` only → slot INTERVAL alarm.
         *  - `habitId` only → habit reminder.
         */
        internal fun classifyAlarm(
            medicationId: Long,
            clockSlotId: Long,
            intervalSlotId: Long,
            habitId: Long
        ): AlarmKind = when {
            medicationId >= 0 && clockSlotId >= 0 ->
                AlarmKind.MedSlotClock(medicationId, clockSlotId)
            medicationId >= 0 -> AlarmKind.Medication(medicationId)
            clockSlotId >= 0 -> AlarmKind.SlotClock(clockSlotId)
            intervalSlotId >= 0 -> AlarmKind.SlotInterval(intervalSlotId)
            habitId >= 0 -> AlarmKind.Habit(habitId)
            else -> AlarmKind.Unknown
        }
    }
}
