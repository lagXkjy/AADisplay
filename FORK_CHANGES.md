# Fork changes — Community Fork R1.0

Direct base: `lagXkjy/AADisplay`, branch `AADisplay-Split-Lite`.

Public release name: **Community Fork R1.0**
Upstream source snapshot: `0.24#17.4-r11` (**unreleased / post-r10 development snapshot**)
Latest documented upstream release at that time: `0.24#17.4-r10`

The received upstream tree already carried `versionName = "0.24#17.4-r11"` / `versionCode = 3067`, but its r11-era entries remained under `CHANGELOG` → `Unreleased` and there was no standalone r11 release-notes file. See `UPSTREAM.md` for the exact provenance wording.

## Added / changed in this community fork

### Phone input bridge
- Adds a phone-side input activity.
- Adds an automatic/manual path to enter text on the phone and send it to the currently targeted AAD pane.
- Adds a car-side `⌨` phone-input control.
- Avoids the earlier experimental approach of forcing the system IME across displays.

### Car controls
- Adds car-side `←` Back control.
- Keeps these controls separate from the upstream display-size logic.

### Android Auto disconnect safety
- Adds conservative teardown behavior for Samsung Android 16 / One UI environments.
- Releases display/wake resources promptly on AA disconnect in the affected Samsung path.
- Includes compatibility handling for task-stack callback changes that can otherwise destabilize `system_server` on newer framework interfaces.

### Samsung Screen Curtain safety
- Avoids continuing display wake/reassert behavior after projection teardown in the affected Samsung path.

### App picker
- Adds A–Z / pinyin-initial sorting and a quick alphabet index.
- Keeps the Recent section behavior separate.

### Android 16 / 16 KB
- DexKit updated to 2.0.7.
- DexKit API call sites adapted accordingly.

### Build environment
- AGP 8.11.2.
- Gradle 8.13.
- JDK 17.
- Debug build is configured non-debuggable to avoid Android's debug-app warning.

## Intentionally not changed for Community Fork R1.0

- Direct-upstream VirtualDisplay resolution/size policy.
- Experimental fixed Audi viewport values.
- Experimental fixed 1280×600 viewport logic.
- Experimental forced per-pane DPI formulas.
- Forced removal of the currently visible Android Auto bottom navigation area.

The Git history/diff should be used as the authoritative record of exact code changes.

## Tested release note

Community Fork R1.0 has been tested on a **Samsung Galaxy S24 Ultra** with a **2022 Audi A4 allroad**. See `TESTED_ENVIRONMENT.md` for the scope of that claim.
