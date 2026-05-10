# E2 In-App Ratings — Combined Bundle Audit

**Branch:** `claude/add-inapp-review-api-cdQkl`
**Window:** Pre-Phase F (May 15) kickoff. ~6 days to ship.
**Bundle estimate:** ~450–650 LOC (counter plumbing chosen by operator).
**Audit shape:** single doc, six § Item sections + § Bundle-decision.

This audit covers the E2 in-app ratings bundle: Google Play in-app review API,
custom "how's it going?" prompt, shared trigger heuristics, FastAPI backend
endpoint, alembic migration, and trigger-site wiring. Implementation lands
in a single PR.

## Phase 0 outcomes

**STOP-A (architectural premise):** Cleared. Findings:
- `com.google.android.play:review` dependency NOT present in `app/build.gradle.kts` →
  Phase 2 adds (~2 LOC).
- `ReviewManager` / `launchReviewFlow` NOT used anywhere in `app/src/main/` →
  fresh integration.
- Crashlytics IS initialized (`BugReportViewModel.kt:28,282`,
  `LeisureScreen.kt:52,96,100`). `recordException(...)` is the existing call
  pattern.
- `backend/app/models.py`, `backend/alembic/versions/`, `backend/app/routers/`
  all present. Latest migration is `025_add_chat_messages.py`. Existing
  `backend/app/routers/feedback.py` already owns the `/feedback` URL prefix —
  the new `/feedback/in-app` endpoint slots in here.

**STOP-PR1167 (onboarding completion flag):** Cleared. `hasCompletedOnboarding`
exists at `OnboardingViewModel.kt:60`, `NavGraph.kt:606`,
`MainActivity.kt:247`. `OnboardingPreferences.hasCompletedOnboarding(): Flow<Boolean>`
is the read API.

**STOP-EXISTING-COUNTERS — FIRED.** All 3 counters absent:
- `tasksCompletedCount` — none.
- `firstLaunch`/`installDate` — none.
- `sessionCount` — none.

Operator chose **option 1: plumb all 3 counters in this PR.** This expands the
bundle by ~+50 LOC and lifts Item 3 from "decision-only helper" to "helper +
counter wiring."

**STOP-3B (Crashlytics 24h-no-fatal not client-queryable):** Will fire during
Phase 1 verdict for Item 3. Crashlytics SDK does not expose a "did we record
a non-fatal in the last 24h" query — it's a one-way write API. Resolution
documented in § Item 3: introduce a `last_crash_at` pref proxy, written from
the existing `recordException` call sites + a global uncaught-exception
handler in `PrismTaskApplication`. This is the "session-stable proxy"
fallback flagged in the prompt.

No other STOPs evaluated true.

## § Item 1 — Google Play in-app review API integration

### Verdict: PROCEED

### Dependency
Add to `app/build.gradle.kts` `dependencies { ... }`:
```kotlin
implementation("com.google.android.play:review:2.0.2")
implementation("com.google.android.play:review-ktx:2.0.2")
```
Min SDK is 26; `com.google.android.play:review:2.0.2` requires API 21 — no
min SDK bump needed. **STOP-1B cleared.**

### Wrapper shape
New file `app/src/main/java/com/averycorp/prismtask/ui/rating/PlayReviewLauncher.kt`:
```kotlin
@Singleton
class PlayReviewLauncher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun launch(activity: Activity): Boolean {
        return try {
            val manager = ReviewManagerFactory.create(context)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
            true
        } catch (e: Exception) {
            // Play Services unavailable (sideloaded, F-Droid, etc.) or
            // Google rate-limited the request — both surface as exceptions
            // from requestReview/launchReview. Silently no-op; the trigger
            // helper will still record this attempt so we don't retry today.
            Log.w(TAG, "Play review launch failed", e)
            false
        }
    }
    companion object { private const val TAG = "PlayReviewLauncher" }
}
```
Uses the `kotlinx-coroutines-play-services` `await()` extension via
`review-ktx`. The wrapper returns a `Boolean` so the caller can distinguish
"Play prompt actually attempted" from "graceful fallback path."

