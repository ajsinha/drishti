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


"""The in-memory lake's caps for the launchers (HOW_IT_FITS.md section D3a): prints the MB the JVM's direct-memory
limit must have, 256 (the server's own buffers) plus every memory store's max-mb, read from the configuration the server reads
(application.local.yaml in the working folder, then the files named on the command line), with ${VAR:default} resolved from the
environment and DRISHTI_LAKE_MEMORY_<STORE>_MAX_MB overriding a store. DRISHTI_LAKE_MEMORY_MB, when set, is the stores' sum itself.
A store is a lake (config/lakes/<store>.yaml, root: mem://<store>, memory.max-mb; docs/guides/DATA_LAKE_GUIDE.md), and the old place,
drishti.lake.memory.stores, still counts. This is the one place the lake files are read without the Java reader (tools/lake/lakeconfig.py):
the launcher runs before any JVM, to size the JVM, and reads two keys only (root and memory.max-mb).

Arrow's off-heap memory (docs/guides/FLIGHT_GUIDE.md section 10; HOW_IT_FITS.md, As built) is sized here too, the same way: the
official Arrow library's buffers (the compute workers' exchange, BI answers sent as Arrow, Arrow Flight) are direct memory, so the limit
also gets ``arrow_mb()``: the exchange's budget (``DRISHTI_ARROW_EXCHANGE_MB``, 512) plus, when the engine ``flight`` is on (its file
config/engines/flight.yaml, or DRISHTI_FLIGHT_ENABLED), Flight's ``memory.total-mb``. The launchers pass the same figure to the server as
``-Ddrishti.arrow.max-bytes`` (``ArrowMemory``'s cap), so Arrow can never take more than the limit was raised for.

    python3 tools/lake/memory_caps.py                 -> 256 + the stores' caps + Arrow's budget
    python3 tools/lake/memory_caps.py --stores-only   -> the stores' caps alone (0 with none)
    python3 tools/lake/memory_caps.py --arrow-only    -> Arrow's budget alone (MB; the -Ddrishti.arrow.max-bytes figure)
    python3 tools/lake/memory_caps.py --extra-only    -> the stores' caps plus Arrow's budget: what a launcher adds to 256
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

BASE_MB = 256
# Arrow's budget beyond Flight (the compute exchange, BI answers as Arrow): DRISHTI_ARROW_EXCHANGE_MB overrides it
ARROW_EXCHANGE_MB = 512
# Flight's own cap when its engine file names none (config/engines/flight.yaml, memory.total-mb)
FLIGHT_TOTAL_MB = 4096
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
    for name, mb in lake_stores().items():
        out[name] = mb
    for name in list(out):
        env = os.environ.get("DRISHTI_LAKE_MEMORY_" + re.sub(r"[^A-Za-z0-9]", "_", name).upper() + "_MAX_MB")
        if env:
            out[name] = int(env)
    return out


def lake_stores() -> dict[str, int]:
    """The memory stores the lake files describe: each lake whose root is mem://<store>, its memory.max-mb."""
    import yaml
    folder = Path(os.environ.get("DRISHTI_LAKES_DIR") or "config/lakes")
    out: dict[str, int] = {}
    if not folder.is_dir():
        return out
    for f in sorted([*folder.glob("*.yaml"), *folder.glob("*/*.yaml")]):        # and the domain folders (Lakes.files)
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        root = resolve(doc.get("root") or "")
        if not root.startswith("mem://"):
            continue
        name = root[len("mem://"):].split("/")[0]
        try:
            out[name] = int(float(resolve((doc.get("memory") or {}).get("max-mb", 0)) or 0))
        except ValueError:
            out[name] = 0
    return out


def caps_mb(files: list[Path]) -> int:
    given = os.environ.get("DRISHTI_LAKE_MEMORY_MB")
    if given:
        return int(given)
    return sum(stores_of(files).values())


def engine_file() -> Path:
    """The in-memory lake's own file (docs/guides/ADMIN_GUIDE.md): DRISHTI_ENGINES_DIR, default ./config/engines."""
    return Path(os.environ.get("DRISHTI_ENGINES_DIR") or "config/engines") / "memory-lake.yaml"


def engine_on(f: Path) -> bool | None:
    """The switch in the engine's file (its variable, DRISHTI_MEMORY_LAKE_ENABLED, wins), or None without a file."""
    import yaml
    if not f.is_file():
        return None
    doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    raw = (((doc.get("drishti") or {}).get("engines") or {}).get("memory-lake") or {}).get("enabled")
    return str(resolve(raw)).strip().lower() == "true" if raw is not None else None


def flight_file() -> Path:
    """Arrow Flight's engine file (docs/guides/FLIGHT_GUIDE.md): DRISHTI_ENGINES_DIR, default ./config/engines."""
    return Path(os.environ.get("DRISHTI_ENGINES_DIR") or "config/engines") / "flight.yaml"


def flight_mb(f: Path | None = None) -> int:
    """Flight's memory.total-mb when its engine is on (the file's switch, DRISHTI_FLIGHT_ENABLED winning), else 0."""
    import yaml
    f = f or flight_file()
    doc = yaml.safe_load(f.read_text(encoding="utf-8")) if f.is_file() else None
    flight = (((doc or {}).get("drishti") or {}).get("engines") or {}).get("flight") or {}
    raw = os.environ.get("DRISHTI_FLIGHT_ENABLED") or resolve(flight.get("enabled", "false"))
    if str(raw).strip().lower() != "true":
        return 0
    total = resolve((flight.get("memory") or {}).get("total-mb", FLIGHT_TOTAL_MB))
    try:
        return int(float(total or FLIGHT_TOTAL_MB))
    except ValueError:
        return FLIGHT_TOTAL_MB


def arrow_mb(f: Path | None = None) -> int:
    """Arrow's budget in MB: the exchange's (DRISHTI_ARROW_EXCHANGE_MB, 512) plus Flight's total when its engine is on."""
    try:
        exchange = int(os.environ.get("DRISHTI_ARROW_EXCHANGE_MB") or ARROW_EXCHANGE_MB)
    except ValueError:
        exchange = ARROW_EXCHANGE_MB
    return max(0, exchange) + flight_mb(f)


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
    try:
        arrow = arrow_mb()
    except Exception as e:                                       # noqa: BLE001 - a launcher must still start: the exchange's budget at least
        print(f"memory_caps: {e}", file=sys.stderr)
        arrow = ARROW_EXCHANGE_MB
    if "--arrow-only" in argv:
        print(arrow)
    elif "--extra-only" in argv:
        print(caps + arrow)
    else:
        print(caps if only else BASE_MB + caps + arrow)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
