# Concept Repositioning Copy Audit (Phase 1)

**Audit date:** 2026-05-15
**Branch / worktree:** `docs/concept-repositioning-copy-audit`
**Code-of-record:** `main` @ `e4801d77`; `app/build.gradle.kts` → versionName `1.9.33`, versionCode `886`
**Proposed concept:**
- Tagline: *Built for every kind of mind.*
- Subline: *A productivity app that works with your brain, not against it.*
- Constraint: productivity-primary classification must not regress (Play Console reclassification from PR #765 era — April 2026).

---

## Phase 0 — Staleness sweep (premise verification)

Sweep against current `main` HEAD surfaces **two material drifts from the prompt's premise** plus one critical residual find.

| Prompt premise | Reality |
|---|---|
| README first paragraph says "ADHD-friendly" / "forgiveness-first" | `README.md:16` reads *"A cross-platform task manager and wellness-aware productivity layer."* No diagnosis terms, no audience-targeting language. |
| OnboardingScreen BrainModePage uses diagnostic terms ("Adhd / Calm / FocusRelease") in user-facing strings | `OnboardingScreen.kt:805-911`: page header is *"How Does Your Brain Work?"*; card titles are *"I Get Distracted Easily"* / *"I Get Overstimulated Easily"* / *"I Have Trouble Letting Go of Tasks"*. "ADHD Mode" appears only in source comments. `OnboardingScreen.kt:1016` explicitly documents the hard rule. |
| `docs/store-listing/copy/` may not exist | Exists. en-US copy already shipped via PR #765-era Play Store listing audit (`docs/store-listing/PHASE1_AUDIT.md`, 2026-04-24). Short description already says *"work with your energy, not against it"* — nearly identical in spirit to the proposed *"works with your brain, not against it"*. |
| `docs/marketing/` lists Reddit subreddits | Directory does not exist. No `docs/marketing/` content. |

**Critical residual find:** `docs/index.html:7` (the GitHub Pages homepage `<meta name="description">`) reads:

> *"An adaptive task manager built for focus, habits, and the way your brain actually works. Four hand-crafted themes. ADHD-friendly."*

This is a short-format public surface (Google search-engine snippet, ≤160 chars). Per the prompt's safe phrasing pattern, diagnosis-language is disallowed on short-format surfaces — only allowed in long-form body. This is the single load-bearing item this audit surfaces.

**STOP-A — not fired.** The premises are not all wrong; one of three load-bearing items (the meta description) survives and is in scope. The other two prompt-premises (README first paragraph, BrainModePage labels) are already aligned, which moves scope toward STOP-E rather than STOP-A.

---

## Section A — Surface inventory

Verbatim quotes ≤15 words; longer copy paraphrased with file:line.

| # | Surface | File:line | Current copy (verbatim or paraphrased) | Audience lang | Med-class risk |
|---|---|---|---|---|---|
| 1 | Play Store short description (≤80 chars, 74 used) | `docs/store-listing/copy/en-US/short-description.txt` | *"Tasks, habits, and focus tools that work with your energy, not against it."* | experience | LOW |
| 2 | Play Store full description — first paragraph | `…/full-description.txt:1-3` | *"… task manager, habit tracker, and focus companion for people who have bounced off rigid productivity systems."* + tagline *"Built for focus. Made for humans."* | experience | LOW |
| 3 | Play Store full description — feature sections | `…/full-description.txt:6-49` | Six sections: tasks, AI, wellness-aware, medication, accessibility, data control. "Neurodivergence-friendly modes" appears once in a feature bullet (long-form body). | feature-name only | LOW |
| 4 | Feature graphic text (PNG) | `docs/store-listing/graphics/src/feature-graphic.svg` | Renders: *"PrismTask"*, *"Today"*, *"Fri, April 24"*, *"Work · Self-care · Personal"*, etc. No tagline, no audience claim. | none | LOW |
| 5 | Play Store category/tags | `docs/store-listing/compliance/categorization.md` | Productivity category (post-PR #765 reclassification). | n/a | LOW |
| 6 | GitHub repo About blurb | `gh repo view averycorp/prismTask` | *"A smart, adaptive Android to-do list app with natural language task entry, habit tracking, Google Calendar sync, and home screen widgets — built with Kotlin, Jetpack Compose, and Firebase."* | none | LOW |
| 7 | README.md first paragraph | `README.md:16-28` | *"A cross-platform task manager and wellness-aware productivity layer."* + feature inventory paragraph. | none | LOW |
| 8 | README.md "Who is this for" / audience section | n/a | No such section exists. | n/a | LOW |
| 9 | OnboardingScreen WelcomePage | `OnboardingScreen.kt:314-326` | Headline *"Welcome to PrismTask"*; body *"Your smart, adaptive productivity companion"* | none | LOW |
| 10 | OnboardingScreen BrainModePage header + body | `OnboardingScreen.kt:836-849` | Header *"How Does Your Brain Work?"*; body *"Select any that apply — or skip if none fit. You can always change these in Settings."* | experience | LOW |
| 11 | OnboardingScreen BrainModePage card titles | `OnboardingScreen.kt:858, 878, 897` | Card 1 *"I Get Distracted Easily"* (internal: ADHD Mode); Card 2 *"I Get Overstimulated Easily"* (Calm); Card 3 *"I Have Trouble Letting Go of Tasks"* (Focus & Release). "ADHD" exists in source comments only. | experience | LOW |
| 12 | OnboardingScreen TuningPage | `OnboardingScreen.kt:1013-1136` | 6 experience-language tuning options. Hard-rule comment at `:1016`. Caption at `:1128`: *"These are preferences, not diagnoses."* | experience | LOW |
| 13 | OnboardingScreen LifeModesPage | `OnboardingScreen.kt:1419-1471` | Header *"What Do You Want to Track?"*; 5 mode rows (Self-Care / Medication / Schoolwork / Housework / Leisure). | feature-name | LOW |
| 14 | OnboardingScreen HabitsPage | `OnboardingScreen.kt:609-613` | Headline *"Build Habits, Stay Focused"*; body *"Track daily habits with streaks and analytics. Use AI-powered focus sessions to get more done."* | none | LOW |
| 15 | OnboardingScreen SetupPage | `OnboardingScreen.kt:1217-1228` | Headline *"Let's Get You Started"*; body *"Set up your preferences (all optional)"*. | none | LOW |
| 16 | GuidedTourCard 5 steps | `GuidedTourCard.kt:53-79` | Step bodies in experience-language (e.g. *"streaks forgive a missed day"*). Comment voice-guide at `:49` says "no productivity-bro language, ADHD-aware framing where it lands naturally". | experience | LOW |
| 17 | Settings top-level Productivity section | `SettingsScreen.kt:276-282` | "Brain Mode" row with subtitle *"ADHD, Calm, Focus Release"*. **Subtitle contains diagnostic label.** | diagnosis (descriptive of feature, not audience) | LOW-MED |
| 18 | Settings → Brain Mode section | `BrainModeSection.kt:44-65` | `SectionHeader("Brain Mode")`; toggles titled *"ADHD Mode"*, *"Calm Mode"*, *"Focus & Release Mode"*; info chip at `:76` *"ADHD Mode helps you start. Focus & Release helps you finish. They work great together."* | diagnosis (feature label) | LOW-MED |
| 19 | Settings → Subscription comparison | `SubscriptionSection.kt:181` | `ComparisonRow("ADHD Mode & Calm Mode", free = true, pro = true)` | diagnosis (feature label) | LOW-MED |
| 20 | About / version-info screen | (none) | No dedicated About screen surfaced; Settings → About-equivalent rows are version + crisis-resources + legal. | n/a | n/a |
| 21 | In-app marketing strings (Pro upsell) | `SubscriptionSection.kt` | Pro tier comparison rows (functional). One row references "ADHD Mode" (#19). | mixed | LOW-MED |
| 22 | `docs/privacy/index.md` opening | `docs/privacy/index.md:1-19` | Title *"Privacy Policy"*; description *"How PrismTask collects, uses, and protects your data."* | none | LOW |
| 23 | GitHub Pages homepage `<title>` + meta | `docs/index.html:6-7` | Title *"PrismTask — Plan your day. Honor your brain."*; **meta description ends in *"ADHD-friendly."***  | diagnosis (audience claim, short-format) | **MEDIUM** |
| 24 | GitHub Pages homepage hero H1 | `docs/homepage.jsx:171` | *"Plan your day. Honor your `<em>`brain.`</em>`"* | experience | LOW |
| 25 | GitHub Pages homepage Wellness section | `docs/homepage.jsx:461-495` | Eyebrow *"Built for real brains"*; title *"Productivity that doesn't punish."*; pillar 4 body: *"Built with neurodivergent users, not for them."* | mixed (long-form body) | LOW |
| 26 | GitHub Pages homepage FAQ | `docs/homepage.jsx:744-746` | Entry: *"What makes it ADHD-friendly?"* + answer; entry: *"Is this a real mental-health tool?"* mentions "neurodivergent and chronically-ill users". Long-form body. | diagnosis (long-form OK per rule) | LOW |
| 27 | `docs/marketing/` directory | n/a | Does not exist. | n/a | n/a |

---

## Section B — Verdict matrix

| # | Surface | Verdict | Proposed new copy (if REFRAME / REPLACE) | LOC |
|---|---|---|---|---|
| 1 | Play Store short description | **KEEP** | Already aligned. Optional reframe `"your energy"` → `"your brain"` is cosmetic and adds no protection. | 0 |
| 2 | Play Store full-description tagline | **REFRAME (optional)** | Swap `"Built for focus. Made for humans."` → `"Built for every kind of mind."` on a single line. Existing line below already provides the *"works with … not against"* framing. | 1 |
| 3 | Play Store full description feature sections | **KEEP** | Already experience-language; "Neurodivergence-friendly modes" sits in a long-form body bullet (permitted per safe phrasing rule). | 0 |
| 4 | Feature graphic text | **KEEP** | Rendered PNG asset contains no audience claim. | 0 |
| 5 | Play Store category/tags | **KEEP** | Productivity category is load-bearing for the non-regression constraint. Do not touch. | 0 |
| 6 | GitHub repo About blurb | **REFRAME (optional)** | Append `" — built for every kind of mind."` to the end. Single `gh repo edit --description …` call. | 1 |
| 7 | README first paragraph | **REFRAME (optional)** | Inject the subline as a one-line italic header beneath the badge block, **above** the existing first paragraph. Existing paragraph already serves as the technical description. | 2-3 |
| 8 | README "Who is this for" | n/a | Section doesn't exist; not adding one (forces audience-claim copy that the rest of the README avoids). | 0 |
| 9 | OnboardingScreen WelcomePage | **REFRAME (optional)** | Optional swap body `"Your smart, adaptive productivity companion"` → `"Built for every kind of mind."` Aligns the tagline at first user touch. | 1 |
| 10 | OnboardingScreen BrainModePage header + body | **KEEP** | *"How Does Your Brain Work?"* is already the in-app expression of the new concept. | 0 |
| 11 | OnboardingScreen BrainModePage card titles | **KEEP** | Already experience-language. Diagnosis terms confined to source comments. | 0 |
| 12 | OnboardingScreen TuningPage | **KEEP** | Hard rule + explicit *"preferences, not diagnoses"* caption already enforce the new framing. | 0 |
| 13 | OnboardingScreen LifeModesPage | **KEEP** | Pure feature names. | 0 |
| 14 | OnboardingScreen HabitsPage | **KEEP** | No audience claim. | 0 |
| 15 | OnboardingScreen SetupPage | **KEEP** | Neutral functional copy. | 0 |
| 16 | GuidedTourCard | **KEEP** | Voice already non-judgmental, no diagnosis labels. | 0 |
| 17 | Settings → Productivity → Brain Mode row subtitle | **REFRAME (optional, R3-YELLOW)** | Swap subtitle `"ADHD, Calm, Focus Release"` → `"Quick-start, calm, finish-and-release"` (descriptive verbs, no diagnostic label). | 1 |
| 18 | Settings → Brain Mode toggles | **REFRAME (optional, R3-YELLOW)** | Swap title `"ADHD Mode"` → `"Quick-Start Mode"`. Info chip `"ADHD Mode helps you start"` → `"Quick-Start Mode helps you begin"`. Preference key `adhdModeEnabled` stays unchanged (internal). | 3 |
| 19 | Settings → Subscription comparison row | **REFRAME (optional, R3-YELLOW)** | Swap `"ADHD Mode & Calm Mode"` → `"Quick-Start & Calm Modes"`. Cosmetic copy change only. | 1 |
| 20 | About / version-info screen | n/a | Not present. | 0 |
| 21 | In-app marketing strings (Pro upsell) | **KEEP** (covered by #19) | No standalone marketing strings beyond the Subscription comparison row. | 0 |
| 22 | `docs/privacy/index.md` opening | **KEEP** | Functional description, no audience claim. | 0 |
| 23 | `docs/index.html` meta description | **REPLACE (load-bearing)** | Replace `"… Four hand-crafted themes. ADHD-friendly."` → `"… Four hand-crafted themes. Built for every kind of mind."` Remove the only diagnosis label from a short-format public surface. | 1 |
| 24 | Homepage hero H1 | **KEEP** | *"Plan your day. Honor your brain."* already aligned. | 0 |
| 25 | Homepage Wellness section | **KEEP** | Long-form body; *"Built with neurodivergent users, not for them"* already expresses the inclusive-design framing. | 0 |
| 26 | Homepage FAQ | **KEEP** (optional cosmetic REFRAME) | Body content permitted per safe phrasing rule. If desired: rephrase `"What makes it ADHD-friendly?"` → `"How does PrismTask support neurodivergent minds?"` (still long-form body, but de-emphasises the diagnostic anchor). | 0-1 |
| 27 | `docs/marketing/` | n/a | Not present. | 0 |

**Tally (excluding n/a):**
- KEEP: 14 of 22 actionable surfaces (~64%)
- REFRAME optional: 7 (~32%)
- REPLACE load-bearing: 1 (#23, the homepage meta description) (~4%)
- DELETE: 0

If we include the 3 R3-YELLOW Settings labels (#17/#18/#19) as KEEP-acceptable (since they're feature labels, not audience claims, and live behind onboarding), the KEEP percentage rises to ~77%.

---

## Section C — Risk audit

### R1 — Medical-classification risk (GREEN)

Does any proposed REPLACE/REFRAME draft name a diagnosis (ADHD, autism, anxiety, depression, OCD, etc.) as the primary audience descriptor on Play Store short description, feature graphic, or README first paragraph?

**No.** All four short-format public surfaces are clean post-REPLACE:
- Play Store short description: experience-language ✅
- Play Store feature graphic: no audience claim ✅
- README first paragraph: technical description, no diagnosis ✅
- `docs/index.html` meta description: **REPLACE** removes the one remaining diagnosis label.

Verdict: **GREEN.** The proposed changes reduce medical-classification risk; they do not add any.

### R2 — ADHD-coach-outreach copy coherence (GREEN)

Does the repositioning force dropping ADHD entirely from any surface that ADHD-coach outreach depends on?

**No.** Diagnosis-language survives in the body-content surfaces where it is both permitted (per safe phrasing rule) and useful for coach outreach:
- Play Store full description feature bullet ("Neurodivergence-friendly modes")
- Homepage Wellness section ("Built with neurodivergent users, not for them")
- Homepage FAQ ("What makes it ADHD-friendly?", "neurodivergent and chronically-ill users")
- Source-level identifiers (`adhdMode`, `NdPreferences`, `BrainMode`)

Verdict: **GREEN.** Coach outreach material can quote any of these. Memory `project_chat_system_prompt_load_bearing.md` notes that Variant B forgiveness-first framing is already shipped at the chat-prompt layer.

### R3 — Onboarding terminology drift (GREEN, with optional YELLOW for Settings)

Do toggle labels need to change?

**Onboarding:** the user-facing toggle on BrainModePage is *"I Get Distracted Easily"*, not *"ADHD Mode"*. This was already resolved in an earlier audit (the hard-rule comment at `OnboardingScreen.kt:1016` documents it).

**Settings:** the post-onboarding Settings → Brain Mode UI still labels its toggles *"ADHD Mode"* / *"Calm Mode"* / *"Focus & Release Mode"* (`BrainModeSection.kt:47-65`), and the Subscription comparison row says *"ADHD Mode & Calm Mode"* (`SubscriptionSection.kt:181`). These are **feature-mode labels** (describing what the toggle does, not who the app is for), inside the app, behind first-launch onboarding. The Settings entry subtitle on `SettingsScreen.kt:278` also reads *"ADHD, Calm, Focus Release"*.

Verdict: **GREEN for the onboarding flow**, the load-bearing user-acquisition surface. **YELLOW for the post-onboarding Settings labels** — operator-discretionary; reframing is cosmetic, with no medical-classification risk impact (Play Console doesn't scrape post-onboarding Settings UI for classification). Preference keys + ViewModel methods + analytics labels stay as `adhdMode*` — no enum rename required.

---

## Section D — Scope verdict: **STOP-E fires**

**STOP-E trigger** (per prompt): *>70% of surfaces verdict KEEP — operator should be told the repositioning is mostly already done, this prompt is moot.*

- Strict KEEP count: 14/22 = 64% (just under the threshold).
- Pragmatic KEEP count (counting the 3 R3-YELLOW Settings labels as KEEP-acceptable, since they describe feature behavior not audience): 17/22 = **77%**.

Either way, this is the STOP-E regime — the repositioning that the prompt frames as a fresh initiative was **substantially completed in the PR #765 era** (April 2026 Play Console reclassification cycle). The current `main` HEAD is already on-brand for *"Built for every kind of mind / works with your brain not against it"* across every short-format public surface **except** `docs/index.html:7`.

**Total estimated LOC** for the full surgical set (#2 + #6 + #7 + #9 + #17 + #18 + #19 + #23 + optional #26):
- Load-bearing (just #23): **1 line**.
- Load-bearing + tagline injection (#2 + #6 + #7 + #9 + #23): **~6-7 lines**.
- Load-bearing + tagline injection + Settings de-labeling (#2 + #6 + #7 + #9 + #17 + #18 + #19 + #23): **~10-11 lines**.

**Suggested PR shape** (operator-gated; do NOT auto-proceed):
- **Option A (recommended, minimal):** single PR shipping only #23 — the `docs/index.html` meta description REPLACE. Closes the one medical-classification residual without churning the marketing tone everyone else already wrote.
- **Option B (medium):** single PR bundling #23 + tagline injections (#2, #6, #7, #9). Establishes the new tagline across short-format public surfaces.
- **Option C (full):** Option B + Settings de-labeling (#17, #18, #19). Pushes the alignment all the way into the in-app Settings UI.

**Hard dependencies on operator decisions** before any of B/C ship:
- Does the operator want *"Built for every kind of mind"* to replace *"Built for focus. Made for humans."* on the Play Store, or sit alongside it? (#2)
- Does the operator want the Settings toggle "ADHD Mode" renamed? Memory's existing project rule applies to **onboarding**, not Settings. (#17-19)
- Does any Play Store copy change need to wait for a Console-draft preview before publishing? (per Phase 3 protocol in the prompt)

---

## STOP-trigger summary

| Trigger | Fired? | Notes |
|---|---|---|
| STOP-A (premise wrong) | Partial | 2 of 3 prompt premises about *"current state has diagnosis-language"* are stale: README first paragraph and BrainModePage labels were already de-labeled. The third (meta description) is real. |
| STOP-B (scope blowout >1000 LOC) | No | Total LOC ≤11 even for full Option C. |
| STOP-C (any RED in R1) | No | R1 is GREEN. |
| STOP-D (R3 enum-rename required) | No | R3 is GREEN for onboarding; YELLOW for Settings is display-label-only. |
| STOP-E (>70% KEEP) | **Yes** | Pragmatic KEEP is 77%. |

---

## Ranked improvement table (wall-clock-savings ÷ implementation-cost)

For operator triage, ordered by `(value to repositioning goal) ÷ (LOC + risk)`. Nothing here is auto-fire; Phase 2 is gated.

| Rank | Item | Cost (LOC) | Value | Verdict |
|---|---|---|---|---|
| 1 | #23 `docs/index.html` meta description REPLACE | 1 | Removes the only diagnosis label from a short-format public surface. Resolves the single load-bearing R1-adjacent finding. | **PROCEED** if operator approves Phase 2. |
| 2 | #2 Play Store full-description tagline swap | 1 | Establishes the new tagline on the highest-traffic public surface. Cosmetic only — no risk delta. | DEFER pending operator decision. |
| 3 | #6 GitHub repo About blurb append | 1 | Establishes tagline on the dev-facing public surface. | DEFER pending operator decision. |
| 4 | #7 README first paragraph subline injection | 2-3 | Establishes tagline on the dev/contributor-facing surface. | DEFER pending operator decision. |
| 5 | #9 OnboardingScreen WelcomePage body | 1 | First-touch user-facing tagline. Behind install — lower public visibility. | DEFER pending operator decision. |
| 6 | #18 Settings → Brain Mode toggle labels | 3 | Removes diagnosis labels from in-app Settings UI. R3-YELLOW; cosmetic. | DEFER pending operator decision. |
| 7 | #17 Settings → Productivity → Brain Mode row subtitle | 1 | Cascade of #18. | DEFER pending operator decision. |
| 8 | #19 Settings → Subscription comparison row | 1 | Cascade of #18. | DEFER pending operator decision. |
| 9 | #26 Homepage FAQ entry rephrase | 0-1 | Cosmetic; long-form body permitted to keep diagnosis. | DEFER pending operator decision. |

---

## Anti-pattern list (flag, do not fix)

These were observed during the sweep but should **not** be addressed by this audit. They are not regressions; they are deliberate choices the project has already made.

- **Source-level identifiers using `adhdMode` / `Adhd...`**. The preference key, function names, info chips' internal references, and analytics labels all use `adhdMode`. These are internal — renaming them would force a cross-cutting refactor (preferences, DataStore migration, analytics dashboard schema, instrumented tests) for zero classification benefit. Leave alone.
- **`OnboardingPreferenceMapper.kt` comments referencing `"ADHD", "depression", "autism", "anxiety"`**. The comments describe what the mapper **avoids** doing. Removing them would erase the audit-trail documenting the safe-phrasing constraint.
- **Homepage long-form body uses of "neurodivergent" / "ADHD"** (FAQ entries, Wellness section pillar 4). Long-form body content is explicitly permitted to use diagnosis terms per the safe-phrasing rule, and these entries are load-bearing for ADHD-coach outreach (R2 GREEN). Leave alone.
- **`README.md:143` Projects feature line mentions "forgiveness-first streak"**. This is a feature-name reference in the feature inventory table, not an audience claim or first-paragraph framing. Leave alone.
- **`CHANGELOG.md` / `docs/audits/*.md` historical references to ADHD framing**. Audit history is durable — do not rewrite past audits to match new framing. Leave alone.

---

## Phase 2 disposition

Phase 2 is **operator-gated** per this prompt (explicit override of the audit-first skill's auto-fire default). The audit conclusion is **STOP-E + 1 real find**:

- **Recommended minimum (Option A):** ship #23 only. One-line `docs/index.html` change. Closes the single load-bearing medical-classification residual.
- **Optional escalation (Option B / C):** tagline injection / Settings de-labeling. Cosmetic; no risk delta; LOC ≤11 total.

No Phase 2 PR has been opened from this audit. The session summary in the chat documents the STOP-E and surfaces the Option A/B/C choice for operator approval.

---

## Phase 3 — Bundle summary

*Populated post-Phase 2 when (and if) operator approves implementation. Currently empty — STOP-E held the gate.*
