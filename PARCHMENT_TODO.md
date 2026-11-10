# Parchment TODO

Updated 2026-11-10. AGENTS.md owns workflow; MEMORY holds contracts. Task IDs stay stable. A checked box means implemented and locally verified; 10.5 is the final Git lock.

## Current status

- **Source:** the user-requested lock of 2026-11-10 holds UI 8.1c–8.4a, protocol replay 4.1a, stream recovery 4.2a, storage modes 4.3, I/O and update safety 6.1–6.3b, cleanup 7.4a–b, workflow 11.1, signing follow-up 10.2c.
- **Last verified:** all 277 desktop tests and the Android Kotlin compile pass at 2.0.64/264 (`verify.py status` shows the latest). Android pickers and export dialogs still need a real run at 10.3.
- **Published:** v2.0.65/265 (run 42616265429) is verified off-device only. The one-time early push authorization is used up; no further publishing or device access.
- **Next action:** apply the user's review of the new design (docs/DESIGN.md), then model discovery 4.4 and the order below.
- **Phone:** unplugged. Do source and local work only. At 10.3, stop and request reconnection and ADB authorization. Device-dependent items stay unchecked until then.

## Execution order

Confirmed critical or high defects always go first. Otherwise small, clear fixes come before long audits.

1. **4.1–4.4, 2.5, 3.3–3.4:** protocol, process and recovery contracts.
2. **7.4:** split ParchmentApp and SettingsPages once state ownership is decided.
3. **5.2–5.4, 7.1–7.3, 7.5:** measured efficiency and work bounds.
4. **9.2–9.3, 8.1–8.3:** core gaps, complete workflows, remaining broad UI review.
5. **10.2–10.6, 3.5:** final audit, one platform and device matrix, docs, one commit and push, final cleanup.

## Open tasks

### Access, process and recovery

- [ ] **2.5 Shell ownership:** cancel only the owning run's processes. Require genuine OS confinement or accurately described explicit full access; keep command grants narrow. Desktop two-process cancellation and exact/stale grant checks pass. Verify the Android owned-session protocol first; app-UID descendant cleanup waits for 10.3.
- [ ] **3.3 Checkpoints:** persist known tool outcomes, pending actions and valid continuation. Resume safely after socket or OS interruption; never repeat uncertain mutations or restart a user-stopped run. Check: interruption, process restart, explicit Stop and unknown-outcome fixtures.
- [ ] **3.4 Android:** verify specialUse, visible foreground startup, application context, permission refresh and bounded liveness-based CPU leases; surface promotion and lock errors; no phantom notifications. Stash 7e1827538dd5e9bbf8ad836346bbeda62577104c is merged locally; keep it until device integration passes. Whole-run ownership, approval waits and device instrumentation remain.
- [ ] **3.5 Device matrix:** app switch, screen off/Doze, denied notifications, compaction, two runs, approval waits, process death/recovery and cleanup on the Android 17 handset. Explain real sleep and battery limits.

### Protocols

- [ ] **4.1 Replay:** schema and isolation audit is done against current docs (4.1a). Remaining: a live round trip on each provider needs keys; the docs did not settle generateContent's per-part signature rules or whether Responses reasoning items need `id`; Anthropic redacted_thinking is not saved, so an interrupted tool turn relies on the fold retry.
- [ ] **4.2 Streams/retries:** core recovery is done (4.2a). Remaining: Anthropic `pause_turn` needs server-tool blocks replayed; output-limit stops (`length`, `max_tokens`, `MAX_TOKENS`, `response.incomplete`) end with no notice to the user.
- [ ] **4.4 Discovery:** remove model-name/version routing; use live/registry capabilities, exact aliases and conflict-aware precedence; keep creator identity accurate; realtime requires explicit support. Check: misleading IDs, overrides, conflicts, unknown modalities, media-job recovery.

### Token efficiency

- [ ] **5.2 Prompt/cache:** measure system/history/tool/lore/attachment overhead; keep stable cacheable prefixes; prune only with safe evidence rules. Check: representative coding workloads before and after, including cache reads/writes and spend.
- [ ] **5.3 Extra requests:** remove duplicate creation/tools, unnecessary summaries and nudges; preserve accepted-job state. Check: request counts and interrupted recovery.
- [ ] **5.4 Controls:** useful spend/effort/context options and breakdowns, preserving explicit model choices and uncapped instructions. Check: budgets stop safely without losing history.

### Updates

- [ ] **6.3 OTA:** verified staging (6.3a) is done, and updates refuse to start or install while chats are running or prompts are queued (6.3b). Remaining: real silent-install and PackageInstaller runs at 10.3.

### Performance and maintainability

