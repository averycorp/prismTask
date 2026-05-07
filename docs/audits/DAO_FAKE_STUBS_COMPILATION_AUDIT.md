# DAO Fake-Stubs Compilation Failure Audit

**Date:** 2026-05-07
**Branch:** `fix/dao-fake-stubs`
**Operator scope:** "Lint and test failed on main" — investigate and open
separate PR.
**Failing CI run:** `25523136908` (Android CI on main commit
`f012708d4be849c5a11b4fe1ace8f9c728a11ef7`).
**Failing step:** `lint-and-test: #13 Unit tests` (`./gradlew testDebugUnitTest --continue -i`).

The latest `lint-and-test` run on main fails at
`:app:compileDebugUnitTestKotlin` — five hand-rolled `FakeProjectDao` /
`FakeTagDao` test doubles never picked up two abstract methods that were
added to the production DAO interfaces. JUnit XML reports come back
"clean" (the post-test verification step says "All 221 JUnit XML reports
clean.") because compilation died before any tests ran — there are no
test results to check.

This is a one-cause, five-call-site Kotlin compile error. PROCEED.

---

## 1. `FakeProjectDao` / `FakeTagDao` test doubles missing abstract methods (RED, PROCEED)

**Findings.**

Production DAO interfaces declare two new abstract methods:

- `ProjectDao.getProjectByNameOnce(name: String): ProjectEntity?` —
  `app/src/main/java/com/averycorp/prismtask/data/local/dao/ProjectDao.kt:71`
  (case-insensitive name lookup; `@Query("SELECT * FROM projects WHERE name = :name COLLATE NOCASE LIMIT 1")`).
- `TagDao.getTagByNameOnce(name: String): TagEntity?` —
  `app/src/main/java/com/averycorp/prismtask/data/local/dao/TagDao.kt:50`.

Five hand-rolled `Fake<X>Dao` classes in `app/src/test/` declare
`override` for the rest of the DAO surface but skipped these two:

| # | File | Class | Missing override |
|---|------|-------|------------------|
| 1 | `app/src/test/java/com/averycorp/prismtask/data/repository/ProjectRepositoryTest.kt:313` | `FakeProjectDao` | `getProjectByNameOnce` |
| 2 | `app/src/test/java/com/averycorp/prismtask/data/repository/TagRepositoryTest.kt:131` | `FakeTagDao` | `getTagByNameOnce` |
| 3 | `app/src/test/java/com/averycorp/prismtask/data/repository/TaskRepositoryTest.kt:998` | `FakeTagDao` | `getTagByNameOnce` |
| 4 | `app/src/test/java/com/averycorp/prismtask/data/repository/TaskTemplateRepositoryTest.kt:691` | `FakeTagDao` | `getTagByNameOnce` |
| 5 | `app/src/test/java/com/averycorp/prismtask/ui/screens/projects/ProjectsPaneViewModelTest.kt:134` | `FakeProjectDao` | `getProjectByNameOnce` |

The Kotlin compile log (run `25523136908`, line ~5260+) lists all five
errors:

```
e: ProjectRepositoryTest.kt:313:13 Class 'ProjectRepositoryTest.FakeProjectDao' is not abstract and does not implement abstract member:
suspend fun getProjectByNameOnce(name: String): ProjectEntity?
…
e: TagRepositoryTest.kt:131:13 Class 'TagRepositoryTest.FakeTagDao' is not abstract and does not implement abstract member:
suspend fun getTagByNameOnce(name: String): TagEntity?
…

> Task :app:compileDebugUnitTestKotlin FAILED
BUILD FAILED in 43s
```

`testDebugUnitTest --continue` cannot continue past a broken compile,
so step #13 of the workflow exits 1.

**Why this slipped to main.** All Android CI workflows in this repo are
**merge-only** (`.github/workflows/android-ci.yml:5-9` —
"No pull_request / merge_group triggers — if branch protection requires
the `lint-and-test` status, drop it from required checks or PRs will
block forever waiting for a status that never posts"). The PR that
added the two `*ByNameOnce` queries (and presumably did fix the *Room*
abstract members) was green from the merge-time perspective — it never
ran `testDebugUnitTest` against this codebase before merge — and the
five hand-rolled Kotlin test fakes were missed because they're not
Room-generated, they're plain class implementations the IDE may have
flagged but CI didn't catch pre-merge.

**Risk classification.** RED. Main is red on `lint-and-test`. Every
subsequent merge that lands on top of this commit will re-fail the
same step, and any developer working from main will find their local
unit-test build broken. The fix is a one-line stub per fake — five
total override declarations.

**Recommendation.** PROCEED. Add the two missing overrides to each
of the five fakes. Implementations should mirror the existing
in-memory filter pattern the fakes use for the parallel `*ByIdOnce`
methods — case-insensitive `name` lookup over the in-memory `projects`
/ `tags` list (or `null` / `unsupported()` for the two fakes that
already use that style).

### Implementation shape

For `FakeProjectDao`:

```kotlin
override suspend fun getProjectByNameOnce(name: String): ProjectEntity? =
    projects.firstOrNull { it.name.equals(name, ignoreCase = true) }
```

For `FakeTagDao`:

```kotlin
override suspend fun getTagByNameOnce(name: String): TagEntity? =
    tags.firstOrNull { it.name.equals(name, ignoreCase = true) }
```

Mirror the production query semantics:
`COLLATE NOCASE LIMIT 1` → `firstOrNull { equals(ignoreCase = true) }`.
The `TaskRepositoryTest.FakeTagDao` and
`TaskTemplateRepositoryTest.FakeTagDao` use `null` and `unsupported()`
respectively for their `*ByIdOnce` cousins; their new `*ByNameOnce`
overrides match that surrounding style.

### Tests

No new tests. The fix is a compilation unblocker — existing unit-test
suites validate behaviour. CI will run all 221 JUnit XML reports
post-merge to confirm green.

### Out of scope

- The merge-only CI policy itself. The fact that `lint-and-test`
  passes pre-merge then breaks post-merge is the structural reason
  this kind of bug ships. Worth a separate audit; not fixing here.
- Wider sweep of other hand-rolled fakes that may be lagging behind
  DAO changes. If the operator wants a defensive sweep, that's a
  separate audit item.

---

## Ranked improvements

| Rank | Item | Wall-clock saved | Impl cost | Ratio | Verdict |
|------|------|------------------|-----------|-------|---------|
| 1 | Add 5 missing DAO-fake overrides | High — main goes green and stays green for everyone | Trivial — 10 lines total across 5 files | Very high | PROCEED |

## Anti-patterns flagged but not fixing here

- **Hand-rolled `Fake<X>Dao` doubles drift behind DAO interfaces.**
  The repo has roughly five such fakes in test code. A `mockk(relaxed = true)`
  approach (used elsewhere) would have absorbed the new methods
  silently with no compilation break, at the cost of needing explicit
  stubs per test that touches them. That's a real design tension —
  not a "should obviously change" — so flagging only.
- **Merge-only CI** means structural compile-time errors in test
  source land on main. The pre-merge cycle relies on author + reviewer
  noticing IDE squiggles. Worth weighing the cost of a
  `pull_request`-triggered fast-path CI for `compileDebugUnitTestKotlin`
  against the original merge-only rationale — but again, separate
  audit.
