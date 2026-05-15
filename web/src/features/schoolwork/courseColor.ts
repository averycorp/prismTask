/**
 * Shared color helpers for the schoolwork surface. Courses persist
 * their accent as an ARGB int (matches Android's `CourseEntity.color`);
 * the web renders it as a CSS hex string.
 */

/**
 * ARGB int → CSS hex string (`#rrggbb`). Drops the alpha channel since
 * the surfaces using this (dots, swatches) layer flat on top of
 * existing backgrounds. Falls back to a neutral gray for the `color
 * === 0` default that Android writes for uncolored courses.
 */
export function argbToCss(color: number): string {
  if (color === 0) return '#888';
  return '#' + (color >>> 0).toString(16).padStart(8, '0').slice(2);
}

/**
 * The web-side course-color palette, shared by the editor swatch
 * picker and any read-side surface that wants to render a known-good
 * sample. Each entry exposes both the ARGB int (the Firestore /
 * Android wire format) and the CSS hex (what the editor renders for
 * preview). Order chosen to match the lucide/tailwind defaults so
 * existing accent colors elsewhere in the app stay visually
 * consistent.
 */
export const COURSE_COLOR_OPTIONS: { argb: number; hex: string }[] = [
  { argb: 0xff6366f1, hex: '#6366f1' }, // indigo
  { argb: 0xff8b5cf6, hex: '#8b5cf6' }, // violet
  { argb: 0xffec4899, hex: '#ec4899' }, // pink
  { argb: 0xffef4444, hex: '#ef4444' }, // red
  { argb: 0xfff97316, hex: '#f97316' }, // orange
  { argb: 0xfff59e0b, hex: '#f59e0b' }, // amber
  { argb: 0xff22c55e, hex: '#22c55e' }, // green
  { argb: 0xff10b981, hex: '#10b981' }, // emerald
  { argb: 0xff14b8a6, hex: '#14b8a6' }, // teal
  { argb: 0xff06b6d4, hex: '#06b6d4' }, // cyan
  { argb: 0xff3b82f6, hex: '#3b82f6' }, // blue
  { argb: 0xff6b7280, hex: '#6b7280' }, // slate
];
