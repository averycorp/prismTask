# Permissions Justifications — PrismTask

**Destination:** Play Console → Policy → App content → Permissions declaration form (where required) and the in-listing "App permissions" section.

Every permission declared in `AndroidManifest.xml` (lines 5-52) has a user-facing justification below. Paste each cell into the matching Play Console field. The table mirrors §3 of `docs/archive/STORE_LISTING_PHASE1_AUDIT.md`.

---

## Standard permissions (no extra declaration needed)

| Permission | User-facing justification |
|---|---|
| `android.permission.INTERNET` | Sync your tasks and habits across devices and fetch AI-powered task parsing. |
| `android.permission.ACCESS_NETWORK_STATE` | Detect when you are offline so sync queues mutations instead of failing. |
| `android.permission.VIBRATE` | Deliver per-profile custom vibration patterns on notifications. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Re-register your scheduled reminders after a phone restart so they still fire. |

---

## Permissions requiring extra declaration or justification

### `POST_NOTIFICATIONS`

**Purpose:** Task reminders, habit streak nudges, Pomodoro completion alerts, medication dose reminders, escalation chains.

**User flow:** Requested at app startup via the system permission dialog. User can revoke at any time from system Settings.

### `USE_FULL_SCREEN_INTENT`

**Purpose:** Display urgent reminders over the lock screen when the user opts in via Settings → Notifications → "Full-Screen Notifications." Useful for medication reminders and high-priority task alarms.

**Android 14+ note:** the system auto-downgrades to a heads-up notification for apps whose primary function is not alarms/calendar/communication. PrismTask's primary function is task/reminder management, which qualifies, but the downgrade path is acceptable fallback behavior.

### `SCHEDULE_EXACT_ALARM` (Android 12, API 31-32)

**Purpose:** Fire reminders at the exact minute they are scheduled. Without this, Android's inexact alarm tier can delay reminders by many minutes on some OEMs (Samsung, Xiaomi, OPPO). User-toggleable in system Settings. `MainActivity` prompts for it the first time `canScheduleExactAlarms()` returns false.

### `USE_EXACT_ALARM` (Android 13+, API 33+)

**Purpose:** Same as `SCHEDULE_EXACT_ALARM` on modern Android. Auto-granted for apps in the "reminder/alarm/calendar/timer" category. No user prompt needed.

**Play Console declaration:** PrismTask is a task manager with scheduled reminders and medication alarms. Reminders are the core product value for a large share of users.

### `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**Purpose:** One-time prompt (shown on Samsung / Xiaomi / OPPO devices with aggressive battery optimization) opening the system exemption screen. Without the exemption, scheduled reminders are silently deferred under Doze / App Standby.

**User flow:** Shown once via `MainActivity`; user is directed to system Settings and the app does not re-prompt.

### `REQUEST_INSTALL_PACKAGES`

**⚠️ Open engineering question (see `docs/archive/STORE_LISTING_PHASE1_AUDIT.md` §Policy-Risk-1):** this permission is used by the in-app self-updater (`AppUpdater.kt`) for Firebase App Distribution / sideloaded builds only. It does not fire on Play Store builds.

**Play Console declaration (if permission is kept as-is):** "Used exclusively by Firebase App Distribution beta builds to install self-updates. Play Store releases do not exercise this path; the app relies on the Play Store update mechanism."

**Recommended engineering follow-up:** move the `<uses-permission>` declaration behind a build-variant manifest overlay so only non-Play builds declare it. This avoids a Play Console permission review and a pre-launch warning.

### `RECORD_AUDIO`

**Purpose:** Voice dictation — speak a task instead of typing it. Captured via `android.speech.SpeechRecognizer`; audio is processed by Google Speech Services and discarded once the transcript is returned. PrismTask does not retain audio.

**User flow:** Permission requested only when the user taps the mic icon on the quick-add bar or in any voice-command affordance. Feature is opt-in and fully optional.

### `FOREGROUND_SERVICE`

**Purpose:** Run the Pomodoro countdown timer as a foreground service so it keeps running when the user switches apps. Without this, `viewModelScope` coroutines are cancelled on backgrounding and the completion notification never fires.

### `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

**Purpose:** `PomodoroTimerService` is declared with `foregroundServiceType="mediaPlayback"`. Samsung One UI suppresses the notification for `FOREGROUND_SERVICE_SPECIAL_USE` service types, so the countdown notification would be invisible on Samsung. `mediaPlayback` is the declaration that reliably shows an ongoing notification across OEMs.

**Play Console note:** this permission type triggers a declaration form asking why the app qualifies. The honest answer is: "Pomodoro countdown notification with the user's active session state. The timer acts like a media/audio-adjacent experience — continuous, foregrounded, with transport controls." If reviewers push back, the fallback is to switch the type to `SPECIAL_USE` and accept the Samsung-specific invisibility bug; document that decision in the app.

---

## Queries declaration (not a permission, but required to be listed in Play Console)

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

**Purpose:** Let the system tell us whether a speech recognition service is installed so the mic button can be hidden when voice input is not available.
