import type {
  BoundaryLifeCategory,
  BoundaryRule,
} from '@/api/firestore/boundaryRules';

/** ISO-1 day ordinals (Mon=1 … Sun=7) → 3-letter labels. Matches the
 *  encoding used in `boundaryRuleParser.ts` so a parsed rule round-
 *  trips through the editor without an ordinal shift. */
export const DAY_SHORT: Record<number, string> = {
  1: 'Mon',
  2: 'Tue',
  3: 'Wed',
  4: 'Thu',
  5: 'Fri',
  6: 'Sat',
  7: 'Sun',
};

/** "WORK" → "Work", "SELF_CARE" → "Self-Care". */
export function prettyCategory(category: BoundaryLifeCategory): string {
  return category
    .toLowerCase()
    .replace('_', '-')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

/** "HH:MM" formatter clamped to valid 24h ranges. */
export function formatClock(hour: number, minute: number): string {
  const h = Math.max(0, Math.min(23, Math.round(hour)));
  const m = Math.max(0, Math.min(59, Math.round(minute)));
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** Friendly active-day summary: "Every day" / "Weekdays" / "Weekend"
 *  / "Mon, Wed, Fri". */
export function formatDays(days: number[] | null | undefined): string {
  if (!days || days.length === 0 || days.length === 7) return 'Every day';
  const sorted = [...days].sort();
  if (sorted.length === 5 && sorted.every((d, i) => d === i + 1)) {
    return 'Weekdays';
  }
  if (sorted.length === 2 && sorted[0] === 6 && sorted[1] === 7) {
    return 'Weekend';
  }
  return sorted.map((d) => DAY_SHORT[d]).join(', ');
}

/** One-line summary for the list row, mirroring Android's
 *  `BoundaryRule.name` template. Keeps formatting deterministic so
 *  list + editor both render identical strings. */
export function summarizeRule(rule: BoundaryRule): string {
  switch (rule.type) {
    case 'work_hours_window': {
      const start = formatClock(rule.value, rule.start_minute ?? 0);
      const end = formatClock(
        rule.secondary_value ?? 0,
        rule.end_minute ?? 0,
      );
      return `${start}–${end} • ${formatDays(rule.active_days)}`;
    }
    case 'category_limit': {
      const verb = rule.bound === 'min' ? 'At least' : 'Max';
      const cat = rule.category ? prettyCategory(rule.category) : 'Uncategorized';
      return `${verb} ${rule.value}h/day on ${cat} • ${formatDays(rule.active_days)}`;
    }
    case 'escalation': {
      const chain = (rule.escalation_chain ?? []).join(' → ') || 'no profiles';
      const trigger = rule.escalation_trigger || 'rule';
      return `Escalate ${trigger}: ${chain} (every ${rule.value}m)`;
    }
    case 'daily_task_cap':
      return `Max ${rule.value} tasks/day`;
    case 'weekly_hour_budget':
      return `${rule.value}h/week budget`;
    default:
      return '';
  }
}

export function ruleTypeLabel(type: BoundaryRule['type']): string {
  switch (type) {
    case 'work_hours_window':
      return 'Work Hours Window';
    case 'category_limit':
      return 'Category Limit';
    case 'escalation':
      return 'Escalation Chain';
    case 'weekly_hour_budget':
      return 'Weekly Hour Budget';
    case 'daily_task_cap':
    default:
      return 'Daily Task Cap';
  }
}
