# Parchment architecture

A map for anyone changing the code. Rules that must not break live in [MEMORY.md](../MEMORY.md); the look and the words live in [DESIGN.md](DESIGN.md).

## Shape

Two Gradle modules. `composeApp` is a Kotlin Multiplatform module holding all the code, for desktop (JVM) and Android; it also packages the Windows app. `androidApp` is a thin shell that builds the APK: application id, version, signing, R8 and the bundled fonts. AGP 9 requires this split. The UI uses Compose Foundation only: no Material, no WebView.

| Source set | Holds |
|---|---|
| `commonMain` | Everything shared: UI, provider protocols, agent tools, data model, storage logic |
| `jvmShared` | JVM file and network code both platforms run: atomic storage, archives, workspace file access, public-web transport |
| `desktopMain` | Windows entry point (`Main.kt`), native title bar, power requests, computer control, local MCP processes |
| `androidMain` | `MainActivity`, the foreground service for long runs, update installs, phone control (Accessibility), file sharing, and the manifest and `res/` that merge into the APK |
| `commonTest`, `desktopTest` | All tests; they run on the desktop JVM through `scripts/verify.py` |

`platform/PlatformBridge.kt` declares what each platform must provide. Keep the platform files thin and put shared JVM logic in `jvmShared`.

## Packages in `commonMain`

| Package | Owns | Start with |
|---|---|---|
| `model` | Chats, messages, variants, tool calls, permissions, usage records | `Models.kt`, `AgentPermissions.kt` |
| `network` | One file per provider wire, plus discovery, usage, media, MCP and helpers | `AiProviderEngine.kt` |
| `harness` | Agent tools, approvals and permission checks | `WorkspaceHarness.kt`, `ToolApprovalBroker.kt` |
| `runtime` | Run timing, leases and whether the app is on screen | `RunActivity.kt`, `AppForeground.kt` |
| `protocol` | Character card codec and the token counter | `CardAndPromptCodec.kt`, `O200kTokenizer.kt` |
| `ui` | Screens, renderers, state, persistence | `ParchmentApp.kt` |

## A chat turn

1. Send calls `beginGeneration` in `ui/ParchmentApp.kt`. The run itself is in `ui/GenerationRun.kt`: it saves the prompt first, then runs in a job owned by the app process, so it survives screen changes.
2. `AiProviderEngine.streamGeneration` resolves the wire format from the provider profile and calls that wire's loop: `OpenAiResponsesProtocol.kt`, `OpenAiChatProtocol.kt`, `AnthropicMessagesProtocol.kt`, `GeminiInteractionsProtocol.kt` or `GeminiGenerateContentProtocol.kt`.
3. Each loop streams text, reasoning and tool calls back as `StreamUpdate`s. Tool calls go through `ProviderToolExecution.kt`, which asks `ToolApprovalBroker` and then runs the tool in `WorkspaceHarness`, an MCP server or computer control.
4. `ProviderUsage.kt` records every request's tokens and cost. `ui/SessionSpend.kt` adds them up, and `streamGeneration` stops before a request once the chat's spend limit is reached.
5. `GenerationRun.kt` applies updates to the chat. `ParchmentDiskWriter.kt` saves the latest version of each changed chat in the background.

Retries, mid-stream recovery and the empty-answer nudge live in `streamGeneration`. Stalls after the app returns from the background are handled in `AiProviderEngine.readSseOrJsonFallback`.

## Storage

Settings are in `parchment-settings.json` with earlier copies in `backups/`, and each chat is in `chats/<id>/chat.json` with its media beside it. On Windows the folder is `%LOCALAPPDATA%\ParchmentAI`. A `parchment-portable-data` folder in the working directory takes precedence. Inactive chats are kept in memory as small stubs and loaded in full when opened (`ParchmentStatePersistence.kt`). Every write is staged and atomically replaced (`jvmShared/platform/AtomicFileStorage.kt`).

## Tools and safety

- Permissions resolve from the assistant's defaults and the chat's overrides in `AgentPermissionResolver`. Imported chats never bring tool access with them.
- Every tool call passes `AgentPermissionGate`, then the approval broker, which issues one-use permits for the exact action shown to the user.
- File tools stay inside the mounted folder (`WorkspacePathGuard`). Web tools reach only public addresses (`PublicWebTransport`). Shell is explicit full access and not sandboxed.
- Computer and phone control are off by default, and every action needs approval.

## Where to change what

| To change | Look in |
|---|---|
| A provider's request or response format | That wire's `network/*Protocol.kt` file |
| How tools are offered or run | `harness/WorkspaceHarness.kt`, `network/ProviderToolExecution.kt` |
| A settings page | `ui/Settings*Page.kt` |
| How messages look | `ui/MessageTurnItem.kt`, `ui/ProseRenderer.kt`, `ui/ParchmentComponents.kt` |
| Colors, type and icons | `ui/ParchmentTheme.kt`, `ui/KissIconGlyphs.kt`, and DESIGN.md first |
| What is saved and how | `ui/ParchmentStatePersistence.kt`, `jvmShared/platform/` |
| Builds and releases | `gradle/libs.versions.toml`, `androidApp/build.gradle.kts` (APK, R8 rules), `.github/workflows/build-installers.yml`, `docs/RELEASE_SIGNING.md` |

## Releases

CI (`.github/workflows/build-installers.yml`) builds and tests both apps on each push to `main` and each pull request, but publishes only for a version tag (or a manual run). Releases come from **release-please**: it keeps a release PR open with the next version and the [CHANGELOG.md](../CHANGELOG.md) entries written from Conventional Commit messages (`feat:`, `fix:`, `perf:`, `deps:`). Merging that PR creates the tag and the GitHub release and starts the build, which attaches the apps, a `SHA256SUMS.txt`, the Android R8 mapping file and a CycloneDX SBOM; the in-app updater reads that release.

Upkeep runs on GitHub's machines: Dependabot opens weekly update PRs, and `dependabot-automerge.yml` merges those whose build passes and whose updates keep their major version (merging doesn't release). CodeQL analyzes the Kotlin code on pull requests and weekly, OpenSSF Scorecard scores the repository's security practices weekly, and `stale.yml` closes issues and PRs after 74 quiet days (bugs, security and pinned items excepted). Actions are pinned by commit.

The Windows installer is not code-signed, so SmartScreen warns on first run. Signing is an option: Azure Artifact Signing (formerly Trusted Signing) costs $9.99 a month for 5,000 signatures (checked 2026-11; identity checks decide who may sign up), and a traditional certificate costs more per year and needs a hardware key. A manual run can rebuild only the Windows installer and reuse an earlier run's APK. Building and testing locally is covered in [CONTRIBUTING.md](../CONTRIBUTING.md#build-and-test).
