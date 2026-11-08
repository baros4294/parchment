# Parchment Production Quality Plan

Updated: 2026-11-08. Baseline: main at 4dad8ab671ab5add25a8fa21c40f960d03ea31ea (published v2.0.57).

## Execution contract

- Plan each improvement before editing its implementation. Investigate every source area and refine this plan when evidence reveals another defect.
- Implement focused changes, run meaningful regressions and the relevant platform builds, then commit and push to origin/main.
- An item is complete only after its implementation, verification and lock succeed. Failed verification keeps the item open.
- Preserve the five existing platform edits and review them as part of the relevant background-lifecycle milestones.
- Never copy builds into the installed Windows Parchment directory. Use workspace build artifacts for testing and GitHub Release OTA for installed updates.
- Keep Compose Foundation UI, vector icons, bounded vertical palettes and wrapped rows. Keep model routing independent of model identifiers and device routing independent of manufacturer/version names.
- Search the web and retrieve current official documentation before changing protocol behavior. This session has no exposed web_search tool; use actual HTTP web searches and official page retrieval, recording the sources below.
- Never optimize by dropping history, changing user instructions, weakening permission checks or silently enabling provider-side storage.
- Use JDK 21, the local Android SDK and --no-daemon for Gradle. Verify compiler execution strategy before changing daemon configuration.
- Do not hide unresolved runtime verification behind a passing compilation. Record limitations and leave those acceptance checks open.
- Continue until all concrete findings are resolved, required checks pass, and a final audit finds no unresolved critical or high-priority defects.

## Verified baseline observations

- Windows: S0 Low Power Idle / Network Connected; AC and DC sleep-after settings are both 300 seconds.
- Windows PowerShell interprets the current helper's 0x80000001 as a negative Int32; conversion to the native UInt32 argument fails. The published helper uses the same signed-literal pattern with 0x80000041.
- Latest published Android manifest still declares dataSync. The working tree's specialUse and concurrent-run changes are not in v2.0.57.
- Android test device is now available and authorized: OnePlus PLZ110, Android 17/API 37, ColorOS V17.0.0; installed Parchment 2.0.57, versionCode 257, targetSdk 36.
- Device notification permission is not granted. Parchment is on the user battery-exemption list; Data Saver is disabled.
- Historical device exits include OTHER KILLS BY SYSTEM at foreground-service importance 125. Proprietary o-kill/o-stop codes have no verified public interpretation; do not branch on them.
- No Parchment foreground service was active during the initial device inspection. This alone does not establish a defect because no active generation was verified.
- Test workers use temporary storage rather than the user's installed app data. Their shared fixed temporary directory still needs isolation and cleanup review.
- UI frame pacing already times out unavailable frames and uses a delay fallback; do not misdiagnose an indefinite frame-clock wait.
- Tool dispatch receives root/mode, but not the Shell toggle or a complete enabled-tool policy. Sequential and parallel paths both require enforcement.
- Memory and checklist tools can write workspace files despite their broad read-only metadata.
- Built-in path checks are lexical, and a shell's working directory provides no OS process confinement.
- Disk-save scheduling cancels the previous pending session save; investigate loss of dirty changes when different chats update.
- Native power hooks are scoped to a network generation call instead of the complete agent run.
- The Gemini background toggle controls poll recovery of a stateful interaction; the outgoing request currently does not request background=true.
- Google documents store=false as incompatible with background execution. Keep stateless defaults and require explicit opt-in for stored server recovery.

## Prioritized implementation queue

### 01 — P0: Baseline, exhaustive audit and protocol evidence

- [ ] Map every source file, build/release configuration and public boundary; distinguish verified defects from investigation candidates.
- [ ] Fetch current official references for Responses, Chat Completions, Gemini Interactions, GenerateContent, Anthropic Messages, streaming, reasoning/signatures and tool-result formats.
- [ ] Run the existing desktop suite and Android compilation; record exact outcomes and test counts.
- [ ] Review test storage, subprocess cleanup and isolation before broadening the suite.
- Files: all source areas; PARCHMENT_TODO.md; build and workflow configuration.
- Verification: source coverage matrix below, fetched official references, baseline test/build results.
- Lock: include this plan and baseline evidence in the first verified implementation commit.

### 02 — P0: Correct and monitor Windows power requests

