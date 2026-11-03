# Parchment

**A native, zero-bloat literary & agent AI frontend for Windows and Android.**

Built from the ground up with **Kotlin Multiplatform + Compose Foundation primitives** (`compose.runtime`, `compose.foundation`, `compose.ui` only—zero Electron/WebView, zero Material 3 or Fluent UI libraries, and zero OS emoji dependencies).

[![Latest Release](installer/release-badge.svg)](https://github.com/baros4294/parchment/releases/latest)

**Downloads:** [**Windows Setup (`.exe`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Windows-x64-Setup.exe) · [**Android Universal (`.apk`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Android-Universal.apk)

---

## Highlights

### 1. Zen-by-Default UI, Concurrent Multi-Tasking Tabs, & Hardware VSync Physics
* **Pure Compose Foundation + Custom Vector Icons (`ParchmentIcons.kt`):** Crisp 24x24 Canvas vector strokes tinted to your active Sepia, Warm Ivory, or OLED Dark palette.
* **Concurrent Multi-Tasking Tabs (`New Tab = New Chat`):** Run multiple chats and long-horizon agent tasks simultaneously in the background. Features a browser-style horizontal tab bar on Desktop and a Chromium/Firefox-style `[ N ]` tab counter with a 2-column visual card grid switcher on Mobile.
* **Hardware VSync at Any Refresh Rate (`60Hz–240Hz`):** Frame-paced token streaming (`MonotonicFrameClock` delta-time scaling) and spring-driven micro-interactions (`Modifier.kissClickable`) with desktop hover lift, hand cursors, and native Android haptics.
* **Adaptive Mobile & Desktop Layout (`WindowSizeClass` + `FlowRow`):** Scales cleanly from vertical mobile screens (`<600dp`) with swipe-to-open slide-over drawers to `4K` desktop monitors.
* **Independent `[ Assistants | Chats ]` Architecture:** Starts cleanly with a Default Assistant (zero hardcoded models or pre-filled system prompts). Assistants and System Prompts persist independently of Chats so clearing or deleting a chat never deletes your Assistant.
* **Rich Clipboard Paste, Gboard Emoji Kitchen / GIFs, & Drag-and-Drop:** Paste screenshots (`Win+Shift+S`), Gboard Emoji Kitchen stickers, animated GIFs, copied images, videos (`.mp4`, `.webm`, `.mov`), PDFs, audio, or code files directly into the composer (`Ctrl+V` / `Shift+Insert`, 1-tap Paste button, Android IME rich content, or Android Share sheet), or drag-and-drop files onto the Windows app.
* **Built-In Zoomable Image Lightbox, Text/Code Reader, & OS PDF/Video Handoff:** Tap any pasted, attached, or AI-generated image in the composer or chat history to open the built-in zoomable & pannable lightbox (`Pinch-to-Zoom`, `Mouse Wheel Zoom`, `Double-Tap Zoom`, `Save As`, `Open in OS`). Tap attached text/code files to inspect and copy them in the built-in reader modal, or tap attached PDFs and videos to open them directly in your OS's default PDF viewer or media player.

### 2. `omp`-Inspired Autonomous `/goal` Engine, Patient `10 × 60s` Auto-Retry, Live `Steer` & `Queue`, & LaTeX
* **Autonomous `/goal` Engine, Evidence-First Verification Gate, & Circuit Breaker:** Inspired by **[Oh-My-Pi (`omp`)](https://github.com/can1357/oh-my-pi)**, type `/goal <objective>` (or toggle `Goal Mode`) to decompose, execute, and verify multi-step objectives via `todo_write` and `goal_complete` with a live checklist card (`Completed`, `In Progress`, `Pending`), live present-participle tool-intent telemetry (`Reading…`, `Editing…`, `Running shell…`), 3-layer rolling context compaction, and a repeated-failure circuit breaker. Casual chat, roleplay, and short writing finish in a single pass with zero overhead.
* **`omp`-Style Patient `10 × 60s` Auto-Retry & Seamless Mid-Turn Recovery:** Transient rate limits (`429`), Cloudflare/proxy gateway resets (`502`/`503`/`504`/`524`), and overnight Wi-Fi drops automatically retry up to **10 times** (or **120 times** during autonomous `/goal` runs), waiting **60 seconds per attempt** with a live per-second countdown in the status bar (`⏳ Auto-retrying (1/10 in 59s)…`). If a stream is interrupted mid-turn after reasoning or executing tools, Parchment preserves all partial thoughts, cryptographic `thought_signature`s, and completed tool calls—resuming seamlessly without dropping context, merging adjacent user prompts, or re-running completed tools.
* **Native Text-to-Speech (`Speak`) & Live Voice Mode:** Read any AI response aloud with 1-tap `Speak` / `Stop` on every message turn, dictate prompts via native microphone voice input on Windows and Android, or enable `Live Voice` in the Tools tray for hands-free spoken replies.
* **Context Slash Commands (`/btw`, `/compact`, `/rewind`):** Ask side-questions with `/btw <question>` (automatically muted from context after answering), run `/compact` (or `Compact Context`) to summarize older turns into Memory and auto-mute them while keeping the last 4 turns active, or use `/rewind` (`Rewind` in `⋯`) to trim turns in-place.
* **Live `Steer` & `Queue` While Streaming:** Redirect an active generation mid-stream without losing partial output (`Steer`), or queue follow-up prompts to auto-send when the current reply finishes (`Queue`).
* **Floating Message Navigation Minimap (5s Scroll Linger) & Turn Filter:** Jump to `Top`, `Prev`, `Message Map` (interactive turn & swipe-variant tree with instant keyword filter), `Next`, and `Bottom`, with horizontal swipe gestures on multi-variant turns (`← 1/3 →`).
* **Markdown & LaTeX in Reasoning Blocks:** Thinking traces render full Markdown and native LaTeX math (`$$...$$`, `$...$`, `\frac`, matrices), auto-opening while thinking and auto-collapsing when the reply begins.

### 3. Universal Multi-Protocol State Machine, Adaptive Self-Healing, & Nullable Parameter Stripping
* **Google Gemini Interactions API (`/v1beta/interactions` & `:streamGenerateContent`):** Full stateful (`previous_interaction_id`), foreground long-decode (`continuation_token`), `background: true` polling, and stateless `steps` parity (`user_input`, `thought` with `signature` + `summary`, `function_call`, `function_result`, `model_output`), index-keyed SSE step parsing with signature deduplication, June 2026 GA `response_format` image output, automatic fallback to `:streamGenerateContent`, and native `image`/`video`/`audio`/`document` (PDF) routing.
* **OpenAI Responses & Image API (`/v1/responses`, `/v1/chat/completions`, `/v1/images/generations`):** Stateful `previous_response_id` chaining, stateless multi-turn `function_call` + `function_call_output` and `input_file` (PDF) reconstruction, automatic fallback to `/v1/chat/completions` on third-party proxies, and unified Seedream/Seedance media routing.
* **Anthropic Messages API (`/v1/messages`):** Model-agnostic adaptive & budget thinking, cryptographic `signature` and `redacted_thinking` round-tripping across `tool_use` / `tool_result` turns, native PDF `document` blocks, and automatic ephemeral prompt caching (`cache_control`).
* **ByteDance Seedream / Seedance / Doubao Ark API (`/api/v3/...` & `/v1/...`):** Smart modality routing across chat/reasoning, Seedream 1K/2K/4K image generation, and Seedance text-to-video & reference image-to-video tasks.
* **Universal Pre-Token HTTP 400 Self-Healing & Nullable Parameter Stripping:** Deleting any generation parameter (`Temperature`, `Top-P`, `Max Output Tokens`, `Thinking Budget`) leaves it blank (`null`) and strips the key from outgoing API JSON (`0 = Unlimited`). If an upstream model or proxy rejects an optional sampling or thinking parameter with `HTTP 400`, Parchment automatically strips/adapts the rejected field and retries transparently in the same turn.

### 4. Local Agent Harness, Deadlock-Free Shell Execution, Multi-Day Sleep Immunity, & Safe OTA Updates
* **Sandboxed Local Workspace Agent (`WorkspaceHarness.kt`):** Built-in `todo_write`, `goal_complete`, `fs_list`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`, `dice_roll`, `agent_memory`, and opt-in `shell_exec` (with immediate `stdin` EOF closure, concurrent background stdout/stderr pipe draining that never deadlocks on `>64 KB` output, descendant process tree cleanup on timeout, and UTF-16LE `-EncodedCommand` execution on Windows PowerShell so inner quotes are never stripped).
* **Multi-Day Sleep Immunity & Overnight Network Recovery:** Windows `SetThreadExecutionState` keep-awake heartbeat and Android rolling `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY` renewal keep long-horizon tasks alive across screen-off/locked sessions for hours or days.
* **`.png` & `.json` Card / Lorebook Codec (`CardAndPromptCodec.kt`):** Pure-Kotlin PNG `tEXt`/`iTXt` chunk reader/writer (`chara`, `ccv3`, `parchment_prompt`) with automatic `character_book` Lorebook extraction.
* **Background Stream Survival & Safe In-App OTA:** Process-wide streaming scope, atomic disk checkpoints, `LongDecodeForegroundService` wake locks, main-screen OTA download progress bar, and protected user-state backups (`parchment-state.user-backup.json`) across in-app Windows `.exe` and Android `.apk` OTA updates.
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
* **[Oh-My-Pi (`omp`)](https://github.com/can1357/oh-my-pi)** — Inspiration for long-horizon agentic orchestration, patient `10 × 60s` auto-retry resilience, live present-participle tool-intent telemetry, evidence-first verification gates, and lossless multi-turn `thought` + `signature` + `function_call`/`function_result` state reconstruction.
* **[Kelivo](https://github.com/chevey339/kelivo)** — Inspiration for the multi-subpage Settings architecture, independent `[ Assistants | Chats ]` workflow, task-specific Default Models, and provider management ergonomics.
* **[Anthropic Claude Code](https://docs.anthropic.com/en/docs/agents-and-tools/claude-code/overview) & [OpenAI Codex](https://github.com/openai/codex)** — Inspiration for autonomous `/goal` execution, `TodoWrite` checklist verification, stop-hook work-check gates, context compaction (`/compact`, `/btw`, `/rewind`), and live `Steer` / `Queue` controls.
* **[iA Writer](https://ia.net/writer) & [Craft](https://www.craft.do/)** — Inspiration for the warm Sepia & Espresso editorial typography, distraction-free reading canvas, and dialogue/narration ink contrast.
* **[Linear](https://linear.app/) & [Raycast](https://www.raycast.com/)** — Inspiration for keyboard-first command ergonomics, tactile spring-physics hover/press feedback, and high-refresh-rate responsiveness.
* **[SillyTavern](https://github.com/SillyTavern/SillyTavern)** — Inspiration for `.png` Character Card V2/V3 (`chara` / `ccv3`) metadata compatibility, keyword-triggered World Book / Lorebook fragments, and co-writing helpers.
* **[models.dev](https://models.dev/)** — Live open model context-window and token-pricing registry.

---

## License

Released under the [MIT License](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.
