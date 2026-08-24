# Tested environment — Community Fork R1.0

This file records combinations that have actually been tested with this community fork. It is intentionally narrower than a general compatibility list.

## Confirmed test setup

- **Phone:** Samsung Galaxy S24 Ultra
- **OS:** Android 16 / One UI 8.5
- **Root:** KernelSU
- **Framework:** LSPosed
- **Vehicle:** 2022 Audi A4 allroad
- **Projection:** Android Auto + AADisplay Split-Lite
- **DHU:** used during development testing
- **Public release:** Community Fork R1.0

## Functions exercised on this setup

- AADisplay projection on the real vehicle head unit
- Dual-pane display and touch interaction
- Phone input bridge
- Car-side `←` Back control
- Car-side `⌨` phone-input control
- App picker A–Z / pinyin-initial index
- Android Auto disconnect / reconnect cleanup
- Samsung Screen Curtain usage while AADisplay is active
- Disconnecting Android Auto with Screen Curtain enabled without the previously observed freeze / soft-reboot behavior

## Display-layout note

Community Fork R1.0 intentionally keeps the direct-upstream VirtualDisplay size/resolution behavior. It does **not** include experimental fixed Audi viewport, fixed 1280×600 viewport, forced per-pane DPI, or other DHU-only display-sizing experiments.

On the tested Audi, Android Auto's bottom navigation area may remain visible. For this release that behavior is accepted because it provides convenient access to calls, voice, applications and media switching.

## Not yet claimed as tested

Other phones, Samsung models, Android / One UI versions, Android Auto versions, root solutions, LSPosed versions, vehicles and head units may work, but they should not be treated as confirmed merely because the setup above works.

When reporting compatibility, please include at least:

- phone model
- Android / One UI version
- Android Auto version
- root solution
- LSPosed version
- vehicle / head-unit model
- whether Screen Curtain was used
