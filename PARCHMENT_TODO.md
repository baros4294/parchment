# Parchment TODO

Updated 2026-11-09. Read this status first, then only the relevant task entries. AGENTS.md owns workflow; MEMORY holds reference contracts. Task IDs stay stable.

## Current status

- **Last verified batch: 5.5d + 3.2 timing/leases.** 45 selected clock/coordinator/approval/HTTP/native Windows lease tests and Android Kotlin compilation pass at 2.0.64/264. Prior fee/persistence results remain valid. Whole-run owners span tools, compaction and queue/preparation handoffs; approval-only waits release holds. Android uptime excludes deep sleep; desktop excludes unobserved scheduling gaps over five seconds (short desktop suspensions remain an accounting limit).
- **Latest UI gate: 2.6 / 8.1a–8.3a.** Three desktop UI tests pass: shared registry saved-choice/search behavior; inherited choice/reset semantics; portrait/landscape/dark/1.8× font renders; owning-chat approval wrapping, scrolling and Stop. Back/Escape state and available-height drawer cap compile and pass source/caller review. Prior permission/lease passes remain valid; handset/IME/reader checks stay at 10.3. Renders: .parchment-local/verification/permissions/.
- **Latest settings gate: 8.1b / 8.2b.** Provider/assistant sampling and typography stack vertically, including matching search fields. Single-line inputs grow with font size; theme presets wrap in shared radio rows. Source/caller/diff review, 320dp/1.8× and 740dp/1× form renders, input/placeholder fit and preset selection pass; final Android Kotlin compilation passes at 2.0.64/264. Three permission UI tests also passed; unaffected runtime/HTTP results are reused.
- **Current boundary: one-time UI review checkpoint.** The user authorized an early commit/push of these UI updates and their integrated local dependencies to origin/main for review. Accept: reviewed staged diff, excluded private/local artifacts and matching local/remote commit. After publishing, await UI review before resuming broader TODO work. This exception applies only this time; 10.5 stays unfinished. Phone/release-secret setup waits for 10.3.

## Working rule

Follow AGENTS.md: bounded edits → complete caller/diff review → smallest useful check → record/close batch. Add regressions for demonstrated behavior risks. Reuse unaffected passes; rerun only what a failure/fix affects. Full platform/package/device verification stays at 10.3. Never lose data/instructions, weaken permissions or guess opaque protocol data.

Phone is unplugged by the user. Continue source/local verification; defer all handset checks to 10.3, then stop and request reconnection/ADB authorization. Do not request it earlier. Tick each completed, verified bullet immediately; leave device-dependent bullets unchecked until that gate.

Checkboxes mean implemented and locally verified; the final Git lock is step 10.5.

## Remaining execution order

1. **2.6, 8.1–8.3:** UI consistency/fit/accessibility; handset checks at 10.3.
3. **6.1–6.3, 7.4:** bounded I/O/archive/OTA and proven cleanup.
4. **4.1–4.4, 2.5, 3.3–3.4:** protocol/process/recovery contracts; fix confirmed high defects first.
5. **5.2–5.4, 7.1–7.3, 7.5:** measured efficiency and work bounds.
6. **9.2–9.3:** confirmed core gaps and complete workflows.
7. **10.2–10.5, 3.5:** final audit, one platform/device matrix, concise docs, one commit/push.

Finish one related batch and tick its verified bullets before moving on. A confirmed critical/high defect takes priority over this speed order.

## 1. Foundation (verified)

- [x] Repair monitored Windows power leases, concurrent owners and cleanup.
- [x] Track generated runtime-version/repository inputs.
- [x] Fix Tabs dimming, narrow layout and dismissal/Back behavior.
- [x] Shared atomic snapshots, lossless dirty revisions, hydration/metadata races, backup/migration recovery and inline attachment preservation.
- [x] Enforce explicit Shell permission in every tool path.
- [x] Per-request usage ledger across five JSON/SSE protocols; cumulative updates, TTL cache writes, wide counts, interruption/continuation records and transparent estimates.
- [x] Custom dialect dispatch, Interactions step usage/final-text recovery and safe catalog identity lookup.
- [x] Guard JSON/ZIP exports; verify usage-record export/import/recovery.
- [x] 92-test/build gate and Android compact usage rendering.

