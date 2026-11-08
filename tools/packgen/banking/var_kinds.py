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
The computed kind var-result (VAR_GUIDE.md section 8) is served by the reactive connector var-engine, the server's Java engine, read as the
viewer; the pre-deal what-if is the same engine's (POST /api/v1/risk/var/what-if), not a kind. The published official end-of-day results
(risk-results/var-official, written by the server's VarPublisher) are the read-only kind var-official of the Delta connector var-results,
for BI and other readers only: no engine reads it (tools/test_contracts.py).

Since 2.9.4 a bank may send a trade's scenario P&L as **scenario PVs** instead of a P&L vector: the trade's PV under each scenario and its
base PV, in the trade's own currency (``scenario-pv``, the vector column SCENARIO_PV), with each currency's scenario FX when the batch
supplies it (``fx-scenario``; else the server derives it from the FX history of the market-data pack's fx-spot). The server converts them to
USD P&L when the date loads, and every VaR answer takes a reporting currency (the parameter ccy of var-result).

The hierarchies are configured (the manifest's ``hierarchies:`` block, kept/market-risk.yaml), any number of them, every one built by the
server from the trades' promoted columns (MGMT: ``DIVISION > DESK > TT_BOOK``, LEGAL: ``LEGAL_ENTITY > DESK > TT_BOOK``, LL:
``LIMIT_LETTER > DESK``; each promoted in both trades' layouts), with their limits from the dated kind ``var-limit`` (by hierarchy and node
id). A P&L vector's id is ``PNL-<trade or position>-<set>`` (``PV-`` before, still read)."""
from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_sys.path.insert(0, str(_Path(__file__).resolve().parents[2]))
from packlake import OFFICIAL_ROOT as _OFFICIAL_ROOT  # noqa: E402  (the publisher's root: tools/lake/lakeconfig.py)

#: kind -> (mnemonic, label, id pattern, id field)
KINDS = {
    "position": ("POS", "Position", "^POS-", "POSITION_ID"),
    "portfolio": ("PORT", "Portfolio", "^PF-", "PORTFOLIO_ID"),
    "pnl-vector": ("PNLV", "P&L vector (509 scenarios)", "^PV-", "VECTOR_ID"),     # the ids before 2.9.5 (PNL- ids: ID_PATTERNS_FIRST)
    "scenario-pv": ("SPV", "Scenario PVs of a trade (509 scenarios, its currency)", "^SPV-", "SCENARIO_PV_ID"),
    "fx-scenario": ("FXS", "Scenario FX of a currency (509 scenarios)", "^FXS-", "FX_SCENARIO_ID"),
    "scenario-set": ("SSET", "Scenario set", "^HS-", "SCENARIO_SET_ID"),
    # 2.9.5: a node's VaR limit by hierarchy and node id (USD), for the hierarchies whose limits name it (LL in the demo)
    "var-limit": ("VLIM", "VaR limit of a hierarchy node", "^VL-", "LIMIT_ID"),
    # a desk's daily actual and hypothetical P&L from the bank's finance and front-office systems, for regulatory backtesting
    # (VAR_GUIDE.md section 15.16); never an input of the VaR engine itself
    "desk-pnl": ("DPNL", "Desk P&L: actual and hypothetical (daily)", "^DPNL-", "DESK_PNL_ID"),
    # the long-history P&L of today's portfolio per hierarchy node, for the stress window selection (VAR_GUIDE.md section 18.9)
    "pnl-history": ("PNLH", "Long-history P&L of a hierarchy node (stress window selection)", "^PNLH-", "HISTORY_ID"),
    # 2.9.11: the positions' sensitivities to the standard risk factors and the factor dictionary (docs/guides/FRTB_SA_GUIDE.md sections 2
    # and 3), read by the server's FRTB-SA engine; a factor's id is its standard name (IR/CURVE/USD/SOFR/10Y)
    "position-sensitivity": ("SENS", "Position sensitivities (delta, vega, curvature by risk factor)", "^SENS-", "SENSITIVITY_ID"),
    "risk-factor": ("RFAC", "Risk factor (the factor dictionary: FRTB and SIMM mapping, source names)", "^(IR|INF|CR|FX|EQ|CM|VOL)/",
                    "RISK_FACTOR"),
    # the computed kinds: the id is a scope (DESK:RATES), so no id pattern claims them
    "var-result": ("VRES", "VaR of a scope (computed)", None, None),
    # the published official results: one row per node, business date and result version (id <NODE_ID>@<date>#v<n>); no pattern
    "var-official": ("VOFF", "VaR official results (published)", None, None),
    # 2.9.7: the bank's stress P&L per scenario, the desks' results added (DATA_ARCHITECTURE.md section 12, Q4); its id is the
    # scenario's own (SCN-...), so no pattern claims it
    "stress-scenario-pnl": ("SSPNL", "Stress P&L of a scenario across desks (derived from the stress results)", None, "SCENARIO_ID"),
}
#: id patterns that must come before the taxonomy's (2.9.5): a P&L vector's PNL-<trade>-<set> id before pnl-explain's ^PNL-
ID_PATTERNS_FIRST = [{"pattern": "^PNL-.+-HS-[0-9]+$", "kind": "pnl-vector"}]
#: kinds not served by risk-store: the connector each is routed to
SERVED_BY = {"position": "var-positions", "var-result": "var-engine", "var-official": "var-results",
             "stress-scenario-pnl": "mr-derived"}
_INPUTS = ["pnl-vector", "scenario-pv", "fx-scenario", "scenario-set", "portfolio", "var-limit"]
#: the reporting currency every VaR answer takes (2.9.4): USD unless asked, any currency with scenario FX on the date
_CCY = {"ccy": {"type": "text", "default": "USD"}}
#: the lens of a VaR answer (2.9.7, VAR_GUIDE.md section 18.6): the horizon, the 10-day method (default: drishti.var.ten-day-method) and
#: the confidences (none: the configured ones; one of drishti.var.confidences and es-confidences)
_LENS = {"horizon": {"type": "enum", "options": ["1d", "10d"], "default": "1d"},
         "method": {"type": "enum", "options": ["default", "sqrt10", "sum"], "default": "default"},
         "confidence": {"type": "decimal", "min": 0.5, "max": 0.9999},
         "es-confidence": {"type": "decimal", "min": 0.5, "max": 0.9999}}
#: the connectors besides risk-store: the positions the server derives, from the VaR engine's memory; the computed kinds
CONNECTORS = {
    # the bank's stress P&L per scenario (DATA_ARCHITECTURE.md section 12, Q4): the desks' stored results added (additive), each desk's
    # limit use and breach against its positive limit (F5); built once per business date and data version, shared by every reader
    "mr-derived": {
        "plugin": "derived", "enabled": "${DRISHTI_MR_DERIVED_ENABLED:true}",
        "description": "The bank's stress P&L per scenario: the desks' stress results added, with each desk's limit use and breaches, once "
                       "per business date (until the stress engine computes it)",
        "kinds": ["stress-scenario-pnl"],
        "settings": {"refresh": "60s", "stress-scenario-pnl": {
            "from": "stress-result", "group-by": "$.SCENARIO", "id-field": "SCENARIO_ID", "members": "RESULT_IDS", "max-members": 1000,
            "rows-field": "DESKS",
            "fields": {"DESK_COUNT": "count", "PNL": "sum $.PNL", "WORST_DESK_PNL": "min $.PNL", "SCENARIO_NAME": "first $.SCENARIO_NAME",
                       "LIMIT": "sum $.LIMIT"},
            "rows": {"RESULT_ID": "$.RESULT_ID", "DESK": "$.DESK", "PNL": "$.PNL", "LIMIT": "$.LIMIT",
                     "LIMIT_USE": "$.LIMIT > 0 ? max(-$.PNL, 0) / $.LIMIT : null",
                     "STATUS": "$.LIMIT > 0 ? (max(-$.PNL, 0) / $.LIMIT > 1 ? 'BREACH' : 'OK') : null"},
            "then": {"BREACHES": "size($.DESKS[?@.LIMIT_USE > 1])", "WORST_LIMIT_USE": "max($.DESKS[*].LIMIT_USE)",
                     "BY_DESK": "group($.DESKS, 'DESK', 'PNL')"},
            "sort": {"DESKS": "PNL asc", "BY_DESK": "value asc"}}},
    },
    # read only: the table the server's VarPublisher appends the official captures to (VAR_GUIDE.md section 19); BI reads it, no engine does
    "var-results": {
        "plugin": "delta", "enabled": "${DRISHTI_VAR_ENABLED:true}",
        "description": "The official end-of-day VaR results the server publishes (risk-results/var-official), read only: for BI and "
                       "other readers, never an engine's input",
        "kinds": ["var-official"],
        "settings": {"root": _OFFICIAL_ROOT, "domain": "risk-results",
                     "layout": {"var-official": {"columns": ["HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL", "CAPTURE_ID",
                                                             "RESULT_VERSION", "VAR", "ES", "LIMIT", "LIMIT_USE", "DIVERSIFICATION", "HYPO_PNL",
                                                             "EXCEPTIONS_250", "TRAFFIC_LIGHT", "CONFIDENCE", "ES_CONFIDENCE"]}}},
    },
    "var-positions": {"plugin": "var-positions", "enabled": "${DRISHTI_VAR_ENABLED:true}", "kinds": ["position"]},
    # 2.9.4: the PV topic of the pricing systems, windowed into intraday batches of the lake (VAR_GUIDE.md section 8.4); off by default,
    # as every Kafka connector of the packs
    "pv-updates": {"plugin": "var-pv-stream", "enabled": "${DRISHTI_VAR_PV_STREAM:false}", "kinds": ["pv-update"],
                   "settings": {"bootstrap-servers": "${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}", "topic": "${DRISHTI_VAR_PV_TOPIC:drishti.risk.pv-updates}",
                                "lake": "demo", "pack": "market-risk"}},
    "var-engine": {
        "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_VAR_ENABLED:true}",
        "description": "The VaR of any scope, computed when asked by the server's VaR engine (the additive aggregation over the hot store), "
                       "as the viewer: VRES <scope> <GO>",
        "kinds": {"var-result": {"compute": "var-result", "params": {**_CCY, **_LENS},
                                 "result": {"required": ["VAR", "ES", "PNL"], "types": {"VAR": "number", "ES": "number"}}}},
        "engine": {"type": "java", "class": "com.ash.drishti.banking.var.VarResultEngine", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": _INPUTS, "affects": "all"}],
        "coalesce": {"quiet": "250ms", "max-wait": "2s"},
        "cache": {"results": 2000, "ttl": "1h"},
        "limits": {"timeout": "60s", "wait": "5s", "max-inflight-per-user": 4},
    },
}
#: link fields the kinds carry (field -> (kind, label)); the hierarchy fields (TT_BOOK, LEGAL_ENTITY, COUNTERPARTY) link through the
#: core and trading packs' own fields
FIELDS = {"POSITION_ID": ("position", "Position"), "PORTFOLIO_ID": ("portfolio", "Portfolio"),
          "TRADE_IDS": ("trade", "Trade"), "RISK_FACTOR": ("risk-factor", "Risk factor"),
          # a trade page's Risk links (risk-links@1): set on the view by the VaR engine when the trade is live
          "PNL_VECTOR_ID": ("pnl-vector", "P&L vector"), "SCENARIO_PV_ID": ("scenario-pv", "Scenario PVs"),
          "VAR_RESULT_ID": ("var-result", "VaR of this trade")}
#: the hierarchy columns of the trades' rows, promoted in both trades' layouts: the level columns of the pack's hierarchies (MGMT, LEGAL, LL)
_HIER = ["LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "LIMIT_LETTER", "PORTFOLIO"]
#: the risk-store layout of each kind: promoted columns, and the vector columns (list<double>, out of the document)
LAYOUT = {
    # 2.9.4: every record says what it is first (VALUE_TYPE, GRAIN, its id, CURRENCY, SOURCE_SYSTEM: VAR_GUIDE.md section 15.13)
    "pnl-vector": {"columns": ["VALUE_TYPE", "GRAIN", "TRADE_ID", "POSITION_ID", "CURRENCY", "SOURCE_SYSTEM", "SECURITY_ID", "INSTRUMENT",
                               *_HIER, "STRATEGY", "PRODUCT_GROUP", "PRODUCT_TYPE", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TAGS",
                               "TRADE_STATUS", "MATURITY_DATE", "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID", "RUN_KIND",
                               "COMPUTED_AT", "TRADE_IDS", "TRADE_CURRENCY"],
                   "vectors": ["PNL"]},
    "scenario-pv": {"columns": ["VALUE_TYPE", "GRAIN", "TRADE_ID", "POSITION_ID", "CURRENCY", "SOURCE_SYSTEM", "SECURITY_ID", "INSTRUMENT",
                                *_HIER, "STRATEGY", "PRODUCT_GROUP", "PRODUCT_TYPE", "COUNTERPARTY", "DIRECTION", "QUANTITY", "BASE_PV",
                                "VALUATION_DATE", "BASE_VALUATION_DATE", "TAGS", "TRADE_STATUS", "MATURITY_DATE", "STATUS", "SCENARIO_SET_ID",
                                "BATCH_ID", "RUN_KIND", "COMPUTED_AT", "TRADE_IDS"],
                    "vectors": ["SCENARIO_PV"]},
    "fx-scenario": {"columns": ["CURRENCY", "RATE", "SCENARIO_SET_ID"]},
    "var-limit": {"columns": ["LIMIT_ID", "HIERARCHY", "NODE_ID", "LIMIT_VAR", "CURRENCY"]},
    "desk-pnl": {"columns": ["DESK_PNL_ID", "DESK_ID", "LEGAL_ENTITY", "ACTUAL_PNL", "HYPOTHETICAL_PNL", "FEES_AND_COMMISSIONS", "CURRENCY",
                             "SOURCE", "AS_OF"]},
    # one row per node and date: the day-by-day P&L a vector column, its dates in the document
    "pnl-history": {"columns": ["HIERARCHY", "NODE_ID", "LEVEL", "CURRENCY", "FIRST_DAY", "LAST_DAY", "DAY_COUNT"], "vectors": ["PNL"]},
    "portfolio": {"columns": ["PORTFOLIO_ID", "NAME", "TYPE", "OWNER"]},
    # 2.9.11: the sparse SENSITIVITIES list stays in the document; the columns are the contract's storage columns
    "position-sensitivity": {"columns": ["SENSITIVITY_ID", "GRAIN", "POSITION_ID", "TRADE_ID", "SOURCE_SYSTEM", "BATCH_ID", "STATUS", "DESK", "TT_BOOK",
                                         "PRODUCT_TYPE", "CURRENCY"]},
    "risk-factor": {"columns": ["RISK_FACTOR", "FACTOR_TYPE", "RISK_CLASS", "QUALIFIER", "TENOR", "FRTB_BUCKET", "SIMM_RISK_CLASS", "SIMM_BUCKET"]},
}
#: the search form (FORM market-risk) of each kind: its key columns
FORM_COLUMNS = {"position": ["DIRECTION", "DESK", "TT_BOOK", "PRODUCT_TYPE", "CURRENCY", "SECURITY_ID"],
                "portfolio": ["NAME", "TYPE", "OWNER"],
                "pnl-vector": ["TRADE_ID", "SECURITY_ID", "DESK", "TT_BOOK"],
                "scenario-pv": ["TRADE_ID", "CURRENCY", "DESK", "TT_BOOK"],
                "fx-scenario": ["CURRENCY", "BUSINESS_DATE"],
                "scenario-set": ["BUSINESS_DATE"],
                "var-limit": ["HIERARCHY", "NODE_ID", "LIMIT_VAR"],
                "desk-pnl": ["DESK_ID", "BUSINESS_DATE", "ACTUAL_PNL", "HYPOTHETICAL_PNL"],
                "pnl-history": ["HIERARCHY", "NODE_ID", "FIRST_DAY", "LAST_DAY"],
                "var-official": ["NODE_ID", "HIERARCHY", "LEVEL", "NODE_NAME", "RESULT_VERSION", "TRAFFIC_LIGHT"],
                "stress-scenario-pnl": ["SCENARIO_NAME", "PNL", "DESK_COUNT", "BREACHES"],
                "position-sensitivity": ["POSITION_ID", "DESK", "TT_BOOK", "PRODUCT_TYPE", "SOURCE_SYSTEM"],
                "risk-factor": ["RISK_CLASS", "FACTOR_TYPE", "QUALIFIER", "FRTB_BUCKET"]}
BADGES = {"position": "fmt($.QUANTITY, 'compact')", "portfolio": "$.TYPE"}
#: the computed kinds' fields (VAR_GUIDE.md section 8): read as an ordinary kind, never stored
COMPUTED = ("var-result",)
EXAMPLES = [("PORT PF-RATES-DESK", "Portfolio · its positions, longs and shorts, VaR and component VaR (computed as you open it)"),
            ("PORT PF-ASHA-UNWIND", "A basket of positions and trades · overlaps the rates desk"),
            ("VRES DESK:RATES", "The rates desk's VaR as an ordinary kind (computed when asked, as you; the explorer is VAR DESK:RATES)"),
            ("VAR BANK 10D SUM", "The bank's 10-day VaR from 10 consecutive days (VAR BANK 10D SQRT: the 1-day × √10; VAR BANK 95: at 95%)")]
