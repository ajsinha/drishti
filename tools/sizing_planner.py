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

"""The single-node sizing planner workbook (RESOURCES_AND_SCALE.md, Sizing a single node: the planner workbook).

    python3 tools/sizing_planner.py                 writes docs/admin/sizing/Drishti-Sizing-Planner.xlsx
    python3 tools/sizing_planner.py --out DIR       writes the workbook into DIR instead
    python3 tools/sizing_planner.py --check         exit 1 when the committed workbook differs from a fresh build

The workbook has four tabs: Inputs (the yellow cells a planner types: positions, dates in memory, users, the Java version...),
Results (every heap, page-cache, RAM, disk and CPU figure, the settings with their keys, the JVM arguments and the warnings, each a
real Excel formula over named cells), Coefficients (every constant with its unit, source and status: measured, formula, calibrated
or estimate; Results never hard-code one) and Explain (plain words). Standard library only: the parts are written as XML into a zip.
The look is the VaR Playground's template (drishti-server/src/main/resources/playground/var/template/xl/styles.xml).

Every formula is also evaluated here by a small evaluator of the subset used (names, + - * / ^ &, comparisons, MAX, MIN, IF,
CEILING, AND, OR, INT, ROUND), so each cell carries its value at the defaults and the tests can check the figures the Explain tab
states (EXPECTED) without a spreadsheet program.
"""
from __future__ import annotations

import argparse
import math
import re
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "admin" / "sizing" / "Drishti-Sizing-Planner.xlsx"
TEMPLATE = ROOT / "drishti-server" / "src" / "main" / "resources" / "playground" / "var" / "template"
MB = "BytesPerMB"


def _version() -> str:
    m = re.search(r"<artifactId>drishti-server</artifactId>\s*<version>([^<]+)</version>", (ROOT / "drishti-server" / "pom.xml").read_text())
    return m.group(1) if m else "VERSION"


def _branding() -> dict:
    text = (ROOT / "drishti-server" / "src" / "main" / "resources" / "application.yaml").read_text(encoding="utf-8")
    out = {}
    for key in ("product", "owner", "copyright"):
        m = re.search(r"^\s+" + key + r":\s*(.+?)\s*(#.*)?$", text[text.index("branding:"):], re.M)
        out[key] = re.sub(r"^\$\{[A-Z_]+:(.*)\}$", r"\1", m.group(1)) if m else ""
    return out


# ---------------------------------------------------------------------------------------------------------------- the inputs
# (section, name, label, default, unit, kind, hint); kind: int, dec, text
INPUTS = [
    ("VaR", "Positions", "Positions a business date", 1_000_000, "positions", "int",
     "How many positions one business date holds (one row of P&L a position after netting)."),
    ("VaR", "Trades", "Trades a business date", 1_000_000, "trades", "int",
     "How many trades those positions are made of; equal to the positions when every position is one trade."),
    ("VaR", "TradesPerFungible", "Trades per position, of positions that hold several", 2, "trades", "dec",
     "Only used when trades exceed positions: how many trades a shared (fungible) position holds on average."),
    ("VaR", "Portfolios", "Portfolios", 15_000, "portfolios", "int", "Stored portfolios: baskets, rule portfolios, desk and book portfolios."),
    ("VaR", "BasketMembers", "Basket members", 2_000_000, "members", "int", "Positions named by all the baskets together."),
    ("VaR", "Nodes", "Hierarchy nodes", 27_024, "nodes", "int", "Nodes of every risk hierarchy (bank, legal entities, divisions, desks, limit letters...)."),
    ("VaR", "BooksCells", "Books and cells", 130_000, "books + cells", "int", "Trading books plus the cells of column hierarchies."),
    ("VaR", "Scenarios", "Scenarios", 509, "scenarios", "int", "Scenario days in a P&L vector (509: two years of history plus the base)."),
    ("VaR", "TradeCcysPerNode", "Trade currencies per node", 1, "currencies", "dec", "On average, how many trade currencies a node's trades are in."),
    ("VaR", "LakeYears", "Years of business dates kept in the lake", 1, "years", "dec", "How much history the lake keeps (the bank's rule is often 7 years)."),
    ("VaR", "HotDates", "Dates in memory (hot-dates)", 10, "dates", "int", "Business dates kept open at once; the least recently used one leaves first."),
    ("VaR", "PinnedMem", "Pinned dates kept in memory", 0, "dates", "int", "Dates that must always stay open (a month-end the desks compare with, an audit date)."),
    ("VaR", "PinnedDisk", "Pinned dates kept on disk", 0, "dates", "int", "Dates whose hot file is always kept on local disk, so they open in a second (month-ends, year-ends)."),
    ("VaR", "CcysAhead", "Reporting currencies built ahead (besides USD)", 0, "currencies", "int",
     "Each one is a whole extra book a date (the shipped setting builds 4: EUR, CAD, JPY, GBP)."),
    ("VaR", "StressOn", "Stress set on (1) or off (0)", 0, "0 or 1", "int", "1 when stressed VaR is used: the stress set is a book of its own a date."),
    ("VaR", "IntradayBatches", "Intraday batches a day", 0, "batches", "int", "Intraday amendment batches applied to today's date."),
    ("VaR", "UpdatesPerBatch", "Updates per intraday batch", 0, "trades", "int", "Trades amended or cancelled in one batch."),
    ("Exposure", "ExposureOn", "Exposure on (1) or off (0)", 0, "0 or 1", "int", "1 when the counterparty exposure engine serves dates on this server."),
    ("Exposure", "NettingSets", "Netting sets", 10_000, "sets", "int", "Netting sets a business date."),
    ("Exposure", "ExpTrades", "Exposure trades", 100_000, "trades", "int", "Trades with simulated paths a business date."),
    ("Exposure", "Paths", "Paths", 200, "paths", "int", "Monte Carlo paths a trade."),
    ("Exposure", "TimeSteps", "Time steps", 24, "grid dates", "int", "Grid dates of a path."),
    ("Exposure", "ExpHotDates", "Exposure dates in memory", 10, "dates", "int", "drishti.ccr.exposure.hot-dates: exposure dates kept open."),
    ("Trades and BI", "TradeRowsPerDay", "Trade and BI rows a day", 20_000, "rows", "int", "Trade rows loaded a business date, all trade systems together."),
    ("Trades and BI", "TradeDaysKept", "Days of trade rows kept", 252, "days", "int", "Business days of trades kept in the lake (252 is a year)."),
    ("Trades and BI", "TradeConnectors", "Trade connectors", 3, "connectors", "int", "Trade systems read through their own connector (for example ENDUR, CALYPSO, MUREX)."),
    ("Users", "Users", "Concurrent users", 50, "users", "int", "People (or programs) asking questions at the same moment at the busiest time."),
    ("Users", "PeakRps", "Peak requests a second wanted", 100, "requests/s", "dec", "Requests a second the server must answer at the peak."),
    ("Users", "VarShare", "Share of VaR questions", 0.5, "share", "dec", "Of those requests, the share that are VaR questions (the rest are trades, searches, pages)."),
    ("Machine", "JavaVersion", "Java version (21 or 25)", 21, "21 or 25", "int", "The Java the server runs on; 25 adds compact object headers."),
    ("Machine", "LoadThreads", "Load threads (drishti.var.load-threads)", 2, "threads", "int", "Dates that may load from the lake at the same time."),
    ("Machine", "Collector", "Garbage collector: auto, G1 or ZGC", "auto", "text", "text", "auto follows the documented rule: generational ZGC from 16 GB of heap, G1 below."),
    ("Machine", "DataFolder", "Data folder", "/opt/drishti/data", "path", "text", "Where Drishti keeps everything it writes (dumps, logs and temporary files go under it)."),
    ("Machine", "ServerJar", "Server jar", "drishti-server-{VERSION}-exec.jar", "file", "text", "The server's jar, as the command line starts it."),
]

