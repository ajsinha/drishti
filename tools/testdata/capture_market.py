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

"""capture-market: capture a real snapshot of the news-and-markets pack's quotes and headlines, so the pack works with no internet.

    python3 tools/drishti.py testdata capture-market                      start a scratch server, capture, write data/testdata
    python3 tools/drishti.py testdata capture-market --server URL         use a server that already runs the live connectors
    python3 tools/drishti.py testdata capture-market --only quote --dry-run

Run it on a machine WITH internet. Every document is fetched through Drishti's own connectors (the yfinance and news plugins), so it has
exactly the shape the pack's Sutras read (UPPER_SNAKE): by default the tool starts a scratch server (the built exec jar; ports and
folders of its own under <data>/build/capture-market, removed at the end) with only the news-and-markets pack, the two live connectors
on (quote history over --range, default 1y of daily bars) and the demo lake off, then GETs /api/v1/entities/{kind}/~/raw for:

    quote   QUOTE-<ticker> of the twenty listed companies (and any QUOTE- id among the pack's console examples)
    news    NEWS-<name> and NEWS-<name>~all of the twenty companies and the fifteen topics (and the examples' NEWS- ids)

Politely: at least --delay seconds (default 2) between calls; a call that fails (429 Too Many Requests, a 5xx, a timeout) is tried again
after 5, 10, 20, 40 s. Each document is stamped as a snapshot (SNAPSHOT, SNAPSHOT_CAPTURED_AT, PROVENANCE.SNAPSHOT, and the quote's
AS_OF or the headlines' FETCHED_AT end in "(snapshot ...)"), then written, sorted by id, to

    data/testdata/news-and-markets/quote/snapshot-<UTC date>.jsonl.gz
    data/testdata/news-and-markets/news/snapshot-<UTC date>.jsonl.gz

(an older snapshot in those folders is replaced) and data/testdata/testdata.yaml lists them, so `drishti.py lake demo` builds the
news/quote and news/news tables the pack's news-store connector serves when the live connectors are off or the server runs with
DRISHTI_OFFLINE=true. The data belongs to its providers (Yahoo Finance, Google News and the publishers): shown for demonstration only.
Exit: 0 every document captured, 2 some could not be (the rest written), 1 nothing captured.
"""
from __future__ import annotations

import argparse
import glob
import gzip
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
if str(ROOT / "tools") not in sys.path:
    sys.path.append(str(ROOT / "tools"))
from javacmd import java_cmd  # noqa: E402
PACK = "news-and-markets"
CONNECTOR = "news-store"                     # the pack's Delta connector that serves the snapshot
KINDS = {"quote": ("QUOTE_ID", "Yahoo Finance (yfinance connector)"), "news": ("NEWS_ID", "Google News (news connector)")}
LIVE = {"quote": "yfinance", "news": "news"}
FORBIDDEN = {18480, 17480, 5432, 9092, 5672, 61616, 6379, 2181, 28480}
PLACEHOLDER = re.compile(r"\$\{[A-Za-z0-9_.-]+:([^{}]*)\}")


def data_dir() -> pathlib.Path:
    return pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))


# ---- what to capture -------------------------------------------------------------------------------------------------------------------

def targets(pack_dir: pathlib.Path) -> dict[str, list[str]]:
    """kind -> ids: the companies' quotes, the companies' and topics' headlines (filtered and ~all), and the console examples' ids."""
    import yaml
    out: dict[str, list[str]] = {"quote": [], "news": []}

    def add(kind: str, id_) -> None:
        if id_ and str(id_) not in out[kind]:
            out[kind].append(str(id_))
    for name in ("listed-company", "news-topic"):
        f = pack_dir / "samples" / f"{name}.jsonl"
        for line in f.read_text(encoding="utf-8").splitlines() if f.is_file() else []:
            if line.strip():
                d = json.loads(line)
                add("quote", d.get("QUOTE_ID"))
                add("news", d.get("NEWS_ID"))
                add("news", d.get("NEWS_ALL_ID"))
    spec = yaml.safe_load((pack_dir / "pack.yaml").read_text(encoding="utf-8"))
    for cmd, _what in (spec.get("console") or {}).get("examples") or []:
        for word in str(cmd).split():
            if word.startswith("QUOTE-"):
                add("quote", word)
            elif word.startswith("NEWS-"):
                add("news", word)
    return out


# ---- the snapshot stamp ------------------------------------------------------------------------------------------------------------------

