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

"""A real scratch stack for the news-and-markets sample pack: the built server jar on :18993 with both on-demand connectors switched on and
pointed at two local fakes (a Google News RSS and a Yahoo chart endpoint, canned here), and the console on :17993. Nothing ever reaches Google or Yahoo.
Everything lives in a scratch directory; ports that are already in use are never touched (the stack refuses to start)."""
from __future__ import annotations

import glob
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from java_launch import java_cmd

ROOT = Path(__file__).resolve().parents[2]
CONSOLE = ROOT / "drishti-console"
# Each pytest-xdist worker collects every test file first, so the first file's setdefault of the ports wins on every worker:
# without an offset two workers would start (or reuse) one server on the same ports. gw0 keeps the ports, gw1 adds 100, …
_WORKER = int((os.environ.get("PYTEST_XDIST_WORKER") or "gw0")[2:] or 0)
SERVER_PORT = int(os.environ.get("DRISHTI_NEWS_SERVER_PORT", 18993)) + 100 * _WORKER
CONSOLE_PORT = int(os.environ.get("DRISHTI_NEWS_CONSOLE_PORT", 17993)) + 100 * _WORKER
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
FORBIDDEN = {5432, 9092, 5672, 61616, 6379, 2181, 18480, 17480}


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def wait(url: str, seconds: float = 150) -> None:
    end = time.time() + seconds
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2).read()
            return
        except urllib.error.HTTPError:
            return
        except Exception:  # noqa: BLE001 - not up yet
            time.sleep(0.4)
    raise RuntimeError(f"{url} did not come up")


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def rss(name: str) -> bytes:
    """A canned Google News answer for ``name``: three headlines that name it, one that does not, and a duplicate."""
    def item(title, link, date, source):
        return (f"<item><title>{_esc(title)} - {_esc(source)}</title><link>{link}</link><pubDate>{date}</pubDate><description>{_esc(title)}</description>"
                f"<source url=\"https://{source.lower().replace(' ', '-')}.example\">{_esc(source)}</source></item>")
    items = [item(f"{name} announces record quarter", "https://news.example/a1", "Fri, 09 Oct 2026 18:45:00 GMT", "Reuters"),
             item(f"{name} announces record quarter", "https://news.example/a1", "Fri, 09 Oct 2026 18:45:00 GMT", "Reuters"),
             item(f"Why {name} shares moved on Thursday", "https://news.example/b1", "Thu, 08 Oct 2026 07:15:00 GMT", "Bloomberg"),
             item(f"Analysts react to {name} guidance", "https://news.example/c1", "Wed, 07 Oct 2026 12:00:00 GMT", "Financial Times"),
             item("Markets close mixed ahead of the weekend", "https://news.example/d1", "Tue, 06 Oct 2026 08:30:00 GMT", "The Guardian")]
    return f'<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>{_esc(name)} - Google News</title>{"".join(items)}</channel></rss>'.encode()


def chart(symbol: str) -> bytes:
    d = 1_790_000_000
    closes = [182.0 + i * 0.7 + (i % 3) for i in range(30)]
    doc = {"chart": {"result": [{"meta": {"currency": "USD", "symbol": symbol, "exchangeName": "NMS", "fullExchangeName": "NasdaqGS", "instrumentType": "EQUITY",
                                           "regularMarketTime": d + 29 * 86400, "gmtoffset": -14400, "exchangeTimezoneName": "America/New_York",
                                           "regularMarketPrice": closes[-1], "regularMarketDayHigh": closes[-1] + 1.1, "regularMarketDayLow": closes[-1] - 2.4,
                                           "regularMarketVolume": 51234567, "fiftyTwoWeekHigh": 230.5, "fiftyTwoWeekLow": 140.1, "longName": f"{symbol} Corporation"},
                                  "timestamp": [d + i * 86400 for i in range(30)],
                                  "indicators": {"quote": [{"open": closes, "high": [c + 1 for c in closes], "low": [c - 1 for c in closes], "close": closes,
                                                            "volume": [1000 * (i + 1) for i in range(30)]}]}}], "error": None}}
    return json.dumps(doc).encode()


