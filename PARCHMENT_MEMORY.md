# Parchment — Core Engineering, Architecture & QA Memory

## 1. Unbreakable Rules & Three-Command Workflow
1. **Three-Command Workflow**:
   - **`plan`**: Audit root causes, edge cases, loopholes, developer POV, and end-user POV. Design the optimal architecture/UI/UX. **NEVER modify project code during `plan`.**
   - **`execute`**: Implement changes surgically in project files and verify builds/tests locally.
   - **`lock`**: Commit and push verified changes to `origin/main` (`baros4294/parchment`).
2. **Never Touch Local Install Directory**: NEVER overwrite or copy builds into `%LOCALAPPDATA%\Programs\Parchment`. Updates happen exclusively via in-app OTA from GitHub Releases.
3. **Zero Hardcoded Models**: NEVER branch on specific model IDs or version strings (`"gemini-4"`, `"gpt-image"`, `"flux"`, etc.). Route universally via live `/models` discovery, `models.dev/api.json` modalities, and self-healing HTTP parameter cascades.
4. **"Show, Don't Tell" Editorial UI**:
   - Use custom `KissVectorIcon` / `ModelLabSvgIcon` vectors everywhere (zero raw OS emoji strings in UI).
   - Never pair redundant text labels with self-explanatory icons or repeat toggle titles in subtitles.
   - Use bounded vertical palettes for `/` and `@` autocomplete and wrapped `FlowRow`s (never horizontal scroll bars).

## 2. Foolproof Memory, Daemon & Performance Invariants (Anti-OOM Shield)
1. **Zero Orphan Gradle/Kotlin Daemons (`gradle.properties`)**:
   - Always keep `org.gradle.daemon=false` and `-Xmx1280m` in `gradle.properties`, and pass `--no-daemon` when running `./gradlew` so `OpenJDK Platform binary` daemons never linger in Windows Task Manager.
   - Local JDK path on this machine: `$env:JAVA_HOME = "C:\Users\Baros\.jdks\temurin-21"`.
2. **Lean JVM with Aggressive OS Page Uncommit (`composeApp/build.gradle.kts`)**:
   - Keep `-Xms32m`, `-Xmx1024m`, `-XX:+UseG1GC`, `-XX:+UseStringDeduplication`, `-XX:MinHeapFreeRatio=10`, `-XX:MaxHeapFreeRatio=20`, `-XX:-ShrinkHeapInSteps`, `-XX:G1PeriodicGCInterval=8000`, and `-XX:+G1PeriodicGCInvokesConcurrent` so idle working set stays `~130–190 MB` while 100-step agent turns never hit `OutOfMemoryError`.
3. **Streaming & Recomposition Discipline**:
   - Never emit per-SSE-line full request/response snapshots through `Flow`; bound raw wire previews in-place (`<= 4096` chars) and use compact `json.encodeToString`.
   - In `pacerJob`, only slice `StringBuilder.substring()` or `signatureSb.toString()` when the displayed length actually changes.
   - Window long interleaved Thinking & Tool Timelines (`maxVisibleTailSteps = 12` while streaming) so Compose never mounts 50+ `LiteraryProseRenderer` trees at once.
   - Serialize all background disk saves through a single cancellable `diskPersistJob`.
4. **Bounded File & Network I/O (`PlatformBridge.*`, `WorkspaceHarness.kt`)**:
   - Stream-read files in `workspaceRead` (`useLines`) instead of loading whole files into memory (`readLines`), and cap `fetchUrlText` stream reads before regex stripping.
   - Keep inactive chats dehydrated as lightweight stubs (`dehydrateChatSessionToCompactStub` / `hydrateSessionFromDiskIfNeeded`) and strip duplicate in-memory `PromptAttachment.base64Data` when `localFilePath` exists on disk.
   - Reuse `sharedScratchIconPath` in `KissVectorIcon`, cache parsed `Path`s in `ModelLabSvgIcon`, and share `AiProviderEngine.httpClient`.

## 3. Core Architecture Matrix (`v2.0.36+`)
- **Split Persistence**: `parchment-settings.json` (settings/providers/assistants, 0 embedded chats) + `chats/<sessionId>.json` (fast Frame-0 active chat boot + background stub hydration).
- **Uncapped Prompts & Compaction**: Assistant `systemPrompt`, Chat `Extra Instructions`, `Style & Tone Reminder`, `Chat Summary & Memory`, and `generateUtilityText` (`maxOutputTokens = null`) are 100% uncapped (`Int.MAX_VALUE` lines). `prepareCompactionPlan` preserves 100% of `existingStorySummary`.
- **17-Tool Agent Harness (`WorkspaceHarness.kt`)**: Parallel read/search batching, fuzzy whitespace self-healing edits, head+tail bookend size valves, live `shell_exec` streaming, and process-tree kill on Stop.
- **Unified `o200k_base` Tokenizer (`O200kTokenizer.kt`)**: Base session breakdown memoized separately from $O(1)$ live Composer draft tokens; accurate `tok/s` excludes TTFT wait and local tool execution time.
- **Android Compatibility Ceiling (`gradle/libs.versions.toml`)**: `agp = "8.13.2"`, `compileSdk = 36`, `kotlin = "2.3.21"`, `compose-multiplatform = "1.10.3"`, `ktor = "3.5.2"`. Keep Android AAR `minCompileSdk <= 36`.