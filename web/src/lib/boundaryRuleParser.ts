/**
 * Natural-language parser for boundary-rule phrases, ported from
 * Android's `domain/usecase/BoundaryRuleParser.kt`. The grammar mirrors
 * the Android reference so a phrase that parses on one platform parses
 * on the other.
 *
 * Supported phrases:
 *
 *  - Work-hours blocks:
 *      "No work after 18:00", "No tasks before 09:00 on weekdays",
 *      "Block work after 7pm".
 *      Parsed as a `work-hours` rule with a start hour + end hour
 *      forming the allowed window (everything outside is the breach).
 *
 *  - Category limits:
 *      "Max 4 hours/day on Work", "At least 1 hour/day on Self-Care".
 *      Parsed as a `category-limit` rule capturing the category, the
 *      hours/day cap, and whether it's a ceiling (`max`) or floor
 *      (`min`).
 *
 *  - Escalation chains:
 *      "Escalate Work-Hours rule to Focus then Quiet after 10 minutes".
 *      Parsed as an `escalation` rule capturing the chain of notification
 *      profiles + a stage delay.
 *
 *  - Optional day clause anywhere in the phrase:
 *      "on weekdays", "on weekends", "every day", "on Mon", "on
 *      Tuesday", etc. — collected into `activeDays`.
 *
 * Pure function. Returns either a `BoundaryRule` or an `{ error }`
 * object describing why the phrase didn't parse so the caller can
 * surface a hint inline. The Android reference returned `null` on
 * failure; the web returns an error string so the editor can show a
 * coachmark instead of silently doing nothing.
 */

export type ParsedRuleType = 'work-hours' | 'category-limit' | 'escalation';

export type ParsedLifeCategory =
  | 'WORK'
  | 'PERSONAL'
  | 'SELF_CARE'
  | 'HEALTH';

/** ISO-style 1-7 = Mon-Sun, matching Java DayOfWeek ordinals. */
export type DayOrdinal = 1 | 2 | 3 | 4 | 5 | 6 | 7;

export const WEEKDAYS: DayOrdinal[] = [1, 2, 3, 4, 5];
export const WEEKEND: DayOrdinal[] = [6, 7];
export const ALL_DAYS: DayOrdinal[] = [1, 2, 3, 4, 5, 6, 7];

export interface WorkHoursRule {
  ruleType: 'work-hours';
  /** Local 24-hour clock window the user *wants to allow* work in.
   *  Hours outside the window are breaches. */
  startHour: number;
  startMinute: number;
  endHour: number;
  endMinute: number;
  activeDays: DayOrdinal[];
}

export interface CategoryLimitRule {
  ruleType: 'category-limit';
  category: ParsedLifeCategory;
  /** Cap kind: `max` means "at most"; `min` means "at least". */
  bound: 'max' | 'min';
  /** Hours per day budgeted for this category. */
  hoursPerDay: number;
  activeDays: DayOrdinal[];
}

export interface EscalationRule {
  ruleType: 'escalation';
  /** Optional pointer to the rule that triggers this escalation, by
   *  its summary phrase (e.g. "Work-Hours"). The editor maps this
   *  to a stored rule id when persisting. */
  trigger: string;
  /** Ordered notification-profile names. Stage `n` fires after
   *  `delayMinutes * n` minutes since the trigger. */
  profiles: string[];
  /** Minutes between escalation stages. */
  delayMinutes: number;
  activeDays: DayOrdinal[];
}

export type BoundaryRule = WorkHoursRule | CategoryLimitRule | EscalationRule;

export interface ParseError {
  error: string;
}

export type ParseResult = BoundaryRule | ParseError;

const CATEGORY_MAP: Record<string, ParsedLifeCategory> = {
  work: 'WORK',
  personal: 'PERSONAL',
  'self-care': 'SELF_CARE',
  selfcare: 'SELF_CARE',
  'self care': 'SELF_CARE',
  health: 'HEALTH',
};

const DAY_CLAUSE_MAP: Record<string, DayOrdinal[]> = {
  weekdays: WEEKDAYS,
  weekday: WEEKDAYS,
  weekends: WEEKEND,
  weekend: WEEKEND,
  'every day': ALL_DAYS,
  daily: ALL_DAYS,
  monday: [1],
  mon: [1],
  tuesday: [2],
  tue: [2],
  tues: [2],
  wednesday: [3],
  wed: [3],
  thursday: [4],
  thu: [4],
  thur: [4],
  thurs: [4],
  friday: [5],
  fri: [5],
  saturday: [6],
  sat: [6],
  sunday: [7],
  sun: [7],
};

