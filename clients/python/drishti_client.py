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

"""A small Drishti client for scripts and notebooks: Python's standard library only, read-only.

    from drishti_client import Drishti
    d = Drishti("https://drishti.bank.example:18480", token="drk_…")   # a token from My account → API tokens
    d.field("trade", "T-10001", "mtm")                                   # 1875863.0
    d.document("trade", "T-10001")["legs"][0]["rate"]
    rows = d.search("TRD productType=Revolver")                         # list of dicts: kind, id, title, then the columns
    d.search("TRD where notional > 250m order by mtm desc limit 20", as_of="2026-09-29")
    d.diff("trade", "T-10001", "2026-09-25", "2026-09-30")              # what changed between two business dates

    python3 drishti_client.py --url … --token … search "TRD productType=Revolver" > revolvers.csv

A token reads what its owner may read, and nothing else; every call is as that user.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


class DrishtiError(Exception):
    """The server said no: ``code`` (DRS-…), ``status`` (HTTP) and ``detail``."""

    def __init__(self, status: int, code: str, detail: str):
        super().__init__(f"{code} {detail}")
        self.status, self.code, self.detail = status, code, detail


class Drishti:
    def __init__(self, url: str | None = None, token: str | None = None, timeout: float = 30.0):
        self.url = (url or os.environ.get("DRISHTI_URL") or "http://localhost:18480").rstrip("/")
        self.token = token or os.environ.get("DRISHTI_TOKEN")
        self.timeout = timeout

    # ---- transport ------------------------------------------------------------------------------------------------
    def _get(self, path: str, as_of: str | None = None, known_at: str | None = None, raw: bool = False, **params):
        query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        req = urllib.request.Request(f"{self.url}/api/v1{path}" + (f"?{query}" if query else ""))
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        if as_of:
            req.add_header("X-Drishti-As-Of", as_of)                 # a business date, YYYY-MM-DD
        if known_at:
            req.add_header("X-Drishti-Known-At", known_at)          # as known at an instant, ISO-8601
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                body = r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            try:
                p = json.loads(e.read().decode("utf-8") or "{}")
            except ValueError:
                p = {}
            raise DrishtiError(e.code, p.get("code", f"HTTP-{e.code}"), p.get("detail", e.reason)) from None
        return body if raw else json.loads(body)

    # ---- reads --------------------------------------------------------------------------------------------------
    def document(self, kind: str, id_: str, as_of: str | None = None) -> dict:
        """The entity's document as its source holds it (fields you may not see are masked)."""
        return self._get(f"/entities/{urllib.parse.quote(kind)}/{urllib.parse.quote(id_)}/raw", as_of)["data"]

    def field(self, kind: str, id_: str, path: str, as_of: str | None = None):
        """One value by path: "mtm", "counterparty.name", "legs[0].rate"."""
        node = self.document(kind, id_, as_of)
        for part in path.replace("]", "").replace("[", ".").split("."):
            if part == "":
                continue
            node = node[int(part)] if isinstance(node, list) else (node or {}).get(part)
        return node

    def view(self, kind: str, id_: str, as_of: str | None = None) -> dict:
        """The view model: strip, panels and provenance, as the console shows them."""
        return self._get(f"/views/{urllib.parse.quote(kind)}/{urllib.parse.quote(id_)}", as_of)

    def search(self, query: str, as_of: str | None = None) -> list[dict]:
        """A pick list or search (TRD T-100, TRD productType=Revolver, TRD where …): one dict per entity."""
        r = self._get("/search", as_of, q=query)
        labels = r.get("labels", {})
        return [{"kind": row["ref"]["kind"], "id": row["ref"]["id"], "title": row.get("title"),
                 **{labels.get(c, c): row["values"].get(c) for c in r.get("columns", [])}} for row in r.get("rows", [])]

    def search_csv(self, query: str, as_of: str | None = None) -> str:
        return self._get("/search/csv", as_of, raw=True, q=query)

    def diff(self, kind: str, id_: str, date_from: str, date_to: str) -> dict:
        """What changed in an entity between two business dates."""
        return self._get(f"/history/{urllib.parse.quote(kind)}/{urllib.parse.quote(id_)}/diff", None, **{"from": date_from, "to": date_to})

    def to_pandas(self, query: str, as_of: str | None = None):
        """The search as a pandas DataFrame (needs pandas)."""
        import pandas as pd  # noqa: PLC0415 - optional

        return pd.DataFrame(self.search(query, as_of))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Read from a Drishti server with a personal API token.")
    ap.add_argument("--url", default=os.environ.get("DRISHTI_URL"))
    ap.add_argument("--token", default=os.environ.get("DRISHTI_TOKEN"), help="or DRISHTI_TOKEN")
    ap.add_argument("--as-of", help="business date, YYYY-MM-DD")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("search", help="a search or pick list, as CSV on stdout")
    s.add_argument("query")
    f = sub.add_parser("field", help="one value")
    f.add_argument("kind")
    f.add_argument("id")
    f.add_argument("path")
    g = sub.add_parser("document", help="an entity's document as JSON")
    g.add_argument("kind")
    g.add_argument("id")
    a = ap.parse_args(argv)
    d = Drishti(a.url, a.token)
    try:
        if a.cmd == "search":
            rows = d.search(a.query, a.as_of)
            if rows:
                w = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]))
                w.writeheader()
                w.writerows(rows)
        elif a.cmd == "field":
            print(json.dumps(d.field(a.kind, a.id, a.path, a.as_of)))
        else:
            print(json.dumps(d.document(a.kind, a.id, a.as_of), indent=2))
    except DrishtiError as e:
        print(f"{e.code} {e.detail}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