# ---------------------------------------------------------------------------------------------------------------- coefficients
# (name, value, unit, status, source); status: measured, formula, calibrated, estimate
COEFFS = [
    ("BytesPerMB", 1_048_576, "bytes", "formula", "1 MB = 1,024 × 1,024 bytes, as the JVM and Admin → Caches count it; 1 GB = 1,024 MB."),
    ("Scale1M", 1_000_000, "positions", "formula", "The size every per-million coefficient refers to."),
    ("BusinessDaysYear", 252, "days", "formula", "Business days a year."),
    ("BytesPerRun", 8, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: 8·R, node members kept as runs."),
    ("RunsPerNode", 9.7, "runs", "estimate", "VAR_GUIDE.md 27.1 worked example: about 2 MB of runs at 27,024 nodes (about 9.7 runs a node)."),
    ("BytesPerNamedRow", 150, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: ~150·(B + K + N), names, book and cell runs, nodes and their book lists."),
    ("BytesPerAggIndexRow", 64, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: 64·A, the index of the aggregates (A = B + K + N, the rows mapped from the hot file)."),
    ("BytesPerTradeCols", 108, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: 108·T, code and number columns of a trade, positionOf, its id index."),
    ("BytesPerPositionCols", 136, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: 136·P, a position's columns, run and id index."),
    ("BytesPerId", 72, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: 72·(2T + 3P), trade and position ids and the high-cardinality dictionaries."),
    ("BytesPerCtotValue", 8, "bytes", "formula", "VAR_GUIDE.md 27.1, H_book: 8·(S + 1)·N·C, every node's per-trade-currency totals (float64)."),
    ("BytesPerPortfolio", 1_000, "bytes", "estimate", "VAR_GUIDE.md 27.1: the portfolio definitions are about 30 MB at 15,000 portfolios and 2 million basket members; half given to the portfolios."),
    ("BytesPerBasketMember", 7.5, "bytes", "estimate", "The other half of those 30 MB, given to the basket members."),
    ("BookHeapCalibration", 0.7851, "factor", "calibrated",
     "The 1,000,000-position re-benchmark (Java 21, 6 GB heap): the second date added 584 MB of heap after GC; the H_book formula gives 743.8 MB. "
     "The users run measured 769 / 1,352 / 2,213 MB at start, one and two dates (two dates within 1.2% of this calibration). Before node members were runs and the aggregates were mapped, a date held about 3.2 GB of heap at this size."),
    ("FirstOpenOnceMB", 278, "MB at 1M positions", "calibrated",
     "Re-benchmark: the first date added 862 MB (740 → 1,602 MB), the second 584 MB; the 278 MB difference is warmed once (answers, views, connector caches), scaled by positions."),
    ("ProtectedAlways", 2, "dates", "formula", "VAR_GUIDE.md 26.8: the date just used and the newest business date always stay open, on top of the pinned ones."),
    ("IntradayLayerMB", 5, "MB", "formula", "VAR_GUIDE.md 27.1, H_date: about 5 MB and the updates per intraday layer."),
    ("BytesPerIntradayUpdate", 4_300, "bytes", "estimate", "An updated trade's vector in float64 (509 × 8 bytes) and its row."),
    ("BaseFixedMB", 128, "MB", "formula", "BENCHMARK.md 4.8: the fitted heap at start, 128 MB plus a share per position."),
    ("BasePerPositionB", 574.6, "bytes", "calibrated",
     "Re-benchmark: 740 MB of heap after GC at start at 1,000,000 positions; minus the 64 MB footer cache (below) and 128 MB, 548 MB over 1,000,000 positions (lake footers' residue, the entity index of the P&L rows)."),
    ("BaseTradeRows", 20_000, "rows a day", "calibrated", "The measured start heap already holds the benchmark's trades (20,000 a day over 2 days, 2,000 counterparties); only rows above it are added."),
    ("FooterCacheDefaultMB", 64, "MB", "formula", "RESOURCES_AND_SCALE.md 3.5: -Ddrishti.parquet.footer-cache-mb, 64 MB by default."),
    ("FooterBytesPerChunk", 700, "bytes", "measured", "RESOURCES_AND_SCALE.md 3.5: about 700 bytes a column chunk of a Parquet footer."),
    ("RowsPerRowGroup", 128_000, "rows", "estimate", "Rows in one Parquet row group of a trade table."),
    ("ColumnsPerTable", 60, "columns", "estimate", "Columns of a trade table's Parquet files (each a column chunk in the footer)."),
    ("SearchIndexCapMB", 600, "MB", "formula", "RESOURCES_AND_SCALE.md 3.5, CONFIGURATION.md: the type-ahead index's index.max-memory, 600 MB."),
    ("SearchBytesPerRow", 200, "bytes", "estimate", "Heap of the type-ahead index a trade row of the kept day."),
    ("BiQueryCacheMB", 64, "MB", "formula", "RESOURCES_AND_SCALE.md 3.3: drishti.bi.query.cache-max-bytes, 64 MB."),
    ("BiPlanAnswersMB", 50, "MB", "estimate", "RESOURCES_AND_SCALE.md 3.3: plan answers, 2,000 entries (drishti.bi.plan.cache-entries), about 25 KB each."),
    ("HistoryCacheMB", 256, "MB", "formula", "RESOURCES_AND_SCALE.md 3.4, CONFIGURATION.md: drishti.history.cache-mb, 256 MB."),
    ("VarAnswersMB", 32, "MB", "estimate", "RESOURCES_AND_SCALE.md 3.1: the VaR answers, 512 entries, about 64 KB each."),
    ("TradeCacheBytesPerRow", 1_300, "bytes", "estimate", "BENCHMARK.md 8.2 and 8.4: 1.2 to 1.8 GB of heap after GC with 1.16 million trades on the latest day and 50 users."),
    ("PerLoadMBAt1M", 1_600, "MB", "formula", "VarProperties.PER_LOAD_BYTES: the heap one load holds while it builds a date (its aggregates and currency totals), 1.6 GB at 1,000,000 positions, scaled by positions."),
    ("ReadBufferMB", 64, "MB", "formula", "VAR_GUIDE.md 27a.6: drishti.var.read.buffer-mb, decoded rows waiting for one load."),
    ("PerUserMB", 2.466, "MB", "measured",
     "PER-USER WORKING MEMORY. The 1,000,000-position users run (6 GB heap, Java 21, G1): heap after GC 2,336.2 MB warm at 50 users against 2,212.9 MB with two dates open, (2,336.2 − 2,212.9) ÷ 50; 2,355 MB at the end after 200 users."),
    ("LiveShare", 0.61, "share", "formula", "RESOURCES_AND_SCALE.md 9.5 and BENCHMARK.md 11: the heap needed is the heap after collection ÷ 0.61 (39% headroom for the collector)."),
    ("Java25HeapFactor", 0.977, "factor", "measured", "BENCHMARK.md 4.4: Java 25 with compact object headers, 7,335 MB against 7,505 MB at 1,000,000 positions."),
    ("CompressedOopsMaxGB", 31, "GB", "formula", "Java: compressed object references work up to a heap just under 32 GB; 31g is the safe largest."),
    ("AlignedOopsMaxGB", 64, "GB", "formula", "Java: with -XX:ObjectAlignmentInBytes=16 compressed references reach 64 GB (objects pad to 16 bytes)."),
    ("ZgcFromGB", 16, "GB", "formula", "RESOURCES_AND_SCALE.md 5.5, PERFORMANCE.md (JVM settings): generational ZGC for large single servers (16 to 36 GB), G1 for small ones."),
    ("OpenHeapStepMB", 64, "MB", "formula", "drishti.var.open-heap-mb is rounded up to a multiple of this."),
    ("ZgcSoftMaxShare", 0.8, "share", "formula", "RESOURCES_AND_SCALE.md 5.5: -XX:SoftMaxHeapSize = 0.8 × -Xmx with ZGC (planned in the docs)."),
    ("ExpShardLoadMB", 8_192, "MB", "estimate", "BENCHMARK.md 6: past about 8 GB of value matrices an exposure date's load should be sharded."),
    ("SingleNodeRamGB", 512, "GB", "estimate", "Past about 512 GB of RAM one server is the wrong shape: plan a cluster."),
    ("OpenHeapMargin", 0.10, "share", "estimate", "Room in drishti.var.open-heap-mb above the dates in memory, so the safety net does not close a date for a few MB."),
    ("NetShare", 0.4, "share", "formula", "OpenDates.automaticNet: the automatic safety net is 0.4 × the heap − 512 MB − load-threads × 1.6 GB."),
    ("NetReserveMB", 512, "MB", "formula", "OpenDates.automaticNet: the 512 MB of that formula."),
    ("NetLoadMB", 1_600, "MB", "formula", "OpenDates.automaticNet: the per-load 1.6 GB of that formula (not scaled by positions)."),
    ("NetFloorShare", 0.1, "share", "formula", "OpenDates.automaticNet: at least a tenth of the heap."),
    ("NetGraceSeconds", 120, "seconds", "formula", "-Ddrishti.open-dates.net-grace-seconds: the safety net never closes a date read in the last 120 s."),
    ("ExpOpenBytesPerTrade", 1_720, "bytes", "measured", "BENCHMARK.md 6: an open exposure date, 172 MB of heap at 100,000 trades (matrices mapped)."),
    ("ExpLoadBytesPerValue", 8, "bytes", "measured", "BENCHMARK.md 6: while loading, every set's value matrix in float64 (sets × paths × steps × 8): 380 MB at 9,901 sets, 200 paths, 24 steps."),
    ("ExpPathsCacheMB", 256, "MB", "formula", "RESOURCES_AND_SCALE.md 3.2: the exposure trade-paths cache, 256 MB."),
    ("ExpHotBytesPerValue", 6, "bytes", "formula", "EXPOSURE_GUIDE.md 27.2: V in float32 (4 bytes) and E on the primary dates, half the grid (2 bytes a value)."),
    ("ExpHotBytesPerTrade", 1_256, "bytes", "calibrated", "BENCHMARK.md 6: the hot file of 100,000 trades was 410.8 MB; minus the matrices' 285 MB, 1,256 bytes a trade."),
    ("ExpPathBytes", 4, "bytes", "formula", "EXPOSURE_GUIDE.md 27.2: trade paths in the lake, float32."),
    ("ExpPathDaysKept", 20, "days", "formula", "EXPOSURE_GUIDE.md 27.3: trade paths kept at full resolution for 20 business days."),
    ("ExpDiskDates", 60, "files", "formula", "CONFIGURATION.md: the exposure tier's disk-dates, 60 (the retention may keep more)."),
    ("HotRowBytes", 4, "bytes", "formula", "VAR_GUIDE.md 26.3: position and trade rows in float32."),
    ("AggBytes", 8, "bytes", "formula", "VAR_GUIDE.md 26.3: aggregates and currency totals in float64."),
    ("HotSmallBytesPerTrade", 354, "bytes", "calibrated",
     "BENCHMARK.md 3: the hot file measured 3.14 GB a date at 1,000,000 positions; the rows, aggregates and totals are 2.79 GB, the rest (ids, columns, runs) 354 bytes a trade (VAR_GUIDE.md 27.1 says about 120)."),
    ("StressScenarios", 250, "scenarios", "estimate", "RESOURCES_AND_SCALE.md 2.3: a stress window of 250 days."),
    ("LakeBytesPerValue", 2.9535, "bytes", "calibrated", "Re-benchmark: 2.8 GB of lake for two dates of 1,000,000 vectors of 509 values (read as GB of 1,024 MB, the larger reading)."),
    ("DiskDates", 60, "files", "formula", "CONFIGURATION.md: drishti.var.tier.disk-dates, 60 hot files kept on disk."),
    ("CurrencyRetentionDates", 5, "dates", "formula", "RESOURCES_AND_SCALE.md 3.1: currency books kept on disk for 5 dates."),
    ("SpillFactor", 3, "× hot file", "formula", "VAR_GUIDE.md 26.8: before a build the tier reserves three times its largest hot file (the disk guard)."),
    ("LogsGB", 1.1, "GB", "formula", "RESOURCES_AND_SCALE.md 3.8, 6.4: the server log rotated at 1 GB in all, the GC log 5 × 20 MB."),
    ("DumpsKept", 1, "dumps", "estimate", "OPERATIONS.md: a heap dump is about the size of the heap; one kept."),
    ("IdMapBytesPerRow", 15, "bytes", "measured", "BENCHMARK.md 8.4: id maps on disk, about 15 bytes a trade a date."),
    ("TradeLakeBytesPerRow", 1_500, "bytes", "measured", "BENCHMARK.md 8.4: about 1.5 KB a trade a day in the lake."),
    ("GroupedPagesBytesPerRow", 550, "bytes", "measured", "BENCHMARK.md 8.4: grouped pages, about 0.55 KB a trade a cached date."),
    ("GroupedPagesDates", 16, "dates", "formula", "RESOURCES_AND_SCALE.md 3.4: grouped pages keep 16 dates."),
    ("DocCacheGB", 1, "GB", "formula", "RESOURCES_AND_SCALE.md 3.5: the document disk cache, 1 GB a connector."),
    ("IndexDiskGB", 2, "GB", "formula", "RESOURCES_AND_SCALE.md 3.5: the type-ahead index, index.max-disk 2 GB a connector."),
    ("BiLayerBytesPerRow", 300, "bytes", "estimate", "BI layers on disk a trade row a day read."),
    ("BiRunsGB", 20, "GB", "formula", "RESOURCES_AND_SCALE.md 3.3: the BI runs' store, 20 GB."),
    ("MinFreePercent", 10, "%", "formula", "CONFIGURATION.md: drishti.disk.min-free-percent, 10."),
    ("MinFreeGB", 20, "GB", "formula", "CONFIGURATION.md: drishti.disk.min-free-gb, 20 (both must hold)."),
    ("DiskStepGB", 50, "GB", "formula", "Disk recommendations are rounded up to a multiple of this."),
    ("ResidentShare", 0.7458, "share", "calibrated", "The users run: RSS 11,160 MB at the end minus 6,694 MB committed (NMT) leaves 4,466 MB of mapped hot-file pages, 75% of the two dates' 5,988 MB, under load."),
    ("MeasuredXmxMB", 6_144, "MB", "measured", "The -Xmx of the 1,000,000-position runs (6 GB)."),
    ("ResidentFirstIdle", 0.7728, "share", "calibrated", "Re-benchmark: RSS rose 3,176 MB with the first date (862 MB of it heap); the rest is 77% of its 2,994 MB hot file, written and read."),
    ("ResidentNextIdle", 0.1099, "share", "calibrated", "Re-benchmark: RSS rose 913 MB with the second date (584 MB heap); the rest is 11% of its hot file (only the pages read)."),
    ("RssStartMB", 2_708, "MB", "measured", "Re-benchmark: RSS at start, 1,000,000 positions, 6 GB heap, Java 21 (2,875 MB in the users run)."),
    ("MetaspaceCodeMB", 215, "MB", "measured", "PERFORMANCE.md (Resident memory): metaspace 131 MB and code cache 84 MB, flat."),
    ("DirectMaxMB", 256, "MB", "formula", "PERFORMANCE.md, OPERATIONS.md: -XX:MaxDirectMemorySize=256m on every launcher."),
    ("OtherNativeMB", 600, "MB", "estimate", "PERFORMANCE.md (Resident memory): RSS levels off at about -Xmx + 0.4 GB (two malloc arenas) to + 1 GB (default allocator); the rest beyond metaspace and direct memory."),
    ("OsReserveGB", 2, "GB", "estimate", "The operating system and its own services."),
    ("ConsoleGB", 1, "GB", "estimate", "The console's 4 worker processes on the same machine."),
    ("RamStepGB", 8, "GB", "formula", "RAM recommendations are rounded up to a multiple of this."),
    ("MeasuredCores", 12, "cores", "measured", "The benchmark machine: nproc says 24 hardware threads, 12 cores of 2 threads each (BENCHMARK.md 3). Cores in Results are physical cores."),
    ("ReqPerCoreTrades", 22.5, "requests/s a core", "measured",
     "REQ/S PER CORE, trade questions (trades, counterparty pages, netting sets, searches). BENCHMARK.md 4.3, trades only: 270 requests a second at 50 users on 12 cores."),
    ("ReqPerCoreVarAtRef", 11.56, "requests/s a core", "calibrated",
     "REQ/S PER CORE, VaR questions at 1,000,000 positions. The users run: 183.3 requests a second at 50 users on 12 cores, 0 failures, p95 0.6 to 1.1 s, 50.5% of them VaR (5,582 of 11,050); with trade questions at 22.5 a core, the VaR ones are 11.56 a core. 137.5 a second at 100 users and 123.8 at 200 (queueing: p95 about 2 and 5 s)."),
    ("VarRefPositions", 1_000_000, "positions", "formula", "The size the VaR rate above was measured at."),
    ("VarCostExponent", 1.0, "exponent", "estimate",
     "How a VaR question's cost grows with positions away from 1,000,000 (1: in proportion; the bank minus a desk reads every position)."),
    ("CpuTarget", 0.7, "share", "estimate", "Plan the cores to be 70% busy at the peak, so queues stay short."),
    ("CoresReserve", 2, "cores", "estimate", "Cores kept for a date's load, the collector and the operating system."),
    ("MinCores", 4, "cores", "estimate", "The fewest cores recommended."),
    ("FirstOpenSecPer1M", 18.5, "s", "measured", "Re-benchmark: a date's first open took 18 to 19 s at 1,000,000 positions."),
    ("LakeLoadSecPer1M", 14.2, "s", "measured", "Re-benchmark: the lake load took 28.4 s for two dates of 1,000,000 vectors."),
    ("ReopenSecPer1M", 0.25, "s", "estimate", "BENCHMARK.md 5.1: reopening a date from its hot file, about 0.25 s a million trades."),
]


# ---------------------------------------------------------------------------------------------------------------- results
# ("band", title) or (name, label, formula, unit, why) or ("note", text); unit "text" marks a text result
def _results() -> list:
    r = []
    band = lambda t: r.append(("band", t))  # noqa: E731
    row = lambda *a: r.append(a)  # noqa: E731
    band("Heap: one VaR book (VAR_GUIDE.md 27.1, term by term)")
    row("HbRuns", "Node members as runs", f"BytesPerRun*RunsPerNode*Nodes/{MB}", "MB", "8·R: each node's books and cells kept as runs of positions.")
    row("HbNames", "Names, runs, nodes", f"BytesPerNamedRow*(BooksCells+Nodes)/{MB}", "MB", "~150·(B + K + N): book and cell names and runs, the nodes and their book lists.")
    row("HbAggIndex", "Index of the aggregates", f"BytesPerAggIndexRow*(BooksCells+Nodes)/{MB}", "MB", "64·A: the aggregates themselves stay in the hot file; the heap keeps where each row is.")
    row("HbColumns", "Columns and position runs", f"(BytesPerTradeCols*Trades+BytesPerPositionCols*Positions)/{MB}", "MB", "108·T + 136·P: the coded columns, the position runs, positionOf, the id indexes.")
    row("HbIds", "Trade and position ids", f"BytesPerId*(2*Trades+3*Positions)/{MB}", "MB", "72·(2T + 3P): the ids and the high-cardinality dictionaries; the largest term.")
    row("HbCtot", "Per-currency totals of every node", f"BytesPerCtotValue*(Scenarios+1)*Nodes*TradeCcysPerNode/{MB}", "MB", "8·(S + 1)·N·C: each node's totals per trade currency, in float64.")
    row("HbPortfolios", "Portfolio definitions", f"(BytesPerPortfolio*Portfolios+BytesPerBasketMember*BasketMembers)/{MB}", "MB", "About 30 MB at 15,000 portfolios and 2 million basket members.")
    row("HbFormula", "One book by the formula", "HbRuns+HbNames+HbAggIndex+HbColumns+HbIds+HbCtot+HbPortfolios", "MB", "The sum of the terms above.")
    row("JavaHeapFactor", "Java version factor", "IF(JavaVersion>=25,Java25HeapFactor,1)", "factor", "Compact object headers on Java 25 save a little (the heap is mostly arrays).")
    row("HeapBook", "One book, calibrated", "HbFormula*BookHeapCalibration*JavaHeapFactor", "MB", "The formula scaled to what the 1,000,000-position re-benchmark measured.")
    row("BooksPerDate", "Books a date", "1+CcysAhead+StressOn", "books", "The USD book, one more per reporting currency built ahead, one for the stress set.")
    row("HeapDate", "One date", "HeapBook*BooksPerDate", "MB", "H_date ≈ H_book × (1 + currency books + stress book).")
    row("DatesInMemory", "Dates in memory", "MAX(HotDates,PinnedMem+ProtectedAlways)", "dates", "hot-dates, unless the protected dates (pinned + the newest + the one just used) are more.")
    row("DatesAtPeak", "Dates at the peak", "DatesInMemory+1", "dates", "While a new date opens, the date it pushes out stays open until its readers finish.")
    row("HeapDates", "All open dates", "DatesAtPeak*HeapDate", "MB", "Dates at the peak × one date.")
    row("HeapIntraday", "Intraday layers", f"IntradayBatches*(IntradayLayerMB+UpdatesPerBatch*BytesPerIntradayUpdate/{MB})", "MB", "About 5 MB a layer plus its updated trades.")
    row("HeapFirstOpen", "Warmed by the first date", "FirstOpenOnceMB*Positions/Scale1M*JavaHeapFactor", "MB", "What the first open warms once (answers, views, connector caches).")
    band("Heap: exposure")
    row("ExpOpenDateMB", "One open exposure date", f"ExposureOn*ExpTrades*ExpOpenBytesPerTrade/{MB}", "MB", "The sets and trades of a date; its matrices are mapped, not on the heap.")
    row("HeapExposure", "Exposure dates and paths cache", "ExpHotDates*ExpOpenDateMB+ExposureOn*ExpPathsCacheMB", "MB", "Exposure dates in memory × one date, and the 256 MB trade-paths cache.")
    row("ExpLoadMB", "Loading an exposure date", f"ExposureOn*NettingSets*Paths*TimeSteps*ExpLoadBytesPerValue/{MB}", "MB", "Every netting set's value matrix in float64 while the date loads.")
    band("Heap: the server, caches and users")
    row("HeapBase", "The server at start", f"(BaseFixedMB+BasePerPositionB*Positions/{MB})*JavaHeapFactor", "MB", "Measured after GC with no date open: the lake's footers' residue and the entity index grow with positions.")
    row("FooterCacheMB", "Parquet footer cache", f"MAX(FooterCacheDefaultMB,CEILING(TradeRowsPerDay*TradeDaysKept/RowsPerRowGroup*ColumnsPerTable*FooterBytesPerChunk/{MB},1))",
        "MB", "64 MB, or the footers of every trade table kept when they are more.")
    row("HeapSearch", "Type-ahead index", f"MIN(SearchIndexCapMB,TradeRowsPerDay*SearchBytesPerRow/{MB})", "MB", "The kept day's index of trade rows, capped by index.max-memory.")
    row("HeapBiCaches", "BI, history and VaR answer caches", "BiQueryCacheMB+BiPlanAnswersMB+HistoryCacheMB+VarAnswersMB", "MB", "Each cache at its budget.")
    row("HeapTradeRows", "Trade rows beyond the measured", f"MAX(0,TradeRowsPerDay-BaseTradeRows)*TradeCacheBytesPerRow/{MB}", "MB", "The trade connectors' working set for rows above the 60,000 a day already in the start heap.")
    row("HeapUsers", "Requests in flight", "Users*PerUserMB", "MB", "Working memory per concurrent user (measured: about 2.5 MB).")
    row("HeapSteady", "Heap after GC, all dates open", "HeapBase+FooterCacheMB+HeapFirstOpen+HeapDates+HeapIntraday+HeapExposure+HeapSearch+HeapBiCaches+HeapTradeRows+HeapUsers", "MB",
        "What survives a collection at the busiest steady moment.")
    row("HeapLoadTransient", "Loads in progress", "LoadThreads*(PerLoadMBAt1M*Positions/Scale1M+ReadBufferMB)+ExpLoadMB", "MB", "Each load thread's build (1.6 GB at 1,000,000 positions) and read buffer, and an exposure load.")
    band("The heap to set")
    row("XmxRawMB", "Heap needed", "HeapSteady/LiveShare+HeapLoadTransient", "MB", "The steady heap with the collector's 39% headroom, plus the loads.")
    row("XmxGB", "Recommended -Xmx", "CEILING(XmxRawMB/1024,1)", "GB", "Rounded up to a whole GB.")
    row("OopsMode", "Compressed object references", 'IF(XmxGB<=CompressedOopsMaxGB,"on: the heap is under 32 GB",IF(XmxGB<=AlignedOopsMaxGB,"on only with -XX:ObjectAlignmentInBytes=16 (added below)","off: past 64 GB, plan a cluster"))',
        "text", "Under 32 GB Java uses 4-byte references; from 32 GB they double unless objects align to 16 bytes (up to 64 GB).")
    row("GcChoice", "Garbage collector", 'IF(Collector="auto",IF(XmxGB>=ZgcFromGB,"ZGC","G1"),Collector)', "text", "Generational ZGC from 16 GB of heap, G1 below (RESOURCES_AND_SCALE.md 5.5).")
    band("Page cache and RAM")
    row("TmatRows", "Trade rows of shared positions", "IF(Trades>Positions,TradesPerFungible*(Trades-Positions)/MAX(TradesPerFungible-1,1),0)", "rows", "Trades of positions that hold several get rows of their own in the hot file.")
    row("HotFileBookMB", "Hot file of one book", f"(Positions*Scenarios*HotRowBytes+TmatRows*Scenarios*HotRowBytes+(BooksCells+Nodes)*Scenarios*AggBytes+Nodes*TradeCcysPerNode*(Scenarios+1)*AggBytes+HotSmallBytesPerTrade*Trades)/{MB}",
        "MB", "P·S·4 + Q·S·4 + A·S·8 + N·C·(S+1)·8 + the small sections (VAR_GUIDE.md 27.1).")
    row("StressFileMB", "Hot file of the stress book", "StressOn*HotFileBookMB*StressScenarios/Scenarios", "MB", "The stress set's book, about 250 scenarios.")
    row("HotFileDateMB", "Hot files of one date", "HotFileBookMB*(1+CcysAhead)+StressFileMB", "MB", "The USD book, each currency book and the stress book.")
    row("ExpHotFileMB", "Exposure hot file of a date", f"ExposureOn*(NettingSets*Paths*TimeSteps*ExpHotBytesPerValue+ExpTrades*ExpHotBytesPerTrade)/{MB}", "MB", "V and E in float32 and the sets and trades.")
    row("PageCacheMB", "Page cache for the open dates", "(DatesInMemory*HotFileDateMB+ExpHotDates*ExpHotFileMB)*ResidentShare", "MB", "The open dates' hot files resident while people read them.")
    row("NativeMB", "Native memory beside the heap", "MetaspaceCodeMB+DirectMaxMB+OtherNativeMB", "MB", "Metaspace and code, direct buffers at their cap, threads, the collector and the allocator.")
    row("RssPlannedMB", "Resident memory planned", "XmxGB*1024+NativeMB+PageCacheMB", "MB", "RSS = heap (fully committed over a long run) + native + the page cache of the open dates.")
    row("RamRecommendedGB", "Recommended machine RAM", "CEILING(RssPlannedMB/1024+OsReserveGB+ConsoleGB,RamStepGB)", "GB", "RSS + the operating system + the console, rounded up to 8 GB.")
    row("RamMinimumGB", "Smallest workable RAM", "CEILING(XmxGB+NativeMB/1024+OsReserveGB+ConsoleGB+HotFileDateMB/1024,RamStepGB)", "GB",
        "Only the date being read resident: it works, but other dates read from disk first (slower).")
    band("Disk, by folder")
    row("LakeDates", "Business dates in the lake", "LakeYears*BusinessDaysYear", "dates", "Years kept × 252.")
    row("LakeVarDateMB", "VaR lake, one date", f"Trades*Scenarios*LakeBytesPerValue/{MB}*(1+StressOn*StressScenarios/Scenarios)", "MB", "The P&L vectors as Parquet (2.8 GB for two dates at 1,000,000).")
    row("DiskLakeVarGB", "VaR lake (the lake folder)", "LakeDates*LakeVarDateMB/1024", "GB", "Every date kept.")
    row("HotDatesOnDisk", "VaR hot files kept", "MIN(LakeDates,DiskDates)+PinnedDisk", "dates", "disk-dates (60) and the pinned dates on disk.")
    row("DiskHotGB", "VaR hot files (var/hot)", "(HotDatesOnDisk*(HotFileBookMB+StressFileMB)+CcysAhead*MIN(HotDatesOnDisk,CurrencyRetentionDates)*HotFileBookMB)/1024", "GB",
        "Each kept date's book and stress book; currency books for the 5 newest dates.")
    row("DiskSpillGB", "Spill and temporary files (tmp/server)", "LoadThreads*SpillFactor*HotFileBookMB/1024", "GB", "The disk guard reserves 3 × the largest hot file for each load running.")
    row("DiskExpHotGB", "Exposure hot files (ccr/hot)", "MIN(LakeDates,ExpDiskDates)*ExpHotFileMB/1024", "GB", "60 dates of exposure hot files.")
    row("DiskExpLakeGB", "Exposure trade paths (the lake)", f"ExposureOn*MIN(LakeDates,ExpPathDaysKept)*ExpTrades*Paths*TimeSteps*ExpPathBytes/{MB}/1024", "GB", "20 business days of trade paths in float32.")
    row("DiskTradesLakeGB", "Trades lake", f"TradeRowsPerDay*TradeDaysKept*TradeLakeBytesPerRow/{MB}/1024", "GB", "About 1.5 KB a trade a day.")
    row("DiskTradeCachesGB", "Trade connectors' caches (cache/, derived/, index/)",
        f"(TradeRowsPerDay*TradeDaysKept*IdMapBytesPerRow+TradeRowsPerDay*MIN(TradeDaysKept,GroupedPagesDates)*GroupedPagesBytesPerRow)/{MB}/1024+TradeConnectors*(DocCacheGB+IndexDiskGB)",
        "GB", "Id maps, grouped pages, the document cache and the type-ahead index of each connector.")
    row("DiskBiGB", "BI layers and runs (bi-store, bi-runs)", f"TradeRowsPerDay*TradeDaysKept*BiLayerBytesPerRow/{MB}/1024+BiRunsGB", "GB", "Layers of the dates read, and the runs' 20 GB.")
    row("DiskLogsGB", "Logs (logs)", "LogsGB", "GB", "The server log and the GC log, both rotated.")
    row("DiskDumpsGB", "Heap dumps (dumps)", "DumpsKept*XmxGB", "GB", "A dump on OutOfMemoryError is about the heap's size.")
    row("DiskUsedGB", "Disk in use", "DiskLakeVarGB+DiskHotGB+DiskSpillGB+DiskExpHotGB+DiskExpLakeGB+DiskTradesLakeGB+DiskTradeCachesGB+DiskBiGB+DiskLogsGB+DiskDumpsGB", "GB", "Every folder together.")
    row("DiskRecommendedGB", "Recommended disk (lake local)", "CEILING(MAX(DiskUsedGB/(1-MinFreePercent/100),DiskUsedGB+MinFreeGB),DiskStepGB)", "GB",
        "With the free-space guard's margin: 10% and 20 GB must stay free.")
    row("DiskLocalGB", "Local disk without the lakes", "DiskUsedGB-DiskLakeVarGB-DiskTradesLakeGB-DiskExpLakeGB", "GB", "When the lakes live elsewhere (object storage): hot files, spill, caches, logs, dumps.")
    row("DiskLocalRecommendedGB", "Recommended local disk (lake elsewhere)", "CEILING(MAX(DiskLocalGB/(1-MinFreePercent/100),DiskLocalGB+MinFreeGB),DiskStepGB)", "GB", "Fast local disk (NVMe) for the hot files.")
    band("CPU and times")
    row("VarRatePerCore", "VaR questions a second a core", "ReqPerCoreVarAtRef*(VarRefPositions/Positions)^VarCostExponent", "requests/s", "The rate measured at 1,000,000 positions, scaled by positions.")
    row("MixRatePerCore", "Mixed requests a second a core", "1/(VarShare/VarRatePerCore+(1-VarShare)/ReqPerCoreTrades)", "requests/s", "The VaR and other questions in their shares.")
    row("CoresForRequests", "Cores for the requests", "CEILING(PeakRps/MixRatePerCore/CpuTarget,1)", "cores", "The peak at 70% busy.")
    row("CoresRecommended", "Recommended cores", "MAX(MinCores,CoresForRequests+CoresReserve)", "cores", "Plus 2 for loads, the collector and the operating system.")
    row("FirstOpenSec", "First open of a date", "FirstOpenSecPer1M*Positions/Scale1M", "s", "Lake read, sort, sums and the hot file written.")
    row("LakeLoadSec", "Loading a date into the lake", "LakeLoadSecPer1M*Trades/Scale1M", "s", "The Java loader, P&L vectors of one date.")
    row("ReopenSec", "Reopening a date from its hot file", "ReopenSecPer1M*Trades/Scale1M", "s", "No lake read.")
    band("The settings to put in the configuration")
    row("SetHotDates", "drishti.var.hot-dates", "DatesInMemory", "dates", "Dates in memory.")
    row("SetOpenHeapMB", "drishti.var.open-heap-mb", "CEILING(DatesInMemory*HeapDate*(1+OpenHeapMargin)+HeapIntraday,OpenHeapStepMB)", "MB",
        "The byte safety net: every open date with 10% room; set it explicitly rather than leave the automatic value.")
    row("AutoNetMB", "(the automatic safety net, for comparison)", "MAX(NetShare*XmxGB*1024-NetReserveMB-LoadThreads*NetLoadMB,XmxGB*1024*NetFloorShare)", "MB",
        "What open-heap-mb 0 would give: 0.4 × heap − 512 MB − load-threads × 1.6 GB, at least a tenth of the heap.")
    row("SetMemoryBudgetMB", "drishti.var.memory-budget-mb", "0", "MB", "0: it bounds the heap tier only; the mapped tier (the default) keeps vectors on disk.")
    row("HeapTierBudgetMB", "(the heap tier would need, a date)", f"Trades*Scenarios*AggBytes/{MB}", "MB", "Only with drishti.var.tier.mode: heap, every vector in float64 on the heap.")
    row("SetFooterCacheMB", "-Ddrishti.parquet.footer-cache-mb", "FooterCacheMB", "MB", "A JVM system property.")
    row("SetLoadThreads", "drishti.var.load-threads", "LoadThreads", "threads", "Dates loading at once; each needs its transient and its spill.")
    row("SetMaxDiskGB", "drishti.var.tier.max-disk-gb", "CEILING(DiskHotGB+DiskSpillGB,1)", "GB", "Every hot file kept plus the reserve of the builds.")
    row("SetDiskDates", "drishti.var.tier.disk-dates", "DiskDates", "files", "The default, 60.")
    row("SetCurrencies", "drishti.var.currencies", 'IF(CcysAhead=0,"USD","USD and "&CcysAhead&" more (name them)")', "text", "Only the currencies the planner counted.")
    row("SetStress", "drishti.var.stress.enabled", 'IF(StressOn=1,"true","false")', "text", "Offer stressed VaR.")
    row("SetExpHotDates", "drishti.ccr.exposure.hot-dates", 'IF(ExposureOn=1,""&ExpHotDates,"(exposure off)")', "text", "Exposure dates in memory.")
    row("SetDiskGuard", "drishti.disk.min-free-percent / min-free-gb", '""&MinFreePercent&" / "&MinFreeGB', "text", "The free-space guard the disk line keeps.")
    row("SetXmx", "-Xmx", '"-Xmx"&XmxGB&"g"', "text", "The heap.")
    row("SetGc", "Garbage collector", 'IF(GcChoice="ZGC",IF(JavaVersion>=25,"-XX:+UseZGC","-XX:+UseZGC -XX:+ZGenerational"),"-XX:+UseG1GC")', "text", "See the JVM arguments below.")
    row("SetMaxDirect", "-XX:MaxDirectMemorySize", '"-XX:MaxDirectMemorySize="&DirectMaxMB&"m"', "text", "The documented 256 MB on every launcher; mapped hot files do not count against it.")
    return r


# ---------------------------------------------------------------------------------------------------------------- JVM arguments
# (name, flag, formula, condition, why, source); source: documented or suggested
JVM = [
    ("JvmXmx", "-Xmx", '"-Xmx"&XmxGB&"g"', "always", "The heap computed above.", "documented"),
    ("JvmXms", "-Xms", '"-Xms"&XmxGB&"g"', "a server", "The whole heap reserved and committed from the start: no growth pauses, and RSS is known from day one.", "suggested"),
    ("JvmGc", "Collector", 'IF(GcChoice="ZGC",IF(JavaVersion>=25,"-XX:+UseZGC","-XX:+UseZGC -XX:+ZGenerational"),"-XX:+UseG1GC")', "heap ≥ 16 GB: ZGC; Java 21 needs -XX:+ZGenerational",
     "ZGC pauses under a millisecond whatever the heap; G1 costs less on a small heap. Measured: ZGC doubled RSS at the same -Xmx and stalled allocations at 200,000 positions; choose G1 (Collector) when RAM is tight.", "documented"),
    ("JvmGcExtra", "Collector tuning", 'IF(GcChoice="ZGC","-XX:SoftMaxHeapSize="&INT(XmxGB*1024*ZgcSoftMaxShare)&"m","-XX:G1PeriodicGCInterval=60000")', "ZGC: 0.8 × heap; G1: always",
     "ZGC collects early to stay under 80% of the heap (planned in the docs); G1's periodic collection returns unused memory.", "documented"),
    ("JvmPause", "-XX:MaxGCPauseMillis", '""', "not set", "The docs give no pause goal; G1's default (200 ms) stays.", "documented"),
    ("JvmOops", "-XX:ObjectAlignmentInBytes", 'IF(XmxGB>CompressedOopsMaxGB,IF(XmxGB<=AlignedOopsMaxGB,"-XX:ObjectAlignmentInBytes=16",""),"")', "heap over 31 GB, up to 64 GB",
     "Keeps 4-byte references above 32 GB, at the cost of padding objects to 16 bytes; or stay at 31g.", "suggested"),
    ("JvmCompact", "-XX:+UseCompactObjectHeaders", 'IF(JavaVersion>=25,"-XX:+UseCompactObjectHeaders","")', "Java 25 only (21 refuses to start with it)", "Smaller object headers, a little less heap.", "documented"),
    ("JvmPreTouch", "-XX:+AlwaysPreTouch", '""', "not set", "Not proposed by the docs: a slow start, and the whole heap committed at once.", "documented"),
    ("JvmDirect", "-XX:MaxDirectMemorySize", '"-XX:MaxDirectMemorySize="&DirectMaxMB&"m"', "always", "Caps direct buffers (the default cap is the heap's size).", "documented"),
    ("JvmTrim", "-XX:TrimNativeHeapInterval", '"-XX:TrimNativeHeapInterval=60000"', "Java 21.0.1 and later", "Returns freed native memory to the system every minute.", "documented"),
    ("JvmNioCache", "-Djdk.nio.maxCachedBufferSize", '"-Djdk.nio.maxCachedBufferSize=262144"', "always", "Stops each thread keeping a large temporary direct buffer after a read.", "documented"),
    ("JvmNmt", "-XX:NativeMemoryTracking", '"-XX:NativeMemoryTracking=summary"', "always", "Lets jcmd split native memory by category; a few per cent of overhead.", "documented"),
    ("JvmMeta", "-XX:MaxMetaspaceSize", '"-XX:MaxMetaspaceSize=512m"', "always", "A ceiling well above the 131 MB measured, so a class leak fails loudly instead of growing; the code cache keeps its default.", "suggested"),
    ("JvmDump", "-XX:+HeapDumpOnOutOfMemoryError", '"-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="&DataFolder&"/dumps"', "always",
     "A heap dump when the heap runs out, under the data folder; the disk lines keep room for it.", "documented"),
    ("JvmGcLog", "-Xlog:gc*", '"-Xlog:gc*:file="&DataFolder&"/logs/gc-%p.log:time,uptime:filecount=5,filesize=20m"', "always", "A rotated GC log (5 × 20 MB).", "documented"),
    ("JvmTmp", "-Djava.io.tmpdir", '"-Djava.io.tmpdir="&DataFolder&"/tmp/server"', "always", "Native libraries unpack and temporary files go under the data folder.", "documented"),
    ("JvmFooter", "-Ddrishti.parquet.footer-cache-mb", '"-Ddrishti.parquet.footer-cache-mb="&FooterCacheMB', "always", "The Parquet footer cache computed above.", "documented"),
    ("JvmGrace", "-Ddrishti.open-dates.net-grace-seconds", '"-Ddrishti.open-dates.net-grace-seconds="&NetGraceSeconds', "always",
     "The byte safety net never closes a date read in the last 120 s (closing it would reload it at once).", "documented"),
    ("JvmPinning", "virtual-thread flags", '""', "not set", "-Djdk.tracePinnedThreads=full is a diagnostic only; the scheduler's parallelism keeps its default (the cores).", "documented"),
    ("JvmNative", "--enable-native-access", '""', "not set with -jar", "The server jar's manifest already grants native access; no --add-opens is needed.", "documented"),
    ("JvmArenas", "MALLOC_ARENA_MAX (environment)", '"MALLOC_ARENA_MAX=2"', "Linux (glibc) only", "Two malloc arenas, not one per busy thread: about 500 MB less RSS measured.", "documented"),
]


def _command_formula() -> str:
    parts = ['JvmArenas&" java"'] + [f'IF({n}="",""," "&{n})' for n, *_ in JVM if n != "JvmArenas"]
    return "&".join(parts) + '&" -jar "&ServerJar'


WARNINGS = [
    ("WarnProtected", "Protected dates",
     'IF(PinnedMem+ProtectedAlways>HotDates,"The protected dates alone ("&(PinnedMem+ProtectedAlways)&": pinned, the newest and the one just used) exceed hot-dates ("&HotDates&"): the number is exceeded; the planner counts "&DatesInMemory&".","OK")'),
    ("WarnOops", "Compressed oops",
     'IF(XmxGB>AlignedOopsMaxGB,"Over 64 GB: compressed oops are off whatever the alignment; one server is past its comfortable size: plan a cluster.",IF(XmxGB>CompressedOopsMaxGB,"Over 31 GB: compressed oops are off unless -XX:ObjectAlignmentInBytes=16 (added below), or stay at 31g with fewer dates in memory.","OK"))'),
    ("WarnAutoNet", "Automatic safety net",
     'IF(AutoNetMB<DatesInMemory*HeapDate,"The automatic safety net ("&ROUND(AutoNetMB,0)&" MB) holds fewer than the "&DatesInMemory&" dates in memory need ("&ROUND(DatesInMemory*HeapDate,0)&" MB): set drishti.var.open-heap-mb to "&SetOpenHeapMB&".","OK")'),
    ("WarnNetOneDate", "Safety net under one date",
     'IF(AutoNetMB<HeapDate,"The automatic safety net is smaller than one date: dates in use stay open over it (the net\'s "&NetGraceSeconds&"-second grace), so set open-heap-mb at least to the dates in use × the date heap ("&ROUND(HeapDate,0)&" MB a date).","OK")'),
    ("WarnZgcRss", "ZGC resident memory",
     'IF(GcChoice="ZGC","ZGC: measured about twice G1\'s resident memory at the same -Xmx, with allocation stalls (200,000 positions); the RAM line counts the whole heap resident. Choose G1 when RAM is tight.","OK")'),
    ("WarnExposureLoad", "Exposure load",
     'IF(ExpLoadMB>ExpShardLoadMB,"Loading an exposure date needs "&ROUND(ExpLoadMB/1024,0)&" GB of heap for the netting sets\' value matrices: past about "&ROUND(ExpShardLoadMB/1024,0)&" GB the load should be sharded (a cluster).","OK")'),
    ("WarnSingleNode", "One server's limit",
     'IF(OR(XmxGB>AlignedOopsMaxGB,RamRecommendedGB>SingleNodeRamGB),"Beyond what one server should carry: plan a cluster (the cluster sheet comes later).","OK")'),
    ("WarnPageCache", "Page cache",
     'IF(PageCacheMB>XmxGB*1024*2,"The open dates\' hot files ("&ROUND(PageCacheMB/1024,0)&" GB) are larger than twice the heap: with less RAM than recommended, questions on dates not resident read the disk first.","OK")'),
]

# The figures the Explain tab states at the defaults; tools/test_sizing_planner.py checks the formulas reproduce them.
EXPECTED = {
    "HbFormula": 743.8, "HeapBook": 584.0, "HeapBase": 676.0, "HeapDates": 6424.0, "HeapSteady": 7970.9, "HeapLoadTransient": 3328.0,
    "XmxGB": 17, "SetOpenHeapMB": 6464, "AutoNetMB": 3251.2, "HotFileBookMB": 2994.2, "PageCacheMB": 22330.8, "RamRecommendedGB": 48,
    "RamMinimumGB": 24, "DiskLakeVarGB": 352.8, "DiskHotGB": 175.4, "DiskUsedGB": 601.6, "DiskRecommendedGB": 700, "DiskLocalRecommendedGB": 300,
    "CoresRecommended": 12, "FirstOpenSec": 18.5, "CalHeapStart": 740.0, "CalHeapOne": 1602.0, "CalHeapTwo": 2186.0, "CalRssStart": 2708.0,
    "CalRssOne": 5884.0, "CalRssTwo": 6797.0, "CalLakeTwoGB": 2.8, "CalWarmHeap": 2309.2, "CalRssEnd": 11681.2, "CalReqPerSec": 183.3,
}

CALIBRATION = [
    ("CalHeapStart", "Heap after GC at start", "HeapBase+FooterCacheMB", "MB", "Measured 740 MB (1,000,000 positions, 6 GB heap, Java 21)."),
    ("CalHeapOne", "Heap after GC, one date open", "CalHeapStart+HeapFirstOpen+HeapDate", "MB", "Measured 1,602 MB."),
    ("CalHeapTwo", "Heap after GC, two dates open", "CalHeapOne+HeapDate", "MB", "Measured 2,186 MB."),
    ("CalRssStart", "RSS at start (at a 6 GB heap)", "RssStartMB", "MB", "Measured 2,708 MB; RSS depends on -Xmx, so this row holds at the measured shape only."),
    ("CalRssOne", "RSS, one date open", "CalRssStart+(CalHeapOne-CalHeapStart)+ResidentFirstIdle*HotFileBookMB", "MB", "Measured 5,884 MB."),
    ("CalRssTwo", "RSS, two dates open", "CalRssOne+HeapDate+ResidentNextIdle*HotFileBookMB", "MB", "Measured 6,797 MB."),
    ("CalLakeTwoGB", "Lake, two dates", "2*LakeVarDateMB/1024", "GB", "Measured 2.8 GB."),
    ("CalFirstOpen", "First open of a date", "FirstOpenSec", "s", "Measured 18 to 21 s; the lake load 28.4 s for two dates."),
    ("CalWarmHeap", "Heap after GC, warm at 50 users", "CalHeapTwo+Users*PerUserMB", "MB", "Measured 2,336 MB (in the users run, whose two-date heap was 2,213 MB)."),
    ("CalRssEnd", "RSS at the end of the users run (6 GB heap)", "MeasuredXmxMB+NativeMB+ResidentShare*2*HotFileBookMB", "MB",
     "Measured 11,160 MB; the planner's native memory (1,071 MB) is more than the 550 MB NMT saw beyond the heap, so it lands a little above."),
    ("CalReqPerSec", "Requests a second on the benchmark machine", "MeasuredCores*MixRatePerCore", "requests/s", "Measured 183.3 at 50 users, half VaR, 0 failures."),
]


# ---------------------------------------------------------------------------------------------------------------- the evaluator
_TOKEN = re.compile(r'\s*(?:(?P<num>\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(?P<str>"(?:[^"]|"")*")|(?P<name>[A-Za-z_][A-Za-z0-9_.]*)|(?P<op><=|>=|<>|[-+*/^&=<>(),]))')


def _tokens(f: str) -> list:
    out, i = [], 0
    while i < len(f):
        m = _TOKEN.match(f, i)
        if not m or m.end() == i:
            if f[i:].strip() == "":
                break
            raise ValueError(f"cannot read {f[i:]!r} in {f!r}")
        i = m.end()
        kind = m.lastgroup
        val = m.group(kind)
        out.append((kind, val[1:-1].replace('""', '"') if kind == "str" else val))
    return out


def names_in(formula: str) -> set:
    """The names a formula refers to (functions excluded)."""
    toks = _tokens(formula)
    return {v for i, (k, v) in enumerate(toks) if k == "name" and not (i + 1 < len(toks) and toks[i + 1] == ("op", "("))}


def _text(v) -> str:
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(int(v)) if float(v).is_integer() else repr(float(v))
    return str(v)


def _num(v) -> float:
    if isinstance(v, str):
        raise ValueError(f"text {v!r} used as a number")
    return float(v)


class Evaluator:
    """Evaluates the workbook's formulas by name: {name: number, text or formula string starting with '='}."""

    def __init__(self, cells: dict):
        self.cells, self.memo = cells, {}

    def value(self, name: str):
        if name not in self.memo:
            c = self.cells[name]
            self.memo[name] = self.eval(c[1:]) if isinstance(c, str) and c.startswith("=") else c
        return self.memo[name]

    def eval(self, formula: str):
        saved = getattr(self, "toks", None), getattr(self, "pos", 0)  # a name's own formula is read in the middle of another
        self.toks, self.pos = _tokens(formula), 0
        try:
            v = self._compare()
            if self.pos != len(self.toks):
                raise ValueError(f"unexpected {self.toks[self.pos]} in {formula!r}")
            return v
        finally:
            self.toks, self.pos = saved

    def _peek(self):
        return self.toks[self.pos] if self.pos < len(self.toks) else (None, None)

    def _take(self, op=None):
        t = self._peek()
        if op is not None and t != ("op", op):
            raise ValueError(f"expected {op}, found {t}")
        self.pos += 1
        return t

    def _compare(self):
        a = self._concat()
        while self._peek()[0] == "op" and self._peek()[1] in ("=", "<>", "<", ">", "<=", ">="):
            op = self._take()[1]
            b = self._concat()
            x, y = (a.lower(), b.lower()) if isinstance(a, str) and isinstance(b, str) else (a, b)
            a = {"=": x == y, "<>": x != y, "<": x < y, ">": x > y, "<=": x <= y, ">=": x >= y}[op]
        return a

    def _concat(self):
        a = self._add()
        while self._peek() == ("op", "&"):
            self._take()
            a = _text(a) + _text(self._add())
        return a

    def _add(self):
        a = self._mul()
        while self._peek()[0] == "op" and self._peek()[1] in "+-" and self._peek()[1] != "":
            op = self._take()[1]
            b = self._mul()
            a = _num(a) + _num(b) if op == "+" else _num(a) - _num(b)
        return a

    def _mul(self):
        a = self._pow()
        while self._peek()[0] == "op" and self._peek()[1] in ("*", "/"):
            op = self._take()[1]
            b = self._pow()
            a = _num(a) * _num(b) if op == "*" else _num(a) / _num(b)
        return a

    def _pow(self):
        a = self._unary()
        while self._peek() == ("op", "^"):
            self._take()
            a = _num(a) ** _num(self._unary())
        return a

    def _unary(self):
        if self._peek() == ("op", "-"):
            self._take()
            return -_num(self._unary())
        return self._atom()

    def _atom(self):
        kind, v = self._take()
        if kind == "num":
            return float(v)
        if kind == "str":
            return v
        if kind == "op" and v == "(":
            x = self._compare()
            self._take(")")
            return x
        if kind == "name":
            if self._peek() == ("op", "("):
                return self._call(v.upper())
            return self.value(v)
        raise ValueError(f"unexpected {v!r}")

    def _call(self, fn: str):
        self._take("(")
        if fn == "IF":
            cond = self._compare()
            self._take(",")
            a = self._compare()
            self._take(",")
            b = self._compare()
            self._take(")")
            return a if cond else b
        args = []
        if self._peek() != ("op", ")"):
            args.append(self._compare())
            while self._peek() == ("op", ","):
                self._take()
                args.append(self._compare())
        self._take(")")
        if fn == "MAX":
            return max(_num(a) for a in args)
        if fn == "MIN":
            return min(_num(a) for a in args)
        if fn == "CEILING":
            x, s = _num(args[0]), _num(args[1])
            return math.ceil(x / s - 1e-12) * s
        if fn == "AND":
            return all(bool(a) for a in args)
        if fn == "OR":
            return any(bool(a) for a in args)
        if fn == "INT":
            return float(math.floor(_num(args[0])))
        if fn == "ROUND":
            return float(round(_num(args[0]), int(_num(args[1]))))
        raise ValueError(f"function {fn} is not supported")


# ---------------------------------------------------------------------------------------------------------------- the explanation
def explain() -> list:
    """(title, text) rows of the Explain tab; figures in braces are EXPECTED's, formatted."""
    e = {k: (f"{v:,.0f}" if abs(v) >= 100 else f"{v:,.1f}".rstrip("0").rstrip(".")) for k, v in EXPECTED.items()}
    rows = [
        ("In plain words: what this workbook answers",
         "Before a large deployment you need to know how much memory, disk and CPU one Drishti server needs, and which settings to give it. "
         "Type what you expect (positions, dates kept in memory, users, the Java version) into the yellow cells of Inputs; Results then gives, "
         "line by line, the heap each part takes, the -Xmx to set, the machine's RAM, the disk by folder, the cores, the exact configuration keys "
         "with their values, the JVM arguments ready to paste, and warnings in words when a choice is risky. Every figure is an Excel formula you can "
         "follow; every constant it uses is on Coefficients, with where it came from, so you can tune it when you measure your own server."),
        ("How to use it",
         "1. Fill Inputs (yellow). 2. Read Results from the top: the heap terms, then the heap to set, page cache and RAM, disk, CPU, the settings and "
         "the JVM arguments; read every warning that is not OK. 3. Copy the settings into the server's configuration and the command line into its "
         "start script. 4. After the server runs with real data, compare Admin → Caches and Health with the lines here, and correct a coefficient "
         "(its status says how sure it is) when they differ. If your spreadsheet program does not recalculate on opening, press F9 (Excel) or "
         "Ctrl+Shift+F9 (LibreOffice)."),
        ("Units", "MB is 1,024 × 1,024 bytes and GB is 1,024 MB, as Java and Admin → Caches count them. Times are seconds."),
        ("Coefficients and their status",
         "measured: read in a benchmark or a test (the source names the section). formula: a rule of the code or the guides (VAR_GUIDE.md, "
         "RESOURCES_AND_SCALE.md, CONFIGURATION.md). calibrated: set so that the defaults reproduce the 1,000,000-position re-benchmark (Java 21, "
         "6 GB heap): BookHeapCalibration, FirstOpenOnceMB, BasePerPositionB, BaseTradeRows, HotSmallBytesPerTrade, LakeBytesPerValue, "
         "ResidentFirstIdle, ResidentNextIdle, ResidentShare, ExpHotBytesPerTrade, and ReqPerCoreVarAtRef from the users run. The users' "
         "figures are PerUserMB (working memory per concurrent user, measured) and ReqPerCoreTrades / ReqPerCoreVarAtRef (requests a second per "
         "core): change them in one place when you measure your own server. estimate: a reasoned guess to replace by a measurement."),
    ]
    for sec, name, label, default, unit, kind, hint in INPUTS:
        rows.append((f"Input: {label}", f"{hint} Default {default if kind == 'text' else f'{default:,}'} ({unit}); named {name}."))
    terms = [
        ("Heap: one VaR book",
         "A business date's VaR data lives in three places: the lake (every date, on disk), a hot file (one per open date, on local disk, mapped into "
         "memory: the P&L rows) and the heap (the date's ids, columns, nodes and links). The heap of one book is VAR_GUIDE.md section 27.1's "
         "H_book, term by term: node members as runs (8·R), names and runs (~150 bytes a book, cell or node), the aggregates' index (64·A), the "
         "columns (108·T + 136·P), the ids (72·(2T + 3P), the largest part), the per-currency totals of every node (8·(S+1)·N·C) and the portfolios. "
         "The sum is scaled by BookHeapCalibration to what the server measured."),
        ("Heap: one date and the dates in memory",
         "A date is its USD book plus one book per reporting currency built ahead and one for the stress set. The server keeps hot-dates dates open; "
         "the date just used, the newest date and the dates pinned in memory always stay, so if those alone are more than hot-dates, the planner counts "
         "them instead. While a new date opens, the date it pushes out stays open until the requests reading it finish, so the peak is one date more."),
        ("Heap: the server, caches and users",
         "At start, before any date is open, the server already holds the lake's footers and an index of the P&L rows (HeapBase, measured). The first "
         "date opened warms caches once (HeapFirstOpen). Each cache is counted at its budget: the Parquet footer cache, the type-ahead index, BI's "
         "query and plan caches, the history answers and VaR's answers. Trade rows beyond those measured add the trade connectors' working set, and "
         "each concurrent user's request needs working memory (PerUserMB, measured: about 2.5 MB)."),
        ("Heap: loads in progress",
         "While a date loads, the build holds its aggregates and currency totals on the heap (1.6 GB at 1,000,000 positions, VarProperties) plus a read "
         "buffer; drishti.var.load-threads dates may load at once. An exposure load holds every netting set's value matrix in float64."),
        ("The heap to set (-Xmx)",
         "The collector needs room: the heap after collection should be at most 61% of -Xmx (RESOURCES_AND_SCALE.md 9.5). So -Xmx = the steady heap ÷ "
         "0.61 + the loads in progress, rounded up to a whole GB. Under 32 GB Java keeps 4-byte object references (compressed oops); at 32 GB and above "
         "they double in size unless -XX:ObjectAlignmentInBytes=16, which keeps them compressed up to 64 GB: so either stay at 31g or go well past 32."),
        ("Page cache and RAM",
         "The hot files are read through the operating system's page cache, outside the heap: a question over the whole bank reads every row of a date. "
         "Recommended RAM keeps every open date's hot files resident, plus the heap (fully committed after a while), native memory (metaspace, direct "
         "buffers, threads, the allocator), the operating system and the console. The smallest workable RAM keeps only the date being read resident: "
         "it works, but a question on another date reads the disk first."),
        ("Disk, by folder",
         "The lake keeps every date for the years you keep; var/hot keeps up to 60 hot files (disk-dates) plus the pinned dates on disk and currency books "
         "for the 5 newest dates; tmp/server needs three times the largest hot file for each load running (the disk guard checks this before a build); "
         "logs rotate; a heap dump is the heap's size; the trade connectors keep id maps, grouped pages, a document cache and the type-ahead index; BI "
         "keeps layers and runs. The recommendation leaves the free-space guard's margin: 10% of the disk and 20 GB free. When the lakes live on object "
         "storage, the local-disk line is the one to buy fast (NVMe)."),
        ("CPU",
         "Each core answers about 22.5 trade questions a second and, at 1,000,000 positions, about 11.6 VaR questions a second (measured on a machine "
         "of 12 cores and 24 hardware threads: 183 requests a second at 50 users, half of them VaR, none failed). A VaR question's cost grows with the "
         "positions (VarCostExponent). The mix is weighed by your share of VaR questions; the cores (physical cores, two hardware threads each) are "
         "planned 70% busy at the peak, plus 2 for loads, the collector and the operating system. More users than the cores can serve do not fail, "
         "they wait: the same machine answered 138 requests a second at 100 users and 124 at 200, with p95 about 2 and 5 s."),
        ("The settings",
         "drishti.var.hot-dates: the dates in memory. drishti.var.open-heap-mb: the byte safety net over every open date's heap; when the dates open "
         "exceed it, the least recently used dates' currency books, then stress books, then whole dates close (never a protected one, never one read in "
         "the last 120 seconds). Its automatic value (0.4 × heap − 512 MB − load-threads × 1.6 GB) is often smaller than ten dates need, so the planner "
         "gives an explicit value. drishti.var.memory-budget-mb bounds the heap tier only and stays 0 on the mapped tier. -Ddrishti.parquet.footer-cache-mb "
         "is a JVM property. drishti.var.load-threads, drishti.var.tier.max-disk-gb, disk-dates, currencies, stress and the exposure dates follow."),
    ]
    rows += terms
    flags = [(f"JVM: {flag}", f"{why} When: {cond}. ({src}: {'from the docs and launchers' if src == 'documented' else 'beyond the docs, a suggestion'}.)")
             for _n, flag, _f, cond, why, src in JVM]
    rows += flags
    rows += [
        ("The command line",
         "Results assembles every argument into one line ready to paste: MALLOC_ARENA_MAX=2 java <arguments> -jar <the server jar>. On Windows leave out "
         "MALLOC_ARENA_MAX (a Linux setting) and use Windows paths for the data folder."),
        ("Worked example: the defaults (1,000,000 positions, 10 dates in memory, Java 21)",
         f"One book by the formula is {e['HbFormula']} MB; calibrated, {e['HeapBook']} MB. Ten dates in memory and one opening make 11 × "
         f"{e['HeapBook']} = {e['HeapDates']} MB. With the server at start ({e['HeapBase']} MB plus the 64 MB footer cache), the 278 MB the first date "
         f"warms, the caches' 406 MB and 50 users' 123 MB, the steady heap is {e['HeapSteady']} MB; two loads add {e['HeapLoadTransient']} MB. "
         f"-Xmx = {e['HeapSteady']} ÷ 0.61 + {e['HeapLoadTransient']} = about 16,395 MB, so -Xmx{e['XmxGB']}g, with compressed oops; at 16 GB and "
         f"above the documented collector is generational ZGC. drishti.var.open-heap-mb = 10 × {e['HeapBook']} × 1.1, rounded up to 64 MB: "
         f"{e['SetOpenHeapMB']} (the automatic net would be only {e['AutoNetMB']} MB). A hot file is {e['HotFileBookMB']} MB a date, and ten of them, 75% resident "
         f"under load as measured, take {e['PageCacheMB']} MB of page cache; with the heap and native memory, RAM = {e['RamRecommendedGB']} GB recommended "
         f"({e['RamMinimumGB']} GB the smallest workable). Disk: the VaR lake for a year {e['DiskLakeVarGB']} GB, 60 hot files {e['DiskHotGB']} GB, "
         f"{e['DiskUsedGB']} GB in all, {e['DiskRecommendedGB']} GB recommended ({e['DiskLocalRecommendedGB']} GB of local disk if the lakes live "
         f"elsewhere). CPU: {e['CoresRecommended']} cores for 100 requests a second, half of them VaR. A date's first open takes about {e['FirstOpenSec']} s."),
        ("Worked example: against the measured",
         f"At the defaults the Calibration check block reproduces the re-benchmark at 6 GB of heap: heap after GC {e['CalHeapStart']} MB at start, "
         f"{e['CalHeapOne']} MB with one date open, {e['CalHeapTwo']} MB with two; RSS {e['CalRssStart']}, {e['CalRssOne']} and {e['CalRssTwo']} MB; "
         f"{e['CalLakeTwoGB']} GB of lake for two dates. Against the users run (same size and heap): {e['CalWarmHeap']} MB warm at 50 users "
         f"(measured 2,336), RSS {e['CalRssEnd']} MB at the end (measured 11,160) and {e['CalReqPerSec']} requests a second on its 12 cores "
         f"(measured 183.3). The recommended -Xmx is larger than the 6 GB that held two dates because it adds ten dates, "
         "the loads running together, every cache at its budget, the users and the collector's headroom."),
        ("Limits",
         "One server only: a sharded cluster divides the hot files, the page cache and the loads among its members but copies each date's skeleton to "
         "every member, which this sheet does not model; a cluster sheet comes later. The requests a second per core were measured on one "
         "workstation; a server's cores may differ. The hot files' page cache is advisory: the server works with less RAM, more slowly. The figures are for the "
         "mapped tier (the default); the heap tier keeps every vector on the heap (HeapTierBudgetMB a date)."),
    ]
    return rows


# ---------------------------------------------------------------------------------------------------------------- the workbook
def _col(i: int) -> str:
    s = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s


def _bad_name(n: str) -> bool:
    return bool(re.fullmatch(r"[A-Za-z]{1,3}[0-9]+|[RrCc]|[Rr][0-9]*[Cc][0-9]*", n))


class Sheet:
    def __init__(self, name: str, styles: dict, tab: str):
        self.name, self.styles, self.tab = name, styles, tab
        self.rows, self.widths, self.merges, self.heights, self.cf, self.validations = {}, {}, [], {}, [], []
        self.freeze = 0

    def put(self, r: int, c: int, kind: str, value, style: str = "default", cached=None):
        self.rows.setdefault(r, {})[c] = (kind, value, style, cached)

    def xml(self) -> str:
        out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
               'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">',
               f'<sheetPr><tabColor rgb="FF{self.tab}"/></sheetPr><sheetViews><sheetView workbookViewId="0"'
               + (' tabSelected="1"' if self.name == "Inputs" else "") + ">"]
        if self.freeze:
            out.append(f'<pane ySplit="{self.freeze}" topLeftCell="A{self.freeze + 1}" activePane="bottomLeft" state="frozen"/>')
        out.append('</sheetView></sheetViews><sheetFormatPr defaultRowHeight="14"/><cols>')
        out += [f'<col min="{c + 1}" max="{c + 1}" width="{w}" customWidth="1"/>' for c, w in sorted(self.widths.items())]
        out.append("</cols><sheetData>")
        for r in sorted(self.rows):
            ht = f' ht="{self.heights[r]}" customHeight="1"' if r in self.heights else ""
            out.append(f'<row r="{r}"{ht}>')
            for c in sorted(self.rows[r]):
                kind, v, style, cached = self.rows[r][c]
                ref, s = f"{_col(c)}{r}", self.styles[style]
                sa = f' s="{s}"' if s else ""
                if kind == "text":
                    out.append(f'<c r="{ref}"{sa} t="inlineStr"><is><t xml:space="preserve">{escape(v)}</t></is></c>')
                elif kind == "num":
                    out.append(f'<c r="{ref}"{sa}><v>{_text(v)}</v></c>')
                elif kind == "blank":
                    out.append(f'<c r="{ref}"{sa}/>')
                elif isinstance(cached, str):
                    out.append(f'<c r="{ref}"{sa} t="str"><f>{escape(v)}</f><v>{escape(cached)}</v></c>')
                elif isinstance(cached, bool):
                    out.append(f'<c r="{ref}"{sa} t="b"><f>{escape(v)}</f><v>{int(cached)}</v></c>')
                else:
                    out.append(f'<c r="{ref}"{sa}><f>{escape(v)}</f><v>{_text(round(cached, 9))}</v></c>')
            out.append("</row>")
        out.append("</sheetData>")
        if self.merges:
            out.append(f'<mergeCells count="{len(self.merges)}">' + "".join(f'<mergeCell ref="{m}"/>' for m in self.merges) + "</mergeCells>")
        out += self.cf
        if self.validations:
            out.append(f'<dataValidations count="{len(self.validations)}">' + "".join(self.validations) + "</dataValidations>")
        out.append('<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.6" header="0.2" footer="0.25"/>'
                   '<pageSetup orientation="landscape" fitToWidth="1" fitToHeight="0"/></worksheet>')
        return "".join(out)


def _styles() -> tuple[str, dict]:
    """The playground template's styles plus five of the planner's own (thousands, one decimal, totals, input numbers, the command line)."""
    xml = re.sub(r"<!--.*?-->\s*", "", (TEMPLATE / "xl" / "styles.xml").read_text(encoding="utf-8"), flags=re.S)
    names = {}
    for line in (TEMPLATE / "template.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("style."):
            k, v = line[6:].split("=")
            names[k.strip()] = int(v)
    n_fmt = int(re.search(r'<numFmts count="(\d+)">', xml).group(1))
    xml = xml.replace(f'<numFmts count="{n_fmt}">', f'<numFmts count="{n_fmt + 1}">').replace(
        "</numFmts>", '<numFmt numFmtId="170" formatCode="#,##0.0"/></numFmts>')
    n_font = int(re.search(r'<fonts count="(\d+)">', xml).group(1))
    xml = xml.replace(f'<fonts count="{n_font}">', f'<fonts count="{n_font + 1}">').replace(
        "</fonts>", '<font><sz val="9"/><color rgb="FF1F2937"/><name val="Consolas"/><family val="3"/></font></fonts>')
    n_xf = int(re.search(r'<cellXfs count="(\d+)">', xml).group(1))
    extra = [
        ("num0", '<xf numFmtId="168" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'),
        ("num1", '<xf numFmtId="170" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'),
        ("num.total", '<xf numFmtId="168" fontId="6" fillId="4" borderId="2" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>'),
        ("input.num", '<xf numFmtId="168" fontId="5" fillId="5" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/>'),
        ("code", f'<xf numFmtId="0" fontId="{n_font}" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>'),
    ]
    for i, (k, x) in enumerate(extra):
        names[k] = n_xf + i
    xml = xml.replace(f'<cellXfs count="{n_xf}">', f'<cellXfs count="{n_xf + len(extra)}">').replace("</cellXfs>", "".join(x for _, x in extra) + "</cellXfs>")
    return xml, names


def _height(text: str, width: float) -> float:
    lines = sum(max(1, math.ceil(len(p) / max(width * 1.15, 1))) for p in text.split("\n"))
    return max(14.0, 13.0 * lines + 2)


def build() -> dict:
    """The workbook's parts: {path in the zip: text}."""
    version, brand = _version(), _branding()
    styles_xml, st = _styles()
    cells, where = {}, {}  # name -> value or "=formula"; name -> (sheet, row, col)
    for _s, name, _l, default, _u, kind, _h in INPUTS:
        cells[name] = default.replace("{VERSION}", version) if isinstance(default, str) else default
    for name, value, *_ in COEFFS:
        cells[name] = value
    results = _results()
    for item in results:
        if item[0] not in ("band", "note"):
            cells[item[0]] = "=" + item[2]
    for name, _flag, f, *_ in JVM:
        cells[name] = "=" + f
    cells["JvmCommand"] = "=" + _command_formula()
    for name, _l, f in WARNINGS:
        cells[name] = "=" + f
    for name, _l, f, *_ in CALIBRATION:
        cells[name] = "=" + f
    ev = Evaluator(cells)
    for n in cells:
        if _bad_name(n):
            raise ValueError(f"{n} is not a valid Excel name")
        ev.value(n)

    # Inputs
    inp = Sheet("Inputs", st, "1F3A5F")
    inp.widths = {0: 46, 1: 20, 2: 12, 3: 20, 4: 90}
    inp.put(1, 0, "text", f"{brand['product']} · Single-node sizing planner: inputs", "title")
    for c in range(1, 5):
        inp.put(1, c, "blank", None, "title")
    inp.put(2, 0, "text", "Type your expected loads into the yellow cells; Results recomputes everything. Defaults are the 1,000,000-position benchmark's shape.", "note")
    inp.put(3, 0, "text", "Input", "head")
    for c, h in enumerate(["Value", "Unit", "Name", "Hint"], 1):
        inp.put(3, c, "text", h, "head")
    inp.freeze = 3
    r, sec = 4, None
    for s, name, label, default, unit, kind, hint in INPUTS:
        if s != sec:
            sec = s
            inp.put(r, 0, "text", s, "band")
            for c in range(1, 5):
                inp.put(r, c, "blank", None, "band")
            r += 1
        v = cells[name]
        style = "input.text" if kind == "text" else ("input.num" if kind == "int" else "input.dec")
        inp.put(r, 0, "text", label, "label")
        inp.put(r, 1, "text" if kind == "text" else "num", v, style)
        inp.put(r, 2, "text", unit, "text")
        inp.put(r, 3, "text", name, "note")
        inp.put(r, 4, "text", hint, "wrap")
        where[name] = ("Inputs", r, 1)
        if name == "Collector":
            inp.validations.append(f'<dataValidation type="list" allowBlank="0" showErrorMessage="1" sqref="B{r}"><formula1>"auto,G1,ZGC"</formula1></dataValidation>')
        if name == "JavaVersion":
            inp.validations.append(f'<dataValidation type="list" allowBlank="0" showErrorMessage="1" sqref="B{r}"><formula1>"21,25"</formula1></dataValidation>')
        if name in ("StressOn", "ExposureOn"):
            inp.validations.append(f'<dataValidation type="list" allowBlank="0" showErrorMessage="1" sqref="B{r}"><formula1>"0,1"</formula1></dataValidation>')
        r += 1

    # Coefficients
    co = Sheet("Coefficients", st, "6B7686")
    co.widths = {0: 26, 1: 14, 2: 18, 3: 12, 4: 120}
    co.put(1, 0, "text", "Coefficients: every constant Results uses (tune them here; Results never hard-codes one)", "title")
    for c in range(1, 5):
        co.put(1, c, "blank", None, "title")
    co.put(2, 0, "text", "measured: read in a benchmark · formula: a rule of the code or the guides · calibrated: set so the defaults reproduce the re-benchmark · estimate: to replace by a measurement", "note")
    for c, h in enumerate(["Name", "Value", "Unit", "Status", "Source"]):
        co.put(3, c, "text", h, "head")
    co.freeze = 3
    for i, (name, value, unit, status, source) in enumerate(COEFFS, 4):
        co.put(i, 0, "text", name, "label")
        co.put(i, 1, "num", value, "input.num" if float(value).is_integer() and abs(value) >= 1 else "input.dec")
        co.put(i, 2, "text", unit, "text")
        co.put(i, 3, "text", status, "text")
        co.put(i, 4, "text", source, "wrap")
        co.heights[i] = _height(source, 120)
        where[name] = ("Coefficients", i, 1)

    # Results
    rs = Sheet("Results", st, "2E5A88")
    rs.widths = {0: 44, 1: 18, 2: 11, 3: 70, 4: 22, 5: 90}
    rs.put(1, 0, "text", "Results: memory, disk, CPU, settings and JVM arguments for one server", "title")
    for c in range(1, 6):
        rs.put(1, c, "blank", None, "title")
    rs.put(2, 0, "text", "Every value is a formula over the named cells of Inputs and Coefficients (column Formula shows it). Read every warning that is not OK.", "note")
    for c, h in enumerate(["Item", "Value", "Unit", "Why", "Name", "Formula"]):
        rs.put(3, c, "text", h, "head")
    rs.freeze = 3
    r = 4

    def band_row(title):
        nonlocal r
        rs.put(r, 0, "text", title, "band")
        for c in range(1, 6):
            rs.put(r, c, "blank", None, "band")
        r += 1

    def result_row(name, label, formula, unit, why, style=None):
        nonlocal r
        v = ev.value(name)
        if style is None:
            style = "text" if isinstance(v, str) else ("num0" if unit in ("MB", "rows", "requests/s") and abs(v) >= 100 else "num1" if unit not in ("factor", "share") else "rate")
        if name in ("XmxGB", "RamRecommendedGB", "DiskRecommendedGB", "CoresRecommended", "SetOpenHeapMB"):
            style = "num.total"
        rs.put(r, 0, "text", label, "label.wrap")
        rs.put(r, 1, "formula", formula, style, v)
        rs.put(r, 2, "text", unit if unit != "text" else "", "text")
        rs.put(r, 3, "text", why, "wrap")
        rs.put(r, 4, "text", name, "note")
        rs.put(r, 5, "text", "=" + formula, "code")
        rs.heights[r] = max(_height(why, 70), _height(formula, 100), _height(label, 44), _height(_text(v), 18) if isinstance(v, str) else 14)
        where[name] = ("Results", r, 1)
        r += 1

    for item in results:
        if item[0] == "band":
            band_row(item[1])
        else:
            result_row(*item)
    band_row("JVM arguments (documented: from the docs and launchers; suggested: beyond them)")
    for name, flag, f, cond, why, src in JVM:
        result_row(name, flag, f, "text", f"{why} When: {cond}. [{src}]")
    band_row("The command line, ready to paste")
    v = ev.value("JvmCommand")
    rs.put(r, 0, "text", "java ... -jar", "label")
    rs.put(r, 1, "formula", _command_formula(), "code", v)
    rs.merges.append(f"B{r}:F{r}")
    rs.heights[r] = _height(v, 200) + 6
    where["JvmCommand"] = ("Results", r, 1)
    r += 1
    band_row("Warnings (OK when nothing to say)")
    first = r
    for name, label, f in WARNINGS:
        v = ev.value(name)
        rs.put(r, 0, "text", label, "label")
        rs.put(r, 1, "formula", f, "wrap", v)
        rs.merges.append(f"B{r}:D{r}")
        rs.put(r, 4, "text", name, "note")
        rs.put(r, 5, "text", "=" + f, "code")
        rs.heights[r] = max(_height(v, 100), _height(f, 100))
        where[name] = ("Results", r, 1)
        r += 1
    rs.cf.append(f'<conditionalFormatting sqref="B{first}:B{r - 1}"><cfRule type="cellIs" dxfId="0" priority="1" operator="equal"><formula>"OK"</formula></cfRule>'
                 f'<cfRule type="cellIs" dxfId="1" priority="2" operator="notEqual"><formula>"OK"</formula></cfRule></conditionalFormatting>')
    band_row("Calibration check: the defaults against the 1,000,000-position re-benchmark (holds at the defaults only)")
    for item in CALIBRATION:
        result_row(*item)

    # Explain
    ex = Sheet("Explain", st, "C00000")
    ex.widths = {0: 40, 1: 150}
    ex.put(1, 0, "text", "Explain: the sizing planner in plain words", "title")
    ex.put(1, 1, "blank", None, "title")
    for i, (title, text) in enumerate(explain(), 2):
        ex.put(i, 0, "text", title, "label.wrap")
        ex.put(i, 1, "text", text, "wrap")
        ex.heights[i] = max(_height(text, 150), _height(title, 40))

    sheets = [inp, rs, co, ex]
    defined = "".join(f'<definedName name="{n}">{s}!${_col(c)}${row}</definedName>' for n, (s, row, c) in sorted(where.items(), key=lambda x: x[0].lower()))
    missing = set(cells) - set(where)
    if missing:
        raise ValueError(f"names without a cell: {sorted(missing)}")
    parts = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        + "".join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in range(1, len(sheets) + 1))
        + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
        '<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>',
        "docProps/core.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
        f"<dc:title>{escape(brand['product'])} sizing planner</dc:title><dc:subject>Single-node sizing</dc:subject><dc:creator>{escape(brand['product'])}</dc:creator>"
        f"<dc:description>{escape(brand['copyright'])}</dc:description></cp:coreProperties>",
        "docProps/app.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
        f"<Application>{escape(brand['product'])}</Application><Company>{escape(brand['owner'])}</Company><DocSecurity>0</DocSecurity></Properties>",
        "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + "".join(f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i}.xml"/>' for i in range(1, len(sheets) + 1))
        + f'<Relationship Id="rId{len(sheets) + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
        "xl/workbook.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView activeTab="0"/></bookViews><sheets>'
        + "".join(f'<sheet name="{s.name}" sheetId="{i}" r:id="rId{i}"/>' for i, s in enumerate(sheets, 1))
        + f'</sheets><definedNames>{defined}</definedNames><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>',
        "xl/styles.xml": styles_xml,
    }
    for i, s in enumerate(sheets, 1):
        parts[f"xl/worksheets/sheet{i}.xml"] = s.xml()
    return parts


def write(parts: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".part")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name in parts:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, parts[name].encode("utf-8"))
    tmp.replace(path)


def read(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        return {n: z.read(n).decode("utf-8") for n in z.namelist()}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", help="the folder to write the workbook into (default docs/admin/sizing)")
    ap.add_argument("--check", action="store_true", help="exit 1 when the committed workbook differs from a fresh build")
    a = ap.parse_args(argv)
    parts = build()
    if a.check:
        if not OUT.exists() or read(OUT) != parts:
            print(f"{OUT.relative_to(ROOT)} is out of date: run python3 tools/sizing_planner.py", file=sys.stderr)
            return 1
        print(f"{OUT.relative_to(ROOT)} is current")
        return 0
    path = Path(a.out) / OUT.name if a.out else OUT
    write(parts, path)
    print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
