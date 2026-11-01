# Parchment — Minimal Bleeding-Edge Literary & Agent AI Studio

**Parchment** is a native, zero-bloat AI frontend for **Windows 11** and **Android 16** (and universal desktop/mobile targets) built with **Kotlin Multiplatform + Compose Foundation primitives** (zero Electron/WebView bloat, zero Material 3 or Fluent UI libraries).

---

## Core Architecture & Highlights

### 1. First-Class Native Bleeding-Edge Wire Protocols (Zero Hardcoded Models)
* **Automatic & Manual Model Fetching:** Automatically fetches available models from `/v1beta/models` or `/v1/models` when an API key or custom proxy URL is provided, or lets you type and save any Model ID manually.
* **Google Gemini Interactions API (`/v1beta/interactions` & `/v1/interactions`):**
  * Full **Long Decode** support (60-minute socket timeouts, live SSE streaming via `?alt=sse`, AND optional **`background: true` server-side async polling** + server-side `/cancel` so long chapter decodes survive network drops or phone screen locks).
  * Stateful multi-turn chaining via `previous_interaction_id` (plus 1-click toggle for stateless `store: false` proxy mode).
  * Built-in grounding toggles (`google_search`, `code_execution`, `url_context`) and optional `:streamGenerateContent` fallback for older Gemini proxies.
* **OpenAI Responses API (`/v1/responses`):**
  * Stateful `previous_response_id` chaining, `reasoning: { effort, summary }`, `web_search_preview`, and a 1-click `/v1/chat/completions` fallback toggle for third-party OpenAI-compatible proxies.
* **Anthropic Messages API (`/v1/messages`):**
  * Extended Thinking (`thinking` + cryptographic `signature` preservation across multi-turn tool loops).
  * **Automatic Ephemeral Prompt Caching (`cache_control`)** placed on the System Prompt, Character/Scenario Card, and rolling history breakpoint to maintain ~90% cache hit rates during long fiction and co-writing sessions.
  * Native Assistant Prefill for seamless mid-sentence continuation.

### 2. Custom Base URL Proxy & Raw JSON Escape Hatch
* Override `baseUrl` and `endpointPathOverride` per profile.
* Switch auth header modes (`Provider Native`, `Force Authorization: Bearer <key>`, or `Query Param ?key=<key>`).
* Add custom HTTP headers and **Raw JSON Extra Body Overrides** (deep-merged into outgoing requests for day-0 model parameters).

### 3. Custom "Parchment & Espresso Ink" Sepia UI (Compose Foundation Only)
* Built strictly on `androidx.compose.foundation` (`BasicText`, `BasicTextField`, `LazyColumn`) with **zero Material or Fluent dependencies**.
* **Literary Prose Renderer:** Distinct ink tinting for `"spoken dialogue"`, `*italic narration*`, and `— ✦ —` scene breaks.
* **Manuscript View vs. Editorial Turns View:** Read and co-write like a continuous book manuscript or structured turns.
* **Branching & Swipe Variants (`← 2 / 4 →`):** In-place prose editing, mid-sentence **`Continue →`**, and non-destructive story branching.
* **Cache-Friendly Author's Note:** Dynamic scene direction injected at a configurable depth from the bottom of history so your top prompt cache stays intact.
* **100% Customizable Theme Studio & Custom `.TTF` / `.OTF` Fonts:** Load any custom `.ttf` or `.otf` font from your machine independently for **Prose / UI** and **Code / Inspector blocks**, edit any hex color, font size, line height, paragraph spacing, or page width, and import/export `.theme.json`.

### 4. `.png` & `.json` Card & System Prompt Protocol
* Pure-Kotlin binary PNG chunk parser and injector (`tEXt` & `iTXt` chunks with CRC32 verification and uncompressed zlib image synthesis).
* Import and export `.png` files with instructions hardcoded inside (`chara`, `ccv3`, `parchment_prompt`) as well as standalone `.json` Card and `.prompt.json` System Prompt files.

### 5. Built-In Pure-API Local File System Agent Harness
* Zero CLI bridge required—connects directly to the native tool-calling loops of Gemini Interactions, OpenAI Responses, and Anthropic Messages.
* Exposes 5 sandboxed tools (`fs_list`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`) with a 3-way top-bar switch (`Workspace: Off` | `Read-Only` | `Agent R/W`).

---

## GitHub Actions Auto-Build (`.apk` & Zero-Trace `.exe` Installer)

Pushing to `main`/`master` or pushing a `v*` tag triggers [`.github/workflows/build-installers.yml`](.github/workflows/build-installers.yml):
1. **`Parchment-Android-Universal.apk`:** Compiled against Android 16 (API 36) and signed for immediate installation. Configured with `android:allowBackup="false"` and `android:hasFragileUserData="false"` so uninstalling removes 100% of local and cloud data.
2. **`Parchment-Windows-x64-Setup.exe`:** Built via `./gradlew :composeApp:createDistributable` + Inno Setup (`installer/windows-installer.iss`). Uninstalling removes the application binary directory (`{app}`) and all user data directories (`%LOCALAPPDATA%\ParchmentAI`, `%APPDATA%\ParchmentAI`), leaving zero trace on Windows.

---

## Local Build Commands

```powershell
# Run Desktop App (Windows / macOS / Linux)
.\gradlew.bat :composeApp:run

# Build Standalone Windows App Image
.\gradlew.bat :composeApp:createDistributable

# Build Signed Android Release APK
.\gradlew.bat :composeApp:assembleRelease

# Run Protocol & Workspace Harness Unit Tests
.\gradlew.bat :composeApp:desktopTest
```
