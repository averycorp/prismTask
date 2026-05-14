import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { addDays, format, parseISO } from 'date-fns';
import {
  Archive,
  ArchiveRestore,
  ChevronLeft,
  ChevronRight,
  Clock,
  ListChecks,
  Pencil,
  Pill,
  Plus,
  PlusCircle,
  Undo2,
} from 'lucide-react';
import { toast } from 'sonner';
import { dailyEssentialsApi } from '@/api/dailyEssentials';
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
  getAllMedications,
  unarchiveMedication,
  type MedicationDoc,
} from '@/api/firestore/medications';
import {
  deleteDose,
  getDosesForDay,
  logDose,
  medicationDoseId,
  type MedicationDoseDoc,
} from '@/api/firestore/medicationDoses';
import {
  deriveVirtualSlots,
  mergeVirtualWithMaterialized,
} from '@/features/medication/virtualSlots';
import { getFirebaseUid } from '@/stores/firebaseUid';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { MedicationSlotDetailModal } from '@/features/daily-essentials/MedicationSlotDetailModal';
import { BulkMarkDialog } from '@/features/medication/BulkMarkDialog';
import { MedicationEditorDialog } from '@/features/medication/MedicationEditorDialog';
import { MedicationTierPicker } from '@/features/medication/MedicationTierPicker';
import { MedicationTimeEditModal } from '@/features/medication/MedicationTimeEditModal';
import { isBacklogged } from '@/features/medication/backloggedHelpers';
import { useSettingsStore } from '@/stores/settingsStore';
import { useLogicalToday } from '@/utils/useLogicalToday';
import type {
  MedicationSlot,
  MedicationSlotCompletion,
} from '@/types/dailyEssentials';

const ANYTIME_KEY = 'anytime';

function displayTimeFor(slotKey: string): string {
  if (slotKey === ANYTIME_KEY) return 'Anytime';
  return slotKey;
}

function prettifyMedId(key: string): string {
  const idx = key.indexOf(':');
  const raw = idx >= 0 ? key.slice(idx + 1) : key;
  return raw
    .split(/[_\s]+/)
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ');
}

function rowToSlot(row: MedicationSlotCompletion): MedicationSlot {
  return {
    slotKey: row.slot_key,
    displayTime: displayTimeFor(row.slot_key),
    medLabels: row.med_ids.map(prettifyMedId),
    medIds: row.med_ids,
    takenAt: row.taken_at,
  };
}