/** Pick out an optional `on <days>` clause. Mirrors Android's
 *  `dayClauseMap` lookup but also accepts the bare day name without
 *  the leading "on". */
function extractDays(text: string): DayOrdinal[] {
  // Sort keys by descending length so "weekdays" beats "weekday".
  const keys = Object.keys(DAY_CLAUSE_MAP).sort(
    (a, b) => b.length - a.length,
  );
  for (const key of keys) {
    const re = new RegExp(`\\b${key.replace(/ /g, '\\s+')}\\b`, 'i');
    if (re.test(text)) return DAY_CLAUSE_MAP[key];
  }
  return ALL_DAYS;
}

interface ParsedClock {
  hour: number;
  minute: number;
}

/** Parse "9", "9am", "9:30", "9:30pm", "18:00", "noon", "midnight". */
function parseClock(raw: string): ParsedClock | null {
  const text = raw.trim().toLowerCase();
  if (!text) return null;
  if (text === 'noon') return { hour: 12, minute: 0 };
  if (text === 'midnight') return { hour: 0, minute: 0 };
  const m = text.match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/);
  if (!m) return null;
  const hourRaw = Number(m[1]);
  const minute = m[2] ? Number(m[2]) : 0;
  const meridiem = m[3];
  if (Number.isNaN(hourRaw) || Number.isNaN(minute)) return null;
  if (minute < 0 || minute > 59) return null;
  let hour: number;
  if (meridiem === 'pm') {
    hour = hourRaw === 12 ? 12 : hourRaw + 12;
  } else if (meridiem === 'am') {
    hour = hourRaw === 12 ? 0 : hourRaw;
  } else {
    hour = hourRaw;
  }
  if (hour < 0 || hour > 23) return null;
  return { hour, minute };
}

/** Pull the first category keyword we find anywhere in `text`. */
function extractCategory(text: string): ParsedLifeCategory | null {
  const keys = Object.keys(CATEGORY_MAP).sort(
    (a, b) => b.length - a.length,
  );
  for (const key of keys) {
    const re = new RegExp(`\\b${key.replace(/ /g, '\\s+')}\\b`, 'i');
    if (re.test(text)) return CATEGORY_MAP[key];
  }
  return null;
}

