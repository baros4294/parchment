# Workflow coverage

Reviewed 2026-11-08 against the current source and the official references below. “Core” means needed for the requested Parchment workflows; it is a product judgment, not a universal industry checklist. “Implemented” describes local source; final desktop/handset verification is still required before release.

| Workflow / capability | State | Evidence or remaining work |
|---|---|---|
| Agent: assistant/chat permissions, approval cards, revocation | Implemented | Shared policy/editor and run-scoped exact grants; permission/HTTP tests pass. |
| Agent: file read/edit/search/diffs and shell | Implemented / partial | Canonical file boundaries and owned workers; Shell has explicit full process access. Android session cleanup awaits device verification. |
| Agent: parallel function calls | Implemented | Read-only batches run concurrently with ordered results and independent approvals. Mutations run sequentially. |
| Agent: Steer, Queue, Stop and side questions | Implemented | Per-chat queues and `/btw`; stopped/interrupted runs do not automatically dispatch queued prompts. |
| Agent: structured questions and waiting for user input | Missing — core | No `ask_user` tool or question broker. A goal can request a human answer yet continue its work-check loop; add an explicit wait state. |
| Agent: context display, compaction and durable memory | Implemented / partial | Tokenizer breakdown, compaction, lore/notes and workspace memory exist. Verify measured savings and safe recovery across compaction. |
| Agent: run ownership across UI recreation | Implemented / verification in progress | Process-owned state, identities, borrowed frame clocks and draining-worker handoffs. Handset recreation/process-death checks remain. |
| Agent: recovery without repeating uncertain mutations | Partial — core | Atomic chat checkpoints preserve text/tool records. Persist explicit effect outcomes and accepted jobs before automated recovery. |
| Agent: spend/context/effort controls | Partial — core | Model/effort/output/context settings and request ledgers exist; complete utility attribution and optional spend stopping. |
| Agent: external tools/extensions | Missing — extension | Workspace instruction discovery exists; no MCP client/extension registry. MCP support needs its own bounded transport, capability and approval contracts. |
| Daily chat: providers, explicit Custom dialects and model discovery | Implemented / audit pending | Five wires, live/catalog metadata and overrides. Finish capability conflict/unknown-model auditing. |
| Daily chat: conversation search and navigation | Partial — core | Titles and retained message content are searchable; dehydrated history supplies only previews. Full-history search remains incomplete. |
| Daily chat: editing, regeneration, forks and rewind | Implemented | Message variants, topic forks and rewind/clear controls. Verify complete workflows. |
| Daily chat: images, PDFs, audio/video attachments and clipboard | Implemented / audit pending | File/media handling and inline recovery exist; large input, pixel/allocation and archive limits need the I/O gate. |
| Daily chat: Markdown/code/LaTeX/SVG, selection and copy | Implemented / audit pending | Foundation renderers and copy/export controls. Finish pathological-input bounds and accessibility checks. |
| Daily chat: dictation, TTS and voice playback | Implemented / device pending | OS recognition/playback and provider speech paths exist. A full duplex Realtime session implementation is separate. |
| Daily chat: image/video generation | Partial | Metadata/modality dispatch and accepted media jobs exist; finish universal capability and interrupted-job verification. |
| Daily chat: cost transparency | Partial — core | Main/utility/media attempts retain ownership, model rates, free/unknown prices and cache/context tiers. Reported gateway costs replace estimates; matching audio/search/request fees are priced. Unsupported fees and external BYOK billing remain explicitly unpriced. Timing, spend controls and measured efficiency remain. |
| Daily chat: privacy, backup/export/import and OTA | Implemented / audit pending | Stateless defaults, guarded hydration/export and atomic storage; validate archives, staged OTA integrity and cancellation. |
| Daily chat: mobile fit, calm motion and accessibility | Partial — core | Adaptive tabs, vertical policy controls and floating rounded notices are verified locally. Complete wider layout/accessibility and handset cases. |
| Roleplay: character/scenario cards, greetings and variants | Implemented | Card prompts, alternate greetings, examples, notes and generation branches exist. |
| Roleplay: lore, persona/context notes and macros | Implemented / partial | Embedded/global fragments, keyword/pinned activation, user name and prompt macros. Dedicated reusable user-persona profiles are a convenience gap. |
| Roleplay: card format interoperability | Partial — core | Common V2/V3 prompt/lore fields import; export currently emits V2. Preserve V3/unknown fields and assets instead of silently downgrading content. |
| Roleplay: continuity and branching | Implemented / audit pending | Story summary, author notes, variants, forks and rewind; verify preservation through compaction/recovery. |

Confirmed core gaps feed TODO 9.2: structured human questions, full-history retrieval, lossless card interoperability, spend/context controls and safe uncertain-effect recovery. Extend the list only when source/workflow evidence establishes a need. Browser/computer control, group roleplay, full duplex voice and additional extension protocols are separate capabilities, not automatic requirements for every chat.

Official references fetched for this review:

- [Claude Code interactive controls](https://code.claude.com/docs/en/interactive-mode) and [checkpointing limits](https://code.claude.com/docs/en/checkpointing).
- [Gemini CLI Ask User tool](https://geminicli.com/docs/tools/ask-user/): explicit choice/text questions and human input.
- [MCP tools specification](https://modelcontextprotocol.io/specification/latest/server/tools): discovery, schemas and human control; annotations are untrusted.
- [Character Card V3 specification](https://github.com/kwaroran/character-card-spec-v3/blob/main/SPEC_V3.md): required fields, preferred `ccv3` import and asset/CHARX contracts.
- Provider, platform, pricing and build references remain in [TODO](../PARCHMENT_TODO.md); re-read the affected source before implementing a protocol change.
