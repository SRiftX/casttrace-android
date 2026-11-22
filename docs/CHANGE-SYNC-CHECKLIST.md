# Change Synchronization Checklist

Use this for every code change, not only UI work. Search the repository for direct and indirect consumers before deciding an area does not apply. Add another checklist item in the same change when the feature has an affected surface that is not listed here.

- Behavior and callers: trace the changed function, model, route, resolver, worker, service, receiver, and API through all call sites and user workflows. Check share intents, notifications, deep links, boot recovery, and foreground/background paths when relevant.
- UI and navigation: check home, details, player, settings, dialogs, empty/loading/error states, accessibility labels, and back navigation. Update every renamed or moved control and its displayed status.
- Setup and discovery: check first launch, feature tour, setup wizard, shortcuts, and contextual entry points so users can discover changed behavior.
- Settings and defaults: align controls, summaries, defaults, validation, dependent options, and current-value displays with runtime behavior.
- Data compatibility: check Room entities and migrations, DataStore and SharedPreferences keys, serialization, backup/import/export formats, cached files, and old user data. Add a migration or compatibility path when a stored contract changes.
- System integration: check runtime and manifest permissions, Android version behavior, battery and vendor settings, notifications, intents, foreground services, WorkManager constraints, boot handling, and installer flow.
- Platform and capability metadata: align resolver registration, aliases, catalog rows, support states, danmaku capability, credential UI, tests, and documentation. Tie measured claims to actual test dates and results. Keep the complete platform catalog visible by default.
- Diagnostics and recovery: update task logs, error context, progress, retry/cancel behavior, recovery controls, and support-report exports when the changed path can fail or be retried.
- Help and release communication: update in-app user guide, feature tour, setup guidance, README or operator docs, and in-app release notes wherever their instructions or claims are affected. Keep release-note version/date aligned with the app build.
- Tests and fixtures: search for affected assertions, fakes, screenshots, test data, and device tests. Update coverage for changed contracts and run the tests that exercise the affected paths.
- Build and distribution: check version metadata, release workflow validation, artifact names, permissions, signing assumptions, and install/update compatibility when build or package behavior changes.
- Final verification: run `git diff --check` and the applicable tests, lint, build, and MuMu flow. State which areas were reviewed and why any area marked `not applicable` does not consume the changed behavior.
