# Email / Password Sign-Up Option Audit

**Date**: 2026-05-06
**Scope**: User asked: *"There should be an option to make an account without
signing in through Google."* Audit the current authentication surface and
plan the smallest correct change to add an email + password account creation
path alongside the existing Google Sign-In.
**Branch**: `claude/add-email-signup-option-ifQ9P`

## Phase 1 — Audit

### Premise verification (load-bearing)

**The premise is true.** The app's only path to *create* an account is Google
Sign-In via Credential Manager:

- `data/remote/AuthManager.kt:75` — only `signInWithGoogle(idToken)` exists;
  no email/password methods.
- `ui/screens/auth/AuthScreen.kt:235` — only "Sign In with Google" button.
- `ui/screens/auth/AuthScreen.kt:250` — "Continue Without Account" *skips*
  account creation entirely (local-only mode); it does not create an account.
- `ui/screens/onboarding/OnboardingScreen.kt:357` — welcome page's "Already
  have an account? Sign in" link is also Google-only.

**Already-present prerequisites (no work needed):**

- `firebase-auth-ktx` is already a dep — `app/build.gradle.kts:328`.
- All Firestore sync paths are keyed by Firebase UID, not provider —
  `data/remote/SyncService.kt` uses `authManager.userId`. Email/password
  users will hit the exact same `/users/{uid}/...` paths with no schema
  changes (verified by inspection of the UID-only `userId` getter at
  `AuthManager.kt:55`).
- `AuthState.SignedIn` / deletion-guard / post-sign-in sync (`fullSync` →
  `initialUpload` → `startRealtimeListeners`) is provider-agnostic — once
  Firebase returns a `FirebaseUser`, the rest of the pipeline doesn't care
  *how* the user signed in (`AuthViewModel.kt:101–121, 221–236`).

### Items

#### 1. `AuthManager` — add email/password sign-up + sign-in methods (PROCEED)

- **Findings.** Currently exposes only `signInWithGoogle(idToken)` at
  `AuthManager.kt:75`. Both `FirebaseAuth.createUserWithEmailAndPassword`
  and `signInWithEmailAndPassword` are available on the existing
  `firebase-auth-ktx` dependency.
- **Risk**: GREEN. Same `FirebaseUser` return type, same UID semantics; no
  branching needed elsewhere in the app.
- **Recommendation**: Add two suspend methods on `AuthManager`:
  - `signUpWithEmail(email, password): Result<FirebaseUser>`
  - `signInWithEmail(email, password): Result<FirebaseUser>`
  Mirror the `Result`/`IllegalStateException("Firebase Auth not available")`
  pattern from `signInWithGoogle` (`AuthManager.kt:76`) so the null-Firebase
  fallback established by `AuthManagerSafetyTest` keeps holding.

#### 2. `AuthScreen` UI — add email/password form (PROCEED)

- **Findings.** `AuthScreen.kt` renders only the Google button + skip link.
  An orphaned `BackendAuthDialog` composable in
  `ui/components/settings/SettingsDialogs.kt:181` already shows the desired
  form pattern (email + password + "Create Account / Sign In" toggle) —
  styled to match Material 3 conventions, with a `PasswordVisualTransformation`
  and disabled-while-loading guard. This is reference shape, not reusable
  composable: it's an `AlertDialog` and lives in a different feature
  package; lift the structure, not the file.
- **Risk**: GREEN. Standard form, no new dependencies. Existing
  `AuthState.{Loading,Error}` already handles the spinner / error text.
- **Recommendation**: Below the Google button (and above
  "Continue Without Account") add a collapsed "Use Email Instead" section
  that expands to show email + password fields and a Create Account / Sign
  In toggle. Use Title Capitalization per CLAUDE.md repo conventions.
  Wire to two new viewmodel methods (item 3).

#### 3. `AuthViewModel` — wire `onEmailSignUp` + `onEmailSignIn` (PROCEED)

- **Findings.** `onGoogleSignIn(idToken)` at `AuthViewModel.kt:67` is the
  template: set `Loading` → call `AuthManager` → on failure set
  `AuthState.Error`, on success delegate to `handlePostAuthDeletionGuard()`
  which already drives the rest of the post-sign-in pipeline.
- **Risk**: GREEN.
- **Recommendation**: Add `onEmailSignUp(email, password)` and
  `onEmailSignIn(email, password)` that follow the same shape as
  `onGoogleSignIn`. No new state, no new branches downstream.

#### 4. Firebase Console: enable Email/Password provider (DEFERRED — operator action)

