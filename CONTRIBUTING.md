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
* **Android SDK** (API 35)

### Build & Test Commands

```powershell
# Windows PowerShell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Run Protocol & Workspace Harness Unit Tests
.\gradlew.bat :composeApp:desktopTest

# Run Desktop App
.\gradlew.bat :composeApp:run

# Build Standalone Windows App Image & Signed Android Release APK
.\gradlew.bat :composeApp:createDistributable :composeApp:assembleRelease
```
