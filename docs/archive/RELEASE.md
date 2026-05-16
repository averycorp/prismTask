# Release Checklist

## Keystore Generation

Generate a release keystore (do this once, keep it safe forever):

```bash
keytool -genkey -v -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias prismtask
```

**IMPORTANT:** Back up `release-keystore.jks` securely. Losing this keystore means you can never update the app on the Play Store.

## Version Code Strategy

- `versionCode`: Increment by 1 for each Play Store upload (strictly increasing, required by Play Store)
- `versionName`: Semantic versioning (e.g., 1.1.0, 1.1.1, 1.2.0)
- Both are set in `app/build.gradle.kts` under `defaultConfig`

## Prerequisites

- [ ] `release-keystore.jks` exists (never committed to git)
- [ ] Environment variables set:
  - `KEYSTORE_PATH` — path to the release keystore file
  - `KEYSTORE_PASSWORD` — keystore password
  - `KEY_ALIAS` — key alias (default: `prismtask`)
  - `KEY_PASSWORD` — key password
- [ ] Google Play Console: app created, store listing complete
- [ ] Google Play Console: `prismtask_pro_monthly` subscription product created

## Build

- [ ] Update `versionName` in `app/build.gradle.kts`
- [ ] Increment `versionCode` in `app/build.gradle.kts`
- [ ] Update `CHANGELOG.md`
- [ ] Run unit tests: `./gradlew testDebugUnitTest`
- [ ] Run instrumented tests: `./gradlew connectedDebugAndroidTest`
- [ ] Build release bundle: `./gradlew bundleRelease`
- [ ] Verify AAB: `app/build/outputs/bundle/release/app-release.aab`

## Test Release Build

- [ ] Install release APK on device:
  ```bash
  ./gradlew assembleRelease
  adb install app/build/outputs/apk/release/app-release.apk
  ```
- [ ] Verify R8 didn't break anything (all screens load, no crashes)
- [ ] Verify Pro purchase flow works with test card in Play Console
- [ ] Test all gated features show upgrade prompts for free users
- [ ] Test local features work without network

## Upload

- [ ] Upload AAB to Play Console (Internal Testing track first, then Production)
- [ ] Fill content rating questionnaire (see `store/listing/CONTENT_RATING.md`)
- [ ] Fill data safety form (see `store/listing/DATA_SAFETY.md`)
- [ ] Add screenshots (minimum 4, see `store/listing/ASSET_SPECS.md`)
- [ ] Set pricing: Free (with in-app purchases)
- [ ] Submit for review

## Environment Setup for Release Build

```bash
export KEYSTORE_PATH=./release-keystore.jks
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=prismtask
export KEY_PASSWORD=your_password
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`
