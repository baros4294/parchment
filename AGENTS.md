# Parchment working rules

## Load only the context needed

- Start with the current status/batch at the top of `PARCHMENT_TODO.md` and `git status --short`. Read the relevant MEMORY section and TODO entries on demand. Do not routinely reload full docs, saved conversations or large source files.
- Search with `rg`, then read the defining code and affected callers, including recursive/fallback calls. Batch independent reads; keep output to relevant excerpts. Reuse applicable documentation already fetched; research missing or changed external contracts once.

## Finish a bounded batch

- Before editing, state the defect/outcome, affected files, acceptance criteria and smallest useful check in a few lines. Routine edits need no separate plan document.
- Group edits that share a behavior and verification. Finish implementation, regression fixtures and one complete source/caller/diff review before compiling. Avoid expanding a feature batch into a general audit.
- After checks pass, close the batch and continue the next authorized item. Put unrelated improvements in TODO; reopen passed work only for a demonstrated correctness issue. After two related failed attempts, diagnose and revise the approach before rerunning.

## Verification by impact

- Docs, tracking, copy and simple styling: diff/source review; no Gradle or new unit tests. Render only when layout behavior is at risk.
- Behavior changes: reuse existing coverage; add a focused regression for a real failure mode. Run selected desktop tests once for the finished batch with `scripts/verify.ps1 -Mode Test -Tests ...`.
- Compile Android for Android/platform API changes or at a shared subsystem checkpoint, not after every common-only edit. Release/package/device checks belong to final TODO 10.3 unless the changed behavior specifically requires them earlier.
- After a failure, rerun affected checks. Reuse unaffected passes; documentation edits do not invalidate builds. Full desktop tests and the platform/package/device matrix remain mandatory at the final gate.
- Use local HTTP/SSE fixtures. Preserve incremental outputs and version flags; no routine clean, forced rerun, parallel Gradle processes or configuration experiments. The helper caps workers at two.

## Keep progress and boundaries clear

- Maintain one current batch, its acceptance/check scope, last verified result and next action in TODO. Tick each bullet when verified; update at batch boundaries or a changed blocker, not after every tool call. MEMORY holds stable contracts, not a second progress log.
- Keep existing work local until the entire checklist/final gate passes, then one commit/push. Phone checks wait for 10.3. Preserve data, permissions, opaque signatures, explicit model choices and the installed Windows app.
- Use Compose Foundation and existing icons. Consult MEMORY for storage/protocol/platform contracts before changing them. Report results briefly and distinguish verified behavior from pending device checks.
