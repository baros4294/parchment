# Parchment working rules

These rules bind every contributor, person or agent, in any tool. Nothing may depend on chat history: whatever the next session needs goes in the repository.

## Start

1. Run `python scripts/verify.py status`. It prints pending files, the last verified result and TODO's current status.
2. Do the **Next action**. Read other TODO or MEMORY sections only when the task needs them.
3. Otherwise follow TODO's execution order. Confirmed critical or high defects always go first.
4. New to the code? [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) says where things live; [docs/DESIGN.md](docs/DESIGN.md) owns the look and wording.

## Work in bounded batches

1. Before editing, state in a few lines: the defect, the files, the acceptance criteria and the check.
2. Search first, then read only the defining code and its callers in **every** source set, including recursive and fallback calls. After changing a signature, search for callers again before compiling.
3. Group edits that share a behavior and verification. Finish code and regressions, then review the whole diff once, before the first build.
4. Add a regression only for a real failure mode. When a test could pass without the fix, prove it can fail.
5. Don't grow a batch into a general audit. Log unrelated findings in TODO and continue.
6. After two related failed attempts, stop and diagnose before trying again.

## Verify

- After every batch run `python scripts/verify.py affected`. It runs the quality gates, then all desktop tests and the Android compile if code changed, and skips what is already verified.
- Use `test <filters>` while iterating and `review` for docs-only changes. `final` runs at 10.3 only.
- Failures print compiler errors and failed tests. Open the named log only if that isn't enough.
- Never run Gradle directly or in parallel, and don't clean builds or edit build configuration to get a pass.
- Never weaken a gate, baseline or budget to pass. If a gate is wrong, fix the gate in the same batch and say why.
- Use local HTTP/SSE fixtures. UI tests call `Snapshot.sendApplyNotifications()` before rendering after a state write.

## Finish a batch

A batch is done only when `affected` passes and TODO records it: tick what is verified and update **Source**, **Last verified** and **Next action**. A completed item stays one line. MEMORY takes new stable contracts only. Then continue with the next authorized item.

Report in a few lines: what changed, what was verified, what wasn't. Never claim a check you did not run.

## Spend tokens carefully

- Prefer `status` to reading whole docs. Read line ranges, not whole files, and batch independent reads.
- Don't reread a file you just edited, restate the plan or paste logs. Keep tool output to what you will act on.
- Research a changed external contract once and record the source in TODO.
- Reuse the scripts in `scripts/`. Add a check there rather than repeating it by hand.

## Ask only when

- An action is destructive, public, or needs access you weren't given.
- A decision is genuinely the user's and the code can't settle it.

Otherwise choose the conservative option and say so.

## Boundaries

- Keep work local until the whole checklist and final gate pass, then one commit and push. Phone checks wait for 10.3.
- Preserve user data, permissions, opaque signatures, explicit model choices and the installed Windows app.
- Use Compose Foundation and existing icons. Check MEMORY before changing storage, protocol or platform contracts.
- Keep secrets, keys and personal data out of the repository, logs and requests to outside services.
