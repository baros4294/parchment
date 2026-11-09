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

### Build & Test Commands

Use the short [agent workflow](AGENTS.md). Read TODO's current batch first; consult MEMORY's contracts only where relevant. Repository instructions follow the official [AGENTS.md mechanism](https://developers.openai.com/codex/guides/agents-md).

The Windows helper uses the local Temurin 21/Android SDK paths, stable verification version 2.0.64/264, incremental outputs, quiet logs and at most two compiler workers. It restores process environment variables when finished.

| Change/check | Command |
|---|---|
| Docs, copy, simple styling; review a finished diff | `.\scripts\verify.ps1` |
| Focused behavior regressions | `.\scripts\verify.ps1 -Mode Test -Tests '*SessionSpendTest','*UsageChargesTest'` |
| Android/platform changes or shared subsystem checkpoint | `.\scripts\verify.ps1 -Mode Android` |
| Inspect any command without executing it | Add `-Preview` |
| Final desktop tests, Android compile/release and desktop package | Load the private signer, then `.\scripts\verify.ps1 -Mode Final` |

Test mode requires explicit class/method filters. Add a regression when it covers a demonstrated failure mode; use existing coverage where sufficient. Docs/copy/styling changes do not require new unit tests or a Gradle build. Check layout visually when fit/interaction is affected. Review signatures, enum names, callers and fallback paths before the first compile. After a failure, rerun affected checks and retain unaffected passes. Full tests/platform/device verification remain required at TODO 10.3.

Override `-AppVersion` and `-AppVersionCode` together only when deliberately changing the verification build. Release signing uses `. .\scripts\load-private-signer.ps1`; see [signing guidance](docs/RELEASE_SIGNING.md). The final helper covers Gradle tasks; actual desktop/device runtime checks are separate. Do not clean/rebuild outputs or run multiple Gradle processes routinely.
