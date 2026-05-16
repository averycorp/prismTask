# Phase 1 Audit — Version + Changelog + Documentation Sync

**Date:** 2026-04-24
**Branch:** `feature/version-doc-sync` (worktree at `../prismtask-doc-sync`, off `origin/main` @ `3c1e5717`)
**Auditor:** Claude (read-only pass; no source files modified outside this audit doc)

---

## §1.1 — Version reconciliation

### Sources

| Source                       | Version  | versionCode | Notes |
| ---------------------------- | -------- | ----------- | ----- |
| `app/build.gradle.kts` (HEAD)| **1.6.0**| **690**     | Bumped in commit `3c1e5717` ("PR4 of 4 + v1.6.0"), 2026-04-24 |
| Highest git tag              | v1.5.3   | n/a         | `v1.5.3` (also `v1.5.2`, `v1.5.0`, `v1.4.40`, then jumps back to `v1.3.62`) |
| Latest GitHub Release        | v1.5.3   | n/a         | Published 2026-04-24 01:15 UTC, marked "Latest" |
| **→ Canonical (highest)**    | **1.6.0**| **690**     | Selected from `build.gradle.kts` per spec (highest among sources) |

### Disagreement

Tag and Release both lag `build.gradle.kts` by one minor version. v1.6.0 has **landed in code and CHANGELOG but has not yet been tagged or released** on GitHub. v1.5.0 / v1.5.2 / v1.5.3 are tagged and released but are not represented in the CHANGELOG (see §1.3).

The prompt's stated "current state" (v1.3.110 / versionCode 632, Unreleased = v1.4.0) is several minor versions out of date — the auto-memory note that seeded that premise is stale. The audit proceeds against actual canonical v1.6.0.

---

## §1.2 — Hardcoded version-string sweep

Searched `app/src/`, `web/src/`, `backend/`, `docs/`, `store/`, root for plausible hardcoded version literals (`0.7.x`, `0.8.x`, `0.9.x`, `1.0.x` … `1.6.x`).

| File:line | Current value | Should be | Disposition |
| --- | --- | --- | --- |
| `app/src/test/java/com/averycorp/prismtask/feedback/BugReportModelTest.kt:25` | `appVersion = "1.3.2"` | (test fixture, no app behavior) | **No-op.** Unit-test fixture data. Replacing with a dynamic value would change nothing. |
| `app/src/main/java/com/averycorp/prismtask/data/export/DataExporter.kt:146-147,737` | `EXPORT_VERSION = 5` | (integer schema version) | **No-op.** Memory note about a hardcoded `"0.7.1"` here is **stale** — already fixed. The literal is now an integer schema version (`5`), distinct from app versionName. Persisted in JSON exports under `"version"` and `"schemaVersion"`. |
| `web/src/api/firestore/medicationSlots.ts` | `v1.5.3` (×2) | (KDoc reference to historical migration) | **No-op.** Documents the migration boundary in a comment. Removing would lose context. |
| `web/src/features/settings/SettingsScreen.tsx` | `v1.6.0` | (KDoc PR-track reference) | **No-op.** Same — historical reference. |
| `backend/app/main.py:11` | `API_VERSION = "0.2.0"` | (independent backend semver) | Static, syncable, single-source-of-truth. **Keep**. See §1.8. |
| `web/package.json:4` | `"version": "0.0.0"` | independent semver | **Bump candidate**. See §1.9. |
| `README.md:8` | `FastAPI-0.115` badge | (FastAPI library version) | Verify against `requirements.txt` in §2.4. |
| `README.md:9` | `PostgreSQL-16` badge | (DB version) | OK if backend really targets PG 16; otherwise correct. |
| `README.md:6` | `Kotlin-2.3.20` badge | (Kotlin compiler version) | Matches `app/build.gradle.kts`. OK. |

**Verdict:** No bug-class hardcoded app-version drift. The DataExporter "0.7.1" memory note is stale and can be retired. The only material item is the web `package.json` at `0.0.0`, which is a "never bumped" symptom rather than drift, and the surfacing of `API_VERSION` via the backend (a dedicated single source of truth, good).

