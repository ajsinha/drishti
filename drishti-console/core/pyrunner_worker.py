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

"""The worker of the Python runner (docs/guides/PYTHON_BI.md): one short-lived process per call, started by ``core/pyrunner.py``.

It reads ``req.json`` and ``in.arrow`` (the rows the person looking may see, as Arrow IPC) from its scratch folder, runs the
author's function and writes ``out.json``. It imports nothing of the console.

**Fork-server** (wave B, docs/architecture/BI_DESIGN.md section 3.5 invariant 3): ``python -I pyrunner_worker.py --serve`` is one long-lived
parent per console that imports pandas, numpy and pyarrow once and then, for each line ``{"id", "tmp"}`` on its standard input, forks one child
that runs one call exactly as a fresh process would (its own session, environment, scratch folder, limits and audit hook, installed in the child
only) and exits. The parent answers ``{"id", "pid"}`` when the child starts and ``{"id", "rc"}`` when it ends; it never runs an author's code
itself, so one-process-per-call isolation is kept while the interpreter's start-up and the imports leave the latency. Defence in depth, not a
kernel sandbox:

* the parent gives it an empty environment, the scratch folder as working directory, a new session (so a timeout kills the
  whole group) and, where the machine allows, a network namespace with no interfaces;
* here it sets CPU, address-space and file-size limits before the author's code runs;
* an audit hook (it cannot be removed once installed) refuses sockets, processes, the dynamic loader and any file write
  outside the scratch folder, and reads outside the Python installation and the scratch folder;
* the author's own ``import`` goes through a guard that allows only the packages the manifest lists (and are on the
  console's allow-list) and a short list of safe standard-library modules; ``exec``, ``eval`` and ``compile`` are not given.
"""
from __future__ import annotations

import base64
import json
import math
import os
import re
import resource
import sys
import time
import traceback

FILE = "<python-item>"
SAFE_STDLIB = {"math", "statistics", "json", "datetime", "re", "collections", "itertools", "functools", "typing", "decimal", "fractions",
               "numbers", "string", "random", "operator", "bisect", "heapq", "dataclasses", "enum", "copy", "calendar", "time", "textwrap", "__future__"}
NET_EVENTS = ("socket.", "ssl.")
BLOCKED = {"subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn", "os.fork", "os.forkpty", "ctypes.dlopen", "ctypes.dlsym", "os.kill",
           "os.killpg", "shutil.unpack_archive", "webbrowser.open", "pty.spawn", "os.putenv", "os.unsetenv", "os.setuid", "os.chroot"}
WRITE_PATHS = {"os.remove": (0,), "os.rename": (0, 1), "os.mkdir": (0,), "os.rmdir": (0,), "os.chmod": (0,), "os.chown": (0,), "os.truncate": (0,),
               "os.symlink": (1,), "os.link": (0, 1), "os.utime": (0,), "shutil.copyfile": (0, 1), "shutil.rmtree": (0,), "shutil.move": (0, 1), "os.mkfifo": (0,), "os.mknod": (0,)}
READ_PATHS = {"os.listdir": (0,), "os.scandir": (0,), "glob.glob": (0,), "os.chdir": (0,)}
EXTRA_READ = ("/usr/lib", "/lib", "/lib64", "/usr/lib64", "/usr/local/lib", "/usr/share/zoneinfo", "/etc/ld.so.cache", "/etc/localtime", "/etc/ld.so.conf",
              "/proc/self", "/proc/meminfo", "/proc/cpuinfo", "/proc/sys/vm", "/sys/devices/system", "/dev/null", "/dev/urandom", "/dev/zero", "/dev/random")
MiB = 1024 * 1024


class Denied(PermissionError):
    """Something the sandbox refuses (the parent answers it as DRS-9073)."""


class Limit(Exception):
    """A limit of this call was hit (the parent answers it as DRS-9072)."""


def _vmsize() -> int:
    try:
        with open("/proc/self/status", encoding="ascii") as f:
            for line in f:
                if line.startswith("VmSize:"):
                    return int(line.split()[1]) * 1024
    except OSError:
        pass
    return 0


