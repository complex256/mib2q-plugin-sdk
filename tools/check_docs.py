#!/usr/bin/env python3
"""Checks the user documentation against the code it describes.

Run from the repository root: python3 tools/check_docs.py

Checks:
  * every fenced block preceded by "<!-- file: path -->" matches that file byte for byte;
  * the SDK version in README, the template and the getting-started pages matches gradle.properties;
  * the API level in the reference matches SdkInfo.API_VERSION;
  * public docs do not link to the internal engineering record.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLIC_DOCS = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]
PUBLIC_DOCS = [p for p in PUBLIC_DOCS if "internal" not in p.parts and "guide" not in p.parts]
failures = []


def fail(message):
    failures.append(message)


def read(path):
    return path.read_text(encoding="utf-8")


def check_code_blocks():
    marker = re.compile(r"^<!--\s*file:\s*(.+?)\s*-->$")
    for page in PUBLIC_DOCS:
        lines = read(page).splitlines()
        for i, line in enumerate(lines):
            m = marker.match(line.strip())
            if not m:
                continue
            target = ROOT / m.group(1)
            if not target.is_file():
                fail(f"{page.relative_to(ROOT)}:{i + 1}: marked file does not exist: {m.group(1)}")
                continue
            j = i + 1
            while j < len(lines) and not lines[j].startswith("```"):
                j += 1
            if j == len(lines):
                fail(f"{page.relative_to(ROOT)}:{i + 1}: no fenced block after the marker")
                continue
            j += 1
            block = []
            while j < len(lines) and not lines[j].startswith("```"):
                block.append(lines[j])
                j += 1
            expected = read(target).rstrip("\n").splitlines()
            if block != expected:
                fail(
                    f"{page.relative_to(ROOT)}:{i + 1}: code block differs from {m.group(1)} "
                    f"(page has {len(block)} lines, file has {len(expected)})"
                )


def check_versions():
    props = read(ROOT / "gradle.properties")
    m = re.search(r"^mibsdk\.version=(.+)$", props, re.MULTILINE)
    if not m:
        fail("gradle.properties: mibsdk.version is missing")
        return
    version = m.group(1).strip()

    template = read(ROOT / "templates/plugin/build.gradle.kts")
    tm = re.search(r'id\("mibsdk\.plugin"\)\s+version\s+"([^"]+)"', template)
    if not tm or tm.group(1) != version:
        fail(f"templates/plugin/build.gradle.kts: version is {tm.group(1) if tm else 'missing'}, expected {version}")

    for page in [
        ROOT / "README.md",
        ROOT / "docs/getting-started/create-a-plugin.md",
        ROOT / "docs/getting-started/index.md",
    ]:
        text = read(page)
        if version not in text:
            fail(f"{page.relative_to(ROOT)}: does not mention the current SDK version {version}")

    sdk_info = read(ROOT / "api/src/main/java/mibsdk/api/SdkInfo.java")
    am = re.search(r"API_VERSION\s*=\s*(\d+)", sdk_info)
    if not am:
        fail("api/src/main/java/mibsdk/api/SdkInfo.java: API_VERSION is missing")
    else:
        api_level = am.group(1)
        levels = read(ROOT / "docs/reference/api-levels.md")
        if not re.search(rf"^\|\s*{api_level}\s*\|", levels, re.MULTILINE):
            fail(f"docs/reference/api-levels.md: no row for the current api level {api_level}")
        readme = read(ROOT / "README.md")
        if f"1 through {api_level}" not in readme and f"api 1–{api_level}" not in readme:
            fail(f"README.md: does not state the current api range 1..{api_level}")


def check_internal_links():
    forbidden = ("docs/internal", "docs/superpowers", "docs/research", "docs/bench-e2e", "docs/car-readiness",
                 "docs/car-session-runbook")
    for page in PUBLIC_DOCS:
        # Contributor docs may point at the engineering record on purpose.
        if "contributing" in page.parts:
            continue
        text = read(page)
        for needle in forbidden:
            if needle in text:
                fail(f"{page.relative_to(ROOT)}: links to internal documentation ({needle})")


TRADEMARKS = re.compile(
    r"\b(?:Audi|Volkswagen|VW|e\.solutions|Porsche|Škoda|Skoda|Bentley|Lamborghini|Bugatti|"
    r"Virtual Cockpit|MMI|CarPlay|Apple|Google|Android Auto)\b",
    re.IGNORECASE,
)


def check_trademarks():
    """Public docs must not name vehicle or product brands. Technical platform codes are fine."""
    for page in PUBLIC_DOCS:
        text = read(page)
        for match in TRADEMARKS.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            fail(f"{page.relative_to(ROOT)}:{line}: brand name '{match.group(0)}' is not allowed in public docs")


def main():
    check_code_blocks()
    check_versions()
    check_internal_links()
    check_trademarks()
    if failures:
        print("documentation check failed:")
        for failure in failures:
            print("  - " + failure)
        return 1
    print(f"documentation check passed ({len(PUBLIC_DOCS)} public pages)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