- [ ] **7.1 Baselines:** measure cold/warm first useful frame and typing, idle heap/working set, CPU/disk/network and sustained streaming on desktop and Android.
- [ ] **7.2 Startup/state:** move safe loads, migrations, fonts, tokenizer, discovery and composition-time image decoding off the UI path; bound queues, caches and inactive payloads. Check: large chats/vaults and lifecycle equivalence.
- [ ] **7.3 Incremental work:** replace repeated buffer scans/serialization/copies with correct incremental work; retain bounded wire previews. Check: Unicode/chunk equivalence and allocation measurements.
- [ ] **7.4 Modules:** dead code, unused imports and the unused resources dependency are gone; identical workspace-edit and media-storage code is shared in jvmShared (7.4a). AiProviderEngine went from 8.2k to 1.4k lines across 12 per-protocol and per-concern files, with code lines verified unchanged apart from header comments, visibility and extension receivers (7.4b). Remaining: split ParchmentApp (8k) and SettingsPages (5k); their composables share local state, so this needs explicit state ownership, not a mechanical move. Check: behavior equivalence before and after. workspaceList, Glob and Search differ deliberately (desktop tree cache); a few functions are kept only as test seams for real import/export paths.
- [ ] **7.5 Renderers:** bound recursion and repeated Markdown/LaTeX/SVG/icon parsing. Check: nested, fuzz and pathological inputs on both platforms.

### UI

- [ ] **8.1 Layout:** general consistency review of type, spacing, icons, corners, colors and action states beyond the completed controls and forms.
- [ ] **8.2 Fit:** general screen and long-name coverage, clear cost labels; IME and rotation at 10.3.
- [ ] **8.4 Page design:** core screens are done (8.4a). Remaining: the deeper settings pages, assistant and card editors and modals still use the older card style; settings summaries still say "profile(s)". Real Windows and Android review at 10.3.
- [ ] **8.3 Motion/access:** desktop reduced motion (Compose honors the Android system scale; confirm at 10.3); handset reader checks at 10.3.

### Workflows

- [ ] **9.2 Gaps:** implement confirmed essential gaps as small, testable tasks without unsupported completeness claims.
- [ ] **9.3 Workflows:** verify complete agent, daily-chat and roleplay flows and update docs/FEATURE_COVERAGE.md.

### Final gate

- [ ] **10.2 Final audit:** recheck every public boundary, error, recovery and performance path; resolve critical/high findings. Known input: `.mp4` and image URLs in model text download without the public-web address guard (responses are stored as media, not returned to the model); decide whether to apply it.
- [ ] **10.3 One final matrix:** desktopTest, compileDebugKotlinAndroid, assembleRelease, createDistributable and the desktop/device runtime matrix. Rerun only what failures affect.
- [ ] **10.4 Docs:** concise MEMORY, TODO, README and CONTRIBUTING with actual results and practical limits.
- [ ] **10.5 One final commit/push:** only after every required step and final verification pass; confirm origin/main reachability.
- [ ] **10.6 Final cleanup:** after every other task here is finished, clean up the Parchment codebase and the Parchment GitHub repository. Confirm destructive or public GitHub changes (branches, tags, releases) with the user first.

## Completed

