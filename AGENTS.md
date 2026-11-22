# Repository Change Rules

For every code change, map its affected behavior across the repository before editing. Search for callers, routes, stored keys, model fields, API names, UI labels, logs, tests, and documentation with `rg`. Read [docs/CHANGE-SYNC-CHECKLIST.md](docs/CHANGE-SYNC-CHECKLIST.md), then review every applicable surface, including consumers outside the files being edited. Mark each checklist area applicable or not applicable in the task summary. Do not limit synchronization checks to the screens named in the request.

Keep the behavior, all entry points, settings, onboarding, persisted data, exports and imports, permissions, background work, notifications, diagnostics, recovery paths, help text, tests, and release information consistent with the change. Update or migrate existing data when a changed contract requires it. Do not leave stale labels, instructions, defaults, status claims, or fixtures.

When changing `versionCode` or `versionName`, update `ReleaseNotes.VERSION`, `ReleaseNotes.DATE`, and the release note entries in `ReleaseNotesScreen.kt`. Run `:app:testDebugUnitTest`; `ReleaseNotesTest` enforces the app and notes version match.

For platform resolver or support changes, keep `PlatformCatalog` aligned with the resolver registry and every platform-facing surface. Add verification evidence only when a test or device run supports the stated result. Keep the full catalog visible by default and retain the measured-only filter.

Before reporting completion, run the checks required by the change and state which synchronization areas were reviewed. For app UI changes, install and inspect the debug APK on the connected MuMu device when available.
