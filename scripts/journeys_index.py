#!/usr/bin/env python3
"""Generate journeys/index.json: what each journey's actions name.

Per journeys/**/*.xml file the index records the file, the <journey name>, each identifier its
actions name with the source files that declare it, and the screens its actions name.

An identifier resolves to the files with a testTag("…") call for it; failing that, to the files
with a string literal equal to it (a named argument, a constant, a conditional branch); failing
that, to the files with the most specific string template that can produce it ("N$text"). In a
template with fewer than three characters of its own, an interpolation matches no capital letters,
so "N$text" produces N9 but not NavigationBack.

  python3 scripts/journeys_index.py          write journeys/index.json
  python3 scripts/journeys_index.py --check  fail when journeys/index.json does not match the tree

Both modes fail when a journey names an identifier that no source file declares.
"""

import argparse
import difflib
import itertools
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JOURNEYS_DIR = "journeys"
INDEX_FILE = "journeys/index.json"
COMMAND = "python3 scripts/journeys_index.py"

# Platform: an action names an identifier as testTag "Name"; Kotlin sources declare it.
IDENTIFIER_MENTION = re.compile(r'\btestTag "([^"]+)"')
SOURCE_DIRS = ("app/src/main", "app/src/debug", "app/src/release")
SOURCE_SUFFIX = ".kt"
STRING_LITERAL = re.compile(r'"((?:[^"\\\n$]|\\.|\$\{[^{}\n]*\}|\$)*)"')
INTERPOLATION = re.compile(r"\$\{[^{}\n]*\}|\$[A-Za-z_]\w*")
DECLARATION_CALL = re.compile(r"\btestTag\(\s*" + STRING_LITERAL.pattern + r"\s*\)")

SCREEN_WORD = r'(?:"[^"]+"|(?!(?:a|an|and|at|for|from|in|into|its|of|on|or|the|to|with)\b)[\w-]+)'
SCREEN_MENTION = re.compile(rf"\b(?:[Tt]he|[Aa]n?|[Ii]ts)\s+({SCREEN_WORD}(?:\s+{SCREEN_WORD}){{0,3}}?)\s+(screen|sheet)\b")
GENERIC_SCREEN_WORDS = {"bottom", "current", "entire", "first", "last", "new", "next", "other", "previous", "same", "whole"}


def scan_sources():
    calls, literals, templates = {}, {}, []
    for source_dir in SOURCE_DIRS:
        paths = sorted((ROOT / source_dir).rglob("*" + SOURCE_SUFFIX), key=lambda path: path.as_posix())
        for path in paths:
            relative = path.relative_to(ROOT).as_posix()
            text = path.read_text(encoding="utf-8", errors="replace")
            for value in set(DECLARATION_CALL.findall(text)):
                calls.setdefault(value, set()).add(relative)
            for value in set(STRING_LITERAL.findall(text)):
                parts = INTERPOLATION.split(value)
                own_text = "".join(parts)
                if len(parts) == 1:
                    literals.setdefault(value, set()).add(relative)
                elif re.search(r"[A-Za-z]", own_text):
                    wildcard = ".+" if len(own_text) >= 3 else "[^A-Z]+"
                    pattern = re.compile(wildcard.join(re.escape(part) for part in parts))
                    templates.append((pattern, len(own_text), relative))
    return calls, literals, templates


def declaring_files(identifier, calls, literals, templates):
    for declarations in (calls, literals):
        if identifier in declarations:
            return sorted(declarations[identifier])
    best, files = 0, set()
    for pattern, specificity, path in templates:
        if specificity < best or not pattern.fullmatch(identifier):
            continue
        if specificity > best:
            best, files = specificity, set()
        files.add(path)
    return sorted(files)


def screens_named(actions):
    screens, seen = [], set()
    for action in actions:
        for name, kind in SCREEN_MENTION.findall(action):
            words = name.replace('"', "").split()
            if all(word.lower() in GENERIC_SCREEN_WORDS for word in words):
                continue
            screen = " ".join(words + [kind])
            if screen.lower() not in seen:
                seen.add(screen.lower())
                screens.append(screen)
    return screens


def build_index():
    calls, literals, templates = scan_sources()
    journeys, errors = [], []
    paths = sorted((ROOT / JOURNEYS_DIR).rglob("*.xml"), key=lambda path: path.as_posix())
    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            errors.append(f"{relative}: {error}")
            continue
        actions = [" ".join("".join(action.itertext()).split()) for action in root.iter("action")]
        identifiers = {}
        for action in actions:
            for identifier in IDENTIFIER_MENTION.findall(action):
                if identifier in identifiers:
                    continue
                identifiers[identifier] = declaring_files(identifier, calls, literals, templates)
                if not identifiers[identifier]:
                    errors.append(f'{relative}: identifier "{identifier}" is not declared in any source file')
        journeys.append(
            {
                "file": relative,
                "name": root.get("name", ""),
                "identifiers": identifiers,
                "screens": screens_named(actions),
            }
        )
    return {"generatedBy": COMMAND, "journeys": journeys}, errors


def main():
    parser = argparse.ArgumentParser(description="Generate or check journeys/index.json.")
    parser.add_argument("--check", action="store_true", help="fail when journeys/index.json does not match the tree")
    args = parser.parse_args()

    index, errors = build_index()
    generated = json.dumps(index, indent=2, ensure_ascii=False) + "\n"
    index_path = ROOT / INDEX_FILE
    if args.check:
        committed = index_path.read_text(encoding="utf-8") if index_path.exists() else ""
        if committed != generated:
            diff = difflib.unified_diff(
                committed.splitlines(keepends=True), generated.splitlines(keepends=True), INDEX_FILE, "generated", n=1
            )
            sys.stderr.writelines(itertools.islice(diff, 80))
            errors.insert(0, f"{INDEX_FILE} is stale; run `{COMMAND}` and commit the result")
    else:
        index_path.write_text(generated, encoding="utf-8")

    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