- [x] **1 Foundation:** Windows power leases; runtime-version inputs; Tabs dimming, narrow fit and dismissal; atomic snapshots and lossless dirty revisions; Shell permission at every tool path; five-protocol usage ledger; Custom dialect dispatch and Interactions recovery; guarded JSON/ZIP exports; compact Android usage.
- [x] **2.1 Data:** assistant defaults and explicit chat overrides; imports carry no tool authority.
- [x] **2.2 Enforcement:** shared dispatch denies unknown/disabled tools and read-only writes.
- [x] **2.3 Approval broker:** exact one-use permits, Deny/Stop, invalidation on policy change.
- [x] **2.4 Boundaries:** canonical path guards; public web/DNS/redirect protection.
- [x] **2.6 UI:** shared vertical policy controls and searchable tool registry.
- [x] **3.1 Coordinator:** process-owned runs, frozen context, safe handoff.
- [x] **3.2 Leases:** whole-run owners; approval waits release holds; owner-scoped cleanup.
- [x] **5.1 Utility usage:** every utility and failed attempt carries chat/run/purpose records.
- [x] **5.5a Totals:** unique owned requests; fork provenance preserved.
- [x] **5.5b Rates:** free vs unknown prices, TTL/context tiers, exact units.
- [x] **5.5c Fees:** reported totals replace estimates; missing charges are labelled.
- [x] **5.5d Time:** monotonic accounting excludes approval waits and overlapping tool time.
- [x] **6.1 Files/media:** bounded shell output, line reads, images, caches and downloads (6.1a); streamed exports (6.1b); streamed chat import (6.1c). Attachment, drop, paste and share picks refuse oversized files with a message and read off the Android main thread; large text stays out of history (6.1d).
- [x] **6.2 Archives:** all-or-nothing import; crafted chats cannot reach outside app storage; web links open only in a browser; desktop never launches executables; atomic desktop exports.
- [x] **4.1a Replay audit:** padded OpenAI reasoning kept; turns stamped with the real wire; sequential tool steps stay separate; Chat reasoning stays in its family and covers closing prose; portable, unique tool IDs; model-scoped Responses chains; attachment types per wire.
- [x] **4.2a Stream recovery:** streams need the provider's finish signal; split JSON stays whole; `response.failed` is an error; Chat calls without `index` stay separate; Custom never reroutes after a failure; accepted video jobs are never resubmitted.
- [x] **4.3 Storage:** new installs are stateless; `store` follows its own setting and chaining implies it; polling and cancel require stored Gemini interactions; built-in Gemini and OpenAI profiles reset to stateless once (user decision).
- [x] **6.3a Verified staging:** size and SHA-256 checks before install; imported settings keep this device's update repository.
- [x] **6.3b Active runs:** updates refuse to start, and refuse again after the download, while a chat runs or a prompt is queued.
- [x] **8.4a Page design:** Lapis on Vellum default and dark twin; replies on the page with a lapis rule for prompts; actions on hover, focus or request; one-line header, chat rows and tabs; plain-language copy; invitation empty state; reasoning defaults to None; `UiGalleryTest` renders real screens.
- [x] **8.0 Floating notice:** rounded overlay, wrapping, dismiss action.
- [x] **8.1a–8.3a Permission controls:** vertical choices, wrapping actions, single 48dp targets.
- [x] **8.1b/8.2b Settings forms:** stacked fields, growing inputs, wrapping theme presets.
- [x] **8.1c–8.3c Provider choices/switches:** wrapping radio rows, single labelled switch targets.
- [x] **8.1d–8.3d Shared actions/confirmations:** names, roles, selection, focus rings, readable sheets, scrolling confirmations.
- [x] **8.1e–8.3e Themes/field names:** 4.5:1 ink on all surfaces, readable filled actions, labelled inputs. Accent text stays 3.4:1 on Linear Carbon elevated by theme choice.
- [x] **9.1 Matrix:** docs/FEATURE_COVERAGE.md lists implemented, partial and missing features.
- [x] **10.1 CI:** Node-24 actions, bounded retries, checksum, regression-before-package gate.
- [x] **10.2a Signing:** repository key removed; private local key and CI secret-only staging.
- [x] **10.2b CI signer setup:** run 42616265429 published v2.0.65; APK digest, certificate and metadata verified.
- [x] **10.2c Signing fallback:** blank key password falls back to the store password; key retrieval validated before packaging.

- [x] **11.1 Workflow:** `scripts/verify.py` is the single check command: it remembers verified files, reports exact failures and blocks concurrent runs. `scripts/quality.py` enforces encoding, unused imports, dead code, file-size ratchets, banned patterns, doc budgets and tracked secrets, locally and in CI. AGENTS.md is harness-neutral with thin entry points. Compile, test, filter and concurrency failures were each proven on real runs.

## Sources

Reuse these; consult a changed external contract once.

- **OpenAI:** developers.openai.com/api/reference/resources/responses/methods/create; /api/reference/resources/chat/subresources/completions/methods/create; /api/docs/guides/reasoning; /api/docs/guides/prompt-caching.md; /api/docs/pricing; /codex/agent-approvals-security; /codex/sandboxing
- **Gemini:** aistudio.google.com/api-reference/interactions-api; /docs/interactions-overview; /docs/thinking; ai.google.dev/api/generate-content
- **Anthropic:** platform.claude.com/docs/en/api/messages; /build-with-claude/streaming; /build-with-claude/prompt-caching; /build-with-claude/extended-thinking; /build-with-claude/thinking-tool-workflows; /about-claude/pricing; /agents-and-tools/tool-use/web-search-tool
- **DeepSeek:** api-docs.deepseek.com/guides/thinking_mode
- **Rates:** models.dev/api.json; openrouter.ai/api/v1/models; openrouter.ai/docs/api/reference/overview; vercel.com/docs/ai-gateway/models-and-providers; raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json
- **Windows:** learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate; /nf-winbase-powersetrequest
- **Android:** developer.android.com/develop/background-work/services/fgs/service-types; /fgs/timeout; /training/monitoring-device-state/doze-standby; /tools/adb; /reference/android/system/Os; /develop/ui/views/notifications/notification-permission
- **JVM and processes:** docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html; /java/lang/Process.html; developer.android.com/reference/java/lang/Process; pubs.opengroup.org/onlinepubs/9799919799/functions/kill.html; android.googlesource.com/platform/external/toybox/+/refs/heads/main/toys/other/setsid.c
- **Security:** cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- **GitHub:** docs.github.com/en/rest/actions/secrets; /rest/guides/encrypting-secrets-for-the-rest-api; /rest/releases/assets (asset digest is nullable, observed as `sha256:<hex>`)
- **Build and UI:** kotlinlang.org/docs/gradle-compilation-and-caches.html; /docs/cancellation-and-timeouts.html; /docs/coroutine-context-and-dispatchers.html; docs.gradle.org/8.13/userguide/command_line_interface.html; developer.android.com/develop/ui/compose/state
