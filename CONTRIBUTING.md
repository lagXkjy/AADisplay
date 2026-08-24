# Contributing

Contributions are welcome if they preserve GPL-3.0 compatibility and clearly document behavior changes.

## Ground rules

- Base pull requests on the current public branch.
- Do not commit signing keys, `local.properties`, tokens, private logs, or other secrets.
- Keep Android Auto / VirtualDisplay changes narrowly scoped and document device/AA versions used for testing.
- Do not change the display-size / resolution baseline without clearly marking it as an opt-in experiment.
- Include a concise changelog entry for user-visible changes.

## Build

Use JDK 17, then run:

```powershell
.\gradlew.bat :aa-display:assembleDebug
```