def _under(path: str, roots: tuple[str, ...]) -> bool:
    return any(path == r or path.startswith(r.rstrip("/") + "/") for r in roots)


def _install_hook(tmp: str) -> None:
    real_tmp = os.path.realpath(tmp)
    roots = {os.path.realpath(p) for p in sys.path if p and os.path.isabs(p)}
    roots |= {os.path.realpath(p) for p in (sys.prefix, sys.base_prefix, sys.exec_prefix, sys.base_exec_prefix)}
    read_roots = tuple(sorted(roots | {real_tmp} | {os.path.realpath(p) for p in EXTRA_READ}))
    write_roots = (real_tmp,)
    write_flags = os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_APPEND | os.O_TRUNC

    def real(p) -> str | None:
        if isinstance(p, int):
            return None
        try:
            return os.path.realpath(os.fsdecode(p))
        except (TypeError, ValueError):
            return None

    def hook(event: str, args) -> None:
        if event.startswith(NET_EVENTS):
            raise Denied("the network is not available to Python items")
        if event in BLOCKED or event.startswith("os.exec") or event.startswith("os.spawn"):
            raise Denied(f"{event} is not allowed in a Python item")
        if event == "open":
            path = real(args[0])
            if path is None:
                return
            flags = args[2] if len(args) > 2 and isinstance(args[2], int) else 0
            mode = args[1] if len(args) > 1 and isinstance(args[1], str) else "r"
            writes = bool(flags & write_flags) or any(c in mode for c in "wax+")
            if writes and not _under(path, write_roots):
                raise Denied(f"writing {path} is not allowed: only the scratch folder is writable")
            if not writes and not _under(path, read_roots):
                raise Denied(f"reading {path} is not allowed")
        elif event in WRITE_PATHS:
            for i in WRITE_PATHS[event]:
                p = real(args[i]) if len(args) > i else None
                if p is not None and not _under(p, write_roots):
                    raise Denied(f"{event} on {p} is not allowed: only the scratch folder is writable")
        elif event in READ_PATHS:
            p = real(args[0]) if args else None
            if p is not None and not _under(p, read_roots):
                raise Denied(f"{event} on {p} is not allowed")

    sys.addaudithook(hook)


def _limits(req: dict) -> None:
    cpu = int(time.process_time()) + int(req["timeout"]) + 1          # the imports so far count against the process, not against the author
    resource.setrlimit(resource.RLIMIT_CPU, (cpu, cpu + 1))
    out = int(req["max_output_bytes"])
    resource.setrlimit(resource.RLIMIT_FSIZE, (out * 4 + MiB, out * 4 + MiB))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
    base = _vmsize()
    if base:
        cap = base + int(req["memory"]) * MiB
        resource.setrlimit(resource.RLIMIT_AS, (cap, cap))


def _guard(allowed: set[str], real_import):
    def guarded(name, globals=None, locals=None, fromlist=(), level=0):
        top = name.split(".")[0]
        if level == 0 and top not in allowed:
            raise Denied(f"import of '{top}' is not allowed here (this item may use: {', '.join(sorted(allowed))}); list the package in the manifest's packages")
        return real_import(name, globals, locals, fromlist, level)
    return guarded


def _builtins(allowed: set[str]) -> dict:
    import builtins
    b = {k: v for k, v in vars(builtins).items() if k not in {"exec", "eval", "compile", "breakpoint", "input", "help", "exit", "quit", "__import__", "memoryview"}}
    b["__import__"] = _guard(allowed, builtins.__import__)
    return b


def _plain(o):
    """JSON for numpy and pandas scalars and arrays."""
    if hasattr(o, "tolist"):
        return o.tolist()
    if hasattr(o, "isoformat"):
        return o.isoformat()
    if hasattr(o, "item"):
        return o.item()
    raise TypeError(f"{type(o).__name__} is not JSON data")


DROP = {"formatter", "extraCssText", "appendToBody", "valueFormatter"}


def _clean(o):
    """ECharts options carry data, never code or markup: formatter strings and raw CSS are dropped."""
    if isinstance(o, dict):
        return {k: _clean(v) for k, v in o.items() if k not in DROP}
    if isinstance(o, list):
        return [_clean(v) for v in o]
    return o


