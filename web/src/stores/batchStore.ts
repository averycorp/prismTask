import { create } from 'zustand';
import { toast } from 'sonner';
import * as firestoreTasks from '@/api/firestore/tasks';
import * as firestoreHabits from '@/api/firestore/habits';
import * as firestoreProjects from '@/api/firestore/projects';
import * as firestoreMedications from '@/api/firestore/medications';
import { nlpBatchApi } from '@/api/nlpBatch';
import { webToAndroidPriority } from '@/api/firestore/converters';
import { getFirebaseUid } from '@/stores/firebaseUid';
import type {
  AmbiguousEntityHint,
  BatchHistoryRecord,
  BatchParseResponse,
  BatchUserContext,
  ForcedAmbiguousPhrase,
  ProposedMutation,
} from '@/types/batch';
import { applyMutation, undoEntry } from '@/features/batch/batchApplier';
import {
  matchMedicationsInCommand,
  type MatchResult,
} from '@/features/batch/medicationNameMatcher';
import {
  getBatchHistoryForUser,
  migrateFromLocalStorageIfNeeded,
  putBatchHistoryRecord,
  replaceBatchHistoryForUser,
} from '@/lib/idb/batchHistoryStore';

/** Audit failure mode #2 firewall: MEDICATION mutations from Haiku get
 *  auto-stripped below this confidence floor unless the deterministic
 *  matcher already committed the entity_id. TASK / HABIT / PROJECT mutations
 *  stay regardless — wrong-day scheduling is recoverable, wrong-medication
 *  is not. Mirrors `BatchPreviewViewModel.MEDICATION_CONFIDENCE_FLOOR`. */
const MEDICATION_CONFIDENCE_FLOOR = 0.85;

/** 24h to match Android's `UNDO_WINDOW_MILLIS` — the quick Snackbar is
 *  the primary surface, but Settings → Batch History stays usable within
 *  the same window. */
const UNDO_WINDOW_MS = 24 * 60 * 60 * 1000;

/** How many past batches to keep in localStorage. Older batches fall off
 *  the list even if still inside UNDO_WINDOW_MS — matches the "recent"
 *  framing on Android's BatchHistoryScreen. */
const MAX_HISTORY_ENTRIES = 25;

/**
 * D8 Item 3 — batch history persistence migrated from localStorage to
 * IndexedDB. The store wrapper is the IDB framework's first consumer (see
 * `web/src/lib/idb/`). Read/write are async; the store keeps its
 * synchronous in-memory `history` field as the read source for components
 * and writes to IDB on commit / undo / purge.
 *
 * On first hydrate per uid, any legacy localStorage payload is migrated
 * into IDB and the legacy key removed; subsequent hydrates read straight
 * from IDB.
 */

