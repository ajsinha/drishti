# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""Keeps the generated parts of the connector READMEs in step with their sources (the README standard,
docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md).

A connector README is written by hand, except two blocks this tool writes:

* a connector TYPE (``plugins/drishti-plugin-<x>/README.md``; the built-ins in ``docs/connectors/types/<type>/README.md``): the
  settings table between ``<!-- settings:begin -->`` and ``<!-- settings:end -->`` and the front matter's ``settings:`` list, from
  the settings catalogue (``drishti-engine/src/main/resources/drishti/plugin-settings.yaml``), the same one Admin → Connectors
  uses for its form. A built-in type the catalogue does not list keeps a hand-written table (no markers).
* a folder of connector TEMPLATES (``config/connectors.examples/<x>/README.md``): the table of its files between
  ``<!-- files:begin -->`` and ``<!-- files:end -->`` and the front matter's ``files:`` and ``connectors:`` lists, from the files.

    python3 tools/connector_readmes.py            rewrite the generated blocks
    python3 tools/connector_readmes.py --check    fail when a README is missing, breaks the standard, or a block is stale
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import readme as R  # noqa: E402

CATALOGUE = ROOT / "drishti-engine" / "src" / "main" / "resources" / "drishti" / "plugin-settings.yaml"
EXAMPLES = ROOT / "config" / "connectors.examples"
BUILT_INS = ROOT / "docs" / "connectors" / "types"


def type_readmes() -> list[Path]:
    """Every connector type's README path: one per plugin folder and one per built-in folder (present or not)."""
    return [d / "README.md" for d in sorted((ROOT / "plugins").glob("drishti-plugin-*")) if (d / "pom.xml").is_file()] + \
        [d / "README.md" for d in sorted(BUILT_INS.glob("*")) if d.is_dir()]


def template_readmes() -> list[Path]:
    return [d / "README.md" for d in sorted(EXAMPLES.glob("*")) if d.is_dir()]


def _cell(s) -> str:
    return str(s if s is not None else "").replace("|", "\\|").replace("\n", " ").strip()


def _type(s: dict) -> str:
    t = str(s.get("type") or "string")
    return t.replace("enum:", "one of ").replace("|", ", ")


def settings_block(plugin: str, cat: dict) -> tuple[str, list[str]]:
    spec = (cat.get("plugins") or {}).get(plugin) or {}
    rows, names = [], []
    for s in spec.get("settings") or []:
        names.append(s["name"])
        meaning = s.get("desc", "")
        if s.get("required"):
            meaning = "**Required.** " + meaning
        if s.get("secret"):
            meaning += " (secret: give `${ENV_VAR}` or `file:/path`, never the value)"
        rows.append([f"`{s['name']}`", _type(s), f"`{s['default']}`" if s.get("default") not in (None, "") else "", meaning])
    lines = ["| Setting | Type | Default | Meaning |", "|---|---|---|---|"] + ["| " + " | ".join(_cell(c) for c in r) + " |" for r in rows]
    common = ", ".join(f"`{c['name']}`" for c in cat.get("common") or [])
    extra = [f"Every connector also takes {common} (docs/connectors/CONNECTOR_FILES.md)."]
    if spec.get("tls"):
        extra.append("It takes the shared `tls.*` settings (docs/connectors/TLS.md)" + (
            ", also under " + ", ".join(f"`{p}*`" for p in spec["tls-prefixes"]) if spec.get("tls-prefixes") else "") + ".")
    if spec.get("proxy"):
        extra.append("It takes the shared `proxy.*` settings for an outbound HTTP proxy (CONNECTOR_DEVELOPER_GUIDE.md).")
    names += ["tls.*"] if spec.get("tls") else []
    names += ["proxy.*"] if spec.get("proxy") else []
    return "\n".join(lines + [""] + [" ".join(extra)]), names


def files_block(folder: Path) -> tuple[str, list[str], list[str]]:
    rows, files, plugins = [], [], []
    for f in sorted(folder.glob("*.yaml")):
        y = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        files.append(f.name)
        plugins.append(y.get("plugin", ""))
        kinds = ", ".join(f"`{k}`" for k in y.get("kinds") or []) or "the plugin's own"
        rows.append([f"`{f.name}`", f"`{y.get('plugin', '')}`", kinds, y.get("description", "")])
    lines = ["| File | Plugin | Kinds | What it is |", "|---|---|---|---|"] + ["| " + " | ".join(_cell(c) for c in r) + " |" for r in rows]
    return "\n".join(lines), files, sorted(set(p for p in plugins if p))


def _replace_block(body: str, name: str, block: str) -> str | None:
    pat = re.compile(rf"(<!-- {name}:begin[^>]*-->\n)(?:.*?\n)?(<!-- {name}:end -->)", re.S)
    if not pat.search(body):
        return None
    return pat.sub(lambda m: m.group(1) + block + "\n" + m.group(2), body, count=1)


def _with_front(text: str, updates: dict) -> str:
    """The README with front matter keys replaced (order kept, new keys appended)."""
    head = text[:R._COMMENT.match(text).end()]
    meta, body = R.split(text)
    meta = dict(meta or {})
    for k, v in updates.items():
        meta[k] = v
    return head + "---\n" + yaml.safe_dump(meta, sort_keys=False, allow_unicode=True, width=1000) + "---\n" + body


def regenerate(path: Path, cat: dict) -> str | None:
    """The README text with its generated parts brought up to date (None when it has no front matter to work from)."""
    text = path.read_text(encoding="utf-8")
    meta, _ = R.split(text)
    if meta is None:
        return None
    if meta.get("category") == "template":
        block, files, plugins = files_block(path.parent)
        new = _replace_block(text, "files", block) or text
        return _with_front(new, {"files": files, "connectors": plugins})
    plugin = meta.get("connector")
    if plugin in (cat.get("plugins") or {}):
        block, names = settings_block(plugin, cat)
        new = _replace_block(text, "settings", block) or text
        return _with_front(new, {"settings": names})
    return text


def main(argv: list[str]) -> int:
    cat = yaml.safe_load(CATALOGUE.read_text(encoding="utf-8"))
    bad, wrote = [], 0
    for p in type_readmes() + template_readmes():
        rel = p.relative_to(ROOT)
        probs = R.problems_connector(p)
        if p.is_file():
            want = regenerate(p, cat)
            if want is not None and want != p.read_text(encoding="utf-8"):
                if "--check" in argv:
                    probs.append("generated parts are stale: run tools/connector_readmes.py")
                else:
                    p.write_text(want, encoding="utf-8")
                    wrote += 1
                    probs = R.problems_connector(p)
        bad += [f"{rel}: {x}" for x in probs]
    if bad:
        print("connector READMEs do not follow the standard:\n  " + "\n  ".join(bad))
        return 1
    n = len(type_readmes()) + len(template_readmes())
    print(f"{n} connector READMEs follow the standard" + (f" ({wrote} rewritten)" if wrote else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
