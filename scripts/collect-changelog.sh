#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/collect-changelog.sh --target next|hotfix

Collect changelog fragments from changelog.d/<target>/ into CHANGELOG.md.
EOF
}

target=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      if [[ $# -lt 2 ]]; then
        echo "--target requires a value" >&2
        usage >&2
        exit 1
      fi
      target="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ "$target" != "next" && "$target" != "hotfix" ]]; then
  echo "--target must be either 'next' or 'hotfix'" >&2
  usage >&2
  exit 1
fi

python3 - "$target" <<'PY'
from __future__ import annotations

import re
import sys
from pathlib import Path

TARGET = sys.argv[1]
ROOT = Path.cwd()
CHANGELOG = ROOT / "CHANGELOG.md"
FRAGMENT_DIR = ROOT / "changelog.d" / TARGET

CATEGORY_LABELS = {
    "added": "Added",
    "changed": "Changed",
    "deprecated": "Deprecated",
    "removed": "Removed",
    "fixed": "Fixed",
    "security": "Security",
}
CATEGORY_ORDER = list(CATEGORY_LABELS.values())
FRAGMENT_PATTERN = re.compile(
    r"^(?P<ref>[A-Za-z0-9][A-Za-z0-9._-]*)\.(?P<category>added|changed|deprecated|removed|fixed|security)\.md$"
)


def fail(message: str) -> None:
    raise SystemExit(f"collect-changelog: {message}")


def fragment_entry(path: Path) -> tuple[str, str]:
    match = FRAGMENT_PATTERN.match(path.name)
    if not match:
        fail(
            f"invalid fragment name '{path.relative_to(ROOT)}'; "
            "expected <issue-or-pr>.<category>.md"
        )

    lines = [line.strip() for line in path.read_text().splitlines() if line.strip()]
    if not lines:
        fail(f"fragment '{path.relative_to(ROOT)}' is empty")

    text = " ".join(lines)
    if text.startswith("-"):
        fail(f"fragment '{path.relative_to(ROOT)}' must not start with a bullet")

    ref = match.group("ref")
    category = CATEGORY_LABELS[match.group("category")]
    suffix = f" #{ref}" if ref.isdigit() else ""
    return category, f"- {text}{suffix}"


def unreleased_bounds(changelog: str) -> tuple[int, int]:
    header = re.search(r"^## \[Unreleased\]\n", changelog, flags=re.MULTILINE)
    if not header:
        fail("could not find '## [Unreleased]' in CHANGELOG.md")

    next_release = re.search(r"^## \[[^\]]+\].*$", changelog[header.end() :], flags=re.MULTILINE)
    start = header.end()
    end = start + next_release.start() if next_release else len(changelog)
    return start, end


def category_heading(category: str) -> re.Pattern[str]:
    return re.compile(rf"^### {re.escape(category)}\n", flags=re.MULTILINE)


def insert_entries(body: str, category: str, entries: list[str]) -> str:
    heading = category_heading(category).search(body)
    entries_text = "\n".join(entries) + "\n"

    if heading:
        insert_at = heading.end()
        return body[:insert_at] + entries_text + body[insert_at:]

    block = f"### {category}\n{entries_text}\n"
    category_index = CATEGORY_ORDER.index(category)

    for later_category in CATEGORY_ORDER[category_index + 1 :]:
        later_heading = category_heading(later_category).search(body)
        if later_heading:
            return body[: later_heading.start()] + block + body[later_heading.start() :]

    stripped_body = body.rstrip()
    if not stripped_body:
        return f"\n{block}"

    trailing = body[len(stripped_body) :]
    return f"{stripped_body}\n\n{block}{trailing}"


if not CHANGELOG.exists():
    fail("CHANGELOG.md does not exist")

if not FRAGMENT_DIR.exists():
    fail(f"fragment directory '{FRAGMENT_DIR.relative_to(ROOT)}' does not exist")

fragments = sorted(
    path for path in FRAGMENT_DIR.glob("*.md") if path.is_file() and path.name != ".gitkeep"
)

if not fragments:
    print(f"No changelog fragments found in {FRAGMENT_DIR.relative_to(ROOT)}.")
    raise SystemExit(0)

entries_by_category: dict[str, list[str]] = {category: [] for category in CATEGORY_ORDER}
for fragment in fragments:
    category, entry = fragment_entry(fragment)
    entries_by_category[category].append(entry)

changelog = CHANGELOG.read_text()
start, end = unreleased_bounds(changelog)
body = changelog[start:end]
existing_entries = set(body.splitlines())

inserted = 0
for category in CATEGORY_ORDER:
    entries = [entry for entry in entries_by_category[category] if entry not in existing_entries]
    if not entries:
        continue

    body = insert_entries(body, category, entries)
    existing_entries.update(entries)
    inserted += len(entries)

CHANGELOG.write_text(changelog[:start] + body + changelog[end:])

for fragment in fragments:
    fragment.unlink()

print(f"Collected {inserted} changelog entr{'y' if inserted == 1 else 'ies'} from changelog.d/{TARGET}.")
PY
