import type { CognitiveLoad, TaskMode, TaskPriority } from '@/types/task';

export interface LocalParseResult {
  title: string;
  priority: TaskPriority | null;
  dueDate: string | null;
  dueTime: string | null;
  tags: string[];
  project: string | null;
  recurrenceHint: string | null;
  /**
   * Task mode parsed from `#work-mode` / `#play-mode` / `#relax-mode`
   * suffix hashtags (see `docs/WORK_PLAY_RELAX.md` § *NLP hashtags*).
   * `null` if no mode hashtag was present. The `-mode` suffix exists so
   * `#work` (LifeCategory) and `#work-mode` (TaskMode) don't collide;
   * `#work` is intentionally NOT consumed here.
   */
  taskMode: TaskMode | null;
  /**
   * Cognitive load parsed from `#easy-load` / `#medium-load` / `#hard-load`
   * suffix hashtags (see `docs/COGNITIVE_LOAD.md` § *NLP hashtags*).
   * `null` if no load hashtag was present.
   */
  cognitiveLoad: CognitiveLoad | null;
}

const TASK_MODE_HASHTAGS: Record<string, TaskMode> = {
  'work-mode': 'WORK',
  'play-mode': 'PLAY',
  'relax-mode': 'RELAX',
};

const COGNITIVE_LOAD_HASHTAGS: Record<string, CognitiveLoad> = {
  'easy-load': 'EASY',
  'medium-load': 'MEDIUM',
  'hard-load': 'HARD',
};

function formatDate(d: Date): string {
  return d.toISOString().split('T')[0];
}

function getNextDayOfWeek(dayIndex: number): Date {
  const today = new Date();
  const current = today.getDay();
  let diff = dayIndex - current;
  if (diff <= 0) diff += 7;
  const result = new Date(today);
  result.setDate(result.getDate() + diff);
  return result;
}

const DAY_NAMES: Record<string, number> = {
  sunday: 0, sun: 0,
  monday: 1, mon: 1,
  tuesday: 2, tue: 2, tues: 2,
  wednesday: 3, wed: 3,
  thursday: 4, thu: 4, thur: 4, thurs: 4,
  friday: 5, fri: 5,
  saturday: 6, sat: 6,
};

const MONTH_NAMES: Record<string, number> = {
  jan: 0, january: 0,
  feb: 1, february: 1,
  mar: 2, march: 2,
  apr: 3, april: 3,
  may: 4,
  jun: 5, june: 5,
  jul: 6, july: 6,
  aug: 7, august: 7,
  sep: 8, sept: 8, september: 8,
  oct: 9, october: 9,
  nov: 10, november: 10,
  dec: 11, december: 11,
};

/**
 * Enhanced local NLP fallback parser for quick-add input.
 * Extracts priority (!), tags (#), project (@), dates, times, and recurrence.
 */
