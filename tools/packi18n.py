#!/usr/bin/env python3
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

"""`drishti.py pack i18n export|import`: translate the About text of a pack through a spreadsheet.

    export  <pack> --lang fr --out fr.csv     CSV with key, source (English), translation (prefilled from config/about.fr.yaml when it exists)
    import  <pack> fr.csv                      writes config/about.fr.yaml (the overlay the server merges over about.yaml, key by key)

Translatable keys (a `/`-separated path, so glossary keys with dots stay whole):
    kinds/<kind>/title, kinds/<kind>/about, kinds/<kind>/glossary/<field>/<term|means|unit|sign|note>, kinds/<kind>/glossary/<field>/values/<value>,
    kinds/<kind>/panels/<panel>/about, vocabulary/<term>/<term|means|unit|sign|note>.
`${...}` expressions inside a text are copied unchanged; import warns when a translation changes them. Missing keys are fine (the English shows);
extra keys, unknown paths and a changed source column are reported. Exit 0 ok, 1 problems (extra keys, broken expressions, invalid overlay), 2 usage.
"""
from __future__ import annotations

import csv
import pathlib
import re

TEXT_FIELDS = ("term", "means", "unit", "sign", "note")
LANG = re.compile(r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
EXPR = re.compile(r"\$\{[^}]*\}")


class I18nError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


def about_path(pack: pathlib.Path, lang: str | None = None) -> pathlib.Path:
    return pack / "config" / (f"about.{lang}.yaml" if lang else "about.yaml")


def load_about(path: pathlib.Path, yaml) -> dict:
    try:
        d = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as e:
        raise I18nError(f"{path}: {e}") from None
    if not isinstance(d, dict):
        raise I18nError(f"{path}: not a YAML mapping")
    return d


def _entries(g: dict, prefix: str):
    for f, e in sorted((g or {}).items()):
        if not isinstance(e, dict):
            continue
        for fld in TEXT_FIELDS:
            if isinstance(e.get(fld), str):
                yield f"{prefix}/{f}/{fld}", e[fld]
        for v, txt in sorted((e.get("values") or {}).items()):
            if isinstance(txt, str):
                yield f"{prefix}/{f}/values/{v}", txt


def flatten(about: dict) -> dict[str, str]:
    out: dict[str, str] = {}
    for k, kd in sorted((about.get("kinds") or {}).items()):
        if not isinstance(kd, dict):
            continue
        for fld in ("title", "about"):
            if isinstance(kd.get(fld), str):
                out[f"kinds/{k}/{fld}"] = kd[fld]
        out.update(_entries(kd.get("glossary"), f"kinds/{k}/glossary"))
        for p, pd in sorted((kd.get("panels") or {}).items()):
            if isinstance(pd, dict) and isinstance(pd.get("about"), str):
                out[f"kinds/{k}/panels/{p}/about"] = pd["about"]
    out.update(_entries(about.get("vocabulary"), "vocabulary"))
    return out


def unflatten(pairs: dict[str, str]) -> dict:
    root: dict = {"about": 1}
    for key, text in pairs.items():
        parts = key.split("/")
        if parts[0] == "kinds":
            kind = root.setdefault("kinds", {}).setdefault(parts[1], {})
            rest = parts[2:]
            if rest[0] in ("title", "about"):
                kind[rest[0]] = text
            elif rest[0] == "panels":
                kind.setdefault("panels", {}).setdefault(rest[1], {})["about"] = text
            else:
                _put_entry(kind.setdefault("glossary", {}), rest[1:], text)
        else:
            _put_entry(root.setdefault("vocabulary", {}), parts[1:], text)
    return root


def _put_entry(table: dict, parts: list[str], text: str) -> None:
    e = table.setdefault(parts[0], {})
    if parts[1] == "values":
        e.setdefault("values", {})["/".join(parts[2:])] = text
    else:
        e[parts[1]] = text


def check_lang(lang: str) -> str:
    if not LANG.match(lang or ""):
        raise I18nError(f"--lang {lang!r}: not a language tag (fr, de, pt-BR)")
    return lang


def export(a, cli) -> int:
    yaml = cli.need_yaml()
    pack, lang = pathlib.Path(a.pack), check_lang(a.lang)
    src = about_path(pack)
    if not src.is_file():
        raise I18nError(f"{src}: no such file (a pack's About text lives in config/about.yaml)")
    source = flatten(load_about(src, yaml))
    existing = flatten(load_about(about_path(pack, lang), yaml)) if about_path(pack, lang).is_file() else {}
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["key", "source", "translation"])
        for k, v in source.items():
            w.writerow([k, v, existing.get(k, "")])
    todo = sum(1 for k in source if not existing.get(k))
    rep = {"pack": str(pack), "lang": lang, "file": str(out), "keys": len(source), "translated": len(source) - todo, "untranslated": todo}
    if a.json:
        cli.say_json(rep)
    else:
        print(f"pack i18n export: {len(source)} texts of {pack.name} -> {out}  ({rep['translated']} already translated, {todo} to do)")
        print(f"next: fill the 'translation' column, then  drishti.py pack i18n import {pack} {out} --lang {lang}")
    return 0