## 2. Approval, access and process controls (next bulk section)

- [x] **2.1 Data:** assistant defaults and explicit chat overrides; inheritance differs from Off/unmount. Legacy Shell resets safely; imports carry no tool authority. Permission/import/hydration regressions pass.
  Files: Models, new permission models, StatePersistence/metadata merge. Check: old settings/chat fixtures preserve intent; imports cannot create approval grants.
- [x] **2.2 Enforcement:** shared dispatch policy denies unknown/disabled tools and read-only/indirect writes. Raw provider options cannot grant tools. HTTP regressions verify no writes before approval and independent parallel decisions.
  Files: AgentPermissionGate, WorkspaceHarness, engine. Check: direct/parallel calls cannot bypass toggles or advertise their own authority.
- [x] **2.3 Approval broker:** per-run/chat exact-action requests, one-use permits, exact run grants, Deny and Stop. Policy/path edits invalidate grants and pending actions; repeated denials do not prompt again. Cancellation, two-chat and HTTP revocation tests pass.
  Files: ToolApprovalBroker, runtime context. Check: cancellation/revocation/two-chat tests; no approved action executes after invalidation.
- [x] **2.4 Boundaries:** canonical ancestors and guarded recursive scans; escaping read/write/edit/move/delete calls fail before effects. Windows junction integration and public web/DNS/redirect/disabled-network tests pass. Provider transport stays separate.
  Files: bridges/harness. Check: traversal/links/recursive scans, redirected/private destinations and disabled network.
- [ ] **2.5 Shell ownership:** cancel only the owning run's processes. Require genuine OS confinement or accurately described explicit full access; keep command grants narrowly scoped.
  Files: process bridge/run context. Desktop two-process cancellation passes. Android owned-session control is local; verify protocol first, app-UID descendant cleanup at 10.3. Exact/stale grant checks pass.
- [x] **2.6 UI:** Workspace/Tools/assistant defaults share vertical policy controls and a searchable registry. Saved tool choices are separate from effective availability; toggles do not grant folder/Shell/web access. Manual Override is first; owning-chat actions wrap and scroll, with single-target switch/radio semantics and 48dp targets. Shell/network/power limits are explicit. Three desktop UI regressions/renders and source/caller review pass; earlier revocation/HTTP passes reused. Back/Escape and drawer-height changes compile; actual handset/IME/reader behavior waits for 10.3.

## 3. Long-horizon execution and Android recovery

- [x] **3.1 Coordinator:** process-owned state, run identities, frozen provider context and explicit phases. Invisible workers drain before handoff; stale events cannot erase a replacement. Nine ownership/clock/handoff regressions and both Kotlin checks pass. Actual handset recreation remains in 3.5/10.3.
- [x] **3.2 Leases:** process-owned absolute snapshots span tools, compaction and queue/preparation handoffs; approval-only waits release CPU/Wi-Fi/native holds while active parallel work retains them. Stale/lazy cancellation cleanup is owner-scoped; service snapshots recover from live process owners and process death retains no phantom owner. 45 focused desktop tests and Android Kotlin compilation pass. Actual handset service recreation remains at 3.5/10.3.
- [ ] **3.3 Checkpoints:** persist known tool outcomes, pending actions and valid continuation. Resume safely after socket/OS interruption; never repeat uncertain mutations or restart a user-stopped run.
  Files: coordinator/persistence/engine. Check: interruption/process restart/explicit Stop/unknown-outcome fixtures.
- [ ] **3.4 Android:** reapply stash 7e1827538dd5e9bbf8ad836346bbeda62577104c, then verify specialUse, visible foreground startup, application context, permission refresh and bounded liveness-based CPU leases. Surface promotion/lock errors; no phantom notifications.
  Files: manifest, service, MainActivity/bootstrap/Android bridge. Stash merged without overwriting local work; unbounded wake lock corrected. Source/check gate in progress; whole-run ownership, approval waits and device instrumentation remain. Keep the stash.
- [ ] **3.5 Device matrix:** app switch, screen off/Doze, denied notifications, compaction, two runs, approval waits, process death/recovery and cleanup.
  Check: actual Android 17 handset. Explain real sleep/battery limits; local computation cannot continue through actual suspension.

## 4. Protocol contracts and universal capabilities

