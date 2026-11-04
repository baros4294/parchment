# Parchment

**A native, zero-bloat literary & agent AI frontend for Windows and Android.**

Built from the ground up with **Kotlin Multiplatform + Compose Foundation primitives** (`compose.runtime`, `compose.foundation`, `compose.ui` only—zero Electron/WebView, zero Material 3 or Fluent UI libraries, and zero OS emoji dependencies).

[![Latest Release](installer/release-badge.svg)](https://github.com/baros4294/parchment/releases/latest)

**Downloads:** [**Windows Setup (`.exe`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Windows-x64-Setup.exe) · [**Android Universal (`.apk`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Android-Universal.apk)

---

## Highlights

### 1. Zen-by-Default UI, Frameless KISS Window, Official Lab SVG Icons, & Hardware VSync Physics
* **Pure Compose Foundation + Official AI Lab SVG Icons (`ParchmentIcons.kt`):** Crisp 24×24 Canvas vector strokes and official `@lobehub/icons-static-svg` vector logos (`Gemini`, `OpenAI / GPT`, `Claude`, `Anthropic`, `DeepSeek`, `xAI Grok`, `Alibaba Qwen`, `Mistral`, `Meta Llama`, `ByteDance`, `Doubao / Seedream`, `Zhipu GLM / zAI`, `Moonshot Kimi`, `MiniMax`, `Perplexity`, `Cohere`, `OpenRouter`, and `Google`, plus automatic online `.svg` lookup for any novel lab) tinted to your active Sepia, Warm Ivory, Oxford, Candlelight, or Midnight OLED Ink palette.
* **Frameless KISS Desktop Window & Context-Rich Multi-Tasking Tabs:** Custom theme-synced desktop title bar (`WindowDraggableArea`, double-click maximize/restore, 8-edge resize frame, and taskbar-safe maximized work-area bounds), two-line Desktop tab pills displaying each tab's owning Assistant, Model ID, or message count, and a full `⊞ N Tabs` visual card grid overview on both Desktop and Mobile with 1-tap `Close Empty` cleanup.
* **Hardware VSync at Any Refresh Rate (`60Hz–240Hz`):** Frame-paced token streaming (`MonotonicFrameClock` delta-time scaling) and spring-driven micro-interactions (`Modifier.kissClickable`) with desktop hover lift, hand cursors, and native Android haptics.
* **Hierarchical Mobile Settings & 100% Granular Universal Search:** Native-feeling vertical Settings Root Menu → Category Subpage → Provider Detail drill-down on Mobile, paired with an instant item-level cross-category search engine across all 60+ settings.
* **Independent `[ Assistants | Chats ]` Architecture:** Bind each Assistant to its own System Prompt, Author's Note, Default Model, Thinking Effort, Character Card, and Local Workspace folder independently of Chats.
* **Rich Clipboard Paste, Gboard Emoji Kitchen / GIFs, & Drag-and-Drop:** Paste screenshots (`Win+Shift+S`), Gboard Emoji Kitchen stickers, animated GIFs, copied images, videos (`.mp4`, `.webm`, `.mov`), PDFs, audio, or code files directly into the composer, or drag-and-drop files onto the Windows app.
* **Built-In Zoomable Image Lightbox, Text/Code Reader, & OS PDF/Video Handoff:** Tap any pasted, attached, or AI-generated image to open the built-in zoomable & pannable lightbox (`Pinch-to-Zoom`, `Mouse Wheel Zoom`, `Double-Tap Zoom`, `Save As`, `Open in OS`).

### 2. `omp`-Inspired Autonomous `/goal` Engine, Git-Style Green/Red Diffs, & 3-Layer Auto-Compaction
* **Chronological Interleaved Reasoning & Tool Timeline (`Thought #1 → Tool #1 → Thought #2 → Tool #2`):** Renders multi-step agent turns in exact chronological order with contextual vector tool badges and Git/GitHub-style **Green (`+`) & Red (`−`) Unified Line Diff Cards** (`SepiaFileDiffCard`) whenever the AI edits (`fs_edit`), writes (`fs_write`), or outputs ` ```diff ` patches.
* **3-Layer Automatic Context Window Compaction:** Proactively summarizes and mutes older turns into Rolling Continuity Memory when active context reaches $\ge 85\%$ of the model's context window, reactively self-heals `HTTP 400 / 413` `context_length_exceeded` errors mid-flight without failing, and prunes stale tool outputs during long agent loops.
* **Granular Turn, Swipe, & Regeneration Controls:** Regenerate directly from any **User** turn (`↻ Regenerate` or `↻ Save & Regenerate` when editing, automatically clearing stale Assistant replies below it) or **Assistant** turn (`↻ Redo`), translate any turn in-place into your target language (`Translate` → adds a swipeable version branch), and delete individual swipe versions (`🗑 Delete Swipe (2/4)`) without losing other swipes on that turn.
* **`omp`-Style Patient `10 × 60s` Auto-Retry & Seamless Mid-Turn Recovery:** Transient rate limits (`429`), Cloudflare/proxy gateway resets (`502`/`503`/`504`/`524`), and overnight Wi-Fi drops automatically retry with a live countdown while preserving all partial thoughts, cryptographic `thought_signature`s, and completed tool calls.
* **Native Text-to-Speech (`Speak`), Live Voice Mode, & `Steer` / `Queue`:** Read any response aloud, dictate via microphone voice input, redirect active streams mid-flight (`Steer`), or queue follow-up prompts (`Queue`).

### 3. Universal Multi-Protocol State Machine, Primary-First Smart Legacy Fallback, & Nullable Parameter Stripping
* **Primary-First Smart Legacy Protocol Fallback:** Attempts the newer primary API (`/v1beta/interactions` or `/v1/responses`) first; if a proxy or model endpoint rejects the newer protocol or exhausts fast probe retries (`2 × 3s`), Parchment automatically hands off any accumulated thoughts, cryptographic signatures, completed tool calls, and partial prose to `:streamGenerateContent` or `/v1/chat/completions` with zero data loss.
* **Google Gemini Interactions API (`/v1beta/interactions` & `:streamGenerateContent`):** Full stateful (`previous_interaction_id`), foreground long-decode (`continuation_token`), `background: true` polling, and stateless chronological `steps` parity (`user_input`, `thought` with `signature` + `summary`, `function_call`, `function_result`, `model_output`).
* **OpenAI Responses & Image API (`/v1/responses`, `/v1/chat/completions`, `/v1/images/generations`):** Stateful `previous_response_id` chaining, stateless multi-turn `function_call` + `function_call_output` and `input_file` (PDF) reconstruction, and unified Seedream/Seedance media routing.
* **Anthropic Messages API (`/v1/messages`) & ByteDance Seedream / Seedance Ark API:** Model-agnostic adaptive & budget thinking, cryptographic `signature` and `redacted_thinking` round-tripping across `tool_use` / `tool_result` turns, native PDF `document` blocks, ephemeral prompt caching (`cache_control`), and 1K/2K/4K image & video generation.
* **Universal Pre-Token HTTP 400 Self-Healing & Nullable Parameter Stripping:** Leave any generation parameter (`Temperature`, `Top-P`, `Max Output Tokens`, `Thinking Budget`) blank (`null`) to strip it from outgoing JSON (`0 = Unlimited`).

### 4. Local Agent Harness, Deadlock-Free Shell Execution, Multi-Day Sleep Immunity, & Safe OTA Updates
* **Sandboxed Local Workspace Agent (`WorkspaceHarness.kt`):** Built-in `todo_write`, `goal_complete`, `fs_list`, `fs_read`, `fs_search`, `fs_write`, `fs_edit` (with exact multi-match line number diagnostics), `dice_roll`, `agent_memory`, and opt-in `shell_exec` (with immediate `stdin` EOF closure, concurrent background stdout/stderr pipe draining that never deadlocks on `>64 KB` output, and UTF-16LE `-EncodedCommand` execution on Windows PowerShell).
* **`.png` & `.json` Card / Lorebook Codec & Libraries (`CardAndPromptCodec.kt`):** Pure-Kotlin PNG `tEXt`/`iTXt` chunk reader/writer (`chara`, `ccv3`, `parchment_prompt`) with Saved Character Cards Library, System Prompt Presets Library, and `[ This Chat | Global World Book ]` Lorebook management.
* **Multi-Day Sleep Immunity & Safe In-App OTA:** Windows `SetThreadExecutionState` keep-awake heartbeat, Android rolling `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY` renewal, and protected user-state backups across in-app Windows `.exe` and Android `.apk` OTA updates.

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
* **[LobeHub Icons (`@lobehub/icons-static-svg`)](https://github.com/lobehub/lobe-icons)** — Open-source AI / LLM model and lab SVG vector logo collection.
* **[models.dev](https://models.dev/)** — Live open model context-window and token-pricing registry.
---

## License

Released under the [MIT License](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.
