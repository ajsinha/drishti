#!/usr/bin/env python3
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

"""Drishti's data in Python through Arrow Flight (docs/guides/FLIGHT_GUIDE.md): one file over ``pyarrow.flight``, read-only.

    from drishti_flight import Flight
    f = Flight("grpc+tls://drishti-1.bank.example:18815", token="drk_…", ca="bank-ca.pem")   # a token with the scope data:read
    trades = f.kind("trade", paths=["TRADE_ID", "DESK", "MTM"], where=["DESK = RATES"])      # a pyarrow.Table
    df = trades.to_pandas()                                                                   # or polars.from_arrow(trades)
    pnl = f.measure("var.scenarios", "DESK:RATES", date="2026-10-30")                         # SCENARIO, SCENARIO_DATE, PNL
    parts = f.measure("var.parts", "DESK:RATES", by="position")                               # one P&L vector per position (float32)
    risk = f.model("trades-basic", {"select": {"fields": ["trade.DESK"], "measures": ["MTM"]}})
    cells = f.pivot({"pivot": 1, "source": "trade", "rows": ["DESK"], "values": [{"measure": "sum(MTM)"}]})
    for seq, batch in f.stream(f.request("measure", "measure('var.scenarios', 'DESK:RATES')")):   # each intraday batch, as it lands
        ...

    python3 drishti_flight.py --url grpc+tls://host:18815 --token drk_… kind trade --paths TRADE_ID,DESK,MTM > trades.csv

A request is the same Rachana request the REST API takes (the reference: docs/guides/FLIGHT_GUIDE.md). ``read`` asks the service for the
plan (GetFlightInfo), then reads every part from the node that holds it, in parallel, trying the next node of a part when one is down.
Every read is as the token's person (or the person an application acts for, ``on_behalf_of``), with their entitlements and masks, and is
audited. A refusal raises ``FlightRefused`` with Drishti's code (``DRS-5604`` …).
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import sys
from typing import Any, Iterator

FORMS = ("kind", "model", "pivot", "measure", "lake")


class FlightRefused(Exception):
    """The service said no: ``code`` (DRS-…) and the words."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}" if code else detail)
        self.code, self.detail = code, detail


def _refusal(e: Exception) -> FlightRefused:
    text = str(e)
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("DRS-") or ": DRS-" in line:
            at = line.index("DRS-")
            code = line[at:at + 8]
            return FlightRefused(code, line[at + 10:].strip() if len(line) > at + 10 else line)
    return FlightRefused("", text)


