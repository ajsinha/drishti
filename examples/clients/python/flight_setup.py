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

"""The few lines every client example starts with (examples/clients/README.md): find the one-file Flight client, read the address,
the token and the CA from the environment, and connect. Read only: nothing here, or in any example, writes to the server.

    DRISHTI_FLIGHT_URL   grpc+tls://<a node's real address>:18815
    DRISHTI_TOKEN        an API token with the scope data:read (or DRISHTI_TOKEN_FILE: a file holding it, first line)
    DRISHTI_FLIGHT_CA    the PEM file of the CA that signed the server's certificate (none: the system's trust store)

A token is never written in a notebook or a script: it comes from the environment or a file only you can read.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]                                      # examples/clients/python -> the repository
CLIENT = REPO / "clients" / "python"                        # drishti_flight.py, the one-file client
if str(CLIENT) not in sys.path:
    sys.path.insert(0, str(CLIENT))

from drishti_flight import Flight, FlightRefused  # noqa: E402  (after the path above)

# What the examples read on the demo lake; each may be changed through the environment without editing a notebook.
SCOPE = os.environ.get("DRISHTI_EXAMPLE_SCOPE", "DESK:RATES")          # a VaR scope: a desk of the bank's hierarchy
DATE = os.environ.get("DRISHTI_EXAMPLE_DATE") or None                   # a business date; none: the latest
STREAM_WAIT = float(os.environ.get("DRISHTI_EXAMPLE_STREAM_WAIT", "30"))  # seconds a live-stream example waits for a new batch


def token() -> str:
    """The API token: DRISHTI_TOKEN, else the first line of the file DRISHTI_TOKEN_FILE names."""
    value = os.environ.get("DRISHTI_TOKEN", "").strip()
    if value:
        return value
    file = os.environ.get("DRISHTI_TOKEN_FILE", "").strip()
    if file:
        return Path(file).expanduser().read_text(encoding="utf-8").splitlines()[0].strip()
    raise SystemExit("Set DRISHTI_TOKEN (or DRISHTI_TOKEN_FILE) to an API token with the scope data:read "
                     "(My account -> API tokens).")


def connect(timeout: float | None = None) -> Flight:
    """A read-only client for the address in DRISHTI_FLIGHT_URL, as the token's person."""
    url = os.environ.get("DRISHTI_FLIGHT_URL", "").strip()
    if not url:
        raise SystemExit("Set DRISHTI_FLIGHT_URL to grpc+tls://<a node's real address>:18815 (Admin -> Data out shows it).")
    return Flight(url, token=token(), ca=os.environ.get("DRISHTI_FLIGHT_CA") or None, timeout=timeout)


def meta(table, key: str, default: str | None = None) -> str | None:
    """One entry of a table's schema metadata (drishti.var, drishti.es, drishti.date ...), as text."""
    m = table.schema.metadata or {}
    v = m.get(key.encode())
    return v.decode() if v is not None else default


__all__ = ["Flight", "FlightRefused", "REPO", "SCOPE", "DATE", "STREAM_WAIT", "token", "connect", "meta"]
