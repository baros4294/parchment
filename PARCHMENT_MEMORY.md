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

5. **Codebase Purity & Official Spec Verification**:
   - Keep `composeApp/src/` and `PARCHMENT_MEMORY.md` 100% focused on Parchment only; all acknowledgements to external projects belong exclusively in `README.md`.
   - Always verify API protocol schemas, reasoning/signature rules, and tool-calling formats via official documentation web searches.
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

## 3. Core Architecture Matrix (`v2.0.52+`)
- **Multi-Source Unified Model Registry & Universal Background Auto-Router (`ModelsDevClient.kt`, `AiProviderEngine.kt`, `ParchmentIcons.kt`)**:
  - Concurrently fetches and merges **OpenRouter** (`/v1/models?output_modalities=all` + `/v1/videos/models`), **Vercel AI Gateway** (`/v1/models`), **AIMLAPI** (`/models`), and **LiteLLM** (`model_prices_and_context_window.json`) with canonical fuzzy slug normalization (`5-0` ↔ `5.0`, stripping wrapper prefixes and date snapshots) and modality-preserving `mergeModelSpecs`.
  - Consolidates `ProviderProtocol` to the **Big Three** (`GEMINI_INTERACTIONS`, `OPENAI_RESPONSES`, `ANTHROPIC_MESSAGES`) with automatic persistence migration for legacy `BYTEDANCE_ARK` profiles and tail-free URL resolution (`resolveSmartEndpointUrl` supporting `useExactEndpointUrl`, trailing `#`, concrete endpoint URLs, and `/v1`/`/v2`/`/v3`/`/v4`/`/v1beta` roots).
  - 3-tier `resolveModelModalityProfile` automatically routes text/reasoning, dedicated/interleaved image (`seedream`, `flux`, `imagen`, `gpt-image`), video (`seedance`, `veo`, `sora`, `kling`, `wan`, `hailuo` across `/contents/generations/tasks`, `/videos/generations`, `/video/generations`, `/videos`), and audio/TTS (`/audio/speech`) models in the background on any OpenAI-compatible proxy, and surfaces context-aware Aspect Ratio, Resolution, Duration, Audio, and Voice chips in the composer bar plus a non-overlapping Floating Live Audio Pill above the `Latest` scroll pill.
  - `resolveLabSlugForModelOrProvider` strictly evaluates `modelOrProvider` and registry/discovered vendor slugs before `fallbackProtocolName` so models on OpenAI proxies always display their true creator lab icon.
- **Foolproof Multi-Protocol Signature & Tool-Batch Engine (`AiProviderEngine.kt`, `O200kTokenizer.kt`)**:
  - `sanitizeGeminiThoughtSignature` splits legacy glued Base64 signatures (`...=...==`) into valid protobuf segments and strips server-side `08 02` (`google_search`) signatures from `function_call` steps.
  - `appendSignatureChunkSafe` prevents concatenating distinct complete Base64 signatures across rounds or server-side tool invocations.
  - `groupToolCallsIntoStepBatches` preserves parallel (`FC1, FC2 -> FR1, FR2`) and sequential (`Batch 1 -> Batch 2`) tool boundaries across Gemini Interactions, Gemini GenerateContent, OpenAI Responses, OpenAI Chat Completions (with closing `assistant` prose after `role: "tool"`), and Anthropic Messages, with strict cross-provider signature isolation and HTTP 400 `foldHistoricalToolsOnRetry` self-healing.
- **Wall-Clock Generation Throughput & Per-Round Token Accounting (`AiProviderEngine.kt`, `ParchmentApp.kt`)**:
  - Computes `tokensPerSec` by dividing total generated tokens (`finalOutTokens + finalThinkTokens`, normalized so combined `output_tokens`/`completion_tokens` never double-count `reasoning_tokens`) by net LLM wall-clock generation time (`totalDurationMs - totalToolExecutionMs`), tracking non-overlapping parallel tool batch wall-clock wait time and preserving `initialElapsedMs` across mid-stream auto-compaction.
- **Self-Contained Per-Chat Folder Hierarchy & Split Persistence**:
  - `parchment-settings.json` + `backups/` (`parchment-settings.prev.json`, `parchment-settings.user-backup.json`) + `chats/<sessionId>/chat.json` + `chats/<sessionId>/media/*` (auto-migrates legacy flat `chats/*.json` and root `media/*`, and saves all uploaded + AI-generated images/videos scoped to `sessionId`).
  - Streaming `.chat.zip` / `.zip` bundle export & import (`encodeChatSessionsToZipBundleBytes` / `decodeChatSessionsFromPickedFile`) bundles `chat.json` + `media/` using `8 KB` file streams and portable relative media paths.
- **System-Wide Android Workspace Folder Picker (`AndroidManifest.xml`, `MainActivity.kt`, `PlatformBridge.android.kt`)**:
  - Uses `ActivityResultContracts.OpenDocumentTree` + `MANAGE_EXTERNAL_STORAGE` (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` on API 30+) and `resolveTreeUriToFilePath` (`primary:`, `raw:`, `home:`, `<volumeId>:`) so Android workspace tools can mount any folder on shared storage (`/storage/emulated/0/...`).
- **Context-Aware Markdown, Nested Prose & LaTeX Engine (`ProseRenderer.kt`, `LatexMathRenderer.kt`)**:
  - Nested delimiter scanners (`*...*` skipping inner `**bold**`, `` `code` ``, `$math$`; word-boundary `_..._`; currency-safe `$...$`), indented/checkbox lists, multi-line display equations, and `\mathcal`/`\mathfrak`/`\textbf`/`\cancel` support.
- **Uncapped Prompts & Compaction**: Assistant `systemPrompt`, Chat `Extra Instructions`, `Style & Tone Reminder`, `Chat Summary & Memory`, and `generateUtilityText` (`maxOutputTokens = null`) are 100% uncapped (`Int.MAX_VALUE` lines). `prepareCompactionPlan` preserves 100% of `existingStorySummary` and sanitizes bloated signatures on kept turns.
- **17-Tool Agent Harness (`WorkspaceHarness.kt`)**: Parallel read/search batching, fuzzy whitespace self-healing edits, head+tail bookend size valves, live `shell_exec` streaming, and process-tree kill on Stop.
- **Unified `o200k_base` Tokenizer & `@Immutable` Models (`O200kTokenizer.kt`, `Models.kt`)**: `@Immutable` stability across all model classes, $O(1)$ `estimateTokensFast` during active streaming (preventing `tokenCache` eviction), and `countSessionVaultItemsFast`.
- **Android Compatibility Ceiling (`gradle/libs.versions.toml`)**: `agp = "8.13.2"`, `compileSdk = 36`, `kotlin = "2.3.21"`, `compose-multiplatform = "1.10.3"`, `ktor = "3.5.2"`. Keep Android AAR `minCompileSdk <= 36`.