---

## §1.3 — CHANGELOG audit

### Format

Declared as **Keep a Changelog** (link in header to `keepachangelog.com/en/1.1.0/`) with semver. **Format compliance is partial** — the document mixes three header styles:

| Style | Example | Where used |
| --- | --- | --- |
| Keep-a-Changelog canonical (bracketed, ISO date, em-dash) | `## [1.6.0] — 2026-04-24` | v1.6.0 only |
| Keep-a-Changelog canonical (bracketed, ISO date, hyphen) | `## [0.7.0] - 2026-04-06` | v0.1.0 – v0.7.0 |
| Custom narrative | `## v1.4.40 — AI Time Blocking: horizon selector + mandatory preview (April 2026)` | v1.0.0 – v1.4.40 |

The Keep-a-Changelog spec requires bracketed versions with an ISO date (em-dash or hyphen accepted). The narrative form breaks tooling that scrapes versions, and the omitted dates make release-history reconstruction awkward.

### Unreleased section content (verbatim categorization)

**Changed**
- BREAKING (web): Medication tier enum aligned with Android canonical model (lowercase 4-tier `skipped`/`essential`/`prescription`/`complete`, replacing pre-v1.5.3 uppercase `SKIPPED`/`PARTIAL`/`COMPLETE`). One-time normalization helper in `web/src/api/firestore/medicationSlots.ts`. Substantive — corresponds to commit `44149da4` (PR #762).

**Substantive content judgment:** Yes. One real, shipped change. Not placeholder.

### Most recent released section in CHANGELOG

`## [1.6.0] — 2026-04-24` — matches the canonical version from §1.1. Contents include:

- Medication reminder mode PR1–PR4 (schema, reactive scheduler, Android UI, Web UI)
- Fixed: `SyncService.pushUpdate` delete-wins
- Test infrastructure: Test 8 audit, StreakCalculator clock tests, orphan healer cleanup, Tests 12 & 13 manual runbook, Repo hygiene

### Missing entries — `git log v1.4.40..HEAD`

The CHANGELOG **jumps from `v1.4.40` straight to `[1.6.0]`** with no `v1.4.41…v1.4.43`, `v1.5.0`, `v1.5.2`, `v1.5.3` headers. The intervening tags are real, were released, and shipped substantive work. Reconstruction (do **not** write these without user confirmation):

#### Proposed v1.4.41 — backfill from `v1.4.40..v1.4.41`
- `544be9fd` fix(test): use real SyncTracker in BuiltInTaskTemplateBackfillerEmulatorTest (#691)
- `7ad50666` chore: promote versioned CHANGELOG headers + add migration 48→49/49→50/50→51/52→53 tests (#690)
- `b1594206` fix(test): integration CI — sign in to Auth emulator + stop NetworkMonitor leak (#689)
- `249cef8b` chore: repo hygiene — hooks, conventions, CHANGELOG entry

#### Proposed v1.4.42 — backfill from `v1.4.41..v1.4.42`
- `04b6879e` feat(ai): NLP batch ops — schema + backend (A2 pulled-from-H PR1) (#692)
- `347e5e62` feat(ai): NLP batch ops — UI + preview + snackbar undo (A2 pulled-from-H PR2) (#697)
- `7f34b5f9` feat(ai): NLP batch ops — Settings history + 24hr sweep (A2 pulled-from-H PR3) (#700)
- `6cb086b5` fix(test): repair Migration52To53Test (#693)
- `c2d75f46` fix(lint): ktlint+detekt ArgumentListWrapping in Migration48To49Test (#694)
- `7e3e31dd` fix(lint): move inline comment off value_argument_list in BatchUndoLogDaoTest (#696)
- `ada4bdc4` fix(test): update StartupCrashDiagnosticTest expectations for DB v58 (#698)
- `deb14583` fix(test): wire BatchUndoLogDao into TestDatabaseModule (#699)

#### Proposed v1.4.43 — backfill (build 687)
- `d237d280` feat(meds): medication slot system — schema + backfill (A2 #6 PR1) (#695)

#### Proposed v1.5.0 — A2 medication slot system + Wellness rollups
- `2f88deac` feat(meds): slot editor + tier picker + override toggle (A2 #6 PR2) (#701)
- `546b03bf` feat(meds): MedicationScreen rewire — closes A2 #6 + A2 #7 (PR3) (#703)
- `68aceb5c` fix(test): wire 3 MedicationSlot DAOs into TestDatabaseModule (#702)
- (CHANGELOG header to capture the medication slot system landing end-to-end)

#### Proposed v1.5.2 — Web parity slices 1–22
- 22 web parity PRs landed between `v1.5.0..v1.5.2` (PRs #711, #712, #714, #715, #717, #718, #720, #721, #722, #723, #724, #725, #726, #727, #728, #730, #731, #732, #734, #735, #736, #737), plus docs slip/checkpoints. This is the largest gap by feature count.
- Also `2d90aaab` Chore/changelog plus migration tests (#709)
- Also `12cc95ae` ci(release): surface actual alias names when KEY_ALIAS doesn't match (#739)

#### Proposed v1.5.3 — release-pipeline fixes
- `06c0bb41` ci(release): make Create GitHub Release idempotent (#740)
- `8d4675ee` ci(release): unblock publish on backend-upload failure; bump to 1.5.3

#### Already in CHANGELOG under `[1.6.0]`
- All 4 medication reminder mode PRs (#755, #757, #758, #759)
- Sync test PRs #741, #749, #750, #751, #756
- `52a840e4` pushUpdate delete-wins fix (#753)

#### Possibly missing from `[1.6.0]` — proposed augmentation
- `d2f70852` fix(ci): autofix triggers required checks on post-fix push (#754) — **Not in current 1.6.0 entry; should add under "Repo hygiene" or "CI"**
- `1b2beab4` ci: disambiguate Web CI job name to avoid status-check collision with Android CI — **possibly in Unreleased ChangeLog historical edits, but should verify**

#### Currently in Unreleased (correct)
- `44149da4` fix(web): adopt Android canonical 4-tier lowercase medication enum (#762) — captured

### Order and dating

- Chronological order is **mostly** reverse, but: §1.4.1–v1.4.34 is grouped as a single "Interim releases (April 2026)" block at line 986, after `v1.4.36` and `v1.4.35` — that's an explicit grouped catch-up, declared as such in `7ad50666`. OK as a documented choice.
- Date format: most v1.0+ entries use `(April 2026)` instead of an ISO date. Inconsistent with v1.6.0 and the v0.x entries.

### CHANGELOG audit — recommended Phase 2 actions

1. **Backfill** v1.4.41 / v1.4.42 / v1.4.43 / v1.5.0 / v1.5.2 / v1.5.3 sections — surfaced above for user wording confirmation.
2. **Augment v1.6.0** with PR #754 (autofix CI) and PR #1b2beab4 web CI rename, if user confirms.
3. **Format normalization** (style decision): standardize on Keep-a-Changelog bracketed + ISO date + em-dash. Surface as a separate sub-task; user may prefer to keep narrative format as-is.
4. **Promote `## Unreleased` → `## [1.7.0] — <date>`** is *not* recommended — the Unreleased entry is small (one BREAKING change), and we already have v1.6.0 covering most current code. Treat the Unreleased medication enum normalization as either:
   - Part of v1.5.x backfill (it described pre-1.5.3 enum cleanup), or
   - A v1.6.1 patch level addition (with web bump), or
   - Left in Unreleased to combine with the next change.

---

## §1.4 — Bump decision

**Recommendation: NO further `versionName`/`versionCode` bump.** `app/build.gradle.kts` is already at `1.6.0` / `690`. Tag and Release for v1.6.0 are missing — Phase 3 should cut them.

The Phase 2 PR's job is therefore:

- **Tag/release work**: tag `v1.6.0` and cut a GitHub Release (Phase 3, post-merge).
- **Backfill**: write CHANGELOG sections for v1.4.41/.42/.43, v1.5.0, v1.5.2, v1.5.3 — pending user confirmation on wording.
- **Sync everything else** to canonical v1.6.0: README, ARCHITECTURE.md, store/listing/, backend health-check refs, web footer/version display.

If user prefers, an alternate path: bump build.gradle to v1.6.1 / 691, promote Unreleased → v1.6.1, tag v1.6.0 + v1.6.1 separately. Recommended only if the Unreleased BREAKING (web) change deserves its own version line — likely not.

---

## §1.5 — README audit

`README.md` (85 lines) is heavily stale.

| Concern | Detail | Fix |
| --- | --- | --- |
| Roadmap section header says **"v1.4.0 — In Progress"** | line 71 | Replace with shipped/in-progress framing for v1.5/v1.6 and a forward-looking section for v1.7+ |
| Roadmap items marked "🔜 Planned" that are actually shipped | `Calendar` (ADR shipped), `Projects Phases 2–5` (per `WEB_PARITY_GAP_ANALYSIS.md`, milestones + widget shipped in v1.4) | Move to "Done" with version markers |
| No mention of the **medication slot system** (A2, v1.5.0) | n/a | Add to feature list |
| No mention of the **22-slice web parity** work (v1.5.2) | line 56-58 mentions web exists but doesn't reflect feature parity scope | Replace one-line web mention with feature list parity status |
| No mention of **medication reminder mode** (v1.6.0) | n/a | Add to "Notifications" or new "Medication" feature row |
| Free vs Pro table — feature columns | Comprehensive but does not include medication, medication reminders, daily essentials | Add rows |
| FastAPI badge **0.115** | line 8 | Verify against `backend/requirements.txt` |
| PostgreSQL badge **16** | line 9 | Verify against deployment / Railway docker setup |
| No version badge / current-shipping-version reference | n/a | Add `Current: v1.6.0` callout near the badges |
| Backend description: "JWT authentication, and Claude-powered NLP parsing" | line 61 | Accurate; OK |
| Firebase Emulator dev section | line 65–67 | Confirm setup steps still work with the canonical setup |

---

## §1.6 — Roadmap audit

The **only roadmap-style content lives in README §69–86**. There is no `ROADMAP.md` or `docs/roadmap.md`. Phase G work (web parity full) is referenced in `docs/WEB_PARITY_PHASE_G_PROMPT_TEMPLATE.md` but not as a public roadmap.

Findings:
- The README "v1.4.0 — In Progress" matrix is structurally outdated. v1.4.0 shipped April 2026; the current head is v1.6.0.
- "🔜 Planned" rows reference items that have shipped or are in unclear state. Surface for user, do not silently rewrite.
- The prompt's stated "v1.4.0 → v1.5.0 (AI suite) → v2.0.0 (production)" framework no longer maps to reality — v1.5.0 was medication slots, not AI suite, and v1.5.2 was web parity. The "v1.5.0 = AI suite" framing must be retired.

**Open question for user (Phase 2):**
> The README's roadmap framing predates v1.5/v1.6. What do you want the v1.7+ roadmap to look like? Candidate items observed in code: web push for medication reminders, per-medication reminder mode override UI, web slot editor reminder mode picker (deferred per `[1.6.0]`), v1.6.0 enum-normalization helper retirement, Phase 2 medication cleanup runbook execution.

---

## §1.7 — `/docs` and architecture audit

Inventory of `docs/` (15 files + `audits/`):

| File | Last meaningful update | Summary | Staleness |
| --- | --- | --- | --- |
| `ADR-calendar-sync.md` | (pre-v1.4) | ADR for backend-mediated calendar sync | Likely accurate — ADR is historical record by design |
| `FIREBASE_EMULATOR.md` | active | Emulator setup for two-device testing | Likely current; verify env-var refs |
| `NOTIFICATIONS_DESIGN.md` | v1.4.0 era | "Android implementation shipped in this branch covers every domain… in v1.4.0" | **Stale** — v1.6.0 added medication reminder modes; doc doesn't mention them |
| `PHASE_2_MEDICATION_CLEANUP_RUNBOOK.md` | v1.4 → v1.5 boundary | Cleanup of self_care_steps quarantine tables post-medication migration | **Verify current state** — is this still pending or executed? |
| `PHASE_A_DEVICE_TESTING_RUNBOOK_HYBRID.md` | v1.4.38 | Phase A device testing runbook | **Stale** — references v1.4.38 as "current build"; canonical is v1.6.0 |
| `PRIVACY_POLICY.md` / `privacy-policy.html` | unknown | Privacy policy markdown + HTML | Verify "Last updated" date; verify data flows match v1.6.0 reality |
| `RELEASE.md` | active | Release checklist | Looks current; references `bundleRelease`, `versionCode` strategy, content rating, data safety |
| `SPEC_BUILT_IN_TASK_TEMPLATE_RECONCILER.md` | v1.4 era | Spec doc for built-in template reconciler | Likely OK — implementation doc |
| `SPEC_SELF_CARE_STEPS_SYNC_PIPELINE.md` | v1.4 era | Spec for self_care_steps sync (now superseded by medication migration) | **Likely superseded** — flag for user |
| `SYNC_TESTS_12_13_MANUAL.md` | v1.6.0 (just merged) | Manual runbook for sync tests 12 & 13 | Current |
| `TERMS_OF_SERVICE.md` / `terms-of-service.html` | unknown | ToS markdown + HTML | Verify "Last updated" date; verify accurate to current data flows |
| `WEB_PARITY_GAP_ANALYSIS.md` | 2026-04-23 | Web parity audit | **Internally consistent** — last updated post-slice-22 |
| `WEB_PARITY_PHASE_G_PROMPT_TEMPLATE.md` | active | Phase G framework | OK |
| `projects-feature.md` | v1.4 | Projects feature spec | **Header literally says "(v1.4.0)"** — mostly accurate but doesn't capture v1.5+ refinements |
| `sync-architecture.md` | v1.4+ | Firestore sync architecture | Likely accurate; verify it covers v1.5.x new entity additions |
| `audits/A2_POST_MERGE_AUDIT_2026-04-22.md` | 2026-04-22 | A2 post-merge audit | Historical; preserve as-is |

### `ARCHITECTURE.md` — **major staleness flag**

`ARCHITECTURE.md` describes a **PostgreSQL/FastAPI-only** architecture in its "System Overview" diagram (lines 5–51) and Tech Stack table (lines 55–71). It does **not mention Firebase Firestore at all**, despite Firestore being the actual cross-device sync backbone (see `docs/sync-architecture.md`).

Per `CLAUDE.md`, the real architecture is hybrid:
- **Cross-device sync**: Firebase Firestore directly (Android ↔ Firestore ↔ Web), bypassing the FastAPI backend
- **AI / NLP / batch ops / app-update / analytics**: FastAPI backend on Railway (talks to Firestore directly per project memory, not PostgreSQL for these features)
- **Auth**: Firebase Auth (with the FastAPI verifying ID tokens for backend calls)
- **PostgreSQL**: ?? — verify whether PG is still in use at all, or whether the codebase has moved fully to Firestore

This is a substantive rewrite, **not a typo fix**. Surface for user confirmation in Phase 2 — do not silently rewrite. The diagram and tech-stack table both need rework. Recommended approach:
1. Audit which routers in `backend/app/routers/` actually touch PostgreSQL (via `database.py`) vs. Firestore (via Firebase Admin SDK).
2. Update the system overview diagram to show Firestore as the primary sync layer + FastAPI as the AI/ops layer.
3. Update the tech stack table.

### `CONTRIBUTING.md` audit

Looks current and correct: backend `pip install` + `uvicorn` works; web `npm install` + `npm run dev` works; Android `./gradlew assembleDebug` works. **No fixes needed** beyond a possible mention of the Firebase Emulator local-testing flow (already in README).

### Top-level audit docs (relocated in Phase 2)

These are **point-in-time audits**, intentionally frozen as historical records — Phase 2 moved them from the repo root into `docs/audits/` so the root stays oriented around CLAUDE.md / README / ARCHITECTURE / CONTRIBUTING / SECURITY / CHANGELOG / LICENSE / CI_LOGS:
- `docs/audits/COPY_STRINGS_AUDIT.md`
- `docs/audits/CRASH_STABILITY_AUDIT.md`
- `docs/audits/DATA_INTEGRITY_AUDIT.md`
- `docs/audits/DEAD_CODE_AUDIT.md`

---

## §1.8 — Backend version audit

| Detail | Value |
| --- | --- |
| Source of truth | `backend/app/main.py:11`: `API_VERSION = "0.2.0"` |
| `/health_check` returns | `{"status": "healthy", "service": "PrismTask API", "version": API_VERSION}` |
| OpenAPI exposes | `version=API_VERSION` |
| `pyproject.toml` / `setup.py` / `__init__.py` declared version | **None** — no `pyproject.toml` or `setup.py` present; only `requirements.txt` |
| Independent semver vs. synced? | **Independent — keep**. Backend cadence is decoupled from Android releases (most app-side changes don't touch backend). Single source of truth at `main.py:11` is good. |

### Backend changes in `v1.5.3..HEAD` (4 commits, none touching backend)

`git log v1.5.3..HEAD -- backend/` returns nothing app-relevant — checking…

(Note: confirm with `git log v1.5.3..HEAD -- backend/` in Phase 2.)

**Recommendation:** Backend `API_VERSION` stays at `0.2.0`. No bump required for this PR. The web parity slices in v1.5.0..v1.5.2 added several backend AI endpoints; if any of those bumps were missed, surface separately.

---

## §1.9 — Web app version audit

| Detail | Value |
| --- | --- |
| `web/package.json:4` | `"version": "0.0.0"` |
| Footer / displayed version | **None found** (grep `web/src/` for "footer", "version" with VITE-injected build env returned no display surfaces) |
| Vite env injection | None for version (Firebase env vars only) |
| Independent semver vs. synced? | Recommend **independent** for now; bump when explicitly cutting a web release |

### Recommendation

The web app has shipped 22 substantive slices (v1.5.0..v1.5.2) and is no longer "0.0.0". Two paths:
1. **Bump independently** to e.g. `0.5.0` (representing the web parity work shipped to date) and commit to versioning the web app independently going forward.
2. **Sync to Android** at `1.6.0` — simpler narrative, but no operational benefit and ties release cadences.

Defer to user. Either path is consistent with the audit; both fix the "0.0.0" symptom.

---

## §1.10 — Cross-system consistency table

| System | Stated version | Canonical | Action needed |
| --- | --- | --- | --- |
| Android (`app/build.gradle.kts`) | 1.6.0 / 690 | 1.6.0 / 690 | **No-op** — already at canonical |
| Git tag | v1.5.3 | v1.6.0 | **Tag v1.6.0** in Phase 3 (post-merge) |
| GitHub Release | v1.5.3 | v1.6.0 | **Cut v1.6.0 release** in Phase 3 |
| CHANGELOG.md | `[1.6.0]` exists, but v1.4.41/.42/.43 + v1.5.x entries missing | v1.6.0 | **Backfill v1.4.41/.42/.43 + v1.5.0/.5.2/.5.3** (user-confirm wording); augment v1.6.0 with missing entries; (optional) format-normalize narrative-style sections |
| README.md | "v1.4.0 — In Progress" | v1.6.0 | **Restructure roadmap section**; update feature list for medication slots / web parity / reminder modes; verify badges |
| ARCHITECTURE.md | PostgreSQL/FastAPI-only diagram | hybrid Firestore + FastAPI | **Rewrite system overview + tech stack** (pause for user confirmation; substantive rewrite) |
| `docs/NOTIFICATIONS_DESIGN.md` | v1.4.0 | v1.6.0 | Add medication reminder mode section |
| `docs/PHASE_A_DEVICE_TESTING_RUNBOOK_HYBRID.md` | v1.4.38 | v1.6.0 | Update build references |
| `docs/projects-feature.md` | "(v1.4.0)" header | (still accurate as historical-record-of-v1.4) | Optional: add note that header refers to feature introduction |
| `docs/PRIVACY_POLICY.md` / `TERMS_OF_SERVICE.md` | unknown last-updated | n/a | Verify "Last updated" date; verify data-flow descriptions |
| Backend (`backend/app/main.py`) | API_VERSION = "0.2.0" | (independent) | **No-op** unless backend changes warrant bump |
| Web (`web/package.json`) | "0.0.0" | (independent, decision needed) | **Bump** — user picks 0.5.0 / 1.6.0 / other |
| Store listing (`store/listing/`) | Plain feature description, no version-specific release notes | (per-version notes optional) | If desired, add `release-notes/v1.6.0.txt` (no folder exists today) |

---

## §1.11 — Open questions to resolve before Phase 2

1. **CHANGELOG backfill — wording confirmation.** Phase 1 surfaces commit-level reconstruction for v1.4.41 / v1.4.42 / v1.4.43 / v1.5.0 / v1.5.2 / v1.5.3 (see §1.3 missing entries). Per the prompt's hard rule "Do not invent CHANGELOG entries," Phase 2 will draft full prose entries from the commits and pause for user confirmation before writing.
2. **ARCHITECTURE.md major rewrite.** The PostgreSQL/FastAPI-only diagram is materially wrong. Confirm: do you want a substantive rewrite that introduces Firestore as the primary sync layer? Or keep the doc focused on the FastAPI/PostgreSQL slice and add a separate "Sync architecture" reference?
3. **Web app version.** Bump `web/package.json` from `"0.0.0"` to (a) `0.5.0` (independent), (b) `1.6.0` (sync to Android), or (c) other.
4. **Backend version.** Confirm `API_VERSION = "0.2.0"` stays. (No backend-only commits in `v1.5.3..HEAD` per spot check; reverify in Phase 2.)
5. **Roadmap framing for v1.7+.** README's "v1.4.0 → v1.5.0 (AI suite) → v2.0.0" framing is retired. What's the new framework? (Per prompt: v1.6.0 → v1.7.0 → v2.0.0 → v2.2 widget re-enable, etc. — confirm.)
6. **Release-notes directory.** `store/listing/` does not have per-version release notes today (only static descriptions). Should Phase 2 introduce `store/listing/en-US/release-notes/v1.6.0.txt`?
7. **DataExporter memory note retirement.** Auto-memory mentioned a hardcoded `"0.7.1"` in DataExporter. The literal is gone — `DataExporter.kt:737` has `EXPORT_VERSION = 5` (integer schema version). The memory note is stale. Phase 1 will leave the memory alone; Phase 2 / Phase 3 can retire it.
8. **CHANGELOG format normalization.** Standardize on Keep-a-Changelog bracketed + ISO date for all sections, or leave the v1.0.0–v1.4.40 narrative-style sections as-is? The latter preserves history; the former eases tooling. Recommend: leave historical sections, enforce KaCL on new entries from v1.4.41 onward.
9. **PR #754 (autofix CI) and PR #1b2beab4 (web CI rename) augmentations to v1.6.0 entry.** Phase 1 saw both commits in `v1.5.3..HEAD`. The `[1.6.0]` section may already include them implicitly; Phase 2 will confirm by full-section re-read before adding.

---

## Phase 1 — Recommendation summary

**Canonical version determined:** **v1.6.0 / versionCode 690** (selected as highest among `build.gradle.kts` / git tag / GitHub Release).

**Bump recommendation:** **NO-BUMP for `versionName`/`versionCode`.** The bump already landed in commit `3c1e5717`. Phase 2 PR's job is the sync work + CHANGELOG backfill (with user confirmation on wording). Phase 3 cuts the missing v1.6.0 git tag and GitHub Release.

**Override either decision before Phase 2 proceeds.**