export function parseQuickAdd(input: string): LocalParseResult {
  let text = input.trim();
  let priority: TaskPriority | null = null;
  const tags: string[] = [];
  let project: string | null = null;
  let dueDate: string | null = null;
  let dueTime: string | null = null;
  let recurrenceHint: string | null = null;
  let taskMode: TaskMode | null = null;
  let cognitiveLoad: CognitiveLoad | null = null;

  // Extract priority markers: !! (high), ! (medium), !urgent, !high, !medium, !low, !1-4
  const doubleBang = text.match(/!!/);
  if (doubleBang) {
    priority = 2; // high
    text = text.replace('!!', '').trim();
  } else {
    const priorityMatch = text.match(/!(\d|urgent|high|medium|low)\b/i);
    if (priorityMatch) {
      const val = priorityMatch[1].toLowerCase();
      const priorityMap: Record<string, TaskPriority> = {
        '1': 1, urgent: 1,
        '2': 2, high: 2,
        '3': 3, medium: 3,
        '4': 4, low: 4,
      };
      priority = priorityMap[val] ?? null;
      text = text.replace(priorityMatch[0], '').trim();
    }
  }

  // Extract special-suffix hashtags first so they don't get picked up as
  // generic tags below. The `-mode` / `-load` suffixes exist specifically
  // so `#work` (LifeCategory) and `#work-mode` (TaskMode) don't collide.
  // Last-write-wins for multiple matches, mirroring how the generic tag
  // list is appended.
  const modeMatches = text.matchAll(/#(work-mode|play-mode|relax-mode)\b/gi);
  for (const match of modeMatches) {
    taskMode = TASK_MODE_HASHTAGS[match[1].toLowerCase()] ?? taskMode;
  }
  text = text.replace(/#(?:work-mode|play-mode|relax-mode)\b/gi, '').trim();

  const loadMatches = text.matchAll(/#(easy-load|medium-load|hard-load)\b/gi);
  for (const match of loadMatches) {
    cognitiveLoad = COGNITIVE_LOAD_HASHTAGS[match[1].toLowerCase()] ?? cognitiveLoad;
  }
  text = text.replace(/#(?:easy-load|medium-load|hard-load)\b/gi, '').trim();

  // Extract tags: #tagname
  const tagMatches = text.matchAll(/#(\w[\w-]*)/g);
  for (const match of tagMatches) {
    tags.push(match[1]);
  }
  text = text.replace(/#\w[\w-]*/g, '').trim();

  // Extract project: @projectname
  const projectMatch = text.match(/@(\w[\w-]*)/);
  if (projectMatch) {
    project = projectMatch[1];
    text = text.replace(projectMatch[0], '').trim();
  }

  // Extract time: "at 3pm", "at 15:00", "at noon", "at midnight"
  const timeMatch = text.match(
    /\bat\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?|noon|midnight)\b/i,
  );
  if (timeMatch) {
    const timeStr = timeMatch[1].toLowerCase().trim();
    if (timeStr === 'noon') {
      dueTime = '12:00';
    } else if (timeStr === 'midnight') {
      dueTime = '00:00';
    } else {
      const pmMatch = timeStr.match(/(\d{1,2})(?::(\d{2}))?\s*(am|pm)/i);
      if (pmMatch) {
        let hours = parseInt(pmMatch[1]);
        const minutes = pmMatch[2] ? parseInt(pmMatch[2]) : 0;
        const period = pmMatch[3].toLowerCase();
        if (period === 'pm' && hours < 12) hours += 12;
        if (period === 'am' && hours === 12) hours = 0;
        dueTime = `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`;
      } else {
        const militaryMatch = timeStr.match(/(\d{1,2}):(\d{2})/);
        if (militaryMatch) {
          dueTime = `${militaryMatch[1].padStart(2, '0')}:${militaryMatch[2]}`;
        }
      }
    }
    text = text.replace(timeMatch[0], '').trim();
  }

  // Extract recurrence: "daily", "weekly", "monthly", "every Monday", "every day"
  const recurrenceMatch = text.match(
    /\b(daily|weekly|monthly|yearly|every\s+(?:day|week|month|year|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun))\b/i,
  );
  if (recurrenceMatch) {
    const r = recurrenceMatch[1].toLowerCase();
    if (r === 'daily' || r === 'every day') recurrenceHint = 'daily';
    else if (r === 'weekly' || r === 'every week') recurrenceHint = 'weekly';
    else if (r === 'monthly' || r === 'every month') recurrenceHint = 'monthly';
    else if (r === 'yearly' || r === 'every year') recurrenceHint = 'yearly';
    else if (r.startsWith('every ')) {
      recurrenceHint = 'weekly';
      const dayName = r.replace('every ', '');
      if (DAY_NAMES[dayName] !== undefined && !dueDate) {
        dueDate = formatDate(getNextDayOfWeek(DAY_NAMES[dayName]));
      }
    }
    text = text.replace(recurrenceMatch[0], '').trim();
  }

  // Extract dates
  const today = new Date();

  // "today"
  if (/\btoday\b/i.test(text)) {
    dueDate = formatDate(today);
    text = text.replace(/\btoday\b/i, '').trim();
  }
  // "tomorrow"
  else if (/\btomorrow\b/i.test(text)) {
    const d = new Date(today);
    d.setDate(d.getDate() + 1);
    dueDate = formatDate(d);
    text = text.replace(/\btomorrow\b/i, '').trim();
  }
  // "next week"
  else if (/\bnext\s+week\b/i.test(text)) {
    const d = new Date(today);
    d.setDate(d.getDate() + 7);
    dueDate = formatDate(d);
    text = text.replace(/\bnext\s+week\b/i, '').trim();
  }
  // "next Monday", "next Friday" etc.
  else {
    const nextDayMatch = text.match(
      /\bnext\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)\b/i,
    );
    if (nextDayMatch) {
      const dayName = nextDayMatch[1].toLowerCase();
      if (DAY_NAMES[dayName] !== undefined) {
        dueDate = formatDate(getNextDayOfWeek(DAY_NAMES[dayName]));
      }
      text = text.replace(nextDayMatch[0], '').trim();
    }
  }

  // "in N days/weeks"
  if (!dueDate) {
    const inNMatch = text.match(/\bin\s+(\d+)\s+(day|days|week|weeks)\b/i);
    if (inNMatch) {
      const n = parseInt(inNMatch[1]);
      const unit = inNMatch[2].toLowerCase();
      const d = new Date(today);
      if (unit.startsWith('week')) {
        d.setDate(d.getDate() + n * 7);
      } else {
        d.setDate(d.getDate() + n);
      }
      dueDate = formatDate(d);
      text = text.replace(inNMatch[0], '').trim();
    }
  }

  // "Jan 15", "January 15", "Dec 3"
  if (!dueDate) {
    const monthDayMatch = text.match(
      /\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?\b/i,
    );
    if (monthDayMatch) {
      const monthName = monthDayMatch[1].toLowerCase();
      const day = parseInt(monthDayMatch[2]);
      const monthIndex = MONTH_NAMES[monthName] ?? MONTH_NAMES[monthName.slice(0, 3)];
      if (monthIndex !== undefined) {
        const year = today.getFullYear();
        const candidate = new Date(year, monthIndex, day);
        if (candidate < today) candidate.setFullYear(year + 1);
        dueDate = formatDate(candidate);
      }
      text = text.replace(monthDayMatch[0], '').trim();
    }
  }

  // "5/20" or "05/20" (M/D)
  if (!dueDate) {
    const slashMatch = text.match(/\b(\d{1,2})\/(\d{1,2})\b/);
    if (slashMatch) {
      const month = parseInt(slashMatch[1]) - 1;
      const day = parseInt(slashMatch[2]);
      if (month >= 0 && month <= 11 && day >= 1 && day <= 31) {
        const year = today.getFullYear();
        const candidate = new Date(year, month, day);
        if (candidate < today) candidate.setFullYear(year + 1);
        dueDate = formatDate(candidate);
        text = text.replace(slashMatch[0], '').trim();
      }
    }
  }

  // "2026-05-15" (ISO date)
  if (!dueDate) {
    const isoMatch = text.match(/\b(\d{4}-\d{2}-\d{2})\b/);
    if (isoMatch) {
      dueDate = isoMatch[1];
      text = text.replace(isoMatch[0], '').trim();
    }
  }

  return {
    title: text.replace(/\s+/g, ' ').trim(),
    priority,
    dueDate,
    dueTime,
    tags,
    project,
    recurrenceHint,
    taskMode,
    cognitiveLoad,
  };
}
