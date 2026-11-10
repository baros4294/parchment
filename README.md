# Parchment

A native AI chat and agent app for Windows and Android. Bring your own provider and key; Parchment talks to it directly, keeps your chats on your device, and asks before any tool touches your files, the web or your screen.

Built with Kotlin Multiplatform and Compose Foundation: no Electron, no WebView, no Material.

[![Latest Release](installer/release-badge.svg)](https://github.com/baros4294/parchment/releases/latest)

**Download:** [Windows installer (`.exe`)](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Windows-x64-Setup.exe) · [Android (`.apk`)](https://github.com/baros4294/parchment/releases/latest/download/Parchment-Android-Universal.apk)

## What it does

**Chat and writing**
- Messenger bubbles or a single continuous page, with Markdown, code, LaTeX and SVG rendering.
- Attach images, PDFs, audio, video and code by picking, pasting or dropping files.
- Reasoning, tool steps and text stay in the order they happened.
- Branches, versions, rewind, story summaries, author notes and full-history search.
- Character cards (V2/V3, PNG or JSON) and a World Book whose entries trigger on keywords.
- Read aloud, dictation, and `/` commands such as `/goal`, `/compact` and `/continue`.

**Providers**
- OpenAI Responses and Chat Completions, Anthropic Messages, and Gemini Interactions and generateContent, plus any compatible service through a custom provider.
- Models and their limits come from the provider and public registries; no model names are hardcoded.
- Recovers from dropped streams, empty answers and context overflow without losing reasoning or tool results.
- Per-request token and cost records, with an optional spend limit per chat.

**Agent tools**
- File tools in a folder you choose, web search and fetch, a calculator, a todo list and goals.
- Ask-the-user questions, on-demand skills, read-only helper agents and MCP servers, browsable from the official registry.
- Computer control on Windows and phone control on Android, both off by default.
- Every action can require approval. Imported chats never bring tool access with them.

**Everyday reliability**
- Atomic saves with backups, so a crash never leaves a half-written chat.
- Long runs keep going while the app is in the background, within what the system allows.
- In-app updates check size and SHA-256 before installing.

## Status and limits

- **Last full check (2026-11-11):** 385 desktop tests, the Android compile, a signed release APK and the Windows app image all pass.
- **Run on real hardware:** Windows 11 and an Android 17 phone. Covered: chat switching, Stop, keyboard and rotation, background and return, process death, two chats at once, approval cards, and computer and phone control.
- **Not yet checked on real hardware:** real provider round trips with API keys, an in-app update install, Android Shell descendant cleanup, compaction during a phone run, Doze, screen readers, system animation scale, denied notifications, and third-party MCP servers.
- **Practical limits:** operating-system sleep and battery policy can suspend a run, and some Android phones cut the network soon after the app leaves the screen, so a run may pause until you return. Shell has the app's full OS permissions, is not sandboxed, and is outside the web-tool controls. Computer control needs Android's Accessibility permission and covers the main display on Windows.

Open work is tracked in [PARCHMENT_TODO.md](PARCHMENT_TODO.md).

## Build from source

You need JDK 21, the Android SDK (API 36) and Python 3.10 or later.

```powershell
# Run the desktop app
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21"
.\gradlew.bat :composeApp:run --no-daemon

# Run all checks and build both release apps (needs the private Android signer)
. .\scripts\load-private-signer.ps1
python scripts/verify.py final
```

Android releases are signed with a private key; there is no repository key. See [release signing](docs/RELEASE_SIGNING.md) before building a signed APK or updating an older install.

## Documentation

| Read | For |
|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | Principles, setup and how to submit a change |
| [AGENTS.md](AGENTS.md) | The working rules for people and AI agents |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the code is laid out and where to change what |
| [docs/DESIGN.md](docs/DESIGN.md) | The visual design, tokens, icons and wording |
| [PARCHMENT_MEMORY.md](PARCHMENT_MEMORY.md) | Contracts that must not break |
| [docs/FEATURE_COVERAGE.md](docs/FEATURE_COVERAGE.md) | What is implemented, partial or missing |

## Acknowledgements

- [Oh-My-Pi](https://github.com/can1357/oh-my-pi): long-running agent loops and patient retries.
- [Kelivo](https://github.com/chevey339/kelivo): settings structure, task-specific default models and usage accounting.
- [Claude Code](https://docs.anthropic.com/en/docs/agents-and-tools/claude-code/overview) and [OpenAI Codex](https://github.com/openai/codex): goals, todo checklists, compaction, and steering a running reply.
- [iA Writer](https://ia.net/writer) and [Craft](https://www.craft.do/): the reading-first page.
- [SillyTavern](https://github.com/SillyTavern/SillyTavern): character card and lorebook compatibility.
- [LobeHub Icons](https://github.com/lobehub/lobe-icons): AI lab logos.
- [models.dev](https://models.dev/): context window and pricing data.

## License

[MIT](LICENSE).
