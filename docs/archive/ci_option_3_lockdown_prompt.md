# Claude Code Prompt — CI Pipeline Option 3 (Pre-Launch Lockdown Hardening)

## When to use this prompt

**Park this until you're 1-2 weeks before beta launch and have decided to trade velocity for stability.**

This is a deliberate switch into lockdown mode: every PR pays a longer wall-clock cost in exchange for stronger main-branch guarantees. It is NOT meant to run continuously through development. Recommended trigger conditions, any of which qualifies:

- Beta launch is <14 days away and `connectedDebugAndroidTest` flakiness is below the <5%/20-run benchmark
- A regression has already shipped to main that this prompt's checks would have caught
- You're moving from "ship features" mode to "stabilize for testers" mode and accept ~30-min PR cycles
- Avery has explicitly said "lock it down"

If none of those apply, this prompt is the wrong tool. Stay on Option 2.

---

## Prerequisites

Option 2 (`ci_option_2_medium_speed_prompt.md`) must be shipped and verified. This prompt assumes:
- `version-bump-and-distribute.yml` is a separate `workflow_run`-triggered workflow
- `autofix` and `lint-and-test` run in parallel
- Gradle build cache is artifact-cached
- Auto-merge poll loop is trimmed
- `connected-tests` is conditionally required via either GitHub Rulesets or skipped-success guard

If those aren't in place, stop and run Option 2 first. Option 3 builds on top of Option 2's structure.

---

## Context

The four required checks under Option 2 are:
- `lint-and-test` (Android CI)
- `connected-tests` (Android Integration CI) — conditionally required on `ci:integration`-labeled PRs
- `test` (Backend CI)
- `web-lint-and-test` (Web CI)

Option 3 adds belt-and-suspenders:
1. **`connected-tests` becomes required for all PRs** (no conditional bypass)
2. **`migration-check` job** validates Room schemas against migration chain
3. **Playwright e2e becomes required** for web changes (currently advisory)
4. **`release-build-smoke` job** runs `bundleRelease` with a dummy keystore on PRs to catch v1.5.2-class config drift before release time
5. **Gradle dependency lockfile check** detects unintended dependency drift

Trade: ~12-18 min typical PR wall-clock, ~30 min for data-layer PRs. Solo-dev sustainable for short windows; brutal otherwise.

This is a three-phase task: **audit → implement → verify**. Same structure as Option 2 — do not skip phases.

---

## Phase 1 — Audit (read-only, no code changes)

Read every file below in full and produce an audit report. Do not write any code yet.

**Files to read:**
- All workflow files in `.github/workflows/`
- All composite actions in `.github/actions/`
- `scripts/setup-branch-protection.sh`
- `app/schemas/` directory listing — Room schema JSONs
- `app/src/androidTest/` directory — list migration test files
- `web/playwright.config.ts` (or similar)
- `web/tests/` or `web/e2e/` Playwright test count
- `gradle.lockfile` if present, else identify whether Gradle dependency locking is enabled in `build.gradle.kts` / `settings.gradle.kts`

**Audit deliverables — answer each:**

1. **`connected-tests` flakiness baseline.** The handoff says coverage is <5%/20-run-flakiness short. Quantify:
   - Run the connected-tests workflow 20 times against a recent merged commit (or use existing run history if available — `gh run list -w "Android Integration CI" --limit 50` gives recent runs).
   - Count how many failed for non-substantive reasons (AVD timeout, emulator boot crash, port collision, transient Firestore Emulator issue).
   - Report the actual flake rate. If above 5%, **stop and recommend stabilizing connected-tests before promoting it to all-PRs required**. This is the single biggest risk in Option 3.