- [ ] **4.1 Replay:** verify all five request/history/tool schemas; preserve opaque provider reasoning/signatures, call/result batch boundaries and closing prose. Stamp actual wire/model.
  Files: engine/adapters/Models. Check: current official fixtures, cross-provider/model isolation and signed history.
- [ ] **4.2 Streams/retries:** fragmented/malformed SSE, tool/reasoning-only endings, continuation/stop/errors. No duplicate accepted jobs/tools; Custom never changes its declared URL/dialect.
  Check: local HTTP/SSE round trips and recovery fixtures.
- [ ] **4.3 Storage:** separate persisted history, chaining and actual background execution; stateless remains default. Background requires explicit compatible storage opt-in.
  Files: engine/settings. Check: documented combinations and model-scoped chains.
- [ ] **4.4 Discovery:** remove model-name/version routing; use live/registry capabilities, exact/canonical aliases and conflict-aware precedence. Keep creator identity accurate; realtime requires explicit support.
  Files: ModelsDevClient, engine, Models/icons. Check: arbitrary/misleading IDs, overrides, conflicts, unknown modalities and media-job recovery.

## 5. Token efficiency and complete cost visibility

- [x] **5.1 Utility usage:** title/summary/compaction/translation/draft and failed/interrupted attempts carry chat/run/purpose records. Persisted utility ledger and usage drawer are wired; cumulative updates coalesce. 45 usage/HTTP/hydration regressions and both Kotlin checks pass.
- [ ] **5.2 Prompt/cache:** measure system/history/tool/lore/attachment overhead; keep stable cacheable prefixes/tool definitions and prune only with safe context/evidence rules.
  Files: engine/harness/tokenizer/compaction. Check: representative coding workloads before/after; compare input/output/reasoning/cache reads/writes and estimated spend.
- [ ] **5.3 Extra requests:** remove duplicate creation/tools, unnecessary summaries and continuation/nudges; preserve accepted-job state.
  Check: request counts and interrupted recovery.
- [ ] **5.4 Controls:** useful spend/effort/context options and usage breakdowns, preserving explicit model choices and uncapped user instructions.
  Files: policy/settings/metrics. Check: budgets stop safely without losing history.
- [x] **5.5a Totals:** unique owned requests across all versions/utilities; fork provenance preserved and duplicate user-token estimates removed. Regeneration/fork/deduplication/partial tests pass in the 37-test/Kotlin gate.
- [x] **5.5b Rates:** explicit free/missing prices, model-owned snapshots, TTL/context tiers and exact per-token units. Nine pricing regressions pass within the 49-test gate; both Kotlin targets pass. Vertical editor preserves blank = unknown and 0 = free.
- [x] **5.5c Fees:** reported totals replace estimates; metadata audio/search/request rates use matching units. Persist direct media attempts and label missing tool/media charges, audio/cache splits and external BYOK billing. Image/video hard errors stop endpoint probing. 55 fee/usage/HTTP/persistence tests and both Kotlin targets pass. Per-image/container/video costs remain explicitly partial without compatible usage/rates.
- [x] **5.5d Time:** shared monotonic run/thinking/tool accounting excludes approval-only waits and subtracts parallel tool intervals once. Android uses uptime; one-second liveness samples exclude unobserved desktop scheduling gaps over five seconds (short desktop suspension cannot be identified precisely). Clock rollback/gap, two-owner, parallel/approval, cancellation/revocation and HTTP duration regressions pass in the 45-test/Android gate with 3.2.

## 6. Bounded I/O, archives and OTA

- [ ] **6.1 Files/media:** reduce whole-file/string/image copies; cap allocation/pixels/cache and stream where possible without truncating editable source. Review shell output and cancellation.
  Files: bridges, codecs, media/harness. Check: large/malformed inputs and memory/work bounds.
- [ ] **6.2 Archives:** validate paths/bombs/malformed bundles before committing state; preserve per-chat relative media paths and interrupted import/export recovery.
  Files: persistence/codecs/bridges. Check: traversal, oversized ZIPs, malformed PNG/JSON and partial extraction.
- [ ] **6.3 OTA:** stream to owned staged files, verify completion/size and authoritative digest/signing evidence, cancel without installation, checkpoint/back up runs before maintenance.
  Files: OtaUpdateEngine, bridges, OTA UI/installer. Check: truncated downloads, unknown lengths, mismatch/cancel and data preservation.

