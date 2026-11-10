#!/usr/bin/env python3
"""Regression tests for the workflow tools themselves."""

from __future__ import annotations

import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import quality  # noqa: E402
import verify  # noqa: E402

MAIN = "composeApp/src/commonMain/kotlin/io/parchment/ai/ui/Sample.kt"
TEST = "composeApp/src/commonTest/kotlin/io/parchment/ai/ui/SampleTest.kt"
GOOD_TODO = "# T\n\n## Current status\n\n- **Source:** a\n- **Last verified:** b\n- **Next action:** c\n\n## Open\n\n- [ ] **1.1 Task:** x\n"
GOOD_DOCS = {"AGENTS.md": b"use scripts/verify.py", "PARCHMENT_TODO.md": GOOD_TODO.encode(), "PARCHMENT_MEMORY.md": b"m"}


def rules(problems: list[quality.Problem]) -> list[str]:
    return [problem.rule for problem in problems]


def check(files: dict[str, str | bytes], baseline: dict | None = None) -> list[quality.Problem]:
    encoded = {path: value.encode() if isinstance(value, str) else value for path, value in files.items()}
    return quality.check_files({**GOOD_DOCS, **encoded}, baseline or {})


class EncodingRules(unittest.TestCase):
    def test_clean_repository_passes(self):
        self.assertEqual([], check({"README.md": "fine\n"}))

    def test_byte_order_mark_and_crlf_are_rejected(self):
        self.assertEqual(["encoding"], rules(check({"a.md": b"\xef\xbb\xbfhello\n"})))
        self.assertEqual(["encoding"], rules(check({"a.md": b"one\r\ntwo\r\n"})))

    def test_batch_files_must_stay_crlf(self):
        self.assertEqual([], check({"run.bat": b"one\r\ntwo\r\n"}))
        self.assertEqual(["encoding"], rules(check({"run.bat": b"one\ntwo\n"})))

    def test_powershell_must_be_ascii(self):
        self.assertEqual(["encoding"], rules(check({"a.ps1": "caf\u00e9\n"})))

    def test_binary_files_are_skipped(self):
        self.assertEqual([], check({"font.ttf": b"\x00\xff\xef\r\n"}))


class KotlinRules(unittest.TestCase):
    def test_unused_import_is_reported_and_used_import_is_not(self):
        source = "package a\n\nimport a.b.Used\nimport a.b.Unused\n\nfun f(): Used = g()\nfun g(): Used = f()\n"
        problems = [p for p in check({MAIN: source}) if p.rule == "unused-import"]
        self.assertEqual(1, len(problems))
        self.assertIn("a.b.Unused", problems[0].message)

    def test_delegate_operators_are_not_reported(self):
        source = "package a\n\nimport androidx.compose.runtime.getValue\n\nval x by y\nfun z() = x\n"
        self.assertNotIn("unused-import", rules(check({MAIN: source})))

    def test_mime_string_cannot_hide_code_from_the_scan(self):
        source = 'package a\n\nval mime = "*/*"\nfun used() = mime\nfun caller() = used()\nfun dead() = 1\nval other = "*/*"\nfun keep() = caller() + other\nfun alsoKeep() = keep()\nfun root() = alsoKeep()\n'
        dead = [p.message for p in check({MAIN: source}) if p.rule == "dead-code"]
        self.assertTrue(any("dead is never used" in m for m in dead), dead)

    def test_test_only_code_needs_a_declared_seam(self):
        source = "package a\n\nfun seam() = 1\n"
        test = "package a\n\nfun t() = seam()\n"
        self.assertIn("dead-code", rules(check({MAIN: source, TEST: test})))
        self.assertNotIn("dead-code", rules(check({MAIN: source, TEST: test}, {"testOnly": ["seam"]})))

    def test_overrides_are_never_dead(self):
        problems = check({MAIN: "package a\n\nclass A : B {\n    override fun hook() = 1\n}\n"})
        self.assertFalse(any("hook" in problem.message for problem in problems), problems)

    def test_file_size_is_a_ratchet(self):
        long = "package a\n" + "// x\n" * (quality.MAX_KOTLIN_LINES + 1)
        self.assertIn("file-size", rules(check({MAIN: long})))
        self.assertNotIn("file-size", rules(check({MAIN: long}, {"longFiles": {MAIN: quality.MAX_KOTLIN_LINES + 5}})))
        self.assertIn("file-size", rules(check({MAIN: long}, {"longFiles": {MAIN: 10}})))

    def test_banned_patterns_apply_to_app_code_only(self):
        source = 'package a\n\nfun f() { println("x") }\nfun g() = f()\nfun h() = g()\n'
        self.assertIn("banned", rules(check({MAIN: source})))
        self.assertNotIn("banned", rules(check({TEST: source})))

    def test_turns_must_be_stamped_with_the_resolved_wire(self):
        source = "package a\n\nval a = b(providerProtocol = profile.protocol)\nfun c() = a\nfun d() = c()\n"
        self.assertIn("banned", rules(check({MAIN: source})))

    def test_banned_words_in_comments_are_allowed(self):
        self.assertNotIn("banned", rules(check({MAIN: "package a\n\n// FIXME later println(x)\nval a = 1\nfun b() = a\nfun c() = b()\n"})))


