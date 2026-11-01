# Parchment — Minimal Literary & Agent AI Studio

**Parchment** is a native, zero-bloat KISS AI frontend for **Windows 11** and **Android 16** built with **Kotlin Multiplatform + Compose Foundation primitives** (`compose.runtime`, `compose.foundation`, `compose.ui` only—zero Electron/WebView, zero Material 3 or Fluent UI libraries, and zero OS emoji dependencies).

[![Latest Release](installer/release-badge.svg)](https://github.com/baros4294/parchment/releases/latest)

**Direct Downloads:** [**Windows 11 Setup (`.exe`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Windows-x64-Setup.exe) · [**Android 16 Universal (`.apk`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Android-Universal.apk)

---

## Key Features

### 1. Kelivo-Inspired Multi-Subpage UI & Adaptive Scaling
* **Pure Compose Foundation + Custom Vector Icons (`ParchmentIcons.kt`):** Crisp 24x24 Lucide-style Canvas vector icons tinted to your active Sepia, Ivory, or OLED Dark palette.
* **Adaptive Mobile & Desktop Layout (`WindowSizeClass` + `FlowRow`):** Dynamically scales from vertical mobile screens (`<600dp`) to widescreen desktops (`4K`), opening maximized by default on Windows and remembering window bounds.
* **Independent `[ Assistants | Topics ]` Sidebar:** Pure zero-shot by default (zero hardcoded models, prompts, or example assistants). Assistants and System Prompts live independently of chat Topics so deleting a chat never deletes your Assistant.
* **7-Subpage Settings Studio:**
  * **Preferences:** Theme presets, custom hex colors, bundled/custom `.ttf`/`.otf` fonts, granular chat display toggles, Markdown/LaTeX rendering controls, and OnePlus background battery optimization helper.
  * **Providers:** 3-pane master-detail manager with `♡ Test` latency check, model search/fetch, protocol overrides, and a dedicated per-model token & pricing subpage.
  * **Assistant:** Reusable system prompt, author's note, and per-assistant sampling overrides.
  * **Default Model:** Global chat model plus task-specific models for **Title Generation**, **Context Compression**, and **Translation** (each with a *"Follow Global Choice"* toggle).
  * **Workspace, World Book & Cards, and Backup & OTA.**

### 2. 120fps Streaming, Live `Steer` & `Queue`, and Message Minimap
* **120fps Character Pacer:** Smooth frame-paced token rendering over a process-wide supervisor coroutine scope with `18.dp` literary paragraph spacing.
* **Live `⚡ Steer` & `⏳ Queue` While Streaming:** Interrupt and redirect an active generation mid-stream without losing partial progress (`Steer`), or queue follow-up prompts to auto-send as soon as the current reply finishes (`Queue`).
* **Floating Message Navigation Minimap:** 1-tap jump to `Top`, `Prev`, `Tree / Swipes` (interactive turn & swipe-variant map), `Next`, and `Bottom`.
* **Markdown & LaTeX in Reasoning Blocks:** Thinking traces render full Markdown and native LaTeX math (`$$...$$`, `$...$`, `\frac`, matrices), briefly auto-opening while thinking and auto-collapsing when the reply begins.
* **1-Tap Turn Actions:** Plain-text `Copy` (strips Markdown), raw `MD` copy, `↻ Redo`, `→ Continue`, `✎ Edit`, `⑂ Fork`, and non-destructive swipe variants (`← 1/3 →`).

### 3. Four First-Class Provider Protocols & Nullable Parameter Stripping
* **Google Gemini Interactions API (`/v1beta/interactions` & `:streamGenerateContent`):** Native `useStatefulChaining = true` (`previous_interaction_id`) and `useBackgroundLongDecode = true` (`background: true` polling recovery) enabled by default, plus `google_search`, `code_execution`, `url_context`, and multimodal image generation.
* **OpenAI Responses & Image API (`/v1/responses`, `/v1/chat/completions`, `/v1/images/generations`):** Stateful `previous_response_id` chaining, 64-step function calling, and standalone image generation routing.
* **Anthropic Messages API (`/v1/messages`):** Adaptive & budget thinking, cryptographic `signature` verification, Assistant prefill, and automatic ephemeral prompt caching (`cache_control`).
* **ByteDance Seedream / Seedance / Doubao Ark API (`/api/v3/...`):** Smart modality routing across chat/reasoning, Seedream image generation, and Seedance async video/audio tasks.
* **Strict Nullable Parameter Stripping:** Deleting any generation parameter (`Temperature`, `Top-P`, `Max Output Tokens`, `Thinking Budget`) leaves it blank (`null`) and strips the key from outgoing API JSON; `0 = Unlimited` with zero forced clamping.

### 4. Long-Horizon Resilience, Local Agent Harness, & Safe In-App OTA
* **OnePlus / Android 16 Mid-Stream Survival:** Process-wide streaming scope, periodic atomic disk checkpoints (`1.2s`), synchronous `onPause`/`onStop` flushes, and `LongDecodeForegroundService` wake/wifi locks preserve in-flight generations when switching apps.
* **Sandboxed Local Workspace Agent (`WorkspaceHarness.kt`):** Built-in `fs_list`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`, `dice_roll`, `agent_memory`, and opt-in `shell_exec`, plus automatic `.png` / `.json` Character Card (`chara` / `ccv3`) & Lorebook extraction.
* **Zero-Data-Loss In-App OTA Updates:** Downloads and installs Windows `.exe` and Android `.apk` releases directly inside the app while flushing state, keeping a rolling backup (`parchment-state.prev.json`), and recovering state field-by-field across schema updates.

---

## Build & Run Locally

```powershell
# Set JDK 21 & Android SDK paths (Windows PowerShell)
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Run Desktop App
.\gradlew.bat :composeApp:run

# Build Standalone Windows App Image & Signed Android Release APK
.\gradlew.bat :composeApp:desktopTest :composeApp:createDistributable :composeApp:assembleRelease
```
