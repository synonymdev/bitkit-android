#!/usr/bin/env python3
"""Fail closed when an instrumented lane produces no executed tests."""

import sys
from pathlib import Path
import xml.etree.ElementTree as ET

reports = list(Path("app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
cases = [case for report in reports for case in ET.parse(report).iter("testcase")]
executed = [case for case in cases if case.find("skipped") is None]
failed = [case for case in executed if case.find("failure") is not None or case.find("error") is not None]
print(f"Instrumented results: {len(reports)} reports, {len(executed)} executed, "
      f"{len(cases) - len(executed)} skipped, {len(failed)} failed")
if not executed or failed:
    raise SystemExit("Expected at least one executed test and no failures")
if sys.argv[1:] == ["ComposeUi"] and len(executed) != len(cases):
    raise SystemExit("ComposeUi must not contain skipped tests")
