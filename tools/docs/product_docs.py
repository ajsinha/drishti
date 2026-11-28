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

"""The product documents Drishti ships with (i7m), copied byte for byte so Help works offline.

    python3 tools/docs/product_docs.py            copy the pinned documents into docs/products/<product>/<tag>/<repo path>
    python3 tools/docs/product_docs.py --check    fail when the committed copy differs from the pinned tag

The pins and the files are in ``tools/docs/product_docs.yaml``. The documents live in the product's own repository (one home); this
reads them with ``git show <tag>:<path>`` from the product's clone beside this repository (a worktree finds the clones beside its main checkout). When the clone is absent (a source-zip build) or lacks the tag, the
committed copy is the source and ``--check`` only verifies it is complete, so a zip build still passes. Every copied file keeps the
product's own header. Each copy sits at its repository path under the tag, so a relative link inside it resolves within the copy.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
PINS = ROOT / "tools" / "docs" / "product_docs.yaml"
OUT = ROOT / "docs" / "products"


def pins() -> dict:
    return yaml.safe_load(PINS.read_text(encoding="utf-8"))["products"]


def clone_of(repo: str) -> Path | None:
    """The product's clone: beside this checkout, else beside the main checkout of a worktree."""
    homes = [ROOT.parent]
    r = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--path-format=absolute", "--git-common-dir"], capture_output=True, text=True)
    if r.returncode == 0 and r.stdout.strip():
        homes.append(Path(r.stdout.strip()).parent.parent)
    return next((h / repo for h in homes if (h / repo / ".git").exists()), None)


def show(clone: Path, tag: str, path: str) -> bytes | None:
    r = subprocess.run(["git", "-C", str(clone), "show", f"{tag}:{path}"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def targets() -> list[tuple[str, str, str, Path]]:
    """(product, tag, repo path, committed file) for every pinned document."""
    return [(name, p["tag"], f, OUT / name / p["tag"] / f) for name, p in pins().items() for f in p["files"]]


def run(check: bool, out=sys.stdout, refs: dict[str, str] | None = None) -> int:
    """``refs`` names, per product, the commit-ish the documents are read from instead of the pinned tag (a branch before its tag exists)."""
    refs = refs or {}
    bad: list[str] = []
    for name, p in pins().items():
        clone = clone_of(p["repo"])
        ref = refs.get(name, p["tag"])
        have = clone is not None and subprocess.run(["git", "-C", str(clone), "rev-parse", "-q", "--verify", f"{ref}^{{commit}}"],
                                                     capture_output=True).returncode == 0
        if not have:
            print(f"  {name}: no clone with tag {p['tag']} beside this repository: the committed copy is used as it is", file=out)
        for f in p["files"]:
            dest = OUT / name / p["tag"] / f
            if have:
                data = show(clone, ref, f)
                if data is None:
                    bad.append(f"{name} {ref}: {f} is not there")
                    continue
                if check:
                    if not dest.is_file() or dest.read_bytes() != data:
                        bad.append(f"{dest.relative_to(ROOT)} differs from {p['repo']}@{ref}:{f}")
                else:
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
            elif not dest.is_file():
                bad.append(f"{dest.relative_to(ROOT)} is missing and there is no clone to copy it from")
        if not check and have:
            keep = {OUT / name / p["tag"] / f for f in p["files"]}
            for old in (OUT / name).rglob("*") if (OUT / name).is_dir() else []:   # a copy of an older pin or a dropped file goes
                if old.is_file() and old not in keep:
                    old.unlink()
            for d in sorted((OUT / name).rglob("*"), reverse=True) if (OUT / name).is_dir() else []:
                if d.is_dir() and not any(d.iterdir()):
                    d.rmdir()
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    for name, prop in (("rachana", "drishti.rachana.version"), ("elements", "drishti.elements.version")):
        m = re.search(rf"<{re.escape(prop)}>([^<]+)</", pom)
        tag = pins()[name]["tag"]
        if not m or not (tag == f"v{m.group(1)}" or tag.startswith(f"v{m.group(1)}-")):
            bad.append(f"{name}: the pinned tag {tag} is not a tag of the pom's {prop} {m.group(1) if m else '?'}")
    for b in bad:
        print(f"  {b}", file=out)
    if bad:
        print("fix with: python3 tools/docs/product_docs.py", file=out)
        return 1
    print(f"  {sum(len(p['files']) for p in pins().values())} product documents " + ("are current" if check else "written"), file=out)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--ref", action="append", default=[], metavar="PRODUCT=REF",
                    help="read a product's documents from this commit-ish (a branch) instead of its pinned tag, until the tag exists")
    a = ap.parse_args(argv)
    return run(a.check, refs=dict(r.split("=", 1) for r in a.ref))


if __name__ == "__main__":
    sys.exit(main())