/** Public entry point. */
export function parse(input: string): ParseResult {
  const trimmed = input.trim();
  if (!trimmed) return { error: 'Phrase is empty.' };
  const text = trimmed.toLowerCase();

  // 1. Escalation rule. "escalate ... to A then B [then C] after N minutes"
  if (/^escalate\b/.test(text)) {
    return parseEscalation(text);
  }

  // 2. Category-limit rule. "max|at most N hours/day on <category>"
  //                        "at least N hours/day on <category>"
  const limitMatch = text.match(
    /^(max|at\s+most|min|at\s+least)\s+(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\s*(?:\/|per)?\s*day\s+(?:on|of)\s+(.+?)$/,
  );
  if (limitMatch) {
    return parseCategoryLimit(limitMatch, text);
  }

  // 3. Work-hours rule. Must start with a blocking cue.
  if (/^(no|don'?t\s+schedule|block|stop)\b/.test(text)) {
    return parseWorkHours(text);
  }

  return {
    error:
      'Try "No work after 18:00", "Max 4 hours/day on Work", or "Escalate Work-Hours to Focus then Quiet after 10 minutes".',
  };
}

function parseWorkHours(text: string): ParseResult {
  // "no <subject> (after|before) <time> [on <days>]"
  // The time portion accepts HH, HH:MM, HHam/pm, or the keywords
  // "noon" / "midnight".
  const m = text.match(
    /^(?:no|don'?t\s+schedule|block|stop)\s+(\w[\w-]*(?:\s+\w[\w-]*)?)\s+(after|before)\s+(noon|midnight|\d{1,2}(?::\d{2})?\s*(?:am|pm)?)(?:\s+(?:on|every|each)\s+.+)?$/,
  );
  if (!m) {
    return {
      error:
        'Expected "No <subject> after HH:MM" — try "No work after 18:00".',
    };
  }
  const clock = parseClock(m[3]);
  if (!clock) {
    return { error: `Could not parse time "${m[3].trim()}".` };
  }
  const direction = m[2];
  // "after HH:MM" means work is allowed *until* HH:MM, so the allowed
  // window ends at HH:MM. The day starts at 00:00 by default.
  // "before HH:MM" means work is allowed *from* HH:MM onward, so the
  // allowed window starts at HH:MM and ends at 23:59.
  const startHour = direction === 'before' ? clock.hour : 0;
  const startMinute = direction === 'before' ? clock.minute : 0;
  const endHour = direction === 'after' ? clock.hour : 23;
  const endMinute = direction === 'after' ? clock.minute : 59;

  return {
    ruleType: 'work-hours',
    startHour,
    startMinute,
    endHour,
    endMinute,
    activeDays: extractDays(text),
  };
}

function parseCategoryLimit(
  m: RegExpMatchArray,
  text: string,
): ParseResult {
  const cue = m[1].replace(/\s+/g, ' ').trim();
  const bound: 'max' | 'min' =
    cue === 'min' || cue === 'at least' ? 'min' : 'max';
  const hoursPerDay = Number(m[2]);
  if (Number.isNaN(hoursPerDay) || hoursPerDay <= 0 || hoursPerDay > 24) {
    return { error: `Hours/day must be 0–24 (got "${m[2]}").` };
  }
  const tail = m[3];
  const category = extractCategory(tail);
  if (!category) {
    return {
      error: `Could not find a category in "${tail.trim()}". Try Work, Personal, Self-Care, or Health.`,
    };
  }
  return {
    ruleType: 'category-limit',
    category,
    bound,
    hoursPerDay,
    activeDays: extractDays(text),
  };
}

function parseEscalation(text: string): ParseResult {
  // "escalate [<trigger> [rule]] to A [then B [then C]] after N minutes"
  const m = text.match(
    /^escalate\s+(?:(.+?)\s+(?:rule\s+)?)?to\s+(.+?)\s+after\s+(\d+)\s*(?:minutes?|mins?|m)\b/,
  );
  if (!m) {
    return {
      error:
        'Expected "Escalate <name> to A then B after N minutes". Try "Escalate Work-Hours to Focus then Quiet after 10 minutes".',
    };
  }
  const trigger = (m[1] ?? '').trim();
  const profilesRaw = m[2];
  const delayMinutes = Number(m[3]);
  if (Number.isNaN(delayMinutes) || delayMinutes <= 0) {
    return { error: `Stage delay must be > 0 minutes.` };
  }
  const profiles = profilesRaw
    .split(/\s+then\s+|\s*,\s*/i)
    .map((s) => titleCase(s.trim()))
    .filter(Boolean);
  if (profiles.length === 0) {
    return { error: 'Escalation chain needs at least one profile.' };
  }
  return {
    ruleType: 'escalation',
    trigger: trigger ? titleCase(trigger) : '',
    profiles,
    delayMinutes,
    activeDays: extractDays(text),
  };
}

function titleCase(s: string): string {
  return s
    .split(/\s+/)
    .filter(Boolean)
    .map((w) =>
      w
        .split('-')
        .map((p) => (p ? p[0].toUpperCase() + p.slice(1) : p))
        .join('-'),
    )
    .join(' ');
}

/** Compact "HH:MM" formatter for previews. */
export function formatHourMinute(hour: number, minute: number): string {
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/** Friendly preview string mirroring Android's rule-name template
 *  ("No work after 18:00", "Max 4h/day Work", etc.). */
export function formatRulePreview(rule: BoundaryRule): string {
  switch (rule.ruleType) {
    case 'work-hours': {
      const start = formatHourMinute(rule.startHour, rule.startMinute);
      const end = formatHourMinute(rule.endHour, rule.endMinute);
      return `Work window ${start}–${end}`;
    }
    case 'category-limit': {
      const verb = rule.bound === 'max' ? 'Max' : 'Min';
      const cat = rule.category
        .toLowerCase()
        .replace('_', '-')
        .replace(/\b\w/g, (c) => c.toUpperCase());
      return `${verb} ${rule.hoursPerDay}h/day on ${cat}`;
    }
    case 'escalation': {
      const chain = rule.profiles.join(' → ');
      const trigger = rule.trigger ? `${rule.trigger} rule` : 'rule';
      return `Escalate ${trigger}: ${chain} (every ${rule.delayMinutes}m)`;
    }
  }
}
