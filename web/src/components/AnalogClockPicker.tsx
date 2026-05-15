/* eslint-disable react-refresh/only-export-components */
// This module pairs a component with a state hook + helpers (mirrors
// Android's `AnalogClockPicker.kt`). Pure-math helpers live in the
// neighbouring `AnalogClockPickerInternals.ts` module and are imported
// here; the hook ships from this file so callers get one entry point.
import {
  useCallback,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import {
  angleForHourDisplay,
  angleForHourLabel,
  angleForMinute,
  angleForSecond,
  applyPointer,
  clamp,
  formatAnalogClockTime,
  polarToCartesian,
  type AnalogClockState,
  type ClockHand,
} from './AnalogClockPickerInternals';

export type { AnalogClockState, ClockHand } from './AnalogClockPickerInternals';
export {
  angleFromCenter,
  applyPointer,
  formatAnalogClockTime,
  hourFromAngle,
  minuteFromAngle,
} from './AnalogClockPickerInternals';

/**
 * Three-hand analog clock picker — web port of Android's
 * `ui/components/AnalogClockPicker.kt`. The picker is the canonical
 * time-of-day input across PrismTask per the
 * `feedback-time-input-use-clock-not-slider` memory: every time field
 * renders a 3-hand dial (hour / minute / second), even when the data
 * model only stores hour+minute (or just hour). Persist `state.second`
 * only where the data model supports it; otherwise round it away on
 * save.
 *
 * The component is hand-rolled SVG (no third-party clock library) so it
 * matches the Android Canvas reference 1:1 and stays self-contained.
 */

export interface AnalogClockStateApi {
  state: AnalogClockState;
  setHour: (h: number) => void;
  setMinute: (m: number) => void;
  setSecond: (s: number) => void;
  setActiveHand: (hand: ClockHand) => void;
  toggleAmPm: () => void;
}

export interface UseAnalogClockStateOptions {
  initialHour: number;
  initialMinute: number;
  initialSecond?: number;
  is24Hour: boolean;
  initialActiveHand?: ClockHand;
}

/**
 * State hook for [AnalogClockPicker]. Mirrors Android's
 * `rememberAnalogClockState`. Hour is stored 24-hour (0..23) regardless
 * of display mode; the dial renders 1..12 + AM/PM toggle when
 * `is24Hour` is false but persists the 24-hour value.
 */
export function useAnalogClockState(
  opts: UseAnalogClockStateOptions,
): AnalogClockStateApi {
  const {
    initialHour,
    initialMinute,
    initialSecond = 0,
    is24Hour,
    initialActiveHand = 'HOUR',
  } = opts;
  const [state, setState] = useState<AnalogClockState>(() => ({
    hour: clamp(initialHour, 0, 23),
    minute: clamp(initialMinute, 0, 59),
    second: clamp(initialSecond, 0, 59),
    is24Hour,
    activeHand: initialActiveHand,
  }));

  const setHour = useCallback(
    (h: number) =>
      setState((s) => ({ ...s, hour: clamp(Math.round(h), 0, 23) })),
    [],
  );
  const setMinute = useCallback(
    (m: number) =>
      setState((s) => ({ ...s, minute: clamp(Math.round(m), 0, 59) })),
    [],
  );
  const setSecond = useCallback(
    (sec: number) =>
      setState((s) => ({ ...s, second: clamp(Math.round(sec), 0, 59) })),
    [],
  );
  const setActiveHand = useCallback(
    (hand: ClockHand) => setState((s) => ({ ...s, activeHand: hand })),
    [],
  );
  const toggleAmPm = useCallback(
    () => setState((s) => ({ ...s, hour: (s.hour + 12) % 24 })),
    [],
  );

  return { state, setHour, setMinute, setSecond, setActiveHand, toggleAmPm };
}

export interface AnalogClockPickerProps {
  api: AnalogClockStateApi;
  /** Outer size in pixels. Defaults to 256 (matches Android default diameter). */
  diameter?: number;
  className?: string;
}

export function AnalogClockPicker({
  api,
  diameter = 256,
  className = '',
}: AnalogClockPickerProps) {
  const { state } = api;
  return (
    <div
      className={`flex flex-col items-center gap-2 ${className}`}
      data-testid="analog-clock-picker"
    >
      <HandTabRow api={api} />
      <ClockFace api={api} diameter={diameter} />
      <ActiveHandHint hand={state.activeHand} />
      {!state.is24Hour && <AmPmRow api={api} />}
      <SelectedReadout state={state} />
    </div>
  );
}

function HandTabRow({ api }: { api: AnalogClockStateApi }) {
  const { state, setActiveHand } = api;
  return (
    <div className="flex gap-1.5" role="tablist" aria-label="Active hand">
      <HandTab
        label="Hour"
        selected={state.activeHand === 'HOUR'}
        onClick={() => setActiveHand('HOUR')}
      />
      <HandTab
        label="Minute"
        selected={state.activeHand === 'MINUTE'}
        onClick={() => setActiveHand('MINUTE')}
      />
      <HandTab
        label="Second"
        selected={state.activeHand === 'SECOND'}
        onClick={() => setActiveHand('SECOND')}
      />
    </div>
  );
}

function HandTab({
  label,
  selected,
  onClick,
}: {
  label: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      onClick={onClick}
      className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
        selected
          ? 'bg-[var(--color-accent)] text-white'
          : 'border border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
      }`}
    >
      {label}
    </button>
  );
}

function ActiveHandHint({ hand }: { hand: ClockHand }) {
  return (
    <p className="rounded-md bg-[var(--color-bg-secondary)] px-2 py-1 text-center text-xs text-[var(--color-text-secondary)]">
      Tap or drag the face to set the {hand.toLowerCase()} hand.
    </p>
  );
}

function AmPmRow({ api }: { api: AnalogClockStateApi }) {
  const { state, toggleAmPm } = api;
  const isAm = state.hour < 12;
  return (
    <div className="flex gap-1.5" role="group" aria-label="AM or PM">
      <AmPmChip
        label="AM"
        selected={isAm}
        onClick={() => {
          if (!isAm) toggleAmPm();
        }}
      />
      <AmPmChip
        label="PM"
        selected={!isAm}
        onClick={() => {
          if (isAm) toggleAmPm();
        }}
      />
    </div>
  );
}

function AmPmChip({
  label,
  selected,
  onClick,
}: {
  label: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={`rounded-full px-3 py-1 text-xs font-semibold transition-colors ${
        selected
          ? 'bg-[var(--color-accent)]/15 text-[var(--color-accent)]'
          : 'border border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
      }`}
    >
      {label}
    </button>
  );
}

function SelectedReadout({ state }: { state: AnalogClockState }) {
  return (
    <p
      className="text-sm text-[var(--color-text-secondary)]"
      data-testid="analog-clock-readout"
    >
      Selected:{' '}
      <span className="font-mono font-semibold text-[var(--color-text-primary)]">
        {formatAnalogClockTime(
          state.hour,
          state.minute,
          state.second,
          state.is24Hour,
        )}
      </span>
    </p>
  );
}

interface ClockFaceProps {
  api: AnalogClockStateApi;
  diameter: number;
}

function ClockFace({ api, diameter }: ClockFaceProps) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const draggingRef = useRef<boolean>(false);
  const pointerMovedRef = useRef<boolean>(false);

  const { state } = api;

  const ticks = useMemo(() => buildTicks(), []);
  const outerNumbers = useMemo(() => buildOuterNumbers(), []);
  const innerNumbers = useMemo(() => buildInnerNumbers(), []);

  const hourEnd = polarToCartesian(50, angleForHourDisplay(state.hour));
  const minuteEnd = polarToCartesian(74, angleForMinute(state.minute));
  const secondEnd = polarToCartesian(84, angleForSecond(state.second));

  const localFromEvent = useCallback(
    (e: ReactPointerEvent<SVGSVGElement>): { x: number; y: number } | null => {
      const svg = svgRef.current;
      if (!svg) return null;
      const rect = svg.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return null;
      // viewBox is -100..100 → 200 units across the face.
      const x = ((e.clientX - rect.left) / rect.width) * 200 - 100;
      const y = ((e.clientY - rect.top) / rect.height) * 200 - 100;
      return { x, y };
    },
    [],
  );

  const applyAt = useCallback(
    (pos: { x: number; y: number }, advance: boolean) => {
      const next = applyPointer(pos, api.state, advance);
      if (next === api.state) return;
      if (next.hour !== api.state.hour) api.setHour(next.hour);
      if (next.minute !== api.state.minute) api.setMinute(next.minute);
      if (next.second !== api.state.second) api.setSecond(next.second);
      if (next.activeHand !== api.state.activeHand)
        api.setActiveHand(next.activeHand);
    },
    [api],
  );

  const onPointerDown = useCallback(
    (e: ReactPointerEvent<SVGSVGElement>) => {
      if (e.button !== undefined && e.button !== 0) return;
      const pos = localFromEvent(e);
      if (!pos) return;
      draggingRef.current = true;
      pointerMovedRef.current = false;
      svgRef.current?.setPointerCapture?.(e.pointerId);
      e.preventDefault();
      applyAt(pos, /* advance */ false);
    },
    [localFromEvent, applyAt],
  );

  const onPointerMove = useCallback(
    (e: ReactPointerEvent<SVGSVGElement>) => {
      if (!draggingRef.current) return;
      const pos = localFromEvent(e);
      if (!pos) return;
      pointerMovedRef.current = true;
      e.preventDefault();
      applyAt(pos, /* advance */ false);
    },
    [localFromEvent, applyAt],
  );

  const onPointerUp = useCallback(
    (e: ReactPointerEvent<SVGSVGElement>) => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      svgRef.current?.releasePointerCapture?.(e.pointerId);
      // A tap (no significant movement) → advance the active hand.
      if (!pointerMovedRef.current) {
        const pos = localFromEvent(e);
        if (pos) applyAt(pos, /* advance */ true);
      }
    },
    [localFromEvent, applyAt],
  );

  const onPointerCancel = useCallback(() => {
    draggingRef.current = false;
  }, []);

  const size: CSSProperties = { width: diameter, height: diameter };

  return (
    <svg
      ref={svgRef}
      viewBox="-100 -100 200 200"
      role="img"
      aria-label="Analog clock face"
      style={size}
      className="touch-none select-none rounded-full bg-[var(--color-bg-secondary)] shadow-inner"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
    >
      <g>
        {ticks.map((t) => (
          <line
            key={t.key}
            x1={t.x1}
            y1={t.y1}
            x2={t.x2}
            y2={t.y2}
            stroke="var(--color-text-secondary)"
            strokeWidth={t.long ? 1.2 : 0.6}
            strokeLinecap="round"
            opacity={t.long ? 0.85 : 0.5}
          />
        ))}
      </g>
      <g>
        {outerNumbers.map((n) => (
          <text
            key={`outer-${n.label}`}
            x={n.x}
            y={n.y}
            textAnchor="middle"
            dominantBaseline="central"
            fontSize={10}
            fontWeight={500}
            fill="var(--color-text-primary)"
          >
            {n.label}
          </text>
        ))}
      </g>
      {state.is24Hour && (
        <g opacity={0.7}>
          {innerNumbers.map((n) => (
            <text
              key={`inner-${n.label}`}
              x={n.x}
              y={n.y}
              textAnchor="middle"
              dominantBaseline="central"
              fontSize={7}
              fill="var(--color-text-secondary)"
            >
              {n.label}
            </text>
          ))}
        </g>
      )}
      {/* Hand order: SECOND back, MINUTE middle, HOUR front. */}
      <line
        x1={0}
        y1={0}
        x2={secondEnd.x}
        y2={secondEnd.y}
        stroke="var(--color-danger, #ef4444)"
        strokeWidth={1.2}
        strokeLinecap="round"
      />
      <line
        x1={0}
        y1={0}
        x2={minuteEnd.x}
        y2={minuteEnd.y}
        stroke="var(--color-text-secondary)"
        strokeWidth={2.4}
        strokeLinecap="round"
      />
      <line
        x1={0}
        y1={0}
        x2={hourEnd.x}
        y2={hourEnd.y}
        stroke="var(--color-accent)"
        strokeWidth={3.6}
        strokeLinecap="round"
      />
      <circle cx={0} cy={0} r={3.2} fill="var(--color-accent)" />
      <circle cx={0} cy={0} r={1.2} fill="var(--color-bg-secondary)" />
    </svg>
  );
}

function buildTicks(): Array<{
  key: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  long: boolean;
}> {
  const ticks: Array<{
    key: string;
    x1: number;
    y1: number;
    x2: number;
    y2: number;
    long: boolean;
  }> = [];
  const tickOuter = 90;
  for (let i = 0; i < 60; i++) {
    const angle = i * 6;
    const long = i % 5 === 0;
    const inner = tickOuter - (long ? 7 : 3.5);
    const outerPt = polarToCartesian(tickOuter, angle);
    const innerPt = polarToCartesian(inner, angle);
    ticks.push({
      key: `tick-${i}`,
      x1: innerPt.x,
      y1: innerPt.y,
      x2: outerPt.x,
      y2: outerPt.y,
      long,
    });
  }
  return ticks;
}

function buildOuterNumbers(): Array<{ label: string; x: number; y: number }> {
  const out: Array<{ label: string; x: number; y: number }> = [];
  const r = 72;
  for (let h = 1; h <= 12; h++) {
    const pos = polarToCartesian(r, angleForHourLabel(h));
    out.push({ label: String(h), x: pos.x, y: pos.y });
  }
  return out;
}

function buildInnerNumbers(): Array<{ label: string; x: number; y: number }> {
  const out: Array<{ label: string; x: number; y: number }> = [];
  // Inner ring runs 13..00, placed at the same clock position as the
  // outer hour it corresponds to (e.g. 13 sits behind 1, 00 behind 12).
  const r = 52;
  for (let outerH = 1; outerH <= 12; outerH++) {
    const display = outerH === 12 ? 0 : outerH + 12;
    const pos = polarToCartesian(r, angleForHourLabel(outerH));
    out.push({
      label: String(display).padStart(2, '0'),
      x: pos.x,
      y: pos.y,
    });
  }
  return out;
}
