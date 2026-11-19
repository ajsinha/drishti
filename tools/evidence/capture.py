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

"""Captures a baseline (or a later state) of a running Drishti server for the migration evidence (I7B_PRODUCTS_PLAN 3.3, C0).

    python3 tools/evidence/capture.py --server http://127.0.0.1:19183 --out evidence/i7a --label v2.9.11.i_7a [--console URL] [--commit SHA] [--jar FILE]
    python3 tools/evidence/capture.py --home CHECKOUT --jar JAR --data DIR --port 19183 --out ...   # starts a scratch server itself

What is written under --out (nothing in it depends on the clock, except the page timings, which are compared by threshold):
  sutras.json           the Sutras the server serves (name, kind, versions, domain)
  viewmodels/<name>.json  each Sutra's ViewModel for the first entity of its kind (EV1), without timings and provenance times
  frames/<name>.json    the first events of the live stream of a few views (EV1, the frames)
  answers/<n>.json      the answers of fixed API paths (EV3)
  figures.json          the VaR and ES answers (EV4)
  pages.json            the console pages: status, bytes, median time (EV5, EV10), when --console is given
  manifest.json         the label, the commits, the jar's SHA-256, the counts (never compared)
Never run it against the owner's stack: the ports 18480, 18791, 18792 and 17480 are refused."""
from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
from scratch import FORBIDDEN, ScratchServer, listening  # noqa: E402

#: The fixed API paths whose answers are recorded (EV3); a path the server does not have is recorded with its status.
API_PATHS = ["/api/v1/modules", "/api/v1/sutras", "/api/v1/rachana/schema", "/api/v1/rachana/blocks", "/api/v1/viewmodel/schema", "/api/v1/functions",
             "/api/v1/ui-kit", "/api/v1/packs", "/api/v1/search?q=trade&limit=5"]
#: The console pages whose load is measured (EV5, EV10).
PAGES = ["/", "/healthz", "/go?q=TRD%20%3CGO%3E", "/pivot", "/help", "/admin"]
#: The figures of the VaR engine (EV4): the pivot query of the 1-day VaR and ES, by desk, on the demo bank's business date.
FIGURES = [{"name": "var-es-by-desk", "view": {"source": "var", "rows": ["DESK"], "values": ["var()", "es()"], "asOf": "2026-09-30"}}]
STREAMED = 5          # how many views have their stream's first events recorded
STREAM_SECONDS = 3.0


def write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(common.normalise(value), indent=1, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")


def sample_entities(server: str, kinds) -> dict:
    out = {}
    for kind in sorted(kinds):
        s, d = common.get_json(f"{server}/api/v1/search?q={urllib.parse.quote(kind)}&limit=1")
        rows = (d or {}).get("rows") if isinstance(d, dict) else None
        if s == 200 and rows:
            out[kind] = rows[0]["ref"]["id"]
    return out


def stream_events(url: str, seconds: float) -> list:
    """The events of a Server-Sent Events stream for a few seconds: [(name, SHA-256 of the data without volatile keys)]."""
    import hashlib
    events, name, end = [], None, time.time() + seconds
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "text/event-stream"}), timeout=seconds + 2) as r:
            for raw in r:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line.startswith("event:"):
                    name = line[6:].strip()
                elif line.startswith("data:") and name:
                    try:
                        data = common.canonical(common.normalise(json.loads(line[5:])))
                    except ValueError:
                        data = line[5:]
                    events.append({"event": name, "sha256": hashlib.sha256(data.encode()).hexdigest()})
                    name = None
                if time.time() > end or len(events) >= 4:
                    break
    except Exception as e:  # noqa: BLE001 - a stream that cannot be read is itself a recorded fact
        events.append({"error": type(e).__name__})
    return events


