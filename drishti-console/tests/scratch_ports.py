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

"""Scratch ports for the browser tests that start their own server, console or host.

Under pytest-xdist every worker runs a different test at the same moment, so a port written as a bare number would be shared by two
workers: one starts the stack, the other stops it under it (ERR_CONNECTION_REFUSED half way through a file). Every port a test file starts
something on goes through ``worker_port``: each worker has a block of its own, ``STRIDE`` ports above the previous worker's (gw0 keeps the
number, gw1 adds 1250, gw2 2500, ...). Every base number lies in one window of ``STRIDE`` ports (``LOW`` to ``HIGH``), so no two
workers' ports can meet whatever the bases, for up to ``MAX_WORKERS`` workers; the last block ends below Linux's ephemeral ports (32768).
Each file has its own base numbers (tests/test_scratch_ports.py fails when two files write the same one, a base lies outside the window or
a port is written without the offset). docs/architecture/TEST_HARNESS.md, "Ports"."""
from __future__ import annotations

import os

WORKER = int((os.environ.get("PYTEST_XDIST_WORKER") or "gw0")[2:] or 0)
LOW, STRIDE = 17750, 1250
HIGH = LOW + STRIDE - 1
MAX_WORKERS = (32768 - LOW) // STRIDE          # 12: the browser lane's worker count is capped at it (tools/drillkit/drill_plan.py workers)


def worker_port(base) -> int:
    """The port for this pytest-xdist worker: the base number plus STRIDE per worker."""
    if WORKER >= MAX_WORKERS:
        raise RuntimeError(f"pytest worker gw{WORKER}: scratch ports allow {MAX_WORKERS} workers (scratch_ports.py)")
    return int(base) + STRIDE * WORKER


def worker_ports(*bases) -> tuple:
    """The ports for these bases; DRISHTI_SCRATCH_PORTS (comma-separated, e.g. 18781,17781,18782) replaces them, in order, for a run that must stay on
    fixed scratch ports (one file at a time then: every file gets the same ones)."""
    fixed = [int(x) for x in os.environ.get("DRISHTI_SCRATCH_PORTS", "").replace(" ", "").split(",") if x]
    if fixed:
        if len(fixed) < len(bases):
            raise ValueError(f"DRISHTI_SCRATCH_PORTS names {len(fixed)} ports; this file needs {len(bases)}")
        return tuple(fixed[:len(bases)])
    return tuple(worker_port(b) for b in bases)
