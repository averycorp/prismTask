import { useCallback, useEffect, useMemo, useState } from 'react';
import { format, subDays } from 'date-fns';
import { CalendarDays, Pill } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Select } from '@/components/ui/Select';
import {
  getAllDosesInRange,
  type MedicationDoseDoc,
} from '@/api/firestore/medicationDoses';
import {
  getAllMedications,
  type MedicationDoc,
} from '@/api/firestore/medications';
import {
  getTierStatesInRange,
  type MedicationTierState,
} from '@/api/firestore/medicationSlots';
import { getFirebaseUid } from '@/stores/firebaseUid';

/**
 * Web port of Android's Medication Log. Reads `medication_doses` +
 * `medication_tier_states` for the selected lookback window (default 30
 * days, 90-day max per the audit spec). Groups doses by medication and
 * shows the per-day tier-state alongside so the user can scan
 * "did I take it, when did I take it, what tier was the day at."
 *
 * Synthetic-skip dose rows are excluded by the firestore helpers; this
 * screen never surfaces them.
 */

const RANGE_OPTIONS = [
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
];

function todayIso(): string {
  return format(new Date(), 'yyyy-MM-dd');
}

export function MedicationHistoryScreen() {
  const [rangeDays, setRangeDays] = useState<number>(30);
  const [medications, setMedications] = useState<MedicationDoc[]>([]);
  const [doses, setDoses] = useState<MedicationDoseDoc[]>([]);
  const [tierStates, setTierStates] = useState<MedicationTierState[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterMedId, setFilterMedId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const uid = getFirebaseUid();
      const endIso = todayIso();
      const startIso = format(subDays(new Date(), rangeDays - 1), 'yyyy-MM-dd');
      const [meds, doseRows, tiers] = await Promise.all([
        getAllMedications(uid).catch(() => [] as MedicationDoc[]),
        getAllDosesInRange(uid, startIso, endIso).catch(
          () => [] as MedicationDoseDoc[],
        ),
        getTierStatesInRange(uid, startIso, endIso).catch(
          () => [] as MedicationTierState[],
        ),
      ]);
      setMedications(meds);
      setDoses(doseRows);
      setTierStates(tiers);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to load history');
    } finally {
      setLoading(false);
    }
  }, [rangeDays]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load on range change
    load();
  }, [load]);

  const medsById = useMemo(() => {
    const m = new Map<string, MedicationDoc>();
    for (const med of medications) m.set(med.id, med);
    return m;
  }, [medications]);

  const tiersByDate = useMemo(() => {
    const m = new Map<string, MedicationTierState[]>();
    for (const ts of tierStates) {
      const list = m.get(ts.date_iso) ?? [];
      list.push(ts);
      m.set(ts.date_iso, list);
    }
    return m;
  }, [tierStates]);

  const filteredDoses = useMemo(() => {
    if (filterMedId === null) return doses;
    return doses.filter((d) => d.medication_cloud_id === filterMedId);
  }, [doses, filterMedId]);

  // Group by date (newest first), then within day list doses sorted by takenAt.
  const dayGroups = useMemo(() => {
    const byDay = new Map<string, MedicationDoseDoc[]>();
    for (const dose of filteredDoses) {
      const date = dose.taken_date_local;
      const list = byDay.get(date) ?? [];
      list.push(dose);
      byDay.set(date, list);
    }
    return [...byDay.entries()]
      .map(([date, list]) => ({
        date,
        doses: list.sort((a, b) => b.taken_at - a.taken_at),
      }))
      .sort((a, b) => b.date.localeCompare(a.date));
  }, [filteredDoses]);

  const medSelectOptions = useMemo(
    () => [
      { value: '__all__', label: 'All medications' },
      ...medications
        .filter((m) => !m.is_archived)
        .map((m) => ({ value: m.id, label: m.display_label ?? m.name })),
    ],
    [medications],
  );

  const totalDoses = filteredDoses.length;

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <CalendarDays className="h-5 w-5 text-[var(--color-accent)]" />
          <div>
            <h1 className="text-xl font-semibold text-[var(--color-text-primary)]">
              Medication History
            </h1>
            <p className="text-sm text-[var(--color-text-secondary)]">
              Recent doses and daily tier outcomes.
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <Select
            options={RANGE_OPTIONS}
            value={String(rangeDays)}
            onChange={(v) => setRangeDays(parseInt(v ?? '30', 10) || 30)}
            className="w-40"
          />
          <Select
            options={medSelectOptions}
            value={filterMedId ?? '__all__'}
            onChange={(v) => setFilterMedId(v === '__all__' ? null : v)}
            className="w-56"
          />
        </div>
      </header>

      {loading && doses.length === 0 ? (
        <p className="py-8 text-center text-sm text-[var(--color-text-secondary)]">
          Loading history…
        </p>
      ) : dayGroups.length === 0 ? (
        <EmptyState
          icon={<Pill className="h-8 w-8" />}
          title="No doses logged"
          description="Mark a dose on the Medication screen to start building history."
        />
      ) : (
        <>
          <p className="mb-4 text-xs text-[var(--color-text-secondary)]">
            {totalDoses} dose{totalDoses === 1 ? '' : 's'} in this window
          </p>
          <ul className="flex flex-col gap-4">
            {dayGroups.map((day) => (
              <li
                key={day.date}
                className="rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-card)] p-4"
              >
                <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
                  <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">
                    {format(new Date(`${day.date}T00:00:00`), 'EEEE, MMM d')}
                  </h2>
                  <div className="flex flex-wrap gap-1">
                    {(tiersByDate.get(day.date) ?? []).map((ts) => (
                      <span
                        key={ts.id}
                        className="rounded-full bg-[var(--color-bg-secondary)] px-2 py-0.5 text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]"
                        title={`Slot ${ts.slot_key} → ${ts.tier}`}
                      >
                        {ts.slot_key}: {ts.tier}
                      </span>
                    ))}
                  </div>
                </div>
                <ul className="flex flex-col gap-1">
                  {day.doses.map((dose) => {
                    const med =
                      dose.medication_cloud_id !== null
                        ? medsById.get(dose.medication_cloud_id)
                        : null;
                    const name =
                      med?.display_label ??
                      med?.name ??
                      dose.custom_medication_name ??
                      'Unknown medication';
                    return (
                      <li
                        key={dose.id}
                        className="flex items-center justify-between gap-2 text-xs"
                      >
                        <span className="truncate text-[var(--color-text-primary)]">
                          {name}
                          <span className="ml-2 text-[var(--color-text-secondary)]">
                            ({dose.slot_key})
                          </span>
                        </span>
                        <span className="shrink-0 text-[var(--color-text-secondary)]">
                          {format(new Date(dose.taken_at), 'h:mm a')}
                          {dose.dose_amount && ` · ${dose.dose_amount}`}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </li>
            ))}
          </ul>
        </>
      )}

      <div className="mt-8 flex justify-end">
        <Button variant="ghost" size="sm" onClick={() => load()}>
          Refresh
        </Button>
      </div>
    </div>
  );
}