class Flight:
    """A read-only client of Drishti's Flight service.

    ``url``: ``grpc+tls://host:18815`` (else ``DRISHTI_FLIGHT_URL``); ``token``: a personal API token with ``data:read`` (else
    ``DRISHTI_TOKEN``); ``ca``: the PEM file of the CA that signed the server's certificate (else ``DRISHTI_FLIGHT_CA``; none: the
    system's trust store); ``on_behalf_of``: the person an application acts for (an application's token only); ``cert``/``key``: a
    client certificate when the service asks for one (mutual TLS); ``parallel``: parts read at once.
    """

    def __init__(self, url: str | None = None, token: str | None = None, ca: str | None = None, on_behalf_of: str | None = None,
                 cert: str | None = None, key: str | None = None, parallel: int = 4, timeout: float | None = None):
        import pyarrow.flight as fl

        self.url = url or os.environ.get("DRISHTI_FLIGHT_URL") or ""
        if not self.url:
            raise ValueError("give the Flight address: grpc+tls://host:18815 (or DRISHTI_FLIGHT_URL)")
        if not self.url.startswith("grpc+tls://"):
            raise ValueError("The Flight service is TLS only: the address starts with grpc+tls://")
        self.token = token or os.environ.get("DRISHTI_TOKEN") or ""
        if not self.token:
            raise ValueError("give an API token with the scope data:read (or DRISHTI_TOKEN)")
        ca = ca or os.environ.get("DRISHTI_FLIGHT_CA")
        self._tls = {}
        if ca:
            with open(ca, "rb") as fh:
                self._tls["tls_root_certs"] = fh.read()
        if cert and key:
            with open(cert, "rb") as fh:
                self._tls["cert_chain"] = fh.read()
            with open(key, "rb") as fh:
                self._tls["private_key"] = fh.read()
        self.on_behalf_of = on_behalf_of
        self.parallel = max(1, parallel)
        self.timeout = timeout
        self._clients: dict[str, Any] = {}
        self._sessions: dict[str, Any] = {}
        self._fl = fl

    # ---- connections ---------------------------------------------------------------------------------------------------------

    def _client(self, location: str):
        """One client per node, authenticated once: the token at the handshake, the session the service gives after it."""
        if location not in self._clients:
            c = self._fl.FlightClient(location, **self._tls)
            headers = [(b"x-drishti-on-behalf-of", self.on_behalf_of.encode())] if self.on_behalf_of else []
            try:
                pair = c.authenticate_basic_token("token", self.token, self._fl.FlightCallOptions(headers=headers, timeout=self.timeout))
            except self._fl.FlightError as e:
                raise _refusal(e) from None
            self._clients[location] = c
            self._sessions[location] = self._fl.FlightCallOptions(headers=[pair], timeout=self.timeout)
        return self._clients[location], self._sessions[location]

    def _forget(self, location: str):
        c = self._clients.pop(location, None)
        self._sessions.pop(location, None)
        if c is not None:
            try:
                c.close()
            except Exception:  # noqa: BLE001 - a node that is down may fail to close too
                pass

    def close(self):
        for c in self._clients.values():
            c.close()
        self._clients.clear()
        self._sessions.clear()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # ---- requests ------------------------------------------------------------------------------------------------------------

    @staticmethod
    def request(form: str, request: Any, stream: bool = False, resume: int | None = None) -> dict:
        """A request as the service reads it: ``{"form", "request"}`` (the reference lists every form)."""
        if form not in FORMS:
            raise ValueError(f"form is one of {', '.join(FORMS)}")
        r: dict = {"form": form, "request": request}
        if stream:
            r["stream"] = True
        if resume is not None:
            r["resume"] = int(resume)
        return r

    def plan(self, request: dict):
        """The plan: the schema, one endpoint per part with the nodes that hold it (a ``pyarrow.flight.FlightInfo``)."""
        c, o = self._client(self.url)
        try:
            return c.get_flight_info(self._fl.FlightDescriptor.for_command(json.dumps(request).encode()), o)
        except self._fl.FlightError as e:
            raise _refusal(e) from None

    def _read_endpoint(self, endpoint):
        """One part, from the first of its nodes that answers; the next node when one is down."""
        locations = [loc.uri.decode() if isinstance(loc.uri, bytes) else str(loc.uri) for loc in endpoint.locations] or [self.url]
        last: Exception | None = None
        for loc in locations:
            try:
                c, o = self._client(loc)
                return c.do_get(endpoint.ticket, o).read_all()
            except self._fl.FlightUnavailableError as e:
                last = e
                self._forget(loc)
                continue
            except FlightRefused as e:
                if e.code:                                  # the node answered and refused: a refusal, not a node down
                    raise
                last = e                                    # no Drishti code: the node could not be reached
                self._forget(loc)
                continue
            except self._fl.FlightError as e:
                r = _refusal(e)
                if r.code:
                    raise r from None
                last = e
                self._forget(loc)
                continue
        raise FlightRefused("DRS-5608", f"no node of this part answered: {', '.join(locations)} ({last})")

    def read(self, request: dict):
        """Every part of the request, read in parallel from the nodes that hold them, as one ``pyarrow.Table``."""
        import pyarrow as pa

        info = self.plan(request)
        if len(info.endpoints) <= 1:
            tables = [self._read_endpoint(e) for e in info.endpoints]
        else:
            with concurrent.futures.ThreadPoolExecutor(self.parallel) as pool:
                tables = list(pool.map(self._read_endpoint, info.endpoints))
        if not tables:
            return info.schema.empty_table()
        return tables[0] if len(tables) == 1 else pa.concat_tables(tables)

    def stream(self, request: dict, resume: int | None = None) -> Iterator[tuple[int, Any]]:
        """A live stream: ``(sequence, pyarrow.RecordBatch)`` as each batch lands. Keep the last sequence; after a break call again with
        ``resume=`` it (to this node or another holding the data) and nothing is missed or sent twice."""
        r = dict(request, stream=True)
        if resume is not None:
            r["resume"] = int(resume)
        c, o = self._client(self.url)
        try:
            reader = c.do_get(self._fl.Ticket(json.dumps(r).encode()), o)
            while True:
                try:
                    chunk = reader.read_chunk()
                except StopIteration:
                    return
                meta = chunk.app_metadata
                seq = int.from_bytes(meta.to_pybytes(), "little", signed=True) if meta is not None else -1
                yield seq, chunk.data
        except self._fl.FlightError as e:
            raise _refusal(e) from None

    # ---- the forms -----------------------------------------------------------------------------------------------------------

    def kind(self, kind: str, paths: list[str] | None = None, where: list[str] | None = None, date: str | None = None):
        """A kind's rows on a day (the latest when no date): the chosen paths, filtered by ``where`` lines (``DESK = RATES``)."""
        q: dict = {"kind": kind}
        if paths:
            q["paths"] = list(paths)
        if where:
            q["where"] = list(where)
        if date:
            q["date"] = date
        return self.read(self.request("kind", q))

    def model(self, model: str, query: dict | None = None):
        """A BI model query (MQ, BI_GUIDE.md): its keys ``select`` ({"fields": [...], "measures": [...]}), ``where``, ``sort``,
        ``limit``, ``time`` ...; ``mq: 1`` is added by the server."""
        return self.read(self.request("model", {"model": model, "query": query or {}}))

    def pivot(self, view: dict):
        """A pivot view's cells (the same JSON as POST /api/v1/pivot/query): ROW, COLUMN, each dimension, MEASURE, VALUE, MEMBERS."""
        return self.read(self.request("pivot", view))

    def measure(self, measure: str, scope: str, date: str | None = None, ccy: str | None = None, by: str | None = None):
        """A measure computed by its engine, exactly: ``var.scenarios`` (the scope's P&L vector) or ``var.parts`` (one vector per part)."""
        q: dict = {"measure": measure, "scope": scope}
        for k, v in (("date", date), ("ccy", ccy), ("by", by)):
            if v:
                q[k] = v
        return self.read(self.request("measure", q))

    def lake(self, lake: str, kind: str, paths: list[str] | None = None, where: list[str] | None = None, cursor: str | None = None):
        """A table of a lake (the lake configuration names it), from the node that holds it; ``cursor`` for a live stream of appends."""
        q: dict = {"lake": lake, "kind": kind}
        if paths:
            q["paths"] = list(paths)
        if where:
            q["where"] = list(where)
        if cursor:
            q["cursor"] = cursor
        return self.read(self.request("lake", q))

    def catalogue(self) -> list[dict]:
        """What this token may read: one request per kind, model, measure and lake table (names only)."""
        c, o = self._client(self.url)
        try:
            return [json.loads(i.descriptor.command) for i in c.list_flights(options=o)]
        except self._fl.FlightError as e:
            raise _refusal(e) from None

    def whoami(self) -> dict:
        """The person, the person acted for, the token, the roles, scopes and limits."""
        return self._action("whoami")

    def health(self) -> dict:
        """The service on the node asked: Arrow memory in use and its cap, live reads."""
        return self._action("health")

    def _action(self, name: str) -> dict:
        c, o = self._client(self.url)
        try:
            return json.loads(next(iter(c.do_action(self._fl.Action(name, b""), o))).body.to_pybytes())
        except self._fl.FlightError as e:
            raise _refusal(e) from None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="drishti_flight.py", description="Read Drishti's data through Arrow Flight; CSV to stdout.")
    ap.add_argument("--url", default=os.environ.get("DRISHTI_FLIGHT_URL"))
    ap.add_argument("--token", default=os.environ.get("DRISHTI_TOKEN"))
    ap.add_argument("--ca", default=os.environ.get("DRISHTI_FLIGHT_CA"))
    ap.add_argument("--on-behalf-of")
    ap.add_argument("--out", help="a file: .csv, .parquet or .arrow (default: CSV to stdout)")
    sub = ap.add_subparsers(dest="what", required=True)
    k = sub.add_parser("kind")
    k.add_argument("kind")
    k.add_argument("--paths")
    k.add_argument("--where", action="append")
    k.add_argument("--date")
    m = sub.add_parser("measure")
    m.add_argument("measure")
    m.add_argument("scope")
    m.add_argument("--date")
    m.add_argument("--by")
    r = sub.add_parser("request", help="a request as JSON: {\"form\": ..., \"request\": ...}")
    r.add_argument("json")
    sub.add_parser("catalogue")
    sub.add_parser("whoami")
    a = ap.parse_args(argv)
    try:
        with Flight(a.url, a.token, a.ca, a.on_behalf_of) as f:
            if a.what == "catalogue":
                print(json.dumps(f.catalogue(), indent=2))
                return 0
            if a.what == "whoami":
                print(json.dumps(f.whoami(), indent=2))
                return 0
            if a.what == "kind":
                t = f.kind(a.kind, a.paths.split(",") if a.paths else None, a.where, a.date)
            elif a.what == "measure":
                t = f.measure(a.measure, a.scope, a.date, by=a.by)
            else:
                t = f.read(json.loads(a.json))
    except FlightRefused as e:
        print(str(e), file=sys.stderr)
        return 2
    _write(t, a.out)
    return 0


def _write(table, out: str | None):
    import pyarrow.csv as pacsv

    if not out:
        pacsv.write_csv(table, sys.stdout.buffer)
    elif out.endswith(".parquet"):
        import pyarrow.parquet as pq
        pq.write_table(table, out)
    elif out.endswith(".arrow"):
        import pyarrow as pa
        with pa.OSFile(out, "wb") as fh, pa.ipc.new_stream(fh, table.schema) as w:
            w.write_table(table)
    else:
        pacsv.write_csv(table, out)


if __name__ == "__main__":
    sys.exit(main())