def stamp(kind: str, doc: dict, captured: datetime) -> dict:
    """The document marked as a snapshot: when and from where, in fields the Sutras show (and the raw view keeps)."""
    when = captured.strftime("%Y-%m-%d %H:%M UTC")
    label = f"Snapshot captured {when} from {KINDS[kind][1].split(' (')[0]}"
    d = dict(doc)
    d["SNAPSHOT"] = label
    d["SNAPSHOT_CAPTURED_AT"] = captured.isoformat(timespec="seconds").replace("+00:00", "Z")
    prov = dict(d.get("PROVENANCE") or {}) if isinstance(d.get("PROVENANCE"), dict) else {}
    prov["SNAPSHOT"] = label + "; served from the demo lake (news-store), not live; the data belongs to its providers, shown for demonstration"
    d["PROVENANCE"] = prov
    field = "AS_OF" if kind == "quote" else "FETCHED_AT"
    mark = f"(snapshot captured {captured:%Y-%m-%d})"
    if mark not in str(d.get(field) or ""):
        d[field] = f"{d.get(field) or ''} {mark}".strip()
    return d


# ---- the server --------------------------------------------------------------------------------------------------------------------------

def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def resolve(value):
    """A pack template's value with its ${NAME:default} placeholders replaced by their defaults (innermost first)."""
    if not isinstance(value, str):
        return value
    prev = None
    while prev != value:
        prev, value = value, PLACEHOLDER.sub(lambda m: m.group(1), value)
    return value


