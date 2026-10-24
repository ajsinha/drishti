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

"""Real public data for the world-economy pack (economics, politics and society).

    python3 tools/realdata/realdata.py fetch     [--cache DIR] [--refresh] [--pause S]   download the raw data (network)
    python3 tools/realdata/realdata.py normalise [--cache DIR]                           raw cache -> tools/realdata/dataset/*.json
    python3 tools/realdata/realdata.py about                                              sources.yaml + about/*.kinds.yaml -> tools/packgen/*/about.yaml
    python3 tools/realdata/realdata.py write                                              dataset -> the two packs, their glossary and their Sutra tests
    python3 tools/realdata/realdata.py tests                                              the packs' Sutra tests only (config/packs/*/tests)
    python3 tools/realdata/realdata.py docs                                               the generated tables of docs/guides/REAL_DATA.md
    python3 tools/realdata/realdata.py all       [--cache DIR] [--refresh]                fetch, normalise, about, write, docs: everything committed
    python3 tools/realdata/realdata.py check     [--cache DIR]                           fail if anything committed differs from what `all` would write

    --no-imf   a World Bank, UNDP and Factbook build: no IMF fetch, series, forecast kind or mention. It never touches the committed dataset,
               glossary, tests or guide; the packs go to --out (default <cache>/no-imf/packs). Use it where the IMF's terms do not allow the data.

The raw cache lives outside the repository (default ~/.cache/drishti-realdata) and is never committed; the normalised dataset
(tools/realdata/dataset/), the glossary, the Sutra tests, the generated guide tables and the packs are. See docs/guides/REAL_DATA.md.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import about as A  # noqa: E402
import build_docs as BD  # noqa: E402
import doc_snippets as DS  # noqa: E402
import fetch as F  # noqa: E402
import normalise as N  # noqa: E402
import pack_tests as PT  # noqa: E402

GENERATORS = {"world-economy": "tools/packgen/world/make.py"}
HAND_KEPT = {"regions.json"}       # dataset files written by hand (illustrative region figures), not derived from the raw cache


def generators(env: dict, check: bool = False) -> int:
    rc = 0
    for g in GENERATORS.values():
        rc |= subprocess.call([sys.executable, str(ROOT / g)] + (["--check"] if check else []), cwd=ROOT, env=env)
    return rc


def pack_docs(pack: str) -> dict:
    """The documents of a pack exactly as its generator builds them (the generator's own build(), which adds derived fields)."""
    path = ROOT / GENERATORS[pack]
    spec = importlib.util.spec_from_file_location(f"realdata_make_{path.parent.name}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.build()


def check_dataset(cache: Path, cfg: dict) -> list[str]:
    """Normalise the cache into a scratch directory and list the committed dataset files that differ (nothing to compare without a cache)."""
    if not (cache / "wb_country.json").exists():
        print(f"dataset: no raw cache at {cache}, not re-derived")
        return []
    with tempfile.TemporaryDirectory() as tmp:
        N.run(cache, cfg, Path(tmp))
        names = sorted({p.name for p in Path(tmp).iterdir()} | {p.name for p in N.DATASET.iterdir()} - HAND_KEPT)
        return [n for n in names if not (N.DATASET / n).exists() or not (Path(tmp) / n).exists()
                or (N.DATASET / n).read_bytes() != (Path(tmp) / n).read_bytes()]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["fetch", "normalise", "about", "write", "tests", "docs", "all", "check"])
    ap.add_argument("--cache", default=str(Path.home() / ".cache" / "drishti-realdata"))
    ap.add_argument("--refresh", action="store_true", help="download again even when the file is cached")
    ap.add_argument("--pause", type=float, default=0.4, help="seconds between requests")
    ap.add_argument("--no-imf", action="store_true", help="build without any IMF data (see above)")
    ap.add_argument("--out", help="with --no-imf: the directory the packs are written to (default <cache>/no-imf/packs)")
    a = ap.parse_args(argv)
    cache = Path(a.cache).expanduser()
    cfg = N.load_config()
    env = dict(os.environ)
    scratch = None
    if a.no_imf:
        out = Path(a.out).expanduser() if a.out else cache / "no-imf" / "packs"
        scratch = tempfile.TemporaryDirectory()
        ds = cache / "no-imf" / "dataset"
        env.update({BD.ENV_NO_IMF: "1", "DRISHTI_PACKS_OUT": str(out), "DRISHTI_ABOUT_DIR": scratch.name})
        if ds.exists() or a.command in ("normalise", "all"):
            env[N.ENV_DATASET] = str(ds)               # otherwise the committed dataset, filtered while the documents are built
    elif a.out:
        ap.error("--out only goes with --no-imf")
    os.environ.update({k: v for k, v in env.items() if k.startswith(("DRISHTI_REALDATA", "DRISHTI_PACKS_OUT", "DRISHTI_ABOUT"))})
    rc = 0
    try:
        if a.command in ("fetch", "all"):
            F.Fetcher(cache, cfg, a.pause, a.refresh, no_imf=a.no_imf).all()
        if a.command in ("normalise", "all"):
            n = N.run(cache, cfg, Path(env[N.ENV_DATASET]) if a.no_imf else None, a.no_imf)
            print(f"dataset: {n['countries']} countries, {n['series']} series, {n['factbook']} factbook profiles -> {env.get(N.ENV_DATASET, N.DATASET)}")
        if a.command == "about" or (a.command == "all" and not a.no_imf):
            print("about: wrote " + ", ".join(str(p.relative_to(ROOT)) for p in A.write()))
        if a.command in ("write", "all"):
            rc = generators(env)                         # each generator writes its glossary, pack and Sutra tests
        if a.command == "tests":
            for pack in GENERATORS:
                base = Path(env["DRISHTI_PACKS_OUT"]) / pack if a.no_imf else None
                print(f"{pack}: wrote {PT.write(pack, pack_docs(pack), base)} test files")
        if a.command == "docs" or (a.command == "all" and not a.no_imf):
            print("docs: REAL_DATA.md " + ("updated" if DS.write(BD.Data()) else "unchanged"))
        if a.command == "check":
            bad = [] if a.no_imf else check_dataset(cache, cfg)
            if bad:
                print(f"dataset differs from the raw cache: {bad}")
            rc |= bool(bad) | generators(env, check=True)
            if not a.no_imf and not DS.check(BD.Data()):
                print("docs/guides/REAL_DATA.md: generated tables differ; run realdata.py docs")
                rc |= 1
    finally:
        if scratch:
            scratch.cleanup()
    return rc


if __name__ == "__main__":
    sys.exit(main())