2. **Room schema validation feasibility.** Identify:
   - Where `app/schemas/com.averycorp.prismtask.data.local.database.PrismTaskDatabase/` lives (current DB version is v61, so v60.json and v61.json should both exist)
   - Whether `./gradlew validateRoomSchemas` (or equivalent) is a real Gradle task in this project or if it needs to be wired up
   - The migration test pattern in `app/src/androidTest/` — there's already `Migration54To55Test`, `Migration55To56Test`, etc. Are they hermetic enough to run on the lint-and-test job's lightweight context, or do they need the full emulator (which is connected-tests' domain)?
   - Recommend: standalone `migration-check` job vs. extension of existing migration tests vs. new Gradle task.

3. **Playwright e2e promotion analysis.**
   - Current `e2e` job in `web-ci.yml` is advisory (not required). It runs Playwright Chromium with caching.
   - Current Playwright test count (per handoff: "9 Playwright"). Are all 9 stable? Have any failed in the last 30 main pushes?
   - Wall-clock cost on a fresh PR (cold cache) and warm PR (cache hit). Document both.
   - Recommend: promote to required for web-changing PRs only, or all PRs.

4. **`release-build-smoke` design.**
   - Goal: catch the v1.5.2-class bug (Gradle release config / signing config / keystore drift) before tag time.
   - Constraint: cannot use the real upload keystore on a PR — that secret is gitignored and not in PR-accessible secrets.
   - Approach: generate a throwaway debug-signed keystore on-the-fly in the job, configure `signingConfigs.release` to use it via env-var override, run `./gradlew bundleRelease -x lint -x lintVitalRelease`. The bundle output is discarded.
   - Audit: does `app/build.gradle.kts` already support an env-var keystore override? If not, what's the minimum-blast-radius edit to add one without affecting real release builds?
   - Estimated wall-clock cost: ~3-4 min. Confirm by spec-reading the existing `release.yml` build step and comparing to the pre-existing `lint-and-test` Gradle steps.

5. **Gradle dependency lockfile check.**
   - Is dependency locking already enabled in this project? Look for `dependencyLocking { ... }` block in `build.gradle.kts` or a `gradle.lockfile` in repo root.
   - If not: cost-benefit of enabling it now. Pros: detect unintended dep drift, repeatable builds. Cons: every `./gradlew dependencies --write-locks` becomes a required step on PRs that intentionally bump deps; team friction (currently solo, fine).
   - Recommend: enable + add `./gradlew :app:dependencies --update-locks --write-locks` as a verification step that fails if the lockfile would change without an explicit commit.

6. **Branch protection update plan.**
   - Current required checks (Option 2 end state): `lint-and-test`, `connected-tests` (conditional), `test`, `web-lint-and-test`.
   - Option 3 end state: add `migration-check`, `release-build-smoke`, conditionally add Playwright e2e for web PRs (or unconditionally if it's stable enough).
   - Outline the exact `setup-branch-protection.sh` edit and confirm GitHub Rulesets (or skipped-success guards, depending on Option 2's choice) supports the new shape.

7. **Mitigation for the 30-min PR cycle.**
   - At 30 min/PR, batch hotfixing during beta becomes painful. Recommend:
     - Whether to add a `lockdown:bypass` label that skips the new Option 3 checks for emergency hotfixes (with a guard that warns about the bypass)
     - Whether the lockdown should auto-disable after launch (e.g. a workflow that re-runs `setup-branch-protection.sh` with the Option 2 shape on a date-based trigger)
     - Whether to keep lockdown as a manual toggle indefinitely

8. **Side effects audit:**
   - What in-flight PRs would suddenly start failing on lockdown adoption? Inventory open PRs at the time this lands and document their lockdown-readiness.
   - Does `connected-tests` promoted to required produce a deadlock for PRs that opened before the change but haven't been rebased?

9. **Unknowns / blockers** — surface anything that contradicts assumptions in this prompt.

**Stop after Phase 1.** Surface the report. Wait for Avery to confirm before proceeding to Phase 2.

If Phase 1 reveals connected-tests flakiness above the 5% benchmark, **recommend deferring Option 3 entirely** until that's fixed. Do not proceed with promotion-to-required on a flaky test.

---

## Phase 2 — Implementation (after Phase 1 sign-off)

Branch + worktree convention: create a worktree branched off latest `main`. Branch name: `ci/option-3-lockdown`. After PR merges, remove the worktree and branch.

**Implementation order (each is a separate commit on the same branch):**

### Commit 1 — `migration-check` job

Add a new job to `android-ci.yml` (does not need its own workflow file — it's lightweight and shares the Android setup):
- Runs `./gradlew validateRoomSchemas` (or whatever Phase 1 audit endorsed)
- Verifies `app/schemas/` is in sync with the current `@Database(version = N)` declaration
- Fails if migrations are missing or schema diffs are uncommitted

If Phase 1 surfaced that hermetic migration tests can be promoted from `connected-tests` to `lint-and-test`, do that here too.

Update `scripts/setup-branch-protection.sh` to add `migration-check` to required.

### Commit 2 — Promote `connected-tests` to required for all PRs

Edit `scripts/setup-branch-protection.sh`:
- Remove the conditional logic from Option 2 (whether GitHub Rulesets or skipped-success guard)
- Make `connected-tests` flat-required for all PRs

Edit `.github/workflows/android-integration.yml`:
- Remove the label-gated `if:` on the `connected-tests` job — it now runs unconditionally on all PRs
- Keep the `concurrency:` group to prevent parallel emulator runs on rapid syncs

Edit `.github/workflows/label-integration-ci.yml`:
- This workflow becomes redundant for the required-check purpose, but keep it as a no-op-when-already-applied since the label still has utility for filtering / Firebase distribution selection
- Or delete it entirely if Phase 1 audit recommends — surface that decision

### Commit 3 — Promote Playwright e2e to required

Per Phase 1 audit recommendation:
- If e2e is stable: add it to `setup-branch-protection.sh` required list
- Edit `web-ci.yml` to remove `e2e` job's optional/advisory status comment

If e2e flake rate is non-trivial:
- **Stop and surface**. Do not promote a flaky check.

### Commit 4 — `release-build-smoke` job

Add a new job to `android-ci.yml` (or its own workflow file `android-release-smoke.yml` if Phase 1 prefers):
- Generates a throwaway keystore on-the-fly: `keytool -genkey -keystore /tmp/smoke.jks -alias smoke -dname "CN=smoke" -storepass smoke123 -keypass smoke123 -keyalg RSA -validity 1`
- Sets env vars to point `signingConfigs.release` at the throwaway keystore
- Runs `./gradlew bundleRelease -x lint -x lintVitalRelease --no-configuration-cache`
- Discards the AAB output (or uploads as an artifact for debugging if useful)
- Step timeout: 8 min

Audit `app/build.gradle.kts` to ensure the keystore-via-env-var override path exists. If not, add it as part of this commit with the most surgical edit possible.

Update `setup-branch-protection.sh` to add `release-build-smoke` to required.

### Commit 5 — Gradle dependency lockfile

Per Phase 1 audit:
- Add `dependencyLocking { lockAllConfigurations() }` to root `build.gradle.kts` if not present
- Generate initial `gradle.lockfile` via `./gradlew :app:dependencies --update-locks --write-locks`
- Add a verification step to `lint-and-test` (or the new `migration-check` — Phase 1 chooses): `./gradlew :app:dependencies --update-locks --write-locks` followed by `git diff --exit-code gradle.lockfile`
- Document the workflow for intentional dep bumps in `docs/CI_DEPENDENCY_LOCKFILE.md`

### Commit 6 — Lockdown bypass and auto-disable mechanisms

Per Phase 1 mitigation recommendation:
- If bypass label endorsed: add `lockdown:bypass` label handling to relevant workflows. Bypass label requires manual application + posts a PR comment warning.
- If auto-disable endorsed: add a `cron`-triggered workflow that re-runs `setup-branch-protection.sh` with the Option 2 shape on a chosen date (e.g. 30 days post-launch).
- If manual toggle preferred: document the toggle process in `docs/CI_LOCKDOWN_TOGGLE.md`.

---

## Phase 3 — Verification (post-merge)

After the PR lands on main:

1. **Open a small no-op PR** and time the new wall-clock baseline. Document expected: ~12-18 min for non-data-layer, ~30 min for data-layer (with full emulator + connected-tests).

2. **Verify each new required check fires and is enforced:**
   - `migration-check` — open a PR that intentionally adds a Room column without a migration. Should fail.
   - `connected-tests` — open a non-data-layer PR (e.g. README edit). Should still wait for connected-tests to pass.
   - Playwright e2e (if promoted) — open a web-changing PR. Should wait for e2e.
   - `release-build-smoke` — open any PR. Should pass; force a Gradle config break and verify it fails.
   - Gradle lockfile — bump a dependency in a PR without updating the lockfile. Should fail.

3. **Verify bypass mechanism (if implemented):**
   - Apply `lockdown:bypass` to a PR. Verify required checks are skipped or the bypass override works as designed.
   - Verify the bypass posts the warning comment.

4. **Document in `docs/CI_OPTION_3_VERIFICATION.md`:**
   - Actual measured wall-clocks
   - Each new required check's behavior in success and failure cases
   - Any flakes observed during the verification window
   - Whether to keep, tune, or roll back any specific commit

5. **Schedule a re-audit 7 days post-merge.** Lockdown's value is in the catches — log every CI failure that would have shipped to main without Option 3. If the catch rate is zero after 14 days, recommend rolling back to Option 2.

---

## What NOT to do

- **Do not implement Option 1 or Option 2 changes** that aren't already in place. This prompt assumes Option 2 has shipped.
- **Do not promote `connected-tests` to required if Phase 1 measured flake rate above 5%.** Stabilize first.
- **Do not promote Playwright e2e to required if Phase 1 found instability.**
- **Do not skip Phase 1.** Lockdown without measurement is performative — every required check needs a documented justification.
- **Do not bypass branch protection** during this PR. The PR itself goes through Option 2's required-check set.

---

## Honest pushback expected

This prompt makes several assumptions that may not hold by the time it runs:

- That `connected-tests` is by then below 5% flake rate. If not, the largest commit in this prompt is unsafe.
- That release-build-smoke's signing-config override is feasible without a high-blast-radius edit to `build.gradle.kts`.
- That solo-dev with 30-min PR cycles is sustainable for the lockdown window. If Avery's pace requires faster cycles, recommend a narrower lockdown (e.g. only `migration-check` + `release-build-smoke`, skip the e2e and lockfile work).
- That GitHub Rulesets or skipped-success guards from Option 2 still work the same way at the time of running.

Verify all of these in Phase 1. If any fail, recommend a partial Option 3 adoption rather than the full set. The goal is correct main-branch protection, not landing every commit in this prompt verbatim.

---

## Rollback plan

If Option 3 turns out to be wrong for the moment (too slow, too flaky, blocking real work):

1. Revert the branch protection changes via `setup-branch-protection.sh` with the Option 2 required-check list
2. Disable the new jobs via `if: false` rather than deleting them — preserves the work for a future lockdown window
3. Document why rollback was needed in `docs/CI_OPTION_3_VERIFICATION.md` so the next attempt has the context

Rollback is not a failure; it's the correct response to "this isn't the right time."
