# CastTrace Android Architecture

## Dependency graph

`StreamCapApplication` owns the Hilt root. `AppModule` provides the database, preferences, network policy, HTTP client, resolver registry, focused repositories, and recording storage operations as application singletons. UI state is created by Hilt ViewModels; Android services, receivers, and WorkManager workers use their corresponding Hilt entry points.

Production classes consume Hilt bindings directly. Instrumentation tests use an `AppTestGraph` Hilt EntryPoint in `androidTest`, keeping test access out of the production API and avoiding a second dependency container. Local recording fixtures use MockWebServer over loopback; only the Debug network security config permits cleartext to loopback addresses, while Release keeps the production TLS-only policy.

## Platforms and resolvers

Add platform identity, display name, domains, aliases, resolver id, and support status to `PlatformCatalog`. `PlatformResolverFactory` creates the dedicated resolver when one exists and otherwise creates a `GenericWebResolver` from the catalog domains. Direct HLS/FLV URLs keep the dedicated `direct` resolver. `ResolverRegistry` resolves URLs and persisted platform names through the catalog so aliases share one credential key. `AVAILABLE` and `FIRST_BATCH` indicate currently exposed support, `NEEDS_REVIEW` and `PLANNED` indicate limited or unverified behavior, and `DISCONTINUED` entries are omitted from the registry.

When adding a platform, update the catalog and add a dedicated resolver only when generic room checks or stream parsing are insufficient. Extend `PlatformResolverFactoryTest` for new matching behavior.

## Data and storage

`StreamCapRepository` preserves the public API while delegating room, reminder, recording-session, task-log, and live-check work to focused components. `RecordingStorage` preserves its existing entry points while delegating file handling, MediaStore metadata and rename operations, export, segment discovery, deletion, and publication. Room entities and migrations remain the source of persisted state; this refactor does not change the Room schema version.

Publication callers must retain their temporary source until the destination has been copied and the session update is durable. Failed copies and failed publications remain recoverable and must not delete the source.

## Recording lifecycle

`RecordingService` owns Android lifecycle callbacks, Intent compatibility, foreground service behavior, and the outer recording state machine. `RecordingStartupCoordinator` creates sessions and applies startup failure policy, while a narrow host adapter keeps retry scheduling, wake locks, pause handling, and active-session observation in the service. `RecordingMonitorCoordinator` owns periodic room and reminder checks. `RecordingLineSelector` chooses and refreshes stream lines, `RecordingProgressCoordinator` persists recorder progress and commits closed checkpoints, `RecordingRetryPolicy` expresses retry and terminal precedence, `RecordingRecoveryCoordinator` decides which interrupted sessions need repair, `RecordingPublicationCoordinator` repairs/publishes closed media and handles recovery publication, and `RecordingPowerLockController` owns wake and Wi-Fi locks. `RecordingRuntimeState` shares active-room reservations and monitor generations across these service-scoped collaborators. `TemporaryRecordingParts` is the single source for temporary segment indexing and discovery.

The persisted recording path is `STARTING` -> `RECORDING` / `RECONNECTING` -> `FINALIZING` -> `COMPLETED`; user pause ends in `PAUSED`, and unrecoverable startup or publication errors end in `FAILED`. `RecordingRetryPolicy` keeps retry and terminal precedence as a pure rule set with JVM tests; explicit stop and pause requests take precedence over automatic retries. Recovery and publication must be idempotent so an `ACTION_MONITOR` or process restart cannot finalize an active file twice.

The service Intent actions and static start helpers are compatibility contracts. Keep partial media and session progress durable before removing a temporary segment, so recovery can retry after process death without publishing it twice.

## UI ownership

Compose screens render state and dispatch user events. Hilt ViewModels own repository flows and asynchronous UI operations. Keep player instances and frame-by-frame playback controls in the player screen; move persistence, source resolution, export, filtering, and other asynchronous workflows into ViewModels or focused coordinators.
