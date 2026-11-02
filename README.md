# Parchment

**A native, zero-bloat literary & agent AI frontend for Windows 11 and Android 16.**

Built from the ground up with **Kotlin Multiplatform + Compose Foundation primitives** (`compose.runtime`, `compose.foundation`, `compose.ui` only—zero Electron/WebView, zero Material 3 or Fluent UI libraries, and zero OS emoji dependencies).

[![Latest Release](installer/release-badge.svg)](https://github.com/baros4294/parchment/releases/latest)

**Downloads:** [**Windows 11 Setup (`.exe`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Windows-x64-Setup.exe) · [**Android 16 Universal (`.apk`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Android-Universal.apk)

---

## Highlights

### 1. Zen-by-Default UI & Hardware VSync Physics
* **Pure Compose Foundation + Custom Vector Icons (`ParchmentIcons.kt`):** Crisp 24x24 Canvas vector strokes tinted to your active Sepia, Warm Ivory, or OLED Dark palette.
* **Hardware VSync at Any Refresh Rate (`60Hz–240Hz`):** Frame-paced token streaming (`MonotonicFrameClock` delta-time scaling) and spring-driven micro-interactions (`Modifier.kissClickable`) with desktop hover lift, hand cursors, and native Android haptics.
* **Adaptive Mobile & Desktop Layout (`WindowSizeClass` + `FlowRow`):** Scales cleanly from vertical mobile screens (`<600dp`) with swipe-to-open slide-over drawers to `4K` desktop monitors.
* **Independent `[ Assistants | Chats ]` Architecture:** Pure zero-shot by default (zero hardcoded models, prompts, or example assistants). Assistants and System Prompts persist independently of Chats so clearing or deleting a chat never deletes your Assistant.
* **Clipboard Paste (`Ctrl+V`) & Drag-and-Drop:** Paste screenshots (`Win+Shift+S`), copied images, videos (`.mp4`, `.webm`, `.mov`), PDFs, audio, or code files directly into the composer, or drag-and-drop files onto the window.

### 2. Live `Steer` & `Queue`, Message Minimap, & Native LaTeX
* **Live `⚡ Steer` & `⏳ Queue` While Streaming:** Redirect an active generation mid-stream without losing partial output (`Steer`), or queue follow-up prompts to auto-send when the current reply finishes (`Queue`).
* **Floating Message Navigation Minimap (5s Scroll Linger):** Jump to `Top`, `Prev`, `Message Map` (interactive turn & swipe-variant tree), `Next`, and `Bottom`, with horizontal swipe gestures on multi-variant turns (`← 1/3 →`).
* **Markdown & LaTeX in Reasoning Blocks:** Thinking traces render full Markdown and native LaTeX math (`$$...$$`, `$...$`, `\frac`, matrices), auto-opening while thinking and auto-collapsing when the reply begins.
* **Streamlined Turn Actions & Telemetry:** 1-tap plain-text `Copy`, `↻ Redo`, and expandable `⋯` menu (`MD`, `→ Continue`, `✎ Edit`, `⑂ Branch`, `Mute`, `Wire JSON`, `🗑 Delete`) with `first token (s)` and `tok/s` telemetry.

### 3. Four Bleeding-Edge Provider Protocols & Nullable Parameter Stripping
* **Google Gemini Interactions API (`/v1beta/interactions` & `:streamGenerateContent`):** Stateful `previous_interaction_id` chaining and `background: true` polling enabled by default, plus `google_search`, `code_execution`, `url_context`, and multimodal image/video/audio/PDF input.
* **OpenAI Responses & Image API (`/v1/responses`, `/v1/chat/completions`, `/v1/images/generations`):** Stateful `previous_response_id` chaining, 64-step function calling, and standalone image generation routing.
* **Anthropic Messages API (`/v1/messages`):** Adaptive & budget thinking, cryptographic `signature` verification, Assistant prefill, and automatic ephemeral prompt caching (`cache_control`).
* **ByteDance Seedream / Seedance / Doubao Ark API (`/api/v3/...`):** Smart modality routing across chat/reasoning, Seedream image generation, and Seedance async video/audio tasks.
* **Strict Nullable Parameter Stripping:** Deleting any generation parameter (`Temperature`, `Top-P`, `Max Output Tokens`, `Thinking Budget`) leaves it blank (`null`) and strips the key from outgoing API JSON; `0 = Unlimited` with zero forced clamping.

### 4. Local Agent Harness, Card Codec, & Zero-Data-Loss OTA Updates
* **Sandboxed Local Workspace Agent (`WorkspaceHarness.kt`):** Built-in `fs_list`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`, `dice_roll`, `agent_memory`, and opt-in `shell_exec`.
* **`.png` & `.json` Card / Lorebook Codec (`CardAndPromptCodec.kt`):** Pure-Kotlin PNG `tEXt`/`iTXt` chunk reader/writer (`chara`, `ccv3`, `parchment_prompt`) with automatic `character_book` Lorebook extraction.
* **OnePlus / Android 16 Mid-Stream Survival & Safe In-App OTA:** Process-wide streaming scope, atomic disk checkpoints, `LongDecodeForegroundService` wake locks, and protected user-state backups (`parchment-state.user-backup.json`) across in-app Windows `.exe` and Android `.apk` OTA updates.

---

## Build from Source

```powershell
# Set JDK 21 & Android SDK paths (Windows PowerShell)
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Run Desktop App
.\gradlew.bat :composeApp:run

# Run Tests & Build Release Binaries (Windows App Image + Signed Android APK)
.\gradlew.bat :composeApp:desktopTest :composeApp:createDistributable :composeApp:assembleRelease
```

---

## Acknowledgements & Design Inspirations

Parchment stands on the shoulders of the open-source and design community:
* **[Kelivo](https://github.com/chevey339/kelivo)** — Inspiration for the multi-subpage Settings architecture, independent `[ Assistants | Chats ]` workflow, task-specific Default Models, and provider management ergonomics.
* **[iA Writer](https://ia.net/writer) & [Craft](https://www.craft.do/)** — Inspiration for the warm Sepia & Espresso editorial typography, distraction-free reading canvas, and dialogue/narration ink contrast.
* **[Linear](https://linear.app/) & [Raycast](https://www.raycast.com/)** — Inspiration for keyboard-first command ergonomics, tactile spring-physics hover/press feedback, and high-refresh-rate responsiveness.
* **[SillyTavern](https://github.com/SillyTavern/SillyTavern)** — Inspiration for `.png` Character Card V2/V3 (`chara` / `ccv3`) metadata compatibility, keyword-triggered World Book / Lorebook fragments, and co-writing helpers.
* **[models.dev](https://models.dev/)** — Live open model context-window and token-pricing registry.

---

## License

Released under the [MIT License](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.
