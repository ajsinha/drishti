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

"""The VaR engine's kinds in the market-risk pack (docs/guides/VAR_GUIDE.md): what make_packs.py adds to the pack's manifest beside the
taxonomy's kinds. The engine's inputs (P&L vectors, scenario set, hierarchies, portfolio definitions) are written by
tools/packgen/var/make_var_data.py into data/testdata/market-risk/ and served by the pack's Delta connector risk-store, the P&L vectors
as a list<double> column (``vectors:``). Positions are Drishti's: derived from the trades by the pack's rules when a date loads and
served from the engine by the var-positions connector. No kind stores a VaR figure: the pages show the engine's, computed when opened.
The computed kinds (VAR_GUIDE.md section 8) are served by reactive connectors: var-result by the server's Java engine (var-engine), var-whatif
by the Python engine in the compute pool "var" (var-whatif), each read as the viewer. The published official end-of-day results
(risk-results/var-official, written by the server's VarPublisher) are the read-only kind var-official of the Delta connector var-results,
for BI and other readers only: no engine reads it (tools/test_contracts.py).

Since 2.9.4 a bank may send a trade's scenario P&L as **scenario PVs** instead of a P&L vector: the trade's PV under each scenario and its
base PV, in the trade's own currency (``scenario-pv``, the vector column SCENARIO_PV), with each currency's scenario FX when the batch
supplies it (``fx-scenario``; else the server derives it from the FX history of the market-data pack's fx-spot). The server converts them to
USD P&L when the date loads, and every VaR answer takes a reporting currency (the parameter ccy of var-result and var-whatif)."""
from __future__ import annotations

