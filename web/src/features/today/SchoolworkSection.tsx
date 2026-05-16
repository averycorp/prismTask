import { SchoolworkTodayCard } from '@/features/today/SchoolworkTodayCard';

/**
 * Dashboard-order wrapper for the Schoolwork Today card. Mirrors
 * Android's `SchoolworkSection.kt` slot — pure delegation to the
 * existing `SchoolworkTodayCard` so the dashboard preferences switch
 * in `TodayScreen.tsx` can drive its position in the section list.
 *
 * The wrapped card already handles its own visibility (returns `null`
 * when neither active courses nor due-today assignments exist), so no
 * additional gating here.
 *
 * Parity unit 7 of 23.
 */
export function SchoolworkSection() {
  return (
    <div className="mb-4">
      <SchoolworkTodayCard />
    </div>
  );
}

export default SchoolworkSection;
