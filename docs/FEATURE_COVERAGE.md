# Workflow coverage

Reviewed 2026-11-08, updated 2026-11-10, against the current source and the official references below. “Core” means needed for the requested Parchment workflows; it is a product judgment, not a universal industry checklist. “Implemented” describes local source; final desktop/handset verification is still required before release.

| Workflow / capability | State | Evidence or remaining work |
|---|---|---|
| Agent: assistant/chat permissions, approval cards, revocation | Implemented | Shared policy/editor and run-scoped exact grants; permission/HTTP tests pass. |
| Agent: file read/edit/search/diffs and shell | Implemented / partial | Canonical file boundaries and owned workers; Shell has explicit full process access. Android session cleanup awaits device verification. |
| Agent: parallel function calls | Implemented | Read-only batches run concurrently with ordered results and independent approvals. Mutations run sequentially. |
| Agent: Steer, Queue, Stop and side questions | Implemented | Per-chat queues and `/btw`; stopped/interrupted runs do not automatically dispatch queued prompts. |
| Agent: structured questions and waiting for user input | Implemented | The `ask_user` tool pauses the run for a typed or one-tap answer and returns it to the model. Stop or a permission change withdraws it. |
| Agent: context display, compaction and durable memory | Implemented | Context breakdown, automatic shortening and workspace memory. An end-to-end goal run writes a real file, reads it back and stops when complete. Cache savings are measured on a fixture only. |
| Agent: run ownership across UI recreation | Implemented / verification in progress | Process-owned state, identities, borrowed frame clocks and draining-worker handoffs. Handset recreation/process-death checks remain. |
| Agent: recovery without repeating uncertain mutations | Implemented / partial | Atomic checkpoints preserve text and tool records; interrupted turns are recorded and accepted video jobs resume instead of resubmitting. Handset process-death checks remain. |
| Agent: spend/context/effort controls | Implemented | Model/effort/output/context settings, request ledgers and a per-chat spend limit that stops before the next request and keeps history. |
| Agent: external tools/extensions | Implemented | Workspace skills in `.parchment`, `.agents` or `.claude` load on demand. MCP servers are added in Settings; every call follows the approval setting and imports cannot bring local commands. Android supports HTTP servers only. |
| Daily chat: providers, API formats and model discovery | Implemented / keys pending | Five formats with live and catalog model details. Each needs a real round trip with your own key. |
| Daily chat: conversation search and navigation | Implemented | Titles and message text are searched, including chats not loaded in memory, which are scanned in the background. |
| Daily chat: editing, regeneration, forks and rewind | Implemented | Message variants, topic forks and rewind/clear controls. Verify complete workflows. |
| Daily chat: images, PDFs, audio/video attachments and clipboard | Implemented / device pending | Bounded file and media handling with off-thread image decoding. Android pickers need a real run. |
| Daily chat: Markdown/code/LaTeX/SVG, selection and copy | Implemented / device pending | Foundation renderers and copy/export controls. Depth and size bounds pass nested, fuzz and pathological inputs on desktop; the Android run is at 10.3. |
| Daily chat: dictation, TTS and voice playback | Implemented / device pending | OS recognition/playback and provider speech paths exist. A full duplex Realtime session implementation is separate. |
| Daily chat: image/video generation | Implemented / keys pending | Model-aware controls and accepted jobs that resume. Real generation needs your own key. |
| Daily chat: cost transparency | Implemented / keys pending | Per-request ledgers, reported gateway costs, a per-chat spend limit and helper spend. Real provider billing is unmeasured. |
| Daily chat: privacy, backup/export/import and updates | Implemented / device pending | Stateless defaults, atomic storage, safe imports and verified update staging. Real installs are at 10.3. |
| Daily chat: mobile fit, calm motion and accessibility | Implemented / partly device-checked | Adaptive layout, keyboard and rotation pass on an Android 17 phone. A Reduce motion setting passes on desktop. Screen readers and the system animation scale are unchecked on a device. |
| Agent: computer and phone control | Implemented | Off by default, each action asks first, and Stop or denial means nothing runs. Windows covers the main display; Android needs Accessibility turned on. Checked with real actions on Windows and an Android 17 phone. |
| Agent: helpers (subagents) | Implemented | Read-only helpers with their own context, no nesting, counted toward spend. |
| Roleplay: character/scenario cards, greetings and variants | Implemented | Card prompts, alternate greetings, examples, notes and generation branches exist. |
| Roleplay: lore, persona/context notes and macros | Implemented / partial | Embedded/global fragments, keyword/pinned activation, user name and prompt macros. Dedicated reusable user-persona profiles are a convenience gap. |
| Roleplay: card format interoperability | Implemented / partial | V2/V3 import. Export keeps the card's unknown fields, assets and lore settings, and a PNG carries a V2 `chara` and a real V3 `ccv3` chunk. Cards with over 512 KB of data import without that copy. CHARX archives are not supported. |
| Roleplay: continuity and branching | Implemented | Story summary, author notes, versions, branches and rewind. An end-to-end run checks an imported card, its persona and which lore is sent. |

The confirmed core gaps in TODO 9.2 are closed. Extend the list only when source/workflow evidence establishes a need. Browser/computer control, group roleplay, full duplex voice and additional extension protocols are separate capabilities, not automatic requirements for every chat.

Official references fetched for this review:

- [Claude Code interactive controls](https://code.claude.com/docs/en/interactive-mode) and [checkpointing limits](https://code.claude.com/docs/en/checkpointing).
- [Gemini CLI Ask User tool](https://geminicli.com/docs/tools/ask-user/): explicit choice/text questions and human input.
- [MCP tools specification](https://modelcontextprotocol.io/specification/latest/server/tools): discovery, schemas and human control; annotations are untrusted.
- [Character Card V3 specification](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md): required fields, preferred `ccv3` import and asset/CHARX contracts.
- Provider, platform, pricing and build references remain in [TODO](../TODO.md); re-read the affected source before implementing a protocol change.