#: kind -> (mnemonic, label, id pattern, id field)
KINDS = {
    "position": ("POS", "Position", "^POS-", "POSITION_ID"),
    "portfolio": ("PORT", "Portfolio", "^PF-", "PORTFOLIO_ID"),
    "pnl-vector": ("PNLV", "P&L vector (509 scenarios)", "^PV-", "VECTOR_ID"),
    "scenario-pv": ("SPV", "Scenario PVs of a trade (509 scenarios, its currency)", "^SPV-", "SCENARIO_PV_ID"),
    "fx-scenario": ("FXS", "Scenario FX of a currency (509 scenarios)", "^FXS-", "FX_SCENARIO_ID"),
    "scenario-set": ("SSET", "Scenario set", "^HS-", "SCENARIO_SET_ID"),
    "risk-hierarchy": ("HIER", "Risk hierarchy node", "^(MGMT|LEGAL)~", "NODE_ID"),
    # the computed kinds: the id is a scope (DESK:RATES), so no id pattern claims them
    "var-result": ("VRES", "VaR of a scope (computed)", None, None),
    "var-whatif": ("VWIF", "VaR what-if (computed, Python)", None, None),
    # the published official results: one row per node, business date and result version (id <NODE_ID>@<date>#v<n>); no pattern
    "var-official": ("VOFF", "VaR official results (published)", None, None),
}
#: kinds not served by risk-store: the connector each is routed to
SERVED_BY = {"position": "var-positions", "var-result": "var-engine", "var-whatif": "var-whatif", "var-official": "var-results"}
_INPUTS = ["pnl-vector", "scenario-pv", "fx-scenario", "risk-hierarchy", "scenario-set", "portfolio"]
#: the reporting currency every VaR answer takes (2.9.4): USD unless asked, any currency with scenario FX on the date
_CCY = {"ccy": {"type": "text", "default": "USD"}}
#: the connectors besides risk-store: the positions the server derives, from the VaR engine's memory; the computed kinds
CONNECTORS = {
    # read only: the table the server's VarPublisher appends the official captures to (VAR_GUIDE.md section 19); BI reads it, no engine does
    "var-results": {
        "plugin": "delta", "enabled": "${DRISHTI_VAR_ENABLED:true}",
        "description": "The official end-of-day VaR results the server publishes (risk-results/var-official), read only: for BI and "
                       "other readers, never an engine's input",
        "kinds": ["var-official"],
        "settings": {"root": "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", "domain": "risk-results",
                     "layout": {"var-official": {"columns": ["HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL", "CAPTURE_ID",
                                                             "RESULT_VERSION", "VAR", "ES", "LIMIT", "LIMIT_USE", "DIVERSIFICATION", "HYPO_PNL",
                                                             "EXCEPTIONS_250", "TRAFFIC_LIGHT"]}}},
    },
    "var-positions": {"plugin": "var-positions", "enabled": "${DRISHTI_VAR_ENABLED:true}", "kinds": ["position"]},
    # 2.9.4: the PV topic of the pricing systems, windowed into intraday batches of the lake (VAR_GUIDE.md section 8.4); off by default,
    # as every Kafka connector of the packs
    "pv-updates": {"plugin": "var-pv-stream", "enabled": "${DRISHTI_VAR_PV_STREAM:false}", "kinds": ["pv-update"],
                   "settings": {"bootstrap-servers": "${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}", "topic": "${DRISHTI_VAR_PV_TOPIC:drishti.risk.pv-updates}",
                                "lake": "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", "pack": "market-risk"}},
    "var-engine": {
        "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_VAR_ENABLED:true}",
        "description": "The VaR of any scope, computed when asked by the server's VaR engine (the additive aggregation over the hot store), "
                       "as the viewer: VRES <scope> <GO>",
        "kinds": {"var-result": {"compute": "var-result", "params": _CCY,
                                 "result": {"required": ["VAR", "ES", "PNL"], "types": {"VAR": "number", "ES": "number"}}}},
        "engine": {"type": "java", "class": "com.ash.drishti.server.var.VarResultEngine", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": _INPUTS, "affects": "all"}],
        "coalesce": {"quiet": "250ms", "max-wait": "2s"},
        "cache": {"results": 2000, "ttl": "1h"},
        "limits": {"timeout": "60s", "wait": "5s", "max-inflight-per-user": 4},
    },
    "var-whatif": {
        "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_VAR_ENABLED:true}",
        "description": "A scope's VaR before and after hypothetical trades (a private sandbox) and its stressed window, computed by the "
                       "Python VaR engine in the compute pool var from the scope's var-result: VWIF <scope> <GO>",
        "kinds": {"var-whatif": {"compute": "var-whatif",
                                 "params": {**_CCY, "sandbox": {"type": "sandbox-ref"},
                                            "stress-window": {"type": "int", "default": 250, "min": 0, "max": 5000},
                                            "component-window": {"type": "int", "default": 5, "min": 1, "max": 51}},
                                 "result": {"required": ["BEFORE_VAR", "AFTER_VAR"],
                                            "types": {"BEFORE_VAR": "number", "AFTER_VAR": "number"}}}},
        "reads": [{"kind": "var-result", "as": "base", "id": "{id}~ccy={params.ccy}"}],
        "engine": {"type": "python", "entry": "drishti_compute.var.whatif:VarWhatIfEngine", "pool": "var", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": _INPUTS, "affects": "all"}],
        "coalesce": {"quiet": "250ms", "max-wait": "2s"},
        "cache": {"results": 500, "ttl": "1h"},
        "limits": {"timeout": "60s", "wait": "250ms", "max-inflight-per-user": 2},
    },
}
#: link fields the kinds carry (field -> (kind, label)); the hierarchy fields (TT_BOOK, LEGAL_ENTITY, COUNTERPARTY) link through the
#: core and trading packs' own fields
FIELDS = {"POSITION_ID": ("position", "Position"), "PORTFOLIO_ID": ("portfolio", "Portfolio"), "NODE_ID": ("risk-hierarchy", "Hierarchy node"),
          "TRADE_IDS": ("trade", "Trade"),
          # a trade page's Risk links (risk-links@1): set on the view by the VaR engine when the trade is live
          "PNL_VECTOR_ID": ("pnl-vector", "P&L vector"), "SCENARIO_PV_ID": ("scenario-pv", "Scenario PVs"),
          "VAR_RESULT_ID": ("var-result", "VaR of this trade")}
_HIER = ["LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO"]
#: the risk-store layout of each kind: promoted columns, and the vector columns (list<double>, out of the document)
LAYOUT = {
    "pnl-vector": {"columns": ["TRADE_ID", "SECURITY_ID", "INSTRUMENT", *_HIER, "STRATEGY", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY",
                               "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TAGS", "TRADE_STATUS", "MATURITY_DATE", "STATUS",
                               "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID", "RUN_KIND", "COMPUTED_AT"],
                   "vectors": ["PNL"]},
    "scenario-pv": {"columns": ["TRADE_ID", "SECURITY_ID", "INSTRUMENT", *_HIER, "STRATEGY", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY",
                                "COUNTERPARTY", "DIRECTION", "QUANTITY", "BASE_PV", "VALUATION_DATE", "BASE_VALUATION_DATE", "TAGS",
                                "TRADE_STATUS", "MATURITY_DATE", "STATUS", "SCENARIO_SET_ID", "BATCH_ID", "RUN_KIND", "COMPUTED_AT"],
                    "vectors": ["SCENARIO_PV"]},
    "fx-scenario": {"columns": ["CURRENCY", "RATE", "SCENARIO_SET_ID"]},
    "risk-hierarchy": {"columns": ["NODE_ID", "HIERARCHY", "LEVEL", "PARENT_ID", "NAME", "LIMIT_VAR"]},
    "portfolio": {"columns": ["PORTFOLIO_ID", "NAME", "TYPE", "OWNER", "LIMIT_VAR"]},
}
#: the search form (FORM market-risk) of each kind: its key columns
FORM_COLUMNS = {"position": ["DIRECTION", "DESK", "TT_BOOK", "PRODUCT_TYPE", "CURRENCY", "SECURITY_ID"],
                "portfolio": ["NAME", "TYPE", "OWNER", "LIMIT_VAR"],
                "pnl-vector": ["TRADE_ID", "SECURITY_ID", "DESK", "TT_BOOK"],
                "scenario-pv": ["TRADE_ID", "CURRENCY", "DESK", "TT_BOOK"],
                "fx-scenario": ["CURRENCY", "BUSINESS_DATE"],
                "scenario-set": ["BUSINESS_DATE"],
                "risk-hierarchy": ["HIERARCHY", "LEVEL", "NAME", "LIMIT_VAR"],
                "var-official": ["NODE_ID", "HIERARCHY", "LEVEL", "NODE_NAME", "RESULT_VERSION", "TRAFFIC_LIGHT"]}
BADGES = {"position": "fmt($.QUANTITY, 'compact')", "portfolio": "$.TYPE"}
#: the computed kinds' fields (VAR_GUIDE.md section 8): read as an ordinary kind, never stored
COMPUTED = ("var-result", "var-whatif")
EXAMPLES = [("PORT PF-RATES-DESK", "Portfolio · its positions, longs and shorts, VaR and component VaR (computed as you open it)"),
            ("PORT PF-ASHA-UNWIND", "A basket of positions and trades · overlaps the rates desk"),
            ("VRES DESK:RATES", "The rates desk's VaR as an ordinary kind (computed when asked, as you; the explorer is VAR DESK:RATES)")]
