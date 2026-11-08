# Parchment

**A native, zero-bloat literary & agent AI frontend for Windows and Android.**

Built from the ground up with **Kotlin Multiplatform + Compose Foundation primitives** (`compose.runtime`, `compose.foundation`, `compose.ui` only—zero Electron/WebView, zero Material 3 or Fluent UI libraries, and zero OS emoji dependencies).

[![Latest Release](installer/release-badge.svg)](https://github.com/baros4294/parchment/releases/latest)

**Downloads:** [**Windows Setup (`.exe`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Windows-x64-Setup.exe) · [**Android Universal (`.apk`)**](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Android-Universal.apk)

---

## Highlights

### 1. Timeless 2020–2026 Editorial UI, "Show, Don't Tell" Iconography, & Hardware VSync Physics
* **Pure Compose Foundation + Official AI Lab & Full-Spec SVG Engine (`ParchmentIcons.kt`, `SvgSceneRenderer.kt`):** Crisp 24×24 Canvas vector strokes, "Show, Don't Tell" icon-first toolbars, official `@lobehub/icons-static-svg` lab logos with automatic online `.svg` lookup, and a full-spec hierarchical W3C SVG 1.1/2.0 scene-graph renderer (`<defs>`, `<linearGradient>`, `<radialGradient>`, `<style>`, `<g transform="...">`, `<path>`, `<circle>`, `<ellipse>`, `<rect>`, `<line>`, `<polyline>`, `<polygon>`) across Sepia, Warm Ivory, Oxford, Candlelight, Midnight OLED, Paper & Graphite, and Linear Carbon themes.
* **Vertical `/` Slash-Command & `@` File/Attachment/Lore Mention Palettes:** Type `/` for a vertical command palette (`/goal`, `/btw`, `/compact`, `/summarize`, `/continue`, `/rewind`, `/clear` with 1-tap instant execution) or `@` to reference and auto-attach mounted workspace files (`@file`), conversation attachments, or World Book lore fragments.
* **Frameless KISS Desktop Window & Context-Rich Multi-Tasking Tabs:** Custom theme-synced desktop title bar (`WindowDraggableArea`, double-click maximize/restore, 8-edge resize frame, and taskbar-safe maximized work-area bounds), two-line Desktop tab pills, and an adaptive Tabs overview. Narrow mobile layouts use full-width cards in a compact vertical list; wider layouts retain the grid. The content-sized sheet preserves the reading surface without dismissal-layer dimming.
* **Hardware VSync at Any Refresh Rate (`60Hz–240Hz`) & Smart Bottom-Edge Auto-Scroll:** Frame-paced token streaming (`MonotonicFrameClock` delta-time scaling) with bottom-edge viewport pinning, instant user-scroll disengage + 5-second cooldown, and a live **Single-Thought Peek** mode when collapsing reasoning mid-stream.
* **Rich Clipboard Paste, Gboard Emoji Kitchen / GIFs, & Drag-and-Drop:** Paste screenshots (`Win+Shift+S`), Gboard Emoji Kitchen stickers, animated GIFs, copied images, videos (`.mp4`, `.webm`, `.mov`), PDFs, audio, or code files directly into the composer, or drag-and-drop files onto the Windows app.

### 2. `omp`-Inspired Autonomous `/goal` Engine, Foolproof Zero-Answer Auto-Nudge, & 3-Layer Auto-Compaction
* **Foolproof 3-Stage Zero-Answer Auto-Continuation Engine:** If an upstream model or proxy finishes its internal reasoning or tool calls and closes the stream with zero final answer prose, Parchment automatically nudges the model across up to 3 progressive stages (preserving all thoughts, cryptographic signatures, and completed tool calls) so you never have to manually click `Continue`.
* **Chronological Interleaved Reasoning & Tool Timeline (`Thought #1 → Tool #1 → Thought #2 → Tool #2`):** Renders multi-step agent turns in exact chronological order with contextual vector tool badges and Git/GitHub-style **Green (`+`) & Red (`−`) Unified Line Diff Cards** (`SepiaFileDiffCard`) whenever the AI edits (`fs_edit`, `fs_multi_edit`), writes (`fs_write`), or outputs ` ```diff ` patches.
* **3-Layer Automatic Context Window Compaction:** Proactively summarizes and mutes older turns into Rolling Continuity Memory when active context reaches $\ge 85\%$ of the model's context window, reactively self-heals `HTTP 400 / 413` `context_length_exceeded` errors mid-flight, and prunes stale tool outputs during long agent loops.
* **Native Text-to-Speech (`🔊`), In-App Voice Dictation (`🎙`), Live Voice Mode, & `Steer` / `Queue`:** Read any response aloud (with Android 11+ `<queries>` and chunked utterance queueing), dictate via in-app `SpeechRecognizer` with system dialog fallback, redirect active streams mid-flight (`Steer`), or queue follow-up prompts (`Queue`).

### 3. Zero-Hardcoded-Models Universal Protocol Engine & Self-Healing Modality Cascade
* **100% Model-Agnostic & Future-Proof (Zero Hardcoded Model Names):** Never hardcodes model IDs or version strings. Automatically discovers capabilities and output modalities (`text`, `image`, `video`, `audio`) from live `/v1/models`, `/v1beta/models`, and `models.dev` metadata, combined with a **bidirectional self-healing endpoint & parameter cascade** that works seamlessly with any future text, reasoning, image, or video model.
* **Primary-First Smart Legacy Protocol Fallback & Cross-Protocol Signature Isolation:** Attempts `/v1beta/interactions` or `/v1/responses` first; preserves parallel and sequential tool-batch boundaries across all 5 protocols, automatically sanitizes multi-segment `thoughtSignature`s (filtering server-side `08 02` tool signatures), isolates provider signatures across model switches, and hands off accumulated state to `:streamGenerateContent` or `/v1/chat/completions` with zero data loss.
* **Universal Pre-Token HTTP 400 Self-Healing & Nullable Parameter Stripping:** Leave any generation parameter (`Temperature`, `Top-P`, `Max Output Tokens`, `Thinking Budget`) blank (`null`) to strip it from outgoing JSON (`0 = Unlimited`); rejected sampling, thinking levels, mixed-tool modes, or historical tool/signature contexts automatically self-heal and retry.

### 4. 2026 Local Agent Harness, Deadlock-Free Shell Execution, Long-Horizon Execution, & Safe OTA Updates
* **Atomic Desktop Storage:** Settings, chat files, and backups are staged and flushed before atomic replacement. Failed replacement preserves the prior file and recoverable staged content, including temporary Windows reader-lock conflicts.
* **Sandboxed Local Workspace & Utility Agent (`WorkspaceHarness.kt`):** Built-in `todo_write`, `goal_complete`, `fs_list`, `fs_glob`, `fs_read`, `fs_search`, `fs_write`, `fs_edit`, `fs_multi_edit` (atomic multi-hunk batch edits with rollback), `fs_move`, `fs_delete`, `web_fetch` (universal URL reader & HTML-to-Markdown extractor), `calc_eval` (deterministic scientific math evaluator), `dice_roll`, `agent_memory`, and opt-in `shell_exec`.
* **`.png` & `.json` Card / Lorebook Codec & Libraries (`CardAndPromptCodec.kt`):** Pure-Kotlin PNG `tEXt`/`iTXt` chunk reader/writer (`chara`, `ccv3`, `parchment_prompt`) with Saved Character Cards Library, System Prompt Presets Library, and `[ This Chat | Global World Book ]` Lorebook management.
* **Managed Power Requests & Safe In-App OTA:** Windows validates native keep-awake heartbeats, shares ownership across concurrent generations, and recovers failed helpers independently of token progress. Android provides foreground-service and CPU wake-lock support. Operating-system sleep and battery policies can still interrupt local execution. User-state backups are preserved across in-app Windows `.exe` and Android `.apk` OTA updates.
## Build from Source

```powershell
# Set JDK 21 & Android SDK paths (Windows PowerShell)
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Run Desktop App
.\gradlew.bat :composeApp:run --no-daemon

# Run Tests & Build Release Binaries (Windows App Image + Signed Android APK)
.\gradlew.bat :composeApp:desktopTest :composeApp:createDistributable :composeApp:assembleRelease --no-daemon
```

Release builds accept `-PappVersion`, `-PappVersionCode`, and `-PgithubRepo`. Changes to these properties regenerate the runtime version and OTA configuration; unchanged values remain eligible for incremental builds.

---

## Acknowledgements & Design Inspirations

Parchment stands on the shoulders of the open-source and design community:
* **[Oh-My-Pi (`omp`)](https://github.com/can1357/oh-my-pi)** — Inspiration for long-horizon agentic orchestration, patient `10 × 60s` auto-retry resilience, live present-participle tool-intent telemetry, evidence-first verification gates, and lossless multi-turn `thought` + `signature` + `function_call`/`function_result` state reconstruction.
* **[Kelivo](https://github.com/chevey339/kelivo)** — Inspiration for the multi-subpage Settings architecture, independent `[ Assistants | Chats ]` workflow, task-specific Default Models, wall-clock token throughput (`tok/s`) & per-request token usage accounting, and provider management ergonomics.
* **[Anthropic Claude Code](https://docs.anthropic.com/en/docs/agents-and-tools/claude-code/overview) & [OpenAI Codex](https://github.com/openai/codex)** — Inspiration for autonomous `/goal` execution, `TodoWrite` checklist verification, stop-hook work-check gates, context compaction (`/compact`, `/btw`, `/rewind`), and live `Steer` / `Queue` controls.
* **[iA Writer](https://ia.net/writer) & [Craft](https://www.craft.do/)** — Inspiration for the warm Sepia & Espresso editorial typography, distraction-free reading canvas, and dialogue/narration ink contrast.
* **[Linear](https://linear.app/) & [Raycast](https://www.raycast.com/)** — Inspiration for keyboard-first command ergonomics, tactile spring-physics hover/press feedback, and high-refresh-rate responsiveness.
* **[SillyTavern](https://github.com/SillyTavern/SillyTavern)** — Inspiration for `.png` Character Card V2/V3 (`chara` / `ccv3`) metadata compatibility, keyword-triggered World Book / Lorebook fragments, and co-writing helpers.
* **[LobeHub Icons (`@lobehub/icons-static-svg`)](https://github.com/lobehub/lobe-icons)** — Open-source AI / LLM model and lab SVG vector logo collection.
* **[models.dev](https://models.dev/)** — Live open model context-window and token-pricing registry.
---

## License

Released under the [MIT License](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.