## 7. Performance, startup and maintainability

- [ ] **7.1 Baselines:** measure cold/warm first useful frame/typing, idle heap/working set, CPU/disk/network and sustained streaming on desktop/Android.
- [ ] **7.2 Startup/state:** move safe loads/migrations/fonts/tokenizer/discovery off the UI path; bound queues/caches and inactive payload retention; preserve lean heap/hydration/timeline limits.
  Files: App/persistence/platform/registry. Check: large chats/vaults and lifecycle equivalence.
- [ ] **7.3 Incremental work:** replace repeated buffer scans/serialization/copies with correct incremental work; retain bounded wire previews.
  Files: streaming/tokenizer/renderer code. Check: Unicode/chunk equivalence and allocation/work measurements.
- [ ] **7.4 Modules:** remove proven dead code/dependencies and consolidate duplication; split oversized controllers/adapters around explicit ownership.
  Check: behavior-equivalence tests before/after.
- [ ] **7.5 Renderers:** bound recursion/work and repeated Markdown/LaTeX/SVG/icon parsing.
  Check: nested/fuzz/pathological inputs and both-platform rendering.

## 8. Calm, consistent mobile/UI behavior

- [x] **8.0 Floating notice:** rounded rectangle overlay with calm motion, wrapping and dismiss action. Wide/narrow/1.6× font/dark renders, pointer dismissal and stable document position pass; fixed-height clipping removed. Existing timeout retained.
- [ ] **8.1 Layout:** vertical substantial choices; no horizontal option scrolling or redundant labels; consistent type/spacing/icons/corners/colors/action states.
- [ ] **8.2 Fit:** portrait/landscape, IME, long names, enlarged fonts and accidental clipping; clear complete/partial/unknown cost labels.
- [ ] **8.3 Motion/access:** shared calm transitions without jumps/overlap; reduced motion, touch/contrast/keyboard/reader semantics.
  Files: App/Components/SettingsPages/Modals/Theme. Check: rendered desktop + actual Android interactions; preserve verified Tabs behavior.
- [x] **8.1a–8.3a Permission controls:** vertical saved/effective choices, wrapping long actions/paths, no new control animation, radio/switch semantics and 48dp targets. Portrait/landscape/dark/1.8× desktop renders, inherited choice/reset, search and owning-chat Stop pass. Remaining general forms/accessibility and handset checks are covered by the parent items.
- [x] **8.1b / 8.2b Settings forms:** provider/assistant sampling/history and typography fields stack vertically, including search counterparts. Labels wrap and single-line fields grow with enlarged text. Shared theme preset rows wrap, retain selection and provide 48dp radio targets. Narrow 1.8×/wide form renders, vertical input/placeholder fit, preset callback/selection and Android Kotlin compilation pass. Parent UI/device coverage remains open.

## 9. 2026 workflow coverage

- [x] **9.1 Matrix:** implemented/partial/missing features and confirmed core gaps are in docs/FEATURE_COVERAGE.md, checked against source/current official references.
  Agent: approvals, boundaries, recovery, tools/protocols, parallel calls, questions/Steer/Queue, context, diffs/evidence, cost and extensions.
  Daily chat: models/search/navigation, edits/regen/forks, attachments/PDF/media/voice, rendering/copy, privacy/export/backup/OTA/mobile.
  Roleplay: cards/scenarios/greetings/variants, lore/persona/macros, notes/continuity and branching/rewind.
- [ ] **9.2 Gaps:** implement confirmed essential gaps as small, testable tasks; retain KISS and avoid unsupported completeness claims.
- [ ] **9.3 Workflows:** verify complete agent, daily-chat and roleplay flows and update the matrix.

## 10. CI and final verification/lock

