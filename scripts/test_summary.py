#!/usr/bin/env python3
"""Aggregates JUnit XML results of all modules: prints per-module totals and failing tests."""
import glob, re, sys, xml.etree.ElementTree as ET
total = failed = skipped = 0
per = {}
fails = []
for path in glob.glob('**/build/test-results/**/*.xml', recursive=True):
    root = ET.parse(path).getroot()
    mod = path.split('/build/')[0]
    t, f, s = int(root.get('tests', 0)), int(root.get('failures', 0)) + int(root.get('errors', 0)), int(root.get('skipped', 0))
    per.setdefault(mod, [0, 0, 0])
    per[mod][0] += t; per[mod][1] += f; per[mod][2] += s
    total += t; failed += f; skipped += s
    for tc in root.iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            fails.append(f"{tc.get('classname')}.{tc.get('name')}")
for m, (t, f, s) in sorted(per.items()):
    print(f"{m:40s} tests={t:4d} failed={f} skipped={s}")
print(f"TOTAL tests={total} failed={failed} skipped={skipped}")
for x in fails: print("FAIL", x)
sys.exit(1 if failed else 0)
