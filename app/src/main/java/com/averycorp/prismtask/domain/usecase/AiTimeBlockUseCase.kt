package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.TimeBlockExistingBlock
import com.averycorp.prismtask.data.remote.api.TimeBlockRequest
import com.averycorp.prismtask.data.remote.api.TimeBlockResponse
import com.averycorp.prismtask.data.remote.api.TimeBlockTaskSignal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Horizon options for AI time blocking.
 *
 * The "days" value is how many calendar days the horizon covers starting
 * from the anchor date. [TODAY] = 1 (legacy single-day), [TODAY_PLUS_ONE]
 * = 2, [WEEK] = 7 (rolling 7-day window).
 */
enum class TimeBlockHorizon(val days: Int) {
    TODAY(1),
    TODAY_PLUS_ONE(2),
    WEEK(7);

    companion object {
        fun fromDays(days: Int): TimeBlockHorizon = when (days) {
            TODAY.days -> TODAY
            TODAY_PLUS_ONE.days -> TODAY_PLUS_ONE
            else -> WEEK
        }
    }
}

/**
 * Gathers per-task signals (Eisenhower quadrant, estimated Pomodoro
 * sessions) and pre-existing scheduled blocks for the horizon window,
 * then calls `/api/v1/ai/time-block` and returns the Retrofit response.
 *
 * Kept as a standalone use case — not a repository method — because the
 * "gather signals + call remote endpoint" shape is one-shot per user tap,
 * not an ongoing observed stream.
 */
@Singleton
class AiTimeBlockUseCase @Inject constructor(private val taskDao: TaskDao, private val api: PrismTaskApi) {
    // Seam for tests that need a deterministic zone. Prod reads
    // ``ZoneId.systemDefault()`` on every call so a timezone change
    // during the app lifetime is picked up.
    internal var clock: AiTimeBlockClock = AiTimeBlockClock.SYSTEM

    /**
     * Build a time-block request from local data + call the backend.
     *
     * [anchorDate] is the first day of the horizon; [horizon] controls
     * how many days follow. The returned [TimeBlockResponse] always has
     * `proposed = true` — the caller is responsible for explicit approval
     * before committing to Room.
     */
    suspend operator fun invoke(
        anchorDate: LocalDate,
        horizon: TimeBlockHorizon,
        dayStart: String,
        dayEnd: String,
        blockSizeMinutes: Int,
        includeBreaks: Boolean,
        breakFrequencyMinutes: Int,
        breakDurationMinutes: Int,
        defaultPomodoroSessionMinutes: Int = DEFAULT_POMODORO_MINUTES
    ): TimeBlockResponse {
        val zone = clock.zone
        val startMillis = anchorDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMillis = anchorDate
            .plusDays(horizon.days.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        val candidateTasks = taskDao.getTasksInHorizonOnce(startMillis, endExclusiveMillis)
        val signals = candidateTasks.map { it.toSignal(defaultPomodoroSessionMinutes) }

        val existingScheduled = taskDao.getScheduledTasksInHorizonOnce(
            startMillis,
            endExclusiveMillis
        )
        val existingBlocks = existingScheduled.mapNotNull { it.toExistingBlock(zone) }

        val request = TimeBlockRequest(
            date = anchorDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            dayStart = dayStart,
            dayEnd = dayEnd,
            blockSizeMinutes = blockSizeMinutes,
            includeBreaks = includeBreaks,
            breakFrequencyMinutes = breakFrequencyMinutes,
            breakDurationMinutes = breakDurationMinutes,
            horizonDays = horizon.days,
            taskSignals = signals,
            existingBlocks = existingBlocks
        )
        return api.getTimeBlock(request)
    }

    private fun TaskEntity.toSignal(defaultSessionMinutes: Int): TimeBlockTaskSignal {
        val duration = estimatedDuration
        val sessions = duration?.let { m ->
            max(1, (m + defaultSessionMinutes - 1) / defaultSessionMinutes)
        }
        val pomodoroSource = if (sessions != null) "estimated_from_duration" else null
        return TimeBlockTaskSignal(
            taskId = id.toString(),
            eisenhowerQuadrant = eisenhowerQuadrant,
            estimatedPomodoroSessions = sessions,
            estimatedDurationMinutes = duration,
            pomodoroSource = pomodoroSource
        )
    }

    private fun TaskEntity.toExistingBlock(zone: ZoneId): TimeBlockExistingBlock? {
        val start = scheduledStartTime ?: return null
        val durationMin = estimatedDuration ?: DEFAULT_BLOCK_MINUTES
        val end = start + durationMin * 60 * 1000L
        val startLocal = java.time.Instant.ofEpochMilli(start).atZone(zone)
        val endLocal = java.time.Instant.ofEpochMilli(end).atZone(zone)
        val dateStr = startLocal.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        return TimeBlockExistingBlock(
            date = dateStr,
            start = startLocal.toLocalTime().format(HOUR_MIN_FORMATTER),
            end = endLocal.toLocalTime().format(HOUR_MIN_FORMATTER),
            title = title,
            source = "task",
            taskId = id.toString()
        )
    }

    companion object {
        private const val DEFAULT_POMODORO_MINUTES = 25
        private const val DEFAULT_BLOCK_MINUTES = 30
        private val HOUR_MIN_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * Seam so tests can pin the current ZoneId without stubbing every
 * `ZoneId.systemDefault()` call. Production uses [SYSTEM].
 */
interface AiTimeBlockClock {
    val zone: ZoneId

    companion object {
        val SYSTEM: AiTimeBlockClock = object : AiTimeBlockClock {
            override val zone: ZoneId get() = ZoneId.systemDefault()
        }

        fun fixed(zone: ZoneId): AiTimeBlockClock = object : AiTimeBlockClock {
            override val zone: ZoneId = zone
        }
    }
}
