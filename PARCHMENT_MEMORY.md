# Parchment — Engineering, UI/UX, Architecture & QA Memory

## 1. Hard Rules & Three-Command Workflow
- **NEVER overwrite or copy-paste local builds into the user's local installation directory** (`%LOCALAPPDATA%\Programs\Parchment` or similar). The user updates their installed app exclusively via in-app OTA from GitHub Releases (`baros4294/parchment`).
- **ZERO Hardcoded Models — Universal & Future-Proof Generality**:
  - **NEVER hardcode specific model IDs, model version numbers, or vendor model lists** anywhere in the codebase (e.g., never branch on `"gemini-4"`, `"3.8-flash"`, `"gpt-image"`, `"dall-e"`, `"flux"`, `"seedream"`, `"seedance"`).
  - All model routing, modality handling (text, reasoning, image generation, video generation, audio, multimodal), and parameter shaping MUST work **universally for any present or future model** via live metadata discovery (`/v1/models`, `/v1beta/models`, `models.dev/api.json` modalities) and **bidirectional self-healing HTTP protocol/parameter cascades**.
- **"Show, Don't Tell" Timeless Editorial UI (2020–2026 KISS Philosophy)**:
  - Never pair redundant text labels with universally understood icons (e.g., Copy, Save, Speak, Redo, Edit, Delete, Expand/Collapse, Dismiss/Reset). Let crisp vector icons speak for themselves.
  - Never use horizontal scrolling bars for autocomplete (`/` or `@`) or filter pills; always use bounded **Vertical Command/Mention Palettes** and wrapped `FlowRow`s.
- **Three-Command Workflow**:
  1. `plan`: Plan extensively — analyze edge cases, loopholes, developer POV, and end-user POV to design the best architecture, UI, and UX possible. **Do not modify project code during `plan`.**
  2. `execute`: Implement the planned code changes directly in project files and verify builds/tests locally (without touching the local installation directory).
  3. `lock`: Commit and push the changes to the GitHub repository (`baros4294/parchment`).

## 2. Role & Mission Charter
You are an elite senior frontend engineer, UI/UX designer, product architect, and software quality assurance specialist. Your mission is to perfect this application in every possible aspect and transform it into a polished, modern, professional, production-ready product.

Do not simply make superficial visual changes. Thoroughly inspect the entire codebase, understand the existing architecture, identify weaknesses, and improve everything that needs improvement while preserving the application's core purpose and existing functionality.

