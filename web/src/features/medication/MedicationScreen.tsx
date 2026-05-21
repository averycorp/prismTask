import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { addDays, format, parseISO } from 'date-fns';
import {
  Archive,
  ArchiveRestore,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Clock,
  FileText,
  ListChecks,
  Pencil,
  Pill,
  Plus,
  PlusCircle,
  RefreshCw,
  Undo2,
} from 'lucide-react';
import { toast } from 'sonner';
import {
  clearTierState,
  getTierStatesForDate,
  setTierState,
  setTierStateIntendedTime,
  setTierStatesAtomic,
  type MedicationTier,
  type MedicationTierState,
} from '@/api/firestore/medicationSlots';
import {
  archiveMedication,
  unarchiveMedication,
  type MedicationDoc,
} from '@/api/firestore/medications';
import {
  deleteDose,
  getDosesForDay,
  logDose,
  type MedicationDoseDoc,
} from '@/api/firestore/medicationDoses';
import {
  assignmentKey,
  buildDoseAssignment,
} from '@/features/medication/doseAssignment';
import { deriveVirtualSlots } from '@/features/medication/virtualSlots';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { MedicationSlotDetailModal } from '@/features/daily-essentials/MedicationSlotDetailModal';
import { BulkMarkDialog } from '@/features/medication/BulkMarkDialog';
import { MedicationEditorDialog } from '@/features/medication/MedicationEditorDialog';
import { MedicationTierPicker } from '@/features/medication/MedicationTierPicker';
import { MedicationTimeEditModal } from '@/features/medication/MedicationTimeEditModal';
import { isBacklogged } from '@/features/medication/backloggedHelpers';
import { useMedicationsStore } from '@/stores/medicationsStore';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import type { MedicationSlot } from '@/types/dailyEssentials';

const ANYTIME_KEY = 'anytime';
const MED_PREFIX = 'med:';

/**
 * Strip the `med:` prefix off a slot medId. Returns null for legacy
 * non-`med:` entries (e.g. `self_care_step:lipitor`) which the old
 * backend slot snapshot used; the Firestore-direct path only mints
 * `med:` keys so this is a forward-compat guard, not a hot code path.
 */
function medCloudIdOf(raw: string): string | null {
  return raw.startsWith(MED_PREFIX) ? raw.slice(MED_PREFIX.length) : null;
}

/**
 * Dedicated Medication screen. Reads slot scaffolding from the user's
 * medication library (Firestore `users/{uid}/medications`) and renders
 * taken-state from per-medication doses (`medication_doses`) and tier
 * states (`medication_tier_states`) — both Firestore-direct. The legacy
 * backend `daily_essential_slot_completions` table is no longer consulted
 * by web; it was a one-directional source-of-truth that never received
 * Android's writes, so Android-logged doses never surfaced here.
 *
 * What this screen adds over the Today row:
 *   - Per-day navigation (prev / today / next)
 *   - Full-card layout per slot instead of the compact row
 *   - Summary header (N slots, M taken)
 *   - Shared DayBoundary-aware "today" default so late-night users
 *     see the correct logical day.
 */