function randomBatchId(): string {
  // crypto.randomUUID isn't universally available; fall back to a
  // simple random when missing. The ID is local-only.
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `batch_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

function expandMatchResult(result: MatchResult): {
  committed: Record<string, string>;
  forced: ForcedAmbiguousPhrase[];
} {
  switch (result.kind) {
    case 'no_match':
      return { committed: {}, forced: [] };
    case 'unambiguous':
      return { committed: { ...result.matches }, forced: [] };
    case 'ambiguous':
      return {
        committed: {},
        forced: result.phrases.map((p) => ({
          phrase: p.phrase,
          candidate_entity_type: 'MEDICATION',
          candidate_entity_ids: p.candidate_entity_ids,
        })),
      };
    case 'mixed':
      return {
        committed: { ...result.unambiguous },
        forced: result.ambiguous.map((p) => ({
          phrase: p.phrase,
          candidate_entity_type: 'MEDICATION',
          candidate_entity_ids: p.candidate_entity_ids,
        })),
      };
  }
}

/** Apply the auto-strip + low-confidence safeguards on top of a Haiku
 *  response. Mirrors `BatchPreviewViewModel.loadPreview` on Android: any
 *  mutation whose entity_id appears in `committedIds` is exempt from both
 *  guards because the deterministic matcher has already proven its
 *  correctness. The `strippedMutations` come back so the picker can recover
 *  the original mutation shape when the user picks a candidate. */
function applyClientSafeguards(
  response: BatchParseResponse,
  committedIds: Set<string>,
): { response: BatchParseResponse; stripped: StrippedMutation[] } {
  const ambiguousIds = new Set(
    response.ambiguous_entities.flatMap((h) => h.candidate_entity_ids),
  );
  const autoStripped: ProposedMutation[] = [];
  const afterStrip: ProposedMutation[] = [];
  for (const m of response.mutations) {
    if (ambiguousIds.has(m.entity_id) && !committedIds.has(m.entity_id)) {
      autoStripped.push(m);
    } else {
      afterStrip.push(m);
    }
  }
  const lowConfStripped: ProposedMutation[] = [];
  const keptMutations: ProposedMutation[] = [];
  for (const m of afterStrip) {
    if (
      m.entity_type === 'MEDICATION' &&
      response.confidence < MEDICATION_CONFIDENCE_FLOOR &&
      !committedIds.has(m.entity_id)
    ) {
      lowConfStripped.push(m);
    } else {
      keptMutations.push(m);
    }
  }
  const stripped = [...autoStripped, ...lowConfStripped];
  const augmented: AmbiguousEntityHint[] = [...response.ambiguous_entities];
  const seen = new Set(
    augmented.map(
      (h) => `${h.phrase}::${[...h.candidate_entity_ids].sort().join(',')}`,
    ),
  );
  for (const m of lowConfStripped) {
    const phrase = m.human_readable_description || m.entity_id;
    const ids = [m.entity_id];
    const key = `${phrase}::${ids.join(',')}`;
    if (seen.has(key)) continue;
    seen.add(key);
    augmented.push({
      phrase,
      candidate_entity_type: 'MEDICATION',
      candidate_entity_ids: ids,
      note:
        "Couldn't confirm the medication for this command — pick below or rephrase.",
    });
  }
  return {
    response: {
      ...response,
      mutations: keptMutations,
      ambiguous_entities: augmented,
      stripped_ambiguous_count: stripped.length,
    },
    stripped,
  };
}

async function resolveMedicationCandidates(
  uid: string,
  ambiguousEntities: AmbiguousEntityHint[],
): Promise<Record<number, MedicationCandidateOption[]>> {
  const out: Record<number, MedicationCandidateOption[]> = {};
  for (let idx = 0; idx < ambiguousEntities.length; idx += 1) {
    const hint = ambiguousEntities[idx];
    if (hint.candidate_entity_type !== 'MEDICATION') continue;
    if (hint.candidate_entity_ids.length === 0) continue;
    try {
      const meds = await firestoreMedications.getMedicationsByIds(
        uid,
        hint.candidate_entity_ids,
      );
      if (meds.length === 0) continue;
      out[idx] = meds.map((m) => ({
        entity_id: m.id,
        name: m.name,
        display_label: m.display_label,
      }));
    } catch {
      // Skip this hint silently — the banner copy still surfaces it; the
      // picker just won't render for the failing fetch.
    }
  }
  return out;
}

async function buildUserContext(uid: string): Promise<BatchUserContext> {
  const [tasks, habits, projects, medications] = await Promise.all([
    firestoreTasks.getAllTasks(uid),
    firestoreHabits.getHabits(uid),
    firestoreProjects.getProjects(uid),
    firestoreMedications.getMedications(uid).catch(() => []),
  ]);

  const projectNameById = new Map(projects.map((p) => [p.id, p.title]));
  const today = new Date().toISOString().slice(0, 10);
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  return {
    today,
    timezone,
    tasks: tasks
      .filter((t) => !t.id || t.id.length > 0) // belt-and-suspenders
      .map((t) => ({
        id: t.id,
        title: t.title,
        due_date: t.due_date ?? null,
        scheduled_start_time: null,
        priority: t.priority != null ? webToAndroidPriority(t.priority) : 0,
        project_id: t.project_id || null,
        project_name: t.project_id ? projectNameById.get(t.project_id) || null : null,
        tags: [],
        life_category: null,
        is_completed: t.status === 'done',
      })),
    habits: habits.map((h) => ({
      id: h.id,
      name: h.name,
      is_archived: !h.is_active,
    })),
    projects: projects.map((p) => ({
      id: p.id,
      name: p.title,
      status: p.status,
    })),
    medications: medications.map((m) => ({
      id: m.id,
      name: m.name,
      display_label: m.display_label,
    })),
  };
}

/** Resolved local candidates for one ambiguous-MEDICATION hint, keyed by
 *  the hint's index in `pendingResponse.ambiguous_entities`. Populated
 *  alongside the parse response and consumed by the picker. */
export interface MedicationCandidateOption {
  entity_id: string;
  name: string;
  display_label: string | null;
}

/** Mutations the safeguards stripped before showing them to the user. We
 *  hold onto these so the picker can recover and substitute the picked
 *  entity_id when the user disambiguates a medication phrase. */
type StrippedMutation = ProposedMutation;

interface BatchStoreState {
  /** Current preview — set by QuickAddBar on batch detection, read by
   *  BatchPreviewScreen, cleared on commit or dismiss. */
  pendingCommand: string | null;
  pendingResponse: BatchParseResponse | null;
  /** Candidates per ambiguous-hint index (only MEDICATION-typed hints
   *  whose candidate ids resolve to live local rows are populated). */
  medicationCandidates: Record<number, MedicationCandidateOption[]>;
  /** Mutations the auto-strip / low-confidence safeguards removed. The
   *  picker recovers from this list when the user disambiguates a phrase. */
  strippedMutations: StrippedMutation[];
  isParsing: boolean;
  parseError: string | null;

  /** Persisted batch history for the current uid. Hydrated on
   *  `hydrate(uid)` — stays empty until then. */
  history: BatchHistoryRecord[];

  setPendingCommand: (commandText: string | null) => void;
  parsePendingCommand: () => Promise<void>;
  resolveAmbiguity: (hintIndex: number, pickedEntityId: string) => void;
  clearPending: () => void;

  hydrate: (uid: string) => Promise<void>;
  commit: (
    commandText: string,
    mutations: ProposedMutation[],
  ) => Promise<BatchHistoryRecord>;
  undo: (batchId: string) => Promise<number>;
  purgeExpired: () => void;
}

export const useBatchStore = create<BatchStoreState>((set, get) => ({
  pendingCommand: null,
  pendingResponse: null,
  medicationCandidates: {},
  strippedMutations: [],
  isParsing: false,
  parseError: null,
  history: [],

  setPendingCommand: (commandText) =>
    set({
      pendingCommand: commandText,
      pendingResponse: null,
      medicationCandidates: {},
      strippedMutations: [],
      parseError: null,
    }),

  parsePendingCommand: async () => {
    const commandText = get().pendingCommand;
    if (!commandText) return;
    set({
      isParsing: true,
      parseError: null,
      pendingResponse: null,
      medicationCandidates: {},
      strippedMutations: [],
    });
    try {
      const uid = getFirebaseUid();
      const userContext = await buildUserContext(uid);

      // Pre-resolver: run the deterministic local matcher and forward its
      // result to the backend as authoritative hints. NoMatch / empty
      // medication list reduces to a no-op so the wire-up stays safe even
      // before the web has a medications UI.
      const matchResult = matchMedicationsInCommand(
        commandText,
        userContext.medications.map((m) => ({
          id: m.id,
          name: m.name,
          display_label: m.display_label ?? null,
        })),
      );
      const { committed, forced } = expandMatchResult(matchResult);

      const enrichedContext: BatchUserContext = {
        ...userContext,
        committed_medication_matches: committed,
        forced_ambiguous_phrases: forced,
      };
      const response = await nlpBatchApi.parse({
        command_text: commandText,
        user_context: enrichedContext,
      });

      const { response: safeguardedResponse, stripped } = applyClientSafeguards(
        response,
        new Set(Object.values(committed)),
      );
      const candidates = await resolveMedicationCandidates(
        uid,
        safeguardedResponse.ambiguous_entities,
      );
      set({
        pendingResponse: safeguardedResponse,
        medicationCandidates: candidates,
        strippedMutations: stripped,
        isParsing: false,
      });
    } catch (e) {
      set({
        isParsing: false,
        parseError: (e as Error).message || 'Failed to parse batch command',
      });
    }
  },

  resolveAmbiguity: (hintIndex, pickedEntityId) => {
    const state = get();
    const response = state.pendingResponse;
    if (!response) return;
    const hint = response.ambiguous_entities[hintIndex];
    if (!hint) return;
    if (!hint.candidate_entity_ids.includes(pickedEntityId)) return;
    const candidateSet = new Set(hint.candidate_entity_ids);
    const recovered = state.strippedMutations.filter(
      (m) =>
        candidateSet.has(m.entity_id) &&
        m.entity_type === hint.candidate_entity_type,
    );
    if (recovered.length === 0) return;
    const resolved = recovered.map((m) => ({
      ...m,
      entity_id: pickedEntityId,
    }));
    const remainingStripped = state.strippedMutations.filter(
      (m) => !recovered.includes(m),
    );
    const remainingHints = response.ambiguous_entities.filter(
      (_, i) => i !== hintIndex,
    );
    const remainingStrippedCount = Math.max(
      (response.stripped_ambiguous_count ?? 0) - recovered.length,
      0,
    );
    const newCandidates: Record<number, MedicationCandidateOption[]> = {};
    for (const [k, v] of Object.entries(state.medicationCandidates)) {
      const oldIdx = Number(k);
      if (oldIdx === hintIndex) continue;
      const newIdx = oldIdx > hintIndex ? oldIdx - 1 : oldIdx;
      newCandidates[newIdx] = v;
    }
    set({
      pendingResponse: {
        ...response,
        mutations: [...response.mutations, ...resolved],
        ambiguous_entities: remainingHints,
        stripped_ambiguous_count: remainingStrippedCount,
      },
      strippedMutations: remainingStripped,
      medicationCandidates: newCandidates,
    });
  },

  clearPending: () =>
    set({
      pendingCommand: null,
      pendingResponse: null,
      medicationCandidates: {},
      strippedMutations: [],
      parseError: null,
    }),

  hydrate: async (uid) => {
    const migrated = await migrateFromLocalStorageIfNeeded(uid);
    const raw = migrated ?? (await getBatchHistoryForUser(uid));
    const history = raw.filter((r) => r.expires_at > Date.now());
    set({ history });
    if (history.length !== raw.length) {
      // Some entries were filtered out as expired — sync the IDB store
      // so it doesn't keep growing forever.
      await replaceBatchHistoryForUser(uid, history);
    } else if (migrated !== null) {
      // We just migrated from localStorage; the IDB store already has
      // the full payload, no follow-up write needed.
    }
  },

  commit: async (commandText, mutations) => {
    const uid = getFirebaseUid();
    const batchId = randomBatchId();
    const createdAt = Date.now();
    const expiresAt = createdAt + UNDO_WINDOW_MS;
    const entries = [];
    let applied = 0;
    let skipped = 0;

    for (const mutation of mutations) {
      const outcome = await applyMutation(uid, mutation);
      if (outcome.applied && outcome.entry) {
        entries.push(outcome.entry);
        applied += 1;
      } else {
        entries.push({
          entity_type: mutation.entity_type,
          entity_id: mutation.entity_id,
          mutation_type: mutation.mutation_type,
          pre_state: {},
          applied: false,
          skip_reason: outcome.reason,
        });
        skipped += 1;
      }
    }

    const record: BatchHistoryRecord = {
      batch_id: batchId,
      command_text: commandText,
      created_at: createdAt,
      expires_at: expiresAt,
      undone_at: null,
      entries,
      applied_count: applied,
      skipped_count: skipped,
    };

    const nextHistory = [record, ...get().history].slice(0, MAX_HISTORY_ENTRIES);
    set({ history: nextHistory });
    // Single record write is cheaper than a full replace; the cap
    // trim only removes the oldest entry which falls off naturally on
    // next hydrate (older records remain in IDB until purged on
    // expiry).
    await putBatchHistoryRecord(uid, record);
    return record;
  },

  undo: async (batchId) => {
    const uid = getFirebaseUid();
    const record = get().history.find((r) => r.batch_id === batchId);
    if (!record || record.undone_at != null) return 0;

    let restored = 0;
    // Reverse order so dependent mutations on the same entity roll back
    // in the reverse order they were applied — matches Android.
    for (let i = record.entries.length - 1; i >= 0; i -= 1) {
      const entry = record.entries[i];
      if (!entry.applied) continue;
      const ok = await undoEntry(uid, entry);
      if (ok) restored += 1;
    }

    const now = Date.now();
    const nextHistory = get().history.map((r) =>
      r.batch_id === batchId ? { ...r, undone_at: now } : r,
    );
    set({ history: nextHistory });
    const updated = nextHistory.find((r) => r.batch_id === batchId);
    if (updated) await putBatchHistoryRecord(uid, updated);

    if (restored > 0) {
      toast.success(`Undo complete — restored ${restored} change${restored === 1 ? '' : 's'}`);
    } else {
      toast.error('Nothing could be restored');
    }
    return restored;
  },

  purgeExpired: () => {
    const now = Date.now();
    const nextHistory = get().history.filter((r) => r.expires_at > now);
    if (nextHistory.length !== get().history.length) {
      let uid: string | null = null;
      try {
        uid = getFirebaseUid();
      } catch {
        // Not authed — leave history in memory.
        return;
      }
      set({ history: nextHistory });
      // Fire-and-forget; in-memory state is the read source so we
      // don't need to await.
      void replaceBatchHistoryForUser(uid, nextHistory);
    }
  },
}));