- **Findings.** `app/google-services.json:1–48` is the OAuth client
  registration only; provider enablement lives in the Firebase Console
  (Authentication → Sign-in method). Code-only changes will compile and
  ship a UI that prompts for an email/password — but
  `createUserWithEmailAndPassword` will throw
  `FirebaseAuthException("OPERATION_NOT_ALLOWED")` until the provider is
  toggled on for project `averytask-50dc5`.
- **Risk**: YELLOW (release-blocking, but not code-blocking).
- **Recommendation**: Note this in Phase 3 as a release prerequisite.
  No code change. Caller-side error handling already surfaces the
  Firebase error message via `AuthState.Error`, which means a missed
  toggle becomes a user-visible "Sign-up failed: OPERATION_NOT_ALLOWED"
  rather than a crash — acceptable failure mode for a pre-flight check.

#### 5. Email validation + password strength (PROCEED, minimal)

- **Findings.** No existing email validators in the codebase
  (`grep -rn "EMAIL_ADDRESS\|isValidEmail" app/src/main` returns nothing).
  Firebase's `createUserWithEmailAndPassword` enforces the 6-char password
  minimum server-side and rejects malformed emails with
  `FirebaseAuthInvalidCredentialsException`.
- **Risk**: GREEN. Don't reinvent password rules — Firebase's defaults are
  fine for v1.
- **Recommendation**:
  - Email: client-side `Patterns.EMAIL_ADDRESS` regex match (Android
    framework constant) before calling Firebase, to avoid round-tripping
    obvious typos.
  - Password: rely on Firebase's 6-char minimum; show a hint
    ("At least 6 characters") in the UI but do not duplicate the check.
  - Surface Firebase exception messages via the existing
    `AuthState.Error(message)` channel — `AuthViewModel.kt:38`.

#### 6. Tests (PROCEED, minimal)

- **Findings.** Auth coverage today is `AuthManagerSafetyTest.kt` (FirebaseAuth
  init safety + null userId handling) plus deletion-flow tests. No
  end-to-end Firebase mocking pattern is established. Robolectric is
  available (`testImplementation("org.robolectric:robolectric:4.13")`).
- **Risk**: GREEN. Mocking `FirebaseAuth` end-to-end is high-cost / low-value
  given the existing `AuthManagerSafetyTest` already proves the
  null-Firebase fallback pattern works.
- **Recommendation**:
  - Extend `AuthManagerSafetyTest` to assert the new email methods also
    return `Result.failure(IllegalStateException)` when `FirebaseAuth.getInstance`
    threw at construction. This is the load-bearing safety property — that
    a misconfigured Firebase doesn't NPE on email sign-in either.
  - Skip ViewModel-level happy-path tests; pattern parity with the existing
    `onGoogleSignIn` flow is sufficient and the post-sign-in pipeline is
    already covered through `runPostSignInSync`.

#### 7. Onboarding "Already have an account?" link (DEFERRED)

- **Findings.** `OnboardingScreen.kt:342–371` is a Google-only sign-in
  link. Adding email sign-in here is additional UX surface (a second
  button or a routed-to mini-screen) that the user request did not
  specifically ask for; the `AuthScreen` is the canonical sign-in entry
  point and is reachable from settings post-onboarding.
- **Risk**: GREEN (deferral is safe).
- **Recommendation**: Defer to a follow-up. Add a one-line comment in
  the audit doc's improvement table.

#### 8. Orphaned `BackendAuthDialog` (GREEN, dead code, out of scope)

- **Findings.** `ui/components/settings/SettingsDialogs.kt:181` defines a
  `BackendAuthDialog` with email/password fields and `onLogin` /
  `onRegister` callbacks. `grep -rn "BackendAuthDialog" app/src/main`
  returns one hit — its own definition. No callers.
- **Risk**: GREEN. Not connected; not affected by this work.
- **Recommendation**: Out of scope. Flag as anti-pattern (dead code) for
  a future cleanup audit. Don't delete in this PR — keeping the diff
  surface minimal protects the review.

#### 9. Email verification (DEFERRED)

- **Findings.** `FirebaseUser.sendEmailVerification()` is the standard
  next step after `createUserWithEmailAndPassword`, but most apps ship
  email/password v1 without forcing verification.
- **Risk**: YELLOW (security posture is weaker than Google OAuth's verified
  email by default).
- **Recommendation**: Defer. Document as a follow-up. The sync model
  doesn't depend on verified email (UID is the key), so unverified
  email/password accounts work end-to-end out of the box. Add a
  `// TODO(email-verification)` next to the sign-up call so the deferred
  work is grep-able.