export function MedicationScreen() {
  const startOfDayHour = useSettingsStore((s) => s.startOfDayHour);
  const todayIso = useLogicalToday(startOfDayHour);

  const [dateIso, setDateIso] = useState(todayIso);
  const [tierStates, setTierStates] = useState<
    Record<string, MedicationTierState>
  >({});
  const [loading, setLoading] = useState(false);
  const [openSlot, setOpenSlot] = useState<MedicationSlot | null>(null);
  // Slot whose intended_time is being edited (right-click / long-press).
  const [timeEditingSlot, setTimeEditingSlot] = useState<MedicationSlot | null>(
    null,
  );
  const [bulkMarkOpen, setBulkMarkOpen] = useState(false);
  // Per-medication doses for `dateIso`, keyed by medication cloud id.
  // Keyed by med (not by `(med, slot, day)` doc id) because Android's
  // UI dose-write path stores `slotKey = slot.id.toString()` — a local
  // Room id that's meaningless on web — while web mints bucket-style
  // slot keys (`morning` / `08:00` / `anytime`). A slot-key-aware
  // lookup never matched Android-logged doses; everything showed
  // Pending. Per-med-per-day is the lowest common denominator that
  // surfaces taken-state regardless of which platform recorded it.
  const [dosesByMed, setDosesByMed] = useState<
    Record<string, MedicationDoseDoc[]>
  >({});
  // Medication library (parity Batch 5 PR-1): full add / edit / archive
  // surface backed by `users/{uid}/medications` Firestore writes. Read
  // from the live store wired by `useFirestoreSync.subscribeToMedications`
  // so Android-side adds / edits / archives surface here without a manual
  // refetch — a one-shot `getAllMedications` would race the Firestore
  // listener and leave the screen blank on first paint.
  const medications = useMedicationsStore((s) => s.medications);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingMed, setEditingMed] = useState<MedicationDoc | null>(null);
  const [showArchived, setShowArchived] = useState(false);

  const load = useCallback(
    async (iso: string, meds: readonly MedicationDoc[]) => {
      setLoading(true);
      try {
        const uid = getFirebaseUid();
        const medCloudIds = meds
          .filter((m) => !m.is_archived && m.name.length > 0)
          .map((m) => m.id);
        // Fetch tier states + per-medication doses in parallel. Both
        // live in Firestore; both follow the same `users/{uid}/...`
        // shape Android writes to, so what the user logs on phone
        // shows up here on the next snapshot.
        const [states, allDoses] = await Promise.all([
          getTierStatesForDate(uid, iso).catch(() => []),
          Promise.all(
            medCloudIds.map((medId) =>
              getDosesForDay(uid, medId, iso).catch(() => []),
            ),
          ),
        ]);
        const byTierKey: Record<string, MedicationTierState> = {};
        for (const s of states) byTierKey[s.slot_key] = s;
        setTierStates(byTierKey);
        // Bucket every today's dose under its medication cloud id —
        // ignoring `slot_key` because Android's UI dose-write path uses
        // a local-Room-id-as-string that web can't reverse-map. The
        // resulting "any dose for this med today" predicate is what
        // the per-med row and slot-pending pill consult below.
        const byMed: Record<string, MedicationDoseDoc[]> = {};
        for (const list of allDoses) {
          for (const dose of list) {
            const medId = dose.medication_cloud_id;
            if (medId === null) continue;
            (byMed[medId] ??= []).push(dose);
          }
        }
        setDosesByMed(byMed);
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const isMedTakenToday = (medCloudId: string): boolean => {
    return (dosesByMed[medCloudId]?.length ?? 0) > 0;
  };

  const handleDoseToggle = async (slotKey: string, medCloudId: string) => {
    try {
      const uid = getFirebaseUid();
      const existing = dosesByMed[medCloudId] ?? [];
      if (existing.length > 0) {
        // Already taken today (on any slot, any device). Toggle off by
        // removing every dose row for this med on this day — keeps the
        // per-med checkbox truthy when at least one dose remains and
        // false when the user has explicitly un-taken every one of them.
        for (const dose of existing) {
          await deleteDose(uid, dose.id);
        }
        setDosesByMed((prev) => {
          const next = { ...prev };
          delete next[medCloudId];
          return next;
        });
      } else {
        const dose = await logDose(uid, {
          medicationCloudId: medCloudId,
          slotKey,
          dateIso,
        });
        setDosesByMed((prev) => ({ ...prev, [medCloudId]: [dose] }));
      }
    } catch (e) {
      toast.error((e as Error).message || 'Failed to toggle dose');
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load tier states + doses on mount and when date / medication library changes
    load(dateIso, medications);
  }, [dateIso, medications, load]);

  // Slot scaffold derived purely from the medication library plus a
  // per-render dose-to-slot assignment (see `doseAssignment.ts`). The
  // assignment first tries exact `slot_key` matches (web-logged doses)
  // and falls back to time-of-day proximity for opaque local-id slot
  // keys (Android-logged doses), so the per-med time chips and slot-
  // pending pills stay precise regardless of which platform recorded
  // the dose. Slots whose medIds are all non-`med:` (legacy / external)
  // fall back to null since web has no source-of-truth for them.
  const virtualSlots = useMemo(
    () => deriveVirtualSlots(medications),
    [medications],
  );
  const doseAssignment = useMemo(
    () => buildDoseAssignment(virtualSlots, dosesByMed),
    [virtualSlots, dosesByMed],
  );
  const slots = useMemo<MedicationSlot[]>(() => {
    return virtualSlots.map((slot) => {
      const medCloudIds = slot.medIds
        .map(medCloudIdOf)
        .filter((id): id is string => id !== null);
      if (medCloudIds.length === 0) return slot;
      let latest = 0;
      for (const medId of medCloudIds) {
        const dose = doseAssignment.get(assignmentKey(slot.slotKey, medId));
        if (dose === undefined) return { ...slot, takenAt: null };
        if (dose.taken_at > latest) latest = dose.taken_at;
      }
      return { ...slot, takenAt: latest === 0 ? null : latest };
    });
  }, [virtualSlots, doseAssignment]);
  const takenCount = slots.filter((s) => s.takenAt !== null).length;

  const handleToggle = async (slot: MedicationSlot, taken: boolean) => {
    try {
      const uid = getFirebaseUid();
      const medCloudIds = slot.medIds
        .map(medCloudIdOf)
        .filter((id): id is string => id !== null);
      if (medCloudIds.length === 0) {
        setOpenSlot(null);
        return;
      }
      if (taken) {
        // Log a dose for every linked med that doesn't already have one
        // today (any slot, any device). Run sequentially so a partial
        // failure stops at the first error rather than fan-failing N
        // writes and double-toasting.
        const newDoses: { medId: string; dose: MedicationDoseDoc }[] = [];
        for (const medCloudId of medCloudIds) {
          if ((dosesByMed[medCloudId]?.length ?? 0) > 0) continue;
          const dose = await logDose(uid, {
            medicationCloudId: medCloudId,
            slotKey: slot.slotKey,
            dateIso,
          });
          newDoses.push({ medId: medCloudId, dose });
        }
        if (newDoses.length > 0) {
          setDosesByMed((prev) => {
            const next = { ...prev };
            for (const { medId, dose } of newDoses) {
              next[medId] = [...(next[medId] ?? []), dose];
            }
            return next;
          });
        }
      } else {
        const removedMedIds: string[] = [];
        for (const medCloudId of medCloudIds) {
          const existing = dosesByMed[medCloudId] ?? [];
          if (existing.length === 0) continue;
          for (const dose of existing) {
            await deleteDose(uid, dose.id);
          }
          removedMedIds.push(medCloudId);
        }
        if (removedMedIds.length > 0) {
          setDosesByMed((prev) => {
            const next = { ...prev };
            for (const medId of removedMedIds) delete next[medId];
            return next;
          });
        }
      }
      setOpenSlot(null);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update slot');
    }
  };

  const handleTierChange = async (slot: MedicationSlot, tier: MedicationTier) => {
    try {
      const uid = getFirebaseUid();
      const next = await setTierState(uid, dateIso, slot.slotKey, tier, 'user_set');
      setTierStates((prev) => ({ ...prev, [slot.slotKey]: next }));
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update tier');
    }
  };

  const handleTierClear = async (slot: MedicationSlot) => {
    try {
      const uid = getFirebaseUid();
      await clearTierState(uid, dateIso, slot.slotKey);
      setTierStates((prev) => {
        const next = { ...prev };
        delete next[slot.slotKey];
        return next;
      });
    } catch (e) {
      toast.error((e as Error).message || 'Failed to clear tier');
    }
  };

  const handleBulkMark = async ({
    scope,
    slotKey: pickedSlotKey,
    tier,
  }: {
    scope: 'slot' | 'full_day';
    slotKey: string | null;
    tier: MedicationTier;
  }) => {
    try {
      const uid = getFirebaseUid();
      if (scope === 'slot') {
        if (!pickedSlotKey) return;
        // Single-doc bulk = a normal setTierState. Skipping the
        // writeBatch helper here saves a tiny amount of overhead and
        // matches what the per-slot tier-picker already does.
        const next = await setTierState(uid, dateIso, pickedSlotKey, tier, 'user_set');
        setTierStates((prev) => ({ ...prev, [pickedSlotKey]: next }));
        toast.success(`Marked slot "${pickedSlotKey}" as ${tier}`);
      } else {
        if (slots.length === 0) return;
        const updates = slots.map((s) => ({
          dateIso,
          slotKey: s.slotKey,
          tier,
          source: 'user_set' as const,
        }));
        await setTierStatesAtomic(uid, updates);
        // Re-pull so we surface the merged state with the same shape
        // the rest of the screen reads. The atomic helper doesn't
        // round-trip per doc.
        const refreshed = await getTierStatesForDate(uid, dateIso);
        const byKey: Record<string, MedicationTierState> = {};
        for (const s of refreshed) byKey[s.slot_key] = s;
        setTierStates(byKey);
        toast.success(`Marked ${updates.length} slots as ${tier}`);
      }
      setBulkMarkOpen(false);
    } catch (e) {
      toast.error((e as Error).message || 'Bulk mark failed');
    }
  };

  const handleSaveIntendedTime = async (
    slot: MedicationSlot,
    intendedTime: number,
  ) => {
    try {
      const uid = getFirebaseUid();
      const next = await setTierStateIntendedTime(
        uid,
        dateIso,
        slot.slotKey,
        intendedTime,
      );
      setTierStates((prev) => ({ ...prev, [slot.slotKey]: next }));
      setTimeEditingSlot(null);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to save time');
    }
  };

  const shift = (days: number) => {
    const next = format(addDays(parseISO(dateIso), days), 'yyyy-MM-dd');
    setDateIso(next);
  };

  const handleAddMedication = () => {
    setEditingMed(null);
    setEditorOpen(true);
  };

  const handleEditMedication = (med: MedicationDoc) => {
    setEditingMed(med);
    setEditorOpen(true);
  };

  const handleArchiveToggle = async (med: MedicationDoc) => {
    try {
      const uid = getFirebaseUid();
      if (med.is_archived) {
        await unarchiveMedication(uid, med.id);
        toast.success(`Restored ${med.name}`);
      } else {
        await archiveMedication(uid, med.id);
        toast.success(`Archived ${med.name}`);
      }
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update medication');
    }
  };

  const handleEditorSaved = async () => {
    // No-op — the live Firestore listener on `useMedicationsStore` picks
    // up the new / edited doc on its next snapshot, so a manual refetch
    // here would just duplicate work and risk a flicker.
  };

  const visibleMedications = useMemo(
    () =>
      showArchived
        ? medications
        : medications.filter((m) => !m.is_archived),
    [medications, showArchived],
  );
  const archivedCount = medications.filter((m) => m.is_archived).length;

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Pill
            className="h-5 w-5 text-[var(--color-accent)]"
            aria-hidden="true"
          />
          <div>
            <h1 className="text-xl font-semibold text-[var(--color-text-primary)]">
              Medications
            </h1>
            <p className="text-sm text-[var(--color-text-secondary)]">
              {format(parseISO(dateIso), 'EEEE, MMMM d')}
              {dateIso !== todayIso && ' · ' + (dateIso < todayIso ? 'Past' : 'Future')}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          <Link to="/medication/history">
            <Button
              variant="ghost"
              size="sm"
              aria-label="View history"
              title="View history"
            >
              <CalendarDays className="h-4 w-4" />
            </Button>
          </Link>
          <Link to="/medication/refills">
            <Button
              variant="ghost"
              size="sm"
              aria-label="Manage refills"
              title="Manage refills"
            >
              <RefreshCw className="h-4 w-4" />
            </Button>
          </Link>
          <Link to="/medication/clinical-report">
            <Button
              variant="ghost"
              size="sm"
              aria-label="Open clinical report"
              title="Open clinical report"
            >
              <FileText className="h-4 w-4" />
            </Button>
          </Link>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setBulkMarkOpen(true)}
            disabled={slots.length === 0}
            aria-label="Bulk mark medications"
            title="Bulk mark medications"
          >
            <ListChecks className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={() => shift(-1)} aria-label="Previous day">
            <ChevronLeft className="h-4 w-4" />
          </Button>
          {dateIso !== todayIso && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setDateIso(todayIso)}
            >
              <Undo2 className="mr-1 h-3.5 w-3.5" />
              Today
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => shift(1)} aria-label="Next day">
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </header>

      <BulkMarkDialog
        isOpen={bulkMarkOpen}
        slots={slots}
        onCancel={() => setBulkMarkOpen(false)}
        onConfirm={handleBulkMark}
      />

      {slots.length > 0 && (
        <p className="mb-4 text-xs text-[var(--color-text-secondary)]">
          {takenCount} of {slots.length} slot{slots.length === 1 ? '' : 's'} taken
        </p>
      )}

      {loading && slots.length === 0 ? (
        <p className="py-8 text-center text-sm text-[var(--color-text-secondary)]">
          Loading…
        </p>
      ) : slots.length === 0 ? (
        <EmptyState
          icon={<PlusCircle className="h-8 w-8" />}
          title="No medication slots"
          description="Add a medication below to derive slot rows from its schedule."
        />
      ) : (
        <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {[...slots]
            .sort((a, b) => {
              if (a.slotKey === ANYTIME_KEY) return 1;
              if (b.slotKey === ANYTIME_KEY) return -1;
              return a.slotKey.localeCompare(b.slotKey);
            })
            .map((slot) => {
              const taken = slot.takenAt !== null;
              return (
                <li
                  key={slot.slotKey}
                  className={`flex flex-col gap-2 rounded-xl border p-4 transition-colors ${
                    taken
                      ? 'border-emerald-500/40 bg-emerald-500/5'
                      : 'border-[var(--color-border)] bg-[var(--color-bg-card)]'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-semibold text-[var(--color-text-primary)]">
                      {slot.displayTime}
                    </span>
                    <span
                      className={`rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide ${
                        taken
                          ? 'bg-emerald-500 text-white'
                          : 'bg-[var(--color-bg-secondary)] text-[var(--color-text-secondary)]'
                      }`}
                    >
                      {taken ? 'Taken' : 'Pending'}
                    </span>
                  </div>
                  {slot.medIds.length === 0 ? (
                    <p className="text-xs text-[var(--color-text-secondary)]">
                      No medications linked
                    </p>
                  ) : (
                    <ul className="flex flex-col gap-1">
                      {slot.medIds.map((raw, idx) => {
                        const isMedRow = raw.startsWith('med:');
                        const medCloudId = isMedRow
                          ? raw.slice('med:'.length)
                          : null;
                        const label = slot.medLabels[idx] ?? raw;
                        const checked =
                          medCloudId !== null && isMedTakenToday(medCloudId);
                        const takenAt =
                          medCloudId !== null
                            ? (doseAssignment.get(
                                assignmentKey(slot.slotKey, medCloudId),
                              )?.taken_at ?? null)
                            : null;
                        return (
                          <li
                            key={raw}
                            className="flex items-center justify-between gap-2 text-xs"
                          >
                            <span
                              className={`flex-1 truncate ${
                                checked
                                  ? 'text-[var(--color-text-secondary)] line-through'
                                  : 'text-[var(--color-text-primary)]'
                              }`}
                              title={label}
                            >
                              {label}
                            </span>
                            {takenAt !== null && (
                              <span className="text-[10px] font-medium text-[var(--color-accent)]">
                                {format(new Date(takenAt), 'h:mm a')}
                              </span>
                            )}
                            {medCloudId !== null ? (
                              <button
                                type="button"
                                onClick={() =>
                                  handleDoseToggle(slot.slotKey, medCloudId)
                                }
                                aria-pressed={checked}
                                aria-label={
                                  checked
                                    ? `Mark ${label} not taken`
                                    : `Mark ${label} taken`
                                }
                                className={`rounded-full border px-2 py-0.5 text-[10px] uppercase tracking-wide transition-colors ${
                                  checked
                                    ? 'border-emerald-500 bg-emerald-500 text-white'
                                    : 'border-[var(--color-border)] bg-[var(--color-bg-card)] text-[var(--color-text-secondary)] hover:bg-[var(--color-bg-secondary)]'
                                }`}
                              >
                                {checked ? 'Taken' : 'Mark'}
                              </button>
                            ) : (
                              <span className="text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]">
                                {raw.split(':')[0]}
                              </span>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                  <div className="flex flex-col gap-1">
                    <span className="flex items-center gap-1 text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]">
                      Tier
                      {tierStates[slot.slotKey]?.source === 'user_set' && (
                        <span className="ml-1 text-[var(--color-accent)]">
                          (manual)
                        </span>
                      )}
                      {isBacklogged(
                        tierStates[slot.slotKey]?.intended_time ?? null,
                        tierStates[slot.slotKey]?.logged_at ?? null,
                      ) && (
                        <span
                          className="ml-1 inline-flex items-center gap-0.5 text-[var(--color-accent)]"
                          title={(() => {
                            const ts = tierStates[slot.slotKey];
                            if (!ts || ts.intended_time === null) return '';
                            const d = new Date(ts.intended_time);
                            const hh = String(d.getHours()).padStart(2, '0');
                            const mm = String(d.getMinutes()).padStart(2, '0');
                            return `Logged at ${hh}:${mm}`;
                          })()}
                        >
                          <Clock className="h-2.5 w-2.5" aria-hidden="true" />
                          backlogged
                        </span>
                      )}
                    </span>
                    {/*
                     * The wrapper div picks up the right-click + touch-and-hold
                     * gestures so the time-edit modal opens for the slot. Tap /
                     * single-click flow inside MedicationTierPicker is unchanged.
                     */}
                    <TierPickerWithLongPress
                      onLongPress={() => setTimeEditingSlot(slot)}
                    >
                      <MedicationTierPicker
                        value={tierStates[slot.slotKey]?.tier ?? null}
                        isUserSet={
                          tierStates[slot.slotKey]?.source === 'user_set'
                        }
                        onChange={(tier) => handleTierChange(slot, tier)}
                        onClear={() => handleTierClear(slot)}
                      />
                    </TierPickerWithLongPress>
                  </div>
                  <div className="flex justify-end gap-1.5">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setOpenSlot(slot)}
                    >
                      Details
                    </Button>
                    <Button
                      variant={taken ? 'secondary' : 'primary'}
                      size="sm"
                      onClick={() => handleToggle(slot, !taken)}
                    >
                      {taken ? 'Mark Not Taken' : 'Mark Taken'}
                    </Button>
                  </div>
                </li>
              );
            })}
        </ul>
      )}

      {openSlot && (
        <MedicationSlotDetailModal
          slot={openSlot}
          onClose={() => setOpenSlot(null)}
          onToggleSlot={(taken) => handleToggle(openSlot, taken)}
        />
      )}

      {timeEditingSlot && (
        <MedicationTimeEditModal
          isOpen={true}
          slotKey={timeEditingSlot.slotKey}
          slotLabel={timeEditingSlot.displayTime}
          initialIntendedTime={
            tierStates[timeEditingSlot.slotKey]?.intended_time ?? null
          }
          dateIso={dateIso}
          onClose={() => setTimeEditingSlot(null)}
          onSave={(intendedTime) =>
            handleSaveIntendedTime(timeEditingSlot, intendedTime)
          }
        />
      )}

      {/*
       * Medication library (parity Batch 5 PR-1). Sits below the per-day
       * tier-state grid so the day-focused workflow stays the primary
       * surface; this section is for adding, editing, and archiving the
       * underlying medication records that feed Android slot linking.
       */}
      <section className="mt-10 border-t border-[var(--color-border)] pt-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold text-[var(--color-text-primary)]">
              My Medications
            </h2>
            <p className="text-xs text-[var(--color-text-secondary)]">
              Add and edit the medications that show up in slot toggles.
              Android handles dose-history projection and slot linking.
            </p>
          </div>
          <div className="flex items-center gap-2">
            {archivedCount > 0 && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setShowArchived((v) => !v)}
              >
                {showArchived
                  ? 'Hide archived'
                  : `Show archived (${archivedCount})`}
              </Button>
            )}
            <Button variant="primary" size="sm" onClick={handleAddMedication}>
              <Plus className="mr-1 h-4 w-4" /> Add
            </Button>
          </div>
        </div>
        {visibleMedications.length === 0 ? (
          <EmptyState
            icon={<Pill className="h-8 w-8" />}
            title="No medications yet"
            description="Add a medication to start tracking it on the daily slot grid."
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {visibleMedications.map((med) => (
              <li
                key={med.id}
                className={`flex items-start justify-between gap-3 rounded-lg border p-3 ${
                  med.is_archived
                    ? 'border-dashed border-[var(--color-border)] bg-[var(--color-bg-secondary)]/50 opacity-70'
                    : 'border-[var(--color-border)] bg-[var(--color-bg-card)]'
                }`}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-[var(--color-text-primary)]">
                      {med.display_label ?? med.name}
                    </span>
                    {med.display_label && med.display_label !== med.name && (
                      <span className="text-xs text-[var(--color-text-secondary)]">
                        ({med.name})
                      </span>
                    )}
                    {med.is_archived && (
                      <span className="rounded bg-[var(--color-bg-secondary)] px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]">
                        Archived
                      </span>
                    )}
                  </div>
                  <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                    {summariseSchedule(med)}
                  </p>
                  {med.notes && (
                    <p className="mt-1 text-xs text-[var(--color-text-secondary)] line-clamp-2">
                      {med.notes}
                    </p>
                  )}
                </div>
                <div className="flex items-center gap-1.5">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleEditMedication(med)}
                    aria-label={`Edit ${med.name}`}
                    title="Edit"
                  >
                    <Pencil className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleArchiveToggle(med)}
                    aria-label={
                      med.is_archived
                        ? `Restore ${med.name}`
                        : `Archive ${med.name}`
                    }
                    title={med.is_archived ? 'Restore' : 'Archive'}
                  >
                    {med.is_archived ? (
                      <ArchiveRestore className="h-4 w-4" />
                    ) : (
                      <Archive className="h-4 w-4" />
                    )}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <MedicationEditorDialog
        isOpen={editorOpen}
        uid={getFirebaseUid()}
        initial={editingMed}
        onClose={() => setEditorOpen(false)}
        onSaved={handleEditorSaved}
      />
    </div>
  );
}

function summariseSchedule(med: MedicationDoc): string {
  switch (med.schedule_mode) {
    case 'TIMES_OF_DAY':
      return med.times_of_day
        ? `Times of day: ${med.times_of_day.split(',').join(', ')}`
        : 'Times of day';
    case 'SPECIFIC_TIMES':
      return med.specific_times
        ? `Specific times: ${med.specific_times.split(',').join(', ')}`
        : 'Specific times';
    case 'INTERVAL': {
      if (med.interval_millis === null) return 'Interval';
      const hours = med.interval_millis / (60 * 60 * 1000);
      const rounded = Math.round(hours * 10) / 10;
      return `Every ${rounded} hr`;
    }
    case 'AS_NEEDED':
      return 'As needed (PRN)';
    default:
      return med.schedule_mode;
  }
}

/**
 * Adds right-click (desktop) + touch-and-hold (mobile) handlers without
 * disturbing the underlying tier-picker buttons' click semantics. The
 * handlers fire on the wrapper, not the buttons, so a regular tap on a
 * tier still selects that tier — only sustained interaction opens the
 * time-edit modal.
 */
function TierPickerWithLongPress({
  onLongPress,
  children,
}: {
  onLongPress: () => void;
  children: React.ReactNode;
}) {
  // Touch-and-hold detection — 500 ms threshold, mirrors Android's
  // default long-press window. Held in a ref so re-renders don't
  // reset the pending timer mid-press.
  const touchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const handleTouchStart = () => {
    touchTimer.current = setTimeout(() => {
      onLongPress();
      touchTimer.current = null;
    }, 500);
  };
  const handleTouchEnd = () => {
    if (touchTimer.current !== null) {
      clearTimeout(touchTimer.current);
      touchTimer.current = null;
    }
  };
  return (
    <div
      onContextMenu={(e) => {
        e.preventDefault();
        onLongPress();
      }}
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
      onTouchMove={handleTouchEnd}
      onTouchCancel={handleTouchEnd}
    >
      {children}
    </div>
  );
}