def import_(a, cli) -> int:
    yaml = cli.need_yaml()
    pack, csv_path = pathlib.Path(a.pack), pathlib.Path(a.file)
    if not csv_path.is_file():
        raise I18nError(f"{csv_path}: no such file")
    lang = check_lang(a.lang or _lang_from_name(csv_path))
    source = flatten(load_about(about_path(pack), yaml))
    with open(csv_path, encoding="utf-8", newline="") as fh:
        rd = csv.DictReader(fh)
        if not rd.fieldnames or not {"key", "translation"} <= set(rd.fieldnames):
            raise I18nError(f"{csv_path}: the header must have the columns key, source, translation")
        rows = list(rd)
    pairs, extra, changed, broken = {}, [], [], []
    for r in rows:
        key, tr = (r.get("key") or "").strip(), r.get("translation") or ""
        if not key:
            continue
        if key not in source:
            extra.append(key)
            continue
        if r.get("source") not in (None, "") and r["source"] != source[key]:
            changed.append(key)
        if tr.strip():
            if sorted(EXPR.findall(tr)) != sorted(EXPR.findall(source[key])):
                broken.append(key)
            pairs[key] = tr
    missing = [k for k in source if k not in pairs]
    overlay = unflatten(pairs)
    text = yaml.safe_dump(overlay, sort_keys=False, width=140, allow_unicode=True)
    back = yaml.safe_load(text)
    if flatten(back) != pairs:  # the strict-parse check: what is written reads back as exactly these keys
        raise I18nError("internal: the overlay does not read back as the imported keys", 1)
    dst = about_path(pack, lang)
    if not a.dry_run:
        dst.write_text(f"# The {lang} translation overlay of about.yaml, written by `drishti.py pack i18n import`; keys not here show in English.\n" + text, encoding="utf-8")
    problems = bool(extra or broken)
    rep = {"pack": str(pack), "lang": lang, "written": None if a.dry_run else str(dst), "translated": len(pairs), "missing": missing, "extra": extra,
           "sourceChanged": changed, "expressionChanged": broken}
    if a.json:
        cli.say_json(rep)
        return 1 if problems else 0
    print(f"pack i18n import: {len(pairs)} of {len(source)} texts -> {dst}" + ("  (dry run, nothing written)" if a.dry_run else ""))
    for label, keys in (("missing (shown in English)", missing), ("EXTRA keys (not in about.yaml, ignored)", extra),
                        ("source changed since the export (check the translation still fits)", changed), ("${...} expressions differ from the English (the text will not render the same)", broken)):
        if keys:
            print(f"  {label}: {len(keys)}")
            for k in keys[:8]:
                print(f"    {k}")
            if len(keys) > 8:
                print(f"    ... and {len(keys) - 8} more")
    if a.strict and missing:
        problems = True
    return 1 if problems else 0


def _lang_from_name(p: pathlib.Path) -> str:
    m = re.search(r"(?:^|[._-])([a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?)$", p.stem)
    if not m:
        raise I18nError("--lang is needed (the file name does not end in a language tag)")
    return m.group(1)
