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

"""The measurements behind "Arrow where it pays" (2.9.7, docs/guides/DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6): one payload fetched both ways in the
browser, its bytes (raw and gzip -6, what a proxy in front of the console would send) and the browser's parse time (median of a few runs:
JSON.parse against reading the Arrow stream into the column-oriented table and putting the answer back). The click-to-painted times
are measured by each benchmark file on its own page. Results are printed and, when DRISHTI_ARROW_BENCH_OUT names a file, added to it."""
from __future__ import annotations

import gzip
import json
import os
import statistics

MEDIA = "application/vnd.apache.arrow.stream"

#: In the page: fetch the payload both ways (same URL, same body), then parse each `runs` times. Arrow: read the stream into the table
#: (drishtiTable.fromArrow) and rebuild the whole answer (what a page that reads rows would do); JSON: JSON.parse of the text.
PARSE = """async ([url, method, body, shape, at, runs]) => {
  const ask = (accept) => fetch(url, { method, credentials: 'same-origin', headers: Object.assign({ Accept: accept }, body ? { 'Content-Type': 'application/json' } : {}),
                                        body: body ? JSON.stringify(body) : undefined });
  await (await ask('application/json')).arrayBuffer(); await (await ask('%s')).arrayBuffer();   // warm: the server's cache, both ways
  const t0 = performance.now();
  const rj = await ask('application/json'); const jb = await rj.arrayBuffer();
  const t1 = performance.now();
  const ra = await ask('%s'); const ab = await ra.arrayBuffer();
  const t2 = performance.now();
  const kind = ra.headers.get('content-type') || '';
  const out = { jsonBytes: jb.byteLength, arrowBytes: ab.byteLength, arrowType: kind, jsonFetchMs: t1 - t0, arrowFetchMs: t2 - t1, json: [], arrow: [], arrowColumns: [] };
  const isArrow = kind.indexOf('%s') >= 0;               // a table the server sends as JSON (a boolean column) comes back as JSON
  for (let i = 0; i < runs; i++) {
    let s = performance.now(); const j = JSON.parse(new TextDecoder().decode(jb)); out.json.push(performance.now() - s);
    if (!isArrow) { continue; }
    s = performance.now(); const t = window.drishtiTable.fromArrow(ab); out.arrowColumns.push(performance.now() - s); t.answer(); out.arrow.push(performance.now() - s);
    void j;
  }
  return out;
}""" % (MEDIA, MEDIA, MEDIA)


def gz(data: bytes) -> int:
    return len(gzip.compress(data, 6))


def measure(page, url: str, method: str = "GET", body=None, shape: str = "rows", at: str = "rows", runs: int = 7) -> dict:
    """Bytes and parse times of one payload, both ways, from the page's own origin and session."""
    got = page.evaluate(PARSE, [url, method, body, shape, at, runs])
    headers = {"Content-Type": "application/json"} if body is not None else {}
    url = url if "://" in url else "/".join(page.url.split("/")[:3]) + url
    req = page.request.fetch(url, method=method, data=json.dumps(body) if body is not None else None, headers={**headers, "Accept": "application/json"})
    jraw = req.body()
    req = page.request.fetch(url, method=method, data=json.dumps(body) if body is not None else None, headers={**headers, "Accept": MEDIA})
    araw = req.body()
    return {"jsonBytes": got["jsonBytes"], "jsonGzip": gz(jraw), "arrowBytes": got["arrowBytes"], "arrowGzip": gz(araw), "arrowIsArrow": MEDIA in got["arrowType"],
            "jsonParseMs": round(statistics.median(got["json"]), 2), "arrowParseMs": round(statistics.median(got["arrow"]), 2) if got["arrow"] else None,
            "arrowColumnsMs": round(statistics.median(got["arrowColumns"]), 2) if got["arrowColumns"] else None,
            "jsonFetchMs": round(got["jsonFetchMs"], 1), "arrowFetchMs": round(got["arrowFetchMs"], 1)}


#: In the page: parse given bytes both ways (a payload the console no longer sends as Arrow, written as Arrow by core/arrow_answer.py).
PARSE_GIVEN = """([j64, a64, runs]) => {
  const bytes = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const jb = bytes(j64), ab = bytes(a64), out = { json: [], arrow: [] };
  for (let i = 0; i < runs; i++) {
    let s = performance.now(); JSON.parse(new TextDecoder().decode(jb)); out.json.push(performance.now() - s);
    s = performance.now(); window.drishtiTable.fromArrow(ab).answer(); out.arrow.push(performance.now() - s);
  }
  return out;
}"""


def measure_given(page, json_bytes: bytes, arrow_bytes: bytes, runs: int = 7) -> dict:
    """Bytes and parse times of a JSON answer and the same answer written as Arrow by the console's encoder."""
    import base64

    got = page.evaluate(PARSE_GIVEN, [base64.b64encode(json_bytes).decode(), base64.b64encode(arrow_bytes).decode(), runs])
    return {"jsonBytes": len(json_bytes), "jsonGzip": gz(json_bytes), "arrowBytes": len(arrow_bytes), "arrowGzip": gz(arrow_bytes),
            "jsonParseMs": round(statistics.median(got["json"]), 2), "arrowParseMs": round(statistics.median(got["arrow"]), 2)}


def record(name: str, result: dict) -> None:
    print(f"\n[arrow-bench] {name}: {json.dumps(result)}")
    out = os.environ.get("DRISHTI_ARROW_BENCH_OUT")
    if out:
        have = json.loads(open(out).read()) if os.path.exists(out) else {}
        have[name] = result
        with open(out, "w") as f:
            f.write(json.dumps(have, indent=1))