class ScratchServer:
    """The built server jar with only the news-and-markets pack, its two live connectors on and the demo lake off, in a scratch folder."""

    def __init__(self, a, pack_dir: pathlib.Path):
        self.a, self.pack_dir = a, pack_dir
        self.work = data_dir() / "build" / "capture-market"
        self.proc = None
        self.url = f"http://127.0.0.1:{a.port}"

    def connector_files(self, folder: pathlib.Path) -> None:
        import yaml
        spec = yaml.safe_load((self.pack_dir / "pack.yaml").read_text(encoding="utf-8"))
        folder.mkdir(parents=True, exist_ok=True)
        for name in LIVE.values():
            c = spec["connectors"][name]
            settings = {k: resolve(v) for k, v in (c.get("settings") or {}).items()}
            if name == "yfinance":
                settings["range"] = self.a.range
            settings["cache-ttl-seconds"] = 3600
            body = {"plugin": c["plugin"], "enabled": True, "kinds": c.get("kinds") or [], "settings": settings}
            (folder / f"{name}.yaml").write_text("# written by capture-market (scratch)\n" + yaml.safe_dump(body, sort_keys=False, allow_unicode=True),
                                                 encoding="utf-8")

    def start(self) -> str:
        if self.a.port in FORBIDDEN or listening(self.a.port):
            raise SystemExit(f"capture-market: port {self.a.port} is in use or reserved; pass --port")
        jars = sorted(glob.glob(str(ROOT / "drishti-server" / "target" / "drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
        jar = self.a.jar or os.environ.get("DRISHTI_JAR") or (jars[0] if jars else None)
        java = shutil.which("java") if not os.environ.get("JAVA_HOME") else str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / "java")
        if not jar or not pathlib.Path(jar).is_file():
            raise SystemExit("capture-market: no server jar; build it (run configuration 'Drishti build server jar') or pass --jar, or --server URL")
        shutil.rmtree(self.work, ignore_errors=True)
        self.connector_files(self.work / "connectors")
        env = dict(os.environ, DRISHTI_PACKS=PACK, DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_DATA_DIR=str(self.work / "data"),
                   DRISHTI_CONNECTORS_DIR=str(self.work / "connectors"), DRISHTI_DEMO_ENABLED="false", DRISHTI_LAKE_ENABLED="false",
                   DRISHTI_OFFLINE="false", DRISHTI_SECURITY_ENABLED="false", DRISHTI_PORT=str(self.a.port))
        log = (self.work / "server.log").open("ab")
        self.proc = subprocess.Popen(java_cmd(java or "java", ["-Xmx768m", "-jar", jar, f"--server.port={self.a.port}"], cwd=self.work, env=env), cwd=self.work, env=env,
                                     stdout=log, stderr=subprocess.STDOUT)
        end = time.time() + 180
        while time.time() < end:
            if self.proc.poll() is not None:
                raise SystemExit(f"capture-market: the scratch server stopped; see {self.work / 'server.log'}")
            try:
                with urllib.request.urlopen(self.url + "/actuator/health", timeout=2):
                    return self.url
            except urllib.error.HTTPError:
                return self.url
            except (urllib.error.URLError, OSError):
                time.sleep(0.5)
        raise SystemExit("capture-market: the scratch server did not come up in 180 s")

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        shutil.rmtree(self.work, ignore_errors=True)
        try:
            self.work.parent.rmdir()                      # <data>/build, when nothing else is in it
        except OSError:
            pass


# ---- fetching ------------------------------------------------------------------------------------------------------------------------------

class Fetcher:
    """GET one document's raw form through the server, politely: --delay between calls, retries with backoff on 429, 5xx and timeouts."""

    BACKOFF = (5, 10, 20, 40)

    def __init__(self, server: str, delay: float, token: str | None = None, opener=None, sleep=time.sleep, say=print):
        self.server, self.delay, self.token = server.rstrip("/"), delay, token
        self.open = opener or (lambda req: urllib.request.urlopen(req, timeout=60))   # noqa: S310 - the operator's server
        self.sleep, self.say = sleep, say
        self.last = 0.0

    def _wait(self) -> None:
        gap = self.delay - (time.monotonic() - self.last)
        if gap > 0:
            self.sleep(gap)
        self.last = time.monotonic()

    def get(self, kind: str, id_: str) -> dict | None:
        url = f"{self.server}/api/v1/entities/{urllib.parse.quote(kind)}/~/raw?id={urllib.parse.quote(id_, safe='')}"
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        why = ""
        for attempt in range(len(self.BACKOFF) + 1):
            self._wait()
            try:
                with self.open(urllib.request.Request(url, headers=headers)) as r:
                    body = json.loads(r.read().decode("utf-8"))
                data = body.get("data") if isinstance(body, dict) else None
                if isinstance(data, dict) and data:
                    return data
                why = "an empty document"
            except urllib.error.HTTPError as e:
                text = e.read().decode("utf-8", "replace")[:300]
                e.close()
                why = f"{e.code} {text}"
                if e.code == 404 and "429" not in text:
                    return None                           # nothing to capture (an unknown ticker or name): not retried
            except (urllib.error.URLError, OSError, ValueError) as e:
                why = str(getattr(e, "reason", e))
            if attempt < len(self.BACKOFF):
                self.say(f"  {kind} {id_}: {why.strip()[:120]}; again in {self.BACKOFF[attempt]} s")
                self.sleep(self.BACKOFF[attempt])
        self.say(f"  {kind} {id_}: not captured ({why.strip()[:160]})")
        return None


# ---- writing -------------------------------------------------------------------------------------------------------------------------------

def write_snapshot(testdata: pathlib.Path, kind: str, docs: list[dict], captured: datetime) -> pathlib.Path:
    """<testdata>/news-and-markets/<kind>/snapshot-<date>.jsonl.gz, one document a line sorted by id; older snapshots there are removed."""
    folder = testdata / PACK / kind
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob("snapshot-*.jsonl.gz"):
        old.unlink()
    key = KINDS[kind][0]
    path = folder / f"snapshot-{captured:%Y-%m-%d}.jsonl.gz"
    lines = [json.dumps(d, ensure_ascii=False, separators=(",", ":"), sort_keys=False) for d in sorted(docs, key=lambda d: str(d.get(key)))]
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
        gz.write(("\n".join(lines) + "\n").encode("utf-8"))
    return path


def captured_datasets(testdata: pathlib.Path) -> list[dict]:
    """The manifest entries of the captured snapshots in <testdata>/news-and-markets/{quote,news} (newest snapshot of each)."""
    out = []
    for kind, (key, source) in KINDS.items():
        files = sorted((testdata / PACK / kind).glob("snapshot-*.jsonl.gz"))
        if not files:
            continue
        f = files[-1]
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            docs = [json.loads(line) for line in fh if line.strip()]
        when = next((d.get("SNAPSHOT_CAPTURED_AT") for d in docs if d.get("SNAPSHOT_CAPTURED_AT")), "")
        out.append({"folder": f"{PACK}/{kind}", "pack": PACK, "kind": kind, "connector": CONNECTOR, "key": key, "snapshot": f.name,
                    "captured": when, "source": source, "documents": len(docs), "bytes": f.stat().st_size})
    return out


def update_manifest(testdata: pathlib.Path) -> None:
    """testdata.yaml with the captured datasets in place of earlier entries for the same kinds (its header comment kept)."""
    import yaml
    path = testdata / "testdata.yaml"
    text = path.read_text(encoding="utf-8") if path.is_file() else ""      # a capture outside data/testdata starts its own manifest
    lines = text.splitlines()
    i = 0
    while i < len(lines) and (lines[i].startswith("#") or not lines[i].strip()):
        i += 1
    head = "\n".join(lines[:i]) + "\n" if i else ""
    m = yaml.safe_load(text) or {}
    keep = [ds for ds in m.get("datasets") or [] if not (ds.get("pack") == PACK and ds.get("kind") in KINDS)]
    m["datasets"] = keep + captured_datasets(testdata)
    path.write_text(head + yaml.safe_dump(m, sort_keys=False, width=140), encoding="utf-8")


# ---- the run -------------------------------------------------------------------------------------------------------------------------------

def run(a, say=print, sleep=time.sleep) -> int:
    a.delay = max(2.0, float(a.delay))                   # politeness is not optional
    pack_dir = pathlib.Path(a.packs) / PACK
    testdata = pathlib.Path(a.testdata)
    want = targets(pack_dir)
    for kind, ids in (getattr(a, "extra", None) or {}).items():        # tools/publicdata/news_and_markets.py --scope: more tickers and topics
        want[kind] += [i for i in ids if i not in want[kind]]
    kinds = [k for k in KINDS if not a.only or k in a.only.split(",")]
    total = sum(len(want[k]) for k in kinds)
    say(f"capture-market: {', '.join(f'{len(want[k])} {k}' for k in kinds)} ({total} calls, at least {a.delay:g} s apart)")
    if a.dry_run:
        for k in kinds:
            say(f"  {k}: " + " ".join(want[k]))
        return 0
    token = None
    if a.token_file:
        token = pathlib.Path(os.path.expanduser(a.token_file)).read_text(encoding="utf-8").strip()
    token = token or os.environ.get("DRISHTI_TOKEN")
    server = ScratchServer(a, pack_dir) if not a.server else None
    missing, written = [], []
    try:
        url = server.start() if server else a.server
        fetch = Fetcher(url, a.delay, token, sleep=sleep, say=say)
        captured = datetime.now(timezone.utc).replace(microsecond=0)
        for kind in kinds:
            docs = []
            for n, id_ in enumerate(want[kind], 1):
                d = fetch.get(kind, id_)
                if d is None:
                    missing.append(f"{kind} {id_}")
                    continue
                docs.append(stamp(kind, d, captured))
                if not a.quiet:
                    extra = len(d.get("HISTORY") or d.get("ITEMS") or [])
                    say(f"  [{n}/{len(want[kind])}] {kind} {id_}: {extra} {'bars' if kind == 'quote' else 'headlines'}")
            if docs:
                written.append(write_snapshot(testdata, kind, docs, captured))
        if written:
            update_manifest(testdata)
    finally:
        if server:
            server.stop()
    for f in written:
        say(f"wrote {f} ({f.stat().st_size / 1024:.0f} KB)")
    if missing:
        say(f"not captured ({len(missing)}): " + ", ".join(missing))
    if written:
        say("next: drishti.py lake demo (builds news/quote and news/news from the snapshot); commit data/testdata")
    return 1 if not written else (2 if missing else 0)


def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    p.add_argument("--server", help="a running server with the live connectors on (default: start a scratch server from the built jar)")
    p.add_argument("--jar", help="the server's exec jar for the scratch server (default DRISHTI_JAR, else the newest in drishti-server/target)")
    p.add_argument("--port", type=int, default=18977, help="the scratch server's port (default 18977)")
    p.add_argument("--range", default="1y", help="quote history for the scratch server's yfinance connector (default 1y of daily bars)")
    p.add_argument("--delay", type=float, default=2.0, help="seconds between calls, at least 2 (default 2)")
    p.add_argument("--only", metavar="quote|news", help="one kind only")
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="where (default data/testdata)")
    p.add_argument("--packs", default=str(ROOT / "config" / "packs"), help="the packs folder (default config/packs)")
    p.add_argument("--token-file", help="an API token for --server when it has security on (default DRISHTI_TOKEN); never printed")
    p.add_argument("--dry-run", action="store_true", help="list what would be captured; fetch nothing")
    p.add_argument("-q", "--quiet", action="store_true", help="only the summary")
    return p


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    return add_arguments(argparse.ArgumentParser(prog="testdata capture-market", description=__doc__.split("\n\n")[0], add_help=add_help,
                                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__.split("\n\n", 1)[1]))


def main(argv=None) -> int:
    return run(build_parser().parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
