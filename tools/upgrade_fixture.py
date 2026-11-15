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

"""What a site of an earlier release holds, built from that release's own files (docs/architecture/pack-resolution-inventory.md 8.3): every
connector template of every pack the release shipped, rendered the way its server generated it at first start (the header, the fields in the
server's order, settings flattened, the marker in <data>/connectors/generated/), and the release's demo bindings when it had them. The upgrade
test (drishti-console/tests/test_upgrade_counts.py) starts this release on it and on an empty site over one demo lake and compares the record
count of every demo kind.

    python3 tools/upgrade_fixture.py --from v2.9.11.i_2_3 --out data/tmp/upgrade-i23

Writes <out>/connectors/*.yaml, <out>/data/connectors/generated/*.properties, <out>/bindings.yaml (when the release had demo bindings) and
<out>/manifest.json. Deterministic; reads git only.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from connector_upgrade_fixture import marker, render, show  # noqa: E402  (the earlier server's rendering, one home)


def pack_manifests(rev: str) -> dict[str, str]:
    """Every shipped pack's pack.yaml path at the revision (config/packs/<domain>/<pack>, modules/<m>/packs/<pack>), by pack name."""
    out = subprocess.run(["git", "ls-tree", "-r", "--name-only", rev, "--", "config/packs", "modules"], cwd=ROOT, check=True,
                         capture_output=True, text=True).stdout.split()
    found: dict[str, str] = {}
    for path in sorted(out):
        if path.endswith("/pack.yaml") and "/packs/" in "/" + path and "/examples/" not in path and "/test" not in path:
            found.setdefault(pathlib.PurePosixPath(path).parent.name, path)
    return found


def templates(rev: str) -> dict[str, tuple[str, dict]]:
    """{connector: (pack, template)} of every shipped pack at the revision; the first pack in name order keeps a name."""
    out: dict[str, tuple[str, dict]] = {}
    for pack, path in sorted(pack_manifests(rev).items()):
        m = yaml.safe_load(show(rev, path)) or {}
        conns = m.get("connectors")
        if not isinstance(conns, dict):
            continue
        for name, t in conns.items():
            if isinstance(t, dict) and t.get("plugin") and name not in out:
                out[name] = (pack, t)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--from", dest="rev", required=True, help="the release tag whose site this is (v2.9.11.i_2_3)")
    ap.add_argument("--out", type=pathlib.Path, required=True)
    a = ap.parse_args(argv)
    out = a.out.resolve()
    shutil.rmtree(out, ignore_errors=True)
    (out / "connectors").mkdir(parents=True)
    (out / "data" / "connectors" / "generated").mkdir(parents=True)
    names = []
    for name, (pack, t) in sorted(templates(a.rev).items()):
        text = render(pack, t)
        (out / "connectors" / f"{name}.yaml").write_text(text, encoding="utf-8")
        (out / "data" / "connectors" / "generated" / f"{name}.properties").write_text(marker(text, t, a.rev), encoding="utf-8")
        names.append(name)
    bindings = None
    try:
        bindings = show(a.rev, "config/demo/bindings.yaml")
    except subprocess.CalledProcessError:
        pass                                            # the release had no demo bindings: its site has no bindings.yaml
    if bindings is not None:
        (out / "bindings.yaml").write_text(bindings, encoding="utf-8")
    (out / "manifest.json").write_text(json.dumps({"from": a.rev, "connectors": names, "bindings": bindings is not None}, indent=2) + "\n",
                                       encoding="utf-8")
    print(f"{len(names)} connector files of {a.rev} in {out}" + (" with its demo bindings" if bindings is not None else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
