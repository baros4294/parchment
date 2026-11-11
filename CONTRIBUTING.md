# Contributing to Parchment

Thanks for helping. This page covers the principles, the setup and how to land a change. [AGENTS.md](AGENTS.md) holds the working rules, which apply to people and AI agents alike.

## Principles

1. **Compose Foundation only.** UI uses `compose.runtime`, `compose.foundation` and `compose.ui`. No Material, Fluent, WebView or third-party widget kits.
2. **Our own icons.** Icons are vector paths in `KissIconGlyphs.kt` on a 24-unit grid, never OS emoji. See [docs/DESIGN.md](docs/DESIGN.md).
3. **Nothing hardcoded about models or devices.** No model names, versions or vendor branches; read capabilities from the provider. Blank generation settings are omitted from requests, and `0` means unlimited.
4. **Plain words.** Sentence case and no internal jargon on screen. DESIGN.md has the word list.
5. **Fits every screen.** Check compact phone widths (under 600 dp) and wide desktop windows.
6. **Safe by default.** Tools need explicit permission, imports carry no tool access, and user data is never dropped to save effort.

## Setup

| Tool | Get it | Then |
|---|---|---|
| JDK 25 (Temurin) | [adoptium.net](https://adoptium.net/temurin/releases/?version=25) | Set `JAVA_HOME` to the JDK folder and add its `bin` to `PATH` |
| Android SDK command-line tools | [developer.android.com](https://developer.android.com/studio#command-tools) | Unzip to `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the `Sdk` folder, add `cmdline-tools\latest\bin` and `platform-tools` to `PATH` |
| Android packages | `sdkmanager --licenses`, then `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"` | |
| Python 3.10 or later | [python.org](https://www.python.org/downloads/) | Standard library only |

Gradle and Kotlin need no install: `gradlew.bat` fetches the right versions.

Run the desktop app with `.\gradlew.bat :composeApp:run`. Create an empty `composeApp\parchment-portable-data` folder first and the run keeps its chats and settings there instead of in your real data folder.

## Find your way

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) maps the source sets and packages and says where to change what. Read the matching section of [MEMORY.md](MEMORY.md) before changing storage, protocols or platform code.

## Build and test

`scripts/verify.py` is the only build and test entry point. It uses `JAVA_HOME` and `ANDROID_HOME`, and it remembers what has already passed.

| Need | Command |
|---|---|
| What to do next and what is verified | `python scripts/verify.py status` |
| After a batch of changes | `python scripts/verify.py affected` |
| Docs-only review, no build | `python scripts/verify.py review` |
| Chosen tests while iterating | `python scripts/verify.py test '*SessionSpendTest'` |
| Screenshots of every screen | `python scripts/verify.py test UiGalleryTest` |
| Android Kotlin compile | `python scripts/verify.py android` |
| Start-up, memory and size | `python scripts/verify.py perf` (budgets in [docs/PERFORMANCE.md](docs/PERFORMANCE.md)) |
| Final desktop, Android and package gate | `. .\scripts\load-private-signer.ps1`, then `python scripts/verify.py final` |

Add `--dry-run` to see what a build command would run. Failures print compiler errors and failed tests, and full logs are in `.parchment-local/verification/`.

`scripts/quality.py` enforces encoding, unused imports, dead code, file-size limits, banned patterns, documentation budgets, working links and tracked secrets. Don't raise a limit in `scripts/quality-baseline.json` to get a pass.

## Submitting a change

- Keep a change to one behavior, with a test when it fixes a real failure.
- Run `python scripts/verify.py affected` and make sure it passes.
- Update [TODO.md](TODO.md) if you finish or find work, and MEMORY only for a new lasting rule.
- Describe what changed, how you checked it and what you didn't check.

Release signing is covered in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).
