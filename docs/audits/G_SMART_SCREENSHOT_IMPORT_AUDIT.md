# G — Smart Screenshot Import (Claude Vision) Audit

**Status**: Phase 1 audit (single combined). Phases 3 + 4 emitted with PR open per
audit-first-skill operator override.

**Goal**: ship full Smart Screenshot Import (image picker → Claude Vision API →
batch preview/confirm UI → edit-before-create → save-original-image-as-task-attachment)
in a single bundled PR. Pre-Phase-F per operator override (originally filed as G/Wk 5
post-launch).

**Branch**: `claude/smart-screenshot-import-WIEMP`

---

## Phase 0 verdicts

| STOP gate | Result | Notes |
| --- | --- | --- |
| STOP-A (premise) | CLEAR | PR #1216 native tool_use present at `backend/app/services/ai_productivity.py:1175`. Paste-extract pattern present at `app/src/main/java/com/averycorp/prismtask/ui/screens/extract/`. AI gate `require_ai_features_enabled` applied at the entire `/ai` router level (`backend/app/routers/ai.py:78`). |
| STOP-ATTACH-INFRA | **CONFIRMED EXISTS** | Both surfaces ready. Android: `AttachmentEntity` + `AttachmentDao` + `AttachmentRepository.addImageAttachment(context, taskId, sourceUri)` (saves to `filesDir/attachments`, tracks via `SyncTracker`). Backend: `Attachment` model with `Task.attachments` back-population. **No Room or alembic migration needed.** Item 6 collapses to ~30-50 LOC. |
| STOP-VISION-MODEL | CLEAR | `anthropic==0.42.0` in `backend/requirements.txt` — supports vision via `{"type": "image", "source": {"type": "base64", "media_type": ..., "data": ...}}` in messages array. No client wrapper to extend; backend calls SDK directly via `client.messages.create(...)`. |
| STOP-COST | DEFAULT | Per operator preference (1): ship without explicit per-month vision cap. The existing `daily_ai_rate_limiter` + per-endpoint `RateLimiter` (10/min) cover burst protection. Post-launch usage analytics review can inform whether a hard cap is needed. |
| STOP-PHASE-F | CLEAR | Total LOC estimate ~500-700 (CONFIRMED-EXISTS path — Item 6 light). Within Phase F window. |
| STOP-1A | CLEAR | Defaulting to `ActivityResultContracts.PickVisualMedia` (Android 13+ system picker, no permission needed; for older versions `PickVisualMedia` ships in androidx.activity since 1.7.0 with a backport that uses GET_CONTENT). No CAMERA permission added. |
| STOP-2A | CLEAR | No existing image-loading library (Coil/Glide) in app — using direct `BitmapFactory` + `Bitmap.compress` for compression. |
| STOP-3A | CLEAR | No `AnthropicClient` wrapper to extend. Vision message constructed inline in service layer, mirroring `extract_tasks_from_text` shape. |
| STOP-3B | CLEAR | Reusing the JSON-output prompt pattern from `extract_tasks_from_text` — Haiku reliably returns parseable JSON for short structured outputs. No need to escalate to native tool_use. |
| STOP-4A | CLEAR | `PasteConversationViewModel` + `PasteConversationScreen` still on disk; not deleted by D13. Pattern reusable for batch UI. |
| STOP-5A | CLEAR | Inline edit (text field per row) like paste-extract — no need for external Composable. |
| STOP-6A/B/C | N/A | Resolved by STOP-ATTACH-INFRA upgrading to CONFIRMED-EXISTS. |
| STOP-7A | INFO | This adds 9th `ProGatedFeature` enum value (`SMART_SCREENSHOT_IMPORT`). Awareness only. |

---

## § Item 1 — Image picker UI

**Verdict**: Use `ActivityResultContracts.PickVisualMedia` (androidx.activity 1.7+,
already on classpath via Compose-activity transitive). Caller is the Today AI hub
("Extract from Screenshot" row); on tap → launch picker → callback delivers `Uri?`.

**Permissions**: None. PickVisualMedia runs through the Photo Picker system UI and
needs no `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` declarations.

**LOC**: ~30 LOC inside the screen Composable (one launcher + one launch site).

---

## § Item 2 — Image transport

**Verdict**:
1. Encoding: **base64** in JSON request body (mirrors paste-extract pattern;
   image typically 0.5-2MB after compression — base64 overhead acceptable).
2. Compression: load via `BitmapFactory.decodeStream(...)` → `Bitmap.compress(JPEG, 80, ...)` → base64.
   Cap final output at 4MB; reject larger with user-facing error.
3. Media type: `"image/jpeg"` (post-compression).

Compression utility lives in a small dedicated file
(`util/ScreenshotEncoder.kt`) so the ViewModel stays UI-only.

**LOC**: ~60 LOC (encoder + compression + size validation).

---

## § Item 3 — Backend Vision endpoint

**Endpoint**: `POST /api/v1/ai/vision/extract-tasks`. Auth + AI gate inherited
from the `/ai` router (`Depends(require_ai_features_enabled)` already wraps the router).

