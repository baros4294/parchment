# Prioritized Engineering, Protocol Hardening & QA Plan (`PARCHMENT_TODO.md`)

## Priority 1 — Multi-Protocol Spec Compliance & Self-Healing Cascades (`AiProviderEngine.kt`)
- [x] **1.1 OpenAI `/v1/chat/completions` `max_completion_tokens` vs. `max_tokens` Bidirectional Self-Healing**:
  - Send `max_completion_tokens` when reasoning is active (or after HTTP 400 rejection of `max_tokens`), and self-heal bidirectionally between `max_completion_tokens`, `max_tokens`, and omitting the cap when rejected by reasoning models or legacy proxies.
- [x] **1.2 Anthropic `/v1/messages` 3-Stage Bidirectional Thinking Cascade (`adaptive` ↔ `enabled` + `budget_tokens` → `omit`) & Empty-Display Signature Replay**:
  - When `thinkingBudgetTokens >= 1024` is rejected by newer Claude models (`HTTP 400: budget_tokens / enabled not supported`), step over to `thinking: {"type": "adaptive", "display": "summarized"}` before falling back to `"omit"`.
  - When `adaptive` is rejected by older models, step over to `enabled` + `budget_tokens` before `"omit"`.
  - Preserve `thinking` blocks on single-batch replay even when `thinking` text is `""` (omitted display mode) as long as `signature` is valid.
- [x] **1.3 Google Gemini `/v1beta/interactions` Per-Step-Index Signature Isolation & `requires_action` Status Support**:
  - Track `stepSigByIndex: MutableMap<Int, StringBuilder>` per SSE `index` so concurrent or sequential server-side `google_search` steps and `thought` steps never share a signature buffer.
  - Handle `"interaction.requires_action"` SSE events and `"requires_action"` polling status so function calls returned under `requires_action` execute seamlessly.
- [x] **1.4 ByteDance Seedance `/api/v3/contents/generations/tasks` Frame Roles, Terminal States & Usage Telemetry**:
  - Assign official `role` fields (`"first_frame"`, `"last_frame"`, `"reference_image"`) on `image_url` items in `content`.
  - Handle `"expired"` terminal task status and report `usage.completion_tokens` / `usage.total_tokens` on task completion.

## Priority 2 — Automatic On-Load Session Signature Self-Repair & Compaction Hardening (`ParchmentStatePersistence.kt`, `O200kTokenizer.kt`)
- [x] **2.1 Automatic On-Load / On-Save Session Signature Repair (`stripRedundantAttachmentBase64`)**:
  - Automatically detect and repair any historically glued multi-segment Base64 `thinkingSignature` or `ToolCallRecord.thoughtSignature` (`...=...==`) whenever a session is loaded, hydrated, or saved to disk, permanently healing existing corrupted chats and reclaiming megabytes of RAM/disk.

## Priority 3 — Comprehensive Regression Verification & Git Lock (`ParchmentProtocolAndHarnessTest.kt`)
- [x] **3.1 Lock Regression Tests & Verify Desktop + Android Builds**:
  - Add unit tests verifying on-load session signature self-repair, `max_completion_tokens` / `adaptive` cascades, and Seedance/Interactions protocol edge cases.
  - Run `:composeApp:desktopTest` and `:composeApp:compileDebugKotlinAndroid` with `--no-daemon`, update `PARCHMENT_TODO.md` checkboxes, commit, and push to `origin/main`.