def capture(server: str, out: Path, console: str | None, label: str, commit: str | None, jar: Path | None) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    status, sutras = common.get_json(server + "/api/v1/sutras")
    assert status == 200 and isinstance(sutras, list), f"/api/v1/sutras answered {status}"
    write(out / "sutras.json", sutras)
    entities = sample_entities(server, {s["kind"] for s in sutras if s.get("kind")})
    counts = {"sutras": len(sutras), "viewmodels": 0, "noEntity": 0, "frames": 0, "answers": 0, "pages": 0}
    streamed = 0
    for s in sutras:
        name, kind = s["name"], s.get("kind")
        ident = entities.get(kind)
        if not ident:
            write(out / "viewmodels" / f"{name}.json", {"sutra": name, "kind": kind, "noEntity": True})
            counts["noEntity"] += 1
            continue
        q = f"?sutra={urllib.parse.quote(name)}"
        st, vm = common.get_json(f"{server}/api/v1/views/{urllib.parse.quote(kind)}/{urllib.parse.quote(ident)}{q}")
        write(out / "viewmodels" / f"{name}.json", {"sutra": name, "kind": kind, "entity": ident, "status": st, "viewModel": vm})
        counts["viewmodels"] += 1
        if streamed < STREAMED and st == 200:
            ev = stream_events(f"{server}/api/v1/views/{urllib.parse.quote(kind)}/{urllib.parse.quote(ident)}/stream{q}", STREAM_SECONDS)
            write(out / "frames" / f"{name}.json", {"sutra": name, "events": ev})
            streamed += 1
            counts["frames"] += 1
    for i, path in enumerate(API_PATHS):
        st, body = common.get_json(server + path)
        write(out / "answers" / f"{i:02d}-{path.strip('/').replace('/', '-').replace('?', '-')[:60]}.json", {"path": path, "status": st, "body": body})
        counts["answers"] += 1
    figures = []
    for f in FIGURES:
        req = urllib.request.Request(server + "/api/v1/pivot/query", json.dumps({"view": f["view"]}).encode(), {"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                st, body = r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            st, body = e.code, json.loads(e.read() or b"null")
        figures.append({"name": f["name"], "status": st, "body": body})
    write(out / "figures.json", figures)
    if console:
        pages = []
        for p in PAGES:
            times, last = [], (0, b"")
            for _ in range(3):
                code, body, secs = common.get(console + p)
                times.append(secs)
                last = (code, body)
            pages.append({"path": p, "status": last[0], "bytes": len(last[1]), "seconds": round(statistics.median(times), 4)})
        write(out / "pages.json", pages)
        counts["pages"] = len(pages)
    manifest = {"label": label, "commit": commit or git_head(), "jar": jar.name if jar else None, "jarSha256": common.sha256_of(jar) if jar else None, "counts": counts}
    write(out / "manifest.json", manifest)
    return manifest


def git_head() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:  # noqa: BLE001
        return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--server", help="a running server's URL (the scratch one you started)")
    ap.add_argument("--home"); ap.add_argument("--jar"); ap.add_argument("--data"); ap.add_argument("--port", type=int)
    ap.add_argument("--console", help="a running console's URL, for the page figures")
    ap.add_argument("--console-python", default=sys.executable, help="the Python with the console's requirements (default: this one)")
    ap.add_argument("--console-port", type=int, help="with --home: start the console of that install on this port beside the scratch server")
    ap.add_argument("--out", required=True)
    ap.add_argument("--tar", help="also write the capture as one reproducible .tar.gz (the committed baseline)")
    ap.add_argument("--label", required=True)
    ap.add_argument("--commit")
    a = ap.parse_args(argv)
    jar = Path(a.jar) if a.jar else None
    ports = [int(u.rsplit(":", 1)[1].split("/")[0]) for u in (a.server, a.console) if u and u.rsplit(":", 1)[-1].split("/")[0].isdigit()]
    if any(p in FORBIDDEN for p in ports):
        print("refused: that port belongs to a running stack", file=sys.stderr)
        return 2
    if a.server:
        m = capture(a.server, Path(a.out), a.console, a.label, a.commit, jar)
    else:
        if not (a.home and a.jar and a.data and a.port):
            ap.error("give --server, or --home --jar --data --port")
        with ScratchServer(Path(a.home), jar, Path(a.data), a.port) as s:
            console_url, proc = a.console, None
            if a.console_port:
                assert a.console_port not in FORBIDDEN and not listening(a.console_port), f"port {a.console_port} is in use"
                env = dict(os.environ, DRISHTI_AUTH_ENABLED="false")
                proc = subprocess.Popen([a.console_python, str(Path(a.home) / "drishti-console" / "run_drishti_web.py"), f"--server.port={a.console_port}", f"--backend.url={s.url}"],
                                        env=env, cwd=a.home, stdout=open(Path(a.data) / "console.log", "ab"), stderr=subprocess.STDOUT)
                console_url = f"http://127.0.0.1:{a.console_port}"
                end = time.time() + 120
                while time.time() < end and not listening(a.console_port):
                    time.sleep(0.5)
            try:
                m = capture(s.url, Path(a.out), console_url, a.label, a.commit or git_head(), jar)
            finally:
                if proc:
                    proc.terminate()
    if a.tar:
        common.pack(Path(a.out), Path(a.tar))
    print(json.dumps(m["counts"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
