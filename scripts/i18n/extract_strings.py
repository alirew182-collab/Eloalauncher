#!/usr/bin/env python3
"""
One-off/maintenance tool: moves user-visible string literals from Compose UI files into
string resources.

  scripts/i18n/extract_strings.py [--dry-run] FILE.kt...

* Literals in composable UI code become `stringResource(R.string.<key>, args...)`.
* Literals passed to `messages.info(...)` / `messages.error(...)` become `messages.info(R.string.<key>, args...)`.
* Kotlin templates ($x / ${expr}) become positional %N$s arguments.
* New keys are appended to i18n/strings.tsv (key, en, ru, tr, de); translations are filled in by hand,
  then `scripts/i18n/gen_strings.py` writes res/values*/strings.xml.

Literals that are identifiers, MIME types, file names, test tags, routes or that contain nested
string literals inside templates are left untouched and reported.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TSV = ROOT / "i18n" / "strings.tsv"

SKIP_CONTEXT = re.compile(
    r"(testTag\(|hiltViewModel\(key\s*=|Regex\(|import |@Suppress\(|route\s*=|navigate\(|\.endsWith\(|\.startsWith\(|"
    r"\.contains\(\"|ClipData\.newPlainText\(|CreateDocument\(|\.launch\(\"|getSystemService|label\s*=\s*\"[a-z]|logger\.|"
    r"Log\.|mapOf\(|const val|\.format\(|split\(|joinToString\(|replace\(|substring|\.let \{ \"\$)$"
)


def load_tsv():
    rows = {}
    if TSV.exists():
        for line in TSV.read_text(encoding="utf-8").splitlines()[1:]:
            if not line.strip():
                continue
            parts = line.split("\t")
            rows[parts[0]] = parts
    return rows


def slug(text):
    words = re.findall(r"[A-Za-z0-9]+", text.lower())
    s = "_".join(words[:6]) or "text"
    if s[0].isdigit():
        s = "s_" + s
    return s[:48].rstrip("_")


def parse_literal(src, i):
    """src[i] == '"'. Returns (end_index_exclusive, parts) where parts is a list of
    ('t', text) and ('e', expr) or None if unsupported (raw string / nested quotes)."""
    if src.startswith('"""', i):
        return None
    j = i + 1
    parts = []
    buf = []
    while j < len(src):
        c = src[j]
        if c == "\\":
            buf.append(src[j:j + 2])
            j += 2
            continue
        if c == '"':
            if buf:
                parts.append(("t", "".join(buf)))
            return j + 1, parts
        if c == "\n":
            return None
        if c == "$" and j + 1 < len(src):
            n = src[j + 1]
            if n == "{":
                depth = 1
                k = j + 2
                while k < len(src) and depth:
                    if src[k] == "{":
                        depth += 1
                    elif src[k] == "}":
                        depth -= 1
                    elif src[k] == '"':
                        return ("nested", None)
                    k += 1
                if buf:
                    parts.append(("t", "".join(buf)))
                    buf = []
                parts.append(("e", src[j + 2:k - 1]))
                j = k
                continue
            if n.isalpha() or n == "_":
                m = re.match(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*", src[j + 1:])
                if buf:
                    parts.append(("t", "".join(buf)))
                    buf = []
                parts.append(("e", m.group(0)))
                j = j + 1 + len(m.group(0))
                continue
        buf.append(c)
        j += 1
    return None


def is_user_text(parts):
    text = "".join(p[1] for p in parts if p[0] == "t")
    if not re.search(r"[A-Za-z]{2,}", text):
        return False
    stripped = text.strip()
    if re.fullmatch(r"[a-z0-9_.\-/*:]+", stripped):  # identifiers, mime, routes, paths
        return False
    if re.fullmatch(r"[A-Za-z0-9_.\-]+\.(json|zip|jar|txt|png|toml|mrpack|eloapack|log|apk|mcworld)", stripped):
        return False
    if re.fullmatch(r"[A-Z0-9_]+", stripped):  # enum-ish constants
        return False
    if stripped.startswith("%") or stripped.startswith("application/"):
        return False
    return True


def to_resource(parts):
    out = []
    args = []
    for kind, val in parts:
        if kind == "t":
            out.append(val)
        else:
            args.append(val.strip())
            out.append("%%%d$s" % len(args))
    text = "".join(out)
    if args:
        # literal % must be doubled when the string is used with format args
        text = re.sub(r"%(?!\d+\$s)", "%%", text)
    # Kotlin escapes -> plain text (XML generator re-escapes)
    text = text.replace('\\"', '"').replace("\\$", "$").replace("\\n", "\n").replace("\\\\", "\\")
    return text, args


def process(path, rows, by_text, dry):
    src = path.read_text(encoding="utf-8")
    out = []
    i = 0
    changed = 0
    skipped = []
    line_start = 0
    in_line_comment = False
    in_block_comment = False
    while i < len(src):
        c = src[i]
        if in_line_comment:
            out.append(c)
            if c == "\n":
                in_line_comment = False
                line_start = i + 1
            i += 1
            continue
        if in_block_comment:
            out.append(c)
            if src.startswith("*/", i):
                out.append("/")
                i += 2
                in_block_comment = False
                continue
            i += 1
            continue
        if src.startswith("//", i):
            in_line_comment = True
            out.append(c)
            i += 1
            continue
        if src.startswith("/*", i):
            in_block_comment = True
            out.append(c)
            i += 1
            continue
        if c == "\n":
            line_start = i + 1
        if c == "'":  # char literal
            m = re.match(r"'(\\.|[^'\\])'", src[i:])
            if m:
                out.append(m.group(0))
                i += len(m.group(0))
                continue
        if c == '"':
            if src.startswith('"""', i):
                end = src.index('"""', i + 3) + 3
                out.append(src[i:end])
                i = end
                continue
            res = parse_literal(src, i)
            prefix = src[line_start:i]
            lineno = src.count("\n", 0, i) + 1
            if res is None or res[0] == "nested":
                # copy through to the closing quote of this literal conservatively
                j = i + 1
                depth = 0
                while j < len(src):
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == "{" and src[j - 1] == "$":
                        depth += 1
                    elif src[j] == "}" and depth:
                        depth -= 1
                    elif src[j] == '"' and depth == 0:
                        break
                    j += 1
                lit = src[i:j + 1]
                if res is not None and re.search(r"[A-Za-z]{3,} ", lit):
                    skipped.append((lineno, lit[:80]))
                out.append(lit)
                i = j + 1
                continue
            end, parts = res
            lit = src[i:end]
            msg_call = re.search(r"messages\.(info|error)\($", prefix)
            if not parts or not is_user_text(parts) or (SKIP_CONTEXT.search(prefix) and not msg_call):
                out.append(lit)
                i = end
                continue
            text, args = to_resource(parts)
            key = by_text.get(text)
            if key is None:
                base = slug(text)
                key = base
                n = 2
                while key in rows:
                    key = f"{base}_{n}"
                    n += 1
                rows[key] = [key, text.replace("\n", "\\n"), "", "", ""]
                by_text[text] = key
            arg_s = "".join(", " + a for a in args)
            if msg_call:
                out.append(f"R.string.{key}{arg_s}")
            else:
                out.append(f"stringResource(R.string.{key}{arg_s})")
            changed += 1
            i = end
            continue
        out.append(c)
        i += 1
    new = "".join(out)
    if changed and not dry:
        if "import androidx.compose.ui.res.stringResource" not in new and "stringResource(" in new:
            new = re.sub(r"(\npackage [^\n]+\n)", r"\1\nimport androidx.compose.ui.res.stringResource", new, count=1)
        if "import dev.eloa.launcher.R\n" not in new:
            new = re.sub(r"(\npackage [^\n]+\n)", r"\1\nimport dev.eloa.launcher.R", new, count=1)
        path.write_text(new, encoding="utf-8")
    return changed, skipped


def save_tsv(rows):
    TSV.parent.mkdir(parents=True, exist_ok=True)
    lines = ["key\ten\tru\ttr\tde"]
    for k in sorted(rows):
        r = rows[k] + [""] * (5 - len(rows[k]))
        lines.append("\t".join(r[:5]))
    TSV.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    dry = "--dry-run" in sys.argv
    files = [Path(a) for a in sys.argv[1:] if not a.startswith("--")]
    rows = load_tsv()
    by_text = {r[1].replace("\\n", "\n"): k for k, r in rows.items()}
    for f in files:
        changed, skipped = process(f, rows, by_text, dry)
        print(f"{f}: {changed} literals")
        for ln, s in skipped:
            print(f"   SKIPPED line {ln}: {s}")
    if not dry:
        save_tsv(rows)


if __name__ == "__main__":
    main()
