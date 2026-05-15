# Waitlist → Firestore (docs/index.html)

**Date:** 2026-05-15
**Branch:** `worktree-feat-docs-waitlist-firestore`

## Goal

Replace the formsubmit.co relay used by the `docs/index.html` waitlist form
with a direct write to a Firestore `waitlist` collection in the existing
`averytask-50dc5` Firebase project, so signups land in a queryable list
that can be inspected and exported from the Firebase console.

## Non-goals

- App Check, captcha, or any anti-abuse beyond rules-level shape validation.
- Admin UI inside the app — the Firebase console is the admin UI.
- Email normalization / deduplication beyond what already exists in
  `localStorage` mirroring on the same browser.
- Notifications on each signup. (Drop with formsubmit.co. Configure later via
  a Firestore-triggered Cloud Function or console alert if you miss them.)

## Storage shape

Top-level collection `waitlist`. One auto-id document per signup:

```
waitlist/{autoId}
  email:     string             (required, validated by rules)
  source:    'docs.homepage'    (required, string ≤ 64 chars)
  userAgent: string             (optional, ≤ 512 chars — for bot triage)
  createdAt: serverTimestamp()  (required, must equal request.time)
```

No `users/{uid}/…` nesting — waitlist signups happen before sign-in, so they
are intentionally not user-scoped.

## Firestore rules (production)

The repo's `firestore.rules` is an emulator stub (see the file's own header
comment) — production rules live in the Firebase console and are NOT in this
repo. To enable this feature, paste the rule below into
`https://console.firebase.google.com/project/averytask-50dc5/firestore/rules`
underneath the existing rule blocks:

```
match /waitlist/{docId} {
  allow create: if
        request.resource.data.keys().hasOnly(['email', 'source', 'userAgent', 'createdAt'])
     && request.resource.data.email is string
     && request.resource.data.email.size() > 3
     && request.resource.data.email.size() <= 320
     && request.resource.data.email.matches('.+@.+\\..+')
     && request.resource.data.source is string
     && request.resource.data.source.size() <= 64
     && request.resource.data.userAgent is string
     && request.resource.data.userAgent.size() <= 512
     && request.resource.data.createdAt == request.time;
  allow read, update, delete: if false;
}
```

Threat model: same as the existing formsubmit.co relay — an unauthenticated
public endpoint that accepts an email. The rule narrows the abuse surface to
"a string that looks like an email," denies all reads (no enumeration), and
denies all mutations after the fact.

## Client changes

### `docs/index.html`

Add the Firebase modular SDK via ESM CDN once, before `homepage.jsx` loads,
and expose `firestore` + `addDoc` + `collection` + `serverTimestamp` on
`window` so the babel-standalone-transformed `homepage.jsx` can use them
without an `import` statement (the page has no bundler):

```html
<script type="module">
  import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js';
  import {
    getFirestore, collection, addDoc, serverTimestamp
  } from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js';

  const app = initializeApp({
    apiKey: "AIzaSyCr8PY_DJh00LmpW8nS3_fnsqttlUr__3g",
    authDomain: "averytask-50dc5.firebaseapp.com",
    projectId: "averytask-50dc5",
    storageBucket: "averytask-50dc5.firebasestorage.app",
    messagingSenderId: "403186103462",
    appId: "1:403186103462:web:70dccb94955d5d2647b067",
  });
  window.__prismWaitlist = {
    db: getFirestore(app), collection, addDoc, serverTimestamp,
  };
</script>
```

These are the same public web keys already inlined in
`web/src/lib/firebase.ts` — Firebase web API keys are not secrets, security
is enforced by Firestore rules.

### `docs/homepage.jsx` — `Hero()` / `handleWaitlistSubmit`

- Delete the `WAITLIST_ENDPOINT` constant and its formsubmit.co comment block.
- Keep the `prismtask_waitlist_local` localStorage mirror exactly as-is — it
  is a cheap belt-and-suspenders backup.
- Replace the `fetch(WAITLIST_ENDPOINT, …)` block with:

  ```js
  const { db, collection, addDoc, serverTimestamp } = window.__prismWaitlist;
  await addDoc(collection(db, 'waitlist'), {
    email,
    source: 'docs.homepage',
    userAgent: (navigator.userAgent || '').slice(0, 512),
    createdAt: serverTimestamp(),
  });
  setStatus('submitted');
  ```

- The existing `idle | sending | submitted | error` state machine and all
  visible button copy stay identical; only the underlying transport changes.

## Rollout

1. Merge this PR. (GitHub Pages picks up `docs/` automatically — no build.)
2. Paste the rule block above into the Firebase console. **Until that is
   done, every submit will fail and the user will see "Saved — try again to
   confirm" while the entry sits in localStorage only.**
3. Spot-check by submitting once and confirming a new doc appears in the
   Firestore console.

## Risks

- **Rules forgotten:** signups silently localStorage-only until the console
  rule is added. Mitigated by making rule deploy step #2 of rollout above
  and by the `error` UI state already covering this case.
- **Abuse:** anyone can write any plausible-looking email. Bounded by rules
  (no reads, shape-validated, no mutations). If abused, add App Check.
- **GitHub Pages cache:** docs HTML is cached aggressively. First visit
  after the deploy may serve the old formsubmit.co code for a few minutes.
  Acceptable.
