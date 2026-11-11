# Parchment Memory

Updated 2026-11-11. Stable contracts only; read the section you need. AGENTS.md owns workflow, TODO progress.

## Standing decisions

- Each TODO batch ends with one build and test run, then a commit and push to origin/main (user decision, 2026-11-11). Never force-push, overwrite unrelated work or claim unverified completion.
- No automatic local Git snapshots between checkpoints (user, 2026-11-10); don't re-propose them.
- Scripted phone taps first confirm Parchment is in front.
- Never optimize by losing history or instructions, weakening permissions, altering opaque signatures or silently enabling server storage.

## Project and build

- scripts/verify.py is the only entry point: saved JAVA_HOME/ANDROID_HOME (a stale inherited JDK 8 path is skipped), version 2.1.0 (CI: VERSION_LINE/VERSION_BASE_RUN), two workers, configuration cache except final; logs in .parchment-local/verification/.
- Keep org.gradle.daemon=false (memory bloat); Gradle heap 2560 MB.
- PowerShell scripts stay ASCII.
- Never copy builds into %LOCALAPPDATA%/Programs/Parchment. Only CI builds (-PofficialRelease=true) check for or install OTA updates. Dev runs keep data in ignored parchment-portable-data.
- AGP 9 split: composeApp (KMP library) holds all code, manifest and res; androidApp only builds the APK (id io.parchment.ai, signing, R8 rules, font assets). Keep class names in io.parchment.ai: the Accessibility component depends on them.
- Android: keep big suspend bodies split into local suspend functions (cancelling a 565-register coroutine aborted).
- Android signing is private-env-only: dot-source scripts/load-private-signer.ps1; never regenerate the ignored key (fingerprint 95d9804cfd9c80313440ed97619100c9e9f741c27943f61700fa802d0dbe4574). The legacy key is rejected (docs/RELEASE_SIGNING.md); blank key passwords use the store password (shipped).
- Packaged desktop uses AppCDS: jlink base archive + `$APPDIR/parchment.jsa`, rebuilt after updates.

## Protocols and harness

- AiProviderEngine holds shared state and streamGeneration; each protocol and concern (media, citations, reasoning, tools, discovery, history, errors) is a `network/` extension file.
- Big three: GEMINI_INTERACTIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES. CUSTOM needs an explicit dialect and verbatim URL, sends only its declared wire, never falls back; unresolved Custom blocks Send.
- useStatefulChaining and storeServerSide default to false; storage, chaining and background execution are separate (Gemini background needs compatible storage). keepsProviderState drives `store`, polling and cancel.
- Chains must match the producing model and protocol. Keep chronological call/result batches and closing prose; ToolCallRecord.startsToolStep separates sequential steps from parallel calls.
- Stamp turns with resolveWireProtocol, not a Custom profile's stored protocol. Chat replays reasoning_content only within its family, closing prose included (DeepSeek 400s without it when tools are sent). History tool IDs go through ReplayToolCallIds.
- Reasoning signatures are opaque and provider-specific: preserve, never translate. Cross-provider handoff uses up to 2000 chars of prior narrative. User, system and continuity instructions stay uncapped.
- Keep request beginnings stable: stale tool output compacts in batches of 8; keyword lore goes in the late note, not the system prompt.
- Discover models and capabilities from live metadata and registries; no model-name, version or vendor branches.
- AgentPermissionResolver owns defaults and overrides: null inherits; explicit Off, empty path or false revoke. New chats inherit; imports disable local tools, paths, web and Shell.
- Shared dispatch covers disabled, unknown, unadvertised, read-only and parallel paths. Raw provider options can't grant tools. Provider-managed code needs approval mode Never.
- ToolApprovalBroker is run/chat scoped: exact previews, one-use permits, denial suppression, synchronous invalidation, memory-only grants.
- Shell needs READ_WRITE plus explicit FULL_ACCESS and isn't OS-confined; web-tool controls don't limit its network. Cancel the owning worker, never kill globally.
- WorkspacePathGuard checks real ancestors and rejects escaping links and junctions. PublicWebTransport binds to validated public DNS addresses and checks each redirect; provider transport is separate.
- SSE closing without the finish signal or `[DONE]` is an interruption, not an answer. AcceptedJobException marks accepted work: poll it, never resubmit.
- Utility requests mask inherited tool context, disable tools and goal loops, and propagate cancellation.
- computer_use is off by default; every action needs approval, no run-wide grants. Screenshots stay out of chat state; only the newest is resent. Permits are claimed against permit.scope.
- MCP tools are `mcp__<server>__<tool>` and need approval like file changes; imports drop local commands and disable the rest. Spec 2025-11-25 handshake, fallback 2026-07-28.