- [x] **10.1 CI:** current Node-24 actions, bounded download-only retries, authoritative Gradle checksum, regression-before-package gate and reduced redundant work. Four retry tests, real wrapper launch and actionlint pass. No GitHub rerun; final CI run follows the final push.
- [x] **10.2a Signing:** repository key removed/rejected; private local key/password protected and excluded from Git. CI secret-only staging, PR isolation, Gradle validation and four signer tests pass; real new-key acceptance/old-key rejection verified. Migration/release guidance is explicit. Final secrets/device setup remains in 10.3.
- [ ] **10.2 Final audit:** recheck every public boundary/error/recovery/performance path; resolve remaining critical/high findings.
- [ ] **10.3 One final matrix:** desktopTest, compileDebugKotlinAndroid, assembleRelease, createDistributable and the desktop/device runtime matrix. Resolve failures and rerun only affected checks.
- [ ] **10.4 Docs:** concise MEMORY/TODO/README/CONTRIBUTING with actual results, measured improvements and practical limits.
- [ ] **10.5 One final commit/push:** only after every required step and final verification pass; confirm origin/main reachability.

## Build and evidence notes

Prefer scripts/verify.ps1 (see CONTRIBUTING); it supplies JDK/SDK, stable version flags, quiet output and two workers. Test mode requires explicit filters; Final runs the complete Gradle matrix. -Preview executes nothing. Do not rebuild for tracking/docs changes. Current verified APK/runtime: 2.0.64/264; compare signer/version before device installs.

Core sources (reuse applicable fetched evidence; consult the changed external contract once):
- OpenAI: https://developers.openai.com/api/reference/resources/responses/methods/create ; https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create ; https://developers.openai.com/api/docs/guides/reasoning ; https://developers.openai.com/api/docs/guides/prompt-caching.md
- Gemini: https://aistudio.google.com/api-reference/interactions-api ; https://aistudio.google.com/docs/interactions-overview ; https://aistudio.google.com/docs/thinking ; https://ai.google.dev/api/generate-content
- Anthropic: https://platform.claude.com/docs/en/api/messages ; https://platform.claude.com/docs/en/build-with-claude/streaming ; https://platform.claude.com/docs/en/build-with-claude/prompt-caching ; https://platform.claude.com/docs/en/build-with-claude/extended-thinking
- Windows: https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate ; https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest
- Android: https://developer.android.com/develop/background-work/services/fgs/service-types ; https://developer.android.com/develop/background-work/services/fgs/timeout ; https://developer.android.com/training/monitoring-device-state/doze-standby ; https://developer.android.com/tools/adb
- Build: https://kotlinlang.org/docs/gradle-compilation-and-caches.html ; https://docs.gradle.org/8.13/userguide/command_line_interface.html
- Permissions: https://developers.openai.com/codex/agent-approvals-security ; https://developers.openai.com/codex/sandboxing (fetched 2026-11-08; approvals and OS confinement are separate).
- Boundaries/processes: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html ; https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html ; https://developer.android.com/reference/java/lang/Process ; https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html (fetched 2026-11-08; validate redirects and bind HTTP connections to validated DNS addresses).
- Android sessions/notifications: https://developer.android.com/reference/android/system/Os ; https://pubs.opengroup.org/onlinepubs/9799919799/functions/kill.html ; https://android.googlesource.com/platform/external/toybox/+/refs/heads/main/toys/other/setsid.c ; https://developer.android.com/develop/ui/views/notifications/notification-permission (fetched 2026-11-08; notification denial does not prohibit foreground-service startup).
- UI/coroutines: https://developer.android.com/develop/ui/compose/state ; https://kotlinlang.org/docs/cancellation-and-timeouts.html ; https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html (fetched 2026-11-08).
- Rate data (fetched 2026-11-09): https://developers.openai.com/api/docs/guides/prompt-caching.md ; https://platform.claude.com/docs/en/about-claude/pricing ; https://models.dev/api.json ; https://openrouter.ai/api/v1/models ; https://vercel.com/docs/ai-gateway/models-and-providers ; https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json . Explicit zero is retained; per-token fields use their declared units, not magnitude heuristics.
- Fees (fetched 2026-11-09): https://developers.openai.com/api/docs/pricing ; https://developers.openai.com/api/reference/resources/responses/methods/create ; https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool ; https://ai.google.dev/api/generate-content ; https://openrouter.ai/docs/api/reference/overview ; https://openrouter.ai/docs/api/api-reference/models/get-models . Reported totals replace estimates. Audio tokens and search/request counts use their declared units; per-image/container/video billing stays partial without compatible usage/rates. BYOK credit totals exclude external provider billing.
