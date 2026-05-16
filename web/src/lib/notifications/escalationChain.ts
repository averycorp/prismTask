/**
 * Escalation-chain + quiet-hours encoders for the Notifications Hub.
 *
 * Mirrors Android's `EscalationChain` / `QuietHoursWindow` shape in
 * `app/src/main/java/com/averycorp/prismtask/domain/model/notifications/`.
 * The Android side persists these as JSON strings on
 * `NotificationProfileEntity` (`escalation_chain_json` /
 * `quiet_hours_json`); we use the same wire format so a chain authored
 * on web round-trips through Firestore + Android's Gson decoder without
 * a translation layer.
 *
 * The notification-profile Firestore listener (`customSoundsStore` /
 * `notificationProfilesStore`, both landed in main via the parity-19
 * sweep) reads `escalation_chain_json` and `quiet_hours_json` as opaque
 * strings; this module is the canonical decoder/encoder for those
 * fields on web. Anything that needs to read or mutate an escalation
 * chain should go through here, not roll its own JSON shape — that
 * keeps both sides converged when Android adds a new step action.
 */

/** Tier vocabulary — must mirror Android's `UrgencyTier.key`. */
export type UrgencyTierKey = 'low' | 'medium' | 'high' | 'critical';

export const URGENCY_TIERS: ReadonlyArray<{
  key: UrgencyTierKey;
  label: string;
}> = [
  { key: 'low', label: 'Low' },
  { key: 'medium', label: 'Medium' },
  { key: 'high', label: 'High' },
  { key: 'critical', label: 'Critical' },
];

/** Action vocabulary — must mirror Android's `EscalationStepAction.key`. */
export type EscalationStepAction =
  | 'gentle'
  | 'standard'
  | 'loud'
  | 'full_screen';

export const ESCALATION_STEP_ACTIONS: ReadonlyArray<{
  key: EscalationStepAction;
  label: string;
}> = [
  { key: 'gentle', label: 'Gentle Ping' },
  { key: 'standard', label: 'Standard Alert' },
  { key: 'loud', label: 'Louder + Vibrate' },
  { key: 'full_screen', label: 'Full-Screen Takeover' },
];

export interface EscalationStep {
  action: EscalationStepAction;
  /** Delay after the previous step (or initial fire time for step 0), in ms. */
  delayMs: number;
  /** Urgency tiers that can trigger this step. Empty = all tiers. */
  triggerTiers: UrgencyTierKey[];
}

export interface EscalationChain {
  enabled: boolean;
  steps: EscalationStep[];
  /** Any tap/snooze/dismiss cancels remaining steps. */
  stopOnInteraction: boolean;
  /** Max total attempts. 0 = unbounded. */
  maxAttempts: number;
}

export interface QuietHoursWindow {
  enabled: boolean;
  /** 0-23. */
  startHour: number;
  /** 0-59. */
  startMinute: number;
  /** 0-23. */
  endHour: number;
  /** 0-59. */
  endMinute: number;
  /** ISO day-of-week numbers (1 = Monday … 7 = Sunday) per Android `DayOfWeek`. */
  days: number[];
  /** Tiers that can break through the quiet window. */
  priorityOverrideTiers: UrgencyTierKey[];
}

export const DEFAULT_ESCALATION_CHAIN: EscalationChain = {
  enabled: false,
  steps: [],
  stopOnInteraction: true,
  maxAttempts: 5,
};

/** Matches Android's `EscalationChain.DEFAULT_AGGRESSIVE`. */
export const DEFAULT_AGGRESSIVE_ESCALATION_CHAIN: EscalationChain = {
  enabled: true,
  steps: [
    { action: 'gentle', delayMs: 0, triggerTiers: [] },
    {
      action: 'standard',
      delayMs: 2 * 60 * 1000,
      triggerTiers: ['medium', 'high', 'critical'],
    },
    {
      action: 'loud',
      delayMs: 5 * 60 * 1000,
      triggerTiers: ['high', 'critical'],
    },
    {
      action: 'full_screen',
      delayMs: 10 * 60 * 1000,
      triggerTiers: ['critical'],
    },
  ],
  stopOnInteraction: true,
  maxAttempts: 4,
};

export const DEFAULT_QUIET_HOURS: QuietHoursWindow = {
  enabled: false,
  startHour: 22,
  startMinute: 0,
  endHour: 7,
  endMinute: 0,
  days: [1, 2, 3, 4, 5, 6, 7],
  priorityOverrideTiers: ['critical'],
};

// ── Encoders / Decoders ────────────────────────────────────────────────

