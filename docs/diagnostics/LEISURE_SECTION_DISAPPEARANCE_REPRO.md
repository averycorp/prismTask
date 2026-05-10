# Leisure Section Disappearance — Repro & Logcat Capture Protocol

## Purpose

This protocol captures the diagnostic signal added for the custom Leisure
section disappearance investigation. It is designed to answer one question:
when adding an activity appears to delete a user-created custom section, which
of the instrumented branches fired?

The instrumentation uses the `LeisurePrefs` log tag and Crashlytics non-fatal
reports with a `mitigation_id` custom key.

## Pre-Conditions

- Build and install an APK that includes this PR's instrumentation.
- Use an AVD or a physical device such as the S25 Ultra connected through adb.
- Confirm adb sees exactly the target device:

```bash
adb devices
```

- Sign in to PrismTask.
- Start from an account with at least one user-created custom Leisure section,
  or create one during Path A below.
- Do not reinstall between paths unless a later investigation specifically asks
  you to test first-run behavior.

## Logcat Setup

Clear the log buffer immediately before each repro path:

```bash
adb logcat -c
```

Capture only the Leisure diagnostics after each path:

```bash
adb logcat -d -s LeisurePrefs:D > leisure_repro_<path>_<timestamp>.log
```

If you need errors only, capture:

```bash
adb logcat -d -s LeisurePrefs:E > leisure_repro_<path>_errors_<timestamp>.log
```

## Repro Paths

### Path A — Settings Screen

1. Open PrismTask.
2. Navigate to Settings → Customize Leisure.
3. Tap Add Section.
4. Name the section `TestSection-A` and save.
5. Tap Add Activity for `TestSection-A`.
6. Name the activity `Activity1` and save.
7. Observe whether `TestSection-A` still exists and whether `Activity1` is
   visible under it.
8. Capture:

```bash
adb logcat -d -s LeisurePrefs:D > leisure_repro_path_a_<timestamp>.log
```

### Path B — Leisure Mode Screen

1. Open Leisure Mode.
2. Tap the plus action for a custom section.
3. Select `TestSection-A` if the UI asks for a section.
4. Add an activity named `Activity3` and save.
5. Observe whether `TestSection-A` still exists and whether `Activity3` is
   visible under it.
6. Capture:

```bash
adb logcat -d -s LeisurePrefs:D > leisure_repro_path_b_<timestamp>.log
```

### Path C — Force-Stop Persistence

1. Reproduce the bug through Path A or Path B.
2. Force-stop the app:

```bash
adb shell am force-stop com.averycorp.prismtask
```

3. Re-open PrismTask.
4. Return to Leisure Mode and Settings → Customize Leisure.
5. Observe whether the custom section reappears, remains hidden, or remains
   deleted.
6. Capture:

```bash
adb logcat -d -s LeisurePrefs:D > leisure_repro_path_c_<timestamp>.log
```

## Expected Diagnostic Markers

### Happy-Path Mutation Markers

For custom section mutations, search for these debug markers:

- `addCustomSection ENTER`
- `addCustomSection PRE-WRITE`
- `addCustomSection POST-WRITE`
- `removeCustomSection ENTER`
- `removeCustomSection PRE-WRITE`
- `removeCustomSection POST-WRITE`
- `updateCustomSection ENTER`
- `updateCustomSection MATCH`
- `updateCustomSection PRE-WRITE`
- `updateCustomSection POST-WRITE`
- `addCustomSectionActivity ENTER`
- `addCustomSectionActivity MATCH`
- `addCustomSectionActivity PRE-WRITE`
- `addCustomSectionActivity POST-WRITE`
- `removeCustomSectionActivity ENTER`
- `removeCustomSectionActivity MATCH`
- `removeCustomSectionActivity PRE-WRITE`
- `removeCustomSectionActivity POST-WRITE`

Each state snapshot is structural only: list size plus
`id:label:activityCount`. No raw DataStore JSON is logged.