def _vega_forbidden(o, path="$"):
    """The path of the first ``url`` or ``loader`` that is a plain value in a Vega-Lite specification (the page loads nothing from a URL), else None."""
    if isinstance(o, dict):
        for k, v in o.items():
            if k in ("url", "loader") and not isinstance(v, (dict, list)):
                return f"{path}.{k}"
            found = _vega_forbidden(v, f"{path}.{k}")
            if found:
                return found
    elif isinstance(o, list):
        for i, v in enumerate(o):
            found = _vega_forbidden(v, f"{path}[{i}]")
            if found:
                return found
    return None


def _number(v, where: str):
    if v is None:
        return None
    if isinstance(v, bool) or not hasattr(v, "__float__") and not isinstance(v, (int, float)):
        raise TypeError(f"{where}: a measure returns one number per group, not {type(v).__name__}")
    f = float(v)
    if math.isnan(f):
        return None
    if math.isinf(f):
        raise ValueError(f"{where}: a measure returned infinity")
    return int(f) if isinstance(v, int) or hasattr(v, "dtype") and getattr(v.dtype, "kind", "") in "iu" else f


def _load_quant(req: dict):
    """The console's quant engine (``core/quant``) as the name ``quant`` in a notebook cell; ``None`` when the cell is run without one."""
    root = req.get("quant_root")
    if not root:
        return None
    sys.path.insert(0, root)
    try:
        from core.quant import QuantError, get_engine
        engine = get_engine(req.get("quant_engine") or "stand-in")
    finally:
        sys.path.remove(root)

    class Quant:
        """``quant.price(instrument, **params)``: a number from the console's quant engine; ``quant.instruments()`` lists what it prices."""
        engine_name = engine.name

        def price(self, instrument, **params):
            try:
                return engine.price(instrument, **params)
            except QuantError as e:
                raise ValueError(f"quant: {e}") from None

        def instruments(self):
            return engine.instruments()

    return Quant()


def _cell(req: dict, df, ns: dict, quant, others: dict | None = None) -> dict:
    """A notebook cell of Analyse: runs the source with ``df`` (the reader's extract), ``params``, ``quant`` and ``extract(name)`` (any other extract of the
    notebook, as the same reader sees it); answers what it printed and its ``result`` (an ECharts option, SVG text, PNG bytes, a table, or a value)."""
    import io
    import contextlib
    frames = others or {}

    def extract(name):
        if name not in frames:
            raise KeyError(f"no extract '{name}' in this notebook (extracts: {', '.join(sorted(frames)) or 'none'})")
        return frames[name].copy()

    ns.update(df=df, params=dict(req["params"]), quant=quant, extract=extract)
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        exec(compile(req["source"], FILE, "exec"), ns)
    text = out.getvalue()[:20000]
    result = ns.get("result")
    answer: dict = {"kind": "cell", "stdout": text}
    if result is None:
        return answer
    if hasattr(result, "to_frame") and not hasattr(result, "columns"):
        result = result.to_frame()
    if hasattr(result, "columns") and hasattr(result, "head"):
        shown = result.head(int(req.get("table_rows") or 200))
        answer["table"] = json.loads(json.dumps({"columns": [str(c) for c in shown.columns], "rows": shown.astype(object).where(shown.notna(), None).values.tolist(),
                                                  "rowCount": int(len(result))},
                                                default=_plain, allow_nan=False))
    elif isinstance(result, dict):
        answer["option"] = _clean(json.loads(json.dumps(result, default=_plain, allow_nan=False)))
    elif isinstance(result, str) and result.lstrip().startswith("<svg"):
        low = result.lower()
        if any(x in low for x in ("<script", "javascript:", "<foreignobject", "onload=", "onerror=", "<iframe", "<image", "xlink:href")):
            raise Denied("an SVG may not hold script, links or embedded content")
        answer["svg"] = result
    elif isinstance(result, (bytes, bytearray)) and bytes(result[:8]) == b"\x89PNG\r\n\x1a\n":
        answer["png"] = base64.b64encode(bytes(result)).decode("ascii")
    else:
        answer["value"] = json.loads(json.dumps(result, default=lambda o: _plain(o) if hasattr(o, "tolist") else str(o), allow_nan=False))
    return answer