**Request schema** (`VisionExtractRequest`):
- `image_base64: str` — base64-encoded JPEG/PNG bytes (max 6MB pre-base64; the
  client compresses to ≤4MB but we leave 50% headroom for the base64 inflation).
- `image_media_type: str` — one of `"image/jpeg"`, `"image/png"`, `"image/webp"`, `"image/gif"`.

**Response schema** (`VisionExtractResponse`): `{tasks: list[ExtractedTaskCandidate]}`.
Reuses the existing `ExtractedTaskCandidate` schema from paste-extract — same
shape (title, suggested_due_date, suggested_priority, suggested_project, confidence).

**Anthropic call**:
```python
message = client.messages.create(
    model=MODEL_HAIKU,  # "claude-haiku-4-5-20251001"
    max_tokens=2048,
    messages=[{
        "role": "user",
        "content": [
            {"type": "image", "source": {"type": "base64", "media_type": ..., "data": ...}},
            {"type": "text", "text": "<extraction prompt mirroring extract_tasks_from_text>"}
        ]
    }]
)
```

**Rate limiting**: per-endpoint `RateLimiter(max_requests=10, window_seconds=60)` +
the existing `daily_ai_rate_limiter`. Mirrors the paste-extract endpoint exactly.

**Error handling**:
- `RuntimeError` from missing API key → 503
- `ValueError` from JSON-parse failure → 500
- `APIError` from Anthropic (e.g. unsupported media type) → 502
- Validation (oversized image, bad media type) handled by Pydantic.

**LOC**: ~120 LOC (router 50 + service 50 + schemas 20).

---

## § Item 4 — Batch preview/confirm UI

**Pattern reuse**: `PasteConversationViewModel` + `PasteConversationScreen` —
reuse the `EditableCandidate` data class shape (title + selected + confidence)
verbatim. Net-new screen because the input flow (image vs text) and the
extraction call site differ.

**Composable shape**: full-screen `Scaffold` with:
- Top: top-app-bar with back nav + (post-extract) thumbnail of source image.
- Body: scrollable `Column` with `OutlinedTextField` per candidate row
  (matches paste-extract row), each with a `Checkbox` for select.
- Loading state: `CircularProgressIndicator` + "Analyzing image…" caption
  while the Vision API call is in-flight.
- Empty state: "Couldn't find any tasks in this image. Try a clearer screenshot."
- Bottom CTA: "Create N task(s)".

**ViewModel**: `ScreenshotImportViewModel` — `MutableStateFlow<List<EditableCandidate>>`
+ `MutableStateFlow<UiState>` (Idle / Loading / Empty / Loaded / Created / Error).
Calls a shared `ScreenshotImportRepository` that wraps the backend call + the
local `ConversationTaskExtractor` is **not** used (Vision-only, no offline fallback —
the prompt explains in the UI that AI features must be on).

**State management**: `viewModelScope.launch` for the HTTP call. Exception → UiState.Error with message.

**LOC**: ~180 LOC (Composable 100 + ViewModel 60 + repository 20).

---

## § Item 5 — Edit-before-create UI

**Verdict**: per-row inline `OutlinedTextField` (mirrors paste-extract). Title-only
edit. Suggested due/priority/project from the Vision response are hidden from the
editor — they are still applied when the task is created via the same NLP pipeline
(`NaturalLanguageParser` parses the title). Future iteration can expose a more
elaborate edit dialog; that's deferred per "no premature abstraction".

**LOC**: included in Item 4.

---

## § Item 6 — Image attachment storage