- [x] Replace signed PowerShell literals with correctly typed native values for both acquisition and release.
- [x] Confirm native acquisition success; do not treat a live helper process as proof of an active power request.
- [x] Serialize holder creation/rearming and preserve concurrent-session reference ownership.
- [x] Ensure helper termination/release when the final owner stops or the parent exits.
- [x] Keep the documented Modern Standby/DC limits visible; do not claim local execution continues through actual sleep.
- Files: desktopMain/platform/PlatformBridge.desktop.kt; desktopTest/platform/WindowsPowerLeaseTest.kt; PARCHMENT_MEMORY.md.
- Verification: real native-call/process-boundary regression, two owners with one completing, helper failure/termination, desktopTest and desktop compilation.
- Lock: completion is published by the commit containing this verified Windows milestone; confirm origin/main reachability after push.

### 19 — P0: Generated build configuration tracks its inputs

- [x] Declare appVersion, appVersionCode and githubRepo as generateBuildConfig inputs.
- [x] Verify changing build properties regenerates runtime constants and matches APK metadata; unchanged inputs remain up to date.
- [x] Re-run relevant desktop/Android/release checks and verify the signing identity before device validation.
- Files: composeApp/build.gradle.kts; build verification evidence; MEMORY/TODO/README.
- Evidence: validation APK manifest was 2.0.59/259 while generated runtime constants remained 2.0.0/200.
- Lock: verified build-cache correction commit and push.

### 03 — P0: Atomic persistence and lossless dirty-session scheduling

- [x] Desktop atomic storage verified: complete staging/sync, atomic target and backup replacement, sharing-friendly reads, bounded transient-lock recovery; five new regressions. Android replacement and shared scheduling remain open.
- [ ] Review settings, chat, backup and temporary-file replacement paths on both platforms.
- [ ] Replace truncate-in-place fallbacks with safe staged replacement and recoverable failure handling.
- [x] Coalesce all dirty session IDs through one serialized writer; retain per-file revisions, in-flight updates, deletions and lifecycle checkpoints.
- [ ] Propagate native save errors into the shared writer; current legacy native wrappers can still suppress I/O failures.
- [ ] Keep disk checkpoints independent of rendering and avoid synchronous full-vault rewrites during activity transitions.
- [ ] Recover from truncated/corrupt writes without overwriting valid history with defaults or dehydrated stubs.
- Files: ParchmentStatePersistence.kt; ParchmentApp.kt persistence callbacks; both PlatformBridge actuals; common/desktop persistence tests.
- Verification: interrupted replacement, failed write, two chats updating, shutdown flush, corrupt primary with valid backup, dehydration/import regressions; both platform compilations and assembleRelease.
- Lock: verified persistence commit and push.

### 04 — P0: Authoritative agent permissions and cancellable approvals

- [ ] Add assistant defaults and explicit chat overrides for approval, sandbox/file access, enabled tools, shell and web-tool permissions.
- [ ] Distinguish inherited settings from explicit Off/unmount. Move local permissions out of provider ownership.
- [ ] Migrate existing workspaces without silently granting full access or preserving blanket approval.
- [ ] Enforce current policy immediately before every actual effect, including unadvertised tool names, parallel calls, memory append/overwrite and checklist file writes.
- [ ] Add an asynchronous approval broker with exact action previews, Allow once, narrowly scoped Allow for this run, Deny and Stop.
- [ ] Revalidate pending requests after policy/file changes; cancellation and repeated denial must not start work or loop prompts.
- [ ] Imported chats/settings and model-generated arguments cannot mint approval grants.
- Files: Models.kt; new harness/AgentPermissionGate.kt and ToolApprovalBroker.kt; WorkspaceHarness.kt; AiProviderEngine.kt; persistence/migrations; O200kTokenizer.kt for matching active-tool accounting.
- Verification: disabled shell in read/write mode, unknown/disabled calls, read-only writes, Never-ask still bounded, denial/cancellation/revocation, parallel authorization, indirect writes and migration/import fixtures.
- Lock: verified permission engine commit and push before exposing permissive UI choices.

### 05 — P0: Real filesystem/network boundaries and scoped process cancellation