## Runs and timing

- Process state keeps chats, jobs, queues and approvals across UI recreation. New runs await draining workers and refresh saved history.
- RunActivity and RunLeaseRegistry own elapsed time and owners: acquire before replacing or queueing workers, close only that lease when its job ends, skip approval-only waits, subtract the union of tool intervals once. Android keeps a visible service while waiting (CPU and Wi-Fi holds released); heartbeats reconcile owners every 15 s.
- Durations use Android uptime and desktop nanoTime, skipping unseen gaps over 5 s.
- Windows power helper: synchronized owners, validated heartbeat, independent restart. Sleep, Modern Standby and battery policy can still suspend runs.
- Device: OnePlus PLZ110 (Android 17/API 37) can cut the network ~2 s after the app leaves the screen; the stall watch retries on return.

## Usage and cost

- Records use wide nullable counts, owner/run/purpose identities, cumulative coalescing and TTL pricing. Failed requests stay unknown. Session totals count each owned request once, excluding copied fork charges.
- Model snapshots keep explicit free (0) and unknown (blank) prices, exact units and tiers. Reported gateway totals replace estimates. Missing per-image/container/video fees and BYOK billing stay marked partial.

## Persistence

- Keep safe legacy migration and portable relative export paths (layout: ARCHITECTURE.md).
- ChatSession.isCompactStub is transient. Hydrate from the pending full payload before disk; never save, generate or export from an unavailable full chat. Merge background loads without replacing edits or resurrecting deletions.
- Stub message ids stay unique (the chat list keys on them). Pinned messages keep their real id and a preview in stubs, so the Pinned list never loads chats. `pinned` is saved only when true.
- appDiskWriter keeps the latest dirty revision per chat, settings and deletion, clears only matching successes and retries failures with backoff.
- AtomicFileStorage and SnapshotFileStorage stage, sync and atomically replace, never truncate. Keep valid backups and failed migration inputs; reject dot IDs, escapes and collisions. Close NIO readers before decoding (Windows replace starvation).
- Strip attachment base64 only when the local file is readable and nonempty.
- Tests use owned temporary directories and clean up.

## Imports, exports and updates

- Imports are untrusted. withImportedPermissions reduces media references to `chats/<id>/media/<name>` or `media/<name>`, drops network font paths and keeps this device's OTA repository.
- ChatArchiveExtractor is all-or-nothing: validate and stage, decode, then commit only new files, never replacing different media. importChatFile streams and detects truncation from the stream's end. Chat JSON stays in memory (256 MiB total).
- ChatArchiveWriter streams one chat at a time. Desktop exports replace only after a complete write; Android removes a partial document on failure.
- Links from model output or imports open only through openWebLink (http/https). Desktop openOrPlayMedia refuses executables.
- OTA streams to OtaStagedFile and installs only after size and SHA-256 match (Windows needs GitHub's `sha256:<hex>` digest; Android relies on the APK signature). Updates refuse to start, before and after download, while a chat runs or a prompt is queued.
- Limits: attachments 50 MiB (refused on pick, drop or paste), inline text 8 MiB, card and settings picks 256 MiB, desktop images 100 MP/128 MiB (downscaled past 2560 px). Shell output keeps head and tail. workspaceRead caps lines at 4k chars, 10k lines and 250k chars: never rewrite a file from it.
- Android clipboard images use FileProvider content URIs (ClipData.newUri).

## UI and performance

- designDefaultsApplied applies DESIGN.md's display defaults once (built-in profiles at high reasoning move to none). Windows keeps the native caption via WindowsChrome, titled with the open chat.
- Foundation primitives and KissVectorIcon/ModelLabSvgIcon; no raw OS emoji. Prose and code baseline 14sp. Prefer vertical choices and wrapped rows; no horizontal option scrollbars.
- Shared controls expose one labelled target with state and a 48dp minimum. Field labels merge into input semantics; a contentDescription would replace the value for readers.
- Palette keeps muted, danger and success ink at 4.5:1 on all surfaces; filled controls use onFillInk.
- Tabs: MRU cap 12, keep the active tab and settings. Notices wrap, dismiss by a 48dp control and keep their timeout.
- Streamed Markdown: a cached split needs text after it, or an open fence breaks. Model text renderers keep depth and size bounds; past one they show plain text.
- Raw wire previews stay at 4096 chars or less; streaming renders only the last 12 interleaved steps. Hydrate active and running chats; dehydrate inactive ones.
- Optimize only with measurements or equivalence checks.
