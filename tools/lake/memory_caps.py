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


"""The in-memory lake's caps for the launchers (docs/architecture/IN_MEMORY_LAKE.md section 3a): prints the MB the JVM's direct-memory
limit must have, 256 (the server's own buffers) plus every memory store's max-mb, read from the configuration the server reads
(application.local.yaml in the working folder, then the files named on the command line), with ${VAR:default} resolved from the
environment and DRISHTI_LAKE_MEMORY_<STORE>_MAX_MB overriding a store. DRISHTI_LAKE_MEMORY_MB, when set, is the stores' sum itself.

    python3 tools/lake/memory_caps.py                 -> 256 with no stores, else 256 + the caps
    python3 tools/lake/memory_caps.py --stores-only   -> the caps alone (0 with none)
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

BASE_MB = 256
REF = re.compile(r"\$\{([A-Za-z0-9_.]+)(?::([^}]*))?\}")


def resolve(v) -> str:
    """``${VAR:default}`` from the environment, as the server resolves it."""
    return REF.sub(lambda m: os.environ.get(m.group(1), m.group(2) or ""), str(v))


def stores_of(files: list[Path]) -> dict[str, int]:
    import yaml
    out: dict[str, int] = {}
    for f in files:
        if not f.is_file():
            continue
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        stores = (((doc.get("drishti") or {}).get("lake") or {}).get("memory") or {}).get("stores") or {}
        for name, s in stores.items():
            try:
                out[name] = int(float(resolve((s or {}).get("max-mb", 0)) or 0))
            except ValueError:
                out[name] = 0
    for name in list(out):
        env = os.environ.get("DRISHTI_LAKE_MEMORY_" + re.sub(r"[^A-Za-z0-9]", "_", name).upper() + "_MAX_MB")
        if env:
            out[name] = int(env)
    return out


def caps_mb(files: list[Path]) -> int:
    given = os.environ.get("DRISHTI_LAKE_MEMORY_MB")
    if given:
        return int(given)
    return sum(stores_of(files).values())


def engine_file() -> Path:
    """The in-memory lake's own file (docs/architecture/COMPUTE_ENGINES.md): DRISHTI_ENGINES_DIR, default ./config/engines."""
    return Path(os.environ.get("DRISHTI_ENGINES_DIR") or "config/engines") / "memory-lake.yaml"


def engine_on(f: Path) -> bool | None:
    """The switch in the engine's file (its variable, DRISHTI_MEMORY_LAKE_ENABLED, wins), or None without a file."""
    import yaml
    if not f.is_file():
        return None
    doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    raw = (((doc.get("drishti") or {}).get("engines") or {}).get("memory-lake") or {}).get("enabled")
    return str(resolve(raw)).strip().lower() == "true" if raw is not None else None


def main(argv: list[str]) -> int:
    only = "--stores-only" in argv
    files = [Path("application.local.yaml")] + [Path(a) for a in argv if not a.startswith("--")]
    try:
        if engine_on(engine_file()):                                 # on in its own file: its stores count (off: only the old place's)
            files = [engine_file()] + files
    except Exception as e:                                       # noqa: BLE001 - a launcher must still start
        print(f"memory_caps: {e}", file=sys.stderr)
    try:
        caps = caps_mb(files)
    except Exception as e:                                       # a launcher must still start: the server's Health says if it is short
        print(f"memory_caps: {e}", file=sys.stderr)
        caps = 0
    print(caps if only else BASE_MB + caps)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
