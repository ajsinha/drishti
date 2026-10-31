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

"""CORR computed from price history (since 2.9.2; docs/guides/PACK_DEVELOPER_GUIDE.md › Part VII. Groupings and analytics, computed, section 15): what make_packs.py adds to the
market-data pack's manifest. A correlation set (correlation-matrix, CORR-...) keeps only its definition: a name and its factors (the
equities' and FX pairs' ids). Its correlations are the computed kind correlation-result, worked out when asked by the server's Java
engine (CorrelationEngine) from the factors' daily closes in the lake, for a window and an estimator: no stored matrix, no stored
average."""
from __future__ import annotations

#: kind -> (mnemonic, label, id pattern, id field): the computed kind's id is the set's (CORR-EU-EQ), so no pattern claims it
KINDS = {"correlation-result": ("CORRC", "Correlations of a factor set (computed from price history)", None, None),
         "equity-beta": ("EQB", "An equity's beta against its index (computed from price history)", None, None)}
SERVED_BY = {"correlation-result": "correlation-engine", "equity-beta": "equity-analytics"}
#: the computed kinds: read as an ordinary kind, never stored, no search form
COMPUTED = ("correlation-result", "equity-beta")
#: the defaults the engine uses and the pages state: every return the history holds, the sample (Pearson) correlation
DEFAULT_WINDOW = 0
DEFAULT_ESTIMATOR = "pearson"
CONNECTORS = {
    "correlation-engine": {
        "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_LAKE_ENABLED:true}",
        "description": "The correlations of a factor set computed when asked from its factors' daily closes (log returns over a window, "
                       "Pearson or Ledoit-Wolf), by the server's Java engine, as the viewer: CORRC CORR-EU-EQ <GO>",
        "kinds": {"correlation-result": {
            "compute": "correlation",
            "params": {"window": {"type": "int", "default": DEFAULT_WINDOW, "min": 0, "max": 5000},
                       "estimator": {"type": "enum", "options": ["pearson", "ledoit-wolf"], "default": DEFAULT_ESTIMATOR}},
            "result": {"required": ["AVERAGE", "PAIRS", "WINDOW"], "types": {"AVERAGE": "number", "PAIRS": "list", "WINDOW": "number"}}}},
        "reads": [{"kind": "correlation-matrix", "as": "set", "id": "{id}"},
                  {"kind": "equity", "as": "equities", "ids": "{inputs.set.EQUITY_IDS}", "limit": 200},
                  {"kind": "fx-spot", "as": "fx", "ids": "{inputs.set.FX_IDS}", "limit": 200}],
        "engine": {"type": "java", "class": "com.ash.drishti.compute.reactive.analytics.CorrelationEngine", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": ["correlation-matrix", "equity", "fx-spot"], "affects": "all"}],
        "coalesce": {"quiet": "250ms", "max-wait": "2s"},
        "cache": {"results": 500, "ttl": "1h"},
        "limits": {"timeout": "30s", "wait": "5s", "max-inflight-per-user": 4},
    },
}
CONNECTORS["equity-analytics"] = {
    "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_LAKE_ENABLED:true}",
    "description": "An equity's beta against its index computed when asked from both price histories (the regression of the daily log "
                   "returns), by the server's Java engine, as the viewer: EQB EQ-NVTK <GO>",
    "kinds": {"equity-beta": {
        "compute": "beta",
        "params": {"window": {"type": "int", "default": DEFAULT_WINDOW, "min": 0, "max": 5000}},
        "result": {"required": ["BETA", "INDEX", "WINDOW"], "types": {"BETA": "number", "INDEX": "text", "WINDOW": "number"}}}},
    "reads": [{"kind": "equity", "as": "equity", "id": "{id}"},
              {"kind": "equity-index", "as": "index", "ids": "{inputs.equity.EQUITY_INDEX}", "limit": 1}],
    "engine": {"type": "java", "class": "com.ash.drishti.compute.reactive.analytics.BetaEngine", "version": "1.0"},
    "triggers": [{"on": "data-version", "kinds": ["equity", "equity-index"], "affects": "all"}],
    "coalesce": {"quiet": "250ms", "max-wait": "2s"},
    "cache": {"results": 500, "ttl": "1h"},
    "limits": {"timeout": "30s", "wait": "5s", "max-inflight-per-user": 4},
}
EXAMPLES = [("CORR CORR-EU-EQ", "European equities · correlations computed from the factors' price history (window and estimator on the page)"),
            ("CORRC CORR-G10FX~estimator=ledoit-wolf", "The computed kind itself: G10 FX correlations, Ledoit-Wolf shrinkage"),
            ("EQB EQ-NVTK", "Novatek's beta against the Nasdaq-100, computed from both price histories")]
