# Goal: Execute and lock RAM footprint optimization, instant application startup, accurate tok/s telemetry, and 120fps prefix-cached streaming optimizations.
- [x] (1) Configure lean JVM memory/G1GC page-uncommit flags in composeApp/build.gradle.kts and unify shared HttpClient across network/icon engines
- [x] (2) Eliminate per-frame native Skia Path/Matrix allocations in KissVectorIcon and ModelLabSvgIcon (ParchmentIcons.kt) and optimize ModelsDevClient JSON parsing memory
- [x] (3) Implement fast-path active session startup, on-demand lazy chat hydration, and inactive session dehydration in PlatformBridge, ParchmentStatePersistence.kt, and ParchmentApp.kt
- [x] (4) Fix tok/s telemetry accuracy in AiProviderEngine.kt and ParchmentApp.kt (exclude tool & TTFT latency, accumulate multi-step usage, include reasoning+output tokens, and show live tok/s while streaming)
- [x] (5) Implement incremental prefix-cached parseProseBlocks in ProseRenderer.kt and memoize active lookups / skip no-op pacer frames in ParchmentApp.kt
- [x] (6) Add unit tests in ParchmentProtocolAndHarnessTest.kt, verify with ./gradlew desktopTest, and commit & push (lock) to origin/main