- [ ] Resolve existing ancestors and targets against allowed roots; prevent symlink/junction escapes in reads, writes and recursive list/glob/search.
- [ ] Validate both source and destination of moves and every target of multi-edit.
- [ ] Check web-tool hosts and redirects; distinguish required provider transport from tool network access.
- [ ] Restricted modes cannot run arbitrary shell without verified OS process confinement. Full access must be explicit and accurately described.
- [ ] Replace global shell termination during Stop/Steer/compaction with ownership by session/run.
- [ ] Make reusable command grants exact or securely scoped; never grant a broad interpreter prefix by inference.
- Files: WorkspaceHarness.kt; common PlatformBridge contract; both actual bridges; run/permission context; filesystem/network/process tests.
- Verification: traversal, symlink/junction fixtures, moves, redirected/private destinations, disabled network, two simultaneous processes with one cancelled, stale grants.
- Lock: verified boundary/cancellation commit and push.

### 06 — P0: Whole-run ownership, checkpoints and safe recovery

- [ ] Add an AgentRunCoordinator with unique run/session ownership and explicit preparing/running/waiting/interrupted/stopped/completed phases.
- [ ] Hold protection across tool rounds, compaction and queue handoff rather than individual HTTP calls.
- [ ] Keep run data/checkpointing independent of Compose lifecycle while publishing compact progress updates.
- [ ] Persist original provider/model, target variant, known tool outcomes, pending actions and valid continuation state.
- [ ] Resume after OS/network interruption using verified continuation or local history.
- [ ] Never automatically replay a mutation with unknown outcome or restart a user-stopped run.
- [ ] Release CPU/power holds when all runs are awaiting human approval, and reconcile owners after restart.
- Files: new harness/AgentRunCoordinator.kt; Models.kt; ParchmentApp.kt; ParchmentStatePersistence.kt; AiProviderEngine.kt; PlatformBridge contract.
- Verification: compaction with uninterrupted lease ownership, missing UI frames, queue handoff, two tabs, process-restart fixtures, socket interruption, pending approvals, unknown mutation outcomes and explicit user Stop.
- Lock: verified coordinator/checkpoint commit and push.

### 07 — P0: Android foreground-service readiness and device verification

- [ ] Review/adapt existing specialUse changes against current official requirements.
- [ ] Confirm foreground startup while an activity is visible; expose startup/promotion/lock failures.
- [ ] Initialize application context safely for service-led restart; refresh power/notification state on resume.
- [ ] Maintain bounded renewable CPU leases from run liveness, not token arrival; remove reliance on foreground/screen-on Wi-Fi lock guarantees.
- [ ] Restore only valid persisted runs, distinguish user stop from system interruption, and avoid phantom notifications/locks.
- [ ] Request notification access through the activity UI; denial does not by itself prohibit foreground-service startup.
- [ ] Run device tests on Android 17/API 37 using generic capabilities, not OnePlus/ColorOS branches.
- [ ] Add app-owned instrumentation for private service lifecycle checks; direct shell starts were correctly rejected by exported=false and are not a startup-failure result.
- Files: AndroidManifest.xml; LongDecodeForegroundService.kt; MainActivity.kt; PlatformBridge.android.kt; application bootstrap if needed; background-state UI.
- Verification: compileDebugKotlinAndroid and assembleRelease, then on-device app switching, screen-off/Doze, notification denial, compaction, two runs, pending approvals, process death and cleanup.
- Lock: verified Android improvement commit(s) and push; runtime acceptance remains open until device results exist.

### 08 — P0: Explicit protocol contracts and lossless historical replay

- [ ] Verify each request/stream/tool-result builder against fetched official schemas.
- [ ] Preserve Custom endpoint URL and declared dialect; do not silently switch its payload or endpoint through fallback.
- [ ] Stamp the actual wire dialect/model on completed and interrupted turns.
- [ ] Preserve opaque reasoning/signature data exactly where the official protocol requires it; do not infer private encryption/protobuf formats.
- [ ] Preserve parallel and sequential tool boundaries and closing assistant prose.
- [ ] Implement documented continuation/stop/error behavior and test fragmented streaming.
- [ ] Separate server storage, chaining and genuine background execution; stateless remains the default.
- Files: AiProviderEngine.kt and eventual protocol adapters; Models.kt; protocol/history tests; provider settings.
- Verification: official-schema fixtures and local HTTP/SSE round trips for all five wire paths plus Custom; malformed/fragmented events, reasoning/tool-only endings, continuation and cross-provider/model isolation.
- Lock: separate verified protocol improvements and pushes; no protocol edit without recorded documentation evidence.

### 09 — P1: Universal model discovery and modality routing