class DocumentRules(unittest.TestCase):
    def test_budgets_are_enforced(self):
        problems = quality.check_docs({**GOOD_DOCS, "AGENTS.md": b"scripts/verify.py" + b"x" * 9000})
        self.assertEqual(["docs"], rules(problems))

    def test_status_fields_are_required(self):
        todo = GOOD_TODO.replace("- **Next action:** c\n", "")
        self.assertEqual(["docs"], rules(quality.check_docs({**GOOD_DOCS, "PARCHMENT_TODO.md": todo.encode()})))

    def test_duplicate_task_ids_are_rejected(self):
        todo = GOOD_TODO + "- [x] **1.1 Again:** y\n"
        self.assertEqual(["docs"], rules(quality.check_docs({**GOOD_DOCS, "PARCHMENT_TODO.md": todo.encode()})))

    def test_status_block_stops_at_the_next_section(self):
        self.assertNotIn("Open", quality.status_block(GOOD_TODO))


class SecretRules(unittest.TestCase):
    def test_signing_keys_cannot_be_tracked(self):
        self.assertEqual(["secrets", "secrets"], rules(quality.check_secrets(["keystore/app.jks", "local.properties", "src/a.kt"])))


class BuildDecision(unittest.TestCase):
    def test_docs_and_tooling_need_no_build(self):
        self.assertFalse(verify.needs_build(["README.md", "docs/x.md", "PARCHMENT_TODO.md", "scripts/verify.py", ".github/workflows/a.yml"]))

    def test_any_app_source_or_build_input_runs_the_full_check(self):
        for path in (MAIN, TEST, "composeApp/build.gradle.kts", "composeApp/src/androidMain/AndroidManifest.xml", "gradle/libs.versions.toml", "gradle.properties"):
            self.assertTrue(verify.needs_build([path]), path)

    def test_next_check_names_the_work(self):
        self.assertEqual("nothing to check", verify.next_check([]))
        self.assertEqual("quality gates only", verify.next_check(["README.md"]))
        self.assertIn("Android", verify.next_check([MAIN]))

    def test_changed_paths_cover_edits_additions_and_deletions(self):
        self.assertEqual(["added", "deleted", "edited"], verify.changed_paths(
            {"edited": "2", "added": "1", "same": "1"}, {"edited": "1", "deleted": "1", "same": "1"}))


class FailureReporting(unittest.TestCase):
    def test_compile_errors_are_extracted_with_short_paths(self):
        log = "w: warning\ne: file:///C:/Users/me/parchment/composeApp/src/commonMain/kotlin/A.kt:3:1 Unresolved reference 'x'.\n"
        self.assertEqual(["commonMain/kotlin/A.kt:3:1 Unresolved reference 'x'."], verify.compile_errors(log))

    def test_a_recovered_incremental_build_is_not_a_compile_error(self):
        self.assertEqual([], verify.compile_errors("e: Incremental compilation failed: The following FqNames...\n"))

    def test_gradle_failure_reason_is_extracted(self):
        log = "noise\n* What went wrong:\nNo tests found for given includes.\n\n* Try:\n> Run with --stacktrace\n"
        self.assertEqual(["No tests found for given includes."], verify.gradle_message(log))

    def test_failed_tests_are_read_from_results_written_by_this_run(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "TEST-a.xml"
            path.write_text('<testsuite tests="2"><testcase classname="io.parchment.ai.ui.A" name="ok"/><testcase classname="io.parchment.ai.ui.A" name="bad[desktop]"><failure message="expected 1">trace</failure></testcase></testsuite>', encoding="utf-8")
            total, failed = verify.read_test_results(Path(folder), time.time() - 5)
            self.assertEqual(2, total)
            self.assertEqual(["ui.A.bad: expected 1"], failed)
            self.assertEqual((0, []), verify.read_test_results(Path(folder), time.time() + 60))

    def test_a_clean_exit_with_failures_is_still_a_failure(self):
        outcome = verify.Outcome(0, 1.0, [], 2, ["a"], [], Path("x"))
        self.assertFalse(outcome.passed)

    def test_ledger_survives_a_missing_file(self):
        original = verify.LEDGER_PATH
        verify.LEDGER_PATH = Path(tempfile.gettempdir()) / "parchment-no-such-ledger.json"
        try:
            self.assertEqual({}, verify.load_ledger())
        finally:
            verify.LEDGER_PATH = original


if __name__ == "__main__":
    unittest.main(verbosity=0)
