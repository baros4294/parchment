# Parchment Memory

Updated 2026-11-11. Stable contracts only: read the section you need. AGENTS.md owns workflow; TODO owns progress.

## Standing decisions

- Keep changes local until all TODO work and the final matrix pass, then one commit/push to origin/main. Never force-push, overwrite unrelated work or claim unverified completion.
- No automatic local Git snapshots of verified batches (user decision, 2026-11-10); don't re-propose them.
- Scripted phone taps must first confirm Parchment is in front.
- Never optimize by losing history or instructions, weakening permissions, altering opaque signatures or silently enabling server storage.

## Project and build

Kotlin Multiplatform/Compose Foundation app for Windows and Android. Shared code: composeApp/src/commonMain; JVM storage and I/O: src/jvmShared; platform actuals: desktopMain/androidMain. Put logic used by both platform bridges in jvmShared (WorkspaceFileEditor, LocalMediaStore, ChatArchiveWriter/Extractor); bridges stay thin.

- Pins: AGP 8.13.2, Kotlin 2.3.21, Compose 1.10.3, Ktor 3.5.2; Android min/compile/target SDK 26/36/36.
- JDK: C:/Users/Baros/.jdks/temurin-21 (inherited JAVA_HOME/PATH point to JDK 8). Android SDK: %LOCALAPPDATA%/Android/Sdk; set ANDROID_HOME and ANDROID_SDK_ROOT in the same shell.
- scripts/verify.py is the only entry point: it supplies JDK/SDK, version flags 2.0.64/264, two workers and the configuration cache (not final: signer task), and keeps logs and its ledger in .parchment-local/verification/.
- Keep org.gradle.daemon=false (an earlier daemon caused memory bloat). Gradle heap is 2560 MB, separate from the app's 1024 MB.
- Text files are UTF-8 without a BOM and use LF, except .bat and .iss. PowerShell scripts stay ASCII.
- Never replace or copy builds into %LOCALAPPDATA%/Programs/Parchment. Installed Windows updates use GitHub Release OTA; test workspace artifacts.
- Release uses DEX/lintVital; minification and shrinking are disabled, so do not call it R8 verification. generateBuildConfig declares appVersion, appVersionCode and githubRepo inputs.
- Android aborted cancelling a 565-register coroutine; keep big suspend bodies split into local suspend functions.
- Android signing is private-env-only: dot-source scripts/load-private-signer.ps1 and never regenerate the ignored local key. Public fingerprint: 95d9804cfd9c80313440ed97619100c9e9f741c27943f61700fa802d0dbe4574. The legacy key is rejected; docs/RELEASE_SIGNING.md covers migration. Unset or blank key passwords resolve to the store password. All four encrypted CI signer secrets stay configured until a release ships that fix.
- Desktop JVM flags live in composeApp/build.gradle.kts (1 GB cap, G1 uncommit). The packaged app adds AppCDS: jlink base + `$APPDIR/parchment.jsa`, rebuilt on update.

## Protocols and harness

- AiProviderEngine keeps shared state and orchestration (streamGeneration). Each protocol, plus media, citations, reasoning, tools, discovery, history and error policy, lives in its own `network/` file as extensions on the object; calls outside the package import those extensions.
- Big three: GEMINI_INTERACTIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES. CUSTOM requires an explicit dialect and verbatim URL, dispatches its declared wire and never falls back to another dialect; unresolved Custom blocks Send.
- useStatefulChaining and storeServerSide default to false. Storage, chaining and background execution are separate; Gemini background requires explicit compatible storage opt-in. keepsProviderState (storage or chaining) drives `store`, polling and cancel.
- Chains must match the producing model and protocol. Preserve chronological call/result batches and closing prose; ToolCallRecord.startsToolStep keeps sequential steps apart from parallel calls.
- Turns are stamped with resolveWireProtocol, never a Custom profile's stored protocol. Chat replays reasoning_content only within its family, closing prose included (DeepSeek returns 400 without it when tools are sent). History tool IDs go through ReplayToolCallIds.
- Reasoning signatures are opaque and provider-specific: preserve, never translate. Signature sanitization still needs a full spec audit. Cross-provider handoff uses prior narrative (up to 2000 chars). User, system and continuity instructions stay uncapped.
- Discover models and capabilities through live metadata and registries; no model-name, version or manufacturer branches.
- AgentPermissionResolver owns defaults and overrides: null inherits; explicit Off, empty path or false revoke. New chats inherit; imports disable local tools, paths, web and Shell.
- Shared dispatch covers disabled, unknown, unadvertised, read-only and parallel paths. Raw provider options cannot grant tools. Provider-managed code requires approval mode Never.
- ToolApprovalBroker is run/chat scoped with exact previews, one-use permits, denial suppression and synchronous invalidation. Grants are memory-only.
- Shell requires READ_WRITE plus explicit FULL_ACCESS and is not OS-confined; web-tool controls do not limit Shell networking. Owning-worker cancellation replaces global kills.
- WorkspacePathGuard checks real ancestors and rejects escaping links and junctions. PublicWebTransport binds connections to validated public DNS addresses and validates each redirect. Provider transport is separate.
- SSE that closes without the provider's finish signal or `[DONE]` is an interruption, not an answer. AcceptedJobException marks work the provider accepted: poll it, never resubmit it.
- Utility requests mask inherited tool context, disable tools and goal loops, and propagate cancellation.
- computer_use is off by default: every action needs approval, with no run-wide grants. Screenshots stay out of chat state and only the newest is resent. Permits are claimed against permit.scope.

