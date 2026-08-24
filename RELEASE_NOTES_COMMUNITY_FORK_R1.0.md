# AADisplay Split-Lite — Community Fork R1.0

First public community release based directly on `lagXkjy/AADisplay`, branch `AADisplay-Split-Lite`, and distributed under GNU GPL v3.0.

## Upstream source state

- **Source snapshot used:** `0.24#17.4-r11` — unreleased / post-r10 development snapshot (`versionCode 3067`)
- **Latest documented upstream release at the time:** `0.24#17.4-r10`

The upstream tree already carried the r11 version string, but its r11-era changes were still in `CHANGELOG` → `Unreleased` and no standalone r11 release-notes file was included. See `UPSTREAM.md`.

## Highlights

- Phone Input Bridge for entering text on the phone and sending it to the active AAD pane.
- Car-side `←` Back and `⌨` Phone Input controls.
- A–Z / pinyin-initial quick index in the app picker.
- Samsung Android 16 / One UI disconnect-safety fixes.
- Samsung Screen Curtain teardown-safety behavior.
- DexKit 2.0.7 for Android 16 / 16 KB page-size compatibility.
- Android Studio build baseline: AGP 8.11.2, Gradle 8.13, JDK 17.
- Debug APK does not advertise itself as a debuggable app.

## Display policy

This release intentionally preserves the direct-upstream VirtualDisplay sizing/resolution behavior. It does not include fixed Audi viewport, fixed 1280×600, forced per-pane DPI or other DHU-only sizing experiments.

The Android Auto bottom navigation area may remain visible and is accepted in this release.

## Tested setup

- Samsung Galaxy S24 Ultra
- Android 16 / One UI 8.5
- KernelSU + LSPosed
- 2022 Audi A4 allroad
- Android Auto + AADisplay Split-Lite

Other combinations are not claimed as tested.

## Suggested GitHub release metadata

- **Tag:** `v1.0-community`
- **Release title:** `AADisplay Split-Lite — Community Fork R1.0`
- **APK:** `AADisplay-Community-Fork-R1.0.apk`

The source attached to / reachable from this tag should correspond to the APK distributed in this release.