function isStepAction(v: unknown): v is EscalationStepAction {
  return (
    v === 'gentle' || v === 'standard' || v === 'loud' || v === 'full_screen'
  );
}

function isTier(v: unknown): v is UrgencyTierKey {
  return v === 'low' || v === 'medium' || v === 'high' || v === 'critical';
}

function coerceTiers(raw: unknown): UrgencyTierKey[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter(isTier);
}

function coerceSteps(raw: unknown): EscalationStep[] {
  if (!Array.isArray(raw)) return [];
  const out: EscalationStep[] = [];
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue;
    const obj = item as Record<string, unknown>;
    const action = obj.action;
    if (!isStepAction(action)) continue;
    const delayMs =
      typeof obj.delayMs === 'number' && obj.delayMs >= 0 ? obj.delayMs : 0;
    out.push({
      action,
      delayMs,
      triggerTiers: coerceTiers(obj.triggerTiers),
    });
  }
  return out;
}

/**
 * Decode an escalation-chain JSON blob. Returns the canonical
 * [DEFAULT_ESCALATION_CHAIN] for null / empty / invalid input — never
 * throws. The Android encoder writes the exact field names below; we
 * mirror them on read AND write to keep round-trips stable.
 */
export function decodeEscalationChain(
  json: string | null | undefined,
): EscalationChain {
  if (!json) return DEFAULT_ESCALATION_CHAIN;
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    return DEFAULT_ESCALATION_CHAIN;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
    return DEFAULT_ESCALATION_CHAIN;
  const obj = parsed as Record<string, unknown>;
  return {
    enabled: obj.enabled === true,
    steps: coerceSteps(obj.steps),
    stopOnInteraction: obj.stopOnInteraction !== false,
    maxAttempts:
      typeof obj.maxAttempts === 'number' && obj.maxAttempts >= 0
        ? obj.maxAttempts
        : 5,
  };
}

/** Encode the chain into the JSON wire format Android decodes verbatim. */
export function encodeEscalationChain(chain: EscalationChain): string {
  return JSON.stringify({
    enabled: chain.enabled,
    steps: chain.steps.map((s) => ({
      action: s.action,
      delayMs: s.delayMs,
      triggerTiers: s.triggerTiers,
    })),
    stopOnInteraction: chain.stopOnInteraction,
    maxAttempts: chain.maxAttempts,
  });
}

function coerceDays(raw: unknown): number[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<number>();
  for (const item of raw) {
    if (typeof item === 'number' && item >= 1 && item <= 7) {
      seen.add(Math.trunc(item));
    }
  }
  return Array.from(seen).sort((a, b) => a - b);
}

/**
 * Decode a quiet-hours JSON blob. Mirrors Android's
 * `NotificationProfileResolver.decodeQuietHours` shape — start/end as
 * `{hour, minute}`, days as ISO numbers.
 */
export function decodeQuietHours(
  json: string | null | undefined,
): QuietHoursWindow {
  if (!json) return DEFAULT_QUIET_HOURS;
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    return DEFAULT_QUIET_HOURS;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
    return DEFAULT_QUIET_HOURS;
  const obj = parsed as Record<string, unknown>;
  const start = (obj.start ?? {}) as Record<string, unknown>;
  const end = (obj.end ?? {}) as Record<string, unknown>;
  return {
    enabled: obj.enabled === true,
    startHour: clampHour(start.hour, DEFAULT_QUIET_HOURS.startHour),
    startMinute: clampMinute(start.minute, DEFAULT_QUIET_HOURS.startMinute),
    endHour: clampHour(end.hour, DEFAULT_QUIET_HOURS.endHour),
    endMinute: clampMinute(end.minute, DEFAULT_QUIET_HOURS.endMinute),
    days:
      coerceDays(obj.days).length > 0
        ? coerceDays(obj.days)
        : DEFAULT_QUIET_HOURS.days,
    priorityOverrideTiers: coerceTiers(obj.priorityOverrideTiers),
  };
}

export function encodeQuietHours(window: QuietHoursWindow): string {
  return JSON.stringify({
    enabled: window.enabled,
    start: { hour: window.startHour, minute: window.startMinute },
    end: { hour: window.endHour, minute: window.endMinute },
    days: window.days,
    priorityOverrideTiers: window.priorityOverrideTiers,
  });
}

function clampHour(raw: unknown, fallback: number): number {
  if (typeof raw !== 'number') return fallback;
  const n = Math.trunc(raw);
  if (n < 0 || n > 23) return fallback;
  return n;
}

