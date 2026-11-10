# Parchment TODO

Updated 2026-11-11. AGENTS.md owns workflow; MEMORY holds contracts. Task IDs stay stable. A checked box means implemented and locally verified; 10.5 is the final Git lock.

## Current status

- **Source:** `main` holds everything checked below (final commit 2026-11-11; see `git log`).
- **Last verified:** final gate 2026-11-11: 385 desktop tests, Android compile, release APK and Windows distributable pass. The phone runs that 2.0.68 build.
- **Published:** v2.0.67 (run 42787738614: Windows rebuilt, run 67's APK kept) is verified off-device only. Each early source push needs a fresh user request; no device access. To rebuild only Windows, run the workflow with `reuse_android_run` set to the run whose APK to keep.
- **Next action:** the open items above, as the user chooses. The user runs 4.1 with keys; give them the steps. 6.3 can run once the next release is published.
- **Phone:** scripted taps must confirm Parchment is in front. The user chose to stop device testing here (2026-11-11); unchecked device items below are untested, not passed.

## Execution order

Confirmed critical or high defects always go first. Otherwise small, clear fixes come before long audits.

Remaining: the open items above, as the user chooses.

## Open tasks

### Access, process and recovery

- [ ] **2.5 Shell ownership:** the owned-session protocol is proven under a real shell. Left: Android descendant cleanup on a device.
- [ ] **3.4 Android:** verify specialUse, visible foreground startup, application context, permission refresh and bounded liveness-based CPU leases; surface promotion and lock errors; no phantom notifications. Whole-run ownership, approval waits and device instrumentation remain.
- [ ] **3.5 Device matrix:** passed on the Android 17 handset: app switch, screen off, two runs, approval waits, process death, Stop and chat switching. Left: denied notifications (skipped), compaction, Doze. Explain real sleep and battery limits.

### Protocols

- [ ] **4.1 Replay (user-tested):** schema and isolation audit is done against current docs (4.1a). The user runs the keyed round trips; give them the test steps at the end (decision, 2026-11-10). Open: the docs did not settle generateContent's per-part signature rules or whether Responses reasoning items need `id`; Anthropic redacted_thinking is not saved, so an interrupted tool turn relies on the fold retry.

### Updates

- [ ] **6.3 OTA:** verified staging (6.3a) is done, and updates refuse to start or install while chats are running or prompts are queued (6.3b). Left: real silent-install and PackageInstaller runs, which need a newer published release.

### Performance and maintainability

- [ ] **7.1 Baselines:** repeatable desktop fixture numbers come from `PerformanceBaselineTest`. Left: the real app's cold/warm first frame, typing, idle memory, CPU/disk/network and sustained streaming, on desktop and Android.
- [ ] **7.2 Startup/state:** chat images decode off the UI thread and desktop startup costs measure small. Left: recheck on Android; large vault and lifecycle checks.
- [x] **7.3 Incremental work:** word counts and open code fences scan only new text, with chunk and Unicode equivalence tests; a streamed split inside an open fence no longer breaks it; replies over 32k chars refresh every 48–96 ms.
- [x] **7.4 Modules:** the provider engine, Settings and ParchmentApp are split into focused files with code lines proven unchanged; streaming and summaries reach app state only through explicit holders (ChatRunHosts.kt). workspaceList, Glob and Search differ deliberately (desktop tree cache).
- [ ] **7.5 Renderers:** Markdown, LaTeX and SVG are depth- and size-bounded, with linear SVG scanning; nested, fuzz and pathological inputs pass on desktop. Left: the same check on Android.

### UI

- [x] **8.2 Fit:** screen, long-name and cost-label work; keyboard and rotation pass on the Android 17 phone.
- [ ] **8.3 Motion/access:** the Reduce motion setting is done. Left: Android's system animation scale and a screen reader on the phone (skipped by the user).

### Workflows

- [x] **9.2 Gaps:** full-history search, the `ask_user` question tool and lossless V2/V3 card export.
- [x] **9.3 Workflows:** agent, daily-chat and roleplay journeys run end to end on desktop.
- [x] **9.4 Skills and MCP:** workspace skills load on demand. MCP servers connect over HTTP on both platforms and as local processes on desktop, for both protocol generations, under the existing approvals. Proven against a real local process and HTTP fixtures; no third-party server has been run yet.
- [x] **9.6 Composer controls:** thinking and duration sliders, aspect ratio tiles and resolution buttons, each with a custom value.
- [x] **9.7 Subagents:** a delegate tool with its own context, read-only, no nesting, counted toward spend. Custom helpers from files can come later.
- [x] **9.8 Browse:** search the official MCP Registry from Settings (hosted servers only); install a skill from a GitHub link.
- [x] **9.5 Computer and phone use:** off by default, every action approved, Stop and denial proven. Screenshot, tap and denial pass on the phone; the user confirmed a real task on Windows (2026-11-11).

### Final gate

- [x] **10.2 Final audit:** a 2026-11-10 review of all uncommitted work and a pass over device control found 13 issues, all fixed; URLs in model text reach only the public web or the provider's own server.
- [x] **10.3 Matrix:** gate passed 2026-11-11 (385 tests, Android compile, release APK, Windows distributable). Phone and Windows runs found and fixed the approval mismatch and crashes on Stop and chat switching. Remaining device items stay open by user decision.
- [x] **10.4 Docs:** MEMORY, TODO, README, CONTRIBUTING and coverage state actual results and limits.
- [x] **10.5 One final commit/push:** one commit on `main` after `affected` passed (385 tests, 2026-11-11), pushed to origin.
- [x] **10.6 Final cleanup:** marketing comments and the idle notification title made plain; stale TODO lines fixed; old stash dropped. GitHub (user-approved, 2026-11-11): 48 old releases and tags deleted (v2.0.62–v2.0.67 kept), plain description, topics, wiki off.
- [x] **10.7 Professional handover:** plain README, CONTRIBUTING with a submit checklist, docs/ARCHITECTURE.md (layout, a chat turn, where to change what), AGENTS.md pointer, pull request and bug report templates.

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
- [x] **5.2 Prompt/cache:** 40-step fixture run shares 70.7% of each request with the last, up from 26.8%. Compaction moves in batches; keyword lore sits late; Anthropic marks the newest tool turn. Real provider billing is unmeasured.
- [x] **5.3 Extra requests:** no nudge after a finished goal; accepted video jobs resume on Continue, from this profile's own endpoints only.
- [x] **5.4 Controls:** per-chat spend limit stops before the next request and keeps history. A reply with no cache split counts at its lowest price.
- [x] **5.5a Totals:** unique owned requests; fork provenance preserved.
- [x] **5.5b Rates:** free vs unknown prices, TTL/context tiers, exact units.
- [x] **5.5c Fees:** reported totals replace estimates; missing charges are labelled.
- [x] **5.5d Time:** monotonic accounting excludes approval waits and overlapping tool time.
- [x] **6.1 Files/media:** bounded shell output, line reads, images, caches and downloads (6.1a); streamed exports (6.1b); streamed chat import (6.1c). Attachment, drop, paste and share picks refuse oversized files with a message and read off the Android main thread; large text stays out of history (6.1d).
- [x] **6.2 Archives:** all-or-nothing import; crafted chats cannot reach outside app storage; web links open only in a browser; desktop never launches executables; atomic desktop exports.
- [x] **4.1a Replay audit:** padded OpenAI reasoning kept; turns stamped with the real wire; sequential tool steps stay separate; Chat reasoning stays in its family and covers closing prose; portable, unique tool IDs; model-scoped Responses chains; attachment types per wire.
- [x] **3.3 Checkpoints:** changes are saved before they run; interrupted tools record an unknown outcome and interrupted replies say so; nothing resumes without Continue.
- [x] **4.2b Stops:** output-limit notice; Anthropic `pause_turn` continues with server blocks intact.
- [x] **4.4 Discovery:** settings, then provider, then registry; no routing by model name; realtime needs explicit support.
- [x] **4.2a Stream recovery:** streams need the provider's finish signal; split JSON stays whole; `response.failed` is an error; Chat calls without `index` stay separate; Custom never reroutes after a failure; accepted video jobs are never resubmitted.
- [x] **4.3 Storage:** new installs are stateless; `store` follows its own setting and chaining implies it; polling and cancel require stored Gemini interactions; built-in Gemini and OpenAI profiles reset to stateless once (user decision).
- [x] **6.3a Verified staging:** size and SHA-256 checks before install; imported settings keep this device's update repository.
- [x] **6.3b Active runs:** updates refuse to start, and refuse again after the download, while a chat runs or a prompt is queued.
- [x] **8.1 Layout:** one scale for type, corners and borders; sentence case and real plurals; button hierarchy; icon family and app icon (docs/DESIGN.md).
- [x] **8.6 Bubbles:** messenger bubbles by default; Page view stays.
- [x] **8.5 Profile:** name and picture in Settings, used on your messages and for `{{user}}`; imports cannot set the picture path.
- [x] **8.4a Page design:** Parchment default with Lapis themes as choices; replies on the page with a lapis rule for prompts; actions on hover, focus or request; one-line header, chat rows and tabs; plain-language copy; invitation empty state; reasoning defaults to None; native Windows caption; hover hints on buttons and chips; `UiGalleryTest` renders real screens.
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

- [x] **8.4 Plain language:** on-screen jargon replaced; docs/DESIGN.md holds the word list.
- [x] **4.5 Turn order:** text written between tools stays in order on screen and in replay on every wire; Responses message phases are kept.
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