### Trigger site
`PlayReviewLauncher.launch(activity)` is invoked from `MainActivity` when
`RatingPromptTriggerHelper` returns `RatingPromptDecision.PlayReview` — see
§ Item 6.

### Rate-limit handling
Google enforces ~5 prompts/year per user inside `requestReview` itself; on
failure the `Task` resolves with an exception which our `try` catches.
Our 90-day client-side `lastPlayReviewShown` cooldown is a *we-don't-want-to-
ask-Google-too-often* guard, distinct from Google's cap.

### LOC: ~30 (wrapper + dependency + 1 Hilt-friendly singleton)

## § Item 2 — Custom "how's it going?" prompt UI (Compose)

### Verdict: PROCEED

### Dialog shape
**ModalBottomSheet** mirroring `ProUpsellSheet.kt` (PR #1219). Rationale:
- Existing convention in repo for "user-action-required overlay."
- Doesn't disrupt navigation stack.
- Material3 dismiss-by-drag matches platform expectation.

`AlertDialog` was the alternative (mirrors PR #1168 C.3 clear-chat
confirmation) but is a worse fit for the optional free-text input — bottom
sheet handles the keyboard imeNestedScroll path more naturally. **STOP-2A
resolved.**

### Input shape
Thumb up / thumb down + optional free-text field. Rationale: thumb is the
fastest signal (1 tap = submit), 1–5 star imposes more cognitive load and
the resulting noise (stars-as-NPS-proxy) isn't worth the friction at this
volume (solo user pre-launch). Free-text is optional, capped at 1000 chars
client-side.

### State management
New `RatingPromptViewModel`:
- `state: StateFlow<RatingPromptUiState>` — Idle | Submitting | Submitted | Error
- `submit(sentiment: Sentiment, freeText: String?)` — calls
  `RatingFeedbackRepository.submit(...)` → backend POST → on success flip
  to `Submitted` (sheet auto-dismisses after 1.2s), on error flip to `Error`
  with retry CTA inline.
- Closing actions: tap-outside, drag-down, dismiss button → `onDismiss`
  callback that the host (MainActivity) uses to clear `pendingPrompt` state.

### Error handling
On backend write failure:
- Show error inline ("Couldn't send — try again?") with a retry button.
- Do NOT silently drop (operator wants the channel verifiable).
- Sheet stays open until user submits successfully OR dismisses explicitly.

### Privacy
Free-text submitted only to Postgres via `/feedback/in-app`. **NOT** logged
to Crashlytics, Analytics, or `Log.d`. Anti-pattern #7 + #17 enforced by
audit.

### LOC: ~180 (Composable + ViewModel + repository + UI state types)

## § Item 3 — Shared trigger heuristics (centralized helper)

### Verdict: PROCEED with full counter plumbing per operator decision.

### Helper shape
New `RatingPromptTriggerHelper` `@Singleton`:
```kotlin
@Singleton
class RatingPromptTriggerHelper @Inject constructor(
    private val prefs: RatingPromptPreferences,
    private val crashSignal: RecentCrashSignal,
    private val onboardingPreferences: OnboardingPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile private var customPromptShownThisSession: Boolean = false

    suspend fun onAppStart()                           // increments sessionCount, stamps installedAt if 0
    suspend fun onTaskCompleted(): RatingPromptDecision  // increments tasksCompleted; returns decision
    suspend fun recordPlayReviewShown()                // updates lastPlayReviewShown
    suspend fun recordCustomPromptShown()              // updates lastCustomPromptShown + flips session flag
}
```
`RatingPromptDecision = sealed { object None; object PlayReview; object CustomPrompt }`.

### Heuristic logic
`evaluate()` short-circuits in this order:
1. `hasCompletedOnboarding == false` → None.
2. `sessionCount <= 3` → None.
3. `crashSignal.hadRecentCrash(now, 24h)` → None.
4. `customPromptShownThisSession` → None (inter-prompt suppression: never fire
   custom same session as a Play review, and never two custom prompts in one
   session either; the in-memory flag is set by `recordCustomPromptShown`).
5. Play review gate: `tasksCompleted >= N_PLAY` AND `daysSinceFirstLaunch >=
   M_PLAY` AND `(now - lastPlayReviewShown) >= PLAY_COOLDOWN` → `PlayReview`.
6. Custom prompt gate: `tasksCompleted >= N_CUSTOM` AND `daysSinceFirstLaunch
   >= M_CUSTOM` AND `(now - lastCustomPromptShown) >= CUSTOM_COOLDOWN` →
   `CustomPrompt`.
7. Else `None`.

### Threshold values (build constants per operator preference, no remote config)
- `N_PLAY = 10`, `M_PLAY = 7`, `PLAY_COOLDOWN = 90` days
- `N_CUSTOM = 5`, `M_CUSTOM = 3`, `CUSTOM_COOLDOWN = 30` days
- `MIN_SESSIONS_BEFORE_PROMPT = 3`
- `RECENT_CRASH_WINDOW = 24` hours

### Storage
New `RatingPromptPreferences` (DataStore, file `rating_prompt_prefs`),
mirroring the `PerFeatureAiPrefs` pattern (PR #1214):
- `tasks_completed_count: Long` (KEY_TASKS_COMPLETED_COUNT)
- `session_count: Long` (KEY_SESSION_COUNT)
- `first_launch_at: Long` (KEY_FIRST_LAUNCH_AT) — 0L sentinel for "absent"
- `last_play_review_shown_at: Long`
- `last_custom_prompt_shown_at: Long`
- `last_crash_at: Long` (KEY_LAST_CRASH_AT) — written by `RecentCrashSignal`

`customPromptShownThisSession` is **in-memory** (`@Volatile var`), reset on
process restart by design.

### Counter sources (per Phase 0 decision)
- `tasksCompletedCount`: incremented inside the helper's `onTaskCompleted()`,
  invoked by `MainActivity` collecting `AutomationEventBus.events` filtered
  to `TaskCompleted`. Zero modifications to `TaskRepository.completeTask` —
  the bus already emits the event after a real completion (see
  `TaskRepository.kt:412`). **STOP-6A cleared:** PR #844 unified completion
  path is untouched.
- `firstLaunchAt`: set to `now` if currently 0L during `onAppStart()`,
  invoked by `MainActivity.onCreate`. Hooks AFTER existing onboarding work,
  not before — `MainActivity.onCreate` runs once per process, not once per
  install, so the 0L→now flip is naturally idempotent.
- `sessionCount`: incremented by `onAppStart()` per `MainActivity.onCreate`.
  This counts cold starts, not foreground returns — sufficient for the
  "first 3 sessions" suppression rule.

### Crashlytics 24h-no-fatal proxy (STOP-3B resolution)
Crashlytics SDK is write-only — no query API for "any non-fatal in the last
24h." Replaced with a session-stable proxy:

New `RecentCrashSignal` `@Singleton`:
```kotlin
@Singleton
class RecentCrashSignal @Inject constructor(
    private val prefs: RatingPromptPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun recordCrash() = prefs.setLastCrashAt(clock())
    suspend fun hadRecentCrash(windowMs: Long): Boolean {
        val last = prefs.lastCrashAt().first()
        return last > 0 && (clock() - last) < windowMs
    }
}
```

`recordCrash()` is invoked from:
1. `BugReportViewModel.kt:282` — wraps the existing `recordException(e)`.
2. `LeisureScreen.kt:100` — wraps the existing `recordException(exception)`.
3. `PrismTaskApplication.onCreate` — installs a global
   `Thread.setDefaultUncaughtExceptionHandler` that writes the timestamp
   then chains to the previous handler (Crashlytics's). This catches
   process-fatal crashes from previous sessions: when the next session
   reads `last_crash_at`, the 24h gate trips correctly.

Documented in Phase 4 summary as STOP-3B FIRED with this resolution.

### LOC: ~200 (helper ~70 + prefs ~80 + RecentCrashSignal ~30 + Application
hook ~10 + ~10 wiring at recordException sites)

## § Item 4 — Backend FastAPI endpoint

### Verdict: PROCEED

### Endpoint
`POST /api/v1/feedback/in-app` — added to existing
`backend/app/routers/feedback.py` (already mounted at `/feedback` prefix).

```python
@router.post("/in-app", status_code=status.HTTP_201_CREATED, response_model=InAppFeedbackResponse)
async def submit_in_app_feedback(
    body: InAppFeedbackCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    feedback = InAppFeedback(
        user_id=current_user.id,
        sentiment=body.sentiment,
        rating=body.rating,
        free_text=body.free_text,
        client_timestamp=body.client_timestamp,
    )
    db.add(feedback)
    await db.flush()
    await db.refresh(feedback)
    return InAppFeedbackResponse(success=True, feedback_id=feedback.id)
```

### Auth
`Depends(get_current_user)` — feedback writes are user-scoped. Anonymous
feedback NOT supported in this iteration (operator can revisit post-launch
if F-Droid build needs anonymous channel; out of scope here). **STOP-4B
resolved as "auth required."**

### AI gate
NOT applied. This endpoint does not call AI services; `require_ai_features_enabled`
is unnecessary.

### Pydantic schemas (in `backend/app/schemas/feedback.py`)
```python
class InAppFeedbackCreate(BaseModel):
    sentiment: str = Field(..., description="thumb_up, thumb_down, or rating")
    rating: int | None = Field(default=None, ge=1, le=5)
    free_text: str | None = Field(default=None, max_length=4000)
    client_timestamp: int | None = Field(default=None, description="Client epoch millis when prompt was submitted")

class InAppFeedbackResponse(BaseModel):
    success: bool
    feedback_id: int
```

### Pattern reference
Mirrors `beta_codes.py` user-scoped writer (PR #1125 era) — direct router
write, no service module. The endpoint is small enough that extracting a
service layer adds boilerplate without reducing duplication. Anti-pattern:
service module would be premature abstraction.

### LOC: ~50 (router endpoint + 2 schemas)

## § Item 5 — Alembic migration: `in_app_feedback` table

### Verdict: PROCEED

### Migration number
`026_add_in_app_feedback.py`. Latest is `025`. **STOP-5B cleared.**

### Schema
```python
op.create_table(
    "in_app_feedback",
    sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
    sa.Column("user_id", sa.Integer, sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
    sa.Column("sentiment", sa.String(32), nullable=False),
    sa.Column("rating", sa.SmallInteger, nullable=True),
    sa.Column("free_text", sa.Text, nullable=True),
    sa.Column("client_timestamp", sa.BigInteger, nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
)
op.create_index("ix_in_app_feedback_user_created", "in_app_feedback", ["user_id", "created_at"])
```
- `id` SERIAL PK (default `Integer + autoincrement`).
- `user_id` FK to `users.id` with CASCADE — account deletion sweeps feedback.
- `sentiment` short discriminator (`thumb_up`, `thumb_down`, `rating`).
- `rating` SmallInt nullable — accommodates both thumb (NULL) and 1–5 (set);
  current UI ships thumb-only but future-proofs for 5-star without migration.
- `free_text` Text nullable — bounded at 4000 chars in Pydantic (Postgres
  Text is unbounded; Pydantic gates at the API boundary).
- `client_timestamp` BigInt nullable — when prompt submitted client-side.
- `created_at` server-default `now()`.

### PII concern (STOP-5A)
Free-text is unencrypted plaintext in Postgres. For the pre-launch single-user
window (per CLAUDE.md userMemories), this is low concern: the only writer
is the operator. Post-launch this becomes load-bearing — Phase 4 summary
files this as a follow-on memory candidate ("E2 free-text PII: revisit when
user volume > 1"). Per PR #788 PII audit pattern, current disclosure
(via in-app dialog text) is sufficient for solo pre-launch. Documented in
Phase 4 summary as STOP-5A FIRED with deferred resolution.

### Backfill
None — greenfield table.

### Model class (in `backend/app/models.py`)
```python
class InAppFeedback(Base):
    """In-app rating / sentiment feedback. See migration 026."""
    __tablename__ = "in_app_feedback"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    sentiment = Column(String(32), nullable=False)
    rating = Column(SmallInteger, nullable=True)
    free_text = Column(Text, nullable=True)
    client_timestamp = Column(BigInteger, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
```

### LOC: ~60 (migration ~30 + model ~25)

## § Item 6 — Trigger site wiring

### Verdict: PROCEED — zero modifications to `TaskRepository.completeTask`.

### Approach
`TaskRepository.completeTask` already emits
`automationEventBus.emit(AutomationEvent.TaskCompleted(id))` AFTER the
no-op guard (`TaskRepository.kt:412`). The rating subsystem subscribes to
this bus from `MainActivity`. Architecturally cleaner than injecting the
helper into `TaskRepository`: the repository emits a domain event, the
rating subsystem reacts. **STOP-6A: PR #844 unified completion path
untouched. ✓**

### Wiring in `MainActivity`
Inside `onCreate`, alongside the existing `lifecycleScope.launch` blocks:
```kotlin
@Inject lateinit var ratingPromptTriggerHelper: RatingPromptTriggerHelper
@Inject lateinit var automationEventBus: AutomationEventBus
@Inject lateinit var playReviewLauncher: PlayReviewLauncher

// in onCreate, post-init:
lifecycleScope.launch { ratingPromptTriggerHelper.onAppStart() }
lifecycleScope.launch {
    automationEventBus.events
        .filterIsInstance<AutomationEvent.TaskCompleted>()
        .collect {
            when (ratingPromptTriggerHelper.onTaskCompleted()) {
                RatingPromptDecision.PlayReview -> {
                    val ok = playReviewLauncher.launch(this@MainActivity)
                    if (ok) ratingPromptTriggerHelper.recordPlayReviewShown()
                }
                RatingPromptDecision.CustomPrompt -> {
                    pendingCustomPromptState.value = true
                    ratingPromptTriggerHelper.recordCustomPromptShown()
                }
                RatingPromptDecision.None -> Unit
            }
        }
}
```

`pendingCustomPromptState` is a `MutableStateFlow<Boolean>` collected inside
`setContent { ... }`, gating the `RatingPromptSheet` Composable.

### Why not `TaskRepository`-side
- No new dependency edge into a hot, well-tested repository.
- Activity-scoped collector means prompts only fire while UI is alive
  (correct: don't fire prompts from background/widget completions when the
  user isn't looking).
- Keeps `RatingPromptTriggerHelper` testable without Hilt-injected
  TaskRepository fakes.

### LOC: ~30 (MainActivity wiring + state plumbing into NavGraph host)

## § Bundle-decision

### PR shape
Single bundle PR, branch `claude/add-inapp-review-api-cdQkl`. ✓

### Implementation order (commits)
1. **Backend stack — alembic migration + model:** `026_add_in_app_feedback.py`,
   `models.py` `InAppFeedback` class.
2. **Backend stack — router + schemas:** `routers/feedback.py` `+/in-app`
   endpoint, `schemas/feedback.py` `InAppFeedbackCreate` /
   `InAppFeedbackResponse`.
3. **Backend tests:** `tests/test_feedback.py` extended with 4 tests
   (auth required, thumb_up minimal, thumb_down with text, rating 1–5
   accepted, malformed 422).
4. **Android prefs + helper:** `RatingPromptPreferences`, `RecentCrashSignal`,
   `RatingPromptTriggerHelper`, `RatingPromptDecision` sealed class +
   wire `recordCrash()` at `BugReportViewModel.kt:282` and
   `LeisureScreen.kt:100` + global uncaught handler in
   `PrismTaskApplication`.
5. **Android Play review:** `app/build.gradle.kts` dep + `PlayReviewLauncher`
   wrapper + Retrofit endpoint addition (`PrismTaskApi.submitInAppFeedback`).
6. **Android custom prompt:** `RatingPromptSheet` Composable +
   `RatingPromptViewModel` + `RatingFeedbackRepository`.
7. **Android wiring:** `MainActivity` collector + `pendingCustomPromptState`
   plumbing + sheet host inside `setContent`.
8. **Tests:** unit test for `RatingPromptTriggerHelper` heuristic gates,
   smoke test for `RecentCrashSignal`.
9. **Audit doc:** this file.

### Cross-item dependencies
- Item 5 (migration) precedes Item 4 (router writes to the table).
- Item 4 (backend endpoint) precedes Item 2 (custom prompt's network call).
- Item 3 (helper) precedes Item 6 (trigger wiring uses helper).
- Item 1 (Play wrapper) is independent of Item 2, 4, 5; depends on Item 3
  for trigger gate decision but not for code-level coupling.

### Total LOC estimate
Item 1: ~30 + Item 2: ~180 + Item 3: ~200 + Item 4: ~50 + Item 5: ~60 +
Item 6: ~30 + Tests: ~80 + Audit doc: ~500 (excluded from LOC budget per
operator convention) = **~630 LOC production+tests** vs estimate
~450–650 → drift within band. ✓

### E2 closure verdict
On merge, E2 in-app ratings item: 0 → 1.0. E2 Pre-E.5 verification smoke
item is independent and remains open. E2 not closed entirely after this
PR — Pre-E.5 smoke is the remaining item.

## STOP-condition rollup
| STOP | Status | Resolution |
|---|---|---|
| STOP-A | cleared | All four premises hold; LOC delta noted. |
| STOP-EXISTING-COUNTERS | FIRED | 3/3 absent → operator chose option 1, plumb in this PR (~+50 LOC). |
| STOP-PR1167 | cleared | `hasCompletedOnboarding` flag findable. |
| STOP-1A | cleared | No prior Play review plumbing. |
| STOP-1B | cleared | review:2.0.2 supports min SDK 21; we're at 26. |
| STOP-2A | resolved | ModalBottomSheet (mirrors ProUpsellSheet PR #1219). |
| STOP-3A | cleared | Counter scope already absorbed by STOP-EXISTING-COUNTERS option 1. |
| STOP-3B | FIRED | Crashlytics 24h-no-fatal not queryable; replaced with `last_crash_at` proxy via existing `recordException` sites + Application uncaught handler. |
| STOP-4A | cleared | `beta_codes.py` is the user-scoped writer pattern reference. |
| STOP-4B | resolved | Auth required (`get_current_user`); anonymous channel deferred. |
| STOP-5A | FIRED | PII free-text in Postgres; pre-launch low-risk per userMemories; revisit memory candidate filed for post-launch. |
| STOP-5B | cleared | Migration 026 next sequential. |
| STOP-6A | cleared | TaskRepository.completeTask untouched; bus-subscribe pattern instead. |

## Anti-pattern adherence
1. ✓ Postgres backend (not Firestore).
2. ✓ TaskRepository.completeTask zero modifications.
3. ✓ No new feature flags; always-on.
4. ✓ Build constants (no remote config).
5. ✓ No admin triage UI.
6. ✓ No client-side Play rate-limit enforcement.
7. ✓ Free-text Postgres-only; never logged to Crashlytics/Analytics.
8. ✓ STOP-EXISTING-COUNTERS surfaced.
9. n/a (no full-text str_replace planned).
10. ✓ Memory candidates flagged in Phase 4 summary, not auto-memorized.
11. ✓ Phase 4 summary committed in chat output.
12. ✓ Re-verify with `git log --oneline -10` before push.
13. ✓ Pre-E.5 verification smoke NOT bundled.
14. ✓ No Play review trigger tuning logic.
15. ✓ Audit doc cap respected (~500 lines).
16. ✓ Backend endpoint test coverage included.
17. ✓ Free-text never log-channeled.
18. n/a (Phase 1 surfaces no blockers; SHIPPING).

## Phase F GREEN-GO impact
NEUTRAL → POSITIVE. Rating velocity is a launch traction lever; the custom
free-text channel surfaces UX friction early. No regression vectors
identified.

## Memory candidates (wait-for-third rule)
- "Bus-subscribe pattern (AutomationEventBus.events.filterIsInstance) for
  Activity-scoped reactions to repository events" — data point 1. Wait for
  third sighting before memorizing.
- "Pre-launch single-user PII threshold: free-text in Postgres without
  encryption is acceptable when only writer is operator; revisit when user
  volume > 1" — data point 1.
- "Crashlytics SDK has no recent-crash query; use `last_crash_at` pref
  proxy" — data point 1.