_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}")


def _arrow_type(t) -> str:
    """The dataset type a column of an Arrow table has: string, integer, decimal, boolean, date or timestamp."""
    import pyarrow as pa
    if pa.types.is_boolean(t):
        return "boolean"
    if pa.types.is_integer(t):
        return "integer"
    if pa.types.is_floating(t) or pa.types.is_decimal(t):
        return "decimal"
    if pa.types.is_timestamp(t):
        return "timestamp"
    if pa.types.is_date(t):
        return "date"
    return "string"


def _transform(req: dict, frames: list, ns: dict, tmp: str) -> dict:
    """A Transform of a derived dataset: ``run(*frames)`` (a top-level function, or the method of a class named ``Transform``, which gets ``self.params``) returns a
    pandas DataFrame; it is checked, typed from its columns and written as Parquet in the scratch folder for the parent to collect."""
    import inspect
    import pandas as pd
    import pyarrow as pa
    import pyarrow.parquet as pq
    exec(compile(req["source"], FILE, "exec"), ns)
    params = dict(req["params"])
    cls = ns.get("Transform")
    if isinstance(cls, type) and callable(getattr(cls, req["entry"], None)):
        inst = cls()
        inst.params = params
        fn = getattr(inst, req["entry"])
    else:
        fn = ns.get(req["entry"])
        if not callable(fn):
            raise ValueError(f"the source defines no function '{req['entry']}' (nor a class Transform with that method)")
    wants_params = "params" in inspect.signature(fn).parameters
    result = fn(*frames, params=params) if wants_params else fn(*frames)
    if not isinstance(result, pd.DataFrame):
        raise TypeError(f"a transform returns a pandas DataFrame, not {type(result).__name__}")
    if len(result) > int(req["max_rows"]):
        raise Limit(f"the frame has {len(result)} rows; the limit is {req['max_rows']}")
    result = result.reset_index(drop=True)
    names = [str(c) for c in result.columns]
    bad = [c for c in names if not _NAME.fullmatch(c)]
    if bad:
        raise ValueError(f"column name(s) {bad} are not usable: a dataset column is letters, digits and underscore, not starting with a digit")
    if len(set(names)) != len(names):
        raise ValueError("the frame has two columns of one name")
    result.columns = names
    try:
        table = pa.Table.from_pandas(result, preserve_index=False)
    except (pa.ArrowInvalid, pa.ArrowTypeError):
        for c in names:
            if result[c].dtype == object:
                result[c] = result[c].map(lambda v: None if v is None or v != v else str(v))
        table = pa.Table.from_pandas(result, preserve_index=False)
    columns = [{"name": f.name, "type": _arrow_type(f.type)} for f in table.schema]
    for f in table.schema:                                         # lists, structs and the like are not columns of a dataset
        if _arrow_type(f.type) == "string" and not (pa.types.is_string(f.type) or pa.types.is_large_string(f.type)):
            table = table.set_column(table.schema.get_field_index(f.name), f.name, table[f.name].cast(pa.string()))
    pq.write_table(table, os.path.join(tmp, "out.parquet"))
    return {"kind": "transform", "columns": columns, "rows": int(table.num_rows)}