#### 10. Password reset flow (DEFERRED)

- **Findings.** `FirebaseAuth.sendPasswordResetEmail(email)` is the
  one-line API; the UI is a separate dialog/screen.
- **Risk**: YELLOW (production-required, but can ship without on day 1
  as long as the operator is aware).
- **Recommendation**: Defer to a follow-up PR. Will need its own audit
  for UI placement (likely a "Forgot password?" link on the sign-in
  form).

### Improvement table — sorted by wall-clock-savings ÷ implementation-cost

| Rank | Item | Recommendation | Cost | Value |
|------|------|----------------|------|-------|
| 1 | `AuthManager` email/password methods (item 1) | PROCEED | ~30 LOC | Unblocks the entire feature |
| 2 | `AuthViewModel` email handlers (item 3) | PROCEED | ~40 LOC | Trivial; mirrors Google flow |
| 3 | `AuthScreen` form UI (item 2) | PROCEED | ~80 LOC | The user-visible deliverable |
| 4 | Email validator + password hint (item 5) | PROCEED | ~10 LOC | Better UX, prevents obvious errors |
| 5 | Test extension (item 6) | PROCEED | ~20 LOC | Proves null-Firebase safety holds |
| 6 | Firebase Console toggle (item 4) | DEFER (operator) | 0 LOC | Release prerequisite — flag in Phase 3 |
| 7 | Onboarding email link (item 7) | DEFER | — | Out of scope for this request |
| 8 | Email verification (item 9) | DEFER | — | Standard v1 omission |
| 9 | Password reset (item 10) | DEFER | — | Standard v1 omission |

### Anti-patterns flagged (no fix this audit)

- **Orphaned `BackendAuthDialog`** at
  `ui/components/settings/SettingsDialogs.kt:181`. Dead code with no
  callers; misleading because its name suggests it's a real auth path.
  Future cleanup audit should delete it (or wire it up if a backend
  login was actually intended).
- **`OnboardingScreen` welcome-page sign-in is duplicate Google flow**
  (`OnboardingScreen.kt:342–371` vs `AuthScreen.kt:144–230`). Both screens
  re-implement the Credential Manager dance. Not in scope here, but
  worth a future "extract `GoogleSignInLauncher` composable" refactor.

### Phase 1 plan summary

Land items 1–3 + 5–6 in **one PR** on `claude/add-email-signup-option-ifQ9P`
(they form a single coherent "add email/password auth" scope; per the
audit-first fan-out rule, bundle when it's one scope). Item 4 (Firebase
Console toggle) is operator action surfaced in Phase 3. Items 7, 9, 10
are documented as deferred follow-ups.

## Phase 3 — Bundle summary

