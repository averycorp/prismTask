/**
 * Offset (0–6) of `date` within its Monday-started week. Mon=0 … Sun=6.
 *
 * Used to default the mobile single-day calendar card to *today* rather
 * than the start of the week (bug B-11):
 * `addDays(startOfWeek(date, { weekStartsOn: 1 }), weekOffsetFromMonday(date))`
 * round-trips back to `date`.
 */
export function weekOffsetFromMonday(date: Date): number {
  const jsDay = date.getDay(); // 0=Sun … 6=Sat
  return (jsDay + 6) % 7; // Mon=0 … Sun=6
}