- [ ] Remove model-name/version substring routing and prefer explicit model specs, live discovery and registry metadata.
- [ ] Review canonical alias collisions, missing values and source precedence instead of taking unsafe maxima/defaults.
- [ ] For unknown capabilities, use only documented/observed endpoint-parameter recovery before output or accepted side effects.
- [ ] Do not infer realtime support solely from text/audio output modalities.
- [ ] Keep lab identity derived from creator metadata and preserve explicit Custom behavior.
- Files: ModelsDevClient.kt; AiProviderEngine.kt routing; Models.kt capability data; ParchmentIcons.kt creator lookup; registry tests.
- Verification: arbitrary model IDs, misleading names, conflicting registries, explicit overrides, unknown modalities, accepted jobs without duplicate creation and Custom endpoints.
- Lock: verified discovery/routing commit and push.

### 10 — P1: Correct usage, caching, billing and timing

- [ ] Verify provider-specific input/output/reasoning/cache read/cache creation semantics.
- [ ] Prevent double counting, overflow, non-finite pricing values and misleading estimates.
- [ ] Keep approval/suspension waiting separate from actual generation/tool timing.
- [ ] Use monotonic duration measurement where applicable while retaining wall time for persisted timestamps.
- Files: Models.kt; AiProviderEngine.kt; ModelsDevClient.kt; O200kTokenizer.kt; metrics UI/tests.
- Verification: official usage fixtures, multiple rounds, parallel waits, cache creation pricing, incomplete usage, approval pause, clock changes and large totals.
- Lock: verified metrics commit and push.

### 11 — P1: Streaming OTA, integrity and cancellation

- [ ] Stream installer downloads to staged files instead of accumulating and concatenating whole byte arrays.
- [ ] Validate completion/size and available authoritative digest/signing evidence before installation.
- [ ] Preserve cancellation and clean only owned temporary files; interrupted download cannot launch an installer.
- [ ] Checkpoint active runs and backups before maintenance/OTA transition.
- [ ] Keep updates inside the established release/OTA workflow.
- Files: OtaUpdateEngine.kt; PlatformBridge contract/actuals; OTA state UI; installer/release workflow as justified.
- Verification: interrupted/malformed/truncated HTTP download, unknown Content-Length, digest mismatch, cancellation, cleanup and data preservation; desktop package and Android release builds.
- Lock: verified OTA commit and push.

### 12 — P1: Bounded media/archive/file I/O

- [ ] Audit attachment, image decode, ZIP export/import and storage-manager reads for whole-file copies and unbounded allocation.
- [ ] Reject malformed/unsafe archives before committing extracted state; preserve relative per-chat media paths.
- [ ] Bound work/pixel allocation and stream where possible without truncating editable source files.
- [ ] Review workspaceRead full-file counting, shell output limits and cancellation responsiveness.
- Files: both PlatformBridge actuals; persistence export/import; CardAndPromptCodec.kt; media UI; WorkspaceHarness.kt.
- Verification: large inputs, archive traversal/bombs, malformed PNG/JSON/media, interrupted extraction/export and existing round trips.
- Lock: verified I/O improvements and pushes.

### 13 — P1: Streaming/rendering performance without semantic loss

- [ ] Replace repeated full-buffer word scans with tested incremental accounting where observed.
- [ ] Bound registry/SVG/path/text caches and avoid unnecessary full history/state copying during deltas.
- [ ] Maintain compact bounded wire previews, active-session hydration and streaming timeline windows.
- [ ] Audit nested Markdown/LaTeX/SVG parsing and rendering for pathological input, recursion and repeated parsing.
- Files: ParchmentApp.kt; ParchmentComponents.kt; ProseRenderer.kt; LatexMathRenderer.kt; SvgSceneRenderer.kt; ParchmentIcons.kt; registry/cache code.
- Verification: incremental/full-result equivalence, Unicode/chunk boundaries, nested/fuzz inputs, large streaming turns, allocation/work measurements and rendered desktop/mobile output.
- Lock: verified performance/regression improvements and pushes; reject optimizations that change valid output.

### 14 — P1: Workspace/Tools permission UI and background diagnostics