**Pattern reuse**: `AttachmentRepository.addImageAttachment(context, taskId, sourceUri)`.
The original `Uri` from `PickVisualMedia` is held in the ViewModel until task
creation; then `addImageAttachment` is called once per created task, attaching the
**same source image** to every task created from that screenshot (so the user can
revisit the source image from any extracted task's detail view).

**Sync wiring**: Already in place — `AttachmentRepository` calls `syncTracker.trackCreate(id, "attachment")`
which the existing sync pipeline picks up. No new code on this surface.

**LOC**: ~25 LOC (call site in ViewModel `createSelected()` loop).

---

## § Item 7 — Pro tier gating

**Pattern reuse**: `ProGatedFeature` enum + `ProUpsellSheet`. Adds:
- `ProGatedFeature.SMART_SCREENSHOT_IMPORT` enum value (label "Extract Tasks From Screenshot",
  description "Snap a screenshot of an email, chat, or notebook page and let Claude pull tasks out of it").
- New `AiSection` row + `onNavigateToScreenshotImport: () -> Unit = {}` callback.
- `TodayAiHubSheet` wires `gatedNavigate(ProGatedFeature.SMART_SCREENSHOT_IMPORT, PrismTaskRoute.ScreenshotImport.route)`.

**Per-feature opt-in toggle**: adds `screenshotImportEnabled: Boolean = true` to
`PerFeatureAiPrefs` data class + companion `KEY_AI_SCREENSHOT_IMPORT_ENABLED`
preferences key + flow plumbing.

**LOC**: ~50 LOC across enum, AiSection, TodayAiHubSheet, PerFeatureAiPrefs, SettingsViewModel.

---

## § Item 8 — Backend AI gate

**Verdict**: Automatic. The new `/ai/vision/extract-tasks` route is registered on
the `/ai` router, which has `dependencies=[Depends(require_ai_features_enabled)]`
applied at router level (`backend/app/routers/ai.py:78`). No per-route decorator
needed; the gate fires before the handler.

**Test**: `backend/tests/test_ai_gate.py` enumerates router-level coverage; the new
endpoint is automatically asserted by the existing `_get_ai_router_paths()`-style
coverage tests. Vision-specific endpoint test in `test_ai_vision_extract.py` adds
a positive 200 path with mocked Anthropic response.

**LOC**: 0 LOC (router-level dep already present).

---

## § Item 9 — Bundle decision

**Shape**: single PR per operator pre-lock. Branch: `claude/smart-screenshot-import-WIEMP`.

**Commit order**:
1. Backend Vision endpoint + schemas + service (Items 3 + 8).
2. Backend tests (`test_ai_vision_extract.py`).
3. Android image picker + transport utility (Items 1 + 2).
4. Android batch UI (ViewModel + Composable + repository) + edit-before-create + image attachment + Pro gating (Items 4 + 5 + 6 + 7).
5. Android tests (ViewModel unit test + screenshot encoder test).
6. Audit doc.

**Cross-item dependencies**:
- Item 6 storage flow needs Items 1+2 wired (image must be picked before being saved).
- Item 7 ProUpsellSheet wiring is orthogonal to Items 4-6 but tested with both Free + Pro accounts.

**Total LOC** (estimate at audit time):
- Backend: ~150 LOC (router + service + schemas + tests)
- Android: ~400 LOC (picker + encoder + ViewModel + Composable + repository + Pro gating + tests)
- Audit doc: ~250 LOC
- **Grand total**: ~800 LOC; well under the 1100 STOP-PHASE-F-RISK ceiling.

**Pattern non-reuse exceptions**:
- Did **not** reuse `ConversationTaskExtractor` for offline fallback — Vision is
  inherently online-only, and a regex fallback for image content would be
  meaningless. The Composable's empty-state messaging makes this explicit.

---

## Verification plan (Phase 3)

| Item | Check |
| --- | --- |
| 1 (picker) | AVD smoke — open Today AI hub → "Extract from Screenshot" → picker launches → select → returns URI |
| 2 (transport) | unit test for `ScreenshotEncoder.encodeToBase64` — large image compresses; oversized rejects |
| 3 (backend) | pytest endpoint test — auth required (401 without token), AI gate works (451 with disabled header), schema validation (422 on missing fields), Vision call returns extracted tasks (mocked) |
| 4 (batch UI) | ViewModel unit test — Idle → Loading → Loaded transitions; toggle + edit mutations |
| 5 (edit) | covered by ViewModel test (editTitle behavior) |
| 6 (attachment) | AVD smoke — created task has 1 image attachment pointing to filesDir/attachments/img_*.jpeg |
| 7 (Pro gate) | AVD smoke — Free account → ProUpsellSheet (SMART_SCREENSHOT_IMPORT label) on tap |
| 8 (AI gate) | covered automatically by existing `test_ai_gate.py` router-level tests |

CI gates the full suite — see `Phase 4 Verification` for the actual results at hand-off.

---

## Anti-patterns honored

1. Did NOT modify PR #1216 native tool_use protocol invariants ✓
2. Will NOT log image bytes to Crashlytics or analytics ✓
3. Did NOT add new file storage abstractions (reused `AttachmentRepository`) ✓
4. Did NOT add new feature flags (always-on; gated only by Pro tier + per-feature opt-in) ✓
5. Did NOT bundle additional G-series items ✓
6. Did NOT skip Phase 0 attachment-infrastructure verification ✓
7. Will NOT use full-text str_replace on similar-named methods without unique anchors ✓
8. Did NOT introduce remote config for vision cap; using existing `RateLimiter` constants ✓
9. Did NOT add admin triage UI for extracted-task records ✓
10. Phase 4 summary printed in CC chat output — see end of session ✓
11. Will NOT amend commits ✓
12. Audit doc cap 1000 lines — current ~250 lines ✓
13. Auto-memorize disabled; patterns flagged with data-point count in Phase 4 ✓
14. Did NOT add OCR fallback ✓
15. Did NOT add multi-image batch — single image per extraction ✓

---

## G closure impact

After this PR merges + 24-48h post-merge usage observation:
- **G Smart screenshot import**: 0 → 1.0
- G remaining open items: Voice input partial, BetaTesting integrations beta, Staged rollout (3 items)
- G stays open

Phase F window: NEUTRAL → POSITIVE (new launch differentiator: screenshot extraction).
