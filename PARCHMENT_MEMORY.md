# Parchment Memory

Updated 2026-11-09. Reference contracts; read relevant sections on demand. Start with AGENTS.md and TODO's current batch.

## Workflow

- AGENTS.md owns the working/verification policy. Use a bounded batch, one complete review before its focused check, and reuse unaffected passes. Docs/copy/simple styling need no Gradle. Android checks occur for Android/platform changes or shared subsystem checkpoints; the full matrix runs at the final gate.
- Default workflow: keep changes local until all TODO work and the final verification matrix finish, then commit/push to origin/main. The user explicitly authorized one early checkpoint after the current UI updates for review; TODO tracks it. This one-time exception does not complete the final gate. Never force-push, overwrite unrelated work or claim unverified completion.
- TODO owns live progress: current batch/acceptance/check scope, last pass and next action. Update at meaningful batch/blocker changes and tick each verified bullet immediately. Keep README factual.
- Use TODO's execution order: quick, well-understood batches first; verify existing edits before expanding scope. Confirmed critical/high defects take priority.
- Continue authorized work until complete, user-paused or genuinely blocked. After repeated failed fixes, inspect the evidence and re-plan.
- Phone is unplugged for personal use. Finish independent TODO work; request reconnection/ADB authorization only at the final device gate. Device-dependent checks stay unchecked until verified.
- Reuse applicable fetched documentation. Search current official sources once when changing an external protocol, capability, platform or dependency contract; record the relevant source/decision in TODO. Routine local refactors do not need a new research pass.
- Never optimize by losing history/instructions, weakening permissions, altering opaque signatures or silently enabling server storage.
- Keep these files concise: current contracts/results and unresolved work, not repeated narration.

## Project and build

Kotlin Multiplatform/Compose Foundation app for Windows and Android. Shared code: composeApp/src/commonMain; JVM storage: src/jvmShared; platform actuals: desktopMain/androidMain.

- Never replace/copy builds into %LOCALAPPDATA%/Programs/Parchment. Installed Windows updates use GitHub Release OTA; test workspace artifacts.
- JDK: C:/Users/Baros/.jdks/temurin-21. Inherited JAVA_HOME/PATH point to JDK 8; set JDK 21 in every Gradle invocation.
- Android SDK: %LOCALAPPDATA%/Android/Sdk. Set ANDROID_HOME and ANDROID_SDK_ROOT in the same shell.
- Keep org.gradle.daemon=false; use --no-daemon and quoted '-Pkotlin.compiler.execution.strategy=in-process'.
- In PowerShell, quote '-PappVersion=…' and '-PappVersionCode=…'. Put --tests filters immediately after desktopTest, before another task.
- scripts/verify.ps1: Review (diff only), Test (explicit desktop filters), Android (compile), Final (full desktop/platform/package gate). -Preview prints commands without running them. Defaults retain verified runtime 2.0.64/264; override deliberately when needed.
- Shared behavior batches use focused desktop tests. Android/platform changes require Android compilation; ordinary common-only batches share an Android subsystem checkpoint. Android release and desktop packaging remain required before lock.
- Release currently uses DEX/lintVital; minification/shrinking are disabled. Do not call this R8 verification.
- generateBuildConfig declares appVersion, appVersionCode and githubRepo inputs; APK/runtime constants must match.
- Android release signing is private-env-only. Dot-source scripts/load-private-signer.ps1 for local release builds; never regenerate the existing ignored local key. New public fingerprint: 95d9804cfd9c80313440ed97619100c9e9f741c27943f61700fa802d0dbe4574. Legacy public key is rejected; docs/RELEASE_SIGNING.md covers backup/clean-install migration. Set CI secrets only at the final setup gate.
- Pins: AGP 8.13.2, Kotlin 2.3.21, Compose 1.10.3, Ktor 3.5.2; Android min/compile/target SDK 26/36/36. Do not raise the AAR compile ceiling without verification.
- Gradle heap is 2560 MB, distinct from the desktop app's 1024 MB heap.

Desktop runtime JVM invariants:
    -Xms32m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication
    -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:-ShrinkHeapInSteps
    -XX:G1PeriodicGCInterval=8000 -XX:+G1PeriodicGCInvokesConcurrent

## Protocol and harness contracts

