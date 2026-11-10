# Contributing to Parchment

Thank you for your interest in contributing to **Parchment**!

## Core Philosophy (KISS & Zero-Bloat)

1. **Pure Compose Foundation Only:**
   * All UI components live in `composeApp/src/commonMain/kotlin/io/parchment/ai/ui/` and use `compose.runtime`, `compose.foundation`, and `compose.ui` (`BasicText`, `BasicTextField`, `Canvas`, `LazyColumn`, `FlowRow`).
   * Do not introduce Material 3, Fluent UI, WebView, or heavy third-party UI widget frameworks.
2. **Custom Vector Iconography (`ParchmentIcons.kt`):**
   * All icons are rendered as crisp 24x24 Canvas vector strokes tinted to the active `ParchmentPalette`—never rely on OS system emoji fonts.
3. **Zero Hardcoded Models or Prompts:**
   * Never hardcode model names, system prompts, or example assistants.
   * Respect nullable generation parameters (`null = omit key from JSON`, `0 = Unlimited`).
4. **Adaptive Mobile & Desktop Scaling:**
   * Always test layouts across both compact mobile widths (`<600.dp`) and widescreen desktop monitors using `LocalParchmentWindowSize`, `SepiaAdaptiveRow`, and `FlowRow`.

## Local Development Setup

### Prerequisites
* **JDK 21** (Temurin recommended)
* **Android SDK** (API 36)
* **Python 3.10+** (standard library only)

### Build & Test Commands

Follow [AGENTS.md](AGENTS.md), the single workflow for people and every agent tool. `scripts/verify.py` runs from any shell, finds the local JDK and SDK, and remembers what is already verified.

| Need | Command |
|---|---|
| See what to do next and what is verified | `python scripts/verify.py status` |
| After any change | `python scripts/verify.py affected` |
| Docs-only review, no build | `python scripts/verify.py review` |
| Chosen tests while iterating | `python scripts/verify.py test '*SessionSpendTest'` |
| Android Kotlin compile | `python scripts/verify.py android` |
| Final desktop, Android and package gate | `. .\scripts\load-private-signer.ps1`, then `python scripts/verify.py final` |

Add `--dry-run` to see what a build command would run. Failures print compiler errors and failed tests; full logs are in `.parchment-local/verification/`.

`scripts/quality.py` enforces encoding, unused imports, dead code, file-size limits, banned patterns, documentation budgets and tracked secrets. Don't raise a limit in `scripts/quality-baseline.json` to get a pass. Release signing is covered in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).