def _column(req: dict, df, fn, params) -> dict:
    """The ``column`` interface (BI_DESIGN.md 3.5, plan node ``PyColumn``): ``derive(frame, params)`` gets the declared input columns of the dataset (masked as
    the reader sees them) and returns one value per row, vectorised: a pandas Series, a numpy array or a list of the frame's length."""
    inputs = req["inputs"] or [c for c in df.columns if c != "id"]
    missing = [c for c in inputs if c not in df.columns]
    if missing:
        raise ValueError(f"the rows lack the column(s) {missing}: list them in the derive's inputs")
    out = fn(df[inputs].reset_index(drop=True).copy(), dict(params))
    values = out.tolist() if hasattr(out, "tolist") else list(out) if isinstance(out, (list, tuple)) else None
    if values is None:
        raise TypeError(f"a column returns one value per row (a pandas Series, an array or a list), not {type(out).__name__}")
    if len(values) != len(df):
        raise ValueError(f"a column returns one value per row: {len(values)} values for {len(df)} rows")
    clean = []
    for v in values:
        if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
            v = None
        clean.append(v)
    kinds = {type(v).__name__ for v in clean if v is not None}
    kind = "integer" if kinds <= {"int"} and kinds else "decimal" if kinds <= {"int", "float"} and kinds else "boolean" if kinds == {"bool"} else "string"
    return {"kind": "column", "type": kind, "values": json.loads(json.dumps(clean, default=_plain, allow_nan=False))}


def _compute_groups(req: dict, df, fn, params) -> dict:
    """A measure's vectorised form (R8): ``compute_groups(frame, by, params)`` gets every group's rows at once (the declared inputs and the group columns) and
    returns a DataFrame with the group columns and one value column (named ``value``, else the last one); one call instead of one per group."""
    inputs = req["inputs"]
    by = req["group_by"] if req["group_by"] is not None else [c for c in df.columns if c not in inputs]
    missing = [c for c in inputs + by if c not in df.columns]
    if missing:
        raise ValueError(f"the rows lack the column(s) {missing}: list them in the visual's columns or the dataset measure's inputs and by")
    out = fn(df[by + [c for c in inputs if c not in by]].reset_index(drop=True).copy(), list(by), dict(params))
    if not hasattr(out, "columns"):
        raise TypeError(f"compute_groups returns a pandas DataFrame of the group columns and one value column, not {type(out).__name__}")
    if hasattr(out, "index") and getattr(out.index, "names", None) and any(n in by for n in out.index.names if n):
        out = out.reset_index()
    lack = [c for c in by if c not in out.columns]
    if lack:
        raise ValueError(f"compute_groups returned no column(s) {lack}: keep the group columns in the frame it returns")
    value = "value" if "value" in out.columns else [c for c in out.columns if c not in by][-1:]
    if not value:
        raise ValueError("compute_groups returned no value column")
    value = value if isinstance(value, str) else value[0]
    if len(out) > req["max_groups"]:
        raise Limit(f"more than {req['max_groups']} groups")
    out = out.sort_values(by) if by else out
    rows = []
    for rec in out[by + [value]].itertuples(index=False, name=None):
        key = [(k.item() if hasattr(k, "item") else k) for k in rec[:-1]]
        rows.append(key + [_number(rec[-1], "group " + ", ".join(map(str, key)))])
    return {"kind": "measure", "by": by, "rows": json.loads(json.dumps(rows, default=_plain, allow_nan=False)), "vectorised": True}


