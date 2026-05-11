package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.preferences.EnergyPomodoroConfig

/**
 * A single Pomodoro session configuration — work length, short break, long
 * break — all expressed in minutes so the UI can render them without any
 * extra conversion.
 */
data class PomodoroSessionConfig(val workMinutes: Int, val breakMinutes: Int, val longBreakMinutes: Int, val rationale: String)

/**
 * Energy-aware Pomodoro session planner (v1.4.0 V11).
 *
 * Consumes today's energy level (1..5) — typically pulled from the latest
 * [MoodEnergyLogEntity] via [MoodCorrelationEngine] or an upcoming widget
 * check-in — and picks a work/break config tuned to the user's bandwidth:
 *
 *  - Energy 1–2 (low):    15-min sessions, 10-min breaks
 *  - Energy 3 (medium):   25-min sessions, 5-min breaks (classic Pomodoro)
 *  - Energy 4–5 (high):   35–45 min sessions, 3-min breaks
 *
 * When no energy data exists for today the planner falls back to the
 * user's configured default (passed via [DefaultPomodoroConfig]) so the
 * behavior is identical to the pre-V11 classic Pomodoro. This lets the
 * feature roll out safely — users who haven't opted into mood/energy
 * tracking see no change.
 */
class EnergyAwarePomodoro(private val energyConfig: EnergyPomodoroConfig = EnergyPomodoroConfig()) {
    fun plan(
        latestEnergy: Int?,
        defaults: DefaultPomodoroConfig = DefaultPomodoroConfig()
    ): PomodoroSessionConfig {
        if (latestEnergy == null) {
            return PomodoroSessionConfig(
                workMinutes = defaults.workMinutes,
                breakMinutes = defaults.breakMinutes,
                longBreakMinutes = defaults.longBreakMinutes,
                rationale = "Using your classic Pomodoro defaults"
            )
        }
        return when (latestEnergy.coerceIn(1, 5)) {
            1 -> PomodoroSessionConfig(
                workMinutes = energyConfig.veryLowWork,
                breakMinutes = energyConfig.veryLowBreak,
                longBreakMinutes = energyConfig.veryLowLong,
                rationale = "Shorter sessions and longer breaks for a low-energy day"
            )
            2 -> PomodoroSessionConfig(
                workMinutes = energyConfig.lowWork,
                breakMinutes = energyConfig.lowBreak,
                longBreakMinutes = energyConfig.lowLong,
                rationale = "Shorter sessions and longer breaks for a low-energy day"
            )
            3 -> PomodoroSessionConfig(
                workMinutes = energyConfig.mediumWork,
                breakMinutes = energyConfig.mediumBreak,
                longBreakMinutes = energyConfig.mediumLong,
                rationale = "Classic Pomodoro — you're in the groove"
            )
            4 -> PomodoroSessionConfig(
                workMinutes = energyConfig.highWork,
                breakMinutes = energyConfig.highBreak,
                longBreakMinutes = energyConfig.highLong,
                rationale = "Longer deep-work blocks for a high-energy day"
            )
            else -> PomodoroSessionConfig(
                workMinutes = energyConfig.veryHighWork,
                breakMinutes = energyConfig.veryHighBreak,
                longBreakMinutes = energyConfig.veryHighLong,
                rationale = "Peak-energy sprint sessions"
            )
        }
    }

    /**
     * Convenience wrapper that pulls the latest energy reading (if any) from
     * a list of [MoodEnergyLogEntity] rows and forwards to [plan].
     */
    fun planFromLogs(
        logs: List<MoodEnergyLogEntity>,
        defaults: DefaultPomodoroConfig = DefaultPomodoroConfig()
    ): PomodoroSessionConfig {
        val latest = logs.maxByOrNull { it.createdAt }
        return plan(latest?.energy, defaults)
    }
}

/**
 * Classic Pomodoro defaults. When the user hasn't logged energy today,
 * [EnergyAwarePomodoro] returns these unchanged.
 */
data class DefaultPomodoroConfig(val workMinutes: Int = 25, val breakMinutes: Int = 5, val longBreakMinutes: Int = 15)
