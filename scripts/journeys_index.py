#!/usr/bin/env python3
r"""Generate journeys/index.json: the identifiers, source files and screens each journey names.

For each journeys/**/*.xml file the index records the file, the <journey name>, the identifiers its
actions name, the source files that declare those identifiers, and the screens its actions name.

A source file declares an identifier when it contains it as a string literal, or as a string template
whose text before the first interpolation begins the identifier while the rest of the identifier has
no capital letters: "N$text" and "N\(number)" declare N9 but not NRemove.

  python3 scripts/journeys_index.py          write journeys/index.json
  python3 scripts/journeys_index.py --check  fail when journeys/index.json is stale

Both modes fail when a journey names an identifier that no source file declares.
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "journeys" / "index.json"

# Platform: an action names an identifier as testTag "Name"; Kotlin sources declare it.
IDENTIFIER_MENTION = re.compile(r'\btestTag "([^"]+)"')
SOURCE_DIRS = ("app/src/main", "app/src/debug", "app/src/release")
SOURCE_SUFFIX = ".kt"

# A string literal "Name", or a string template "Name$…" or "Name\(…)" that begins with Name.
STRING_START = re.compile(r'"([\w-]+)("|\$|\\\()')
SCREEN_WORD = r'(?:"[^"]+"|(?!(?:a|an|bottom|its|same|the)\b)[\w-]+)'
SCREEN_MENTION = re.compile(
    rf"\b(?:[Tt]he|[Aa]n?|[Ii]ts)\s+({SCREEN_WORD}(?:\s+{SCREEN_WORD}){{0,3}}?)\s+(screen|(?:bottom\s+)?sheet)\b"
)


def scan_sources():
    literals, prefixes = {}, {}
    for source_dir in SOURCE_DIRS:
        for path in (ROOT / source_dir).rglob("*" + SOURCE_SUFFIX):
            relative = path.relative_to(ROOT).as_posix()
            for text, end in STRING_START.findall(path.read_text(encoding="utf-8", errors="replace")):
                declarations = literals if end == '"' else prefixes
                declarations.setdefault(text, set()).add(relative)
    return literals, prefixes


def declaring_files(identifier, literals, prefixes):
    if identifier in literals:
        return literals[identifier]
    for end in range(len(identifier) - 1, 0, -1):
        prefix, rest = identifier[:end], identifier[end:]
        if prefix in prefixes:
            return set() if re.search("[A-Z]", rest) else prefixes[prefix]
    return set()


def build_index():
    literals, prefixes = scan_sources()
    journeys, errors = [], []
    for path in sorted((ROOT / "journeys").rglob("*.xml")):
        relative = path.relative_to(ROOT).as_posix()
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            errors.append(f"{relative}: {error}")
            continue
        actions = [" ".join("".join(action.itertext()).split()) for action in root.iter("action")]
        identifiers = list(dict.fromkeys(name for action in actions for name in IDENTIFIER_MENTION.findall(action)))
        sources = set()
        for identifier in identifiers:
            files = declaring_files(identifier, literals, prefixes)
            if not files:
                errors.append(f'{relative}: identifier "{identifier}" is not declared in any source file')
            sources |= files
        screens = [
            " ".join(name.replace('"', "").split() + [kind])
            for action in actions
            for name, kind in SCREEN_MENTION.findall(action)
        ]
        journeys.append(
            {
                "file": relative,
                "name": root.get("name", ""),
                "identifiers": identifiers,
                "sources": sorted(sources),
                "screens": list(dict.fromkeys(screens)),
            }
        )
    return journeys, errors


def main():
    parser = argparse.ArgumentParser(description="Generate or check journeys/index.json.")
    parser.add_argument("--check", action="store_true", help="fail when journeys/index.json is stale")
    check = parser.parse_args().check

    journeys, errors = build_index()
    generated = json.dumps(journeys, indent=2, ensure_ascii=False) + "\n"
    if not check:
        INDEX.write_text(generated, encoding="utf-8")
    elif not INDEX.exists() or INDEX.read_text(encoding="utf-8") != generated:
        errors.insert(0, "journeys/index.json is stale; run `python3 scripts/journeys_index.py` and commit it")

    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