class _Fake(BaseHTTPRequestHandler):
    requests: list[str] = []          # every request the fakes answered (offline mode must leave this empty)

    def log_message(self, *args):  # noqa: D401 - quiet
        pass

    def do_GET(self):  # noqa: N802
        _Fake.requests.append(self.path)
        url = urllib.parse.urlparse(self.path)
        if url.path.startswith("/rss/search"):
            q = urllib.parse.parse_qs(url.query).get("q", [""])[0]
            body, ctype = rss(q), "application/rss+xml"
        elif url.path.startswith("/v8/finance/chart/"):
            symbol = url.path.rsplit("/", 1)[1]
            body, ctype = chart(symbol), "application/json"
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Stack:
    def __init__(self, tmp: Path, connectors_on: bool = True, offline: bool = False):
        """``offline``: DRISHTI_OFFLINE=true over a demo lake of the news-and-markets pack built from the committed snapshot
        (``drishti.py lake demo --only news-and-markets``), with the live connectors still switched on in their files."""
        self.tmp, self.procs, self.connectors_on, self.offline, self.fake = tmp, {}, connectors_on, offline, None

    def start(self) -> "Stack":
        for port in (SERVER_PORT, CONSOLE_PORT):
            assert port not in FORBIDDEN and not listening(port), f"port {port} is in use: stop whatever listens there"
        jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)  # newest build first: target may hold older versions
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            raise FileNotFoundError("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        _Fake.requests = []
        self.fake = ThreadingHTTPServer(("127.0.0.1", 0), _Fake)
        threading.Thread(target=self.fake.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{self.fake.server_address[1]}"
        work = self.tmp / "server"
        work.mkdir(parents=True)
        on = "true" if self.connectors_on else "false"
        env = dict(os.environ, DRISHTI_PACKS="news-and-markets", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_PACKS_SAMPLES="visible",
                   DRISHTI_NEWS_ENABLED=on, DRISHTI_NEWS_URL=base + "/rss/search", DRISHTI_YFINANCE_ENABLED=on, DRISHTI_YFINANCE_URL=base + "/v8/finance/chart",
                   DRISHTI_CONNECTORS_DIR=str(work / "connectors"), DRISHTI_GRAPH_LINK_BUDGET="3s")
        if self.offline:
            lake = self.tmp / "delta"
            built = subprocess.run([sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "demo", "--only", "news-and-markets", "--lake", str(lake),
                                    "--as-of", "today", "--connectors-dir", str(work / "connectors"), "--no-signal", "-q"],
                                   env=dict(os.environ, DRISHTI_DATA_DIR=str(self.tmp / "data")), capture_output=True, text=True, timeout=600)
            if built.returncode:
                raise RuntimeError("lake demo failed: " + built.stderr[-2000:])
            env.update(DRISHTI_OFFLINE="true", DRISHTI_DELTA_ROOT=str(lake), DRISHTI_DATA_DIR=str(work / "data"))
        self.procs["server"] = subprocess.Popen(java_cmd(java, ["-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}"], tmp=self.tmp / "jvm-tmp"), cwd=work, env=env,
                                                stdout=open(self.tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        wait(SERVER + "/actuator/health")
        cenv = dict(os.environ)
        self.procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"], env=cenv,
                                                 stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
        wait(CONSOLE_URL + "/healthz")
        return self

    def close(self) -> None:
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
        if self.fake:
            self.fake.shutdown()


if __name__ == "__main__":      # python news_stack.py: start the stack, print its addresses, stop on Enter
    import tempfile

    stack = Stack(Path(tempfile.mkdtemp(prefix="news-stack-")), connectors_on="off" not in sys.argv).start()
    print(CONSOLE_URL, SERVER)
    try:
        input()
    finally:
        stack.close()