def _run(req: dict, tmp: str) -> dict:
    import pyarrow as pa
    import pandas as pd
    allowed = set(req["packages"]) | SAFE_STDLIB
    for p in req["packages"]:
        __import__(p)                                      # imported before the hook, so a lazy import later has nothing left to load
    if req["interface"] == "transform":
        import pyarrow.parquet  # noqa: F401 - loaded before the hook
        frames = []
        for name in req["inputs_files"]:
            with open(os.path.join(tmp, name), "rb") as f:
                frames.append(pa.ipc.open_stream(f).read_all().to_pandas())
        _limits(req)
        sys.dont_write_bytecode = True
        _install_hook(tmp)
        return _transform(req, frames, {"__name__": "python_item", "__builtins__": _builtins(allowed)}, tmp)
    with open(os.path.join(tmp, "in.arrow"), "rb") as f:
        df = pa.ipc.open_stream(f).read_all().to_pandas()
    df = df.drop(columns=[c for c in ("id",) if c in df.columns and c not in req["inputs"] and c not in (req["group_by"] or [])])
    cell = req["interface"] == "cell"
    quant = _load_quant(req) if cell else None             # imported before the hook, like the packages
    others, by_file = {}, {}
    for ename, fname in (req.get("extracts") or {}).items():          # the notebook's other extracts, read before the sandbox closes
        if fname not in by_file:
            with open(os.path.join(tmp, fname), "rb") as f:
                frame = pa.ipc.open_stream(f).read_all().to_pandas()
            by_file[fname] = frame.drop(columns=[c for c in ("id",) if c in frame.columns])
        others[ename] = by_file[fname]
    _limits(req)
    sys.dont_write_bytecode = True
    _install_hook(tmp)
    ns = {"__name__": "python_item", "__builtins__": _builtins(allowed)}
    if cell:
        return _cell(req, df, ns, quant, others)
    exec(compile(req["source"], FILE, "exec"), ns)          # the worker's own exec; the author's code is not given it
    params = req["params"]
    if req["interface"] == "measure" and callable(ns.get("compute_groups")) and req["entry"] in ("compute", "compute_groups"):
        return _compute_groups(req, df, ns["compute_groups"], params)
    fn = ns.get(req["entry"])
    if not callable(fn):
        raise ValueError(f"the source defines no function '{req['entry']}'")
    if req["interface"] == "column":
        return _column(req, df, fn, params)
    if req["interface"] == "measure":
        inputs = req["inputs"]
        by = req["group_by"] if req["group_by"] is not None else [c for c in df.columns if c not in inputs]       # no declared groups: the other columns handed over
        missing = [c for c in inputs + by if c not in df.columns]
        if missing:
            raise ValueError(f"the rows lack the column(s) {missing}: list them in the visual's columns or the dataset measure's inputs and by")
        rows = []
        groups = df.groupby(by, sort=True, dropna=False) if by else [((), df)]
        for key, sub in groups:
            if len(rows) >= req["max_groups"]:
                raise Limit(f"more than {req['max_groups']} groups")
            key = key if isinstance(key, tuple) else (key,)
            value = _number(fn(sub[inputs].reset_index(drop=True).copy(), dict(params)), "group " + ", ".join(map(str, key)))
            rows.append([(k.item() if hasattr(k, "item") else k) for k in key] + [value])
        return {"kind": "measure", "by": by, "rows": json.loads(json.dumps(rows, default=_plain, allow_nan=False))}
    shown = df[[c for c in df.columns if c != "id"]] if "id" in df.columns else df
    result = fn(shown.copy(), dict(params))
    if isinstance(result, dict):
        if req["output"] == "vega-lite":
            spec = json.loads(json.dumps(result, default=_plain, allow_nan=False))
            bad = _vega_forbidden(spec)
            if bad:
                raise Denied(f"a Vega-Lite specification may not load anything from a URL or name a loader ({bad}): put the data in data.values")
            return {"kind": "vega-lite", "spec": spec}
        if req["output"] != "echarts":
            raise TypeError(f"the manifest says output: {req['output']} but the function returned a dict (an ECharts option or a Vega-Lite specification)")
        return {"kind": "echarts", "option": _clean(json.loads(json.dumps(result, default=_plain, allow_nan=False)))}
    if isinstance(result, str) and result.lstrip().startswith("<svg"):
        low = result.lower()
        if any(x in low for x in ("<script", "javascript:", "<foreignobject", "onload=", "onerror=", "<iframe", "<image", "xlink:href")):
            raise Denied("an SVG may not hold script, links or embedded content")
        return {"kind": "svg", "svg": result}
    if isinstance(result, (bytes, bytearray)) and bytes(result[:8]) == b"\x89PNG\r\n\x1a\n":
        return {"kind": "png", "png": base64.b64encode(bytes(result)).decode("ascii")}
    raise TypeError("a visual returns an ECharts option or a Vega-Lite specification (dict), SVG text or PNG bytes, not " + type(result).__name__)