- [x] Fix Tabs-menu screen dimming; remove full-screen dismissal indication, adapt narrow cards and sheet height, verify actual Android rendering/interactions and desktop/Android compilation.
- [ ] Use a shared effective-policy editor in Workspace settings and the Tools drawer.
- [ ] Expose approval, access, folders, enabled tools, shell/network and long-run readiness without misleading sandbox claims.
- [ ] Keep the Manual Override Bar first; use existing vectors and bounded wrapped layouts.
- [ ] Replace automatic privilege escalation on locked-tool chips with explicit policy editing.
- [ ] Show owning-chat approval requests and background/power states; settings changes affect future effects immediately.
- [ ] Keep global settings search and context/tool counts consistent with the new controls.
- Files: ParchmentSettingsPages.kt; ParchmentApp.kt; ParchmentComponents.kt; ParchmentModalsAndNavigation.kt; O200kTokenizer.kt.
- Verification: both targets, desktop and compact device renders, inheritance/reset, two-tab pending approvals, revocation, keyboard/back navigation and no redundant/emoji UI regressions.
- Lock: verified UI commit and push after the enforcement layer.

### 21 — P1: Whole-app performance, memory, startup and modularity

- [ ] Measure cold/warm startup, first useful frame, ready-to-type time, idle working set/heap, streaming frame work/allocations, and large-session/vault behavior on desktop and Android.
- [ ] Keep startup work off the critical UI path where safe; review settings/chat loading, migrations, fonts/icons, tokenizer warming, model discovery and hydration.
- [ ] Minimize idle CPU/disk/network work, redundant serialization, full-buffer scans, large byte/string copies, duplicate attachment payloads and retained inactive state.
- [ ] Bound caches and queues; preserve the lean runtime heap, compact inactive chats and windowed streaming timelines without losing content.
- [ ] Remove proven dead code/imports/dependencies and consolidate tested duplication across protocols, persistence, platform I/O and UI.
- [ ] Split oversized controllers/adapters into clear modules with explicit ownership and contracts; keep platform code and shared logic separated.
- [ ] Record before/after measurements and regression outcomes for each optimization/refactor; preserve correctness, history, permissions and recovery behavior.
- Files: modules justified by profiling/audit; ParchmentApp/StatePersistence/DiskWriter, provider/registry, renderers/icons, platform bridges, build configuration and focused regression/benchmark code.
- Verification: representative cold/warm launches, idle and sustained streaming measurements, large chats/media/tool sequences, behavior-equivalence tests, both platform builds and real-device UI behavior.
- Lock: each measured/verified improvement committed and pushed; update MEMORY, TODO and README with practical limits and final results.

### 17 — P1: UI consistency, mobile fit and calm editorial motion

- [ ] Audit all option rows, chip groups, drawers, palettes and settings; prefer compact vertical lists for substantial choices and remove horizontal option scrolling.
- [ ] Identify clipped, truncated or unnatural controls on the actual Android device at portrait/landscape widths, with IME open, long labels and enlarged font scale.
- [ ] Keep necessary action text readable; distinguish deliberate content previews from accidental truncation.
- [ ] Unify spacing, typography, vector icon sizes, corners, borders, hover/press feedback and selected/disabled/error states.
- [ ] Make transitions calm and consistent with shared motion primitives; prevent layout jumps, overlapping animations and excessive effects.
- [ ] Respect platform motion/accessibility settings and maintain touch targets, contrast, keyboard navigation and screen-reader semantics.
- [x] Verify Tabs dismissal has no full-screen indication/dimming and that sheet interactions do not accidentally dismiss it.
- Files: ParchmentApp.kt; ParchmentComponents.kt; ParchmentSettingsPages.kt; ParchmentModalsAndNavigation.kt; ParchmentTheme.kt; relevant platform UI hooks only if required.
- Verification: rendered desktop and real-device screenshots/interactions, keyboard and font-scale matrix, existing regression suite, both targets and Android release build.
- Lock: focused verified UI improvements and pushes; update MEMORY, TODO and README with the final behavior.

### 18 — P1: Current 2026 capability coverage for all three workflows