- Big three: GEMINI_INTERACTIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES; CUSTOM requires an explicit dialect (openai_chat/openai_responses/anthropic_messages/gemini_interactions) and a verbatim URL. Unresolved Custom blocks Send.
- useStatefulChaining=false and storeServerSide=false by default. Storage, chaining and actual background execution are separate. Gemini background requires an explicit compatible storage opt-in.
- Chains must match the producing model/protocol. Preserve chronological parallel/sequential call/result batches and closing assistant prose.
- Reasoning signatures are opaque and provider-specific: preserve required data, never translate it. Existing signature sanitization still needs a full spec audit.
- Cross-provider reasoning handoff uses prior narrative (up to 2000 chars), not signatures. Preserve uncapped user/system/continuity instructions.
- Discover models/capabilities through live metadata/registries and verified recovery; no model-name/version or manufacturer-specific branches.
- AgentPermissionResolver owns assistant defaults/chat overrides. null inherits; explicit Off/empty path/false revoke. New chats inherit; imports disable local tools/paths/web/Shell. Legacy provider Shell flags never grant full process access.
- Shared dispatch policy covers disabled/unknown/unadvertised calls, read-only/indirect writes and parallel paths. Raw provider options cannot grant tools. Approval modes use local web tools; provider-managed code requires Never.
- ToolApprovalBroker is run/chat scoped: exact previews, one-use permits, exact action run grants, denial suppression and synchronous revision invalidation. Grants are memory-only. Provider requests stop when their policy snapshot becomes stale.
- Shell requires READ_WRITE + explicit FULL_ACCESS. It is not OS-confined; web-tool controls do not constrain Shell networking. Owning-worker cancellation replaces global process kills. Android descendant cleanup is still pending.
- WorkspacePathGuard checks real existing ancestors and rejects escaping links/junctions; recursive scans skip aliases, mutations validate linked trees. PublicWebTransport binds connections to validated public DNS addresses, validates each redirect and cancels revoked requests. Provider transport remains separate.
- CLI/device permissions do not replace Parchment's own tool policy.

## Persistence

- Layout: parchment-settings.json, backups/, chats/<id>/chat.json and per-chat media/. Preserve safe legacy migration and portable relative export paths.
- ChatSession.isCompactStub is transient and snapshot-local. Hydrate from full pending payload before disk; preserve explicit clears/drafts and full content when metadata changes.
- Never save/generate/export from an unavailable full chat. Both JSON/ZIP exports hydrate and verify full history. Imports accept valid empty-chat JSON with omitted messages and strip execution authority.
- Merge background loads into current state without replacing concurrent edits or resurrecting known deletions.
- Strip attachment base64 only when the resolved local file is readable/nonempty; retain inline recovery bytes otherwise.
- appDiskWriter retains latest dirty revisions for every chat/settings/deletion. Clear only successful matching revisions; serialize writes/flushes, preserve in-flight updates and order deletions safely.
- Healthy files/unrelated settings checkpoint independently. Migration settings wait for required chat writes. Failures propagate, remain dirty and retry with bounded backoff; clear only the recovered save notice.
- Shared AtomicFileStorage/SnapshotFileStorage stage, sync and atomically replace. No truncate fallback. Preserve valid backups/corrupt sources and failed migration inputs; reject dot IDs, path escapes and collisions.
- NIO readers close handles before decoding to avoid Windows replacement starvation. Tests use owned unique temporary directories and cleanup.
- Android clipboard images use content URIs through FileProvider/ClipData.newUri; ClipData.newBitmap does not exist.

## UI and performance

- Foundation primitives, KissVectorIcon/ModelLabSvgIcon, no raw OS emoji rendering. Prefer bounded vertical choices/wrapped rows; no horizontal option scrollbars.
- Prose/code baseline 14sp. Compact layouts must handle IME, font scale, long labels, accessibility and calm consistent motion.
- Substantial sampling/typography fields stack vertically in pages and search. SepiaTextField single-line height follows natural text plus padding; multiline fields retain maxHeight. Theme presets share wrapping Foundation radio rows with 48dp targets.
- Tabs: MRU cap 12, preserve active tab/settings; transparent dismissal has indication=null; consume inside gaps, support Back/outside dismissal.
- Notices float as softly rounded rectangles; they wrap without a fixed text-height cap, dismiss by the 48dp control and retain the existing timeout. Four desktop/narrow/font-scale/dark renders and pointer dismissal pass; handset check waits for the final gate.
- Keep raw wire previews <=4096 chars. Slice builders only when lengths change; render only the last 12 interleaved steps while streaming.
- Hydrate active/running chats; dehydrate inactive history; bound caches, queues and allocation. Reuse icon paths and the shared HTTP client.
- Stream workspace/file/web reads. Never rewrite a file using capped workspaceRead (10k lines/250k chars/4k per-line); use append or safe edit/write APIs.
- Optimize only with measurements/equivalence checks; preserve valid rendering, history and recovery.

