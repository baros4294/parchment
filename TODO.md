# Parchment TODO

Updated 2026-11-11. AGENTS.md owns workflow; MEMORY holds contracts. Task IDs stay stable. A checked box means implemented and locally verified.

## Current status

- **Source:** main at 2f2f51b; all tracked work is done.
- **Last verified:** 2026-11-11, `verify.py affected` passed (385 tests).
- **Next action:** None open. Add a task under Open tasks before starting new work.

## Execution order

Confirmed critical or high defects always go first. Otherwise small, clear fixes come before long audits.

Nothing open.

## Open tasks

## Sources

Reuse these; consult a changed external contract once.

- **OpenAI:** developers.openai.com/api/reference/resources/responses/methods/create; /api/reference/resources/chat/subresources/completions/methods/create; /api/docs/guides/reasoning; /api/docs/guides/prompt-caching.md; /api/docs/pricing; /codex/agent-approvals-security; /codex/sandboxing
- **Gemini:** aistudio.google.com/api-reference/interactions-api; /docs/interactions-overview; /docs/thinking; ai.google.dev/api/generate-content
- **Anthropic:** platform.claude.com/docs/en/api/messages; /build-with-claude/streaming; /build-with-claude/prompt-caching; /build-with-claude/extended-thinking; /build-with-claude/thinking-tool-workflows; /about-claude/pricing; /agents-and-tools/tool-use/web-search-tool
- **DeepSeek:** api-docs.deepseek.com/guides/thinking_mode
- **Rates:** models.dev/api.json; openrouter.ai/api/v1/models; openrouter.ai/docs/api/reference/overview; vercel.com/docs/ai-gateway/models-and-providers; raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json
- **Windows:** learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate; /nf-winbase-powersetrequest
- **Android:** developer.android.com/develop/background-work/services/fgs/service-types; /fgs/timeout; /training/monitoring-device-state/doze-standby; /tools/adb; /reference/android/system/Os; /develop/ui/views/notifications/notification-permission
- **JVM and processes:** docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html; /java/lang/Process.html; developer.android.com/reference/java/lang/Process; pubs.opengroup.org/onlinepubs/9799919799/functions/kill.html; android.googlesource.com/platform/external/toybox/+/refs/heads/main/toys/other/setsid.c
- **Security:** cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- **GitHub:** docs.github.com/en/rest/actions/secrets; /rest/guides/encrypting-secrets-for-the-rest-api; /rest/releases/assets (asset digest is nullable, observed as `sha256:<hex>`)
- **Build and UI:** kotlinlang.org/docs/gradle-compilation-and-caches.html; /docs/cancellation-and-timeouts.html; /docs/coroutine-context-and-dispatchers.html; docs.gradle.org/8.13/userguide/command_line_interface.html; developer.android.com/develop/ui/compose/state
