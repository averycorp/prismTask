/**
 * Pure helpers + state shape backing [AnalogClockPicker]. Split out of
 * the component module so eslint's `react-refresh/only-export-components`
 * stays happy and the math is independently testable from React.
 *
 * Mirrors the helpers in Android's
 * `app/src/main/java/com/averycorp/prismtask/ui/components/AnalogClockPicker.kt`.
 */

export type ClockHand = 'HOUR' | 'MINUTE' | 'SECOND';

export interface AnalogClockState {
  hour: number; // 0..23 (24-hour internal, even when display is 12-hour)
  minute: number; // 0..59
  second: number; // 0..59
  is24Hour: boolean;
  activeHand: ClockHand;
}

/**
 * Apply a pointer at [pos] (in the SVG's local coordinate space — the
 * viewBox is `-100 -100 200 200`, so the center is `(0, 0)`) to the
 * active hand of the supplied [state] and return a new state object.
 *
 * When the pointer is the result of a tap (not a drag),
 * [advanceActiveHand] advances HOUR → MINUTE → SECOND so the user can
 * tap-tap-tap through all three picks without re-selecting tabs.
 *
 * 24-hour mode: an inner-ring pointer for the HOUR hand adds 12 so the
 * user can pick the PM half directly. Outer-ring picks keep the
 * existing AM/PM half (preserving the user's intent when they're
 * refining a time).
 */
export function applyPointer(
  pos: { x: number; y: number },
  state: AnalogClockState,
  advanceActiveHand: boolean,
): AnalogClockState {
  const dx = pos.x;
  const dy = pos.y;
  const distance = Math.hypot(dx, dy);
  // Ignore taps near the center to avoid jitter — the user clearly
  // didn't aim a hand there. SVG units: outer radius is 92, so 4 is the
  // proportional analog of Android's 8px-in-256dp threshold.
  if (distance < 4) return state;
  const angle = angleFromCenter(dx, dy);
  switch (state.activeHand) {
    case 'HOUR': {
      // Outer ring extends to radius 92 in our SVG (see ClockFace).
      const outerRadius = 92;
      const innerRing = state.is24Hour && distance < outerRadius * 0.66;
      const hour12 = hourFromAngle(angle);
      let nextHour: number;
      if (state.is24Hour) {
        nextHour = innerRing ? (hour12 + 12) % 24 : hour12;
      } else {
        const keepPm = state.hour >= 12;
        nextHour = keepPm ? (hour12 % 12) + 12 : hour12 % 12;
      }
      return {
        ...state,
        hour: clamp(nextHour, 0, 23),
        activeHand: advanceActiveHand ? 'MINUTE' : state.activeHand,
      };
    }
    case 'MINUTE': {
      return {
        ...state,
        minute: minuteFromAngle(angle),
        activeHand: advanceActiveHand ? 'SECOND' : state.activeHand,
      };
    }
    case 'SECOND': {
      // SECOND is terminal — no further advancement.
      return {
        ...state,
        second: minuteFromAngle(angle),
      };
    }
    default:
      return state;
  }
}

/** Angle (degrees, 0° at 12 o'clock, clockwise) from a pointer offset relative to center. */
export function angleFromCenter(dx: number, dy: number): number {
  // atan2 returns -PI..PI with 0 at +x; rotate so 0° is at 12 o'clock
  // and direction flips to clockwise.
  const raw = (Math.atan2(dy, dx) * 180) / Math.PI;
  const rotated = raw + 90;
  return ((rotated % 360) + 360) % 360;
}

/** Discretise a touch angle into a 1..12 hour-of-12. 0° maps to 12. */
export function hourFromAngle(angle: number): number {
  const raw = Math.round(angle / 30) % 12;
  return raw === 0 ? 12 : raw;
}

/** Discretise a touch angle into a 0..59 minute. */
export function minuteFromAngle(angle: number): number {
  return ((Math.round(angle / 6) % 60) + 60) % 60;
}

export function angleForMinute(minute: number): number {
  return (((minute % 60) + 60) % 60) * 6;
}

export function angleForSecond(second: number): number {
  return (((second % 60) + 60) % 60) * 6;
}

/**
 * Position of the hour HAND in degrees. The hour hand sits on its
 * label — the picker overwrites minute first via the MINUTE tab, so
 * the hand never needs to drift between hours visually.
 */
export function angleForHourDisplay(hour: number): number {
  const h12 = hour % 12;
  return h12 * 30;
}

/** Center angle for the printed hour number `h` (1..12). 12 sits at the top. */
export function angleForHourLabel(h: number): number {
  return (h % 12) * 30;
}

/** "h:mm:ss AM/PM" (12-hour) or "HH:mm:ss" (24-hour). Locale-stable. */
export function formatAnalogClockTime(
  hour: number,
  minute: number,
  second: number,
  is24Hour: boolean,
): string {
  const h = clamp(hour, 0, 23);
  const m = String(clamp(minute, 0, 59)).padStart(2, '0');
  const s = String(clamp(second, 0, 59)).padStart(2, '0');
  if (is24Hour) {
    return `${String(h).padStart(2, '0')}:${m}:${s}`;
  }
  let display: number;
  if (h === 0) display = 12;
  else if (h > 12) display = h - 12;
  else display = h;
  const suffix = h < 12 ? 'AM' : 'PM';
  return `${display}:${m}:${s} ${suffix}`;
}

export function clamp(v: number, lo: number, hi: number): number {
  if (Number.isNaN(v)) return lo;
  return Math.max(lo, Math.min(hi, v));
}

export function polarToCartesian(
  radius: number,
  angleDegrees: number,
): { x: number; y: number } {
  // 0° at 12 o'clock, clockwise. Math angle = degrees - 90.
  const rad = ((angleDegrees - 90) * Math.PI) / 180;
  return { x: radius * Math.cos(rad), y: radius * Math.sin(rad) };
}