### Read-Path Marker

Search for:

- `readCustomSections POST-SANITIZE`

This marker reports raw parsed count, sanitized output count, and dropped count.

## Diagnostic Decision Tree

### If `M1_GSON_PARSE_FAIL` Fires

Gson failed to parse the stored custom section payload.

- Crashlytics key: `mitigation_id=M1_gson_parse_fail`
- Crashlytics key: `raw_length=<length>`
- Likely cause: malformed JSON or schema drift in the stored DataStore value.
- Next action: instrument write-side serialized length and round-trip validity.

### If `M1_SECTION_INVALID` Fires

Gson parsed the payload, but at least one custom section or activity had an
invalid required field.

- Crashlytics key: `mitigation_id=M1_section_invalid_field`
- Crashlytics key: `invalid_field=<field>`
- Likely cause: Kotlin non-null annotations were bypassed by Gson, or a synced
  payload omitted required fields.
- Next action: add write-side validation for the reported field.

### If `M2_SANITIZE_DROPPED` Fires

The read path produced fewer sanitized sections than parsed sections.

- Crashlytics key: `mitigation_id=M2_sanitize_dropped`
- Crashlytics keys: `dropped_count`, `raw_count`
- If M1 also fired, treat M1 as the upstream cause and M2 as a cascade.
- If M1 did not fire, inspect the sanitizer boundary and compare raw vs.
  sanitized section summaries in a follow-up diagnostic build.

### If `M3_DATASTORE_READ_FAIL` Fires

DataStore threw an `IOException` while reading Leisure preferences.

- Crashlytics key: `mitigation_id=M3_datastore_read_fail`
- Crashlytics key: `exception_type=<IOException subclass>`
- Likely cause: disk, filesystem, low-storage, or Preferences file corruption.
- Next action: inspect device storage state and consider a targeted DataStore
  recovery plan. Do not assume content-level JSON corruption.

### If `M4_UI_HIDING_SECTIONS` Fires

The data path has sections that the Leisure Mode UI does not display because
`enabled=false` filtered them out.

- Crashlytics key: `mitigation_id=M4_ui_hiding_sections`
- Crashlytics keys: `hidden_count`, `total_count`
- Likely cause: either the section was intentionally disabled, or a path is
  silently flipping `enabled` to false.
- Next action: instrument `updateCustomSection(... enabled=...)` callers and
  compare Settings visibility against Leisure Mode visibility.

### If No Mitigation Fires But The Bug Still Reproduces

The four covered candidates are likely ruled out for that repro.

- Likely candidates: ViewModel state caching, observer-chain timing, stale UI
  snapshots, or repository state that diverges from preferences state.
- Next action: instrument `LeisureViewModel.customSlots` and
  `LeisureSettingsViewModel.customSections` emission boundaries.

### If Multiple Mitigations Fire

Use this cascade order:

1. M3 means DataStore read failure is primary.
2. M1 followed by M2 means invalid or malformed content caused sanitizer loss.
3. M2 without M1 means sanitizer behavior needs direct inspection.
4. M4 without M1/M2/M3 means UI filtering or enabled-state mutation is primary.

Report every fired mitigation in timestamp order.

## Submission Bundle

Attach the following to the next diagnostic session:

- `leisure_repro_path_a_<timestamp>.log`
- `leisure_repro_path_b_<timestamp>.log`
- `leisure_repro_path_c_<timestamp>.log`
- Screenshots of any visual mismatch between Settings and Leisure Mode.
- Crashlytics screenshots filtered by `mitigation_id`.
- The exact device model, Android version, app version, and whether the account
  was used on more than one device.

## Notes

- Do not paste raw DataStore JSON into shared logs unless a follow-up session
  explicitly requests it and privacy has been reviewed.
- Do not clear app storage between paths A, B, and C.
- Do not toggle custom section enabled state while running a path unless the
  path specifically asks for it.
