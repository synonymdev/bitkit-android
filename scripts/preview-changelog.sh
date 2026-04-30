#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/preview-changelog.sh [--target next|hotfix|all]

Preview pending changelog fragments without modifying files.
EOF
}

target="all"

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

if [[ "$target" != "next" && "$target" != "hotfix" && "$target" != "all" ]]; then
  echo "--target must be 'next', 'hotfix', or 'all'" >&2
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
CHANGELOG_DIR = ROOT / "changelog.d"

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
    raise SystemExit(f"preview-changelog: {message}")


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
    return category, f"- {text}{suffix} ({path.relative_to(ROOT)})"


def target_dirs() -> list[tuple[str, Path]]:
    targets = ["next", "hotfix"] if TARGET == "all" else [TARGET]
    return [(target, CHANGELOG_DIR / target) for target in targets]


found_any = False

for target, directory in target_dirs():
    if not directory.exists():
        fail(f"fragment directory '{directory.relative_to(ROOT)}' does not exist")

    fragments = sorted(
        path for path in directory.glob("*.md") if path.is_file() and path.name != ".gitkeep"
    )

    print(f"## {target}")
    if not fragments:
        print("No pending changelog fragments.\n")
        continue

    found_any = True
    entries_by_category: dict[str, list[str]] = {category: [] for category in CATEGORY_ORDER}
    for fragment in fragments:
        category, entry = fragment_entry(fragment)
        entries_by_category[category].append(entry)

    for category in CATEGORY_ORDER:
        entries = entries_by_category[category]
        if not entries:
            continue

        print(f"\n### {category}")
        for entry in entries:
            print(entry)

    print()

if not found_any:
    raise SystemExit(0)
PY
