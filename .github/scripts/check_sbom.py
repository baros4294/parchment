"""Fails when the CycloneDX SBOM doesn't list the app's libraries.

The release SBOM once listed only the two Gradle modules and no libraries, and nothing noticed.
Usage: python3 .github/scripts/check_sbom.py build/reports/cyclonedx/bom.json
"""
import json
import sys

# The desktop and Android runtimes hold well over 200 libraries; far fewer means the scan missed them.
MIN_LIBRARIES = 50
# Libraries every Parchment build ships; if one is missing, the wrong classpath was scanned.
REQUIRED_GROUPS = ("io.ktor", "org.jetbrains.compose.runtime", "org.jetbrains.kotlinx")


def libraries(sbom: dict) -> list:
    return [c for c in sbom.get("components", []) if c.get("type") == "library"]


def problems(sbom: dict) -> list:
    libs = libraries(sbom)
    found = []
    if len(libs) < MIN_LIBRARIES:
        found.append(f"only {len(libs)} libraries listed, expected at least {MIN_LIBRARIES}")
    groups = {c.get("group", "") for c in libs}
    found += [f"no {group} library listed" for group in REQUIRED_GROUPS if group not in groups]
    return found


def main(path: str) -> int:
    with open(path, encoding="utf-8") as handle:
        sbom = json.load(handle)
    issues = problems(sbom)
    print(f"{path}: {len(libraries(sbom))} libraries")
    for issue in issues:
        print(f"::error::SBOM {issue}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
