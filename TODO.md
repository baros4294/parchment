# Parchment TODO

Updated 2026-11-11. AGENTS.md owns workflow; MEMORY holds contracts. Task IDs stay stable. A checked box means implemented and verified; finished tasks go once released.

## Current status

- **Source:** batch 14 docs checkpoint on main (2026-11-11). JDK 25 and a fresh Android SDK are installed; Gradle 8.13 cannot run on JDK 25, so builds fail until 16.2.
- **Last verified:** 2026-11-11, `verify.py review` passed (gates, 35 tool tests, link check). Last build: batch 12 `final` and `perf` (390 tests; start 1.44 s with 300 chats, idle 274 MB, APK 4.2 MB, installer 56.5 MB).
- **Next action:** batch 16, starting with 16.2 (Gradle that runs on JDK 25).

## Execution order

Confirmed critical or high defects always go first. Measure before optimizing; one surface at a time.

16 → 14.2 and the 14.3 screenshots (need a working build) → 12.13 → 13.1–13.3 → 12.6 and 12.8 with 13.4–13.7 (same files, one pass per surface) → 12.7 → 15 → 11.7.

## Open tasks

### Performance and efficiency, low-end devices first

- [x] **12.1 Baseline and budgets:** `verify.py perf` measures the packaged app with 300 synthetic chats (start, idle memory, sizes) and a connected phone; budgets in docs/PERFORMANCE.md.
- [x] **12.2 Fonts:** JetBrains Mono without Nerd Font icons, 9.4 MB to 0.9 MB; saved "JetBrainsMono Nerd" choices still match.
- [x] **12.3 Android shrinking:** R8 and resource shrinking with rules in composeApp/proguard-rules.pro; APK 15.5 MB to 4.2 MB.
- [x] **12.4 Start-up:** stored chats load one at a time with an id-only probe (no longer all at once, three parses each); the update check waits 8 s; library start-up profiles already ship.
- [x] **12.5 Memory:** low-memory mode under 4 GB (or Android's low-RAM flag) shrinks the image caches.
- [x] **12.10 Build cost:** UI gallery renders only on request, `affected` compiles Android only when a change can reach it, `final` drops the duplicate debug compile, Gradle uses the parallel collector. `affected` 5 min to 2.5 min.
- [ ] **12.6 Responsiveness:** done: low-memory devices stream at about 30 frames a second. Open: profile recomposition and split ParchmentApp state so streaming doesn't redraw the sidebar; target p95 frame 16 ms desktop, 33 ms low-end Android.
- [ ] **12.7 Harness efficiency:** done: the cache report adds average and largest request. Open: the same report for Anthropic and Gemini, then trim payloads only where equivalence tests prove nothing is lost.
- [ ] **12.8 Codebase:** split the 2,000+ line UI files by feature and render settings search from the pages' own rows (1,000 duplicated lines); done with 13.4 so the screens can be checked.
- [x] **12.9 Phone check:** the R8 release runs on the phone without crashes and starts instantly. The user checks Android on their own and reports problems (2026-11-11); no agent phone runs.
- [x] **12.12 Windows size:** drop the never-used `classes_nocoops.jsa` (12 MB) and compress the installer with lzma2/ultra64; desktop start 1.44 s, idle 274 MB.
- [ ] **12.13 Desktop shrinking:** ProGuard for the desktop release jars (about 25 MB) with keep rules for JNA, Ktor's engine lookup and serialization; test the packaged app before release.
- [ ] **12.11 Crash reports:** attach each release's R8 mapping.txt in CI so phone stack traces can be decoded.

### UI and UX

- [ ] **13.1 Research:** study award-winning work (Apple Design Awards, Webby and Awwwards winners, iA Writer, Things, Linear, Raycast, Craft, Bear) for hierarchy, rhythm, density, motion and empty states. Write the findings into DESIGN.md as principles, each mapped to a concrete Parchment change. Borrow ideas, never assets or branding.
- [ ] **13.2 Audit:** capture every screen from the UI gallery in light and dark, desktop and phone, at 100% and 200% text; rank the issues against the principles.
- [ ] **13.3 Design system:** one spacing scale, type scale, elevation, motion tokens (duration, easing, reduced motion), focus rings and icon rules in code and DESIGN.md; replace one-off values.
- [ ] **13.4 Core surfaces:** chat page and composer, sidebar and tabs, settings (grouping, long pages, search), first run with no provider (guided setup), empty, loading and error states.
- [ ] **13.5 Interaction:** command palette (Ctrl+K), keyboard shortcuts for every main action, undo instead of confirm for reversible deletes, immediate feedback, predictable back on Android, haptics where they help.
- [ ] **13.6 Accessibility:** NVDA and TalkBack passes, keyboard-only navigation, 200% text, contrast on every theme, reduced motion honored everywhere.
- [ ] **13.7 Low-end rendering:** no blur or heavy shadows, cheap animations that switch off in reduced mode, and no layout jank while streaming.

### Documentation

- [x] **14.1 One owner per fact:** README (what, features, privacy), CONTRIBUTING (setup for JDK 25 and the SDK, commands), ARCHITECTURE (code map, storage, releases), DESIGN, PERFORMANCE, MEMORY, TODO. FEATURE_COVERAGE.md retired into README and Known gaps; stale JDK 21, 10.3 and signing notes fixed; quality.py checks links and anchors; verify.py reads JAVA_HOME and ANDROID_HOME.
- [ ] **14.2 Code comments:** keep the why, cut the promotional and all-caps comments, and correct ones the code no longer matches. Needs a build, so it runs after 16.
- [ ] **14.3 README:** done: feature tour and a privacy note listing every network call. Open: current screenshots (light, dark, phone) once the gallery builds again.

### Open-source essentials

- [ ] **15.1 Community files:** SECURITY.md (private reporting through GitHub advisories), CODE_OF_CONDUCT.md, feature-request and question templates, CHANGELOG.md kept with each release, and the licences of the bundled fonts (OFL for Inter, JetBrains Mono and Noto; MIT for KaTeX).
- [ ] **15.2 CI:** pull-request checks without secrets (quality gates, desktop tests, Android compile), Dependabot for Gradle and Actions, actions pinned by SHA, CodeQL for Kotlin.
- [ ] **15.3 Releases:** semantic versions, release notes from the changelog, published SHA-256 sums, an SBOM (CycloneDX); record code signing for Windows as an option with its cost.
- [ ] **15.4 Quality signals:** coverage report (Kover) and UI screenshot comparison in CI.
- [ ] **15.5 Localization ready:** move interface strings into one resource table; no new language until the table is complete.

### Toolchain and dependencies, bleeding edge

- [ ] **16.1 JDK 25 LTS:** the user replaces JDK 8 with Temurin 25. Point verify.py, README, MEMORY and CI (setup-java) at it; the packaged runtime is jlinked from it.
- [ ] **16.2 Build tools:** latest Gradle (9.1+ is needed to run on JDK 25), AGP, Kotlin and Compose Multiplatform; Android compile and target SDK to the newest stable.
- [ ] **16.3 Libraries:** latest Ktor, OkHttp, coroutines, serialization, AndroidX and JNA; read each changelog for breaking changes and remove workarounds the new versions make unneeded.
- [ ] **16.4 JDK 25 runtime wins:** measure the AOT cache (`-XX:AOTCache`, which supersedes AppCDS) and compact object headers against today's 1.44 s and 274 MB; keep only what measures better.
- [ ] **16.5 Verify:** one `final` and `perf` run, then a checkpoint; record new versions in MEMORY.

### Carried over

- [ ] **11.7 Signature sanitization audit:** check every provider's reasoning-signature rules against its current spec; signatures stay opaque and are never translated.
## Known gaps

Not planned yet; open a task before working on one. CHARX card archives are not supported, and a card with over 512 KB of data imports without keeping its original copy. There are no reusable persona profiles. Voice is dictation and read-aloud, not a full-duplex live session. On Android, MCP servers must be HTTP; local commands are desktop-only. Shell runs with your full account access and is not sandboxed.

## Sources

Reuse these; consult a changed external contract once.

- **OpenAI:** developers.openai.com/api/reference/resources/responses/methods/create; /api/reference/resources/chat/subresources/completions/methods/create; /api/docs/guides/reasoning; /api/docs/guides/prompt-caching.md; /api/docs/pricing; /codex/agent-approvals-security; /codex/sandboxing
- **Gemini:** aistudio.google.com/api-reference/interactions-api; /docs/interactions-overview; /docs/thinking; ai.google.dev/api/generate-content
- **Anthropic:** platform.claude.com/docs/en/api/messages; /build-with-claude/streaming; /build-with-claude/prompt-caching; /build-with-claude/extended-thinking; /build-with-claude/thinking-tool-workflows; /about-claude/pricing; /agents-and-tools/tool-use/web-search-tool
- **DeepSeek:** api-docs.deepseek.com/guides/thinking_mode
- **Rates:** models.dev/api.json; openrouter.ai/api/v1/models; vercel.com/docs/ai-gateway/models-and-providers; raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json
- **Windows:** learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate; /nf-winbase-powersetrequest
- **Android:** developer.android.com/develop/background-work/services/fgs/service-types; /fgs/timeout; /training/monitoring-device-state/doze-standby; /tools/adb; /reference/android/system/Os; /develop/ui/views/notifications/notification-permission
- **JVM and processes:** docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html; /java/lang/Process.html; pubs.opengroup.org/onlinepubs/9799919799/functions/kill.html
- **Formats:** modelcontextprotocol.io/specification/latest/server/tools; github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md
- **Security and GitHub:** cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html; docs.github.com/en/rest/releases/assets (asset digest is nullable, observed as `sha256:<hex>`)
- **Build:** kotlinlang.org/docs/gradle-compilation-and-caches.html; docs.gradle.org/8.13/userguide/configuration_cache.html; docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html