def main(tmp: str) -> int:
    with open(os.path.join(tmp, "req.json"), encoding="utf-8") as f:
        req = json.load(f)
    for p in req.get("extra_paths") or []:                 # the external library folder (core/external_lib.py): importable, and so readable
        if isinstance(p, str) and os.path.isabs(p) and p not in sys.path:
            sys.path.append(p)
    out: dict
    try:
        out = {"ok": True, **_run(req, tmp)}
        text = json.dumps(out, allow_nan=False)
        if len(text.encode("utf-8")) > int(req["max_output_bytes"]):
            raise Limit(f"the result is over {req['max_output_bytes']} bytes")
    except BaseException as e:   # noqa: BLE001 - every failure is reported to the parent, with the author's own frames
        frames = [fr for fr in traceback.extract_tb(e.__traceback__) if fr.filename == FILE]
        shown = "".join(traceback.format_list(frames)) + "".join(traceback.format_exception_only(type(e), e))
        kind = "sandbox" if isinstance(e, Denied) else "limit" if isinstance(e, (Limit, MemoryError)) else "invalid" if isinstance(e, SyntaxError) else "failed"
        message = f"{type(e).__name__}: {e}" if str(e) else type(e).__name__
        if isinstance(e, MemoryError):
            message = f"the memory limit of {req['memory']} MB was reached"
        text = json.dumps({"ok": False, "kind": kind, "error": message[:2000], "traceback": shown[-4000:]})
    with open(os.path.join(tmp, "out.json"), "w", encoding="utf-8") as f:
        f.write(text)
    return 0


PRELOAD = ("numpy", "pandas", "pyarrow", "pyarrow.parquet", "pyarrow.ipc")


def _child(tmp: str, env: dict) -> int:
    """One call in a forked child: a session of its own (the parent kills the group on a timeout), nothing of the parent's pipes, the call's environment and
    scratch folder; then exactly what a fresh worker process does."""
    os.setsid()
    null = os.open(os.devnull, os.O_RDWR)
    os.dup2(null, 0)
    os.dup2(null, 1)
    try:
        err = os.open(os.path.join(tmp, "err.txt"), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.dup2(err, 2)
    except OSError:
        os.dup2(null, 2)
    os.environ.clear()
    os.environ.update({k: str(v) for k, v in env.items()})
    os.environ.update({"HOME": tmp, "TMPDIR": tmp, "MPLCONFIGDIR": tmp})
    os.chdir(tmp)
    return main(tmp)


def serve() -> int:
    """The fork-server loop: requests on stdin, one JSON line each (``{"id", "tmp", "env"}``), answers on stdout (``{"id", "pid"}``, then ``{"id", "rc"}``)."""
    import select
    for name in PRELOAD:
        try:
            __import__(name)
        except ImportError:
            pass
    out = sys.stdout
    running: dict[int, object] = {}
    buf = b""
    stdin = sys.stdin.fileno()
    out.write(json.dumps({"ready": True, "pid": os.getpid()}) + "\n")
    out.flush()
    open_input = True
    while open_input or running:
        try:
            ready, _, _ = select.select([stdin] if open_input else [], [], [], 0.02)
        except InterruptedError:
            ready = []
        if ready:
            chunk = os.read(stdin, 65536)
            if not chunk:
                open_input = False                     # the console has gone: finish the children that run, then end
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                try:
                    req = json.loads(line)
                    tmp, rid = str(req["tmp"]), req["id"]
                except (ValueError, KeyError, TypeError):
                    continue
                if not os.path.isdir(tmp):
                    out.write(json.dumps({"id": rid, "rc": 2, "error": "no scratch folder"}) + "\n")
                    out.flush()
                    continue
                pid = os.fork()
                if pid == 0:
                    code = 70
                    try:
                        code = _child(tmp, dict(req.get("env") or {}))
                    finally:
                        os._exit(code)
                running[pid] = rid
                out.write(json.dumps({"id": rid, "pid": pid}) + "\n")
                out.flush()
        while running:
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                running.clear()
                break
            if pid == 0:
                break
            rid = running.pop(pid, None)
            if rid is None:
                continue
            rc = -os.WTERMSIG(status) if os.WIFSIGNALED(status) else os.WEXITSTATUS(status)
            out.write(json.dumps({"id": rid, "rc": rc}) + "\n")
            out.flush()
    return 0


if __name__ == "__main__":
    sys.exit(serve() if sys.argv[1:] == ["--serve"] else main(sys.argv[1]))
