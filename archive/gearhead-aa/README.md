# archive/gearhead-aa — Google gearhead / Car SDK path

Tombstone only — **no recovered source** in this directory.

Runtime cutover (AAP replaces gearhead projection):

- `xposed_scope` = `android` + module package (no gearhead)
- `XposedInit` loads only `AndroidHook` (system_server)
- Deleted: `xposed/hook/aa/**`, `AndroidAutoHook`, `DexKitMethodCache`, `AaDisplayProcessHook`, DexKit dependency
- Deleted: Car SDK shell, Coolwalk settle, `:cluster` Title MediaBrowser, `aauto.aar`
- `CHANNEL_AA` migrates to `defaultChannel()`; status page is AAP / CarPlay only
- Shell broadcast bus pruned (IME visibility only); Binder bridge = module uid only
- Coolwalk / presentation AIDL methods kept as no-ops for ordinal stability

Canonical notes: [`docs/GEARHEAD_LEGACY.md`](../../docs/GEARHEAD_LEGACY.md)  
Historical FacetBar design: [`docs/archive/COOLWALK_FACETBAR.md`](../../docs/archive/COOLWALK_FACETBAR.md)