- [ ] Build an evidence-based feature matrix for agent work, everyday chat and roleplay, recording implemented/partial/missing status and current official sources.
- [ ] Classify core requirements, workflow-dependent capabilities and optional additions; prioritize practical gaps while retaining KISS.
- [ ] Agent coverage: safe approvals/access boundaries, reliable background/recovery, accurate tool/protocol state, parallel calls, user questions/steering/queues, context management, evidence/diffs, cost/usage observability and extension/integration needs.
- [ ] Daily-chat coverage: dynamic model/provider capabilities, search/navigation, editable/regenerable/forkable messages, attachments/media/PDF, voice, rich rendering/copy, privacy, backup/export, OTA and responsive mobile operation.
- [ ] Roleplay coverage: character/scenario cards, greetings/variants, lore activation, persona/macros, author notes, continuity memory, branching/rewind, configurable context and readable dialogue/narration.
- [ ] Check current protocol/platform support rather than fixed model-name lists; add only justified missing core capabilities as individual planned/testable tasks.
- [ ] Verify end-to-end workflows and keep the capability matrix/documentation current after each implementation.
- Files: PARCHMENT_TODO.md feature matrix; affected modules justified by confirmed gaps; README.md and PARCHMENT_MEMORY.md.
- Verification: current official documentation, code-path evidence, workflow tests and rendered/runtime output; no unsupported "bleeding-edge" or completeness claims.
- Lock: every implemented capability separately verified and pushed.

### 15 — P2: Build/CI/process hygiene and documentation

- [ ] Verify and select compiler execution behavior that does not leave orphan Gradle/Kotlin daemons.
- [ ] Keep SDK/dependency compatibility ceilings and distinguish build heap from app runtime heap.
- [ ] Add appropriate regression execution to CI before packaging/publication.
- [ ] Correct README/CONTRIBUTING/PARCHMENT_MEMORY claims and commands to match tested behavior and documented OS limits.
- Files: gradle.properties; build.gradle.kts as needed; workflows; README.md; CONTRIBUTING.md; PARCHMENT_MEMORY.md.
- Verification: repeated local/CI-equivalent builds, daemon cleanup, both distributions, factual/documented commands.
- Lock: verified build/documentation commits and pushes.

### 16 — P2: Maintainability/scalability refactors and final adversarial audit

- [ ] Refactor large controllers/adapters only around verified contracts and measured duplication.
- [ ] Review all remaining error boundaries and remove dead/duplicated paths after equivalence tests.
- [ ] Repeat the source coverage/security/recovery/performance audit and add newly evidenced tasks here.
- [ ] Run the complete regression matrix and final working-tree/remote verification.
- Files: only modules justified by the repeated audit.
- Verification: behavior-equivalence tests, both targets/release/package, device and desktop runtime matrix, no unresolved critical/high-priority findings.
- Lock: every improvement verified and pushed; all completed items reachable from origin/main.

## Audit coverage matrix

| Area | Files | Current status |
|---|---|---|
| Domain/settings | Models.kt | Architecture and defaults mapped; permission/metric deep audit open |
| Agent harness | WorkspaceHarness.kt | Dispatch, side effects and cancellation gaps confirmed; complete boundary review open |
| Provider transport | AiProviderEngine.kt | Five paths mapped; current-schema verification and full stream/retry audit open |
| Model registry | ModelsDevClient.kt | Sources/routing mapped; metadata precedence and bounded cache audit open |
| Persistence | ParchmentStatePersistence.kt | Split storage/dehydration mapped; atomic/dirty scheduling audit open |
| UI controller | ParchmentApp.kt | Streaming/queue/compaction mapped; state ownership and repeated work audit open |
| Settings/navigation | SettingsPages, ModalsAndNavigation, Components, Theme | Architecture mapped; permissions and compact rendering verification open |
| Prose/math/SVG/icons | ProseRenderer, LatexMathRenderer, SvgSceneRenderer, ParchmentIcons | Public contracts mapped; pathological inputs/caches/rendering audit open |
| Codecs/tokenization | CardAndPromptCodec, O200kTokenizer | Existing test coverage mapped; boundary and accounting audit open |
| Desktop platform | Main.kt, PlatformBridge.desktop.kt | Window/storage/process/power mapped; native power defect reproduced |
| Android platform | MainActivity, bridge, foreground service, file provider, OTA receiver, manifest | Device facts verified; implementation and lifecycle tests open |
| OTA/build/release | OtaUpdateEngine, installer, Gradle, workflow | Architecture mapped; streaming/integrity/CI audit open |
| Tests | commonTest suite; new focused tests as needed | 20 existing tests identified; baseline execution open |

## Verification commands

Set JAVA_HOME=C:/Users/Baros/.jdks/temurin-21 and ANDROID_HOME=%LOCALAPPDATA%/Android/Sdk in the same shell invocation; set ANDROID_SDK_ROOT to ANDROID_HOME.

