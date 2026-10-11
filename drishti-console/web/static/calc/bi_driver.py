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

"""The BI driver of the browser target (docs/guides/PYTHON_BI.md): runs a Python item on the rows a report page already received, inside Pyodide, in
the Web Worker of Calc (calc-worker.js). ``bi-python.js`` sends this text, then ``_bi_main(<base64 JSON>)``. The item's result is printed on one line
behind a marker the page reads; nothing is sent anywhere. The same rules as the server worker's: a measure is called once per group with a pandas table of
that group's rows (its inputs only) and returns a number; a visual is called once with the rows and returns an ECharts option, SVG text or PNG bytes.

The browser's own sandbox is the boundary here (no network from this worker beyond the console's origin, no file system): the packages are the ones Pyodide
ships (numpy, pandas, scipy, statsmodels), and memory is the tab's.
"""
import base64 as _b64
import json as _json
import math as _math

import pandas as _pd

_MARK = "\x01BIRESULT"
_DROP = {"formatter", "extraCssText", "appendToBody", "valueFormatter"}


def _bi_plain(o):
    if hasattr(o, "tolist"):
        return o.tolist()
    if hasattr(o, "isoformat"):
        return o.isoformat()
    if hasattr(o, "item"):
        return o.item()
    raise TypeError(f"{type(o).__name__} is not JSON data")


def _bi_clean(o):
    if isinstance(o, dict):
        return {k: _bi_clean(v) for k, v in o.items() if k not in _DROP}
    if isinstance(o, list):
        return [_bi_clean(v) for v in o]
    return o


def _bi_number(v, where):
    if v is None:
        return None
    if isinstance(v, bool) or not (hasattr(v, "__float__") or isinstance(v, (int, float))):
        raise TypeError(f"{where}: a measure returns one number per group, not {type(v).__name__}")
    f = float(v)
    if _math.isnan(f):
        return None
    if _math.isinf(f):
        raise ValueError(f"{where}: a measure returned infinity")
    return int(f) if isinstance(v, int) else f


class _NoQuant:
    """``quant`` in a browser cell: the console's quant engine runs on the server target only (docs/guides/QUANT_SERVICE.md)."""

    def price(self, *a, **k):
        raise ValueError("quant: the quant engine is available on the server target only; run this cell on the server")

    instruments = price


def _bi_cell(p, df):
    """A notebook cell of Analyse (docs/guides/ANALYSE.md): ``df`` is the extract; what it prints and its ``result`` come back, as the server's worker does."""
    import contextlib as _ctx
    import io as _io
    ns = {"__name__": "python_item", "df": df, "params": p["params"] or {}, "quant": _NoQuant()}
    out = _io.StringIO()
    with _ctx.redirect_stdout(out):
        exec(compile(p["source"], "<python-item>", "exec"), ns)
    answer = {"kind": "cell", "stdout": out.getvalue()[:20000]}
    r = ns.get("result")
    if r is None:
        return answer
    if hasattr(r, "to_frame") and not hasattr(r, "columns"):
        r = r.to_frame()
    if hasattr(r, "columns") and hasattr(r, "head"):
        shown = r.head(200)
        answer["table"] = {"columns": [str(c) for c in shown.columns], "rows": shown.values.tolist(), "rowCount": int(len(r))}
    elif isinstance(r, dict):
        answer["option"] = _bi_clean(_json.loads(_json.dumps(r, default=_bi_plain, allow_nan=False)))
    elif isinstance(r, str) and r.lstrip().startswith("<svg"):
        low = r.lower()
        if any(x in low for x in ("<script", "javascript:", "<foreignobject", "onload=", "onerror=", "<iframe", "<image", "xlink:href")):
            raise ValueError("an SVG may not hold script, links or embedded content")
        answer["svg"] = r
    elif isinstance(r, (bytes, bytearray)) and bytes(r[:8]) == b"\x89PNG\r\n\x1a\n":
        answer["png"] = _b64.b64encode(bytes(r)).decode("ascii")
    else:
        answer["value"] = _json.loads(_json.dumps(r, default=lambda o: _bi_plain(o) if hasattr(o, "tolist") else str(o), allow_nan=False))
    return answer


def _bi_main(payload):
    p = _json.loads(_b64.b64decode(payload).decode("utf-8"))
    df = _pd.DataFrame(p["rows"], columns=p["columns"])
    if p["interface"] == "cell":
        print(_MARK + _json.dumps(_bi_cell(p, df), default=_bi_plain, allow_nan=False))
        return
    ns = {"__name__": "python_item"}
    exec(compile(p["source"], "<python-item>", "exec"), ns)
    fn = ns.get(p["entry"])
    if not callable(fn):
        raise ValueError(f"the source defines no function '{p['entry']}'")
    params = p["params"] or {}
    if p["interface"] == "measure":
        inputs = p["inputs"]
        by = p["by"] if p["by"] is not None else [c for c in df.columns if c not in inputs and c != "id"]
        missing = [c for c in inputs + by if c not in df.columns]
        if missing:
            raise ValueError(f"the rows lack the column(s) {missing}")
        groups = df.groupby(by, sort=True, dropna=False) if by else [((), df)]
        rows = []
        for key, sub in groups:
            key = key if isinstance(key, tuple) else (key,)
            value = _bi_number(fn(sub[inputs].reset_index(drop=True).copy(), dict(params)), "group " + ", ".join(map(str, key)))
            rows.append([(k.item() if hasattr(k, "item") else k) for k in key] + [value])
        out = {"kind": "measure", "by": by, "rows": rows}
    else:
        shown = df[[c for c in df.columns if c != "id"]]
        result = fn(shown.copy(), dict(params))
        if isinstance(result, dict):
            out = {"kind": "echarts", "option": _bi_clean(_json.loads(_json.dumps(result, default=_bi_plain, allow_nan=False)))}
        elif isinstance(result, str) and result.lstrip().startswith("<svg"):
            low = result.lower()
            if any(x in low for x in ("<script", "javascript:", "<foreignobject", "onload=", "onerror=", "<iframe", "<image", "xlink:href")):
                raise ValueError("an SVG may not hold script, links or embedded content")
            out = {"kind": "svg", "svg": result}
        elif isinstance(result, (bytes, bytearray)) and bytes(result[:8]) == b"\x89PNG\r\n\x1a\n":
            out = {"kind": "png", "png": _b64.b64encode(bytes(result)).decode("ascii")}
        else:
            raise TypeError("a visual returns an ECharts option (dict), SVG text or PNG bytes, not " + type(result).__name__)
    print(_MARK + _json.dumps(out, default=_bi_plain, allow_nan=False))