### Core Responsibilities
1. **UI/UX DESIGN** — Create an exceptional user experience with a clean, modern, visually cohesive interface. Improve layouts, spacing, typography, colors, hierarchy, alignment, icons, animations, transitions, and visual consistency. Every screen should feel intentional, refined, and professionally designed. Eliminate unintentional or awkward text truncation by adding clean newlines/stacking or making labels more concise and precise.
2. **FUNCTIONALITY** — Audit every feature, button, menu, interaction, setting, input, modal, dropdown, and navigation element. Fix broken behavior, incomplete implementations, unexpected errors, and inconsistent interactions. Nothing should look functional while secretly doing nothing.
3. **RESPONSIVENESS** — Ensure the application works beautifully across desktop, laptop, tablet, and mobile devices (including 360dp–412dp portrait phones). Handle different screen sizes, aspect ratios, and viewport dimensions without broken layouts, overflow, or awkward spacing.
4. **PERFORMANCE** — Optimize rendering, state management, component architecture, asset loading, memory usage, and unnecessary computations. Eliminate avoidable re-renders, sluggish interactions, and performance bottlenecks (e.g., 60/120fps VSync streaming without layout thrashing).
5. **CODE QUALITY** — Refactor messy, duplicated, unnecessarily complicated, or poorly structured code. Establish consistent naming conventions, reusable components, clean abstractions, maintainable architecture, and sensible separation of concerns. Avoid unnecessary dependencies and overengineering.
6. **ACCESSIBILITY** — Implement proper semantics, keyboard navigation (`Ctrl+V`, `Enter`/`Shift+Enter`/`Ctrl+Enter`), visible focus/hover/press states, readable contrast, and accessible interaction patterns across Desktop and Android.
7. **ERROR HANDLING** — Identify potential edge cases, runtime errors, failed requests, interrupted streams, invalid inputs, loading failures, and unexpected application states. Provide graceful error handling, recovery, and useful feedback instead of allowing the application to break silently.
8. **VISUAL POLISH** — Pay attention to the smallest details. Refine hover states, active states, disabled states, loading indicators, pulse indicators, empty states, scrollbars, borders, and subtle spring animations. Maintain a consistent warm editorial design language throughout the application.
9. **LOCAL & MULTI-PROVIDER AI INTEGRATION** — Carefully inspect the connection between the interface and its AI backends (Gemini Interactions, Google Generative AI, OpenAI Responses, OpenAI Chat Completions, Anthropic Messages, ByteDance Ark, and local/custom endpoints). Ensure model selection, inference requests, SSE streaming responses, conversation management, generation controls, system prompts, parameters, thought signatures, tool calls/results, and error states work reliably. Preserve local-first functionality and never silently introduce unnecessary cloud dependencies.
10. **SECURITY AND RELIABILITY** — Validate inputs, sandbox workspace file/shell operations, handle untrusted model outputs safely, avoid exposing sensitive information, and review potentially unsafe rendering or execution paths. Ensure the application behaves predictably under unusual conditions.
11. **USER EXPERIENCE** — Make every workflow intuitive and frictionless. Reduce unnecessary clicks, provide clear feedback, improve discoverability, and eliminate confusing or redundant interactions. The application should feel natural and enjoyable to use.
12. **FINAL QUALITY ASSURANCE** — Test the entire application after making changes during `execute`. Check all major workflows, interactions, responsive layouts, build errors, and integration points. Fix every issue discovered rather than simply documenting it.

### Important Execution Rules
- Start by inspecting the existing project structure, package configuration, framework, dependencies, and architecture.
- Understand the existing application before modifying anything.
- Preserve existing features, functionality, and intended design direction unless there is a clear reason to improve them.
- Do not replace working implementations with incomplete mockups, placeholders, or hardcoded demonstrations.
- Do not leave TODO comments, dead buttons, fake interactions, unfinished components, or temporary implementations.
- Avoid unnecessary rewrites. Make thoughtful, targeted improvements.
- Maintain compatibility with the existing Kotlin Multiplatform + Compose Multiplatform technology stack.
- Do not claim something works unless you have actually verified it.
- When encountering a problem, investigate its root cause instead of applying superficial workarounds.
- During `execute`, make actual changes directly to the project files rather than merely suggesting improvements.

### Definition of Done
The application should look premium, feel responsive, work reliably, remain maintainable, and provide a consistent experience across every screen and interaction. Do not stop after the first round of improvements—inspect, implement, test, refine, and repeat until no obvious problems or meaningful improvements remain.

## 3. Current Architecture & Dependency Matrix (`v2.0.36`)
- **Split Storage Architecture (`parchment-settings.json` + Per-Chat `.json` Files in `chats/`)**:
  - Local disk persistence (`PlatformBridge` & `ParchmentStatePersistence.kt`) is cleanly split into `parchment-settings.json` (Providers, Preferences, Assistants, System Prompts, Default Models, Theme, World Book — zero embedded chat histories) and individual `chats/<sessionId>.json` files (one file per chat, with automatic migration from legacy `parchment-state.json`).
  - `Settings → Backup & OTA` provides separate **Export All Chats (`N` Separate `.chat.json` Files)**, **Export Current Chat (`.json`)**, **Import Chat(s) (`.json` multi-file picker)**, and **Export / Import Settings & Assistants (`.json`)**.