- Desktop regression: gradlew.bat :composeApp:desktopTest --no-daemon
- Desktop compilation: gradlew.bat :composeApp:compileKotlinDesktop --no-daemon
- Android compilation: gradlew.bat :composeApp:compileDebugKotlinAndroid --no-daemon
- Android release gate: gradlew.bat :composeApp:assembleRelease --no-daemon
- Desktop package gate when relevant: gradlew.bat :composeApp:createDistributable --no-daemon
- Record exact test/build/device outcomes for each milestone below before lock.

## Official documentation evidence

Fetched and read during the audit; retrieve specific sections again before implementing an affected contract.

- Windows execution state: https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate
- Modern Standby power-request limits: https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest
- Android foreground-service types/timeouts: https://developer.android.com/develop/background-work/services/fgs/service-types and https://developer.android.com/develop/background-work/services/fgs/timeout
- Android Doze: https://developer.android.com/training/monitoring-device-state/doze-standby
- Notification permission: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Wi-Fi lock limits: https://developer.android.com/reference/android/net/wifi/WifiManager
- Process importance: https://developer.android.com/reference/android/app/ActivityManager.RunningAppProcessInfo
- Gemini Interactions schema: https://aistudio.google.com/api-reference/interactions-api
- Gemini state/storage: https://aistudio.google.com/docs/interactions-overview
- GenerateContent schema: https://aistudio.google.com/api-reference/generate-content
- Gemini thinking/signatures: https://aistudio.google.com/docs/thinking . Thought blocks must be replayed unchanged; implementation review remains open.
- OpenAI reasoning: https://developers.openai.com/api/docs/guides/reasoning
- OpenAI Responses references fetched successfully: https://developers.openai.com/api/reference/resources/responses/methods/create and https://developers.openai.com/api/reference/resources/responses . Chat reference: https://developers.openai.com/api/reference/resources/chat .
- Anthropic Messages: https://platform.claude.com/docs/en/api/messages
- Anthropic tool use: https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview
- Anthropic thinking/caching: https://platform.claude.com/docs/en/build-with-claude/extended-thinking and https://platform.claude.com/docs/en/build-with-claude/prompt-caching
- Kotlin compilation/cache documentation: https://kotlinlang.org/docs/gradle-compilation-and-caches.html . Installed KGP 2.3.21 and successful builds validate the quoted '-Pkotlin.compiler.execution.strategy=in-process' argument.

## Verification and lock log

- Shared disk-writer milestone verified: 39/39 desktop tests, both compilations and Android release passed; seven regressions cover asynchronous dirty-write ordering/recovery. The commit containing this entry publishes the scoped scheduler improvement.
- Desktop atomic-storage milestone locked and pushed: 39db6ea74e902094aa49dca700c6ea8664d16ff9; 32 tests and target compilation passed.
- Desktop atomic-storage milestone verified: 32/32 tests, including five new filesystem regressions; desktop and Android debug compilation passed. The commit containing this entry publishes the scoped desktop improvement.
- Tabs milestone locked and pushed: cd6c43bfbe4875c67698d9666b441b695085cc54, with Android device interactions/rendering and compilation/release verification.
- Build-input milestone locked and pushed: 24f4d3a6a1661ac81ed88bd6289735c67980d080. 27/27 regressions, both targets and Android release passed; unchanged/changed-input behavior and restored SHA-256 were verified.
- Tabs mobile verification passed on Android 17/API 37: compact full-width card/list, content-sized sheet, identical outside-sheet RGB (244,236,216), inside-gap tap preserved the menu, outside tap and Back dismissed it. The commit containing this completion publishes the verified shared UI fix; broader UI audits remain open.
- Baseline: 20/20 existing desktop tests passed; Android debug compilation passed. An initially unquoted Gradle property was split by PowerShell; the quoted invocation passed.
- Windows milestone: 27/27 desktop tests passed (20 existing + 7 native/process regressions); desktop and Android debug compilation passed. Tests now guard UInt32 conversion, Java/PowerShell command encoding, progress output, owner races, quiet helper recovery and parent/final-owner cleanup.
- Windows milestone locked and pushed: 01a28498458ec0141f24a188ebe80b2ce9ff0048; origin/main reachability verified. GitHub rejected the initial private author email, so only the unpublished commit was amended using the repository's existing verified noreply address. The remaining queue is open.

## Previous completed work

The composer Custom inputs were moved into the Tools drawer Manual Override Bar, both targets/release were verified, memory was updated and the change was locked before this audit. That completed work is preserved as baseline context.
