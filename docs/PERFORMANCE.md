# Parchment performance

Parchment is built for low-end hardware first. Measure with `python scripts/verify.py perf`; it builds the desktop package, runs it against 300 synthetic chats in a scratch folder (never your data) and, when a phone is connected, measures the Android app already installed on it (`--android-install` installs the local release APK first).

## Reference devices

- **Low-end Windows:** 2 cores, 4 GB RAM. **Low-end Android:** API 26, 2 cores, 2 GB RAM (emulator profile).
- **Everyday:** the development PC (i5-1135G7, 7.7 GB) and a OnePlus 15T (Android 17), which the user checks by hand.

Under 4 GB of RAM, or with Android's low-RAM flag, the app runs in low-memory mode: smaller image caches and streaming redrawn at about 30 frames a second instead of every frame.

## Budgets

| Measure | Budget | Before (v2.0.70) | Now |
|---|---|---|---|
| Desktop start to chat title, 300 chats | 2.0 s | 1.65 s (3.1 s without class archive) | 1.55 s; 14.4 s once after an update |
| Desktop idle private memory | 250 MB | about 306 MB (dev run) | 289 MB, over budget |
| Android APK | 8 MB | 15.5 MB | 4.2 MB |
| Android cold start | 1.5 s | not measured | needs a phone |
| Android idle PSS | 150 MB | not measured | needs a phone |
| Frame time while streaming, p95 | 16 ms desktop, 33 ms low-end Android | not measured | 7.4 ms desktop (p50 4.2, CPU drawing, `FrameTimeProbeTest`); Android needs a phone |

"Now" is the 2026-11-11 batch 12 measurement on an i5-1135G7 with 7.7 GB. On JDK 25 (batch 16) the same run measures 1.51 s and 268 MB. JDK 25's compact object headers started 1 s slower for 2 MB less memory, so they stay off. With ProGuard-shrunk desktop jars (35.1 MB to 22.2 MB) it measures 1.54 s and 286 MB; after the interface and controller work of 2026-11-11, 1.45 s and 275 MB.

Only change a budget with the user's agreement, and record why.

## What already keeps start-up and memory down

- Before the first frame the app reads only the settings and the open chat; everything else loads afterwards, one chat at a time, and inactive chats are kept as small stubs.
- The packaged desktop app uses class-data sharing: a base archive in the runtime and an app archive written on the first launch after an update.
- Bundled fonts are subset: JetBrains Mono without the Nerd Font icons (0.9 MB instead of 9.4 MB).
- Android release builds are shrunk with R8 and resource shrinking; desktop releases with ProGuard (shrinking only).
- The update check waits a few seconds after start-up.