## Runs and timing

- Process state retains chats, jobs, queues and approvals across UI recreation. New runs await draining workers and refresh saved history.
- RunActivity and RunLeaseRegistry own elapsed time and absolute owner snapshots. Acquire before replacing or queueing workers; close only that lease on actual job completion. Exclude approval-only waits and subtract the union of active tool intervals once. Keep a visible Android service while waiting, with CPU and Wi-Fi holds released; heartbeats reconcile owners every 15 seconds.
- Durations use Android uptime and desktop nanoTime. One-second samples exclude unobserved gaps over five seconds, so brief desktop suspension is a timing limit.
- Windows power helper: synchronized owners, validated heartbeat, independent restart. Real sleep, Modern Standby and battery policy can still suspend execution.
- Device: OnePlus PLZ110, Android 17/API 37. The system can cut the network about 2 s after the app leaves the screen; the stall watch retries on return.

## Usage and cost

- Records use wide nullable counts, owner/run/purpose identities, cumulative coalescing and TTL pricing. Failed requests stay unknown. Session totals count each owned request once and exclude copied fork charges.
- Model snapshots preserve explicit free and unknown prices (blank unknown, 0 free), exact units and tiers. Reported gateway totals replace estimates. Missing per-image/container/video fees and external BYOK billing stay explicitly partial.

- Keep request beginnings stable: stale tool output compacts in batches of 8, and keyword lore goes in the late note, not the system prompt.

- Streamed Markdown: a cached split needs text after it, or an open fence breaks. Model text renderers keep their depth and size bounds; over a bound shows plain text.

- MCP tools are `mcp__<server>__<tool>`, ask for approval like file changes, and imports drop local commands and disable the rest. Spec: 2025-11-25 handshake with fallback to 2026-07-28, checked 2026-11-10.

## Persistence

- Layout: parchment-settings.json, backups/, chats/<id>/chat.json and per-chat media/. Keep safe legacy migration and portable relative export paths.
- ChatSession.isCompactStub is transient. Hydrate from the full pending payload before disk. Never save, generate or export from an unavailable full chat. Merge background loads without replacing edits or resurrecting deletions.
- Stub ids stay unique (chat list keys); pinned keep id + preview. `pinned` saved only if true.
- appDiskWriter keeps the latest dirty revision per chat, settings and deletion, clears only matching successes and retries failures with backoff.
- AtomicFileStorage and SnapshotFileStorage stage, sync and atomically replace, with no truncate fallback. Preserve valid backups and failed migration inputs; reject dot IDs, escapes and collisions. Close NIO readers before decoding to avoid Windows replacement starvation.
- Strip attachment base64 only when the resolved local file is readable and nonempty.
- Tests use owned temporary directories and clean up.

## Imports, exports and updates

- Imports are untrusted. withImportedPermissions reduces media references to `chats/<id>/media/<name>` or `media/<name>`, drops network font paths and keeps this device's OTA repository.
- ChatArchiveExtractor is all-or-nothing: validate and stage, decode, then commit only new files; never replace different existing media. Imports stream through importChatFile; truncation is detected from the retained end of the stream. Chat JSON stays in memory (256 MiB total).
- Chat bundles stream through ChatArchiveWriter one chat at a time. Desktop exports replace only after a complete write; Android removes a partial document on failure.
- Links from model output or imports open only through openWebLink (http/https). Desktop openOrPlayMedia refuses executable file types.
- OTA streams to OtaStagedFile and installs only after size and SHA-256 match. Windows requires GitHub's `sha256:<hex>` digest; Android relies on the system APK signature check. Updates refuse to start, and refuse again after the download, while a chat runs or a prompt is queued.
- Limits: attachments 50 MiB (refused at pick, drop or paste with a message); inline text attachments 8 MiB; card and settings picks 256 MiB; desktop images 100 MP and 128 MiB, downscaled past 2560px; shell output keeps head and tail with an omitted note; workspace reads cap lines at 4k chars (10k lines, 250k chars total). Never rewrite a file from capped workspaceRead output.
- Android clipboard images use content URIs through FileProvider/ClipData.newUri.

## UI and performance

- docs/DESIGN.md owns the page design, tokens and icons. designDefaultsApplied moves display settings to its defaults once; built-in profiles at high reasoning move to none. Windows uses the native caption through WindowsChrome (JNA).
- Foundation primitives and KissVectorIcon/ModelLabSvgIcon; no raw OS emoji. Prose and code baseline 14sp. Prefer vertical choices and wrapped rows; no horizontal option scrollbars.
- Shared controls expose one labelled target with state and a 48dp minimum. Field labels merge into input semantics; a contentDescription would replace the value for readers.
- Palette build keeps muted, danger and success ink at 4.5:1 on all surfaces; filled controls use onFillInk.
- Tabs: MRU cap 12, preserve active tab and settings. Notices wrap, dismiss by a 48dp control and keep their timeout.
- Keep raw wire previews at 4096 chars or less; render only the last 12 interleaved steps while streaming. Hydrate active and running chats, dehydrate inactive history.
- Optimize only with measurements or equivalence checks.
