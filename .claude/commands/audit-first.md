---
description: Standard audit-first workflow — Phase 1 audit doc, Phase 2 PR fan-out (auto-fires), Phase 3 bundle summary, Phase 4 Claude Chat handoff. Skips checkpoints by default.
---

# Audit-first workflow

Use this when starting a multi-step audit/investigation. The user provides
**scope** (a markdown file path, an inline scope description, or a list of
items). This command sets up the standard structure so the user doesn't
have to rewrite the boilerplate every time.

## Hard rules — read first

- **Audit-first.** Phase 1 produces NO config or code changes. Audit doc only.
- **Phase 2 fires automatically after Phase 1 — no approval gate.** Once the
  audit doc is written, proceed straight into PR fan-out for every PROCEED
  item in the same session. Do not pause to ask "ready to implement?" — the
  user's intent is the deliverable, not the gating.
- **Skip all checkpoint stops by default** (memory
  `feedback_skip_audit_checkpoints.md`). Treat any "Checkpoint N — STOP"
  markers as documentation milestones inside the audit doc, NOT approval
  gates. Keep going. The user will course-correct mid-stream if needed —
  they have the brake, you have the throttle.
- **Self-investigate first, ask second.** Read the artifacts before asking the
  user questions. Specific examples > general principles.
- **STOP-and-report on wrong premises is the one real halt.** If a premise
  turns out wrong, stop and report rather than rationalizing scope. This is
  a quality gate, not a checkpoint — distinct from the per-item checkpoints
  above.
- **Per CLAUDE.md "Audit doc length"**, cap each Phase at ~500 lines.
  If the doc would exceed 500 lines, STOP and ask the user to split into
  batched audits before continuing — the cap is a hard rule, not a
  guideline. The validated single-pass shape is
  `docs/audits/CONNECTED_TESTS_STABILIZATION_AUDIT.md` (390 lines, PR #859).
- **Don't inline-restate this framework's headers per item.** The Phase 1
  framework (Premise verification / Findings / Risk classification /
  Recommendation) is documented here once; restating it inside each
  audit doc costs tokens without adding clarity for readers who already
  know the convention. Use inline `(GREEN)` / `(YELLOW)` / `(RED)` /
  `(DEFERRED)` tags after item titles, and only promote `Premise
  verification` to a subheader when the premise is wrong (which is the
  load-bearing case worth flagging).

## Phase 1 — Audit (single doc)

Create `docs/audits/<SCOPE_SLUG>.md`. For each scoped item use:

1. **Premise verification.** Does the item describe real codebase reality?
2. **Findings.** What did the sweep surface? Cite files / line numbers / PR
   numbers / commit SHAs.
3. **Risk classification.** RED / YELLOW / GREEN / DEFERRED.
4. **Recommendation.** PROCEED, STOP-no-work-needed, or DEFER.

End the audit doc with a ranked improvement table sorted by
**wall-clock-savings ÷ implementation-cost**, plus an anti-pattern list
for things worth flagging but not necessarily fixing.

## Phase 2 — Implementation (auto-fires after Phase 1)

Phase 2 begins immediately after Phase 1 is committed — no "ready to start?"
checkpoint. Implement every PROCEED item; skip STOP-no-work-needed and
DEFER items (note them in Phase 3 + Phase 4 instead).

Per-improvement shape:

- Branch: `<type>/<scope-slug>` (e.g. `chore/...`, `fix/...`, `ci/...`).
- Squash-merge auto-merge via `gh pr merge <num> --auto --squash`.
- Required CI green.
- **No `[skip ci]`** in commit messages — applies regardless of quoting
  (`feedback_skip_ci_in_commit_message.md`).
- Trailing newline on `CHANGELOG.md` if touched.
- Use a worktree per feature (memory `feedback_use_worktrees_for_features.md`);
  remove the worktree + delete the branch the same session it merges
  (memory: worktree teardown paired with merge).

Bundle multiple small fixes into one PR only when they're a single coherent
scope; otherwise prefer N small PRs (fan-out bundling rule).

## Phase 3 — Bundle summary (in the audit doc)

After Phase 2 PRs merge, append to the audit doc:

- Per-improvement: PR number(s), measured impact (if measurable post-merge).
- Re-baselined wall-clock-per-PR estimate (if relevant).
- Memory entry candidates (only if surprising / non-obvious).
- Schedule for next audit.

## Phase 4 — Claude Chat handoff summary

After Phase 3 is appended, emit a paste-ready summary the user can drop
straight into a fresh Claude.ai (Claude Chat) conversation. Print it as
the last thing in the run, inside a fenced ` ```markdown ` block so the
whole thing copies cleanly in one selection.

Target ~30–60 lines, self-contained — assume the receiving Claude has no
repo access and no prior context. Include in this order:

1. **Scope** — one sentence: what was audited and why.
2. **Verdicts table** — each scoped item with its
   RED / YELLOW / GREEN / DEFERRED classification + a one-line finding.
3. **Shipped** — bullet list of merged PRs (number + one-line each).
4. **Deferred / stopped** — bullets explaining why each didn't ship.
5. **Non-obvious findings** — surprises a future reader (or a follow-up
   Claude Chat thread) would want to know up front. Skip if nothing
   surprised you.
6. **Open questions** — anything genuinely ambiguous left for the operator.

Write so a fresh chat can pick up the thread without needing the audit
doc in front of it. Prefer concrete file paths, PR numbers, and SHAs over
vague references.

## Args

The user may pass `<scope>` as:

- A path to a markdown file describing scoped items — read it first.
- An inline scope description — use it directly.
- Nothing — ask the user for the scope (one sentence each: domain,
  optimization target, suspected-failure-modes).

## Reference: relevant memory entries

- `feedback_use_worktrees_for_features.md` — worktree teardown paired with merge.
- `feedback_skip_ci_in_commit_message.md` — `[skip ci]` blocks all workflows.
- `feedback_skip_audit_checkpoints.md` — skip checkpoint stops by default.
- `feedback_audit_drive_by_migration_fixes.md` — `git log -p -S` before recommending fixes.
- `feedback_repro_first_for_time_boundary_bugs.md` — write the structural repro test first.
