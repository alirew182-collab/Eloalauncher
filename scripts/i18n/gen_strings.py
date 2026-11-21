#!/usr/bin/env python3
"""
Generates Android string resources from i18n/strings.tsv (single source of truth for UI text).

Columns: key, en, ru, tr, de. Keys starting with `ds_` go to :core:designsystem, all others to :app.
Run after editing the table:  python3 scripts/i18n/gen_strings.py
`--check` fails (exit 1) when a translation is missing or the generated files are out of date (used in CI).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TSV = ROOT / "i18n" / "strings.tsv"
LANGS = {"en": "values", "ru": "values-ru", "tr": "values-tr", "de": "values-de"}
MODULES = {"app": ROOT / "app/src/main/res", "ds": ROOT / "core/designsystem/src/main/res"}
HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- GENERATED from i18n/strings.tsv by scripts/i18n/gen_strings.py. Do not edit. -->\n<resources>\n"


def esc(s):
    s = s.replace("\\n", "\n")
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def placeholders(s):
    return sorted(set(re.findall(r"%\d+\$[sd]", s)))


def main():
    check = "--check" in sys.argv
    rows = []
    errors = []
    for n, line in enumerate(TSV.read_text(encoding="utf-8").splitlines()[1:], start=2):
        if not line.strip():
            continue
        p = line.split("\t")
        if len(p) != 5:
            errors.append(f"line {n}: expected 5 columns, got {len(p)}")
            continue
        rows.append(p)
        for lang, val in zip(("ru", "tr", "de"), p[2:]):
            if not val.strip():
                errors.append(f"{p[0]}: missing {lang}")
            elif placeholders(val) != placeholders(p[1]):
                errors.append(f"{p[0]}: placeholders differ in {lang}")
    keys = [r[0] for r in rows]
    if len(keys) != len(set(keys)):
        errors.append("duplicate keys")
    if errors:
        print("\n".join(errors[:200]))
        sys.exit(1)
    outdated = []
    for mod, res in MODULES.items():
        for idx, (lang, folder) in enumerate(LANGS.items()):
            body = []
            for r in rows:
                is_ds = r[0].startswith("ds_")
                if (mod == "ds") != is_ds:
                    continue
                val = r[1 + idx]
                fmt = ' formatted="false"' if "%" in val and not placeholders(val) else ""
                body.append(f'    <string name="{r[0]}"{fmt}>{esc(val)}</string>\n')
            content = HEADER + "".join(body) + "</resources>\n"
            target = res / folder / "strings.xml"
            if check:
                if not target.exists() or target.read_text(encoding="utf-8") != content:
                    outdated.append(str(target))
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
    if outdated:
        print("Out of date (run scripts/i18n/gen_strings.py):\n" + "\n".join(outdated))
        sys.exit(1)
    print(f"{len(rows)} strings x {len(LANGS)} languages OK")


if __name__ == "__main__":
    main()