/**
 * Dedicated Medication screen. Scoped to the same slot-toggle data path
 * the Today MedicationSlotList already uses — no tier picker, no slot
 * CRUD (both require backend work on top of the current
 * `/daily-essentials/slots` surface).
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
  const [rows, setRows] = useState<MedicationSlotCompletion[]>([]);
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
  // Per-medication dose toggles (parity Batch 5 PR-2). Keyed by Firestore
  // dose docId so a re-tap toggles the same doc rather than racing two
  // writes for the same (med, slot, day) triple.
  const [dosesByKey, setDosesByKey] = useState<
    Record<string, MedicationDoseDoc>
  >({});
  // Medication library (parity Batch 5 PR-1): full add / edit / archive
  // surface backed by `users/{uid}/medications` Firestore writes.
  const [medications, setMedications] = useState<MedicationDoc[]>([]);
  const [medsLoading, setMedsLoading] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingMed, setEditingMed] = useState<MedicationDoc | null>(null);
  const [showArchived, setShowArchived] = useState(false);

  const reloadMedications = useCallback(async () => {
    setMedsLoading(true);
    try {
      const uid = getFirebaseUid();
      const list = await getAllMedications(uid);
      setMedications(list);
    } catch (e) {
      toast.error((e as Error).message || 'Failed to load medications');
    } finally {
      setMedsLoading(false);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- mount-only data fetch
    reloadMedications();
  }, [reloadMedications]);

  const load = useCallback(
    async (iso: string) => {
      setLoading(true);
      try {
        const fetched = await dailyEssentialsApi.listSlots(iso);
        setRows(fetched);
        // Tier states live in Firestore, independent of backend slots.
        try {
          const uid = getFirebaseUid();
          const states = await getTierStatesForDate(uid, iso);
          const byKey: Record<string, MedicationTierState> = {};
          for (const s of states) byKey[s.slot_key] = s;
          setTierStates(byKey);
        } catch {
          setTierStates({});
        }
        // Per-medication doses for the day (PR-2). One read per (med, slot)
        // pair is wasteful; instead we union the medication cloud-ids from
        // the slot rows and pull each medication's doses for the day. The
        // result is keyed by the deterministic doc id so the toggle UI can
        // look up state in O(1).
        try {
          const uid = getFirebaseUid();
          const medCloudIds = new Set<string>();
          for (const row of fetched) {
            for (const raw of row.med_ids) {
              if (raw.startsWith('med:')) {
                medCloudIds.add(raw.slice('med:'.length));
              }
            }
          }
          const allDoses = await Promise.all(
            [...medCloudIds].map((medId) =>
              getDosesForDay(uid, medId, iso).catch(() => []),
            ),
          );
          const byDoseKey: Record<string, MedicationDoseDoc> = {};
          for (const list of allDoses) {
            for (const dose of list) {
              byDoseKey[dose.id] = dose;
            }
          }
          setDosesByKey(byDoseKey);
        } catch {
          setDosesByKey({});
        }
      } catch (e) {
        toast.error((e as Error).message || 'Failed to load slots');
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const isMedTakenInSlot = (
    slotKey: string,
    medCloudId: string,
  ): boolean => {
    const id = medicationDoseId(medCloudId, slotKey, dateIso);
    return dosesByKey[id] !== undefined;
  };

  const handleDoseToggle = async (slotKey: string, medCloudId: string) => {
    try {
      const uid = getFirebaseUid();
      const id = medicationDoseId(medCloudId, slotKey, dateIso);
      const existing = dosesByKey[id];
      if (existing !== undefined) {
        await deleteDose(uid, existing.id);
        setDosesByKey((prev) => {
          const next = { ...prev };
          delete next[id];
          return next;
        });
      } else {
        const dose = await logDose(uid, {
          medicationCloudId: medCloudId,
          slotKey,
          dateIso,
        });
        setDosesByKey((prev) => ({ ...prev, [dose.id]: dose }));
      }
    } catch (e) {
      toast.error((e as Error).message || 'Failed to toggle dose');
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data-fetch effect: load slots on mount and when date changes
    load(dateIso);
  }, [dateIso, load]);

  const slots = useMemo(() => {
    const materialized = rows.map(rowToSlot);
    const virtual = deriveVirtualSlots(medications);
    return mergeVirtualWithMaterialized(materialized, virtual);
  }, [rows, medications]);
  const takenCount = slots.filter((s) => s.takenAt !== null).length;

  const handleToggle = async (slot: MedicationSlot, taken: boolean) => {
    try {
      const updated = await dailyEssentialsApi.toggleSlot({
        date: dateIso,
        slot_key: slot.slotKey,
        med_ids: slot.medIds,
        taken,
      });
      setRows((prev) => {
        const next = prev.filter((r) => r.slot_key !== updated.slot_key);
        return [...next, updated];
      });
      setOpenSlot(null);
    } catch {
      toast.error('Failed to update slot');
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
      await reloadMedications();
    } catch (e) {
      toast.error((e as Error).message || 'Failed to update medication');
    }
  };

  const handleEditorSaved = async () => {
    await reloadMedications();
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
                          medCloudId !== null &&
                          isMedTakenInSlot(slot.slotKey, medCloudId);
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
        {medsLoading && medications.length === 0 ? (
          <p className="py-6 text-center text-sm text-[var(--color-text-secondary)]">
            Loading medications…
          </p>
        ) : visibleMedications.length === 0 ? (
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
