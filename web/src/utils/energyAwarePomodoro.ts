/**
 * Energy-aware Pomodoro session planner. Mirrors Android's
 * `domain/usecase/EnergyAwarePomodoro.kt` (v1.4.0 V11) at capability level
 * — picks work/break lengths tuned to the user's most recent logged
 * energy band so low-energy days get shorter sessions and longer breaks.
 *
 *  - Energy 1–2 (low):    15-min sessions, 10-min breaks
 *  - Energy 3 (medium):   25-min sessions, 5-min breaks (classic)
 *  - Energy 4–5 (high):   35–45 min sessions, 3–4 min breaks
 *
 * When no energy data exists for the recent window the planner returns
 * the classic defaults unchanged. This keeps users who haven't opted
 * into mood/energy tracking on the existing behaviour.
 */
import type { MoodEnergyLog } from '@/api/firestore/moodEnergyLogs';

/**
 * Resolved Pomodoro session configuration (minutes) plus a rationale
 * string the UI can surface so the user understands why the suggested
 * length differs from the default.
 */
export interface PomodoroSessionConfig {
  workMinutes: number;
  breakMinutes: number;
  longBreakMinutes: number;
  rationale: string;
}

/** Classic Pomodoro defaults — applied when no energy data is found. */
export interface DefaultPomodoroConfig {
  workMinutes: number;
  breakMinutes: number;
  longBreakMinutes: number;
}

export const DEFAULT_POMODORO_CONFIG: DefaultPomodoroConfig = {
  workMinutes: 25,
  breakMinutes: 5,
  longBreakMinutes: 15,
};

/**
 * Per-energy-band timing table — mirrors Android's `EnergyPomodoroConfig`
 * defaults so cross-device suggestions stay consistent.
 */
export interface EnergyPomodoroConfig {
  veryLowWork: number;
  veryLowBreak: number;
  veryLowLong: number;
  lowWork: number;
  lowBreak: number;
  lowLong: number;
  mediumWork: number;
  mediumBreak: number;
  mediumLong: number;
  highWork: number;
  highBreak: number;
  highLong: number;
  veryHighWork: number;
  veryHighBreak: number;
  veryHighLong: number;
}

export const DEFAULT_ENERGY_POMODORO_CONFIG: EnergyPomodoroConfig = {
  veryLowWork: 15,
  veryLowBreak: 10,
  veryLowLong: 20,
  lowWork: 15,
  lowBreak: 10,
  lowLong: 20,
  mediumWork: 25,
  mediumBreak: 5,
  mediumLong: 15,
  highWork: 35,
  highBreak: 4,
  highLong: 12,
  veryHighWork: 45,
  veryHighBreak: 3,
  veryHighLong: 10,
};

function clampEnergy(energy: number): 1 | 2 | 3 | 4 | 5 {
  const n = Math.round(energy);
  if (n <= 1) return 1;
  if (n >= 5) return 5;
  return n as 1 | 2 | 3 | 4 | 5;
}

/**
 * Plan a Pomodoro config given the latest logged energy band. When
 * `latestEnergy` is null/undefined, returns the classic defaults.
 */
export function planPomodoroFromEnergy(
  latestEnergy: number | null | undefined,
  defaults: DefaultPomodoroConfig = DEFAULT_POMODORO_CONFIG,
  energyConfig: EnergyPomodoroConfig = DEFAULT_ENERGY_POMODORO_CONFIG,
): PomodoroSessionConfig {
  if (latestEnergy == null || !Number.isFinite(latestEnergy)) {
    return {
      workMinutes: defaults.workMinutes,
      breakMinutes: defaults.breakMinutes,
      longBreakMinutes: defaults.longBreakMinutes,
      rationale: 'Using your classic Pomodoro defaults',
    };
  }
  const band = clampEnergy(latestEnergy);
  switch (band) {
    case 1:
      return {
        workMinutes: energyConfig.veryLowWork,
        breakMinutes: energyConfig.veryLowBreak,
        longBreakMinutes: energyConfig.veryLowLong,
        rationale: 'Shorter sessions and longer breaks for a low-energy day',
      };
    case 2:
      return {
        workMinutes: energyConfig.lowWork,
        breakMinutes: energyConfig.lowBreak,
        longBreakMinutes: energyConfig.lowLong,
        rationale: 'Shorter sessions and longer breaks for a low-energy day',
      };
    case 3:
      return {
        workMinutes: energyConfig.mediumWork,
        breakMinutes: energyConfig.mediumBreak,
        longBreakMinutes: energyConfig.mediumLong,
        rationale: "Classic Pomodoro — you're in the groove",
      };
    case 4:
      return {
        workMinutes: energyConfig.highWork,
        breakMinutes: energyConfig.highBreak,
        longBreakMinutes: energyConfig.highLong,
        rationale: 'Longer deep-work blocks for a high-energy day',
      };
    case 5:
    default:
      return {
        workMinutes: energyConfig.veryHighWork,
        breakMinutes: energyConfig.veryHighBreak,
        longBreakMinutes: energyConfig.veryHighLong,
        rationale: 'Peak-energy sprint sessions',
      };
  }
}

/**
 * Convenience wrapper that pulls the most recent energy reading from a
 * list of mood/energy logs and forwards to {@link planPomodoroFromEnergy}.
 * Mirrors Android's `EnergyAwarePomodoro.planFromLogs`.
 */
export function planPomodoroFromLogs(
  logs: MoodEnergyLog[] | null | undefined,
  defaults: DefaultPomodoroConfig = DEFAULT_POMODORO_CONFIG,
  energyConfig: EnergyPomodoroConfig = DEFAULT_ENERGY_POMODORO_CONFIG,
): PomodoroSessionConfig {
  if (!logs || logs.length === 0) {
    return planPomodoroFromEnergy(null, defaults, energyConfig);
  }
  let latest: MoodEnergyLog | null = null;
  for (const row of logs) {
    if (!latest || row.created_at > latest.created_at) latest = row;
  }
  return planPomodoroFromEnergy(latest?.energy ?? null, defaults, energyConfig);
}