**PR**: [#1155](https://github.com/averycorp/prismTask/pull/1155) — `feat(auth):
add email/password account option alongside Google`. Bundled items 1, 2, 3, 5, 6
in a single coherent "add email/password auth" scope.

**Shipped:**

- Item 1 — `AuthManager.signUpWithEmail` / `signInWithEmail` mirroring
  `signInWithGoogle` shape (Result + null-Firebase fallback).
- Item 2 — `AuthScreen` collapsible "Use Email Instead" form between the
  Google button and "Continue Without Account".
- Item 3 — `AuthViewModel.onEmailSignUp` / `onEmailSignIn` route through
  the same `handlePostAuthDeletionGuard` → `runPostSignInSync` pipeline.
- Item 5 — Client-side `Patterns.EMAIL_ADDRESS` check; "At Least 6
  Characters" supporting text; password rule itself left to Firebase
  server-side.
- Item 6 — `AuthManagerSafetyTest` static-text scan extended to assert
  the new methods early-return `Result.failure(IllegalStateException)`
  when `auth` is null.

**Operator action (RELEASE-BLOCKING, not code-blocking):**

- Enable **Email/Password** provider in the Firebase Console under
  *Authentication → Sign-in method* for project `averytask-50dc5`.
  Without this, `createUserWithEmailAndPassword` returns
  `OPERATION_NOT_ALLOWED`, which surfaces via `AuthState.Error` (visible
  failure, not a crash). The PR description repeats this; do not merge
  to a release branch until the toggle is on.

**Deferred / not shipped:**

- Item 4 — Firebase Console provider toggle (operator action, see above).
- Item 7 — Onboarding "Already have an account?" still Google-only. The
  primary `AuthScreen` is reachable from settings post-onboarding, so
  this is non-urgent.
- Item 9 — Email verification (`sendEmailVerification`). `TODO(email-verification)`
  comment lives at `AuthViewModel.onEmailSignUp`. Sync model is UID-keyed
  so unverified accounts work end-to-end.
- Item 10 — Password reset (`sendPasswordResetEmail`). Needs its own UI
  surface; standard v1 omission.

**Anti-patterns flagged (not fixed):**

- Orphaned `BackendAuthDialog` at `ui/components/settings/SettingsDialogs.kt:181`
  has no callers — out of scope cleanup.
- Duplicated Credential Manager dance in `OnboardingScreen.kt:342–371`
  vs `AuthScreen.kt:144–230` — extract-composable refactor for a
  separate audit.

**Memory entry candidates:** None. The pattern (extend an existing
`AuthManager` Result-returning method to a new provider; route through
the existing `handlePostAuthDeletionGuard`) is sufficiently obvious from
reading the code; no surprise to capture.

**Schedule for next audit:** When the operator wants to ship password
reset + email verification (items 9, 10), run a follow-up audit covering
both — they share the "Forgot password?" / "Verify your email" UI surface
on `AuthScreen`.

## Phase 4 — Claude Chat handoff block

```markdown
# PrismTask — Email/Password Sign-Up Audit Handoff

## Scope
PrismTask Android (Kotlin + Compose) only supports Google Sign-In for account
creation today. Audit + ship a non-Google email/password path so users who
don't have or don't want to use a Google account can still create an account
and get cloud sync.

## Verdicts
| Item | Verdict | One-line finding |
|------|---------|------------------|
| 1. `AuthManager` email methods | GREEN — PROCEED | Mirror `signInWithGoogle` shape; same Result/null-Firebase fallback. |
| 2. `AuthScreen` form UI | GREEN — PROCEED | Collapsible "Use Email Instead" section keeps Google primary. |
| 3. `AuthViewModel` handlers | GREEN — PROCEED | Routes through existing `handlePostAuthDeletionGuard` pipeline. |
| 4. Firebase Console toggle | YELLOW — DEFERRED (operator) | Email/Password provider must be enabled in Console for project `averytask-50dc5`. |
| 5. Validation | GREEN — PROCEED | `Patterns.EMAIL_ADDRESS` client-side; Firebase 6-char minimum server-side. |
| 6. Tests | GREEN — PROCEED | Extend static-text safety scan to new methods. |
| 7. Onboarding email link | GREEN — DEFERRED | Out of scope; `AuthScreen` reachable from settings. |
| 8. Orphaned `BackendAuthDialog` | GREEN — flagged anti-pattern | Dead code at `SettingsDialogs.kt:181`; not the target. |
| 9. Email verification | YELLOW — DEFERRED | `TODO(email-verification)` left in code. |
| 10. Password reset | YELLOW — DEFERRED | Needs its own UI surface. |

## Shipped
- **PR #1155** — `feat(auth): add email/password account option alongside Google`.
  Bundles items 1, 2, 3, 5, 6. Branch `claude/add-email-signup-option-ifQ9P`.

## Deferred / stopped
- **Item 4 (Firebase Console)** — operator must enable Email/Password
  provider before release. Code ships compiled but sign-up surfaces
  `OPERATION_NOT_ALLOWED` until toggled.
- **Items 7, 9, 10** — out of scope for this user request; tracked as
  follow-ups in the audit doc.

## Non-obvious findings
- All Firestore sync paths are keyed by **Firebase UID**, not provider —
  email/password users hit `/users/{uid}/...` exactly like Google users
  with zero schema or branching changes downstream. (`SyncService` +
  `AuthManager.userId`.)
- `BackendAuthDialog` at `ui/components/settings/SettingsDialogs.kt:181`
  is **orphaned dead code** with no callers, despite having a fully
  styled email/password UI. Reference shape only — do not wire it up
  in the auth screen; the production form lives in `AuthScreen.kt`.
- The "Continue Without Account" button on `AuthScreen.kt:250` is a
  **local-only mode toggle**, not an account creation path; it's
  separate from this work.
- `firebase-auth-ktx` was already a dep, so no new dependency was needed —
  email/password just requires calling existing methods on
  `FirebaseAuth.getInstance()`.

## Open questions
- Does the operator want **email verification** gating cloud sync before
  launch (item 9)? Currently unverified email accounts sync the same as
  Google accounts because UID is the key.
- Should the **onboarding welcome page** "Already have an account?" link
  also offer email sign-in (item 7), or stay Google-only as the primary
  CTA on that surface?
```