function clampMinute(raw: unknown, fallback: number): number {
  if (typeof raw !== 'number') return fallback;
  const n = Math.trunc(raw);
  if (n < 0 || n > 59) return fallback;
  return n;
}

// ── Misc shared lookups ───────────────────────────────────────────────

export const VIBRATION_PRESETS: ReadonlyArray<{ key: string; label: string }> =
  [
    { key: 'none', label: 'No Vibration' },
    { key: 'single', label: 'Single Pulse' },
    { key: 'double', label: 'Double Pulse' },
    { key: 'triple', label: 'Triple' },
    { key: 'long', label: 'Long Buzz' },
    { key: 'sos', label: 'SOS' },
    { key: 'heartbeat', label: 'Heartbeat' },
    { key: 'wave', label: 'Wave' },
    { key: 'custom', label: 'Custom Pattern' },
  ];

export const VIBRATION_INTENSITIES: ReadonlyArray<{
  key: string;
  label: string;
}> = [
  { key: 'light', label: 'Light' },
  { key: 'medium', label: 'Medium' },
  { key: 'strong', label: 'Strong' },
];

/** Built-in sound IDs mirror Android's `BuiltInSound.ALL`. */
export interface BuiltInSoundOption {
  id: string;
  label: string;
  category: string;
}

export const BUILT_IN_SOUNDS: ReadonlyArray<BuiltInSoundOption> = [
  { id: '__system_default__', label: 'System Default', category: 'chimes' },
  { id: '__silent__', label: 'Silent', category: 'minimal' },
  { id: 'chime_gentle', label: 'Gentle Chime', category: 'chimes' },
  { id: 'chime_morning', label: 'Morning Chime', category: 'chimes' },
  { id: 'chime_wind', label: 'Wind Chime', category: 'chimes' },
  { id: 'bell_classic', label: 'Classic Bell', category: 'bells' },
  { id: 'bell_tibetan', label: 'Tibetan Bell', category: 'bells' },
  { id: 'bell_desk', label: 'Desk Bell', category: 'bells' },
  { id: 'nature_birds', label: 'Morning Birds', category: 'nature' },
  { id: 'nature_water', label: 'Water Drop', category: 'nature' },
  { id: 'nature_wind', label: 'Forest Wind', category: 'nature' },
  { id: 'scifi_beep', label: 'Console Beep', category: 'sci_fi' },
  { id: 'scifi_pulse', label: 'Energy Pulse', category: 'sci_fi' },
  { id: 'scifi_alert', label: 'Bridge Alert', category: 'sci_fi' },
  { id: 'minimal_pop', label: 'Pop', category: 'minimal' },
  { id: 'minimal_tick', label: 'Tick', category: 'minimal' },
  { id: 'minimal_whisper', label: 'Whisper', category: 'minimal' },
  { id: 'perc_wood', label: 'Wood Block', category: 'percussive' },
  { id: 'perc_clap', label: 'Clap', category: 'percussive' },
  { id: 'perc_tap', label: 'Soft Tap', category: 'percussive' },
  { id: 'voice_chimebell', label: 'Voice: Ding', category: 'voice' },
  { id: 'voice_heyyou', label: 'Voice: Hey There', category: 'voice' },
  { id: 'voice_reminder', label: 'Voice: Reminder', category: 'voice' },
];

export const SOUND_CATEGORIES: ReadonlyArray<{ key: string; label: string }> =
  [
    { key: 'chimes', label: 'Chimes' },
    { key: 'bells', label: 'Bells' },
    { key: 'nature', label: 'Nature' },
    { key: 'sci_fi', label: 'Sci-Fi' },
    { key: 'minimal', label: 'Minimal' },
    { key: 'percussive', label: 'Percussive' },
    { key: 'voice', label: 'Voice' },
    { key: 'custom', label: 'My Uploads' },
  ];

export function builtInSoundLabel(id: string): string | null {
  const entry = BUILT_IN_SOUNDS.find((s) => s.id === id);
  return entry ? entry.label : null;
}

/** Convenience: shorthand for the days-of-week labels. ISO order. */
export const DAY_OF_WEEK_LABELS: ReadonlyArray<{
  day: number;
  short: string;
  long: string;
}> = [
  { day: 1, short: 'Mon', long: 'Monday' },
  { day: 2, short: 'Tue', long: 'Tuesday' },
  { day: 3, short: 'Wed', long: 'Wednesday' },
  { day: 4, short: 'Thu', long: 'Thursday' },
  { day: 5, short: 'Fri', long: 'Friday' },
  { day: 6, short: 'Sat', long: 'Saturday' },
  { day: 7, short: 'Sun', long: 'Sunday' },
];
