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

"""Command-line entry of the Python runner for Transforms (docs/guides/BI_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 13, BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.10).

The Drishti server starts this once per Transform run: ``python core/transform_cli.py <work folder>``. The folder holds ``req.json`` (the Transform's manifest,
parameters, the Arrow files of its inputs, where to leave the result, the limits and the runner's settings) and the inputs; the answer is ``result.json`` in
the same folder. The Transform itself runs where every Python item runs: ``core/pyrunner.py``'s sandboxed worker (no network, a scratch folder, CPU, memory,
time and output limits). This file only translates between the server's folder and that runner, and holds nothing of the console's web side."""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.pyrunner import PyError, PyRunner  # noqa: E402


def run_measure(req: dict, runner: PyRunner) -> dict:
    """A Python measure over rows the server built for one person (an alert): ``{ok, by, rows}``, the group values then the number."""
    from core.pytests import arrow_of
    try:
        answer = asyncio.run(runner.run({"manifest": req["manifest"]}, arrow_of(req["columns"], req["rows"]), group_by=list(req.get("group_by") or []), params=req.get("params") or {}))
    except PyError as e:
        return {"ok": False, "code": e.code, "status": e.status, "detail": e.detail}
    except Exception as e:  # noqa: BLE001 - whatever went wrong is the server's answer, never a stack trace on a pipe
        return {"ok": False, "code": "DRS-9074", "status": 503, "detail": f"the Python runner failed: {type(e).__name__}: {e}"[:500]}
    return {"ok": True, "by": answer.get("by") or [], "rows": answer.get("rows") or [], "ms": answer.get("ms")}


def run(folder: Path) -> dict:
    req = json.loads((folder / "req.json").read_text(encoding="utf-8"))
    runner = PyRunner({**dict(req.get("settings") or {}), "bi.python.fork_server": False})   # one call per process here: no fork-server (a dict answers .get)
    if req.get("mode") == "measure":
        return run_measure(req, runner)
    arrows = [(folder / name).read_bytes() for name in req["inputs"]]
    try:
        answer = asyncio.run(runner.run_transform(req["manifest"], arrows, params=req.get("params") or {}, dest=req["output"], max_rows=int(req["max_rows"])))
    except PyError as e:
        return {"ok": False, "code": e.code, "status": e.status, "detail": e.detail}
    except Exception as e:  # noqa: BLE001 - whatever went wrong is the server's answer, never a stack trace on a pipe
        return {"ok": False, "code": "DRS-9074", "status": 503, "detail": f"the Python runner failed: {type(e).__name__}: {e}"[:500]}
    return {"ok": True, "columns": answer["columns"], "rows": answer["rows"], "ms": answer["ms"]}


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: transform_cli.py <work folder>", file=sys.stderr)
        return 2
    folder = Path(argv[1])
    (folder / "result.json").write_text(json.dumps(run(folder)), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