- **Complete 17-Tool Workspace Agent Harness & 1-Tap Folder Mount**:
  - `WorkspaceHarness.allTools()` exposes all 17 built-in tools (`fs_list`, `fs_glob`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`, `fs_multi_edit`, `fs_move`, `fs_delete`, `agent_memory`, `shell_exec`, `web_search`, `web_fetch`, `calc_eval`, `dice_roll`, `todo_write`, `goal_complete`) with live status and 1-tap activation in both `Settings → Workspace` and the Composer `⚙ Tools` tray (`📂 Mount Folder…`, `@mount`, `🔧 N/17 Tools`).
  - Deep package tree traversal (`maxDepth = 24` with `.onEnter` pruning for `.git`, `build`, `.gradle`, `node_modules`) across Desktop and Android.
- **Zero-Dim Studio Attachment Viewer & High-Contrast Frosted `"↓ Latest"` Pill**:
  - `ParchmentAttachmentViewerModal` opens images, code, `.svg` (with live vector preview), and `.md` (with rendered prose preview) in an elevated Studio Card without dimming the background UI.
  - Floating `"↓ Latest"` button uses an opaque frosted halo + solid `p.accent` pill so underlying manuscript text never bleeds through.
- **Unified `o200k_base` Tokenizer & Context Breakdown Popover (`protocol/O200kTokenizer.kt`)**:
  - Implements OpenAI's official `o200k_base` pre-tokenization regex + UTF-8 subword BPE counter across the Circular Context Ring, `ContextWindowBreakdownPopover` (7 itemized categories: `System & Persona`, `Lorebook & Memory`, `Workspace & Tool Schemas`, `Uploaded Files`, `Chat History`, `Current Composer Draft`, and `Free Space Remaining`), User message token/cost pills, and Assistant message token/speed/cost pills.
- **Line-Grounded `"🌐 N Sources"` Button & Free Multi-Engine `web_search` Cascade**:
  - Extracts `WebSourceCitation`s across all 5 protocols (`AiProviderEngine.kt`) and binds each source to exact 1-based line numbers (`Line N`) and quotes in the assistant's reply (`bindSourcesToMessageLines`). Clicking a source highlights the grounded paragraph in `ProseRenderer.kt`.
  - When native provider web search is unavailable or rejected, automatically falls back to `WorkspaceHarness.executeFreeWebSearch()` (zero-key **DuckDuckGo HTML/Lite** $\rightarrow$ **Wikipedia API** $\rightarrow$ **HackerNews Algolia API** cascade).
- **Collapsible Right Sidebar (`ParchmentRightFilesSidebar` in `ParchmentModalsAndNavigation.kt`)**:
  - Toggled via `📁 N` in `TopEditorialBar`; organizes active chat artifacts into `✦ AI-Generated Files & Media`, `↑ User-Uploaded Documents`, and `🌐 Grounded Web Sources`.
- **Android CI / AGP `8.13.2` Compatibility Ceiling (`gradle/libs.versions.toml`)**:
  - Uses `agp = "8.13.2"`, `android-compileSdk = "36"`, `kotlin = "2.3.21"`, `compose-multiplatform = "1.10.3"`, `ktor = "3.5.2"` (`okhttp 5.3.2`), `kotlinx-serialization = "1.11.0"`, `kotlinx-coroutines = "1.11.0"`, and `androidx-activityCompose = "1.12.4"`. Keep Android AAR dependencies within `minCompileSdk <= 36` unless migrating AGP to `9.2+`.

- **RAM Footprint Reduction, Instant Startup, Accurate `tok/s` Telemetry & Prefix-Cached 120fps Streaming**:
  - **Lean JVM & Automatic OS Page Uncommit (`composeApp/build.gradle.kts`)**: Configured `-Xms32m`, `-Xmx384m`, `-XX:+UseG1GC`, `-XX:+UseStringDeduplication`, `-XX:MinHeapFreeRatio=10`, `-XX:MaxHeapFreeRatio=25`, `-XX:-ShrinkHeapInSteps`, `-XX:G1PeriodicGCInterval=10000`, and `-XX:+G1PeriodicGCInvokesConcurrent` so freed heap pages are periodically uncommitted back to Windows/OS.
  - **Zero-Allocation Vector Icons & Shared `HttpClient` (`ParchmentIcons.kt`, `AiProviderEngine.kt`, `ModelsDevClient.kt`, `OtaUpdateEngine.kt`)**: Reused a single `sharedScratchIconPath` in `KissVectorIcon` and cached `Path` vectors drawn via `withTransform` in `ModelLabSvgIcon`; consolidated 4 `HttpClient` instances into `AiProviderEngine.httpClient`.
  - **Fast-Path Active Session Startup & Lazy Session Dehydration (`PlatformBridge.*`, `ParchmentStatePersistence.kt`, `ParchmentApp.kt`)**: Added `loadChatSessionJsonById`, `loadFastInitialAppState()` (loads only `parchment-settings.json` + the active chat on Frame 0), background hydration on `Dispatchers.Default`, and automatic dehydration of inactive chats (`dehydrateChatSessionToCompactStub` / `hydrateSessionFromDiskIfNeeded`) preserving `messages.size` and media file references while freeing 99.9% of inactive chat RAM.
  - **Accurate Real-Time & Final `tok/s` Telemetry (`AiProviderEngine.kt`, `ParchmentApp.kt`)**: Added `computeTurnTokenMetrics` and active token burst tracking (`recordTokenActivity`, `closeCurrentDecodeBurst`) that excludes TTFT wait and local tool execution time (`totalToolExecutionMs`) and sums reasoning + output tokens across multi-step tool rounds; added live `tok/s` updates while streaming.
  - **$O(1)$ Incremental Prefix-Cached Markdown Block Parser (`ProseRenderer.kt`)**: Implemented `IncrementalProseBlockCache` so 120Hz VSync streaming only parses the active trailing `<512`-char tail instead of re-parsing the entire message from character 0 on every frame.

- **OOM Heap Crash Fix on Long Agent Turns, Uncapped Compaction/System Prompts & Zero-Daemon Gradle (`c3217e8`)**:
  - **Why `OpenJDK Platform binary (2)` Used 1.9 GB**: Running `./gradlew desktopTest` previously spawned persistent `GradleDaemon` + `KotlinCompileDaemon` processes with `-Xmx3G`. Set `org.gradle.daemon=false` and `-Xmx1280m` in `gradle.properties` so Gradle automatically exits after builds/tests and never leaves background OpenJDK processes in Task Manager.
  - **Why `Parchment.exe` Hit `Java heap space` / `Recomposer` Fatal Exception on 60+ Tool Turns**:
    1. Raised JVM ceiling from `-Xmx384m` to `-Xmx1024m` while keeping `-Xms32m`, `-XX:MaxHeapFreeRatio=20`, and `-XX:G1PeriodicGCInterval=8000` so idle RAM stays low (`~130–190 MB`) while heavy 100-step agent turns never run out of heap.
    2. Eliminated per-SSE-line `StreamUpdate.RawWireSnapshot` channel emissions and switched outgoing wire serialization from `prettyJson` to compact `json`, bounding `lastRawReq`/`lastRawResp` to `4096` chars in place.
    3. Cached `cachedVisibleCont`, `cachedVisibleThink`, `cachedVisibleStepThink`, and `cachedSig` in `pacerJob` so `100ms` stopwatch ticks never re-allocate 50KB+ `StringBuilder.substring()` or `signatureSb.toString()` copies when text lengths haven't changed.
    4. Windowed the interleaved Thinking & Tool Timeline in `MessageTurnItem` (`maxVisibleTailSteps = 12` while streaming, with a `"▾ Show N earlier tool steps"` button) so Compose never mounts 60+ `LiteraryProseRenderer` subtrees simultaneously during an active tool loop.
    5. Serialized `persist` and `persistSingleSession` through a single cancellable `diskPersistJob` so `immediateFlush = true` never spawns concurrent full-session JSON serialization coroutines.
  - **Uncapped Compaction, Summarization, System Prompts & Chat Notes**:
    - Removed hardcoded `maxOutputTokens = 1024` from `AiProviderEngine.generateUtilityText()` (`maxOutputTokens: Int? = null`), preserved 100% of `existingStorySummary` in `O200kTokenizer.prepareCompactionPlan()`, raised compaction timeout to `180_000L`, uncapped `SepiaTextField` multi-line `maxLines = Int.MAX_VALUE`, and raised workspace memory file reads to `10,000` lines.