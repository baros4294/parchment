# Parchment — Minimal Bleeding-Edge Literary & Agent AI Studio

**Parchment** is a native, zero-bloat KISS AI frontend for **Windows 11** and **Android 16** (and universal desktop/mobile targets) built with **Kotlin Multiplatform + Compose Foundation primitives** (`compose.runtime`, `compose.foundation`, `compose.ui` only—zero Electron/WebView bloat, zero Material 3 or Fluent UI libraries).

[![Latest Release](https://media.zai.is/image/fpBYbdc3hIwxUjEcRis5Rn.svg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=8432200c273d4a44c7ab1f489bc53f93%2F20261101%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20261101T074015Z&X-Amz-Expires=3600&X-Amz-SignedHeaders=host&X-Amz-Signature=848f3a85cfd88ee03d8e2680edf271645639d3b322efd1746d1805cfcf903d96)](https://github.com/baros4294/parchment/releases/latest)

---

## Core Architecture & Highlights

### 1. "Quiet Folio" KISS Design Language & Physics Animations
* **Zero-Bloat Compose Foundation UI:** Built strictly on `BasicText`, `BasicTextField`, `LazyColumn`, and `Canvas` with custom Sepia & Espresso Ink typography and zero Material/Fluent dependencies.
* **Single-Row Editorial Header & Unified Studio:** Clean top bar with a 1-tap `☰ Folio` sidebar toggle, clickable **Session & Active Model Identity**, `📖 Manuscript / ▤ Turns` toggle, and a unified **`⚙ Studio`** drawer featuring a `SepiaSegmentedBar` (`⚙ Providers & Models` | `✦ Cards & Lore` | `🎨 Theme & OTA`) with smooth `Crossfade` transitions.
* **Unified Floating "Command Deck" Composer:**
  * Softly-rounded floating composer card (`RoundedCornerShape(14.dp)`) with a borderless-feel prompt input and dynamic resolution-independent layout (`BoxWithConstraints` + `FlowRow`) that scales proportionally across any portrait or landscape phone, foldable, tablet, or desktop screen without clipping controls.
  * **Progressive-Disclosure Capabilities Tray (`🧠 Thinking` & `⚙ Tools`):** Keeps all 10 protocol, media, thinking-effort, and workspace-agent toggles 1 tap away inside an animated drawer without cluttering the reading canvas.
* **Tactile Spring Micro-Interactions:**
  * `Modifier.kissClickable` spring-physics press scaling (`Spring.StiffnessMediumLow`) and `animateColorAsState` transitions across all buttons, chips, and segmented controls.
  * Smooth `AnimatedVisibility` and `Modifier.animateContentSize` accordions for Reasoning & Tool-Call traces, Scene & Memory Inspector, Capabilities Tray, and Raw Wire JSON drawers.
  * `KissStreamingPulseDot` breathing indicator during live SSE streaming and long-horizon reasoning.

---

### 2. Long-Horizon Workflow Engine (Active by Default in All Modes)
Designed to overcome the **~5-minute (`300s`) reverse-proxy/gateway timeout wall** (Cloudflare `524`, Nginx `proxy_read_timeout`, API relays, carrier NAT) across **Chatting, Literary Writing, Roleplay, and Agent Modes** by default:
* **60-Minute Socket Keep-Alive & Android 16 Foreground Service:** Ktor `requestTimeoutMillis` and `socketTimeoutMillis` are configured for **60 minutes**, backed by `LongDecodeForegroundService` (`PARTIAL_WAKE_LOCK`) on Android so long decodes survive screen-off and Doze mode.
* **64-Step Iterative Agent & Tool Loop (`MAX_LONG_HORIZON_STEPS = 64`):** Supports up to 64 consecutive tool/continuation rounds per turn across all provider protocols.
* **Pre-Token Deep-Thinking Auto-Retry (`MAX_PRE_TOKEN_RETRIES = 3`):** Automatically retries with exponential backoff (`2s → 4s → 6s`) if a proxy/gateway drops or returns `502`/`503`/`504`/`524`/`429` while a reasoning model (e.g., Gemini 4 Argon, GPT-6.1 Sol, Claude Opus 5.5) is thinking deeply before emitting prose.
* **Automatic Mid-Stream 5-Minute Cutoff Stitching (`MAX_MID_STREAM_CONTINUATION_PASSES = 4`):** If an upstream proxy severs an active SSE stream around the 5-minute mark after partial prose has already been generated, Parchment automatically chains a seamless continuation pass from the exact tail of the partial output (`continueFromAssistantPrefix`), allowing a single response turn to span **20+ minutes across multiple 5-minute proxy windows**.

---

### 3. Four First-Class Bleeding-Edge Provider Protocols (Zero Hardcoded Models)
* **Automatic & Manual Model Discovery:** Automatically queries `/v1beta/models`, `/v1/models`, or `/api/v3/models`, or lets you type and save any Model ID manually.
* **Universal Thinking Effort Control:** Quick-cycle and inline preset selector (`minimal`, `none`, `low`, `medium`, `high`, `xhigh`, `max`) plus a manual free-text thinking level input.
* **Google Gemini Interactions API (`/v1beta/interactions` & `:streamGenerateContent`):**
  * Uses the official discriminated `Step` schema (`user_input`, `model_output`, `thought`, `function_call`, `function_result`) and parses `step.start`, `step.delta`, `content.delta`, and `interaction.completed` SSE streams.
  * Native **Gemini 4 Argon** dialect (`thinking_level: low/medium/high`, `SamplingPolicy.never`, cryptographic `thoughtSignature` preservation, 65,536 default max output tokens).
  * Native image generation (`response_modalities: ["TEXT", "IMAGE"]`), stateful `previous_interaction_id` chaining, `background: true` async server polling, built-in grounding (`google_search`, `code_execution`, `url_context`), and automatic dual `x-goog-api-key` + `Authorization: Bearer` headers on custom proxies.
* **OpenAI Responses API (`/v1/responses`), Chat Completions (`/v1/chat/completions`), & Direct Images (`/v1/images/generations`):**
  * Official `/v1/responses` assistant `output_text` message items, stateful `previous_response_id` chaining, and stateless `function_call` + `function_call_output` tool-loop replay.
  * Native `image_generation` tool + automatic standalone routing to `/v1/images/generations` for GPT Image, DALL-E, Flux, and OpenAI-compatible image models.
  * Full 64-step `tool_calls` loop, multimodal `image_url` vision inputs, and `stream_options: { include_usage: true }` on `/v1/chat/completions`.
* **Anthropic Messages API (`/v1/messages`):**
  * Supports **2026 Adaptive Thinking** (`thinking: { type: "adaptive" }` + `output_config: { effort }`) for Claude 4.6 / 5.x models alongside classic `budget_tokens` thinking and cryptographic `signature` verification.
  * **Automatic Ephemeral Prompt Caching (`cache_control`)** on system prompts, cards, and rolling turn history.
  * Strict user-first turn validation (automatically handling sessions that open with an Assistant character card greeting), multimodal base64 image blocks, and native Assistant Prefill.
* **ByteDance Seedream / Seedance / Doubao Ark API (`/api/v3/...`):**
  * Smart modality routing: **Seedream** (`enableImageGeneration`) dispatches to `/api/v3/images/generations`, **Seedance** (`enableVideoGeneration`) dispatches to `/api/v3/contents/generations/tasks` with async video task polling and audio generation (`generate_audio`), and **Doubao** text/reasoning models route to `/api/v3/chat/completions`.

---

### 4. Live Token Telemetry, `models.dev` Pricing Lookup, & Context Gauge Ring
* **Automatic `models.dev` Integration (`ModelsDevClient`):** Automatically looks up context window limits, max output tokens, and input/output/cached/reasoning prices per 1M tokens from `https://models.dev/api.json`, with full manual override support per model.
* **Animated Circular Context Gauge Ring (`SepiaContextGaugeRing`):** Live circular progress arc + compact token/cost readout (`1.4k/128k (1%) • $0.0012`) in the Command Deck.
* **Per-Turn & Session Telemetry:** Tracks `TTFT (ms)`, `tok/s`, `Input`, `Cached Input`, `Reasoning`, `Output`, `Total Tokens`, and estimated `USD Cost`.

---

### 5. Bundled Typography, Native LaTeX Math & Markdown Tables
* **Bundled Fonts Out of the Box:** Ships with **Inter** (Prose & UI), **JetBrainsMono Nerd Font** (Code, Telemetry & Wire Inspector), and **KaTeX (`KaTeX_Main`, `KaTeX_Math`) + `NotoSansMath`** (Mathematical Typesetting), plus support for loading any custom `.ttf` / `.otf` font from disk.
* **Native Compose LaTeX Math Engine (`LatexMathRenderer.kt`):** Renders display math (`$$...$$`, `\[...\]`) and inline math (`$...$`, `\(...\)`) with stacked fractions (`\frac{a}{b}`), matrices/cases (`\begin{pmatrix}...`, `\begin{cases}...`), superscripts/subscripts, blackboard bold (`\mathbb{R}`), and Greek/calculus operators.
* **Literary Prose & Markdown Renderer (`ProseRenderer.kt`):** Distinct ink tinting for `"spoken dialogue"`, `*italic narration*`, `— ✦ —` scene breaks, code fences with 1-tap copy, and striped Markdown tables.

---

### 6. Literary Co-Writing Workflows & `.png` / `.json` Card Protocol
* **Guided Scene Direction (`🎬 Direct`):** Steer the next AI passage with plot/scene instructions without adding a visible user chat bubble.
* **Impersonate / Draft Turn (`✍ Impersonate`):** Have the model draft `{{user}}`'s next response directly into the composer input box.
* **Rolling Continuity Memory (`✦ Auto-Summarize Chat`):** Summarizes key plot points, character states, and established facts into persistent session memory.
* **Branching, Swiping (`← 1/3 →`), & Chat Forking (`⑂ Fork`):** Non-destructive variant swiping, mid-sentence **`→ Continue`**, in-place prose editing, and 1-tap session forking from any turn.
* **Binary `.png` & `.json` Card / Lorebook Codec (`CardAndPromptCodec.kt`):** Pure-Kotlin PNG `tEXt` / `iTXt` chunk parser and injector (`chara`, `ccv3`, `parchment_prompt`) with automatic **`character_book` Lorebook extraction** into keyword-triggered Context Fragments.

---

### 7. Built-In Sandboxed Local Workspace Agent Harness
* Connects directly to the native function-calling loops of Gemini, OpenAI, Anthropic, and Doubao with zero CLI bridge required.
* **8 Built-In Agent Tools (`WorkspaceHarness.kt`):**
  * `fs_list`, `fs_read`, `fs_search` (Read-Only & Read-Write modes)
  * `fs_write`, `fs_edit` (Read-Write mode, with CRLF/LF preservation)
  * `dice_roll` (Cryptographically random RPG/tabletop dice roller `2d20kh1+5`, `4d6dl1`)
  * `agent_memory` (Persistent `.parchment-memory.md` notebook inside the workspace)
  * `shell_exec` (Opt-in sandboxed PowerShell / POSIX `sh` execution)
* Automatically discovers `AGENTS.md`, `CLAUDE.md`, and `.cursorrules` in mounted workspaces.

---

### 8. Low-RAM & High-Frame-Rate Streaming Architecture
* **Zero-Disk-IO Streaming:** Token deltas mutate Compose state in memory only (`updateActiveSessionInMemoryOnly`), coalesced to `~30fps` (`32ms` frame windows) with zero-allocation word counting (`countWordsFast`).
* **Debounced Background Persistence:** State snapshots serialize asynchronously on `Dispatchers.Default` using compact JSON (`prettyPrint = false`), flushing immediately on turn completion.
* **Disk-Backed Lazy Media & Bounded LRU Bitmap Cache:** Image attachments and generated media live on disk (`localFilePath`) and are lazily read on API dispatch; UI previews use a bounded 16-entry LRU bitmap cache with downsampled Android decoding (`inSampleSize` capped at `1600px`).

---

### 9. In-App Browserless OTA Updates & Zero-Trace Installers
* **In-App OTA Updater (`OtaUpdateEngine.kt`):** Automatically checks GitHub Releases on startup (or manually in **⚙ Studio → 🎨 Theme & OTA**), streams the platform installer with live MB/% progress, and installs in-app:
  * **Android 16 (`.apk`):** Commits a `PackageInstaller` session signed with the persistent repository keystore (`keystore/parchment-ota-signing.jks`) so updates install seamlessly over existing builds without wiping chats or settings.
  * **Windows 11 (`.exe`):** Runs the Inno Setup installer silently (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS`) and automatically relaunches `Parchment.exe`.
* **Zero-Trace Uninstall:** Uninstalling removes 100% of application binaries and isolated local data directories (`%LOCALAPPDATA%\ParchmentAI` on Windows, `noBackupFilesDir` with `allowBackup="false"` on Android).

---

## Local Build Commands

```powershell
# Set JDK 21 & Android SDK paths (Windows PowerShell)
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Run Desktop App (Windows / macOS / Linux)
.\gradlew.bat :composeApp:run

# Build Standalone Windows App Image
.\gradlew.bat :composeApp:createDistributable

# Build Signed Android Release APK
.\gradlew.bat :composeApp:assembleRelease

# Run Protocol & Workspace Harness Unit Tests
.\gradlew.bat :composeApp:desktopTest
```
