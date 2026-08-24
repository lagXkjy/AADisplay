# Upstream / Fork provenance

This repository is a modified GPL-3.0 work derived through the following upstream chain:

1. **Direct upstream:** `lagXkjy/AADisplay`, branch `AADisplay-Split-Lite`
   - https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite
2. `lagXkjy/AADisplay` is shown by GitHub as forked from `Stashboy/AADisplay`.
3. The direct-upstream README credits `Nitsuya/AADisplay` as the original author/project upstream and `Stashboy/AADisplay` as branch upstream.

This fork must not be represented as an original clean-room implementation.

## Exact upstream source snapshot used

The source snapshot received from the direct upstream was **not a plain r10 tree**, and it was also **not a formally released r11 package**. Its state was:

- `aa-display/build.gradle.kts`: `versionName = "0.24#17.4-r11"`, `versionCode = 3067`
- upstream README: current module version shown as `0.24#17.4-r11`
- upstream README: user release notes still point to `RELEASE_NOTES_0.24-17.4-r10.md`, with r11 fixes referenced under `CHANGELOG` → `Unreleased`
- upstream `CHANGELOG.md`: r11-era changes are still under **Unreleased**, followed by the formal `0.24#17.4-r10` section
- files included: `RELEASE_NOTES_0.24-17.4-r10.md` and `RELEASE_NOTES_0.24-17.4-r9.md`; no standalone `RELEASE_NOTES_0.24-17.4-r11.md` was present

For provenance, this repository therefore uses the following wording:

- **Upstream source snapshot:** `0.24#17.4-r11` (**unreleased / post-r10 development snapshot**)
- **Latest documented upstream release at the time:** `0.24#17.4-r10`

This wording preserves both facts and avoids incorrectly claiming either that the source was a pure r10 tree or that r11 had already been formally released.

## Community fork

Current public release: **Community Fork R1.0**.

Public fork baseline prepared: **2026-08-24**.

The changes specific to this fork are summarized in `FORK_CHANGES.md` and repository history.
