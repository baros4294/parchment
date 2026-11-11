#!/usr/bin/env python3
"""The one way to check Parchment, from any shell or tool. Standard library only.

    python scripts/verify.py status              what to do next and what is already verified
    python scripts/verify.py review              quality gates only, no build
    python scripts/verify.py affected            gates, then every test and the Android compile if code changed
    python scripts/verify.py test <filters...>   chosen desktop tests while iterating
    python scripts/verify.py android             Android Kotlin compile
    python scripts/verify.py final               full desktop, Android and package gate
    python scripts/verify.py perf                build the package, then measure start-up, memory and size

Results are remembered between sessions, so finished work is never checked twice.
Add --dry-run to any build command to see what would run.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import perf  # noqa: E402
import quality  # noqa: E402

REPO = quality.REPO
STATE_DIR = REPO / ".parchment-local" / "verification"
LEDGER_PATH = STATE_DIR / "ledger.json"
LOCK_PATH = STATE_DIR / "run.lock"
TEST_RESULTS = REPO / "composeApp" / "build" / "test-results" / "desktopTest"
TOOL_TESTS = REPO / "scripts" / "test_workflow_tools.py"

APP_VERSION = "2.1.0"
APP_VERSION_CODE = "264"
DESKTOP_TEST = ":composeApp:desktopTest"
# The shared module's Android code compiles in :composeApp; :androidApp only packages the APK (AGP 9).
ANDROID_COMPILE = ":composeApp:compileAndroidMain"
# The release APK build compiles all Android code, so a separate compile here would only repeat it.
FINAL_TASKS = (DESKTOP_TEST, ":androidApp:assembleRelease", ":composeApp:createReleaseDistributable")
SIGNER_VARIABLES = ("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS")
MAX_COMPILE_ERRORS = 25
MAX_FAILED_TESTS = 20
MAX_LINE = 260

PACKAGE = "io.parchment.ai"
CODE_PATHS = ("composeApp/", "androidApp/", "gradle/")
CODE_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")
# Every code change runs all desktop tests. Compiling Android repeats the shared code's compile, so affected adds it
# only when a change can reach Android: its sources, shared JVM code, the platform expect declarations or the build.
ANDROID_PATHS = ("composeApp/src/androidMain/", "composeApp/src/jvmShared/", "composeApp/src/commonMain/kotlin/io/parchment/ai/platform/",
                 "gradle/", "composeApp/build.gradle.kts", "androidApp/")


@dataclass
class Outcome:
    exit_code: int
    seconds: float
    compile_errors: list[str]
    total: int
    failed: list[str]
    gradle_message: list[str]
    log: Path

    @property
    def passed(self) -> bool:
        return self.exit_code == 0 and not self.failed and not self.compile_errors


def needs_build(changed: Iterable[str]) -> bool:
    return any(path.startswith(CODE_PATHS) or path in CODE_FILES for path in changed)


def needs_android(changed: Iterable[str]) -> bool:
    """A shared UI or logic change compiles for desktop only; final and CI still compile Android every time."""
    return any(path.startswith(ANDROID_PATHS) or path in CODE_FILES for path in changed)


def code_check(changed: Iterable[str]) -> list[str]:
    return [DESKTOP_TEST, ANDROID_COMPILE] if needs_android(changed) else [DESKTOP_TEST]


def next_check(changed: list[str]) -> str:
    if not changed:
        return "nothing to check"
    if not needs_build(changed):
        return "quality gates only"
    return "all desktop tests and the Android compile" if needs_android(changed) else "all desktop tests"


def changed_paths(current: dict[str, str], baseline: dict[str, str]) -> list[str]:
    return sorted(path for path in set(current) | set(baseline) if current.get(path) != baseline.get(path))


def fingerprint() -> dict[str, str]:
    return {path: hashlib.sha1((REPO / path).read_bytes()).hexdigest() for path in quality.repo_paths()}


def load_ledger() -> dict:
    with contextlib.suppress(FileNotFoundError, ValueError):
        return json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
    return {}


def save_ledger(ledger: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    temporary = LEDGER_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps(ledger, indent=0, sort_keys=True), encoding="utf-8")
    os.replace(temporary, LEDGER_PATH)


def record(ledger: dict, command: str, summary: str, passed: bool) -> None:
    ledger["last"] = {"command": command, "passed": passed, "summary": summary, "finished": time.strftime("%Y-%m-%d %H:%M")}
    save_ledger(ledger)


@contextlib.contextmanager
def single_run() -> Iterator[None]:
    """Gradle must never run twice at once; the OS releases this lock even if the run is killed."""
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    with open(LOCK_PATH, "a+b") as handle:
        try:
            if os.name == "nt":
                import msvcrt
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            print("BLOCKED: another verification is already running. Wait for it to finish.")
            sys.exit(2)
        yield


def variable_candidates(name: str) -> list[str]:
    """The process value, then the user's and machine's saved Windows values: a shell opened before
    JAVA_HOME or ANDROID_HOME changed still carries the old, possibly deleted, value."""
    values = [os.environ.get(name, "")]
    if os.name == "nt":
        import winreg
        for root, key in ((winreg.HKEY_CURRENT_USER, "Environment"),
                          (winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment")):
            with contextlib.suppress(OSError), winreg.OpenKey(root, key) as handle:
                values.append(str(winreg.QueryValueEx(handle, name)[0]))
    return [v for v in values if v]


def gradle_environment() -> dict[str, str]:
    environment = dict(os.environ)
    jdk = next((Path(v) for v in variable_candidates("JAVA_HOME") if (Path(v) / "bin" / "java.exe").is_file()
                or (Path(v) / "bin" / "java").is_file()), None)
    if jdk:
        environment["JAVA_HOME"] = str(jdk)
        environment["PATH"] = str(jdk / "bin") + os.pathsep + environment.get("PATH", "")
    default_sdk = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
    sdk = next((Path(v) for v in [*variable_candidates("ANDROID_HOME"), default_sdk] if Path(v).is_dir()), None)
    if sdk:
        environment["ANDROID_HOME"] = environment["ANDROID_SDK_ROOT"] = str(sdk)
    return environment


def gradle_flags(version: str, code: str, configuration_cache: bool = True, gallery: bool = False) -> list[str]:
    # The configuration cache halves each call's fixed cost (13.6 s to 7.0 s, measured 2026-11-11). final keeps it off:
    # validatePrivateReleaseSigner is not cache-compatible, and release signing is not worth risking for speed.
    cache = ["--configuration-cache"] if configuration_cache else []
    # Rendering the UI gallery was 78 of 180 test seconds, so only final and `test UiGalleryTest` render it.
    render = ["-Pparchment.gallery=true"] if gallery else []
    return ["--no-daemon", "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process", *cache, *render,
            f"-PappVersion={version}", f"-PappVersionCode={code}", "--console=plain", "--quiet"]


def shorten(text: str, limit: int = MAX_LINE) -> str:
    return text if len(text) <= limit else text[:limit - 3] + "..."


def compile_errors(log_text: str) -> list[str]:
    errors = []
    for line in log_text.splitlines():
        # Kotlin logs this when it falls back to a full rebuild, which then succeeds.
        if line.startswith("e: ") and not line.startswith("e: Incremental compilation failed"):
            errors.append(shorten(re.sub(r"file:///.*?/composeApp/src/", "", line[3:])))
    return errors


def gradle_message(log_text: str) -> list[str]:
    match = re.search(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\Z)", log_text, re.S)
    lines = (match.group(1) if match else log_text).strip().splitlines()
    return [shorten(line) for line in (lines[:12] if match else lines[-12:])]


def read_test_results(folder: Path, since: float) -> tuple[int, list[str]]:
    total, failed = 0, []
    if not folder.is_dir():
        return 0, []
    for path in folder.glob("TEST-*.xml"):
        if path.stat().st_mtime < since:
            continue
        suite = ElementTree.parse(path).getroot()
        total += int(suite.get("tests", 0))
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            problem = problem if problem is not None else case.find("error")
            if problem is not None:
                short_class = case.get("classname", "").removeprefix(f"{PACKAGE}.")
                name = case.get("name", "").removesuffix("[desktop]")
                detail = (problem.get("message") or problem.text or "").strip().split("\n", 1)[0]
                failed.append(shorten(f"{short_class}.{name}: {detail}"))
    return total, failed


def run_gradle(name: str, tasks: list[str], version: str, code: str, configuration_cache: bool = True, gallery: bool = False) -> Outcome:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    wrapper = str(REPO / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    log = STATE_DIR / f"{name}.log"
    started = time.time()
    with open(log, "w", encoding="utf-8", errors="replace") as output:
        exit_code = subprocess.run([wrapper, *tasks, *gradle_flags(version, code, configuration_cache, gallery)], cwd=REPO, env=gradle_environment(),
                                   stdout=output, stderr=subprocess.STDOUT, shell=False).returncode
    log_text = log.read_text(encoding="utf-8", errors="replace")
    total, failed = read_test_results(TEST_RESULTS, started - 1)
    return Outcome(exit_code, time.time() - started, compile_errors(log_text), total, failed, gradle_message(log_text), log)


def duration(seconds: float) -> str:
    minutes, rest = divmod(int(seconds), 60)
    return f"{minutes}m{rest:02d}s" if minutes else f"{rest}s"


def report(name: str, outcome: Outcome) -> str:
    """Prints only what the reader must act on and returns a one-line summary."""
    if outcome.passed:
        tested = f"{outcome.total} tests passed" if outcome.total else "up to date"
        summary = f"{tested}, {duration(outcome.seconds)}"
        print(f"PASS {name}: {summary}")
        return summary
    summary = f"exit {outcome.exit_code}, {len(outcome.compile_errors)} compile error(s), {len(outcome.failed)} of {outcome.total} tests failed, {duration(outcome.seconds)}"
    print(f"FAIL {name}: {summary}")
    if outcome.compile_errors:
        print("Compile errors:")
        print("\n".join("  " + line for line in outcome.compile_errors[:MAX_COMPILE_ERRORS]))
        if len(outcome.compile_errors) > MAX_COMPILE_ERRORS:
            print(f"  {len(outcome.compile_errors) - MAX_COMPILE_ERRORS} more in the log")
    if outcome.failed:
        print("Failed tests:")
        print("\n".join("  " + line for line in outcome.failed[:MAX_FAILED_TESTS]))
        if len(outcome.failed) > MAX_FAILED_TESTS:
            print(f"  {len(outcome.failed) - MAX_FAILED_TESTS} more in the log")
    if not outcome.compile_errors and not outcome.failed:
        print("\n".join("  " + line for line in outcome.gradle_message))
    print(f"Log: {outcome.log.relative_to(REPO).as_posix()}")
    return summary


def review() -> bool:
    problems = quality.check_files(quality.load_files(), quality.load_baseline())
    whitespace = subprocess.run(["git", "-c", "core.safecrlf=false", "diff", "--check"], cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace")
    tools = subprocess.run([sys.executable, "-I", "-X", "utf8", str(TOOL_TESTS)], cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace")
    passed = not problems and whitespace.returncode == 0 and tools.returncode == 0
    if passed:
        print("PASS review")
        return True
    print(f"FAIL review: {len(problems)} quality problem(s)")
    if problems:
        print("\n".join(quality.format_problems(problems)))
    if whitespace.returncode:
        print("Whitespace errors:\n" + "\n".join("  " + line for line in whitespace.stdout.splitlines()[:15]))
    if tools.returncode:
        print("Workflow tool tests failed:\n" + "\n".join("  " + line for line in tools.stderr.splitlines()[-15:]))
    return False


def command_status(_: argparse.Namespace) -> int:
    branch = subprocess.run(["git", "branch", "--show-current"], cwd=REPO, capture_output=True, text=True).stdout.strip()
    head = subprocess.run(["git", "log", "-1", "--format=%h"], cwd=REPO, capture_output=True, text=True).stdout.strip()
    dirty = len(subprocess.run(["git", "status", "--porcelain"], cwd=REPO, capture_output=True, text=True).stdout.splitlines())
    print(f"Repository: {branch} at {head}, {dirty} uncommitted path(s)")
    ledger = load_ledger()
    last = ledger.get("last")
    if last:
        print(f"Last check: {'PASS' if last['passed'] else 'FAIL'} {last['command']} ({last['summary']}) at {last['finished']}")
    changed = changed_paths(fingerprint(), ledger.get("baseline", {}))
    print(f"Not yet verified: {len(changed)} file(s); next check: {next_check(changed)}")
    print()
    print(quality.status_block((REPO / "TODO.md").read_text(encoding="utf-8")))
    return 0


def command_review(_: argparse.Namespace) -> int:
    return 0 if review() else 1


def command_affected(args: argparse.Namespace) -> int:
    ledger = load_ledger()
    current = fingerprint()
    changed = changed_paths(current, ledger.get("baseline", {}))
    print(f"Changed since last verified state: {len(changed)} file(s); next check: {next_check(changed)}")
    if args.dry_run:
        return 0
    if not changed:
        print("PASS affected: nothing changed since the last verified state")
        return 0
    if not review():
        return 1
    if not needs_build(changed):
        ledger["baseline"] = current
        record(ledger, "affected", "review only", True)
        print("PASS affected: no build needed")
        return 0
    with single_run():
        outcome = run_gradle("affected", code_check(changed), args.app_version, args.app_version_code)
    summary = report("affected", outcome)
    if outcome.passed:
        ledger["baseline"] = current
    record(ledger, "affected", summary, outcome.passed)
    return 0 if outcome.passed else 1


def command_gradle(name: str, tasks: list[str], args: argparse.Namespace, gallery: bool = False) -> int:
    if args.dry_run:
        print("Gradle:", " ".join(tasks))
        return 0
    with single_run():
        outcome = run_gradle(name, tasks, args.app_version, args.app_version_code, gallery=gallery)
    summary = report(name, outcome)
    record(load_ledger(), name, summary, outcome.passed)
    return 0 if outcome.passed else 1


def command_test(args: argparse.Namespace) -> int:
    tasks = [DESKTOP_TEST]
    for name in args.filters:
        tasks += ["--tests", name]
    return command_gradle("test", tasks, args, gallery=any("Gallery" in name for name in args.filters))


def command_android(args: argparse.Namespace) -> int:
    return command_gradle("android", [ANDROID_COMPILE], args)


RELEASE_APP_JARS = REPO / "composeApp" / "build" / "compose" / "binaries" / "main-release" / "app" / "Parchment" / "app"


def missing_service_classes(app_dir: Path = RELEASE_APP_JARS) -> list[str]:
    """Classes named in META-INF/services that the shrunk desktop jars no longer contain.

    ServiceLoader finds these only by name, so ProGuard can't see they're used; a missing one fails at run time
    (v2.1.2 lost Ktor's JSON provider this way and no request could be made).
    """
    import zipfile
    classes: set[str] = set()
    wanted: dict[str, str] = {}
    for jar in sorted(app_dir.glob("*.jar")):
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.endswith(".class"):
                    classes.add(name[:-len(".class")].replace("/", "."))
                elif name.startswith("META-INF/services/") and not name.endswith("/"):
                    for line in archive.read(name).decode("utf-8", "replace").splitlines():
                        entry = line.split("#", 1)[0].strip()
                        if entry:
                            wanted[entry] = name.removeprefix("META-INF/services/")
    return sorted(f"{cls} (a {service})" for cls, service in wanted.items() if cls not in classes)


def command_final(args: argparse.Namespace) -> int:
    missing = [name for name in SIGNER_VARIABLES if not os.environ.get(name, "").strip()]
    if missing and not args.dry_run:
        print("BLOCKED: final needs the private signer. In PowerShell run: . .\\scripts\\load-private-signer.ps1")
        return 2
    if args.dry_run:
        print("Gradle:", " ".join(FINAL_TASKS))
        return 0
    if not review():
        return 1
    current = fingerprint()
    with single_run():
        outcome = run_gradle("final", list(FINAL_TASKS), args.app_version, args.app_version_code, configuration_cache=False, gallery=True)
    summary = report("final", outcome)
    passed = outcome.passed
    if passed:
        missing = missing_service_classes()
        for entry in missing:
            print(f"FAIL release: ProGuard removed {entry}; add a -keep rule to composeApp/compose-desktop.pro")
        passed = not missing
    ledger = load_ledger()
    if passed:
        ledger["baseline"] = current
    record(ledger, "final", summary, passed)
    return 0 if passed else 1


def command_perf(args: argparse.Namespace) -> int:
    """Budgets live in docs/PERFORMANCE.md; this prints and keeps the latest numbers."""
    if args.dry_run:
        print("Gradle: :composeApp:createReleaseDistributable, then scripts/perf.py")
        return 0
    with single_run():
        # The release package is what users install: ProGuard-shrunk jars, the same runtime and launcher.
        outcome = run_gradle("perf", [":composeApp:createReleaseDistributable"], args.app_version, args.app_version_code)
    if not outcome.passed:
        report("perf", outcome)
        return 1
    results = perf.measure(REPO, android_install=args.android_install)
    lines = [f"{key}={value}" for key, value in results.items()]
    (STATE_DIR / "perf.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print("Saved: .parchment-local/verification/perf.txt")
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    build = argparse.ArgumentParser(add_help=False)
    build.add_argument("--dry-run", action="store_true", help="show what would run")
    build.add_argument("--app-version", default=APP_VERSION)
    build.add_argument("--app-version-code", default=APP_VERSION_CODE)
    commands.add_parser("status").set_defaults(run=command_status)
    commands.add_parser("review").set_defaults(run=command_review)
    commands.add_parser("affected", parents=[build]).set_defaults(run=command_affected)
    test = commands.add_parser("test", parents=[build])
    test.add_argument("filters", nargs="+", help="test class or method filters")
    test.set_defaults(run=command_test)
    commands.add_parser("android", parents=[build]).set_defaults(run=command_android)
    commands.add_parser("final", parents=[build]).set_defaults(run=command_final)
    perf_parser = commands.add_parser("perf", parents=[build])
    perf_parser.add_argument("--android-install", action="store_true", help="install the local release APK on the phone first")
    perf_parser.set_defaults(run=command_perf)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args(argv)
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
