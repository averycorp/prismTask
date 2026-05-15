import { describe, it, expect } from 'vitest';
import {
  angleFromCenter,
  hourFromAngle,
  minuteFromAngle,
  applyPointer,
  formatAnalogClockTime,
  type AnalogClockState,
} from '@/components/AnalogClockPickerInternals';

/**
 * Pure-function regression gate for the analog clock picker. Mirrors
 * the Android AnalogClockPickerTest in
 * `app/src/test/java/com/averycorp/prismtask/ui/components/AnalogClockPickerTest.kt`.
 *
 * The web picker uses SVG-local coordinates (viewBox -100..100), so the
 * point inputs differ from the Android tests' pixel coordinates — the
 * assertions on derived state are identical.
 */

function freshState(overrides: Partial<AnalogClockState> = {}): AnalogClockState {
  return {
    hour: 9,
    minute: 0,
    second: 0,
    is24Hour: false,
    activeHand: 'HOUR',
    ...overrides,
  };
}

describe('AnalogClockPicker — angle helpers', () => {
  it('top of clock (dx=0, dy=-r) is 0° → hour 12', () => {
    const angle = angleFromCenter(0, -80);
    expect(angle).toBeCloseTo(0, 1);
    expect(hourFromAngle(angle)).toBe(12);
  });

  it('three o\'clock (dx=+r, dy=0) is 90° → hour 3', () => {
    const angle = angleFromCenter(80, 0);
    expect(angle).toBeCloseTo(90, 1);
    expect(hourFromAngle(angle)).toBe(3);
  });

  it('six o\'clock (dx=0, dy=+r) is 180° → hour 6', () => {
    const angle = angleFromCenter(0, 80);
    expect(angle).toBeCloseTo(180, 1);
    expect(hourFromAngle(angle)).toBe(6);
  });

  it('nine o\'clock (dx=-r, dy=0) is 270° → hour 9', () => {
    const angle = angleFromCenter(-80, 0);
    expect(angle).toBeCloseTo(270, 1);
    expect(hourFromAngle(angle)).toBe(9);
  });

  it('minute at top of clock is 0', () => {
    expect(minuteFromAngle(angleFromCenter(0, -80))).toBe(0);
  });

  it('minute at three o\'clock is 15', () => {
    expect(minuteFromAngle(angleFromCenter(80, 0))).toBe(15);
  });

  it('minute at six o\'clock is 30', () => {
    expect(minuteFromAngle(angleFromCenter(0, 80))).toBe(30);
  });

  it('minute at nine o\'clock is 45', () => {
    expect(minuteFromAngle(angleFromCenter(-80, 0))).toBe(45);
  });
});

describe('AnalogClockPicker — applyPointer drives the active hand', () => {
  it('sets hour to 3 when tapping three o\'clock in 12h AM', () => {
    const state = freshState({ hour: 9, activeHand: 'HOUR' });
    // Three o'clock position in SVG-local coords (radius ~80, +x axis).
    const next = applyPointer({ x: 80, y: 0 }, state, false);
    expect(next.hour).toBe(3);
  });

  it('keeps PM half when user was already on PM in 12h mode', () => {
    const state = freshState({ hour: 21, activeHand: 'HOUR' }); // 9 PM
    const next = applyPointer({ x: 80, y: 0 }, state, false);
    // Tap 3 → state should remain PM, so 15 (3 PM).
    expect(next.hour).toBe(15);
  });

  it('inner-ring pick selects PM in 24h mode', () => {
    const state = freshState({ hour: 9, is24Hour: true, activeHand: 'HOUR' });
    // Outer radius 92, inner threshold 92 * 0.66 ≈ 60.7.
    // Pointer at (40, 0) → 40 units from center → inner ring.
    const next = applyPointer({ x: 40, y: 0 }, state, false);
    expect(next.hour).toBe(15);
  });

  it('outer-ring pick selects AM in 24h mode', () => {
    const state = freshState({ hour: 15, is24Hour: true, activeHand: 'HOUR' });
    const next = applyPointer({ x: 80, y: 0 }, state, false);
    expect(next.hour).toBe(3);
  });

  it('advance moves HOUR → MINUTE on tap', () => {
    const state = freshState({ activeHand: 'HOUR' });
    const next = applyPointer({ x: 80, y: 0 }, state, true);
    expect(next.activeHand).toBe('MINUTE');
  });

  it('advance moves MINUTE → SECOND on tap and records the minute', () => {
    const state = freshState({ activeHand: 'MINUTE' });
    const next = applyPointer({ x: 0, y: 80 }, state, true);
    expect(next.minute).toBe(30);
    expect(next.activeHand).toBe('SECOND');
  });

  it('SECOND is terminal — tap records the second but does not roll back', () => {
    const state = freshState({ activeHand: 'SECOND' });
    const next = applyPointer({ x: 0, y: 80 }, state, true);
    expect(next.second).toBe(30);
    expect(next.activeHand).toBe('SECOND');
  });

  it('ignores taps too close to the center (jitter guard)', () => {
    const state = freshState({ hour: 9, activeHand: 'HOUR' });
    // 2 SVG units from center → below the 4-unit jitter threshold.
    const next = applyPointer({ x: 2, y: 0 }, state, false);
    expect(next).toBe(state);
    expect(next.hour).toBe(9);
  });
});

describe('AnalogClockPicker — readout formatter', () => {
  it('formats 12-hour AM', () => {
    expect(formatAnalogClockTime(8, 30, 0, false)).toBe('8:30:00 AM');
  });

  it('formats 12-hour PM', () => {
    expect(formatAnalogClockTime(14, 30, 45, false)).toBe('2:30:45 PM');
  });

  it('formats midnight as 12 AM in 12h mode', () => {
    expect(formatAnalogClockTime(0, 0, 0, false)).toBe('12:00:00 AM');
  });

  it('formats noon as 12 PM in 12h mode', () => {
    expect(formatAnalogClockTime(12, 0, 0, false)).toBe('12:00:00 PM');
  });

  it('pads hour, minute, second in 24-hour mode', () => {
    expect(formatAnalogClockTime(8, 5, 9, true)).toBe('08:05:09');
  });

  it('formats midnight as 00:00:00 in 24-hour mode', () => {
    expect(formatAnalogClockTime(0, 0, 0, true)).toBe('00:00:00');
  });
});
