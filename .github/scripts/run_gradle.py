"""Run one incremental Gradle command, retrying only confirmed download failures."""

import argparse
from collections import deque
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def retryable_download_failure(output: str) -> bool:
    if re.search(r"Compilation error|Script compilation errors|There were failing tests|checksum.{0,30}(?:mismatch|failed)|verification failed", output, re.I):
        return False
    transport_error = re.search(
        r"HTTP (?:response )?(?:status )?(?:code[: ]*)?5\d\d|Server returned HTTP response code: 5\d\d|Received status code 5\d\d|"
        r"SocketTimeoutException|ConnectTimeoutException|Connection reset|Read timed out|connect timed out",
        output, re.I,
    )
    download_context = re.search(r"Could not (?:GET|HEAD|download|install Gradle|resolve)|Downloading https?://|Gradle distribution", output, re.I)
    return bool(transport_error and download_context)


def run_with_download_retry(command, attempts=3, wait=time.sleep, execute=None):
    if attempts < 1:
        raise ValueError("attempts must be positive")
    if execute is None:
        def execute(args):
            process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
            tail = deque()
            tail_size = 0
            for line in process.stdout:
                print(line, end="", flush=True)
                retained = line[-2_000_000:]
                tail.append(retained)
                tail_size += len(retained)
                while tail_size > 2_000_000:
                    tail_size -= len(tail.popleft())
            process.stdout.close()
            return process.wait(), "".join(tail)
    for attempt in range(1, attempts + 1):
        code, output = execute(command)
        if code == 0 or not retryable_download_failure(output) or attempt == attempts:
            return code
        delay = min(30, 10 * attempt)
        print(f"Transient artifact download failed; retry {attempt + 1}/{attempts} in {delay}s. Cached tasks are reused.", flush=True)
        wait(delay)
    raise AssertionError("unreachable")


def gradle_command(arguments):
    root = Path(__file__).resolve().parents[2]
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if os.name == "nt":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", subprocess.list2cmdline([str(wrapper), *arguments])]
    return ["bash", str(wrapper), *arguments]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("gradle_args", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    arguments = options.gradle_args
    if arguments and arguments[0] == "--":
        arguments = arguments[1:]
    if not arguments:
        parser.error("provide Gradle tasks or --version after --")
    return run_with_download_retry(gradle_command(arguments), options.attempts)


if __name__ == "__main__":
    sys.exit(main())
