# Parity Batch 6 — Cross-cutting sync hardening (Phase 1 audit)

**Date:** 2026-05-13
**Trigger:** Batch 6 of `docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md` — A.2 LWW timestamp guards + A.5b extended settings sync.
**Baseline:** origin/main at `fe0a057a` (post #1343 batch-2 wellness audit doc).

---

## STOP-and-report findings (surfaced before fan-out)

1. **`useFirestoreSync.ts` is broken on origin/main.** PR #1340
   ("startOfDayHour Firestore sync") left a missing `)` after
   `subscribeToStartOfDayHour` inside `useFirestoreSync` plus a
   duplicate `it('subscribes to all 8 entity types', () => {` /
   `it('subscribes to all 11 entity types', () => {` line inside
   `useFirestoreSync.test.tsx`. Both files fail `tsc -p
   tsconfig.app.json --noEmit` — `web/src/hooks/useFirestoreSync.ts:60`
   TS1135 and `useFirestoreSync.test.tsx:540` TS1005. Every web PR
   merged after #1340 would have failed CI on these errors; the only
   reason `e425ea2e..fe0a057a` is green is that PRs #1342 + #1343 are
   docs-only and don't touch web. **PR-0 below is a mandatory hotfix
   before any Batch 6 PR is mergeable.** The operator's hint about
   "Resolve CHANGELOG / useFirestoreSync.ts conflicts with keep-both
   pattern" refers to this state.

2. **Tags entity has no `updatedAt` on either side.** Per the audit
   doc's own STOP trigger ("if Android's `updatedAt` field on an entity
   is missing entirely (LWW can't guard a non-existent field)"), tags
   is exempt from A.2. Verified at
   `app/.../data/local/entity/TagEntity.kt:12-18` (fields: `id`,
   `cloudId`, `name`, `color`, `createdAt`) and
   `SyncMapper.tagToMap:366-371` (no `updatedAt` field written or
   read). Web `tags.ts` mirrors this — only `createdAt` is persisted.
   **Reclassified ACCEPT-AS-DIVERGENCE for A.2-tags.** Cross-device tag
   write conflicts collapse to last-write-wins-at-Firestore-merge
   regardless, which is acceptable for tag rename/recolor: idempotent,
   user can re-apply if clobbered.

3. **Several modules don't need LWW guards by shape.**
   - `taskCompletions.ts`: deterministic `${taskCloudId}__${date}` doc
     ids + idempotent merge upserts. No mutation paths — the only
     update path (`recordTaskCompletion`) is value-replace, and history
     rows don't track an `updatedAt`. **NOT-APPLICABLE.**
   - `focusReleaseLogs.ts`: append-only `addDoc`; no `updatedAt` field.
     **NOT-APPLICABLE.**
   - `medications.ts`: read-only on web. **NOT-APPLICABLE.**
   - `medicationPreferences.ts` /`aiPreferences.ts` /
     `taskBehaviorPreferences.ts`: bespoke per-doc `__pref_updated_at`
     metadata already drives PreferenceSync conflict resolution at
     Android's pull side. Not in scope for A.2 (settings, not entity
     writes).

4. **Theme sync path diverges from the audit doc's claim.** The audit
   doc speculates A.5b themeStore lives at
   `users/{uid}/prefs/theme_prefs`. Wrong — Android explicitly
   excludes `theme_prefs` from `PreferenceSyncModule.kt:38-42`
   ("`theme_prefs` / `sort_prefs` — covered by dedicated sync services
   that do extra work"). The actual Android theme sync target is
   `users/{uid}/settings/theme_preferences` via
   `ThemePreferencesSyncService.kt:153`, with a flat field shape
   (`prism_theme`, `theme_mode`, `font_scale`, `accent_color`,
   `priority_color_*`, `recent_custom_colors`, `updated_at`) — **not**
   the `__pref_types` / `__pref_updated_at` envelope used by generic
   sync. PR-7 below mirrors `ThemePreferencesSyncService` shape, not
   `aiPreferences.ts` shape.

---

## A.2 enumeration — every `web/src/api/firestore/*.ts` write helper

| File | Has `updatedAt`? | Mutating write paths | LWW-applicable? |
|------|------------------|----------------------|-----------------|
| `tasks.ts` | yes (`Date.now()`) | `createTask`, `updateTask`, `setTagsForTask` | YES — `updateTask` + `setTagsForTask` (create races are first-write-wins by doc id, no stale-clobber risk) |
| `habits.ts` | yes (`Date.now()`) | `createHabit`, `updateHabit`, `toggleCompletion` | YES — `updateHabit` (toggleCompletion is canonical-id natural-key write, idempotent) |
| `projects.ts` | yes | `createProject`, `updateProject` | YES — `updateProject` |
| `tags.ts` | **no** | `createTag`, `updateTag` | **NO** — Android entity has no `updatedAt`. ACCEPT-AS-DIVERGENCE per STOP-and-report #2 |
| `medicationSlots.ts` (slot defs) | yes | `createSlotDef`, `updateSlotDef` | YES — `updateSlotDef` |
| `medicationSlots.ts` (tier states) | yes | `setTierState`, `setTierStateIntendedTime`, `setTierStatesAtomic` | PARTIAL — `setTierState` is canonical-id idempotent merge; LWW value is questionable (multi-device same-day toggles intentionally collapse). Skip for v1, revisit if cross-device clobber bug reported |
| `medications.ts` | n/a | none (read-only) | NOT-APPLICABLE |
| `medicationPreferences.ts` | yes (`__pref_updated_at`) | `setPreferences` | NOT-APPLICABLE — settings doc, handled at PreferenceSync layer |
| `checkInLogs.ts` | yes | `setCheckIn` | YES |
| `moodEnergyLogs.ts` | yes | `createLog`, `updateLog` | YES — `updateLog` (createLog is canonical-id natural-key, idempotent) |
| `focusReleaseLogs.ts` | **no** | `createLog` only (append-only) | NOT-APPLICABLE |
| `boundaryRules.ts` | yes | `createRule`, `updateRule` | YES — `updateRule` |
| `taskCompletions.ts` | n/a | `recordTaskCompletion` (idempotent merge by natural key) | NOT-APPLICABLE |
| `taskDependencies.ts` / `projectPhases.ts` / `projectRisks.ts` / `externalAnchors.ts` | already use Android `updatedAt` shape — verify in PR-3 if scope grows | n/a (out of Batch 6 scope per audit doc) |
| `userTemplates.ts` | n/a — REST-backed, not Firestore-direct | n/a | NOT-APPLICABLE |

**Net A.2 PR fan-out (revised vs audit doc):** 4 PRs instead of 6.
- PR-1: LWW helper + tasks (`updateTask`, `setTagsForTask`)
- PR-2: habits (`updateHabit`)
- PR-3: projects (`updateProject`)
- PR-4: medicationSlots (`updateSlotDef` only — tier-state writes already idempotent merges)
- PR-5: checkInLogs (`setCheckIn`) + moodEnergyLogs (`updateLog`) + boundaryRules (`updateRule`)

PR-6 from the original fan-out dissolves (taskCompletions are idempotent and have no `updatedAt`).

### LWW helper shape

`web/src/api/firestore/lww.ts` exports:

```ts
export async function lwwUpdate(
  ref: DocumentReference,
  patch: Record<string, unknown> & { updatedAt: number },
): Promise<{ applied: boolean; reason?: 'stale' | 'missing' }>;
```

Pattern (matches audit doc's `runTransaction`):
1. `runTransaction(firestore, async (tx) => { const snap = await tx.get(ref); ... })`.
2. If doc missing → write (first-create wins).
3. Else read `remote.updatedAt`. If `remote.updatedAt > patch.updatedAt`, abort and return `{ applied: false, reason: 'stale' }`. Else `tx.update(ref, patch)` and return `{ applied: true }`.
4. Caller maps `{ applied: false }` to a no-op + structured log (`console.warn('[lww] aborted stale write for …')`), not a thrown error — UI shouldn't show a banner for an out-of-order push the listener will reconcile anyway.

**Cost note:** This adds a read-before-write on every guarded mutation. Per audit doc caution: only `updateTask` is in the hot path (every task edit); the rest fire on user-driven settings/log writes. Hot-path read amplification is bounded — Firestore caches reads at the SDK level and `updateTask` is already debounced by the React effect that calls it. Acceptable for v1; revisit if Firestore read quota becomes a constraint.

---

## A.5b enumeration — Android PreferenceSyncModule → web store mapping

Source of truth: `app/.../di/PreferenceSyncModule.kt`. Listed Firestore doc paths follow the canonical `users/{uid}/prefs/{firestoreDocName}` shape unless flagged bespoke.

| Android docName | Source DataStore | Web mirror today | A.5b PR? |
|------------------|------------------|------------------|----------|
| `a11y_prefs` | `a11yDataStore` | `a11yStore.ts` (localStorage only) | **PR-8** |
| `advanced_tuning_prefs` | `advancedTuningDataStore` | none | Out of scope — Android-only NL/heuristic tuning |
| `archive_prefs` | `archiveDataStore` | none | Out of scope — Android-only |
| `coaching_prefs` | `coachingDataStore` | none | Out of scope — Android-only |
| `daily_essentials_prefs` | `dailyEssentialsDataStore` | none | Defer to Batch 5 (medication) |
| `dashboard_prefs` | `dashboardDataStore` | none — no web dashboardStore | **PR-9** (settings sync only, UI is C.1f) |
| `gcal_sync_prefs` (excludes `gcal_last_sync_timestamp`) | `calendarSyncDataStore` | none | Out of scope — Calendar sync wired Android-only per audit doc § Integrations |
| `habit_list_prefs` | `habitListDataStore` | none | Out of scope — Android-only sort knobs |
| `leisure_budget_prefs` | `leisureBudgetDataStore` | none | Batch 4 (leisure port) |
| `medication_prefs` | `medicationDataStore` | `medicationPreferencesStore` (already syncs `medicationPreferences.ts`) | Already covered |
| `morning_checkin_prefs` | `morningCheckInDataStore` | none | Defer — Batch 2 wellness scope |
| `nd_prefs` | `ndPrefsDataStore` | none | Batch 2 wellness scope (C.7a) |
| `notification_prefs` | `notificationDataStore` | none | Out of scope — push deferred to web Phase G |
| `onboarding_prefs` | `onboardingDataStore` | `onboardingStore` | Already covered by PR #844 |
| `tab_prefs` | `tabDataStore` | none | Out of scope — Android-specific bottom nav |
| `task_behavior_prefs` | `taskBehaviorDataStore` | `taskBehaviorPreferences.ts` | Already covered by A.5a (#1340) |
| `template_prefs` | `templateDataStore` | none | Out of scope — Android-side template UI knobs |
| `timer_prefs` | `timerDataStore` | none | Out of scope — Pomodoro Android-side |
| `user_prefs` | `userPrefsDataStore` | `aiPreferences.ts` (`ai_features_enabled` only) | Partial — broader user_prefs port out of scope this batch |
| `voice_prefs` | `voiceDataStore` | none | Out of scope — voice-Android-only |
| **bespoke**: `theme_preferences` (NOT a generic spec) | `themePrefsDataStore` | `themeStore.ts` (localStorage only) | **PR-7** — mirrors `ThemePreferencesSyncService` shape, not generic sync envelope |

**Net A.5b PR fan-out:** 3 PRs as originally planned (themeStore / a11yStore / dashboardPreferences), with PR-7 using the bespoke `users/{uid}/settings/theme_preferences` doc path instead of the generic prefs subcollection.

### Per-PR field detail

**PR-7 themeStore →** `users/{uid}/settings/theme_preferences`. Fields synced from web side: `prism_theme` (string — but web today only stores a `themeKey` from the 4-theme palette; map web `themeKey` → Android `prism_theme` enum names; the four themes — `VOID`, `CYBERPUNK`, etc. — already line up by design). `font_scale` (float). Web doesn't expose `accent_color` / `priority_color_*` / `theme_mode` directly — leave those untouched on remote so Android's per-color overrides survive. Web pull side: read `prism_theme` (map back to `ThemeKey`) and `font_scale`. Skip the priority/accent overrides on pull too — web theme palette is canonical.

**PR-8 a11yStore →** `users/{uid}/prefs/a11y_prefs` (generic shape). Fields: `reduce_motion` (bool), `high_contrast` (bool), `large_touch_targets` (bool — Android-only field, web ignores on pull). Web `fontScale` lives in `themeStore` per parity with Android (Android stores `font_scale` in `theme_prefs`, not `a11y_prefs`) — so PR-7 owns fontScale, PR-8 owns the two booleans web already has. Pattern mirrors `aiPreferences.ts` with `__pref_types: { reduce_motion: 'bool', high_contrast: 'bool' }` and `__pref_updated_at`.

**PR-9 dashboardPreferences →** `users/{uid}/prefs/dashboard_prefs`. No web store yet — create the minimal `web/src/api/firestore/dashboardPreferences.ts` mirror so that when C.1f lands the data layer is ready and round-trips with Android. Fields: `section_order` (string CSV), `hidden_sections` (stringSet), `progress_style` (string), `collapsed_sections` (stringSet). Generic envelope. No web UI consumer this PR — pure sync stub validated by unit test only.

---

## Phase 2 PR fan-out plan

Per `audit-first` skill + repo's 500-line audit cap, this Phase 1 is strategy + inventory only. Phase 2 PR list:

| PR | Branch | Scope | LOC est. |
|----|--------|-------|----------|
| PR-0 | `fix/web-firestore-sync-syntax` | Hotfix `useFirestoreSync.ts` + test file syntax — UNBLOCK MERGE PIPELINE | ~10 |
| PR-1 | `feat/web-lww-tasks` | `lww.ts` helper + apply to `updateTask` + `setTagsForTask` + tests | ~300 |
| PR-2 | `feat/web-lww-habits` | Apply LWW to `updateHabit` + tests | ~120 |
| PR-3 | `feat/web-lww-projects` | Apply LWW to `updateProject` + tests | ~100 |
| PR-4 | `feat/web-lww-medication-slots` | Apply LWW to `updateSlotDef` + tests | ~100 |
| PR-5 | `feat/web-lww-wellness` | Apply LWW to `setCheckIn` + `updateLog` (mood) + `updateRule` (boundary) + tests | ~250 |
| PR-7 | `feat/web-settings-sync-theme` | Bespoke theme sync at `users/{uid}/settings/theme_preferences` + tests | ~200 |
| PR-8 | `feat/web-settings-sync-a11y` | Generic-shape `a11y_prefs` sync + tests | ~200 |
| PR-9 | `feat/web-settings-sync-dashboard` | `dashboard_prefs` Firestore stub + tests (no UI consumer this PR) | ~150 |

Total: ~1400 LOC across 9 PRs. PR-6 (taskCompletions) dropped per STOP-finding #3.

Per CLAUDE.md repo rules: each PR ships against own branch with squash merge; `--auto` is unavailable so use `gh pr merge <num> --squash` direct; resolve `useFirestoreSync.ts` + CHANGELOG conflicts with keep-both pattern.

---

## Risks called out for review

- **Read amplification on `updateTask`.** Hot path; if Firestore read quota in production becomes a concern, fall back to gating LWW on the much-smaller mutation paths (`setTagsForTask`, status flips) and let plain field-name-only edits ride bare `updateDoc`. Phase 3 will record post-merge read deltas if observable.
- **Theme migration drift.** Web's `migrateLegacyAccentToThemeKey` (`themeStore.ts:25-36`) runs on first load; PR-7 must not pull from Firestore *before* migration completes or a freshly-migrated user can have their migrated key overwritten by a stale Android value. Order in `authStore` hydration: `useThemeStore.applyTheme()` (which triggers migration if needed) → then mount subscriber.
- **DashboardPreferences UI consumer absent.** PR-9 ships a sync mirror with no consumer — verified-by-test only. Risk: feature drift between PR-9 and C.1f (Phase 2 batch wellness). Mitigate by referencing this audit from the C.1f scope when it lands.

---

## Phase 3 — Bundle summary (post-merge)

All nine PRs shipped against `main` in a single session, sequenced by dependency (hotfix → LWW helper + tasks → habits/projects/medication-slots/wellness in parallel-eligible order → settings sync). Per CLAUDE.md repo rules each shipped as its own squashed PR; no `--auto` (repo doesn't have it enabled, use direct `gh pr merge --squash`).

| PR | Branch | Title | Merge state |
|----|--------|-------|-------------|
| #1349 | `fix/web-firestore-sync-syntax` | hotfix `useFirestoreSync` + test syntax (unblock Web CI) | merged |
| #1353 | `feat/web-lww-tasks` | LWW guard helper + apply to `updateTask` + `setTagsForTask` | merged |
| #1357 | `feat/web-lww-habits` | LWW guard on `updateHabit` | merged |
| #1360 | `feat/web-lww-projects` | LWW guard on `updateProject` (+ first `projects.test.ts`) | merged |
| #1363 | `feat/web-lww-medication-slots` | LWW guard on `updateSlotDef` | merged |
| #1366 | `feat/web-lww-wellness` | LWW guard on `setCheckIn` / `updateLog` (mood) / `updateRule` (boundary) | merged |
| #1368 | `feat/web-settings-sync-theme` | bespoke `users/{uid}/settings/theme_preferences` sync | merged |
| #1370 | `feat/web-settings-sync-a11y` | generic `users/{uid}/prefs/a11y_prefs` sync | merged |
| #1371 | `feat/web-settings-sync-dashboard` | generic `users/{uid}/prefs/dashboard_prefs` sync (no UI yet) | merged |

### What shipped vs what the original 9-PR plan looked like

| Plan | Result | Notes |
|------|--------|-------|
| PR-1: tasks LWW | ✓ shipped | First LWW helper landed here. |
| PR-2: habits LWW | ✓ shipped | |
| PR-3: projects + tags LWW | partial — projects only | **tags dropped per STOP-and-report #2**: `TagEntity` has no `updatedAt` on either side, so LWW has nothing to compare. Reclassified ACCEPT-AS-DIVERGENCE. |
| PR-4: medication_slots + medications LWW | slot defs only | `medications.ts` is read-only; `medication_tier_states` writes are intentional canonical-id idempotent merges (LWW would defeat the deliberate-collapse semantics). |
| PR-5: checkInLogs + moodEnergyLogs + focusReleaseLogs + boundaryRules | 3 of 4 — focusReleaseLogs dropped | `focusReleaseLogs.ts` is append-only (`addDoc`, no `updatedAt`, no mutation). Nothing to guard. |
| PR-6: task-completions + habit-completion LWW | dropped entirely | Both use canonical-id natural-key `setDoc(merge)` writes that are deliberately idempotent. STOP-and-report #3. |
| PR-7: themeStore Firestore sync | ✓ shipped, bespoke path | Audit doc's `users/{uid}/prefs/theme_prefs` claim was wrong: Android excludes `theme_prefs` from `PreferenceSyncModule` because the bespoke `ThemePreferencesSyncService` writes to `users/{uid}/settings/theme_preferences`. STOP-and-report #4. |
| PR-8: a11yStore Firestore sync | ✓ shipped | `fontScale` carved out — Android stores it in `theme_prefs.font_scale`, not `a11y_prefs`, so PR-7 owns it. |
| PR-9: dashboardPreferences Firestore sync | ✓ shipped (foundation) | No UI consumer yet — that's C.1f in the Batch 2 wellness audit. Sync mirror in place so the data layer is ready. |

### Net result: A.2 closes

| Entity | LWW state | Reason if not guarded |
|--------|-----------|-----------------------|
| `tasks` | guarded | |
| `habits` | guarded | `updateHabit` only; `toggleCompletion` is canonical-id idempotent. |
| `projects` | guarded | |
| `tags` | **divergence** | `TagEntity` has no `updatedAt` on Android. ACCEPT-AS-DIVERGENCE. |
| `medication_slot_defs` | guarded | |
| `medication_tier_states` | not guarded | Canonical-id idempotent collapse is the intentional semantics. |
| `medications` | n/a | Web read-only. |
| `check_in_logs` | guarded | |
| `mood_energy_logs` | guarded | `updateLog` only; `createLog` is canonical-id idempotent. |
| `focus_release_logs` | n/a | Append-only via `addDoc`; no `updatedAt`. |
| `boundary_rules` | guarded | |
| `task_completions` | n/a | Canonical-id idempotent merge. |
| `habit_completions` | n/a | Canonical-id idempotent merge. |

### Net result: A.5b closes

| Web store | Firestore mirror | Path |
|-----------|------------------|------|
| `themeStore` | `web/src/api/firestore/themePreferences.ts` | `users/{uid}/settings/theme_preferences` (bespoke) |
| `a11yStore` (reduceMotion, highContrast) | `web/src/api/firestore/a11yPreferences.ts` | `users/{uid}/prefs/a11y_prefs` (generic envelope) |
| `a11yStore.fontScale` | covered by `themeStore` write path | `users/{uid}/settings/theme_preferences.font_scale` |
| dashboardPreferences (no store yet, mirror ready) | `web/src/api/firestore/dashboardPreferences.ts` | `users/{uid}/prefs/dashboard_prefs` (generic envelope) |

### Post-merge observations

- Web CI is GREEN for every PR after #1349. Confirmed by `gh run list --workflow=web-ci.yml`.
- `lww.ts` is now imported from 6 modules (`tasks`, `habits`, `projects`, `medicationSlots`, `checkInLogs`, `moodEnergyLogs`, `boundaryRules`).
- `themeStore` + `a11yStore` carry `loadFromFirestore` + `subscribeToFirestore` with self-echo suppression and best-effort error swallowing matching `aiPreferences.ts` precedent.
- `authStore.signInWithGoogle` + `initFirebaseAuthListener` now hydrate four cross-device-synced settings (AI, start-of-day-hour, theme, a11y) on every Firebase auth event.
- `useFirestoreSync` mounts 15 live listeners (vs 13 pre-batch): existing 13 + theme + a11y.
- Read-amplification risk on `updateTask` is bounded: the LWW transaction adds one read per mutating edit. With Firestore's SDK-level caching and the React effect's coalescing, this is sub-quota even on a heavy-editing user. No production deltas observable yet — flag in the Batch 6 audit's Risks section as a watch-item.

---

## Phase 4 — Claude Chat handoff block

```markdown
# Batch 6 — Cross-cutting sync hardening — DONE

Closing parity audit Section A items A.2 (LWW timestamp guards) + A.5b (extended settings sync).

## What shipped (9 PRs to main)

1. **#1349** `fix(web/sync)` — Hotfix `useFirestoreSync.ts` + test syntax broken by PR #1340's botched merge. Unblocked Web CI which had been red on every web PR since 2026-05-13.
2. **#1353** `feat(web/sync)` — `web/src/api/firestore/lww.ts` LWW helper + apply to `updateTask` + `setTagsForTask`. Transactional read-then-write with first-create-wins / equality-wins / strict-greater-aborts.
3. **#1357** LWW on `updateHabit`.
4. **#1360** LWW on `updateProject` + first `projects.test.ts`.
5. **#1363** LWW on `updateSlotDef` (medication slot definitions).
6. **#1366** LWW on `setCheckIn`, `updateLog` (mood), `updateRule` (boundaries).
7. **#1368** Theme Firestore sync at `users/{uid}/settings/theme_preferences` (bespoke path mirroring `ThemePreferencesSyncService`).
8. **#1370** A11y Firestore sync at `users/{uid}/prefs/a11y_prefs` (generic envelope).
9. **#1371** Dashboard prefs Firestore mirror at `users/{uid}/prefs/dashboard_prefs` (foundation only — no UI consumer yet; that's C.1f).

## What got divergence-classified instead of guarded

- **Tags LWW (A.2-tags)**: `TagEntity` has no `updatedAt` on Android. Reclassified ACCEPT-AS-DIVERGENCE in the audit doc.
- **Append-only / canonical-id-idempotent writes**: `focus_release_logs`, `task_completions`, `habit_completions`, `medication_tier_states`. Their merge-on-natural-key behaviour is the deliberate semantics — LWW would defeat the collapse.

## What surprised us

- **Web CI was silently red on main** since PR #1340 (2026-05-13). Two TS-fatal syntax errors in `useFirestoreSync.ts` + the matching test file made every web-touching PR's build fail, but docs-only PRs short-circuited out of the relevance filter and merged green. Fixed in PR #1349.
- **Audit doc was wrong about theme sync path**: `users/{uid}/prefs/theme_prefs` is explicitly EXCLUDED from `PreferenceSyncModule` on Android because `ThemePreferencesSyncService` is bespoke and writes to `users/{uid}/settings/theme_preferences`. PR #1368 mirrors the bespoke service shape, not the generic envelope.

## Watch-items / risk register

- **`updateTask` read amplification**: each call now does a read-before-write inside `runTransaction`. Hot path. Monitor Firestore read quota; revisit if observable.
- **Dashboard prefs UI gap**: PR #1371 ships a sync mirror with no UI consumer. When C.1f (Today section-reorder UI in Batch 2 wellness) lands, the data layer is ready; reviewer should reference this audit during C.1f review.
- **Theme migration ordering**: web's `migrateLegacyAccentToThemeKey` must run synchronously before the Firestore subscriber mounts, or a freshly-migrated user could have their migrated key overwritten by a stale Android value. Verified in PR #1368.

## Phase 1 audit doc

`docs/audits/PARITY_BATCH_6_SYNC_HARDENING_AUDIT.md` carries the full Phase 1 inventory, STOP-and-report findings, Phase 3 bundle summary, and this Phase 4 handoff.

## Suggested follow-ups (not blocking)

- Batch 2 wellness PR for C.1f Today section reorder UI should consume the `dashboardPreferences.ts` mirror landed in #1371.
- A future preference-sync sweep could port the remaining Android-only DataStores listed as "Out of scope" in this audit (`advanced_tuning_prefs`, `archive_prefs`, `coaching_prefs`, `template_prefs`, `timer_prefs`, `voice_prefs`) if any of those surfaces gain a web UI.
- Consider a `lwwUpdateMany` consumer for the future B.6 task-completion path so the task-toggle + the `task_completions` row commit atomically.
```