## Verified baseline

| Commit | Improvement | Local verification |
|---|---|---|
| 01a2849 | Windows native power lease/monitor | 27 tests; concurrent owners, recovery, cleanup |
| 24f4d3a | Runtime build inputs | APK/runtime versions and incremental regeneration |
| cd6c43b | Tabs dimming/narrow fit | Android rendering/taps/Back and builds |
| 39db6ea | Desktop atomic storage | 32 tests |
| d4f5e61 | Dirty-revision writer | 39 tests and Android release |
| 3a6c2d4 | Shell flag at all dispatch paths | 42 tests and Android release |
| e8de0ee | Shared storage/hydration/recovery | 66 tests, both targets/release, Android draft/cold restart |

Windows power helper: synchronized reference ownership, validated heartbeat and independent restart. Use UInt32 2147483649/2147483648, UTF-16LE EncodedCommand and silent progress. Actual sleep/Modern Standby/battery policy can suspend execution; no unconditional sleep immunity.

RunActivity/RunLeaseRegistry own process-local elapsed time and absolute platform owner snapshots. Acquire before replacing/queueing workers; close only that lease on actual job completion, including never-started cancellation. Streams/utilities inherit a parent run; standalone streams own a lease. Tool batches track each runnable/waiting/completed branch; exclude approval-only waits and subtract the union of active tool intervals once. Keep a visible Android service while waiting, with CPU/Wi-Fi holds released. Heartbeats reconcile live owners every 15 seconds; a new process has none. Durations use Android uptime / desktop nanoTime; one-second samples exclude unobserved scheduling gaps over five seconds, so brief desktop suspension is a timing limit. Wall-clock persistence timestamps stay separate.

Device: OnePlus PLZ110, Android 17/API 37; unplugged until the final gate. Installed verification APK 2.0.64/264 matches prior signer. Draft/checkpoint/cold-restart and usage layout passed; new permission/background/UI source is not installed yet.

## Current handoff

- Working batch 23A is local/unlocked. New usage models/parser/ledger, five JSON/SSE adapters, persisted continuation records and vertical metrics are implemented.
- TODO holds current results and the next batch; do not reload all handoff/history entries on routine resumes. New transport/usage fixtures are in commonTest/desktopTest.
- Records use wide/nullable counts, owner/run/purpose identities, cumulative coalescing and TTL pricing. Title/summary/compaction/translation/draft usage persists in the owning chat; failed requests stay unknown.
- Session totals count every owned reply version/request once, exclude copied fork charges and retain legacy estimates as partial. User-token estimates no longer duplicate provider input. Model snapshots preserve explicit free/unknown prices, exact units and TTL/context tiers; 5.5b's 49-test gate was recovered from the saved session.
- Fee records retain reported gateway totals, matching audio/search/request metadata units and direct media attempts. Image/video hard failures stop probing. Missing modality/cache splits, per-image/container/video fees and external BYOK billing remain explicit. Current verification/next action is in TODO.
- Local permission batch: both Kotlin targets compile; 30 focused permission/HTTP/web/hydration tests pass after fixing empty-chat imports. Earlier 109-test gate had only that import failure; Windows junction/Shell ownership tests passed. Device fit and remaining section-2 checks are in TODO.
- Custom Chat now dispatches its declared wire; Custom disables dialect-changing fallback. Interactions handles step usage and final text suffixes without extra nudges.
- CI run 64 (e8de0ee) failed HTTP 504 downloads of Gradle/Kotlin, not source compilation. Kotlin download recovered; Gradle URL still returned 504. No release was published.
- Android stash 7e1827538dd5e9bbf8ad836346bbeda62577104c is merged locally; retain it until device integration passes. Owned session control, bounded CPU leases/independent heartbeats and foreground/notification notices compile; 18 protocol/HTTP regressions pass.
- Process state retains chats/jobs/queues/approvals across UI recreation. Coordinator uses run/target identities and frozen providers; new runs await draining workers and refresh saved history. Nine ownership/clock/handoff tests pass. Utility requests mask inherited tool context, disable tools/goal loops and propagate cancellation; 26 focused tests/both Kotlin targets pass.
- Feature coverage/gaps: docs/FEATURE_COVERAGE.md. Follow TODO's quick-first order; actual handset recreation/cleanup remains at the final gate.
- CI: current Node-24 actions, regression-before-package gate, limited transient-download retries, official Gradle checksum and no PR release secrets. Retry/signer Python tests and actionlint pass; no GitHub rerun or release yet.
- Read TODO, git status and any running tool session before resuming.
