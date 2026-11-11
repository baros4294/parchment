# Parchment TODO

Updated 2026-11-11. AGENTS.md owns workflow; MEMORY holds contracts. Task IDs stay stable. A checked box means implemented and locally verified; finished tasks are removed once released.

## Current status

- **Source:** batch 12 checkpoint on main (pushed 2026-11-11; CI publishes the next release).
- **Last verified:** 2026-11-11, `verify.py final` and `affected` passed (390 tests, signed R8 APK, package); `perf`: start 1.55 s with 300 chats, idle 289 MB, APK 4.2 MB. The R8 build has not run on a phone yet.
- **Next action:** 12.9 phone check of the R8 release, then batch 13.

## Execution order

Confirmed critical or high defects always go first. Measure before optimizing; one surface at a time.

12.9 → 13.1–13.3 → 12.6 and 12.8 with 13.4–13.7 (same files, one pass per surface) → 12.7 → 14 → 15 → 11.7.

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
- [ ] **12.9 Phone check:** run the R8 release on the phone (chat, settings, MCP, import and export, Pinned tab and jump, font rows); `verify.py perf` for Android numbers; generate an app baseline profile after R8.
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

- [ ] **14.1 One owner per fact:** README (what and how to install), CONTRIBUTING (how to contribute), ARCHITECTURE (code map), DESIGN (look), PERFORMANCE (budgets), MEMORY (contracts), TODO (progress). Remove duplicates and stale lines, merge or retire FEATURE_COVERAGE.md, check every claim against the code, and add a link and anchor check to quality.py.
- [ ] **14.2 Code comments:** keep the why, cut the promotional and all-caps comments, and correct ones the code no longer matches.
- [ ] **14.3 README:** current screenshots (light, dark, phone), a short feature tour and a privacy note listing every network call the app makes.

### Open-source essentials

- [ ] **15.1 Community files:** SECURITY.md (private reporting through GitHub advisories), CODE_OF_CONDUCT.md, feature-request and question templates, CHANGELOG.md kept with each release, and the licences of the bundled fonts (OFL for Inter, JetBrains Mono and Noto; MIT for KaTeX).
- [ ] **15.2 CI:** pull-request checks without secrets (quality gates, desktop tests, Android compile), Dependabot for Gradle and Actions, actions pinned by SHA, CodeQL for Kotlin.
- [ ] **15.3 Releases:** semantic versions, release notes from the changelog, published SHA-256 sums, an SBOM (CycloneDX); record code signing for Windows as an option with its cost.
- [ ] **15.4 Quality signals:** coverage report (Kover) and UI screenshot comparison in CI.
- [ ] **15.5 Localization ready:** move interface strings into one resource table; no new language until the table is complete.

### Carried over

- [ ] **11.7 Signature sanitization audit:** check every provider's reasoning-signature rules against its current spec; signatures stay opaque and are never translated.
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
- **Security and GitHub:** cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html; docs.github.com/en/rest/releases/assets (asset digest is nullable, observed as `sha256:<hex>`)
- **Build:** kotlinlang.org/docs/gradle-compilation-and-caches.html; docs.gradle.org/8.13/userguide/configuration_cache.html; docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html
