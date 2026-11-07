# Goal: Execute & lock v2.0.53: custom reasoning input, CUSTOM protocol enum (stateless/privacy-first, no default dialect), custom media inputs, image clipboard copy, tabs audit + no dim, 14/14 type defaults, Gemini-only chaining toggles, cross-provider reasoning handoff
- [x] (1) Models.kt: CUSTOM enum, customDialect/storeServerSide, useStatefulChaining=false, 14/14 type defaults
- [x] (2) ParchmentStatePersistence.kt: legacy provider JSON migration, one-shot typeface migration, effort presets + normalizeThinkingEffort
- [x] (3) AiProviderEngine.kt: CUSTOM verbatim URL + auth, xhigh passthrough, store=false, model-scoped chain guard, cross-provider reasoning handoff
- [x] (4) ParchmentSettingsPages.kt: Custom dialect levers, Gemini-only chaining toggles, custom reasoning text input, legacy budget relabel
- [x] (5) ParchmentApp.kt: media drawer with Custom... verbs, copy-image action, tab logic fixes
- [x] (6) ParchmentModalsAndNavigation.kt: remove tab-menu dim scrim; ProseRenderer.kt looser bullet spacing
- [x] (7) PlatformBridge copyImageToClipboard expect/actual
- [x] (8) Tests, compile verification, memory update, commit & push
