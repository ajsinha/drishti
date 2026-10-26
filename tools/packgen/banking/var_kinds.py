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

"""The VaR engine's kinds in the market-risk pack (docs/contracts/VAR.md): what make_packs.py adds to the pack's manifest beside the
taxonomy's kinds. The engine's inputs (P&L vectors, scenario set, hierarchies, portfolio definitions) are written by
tools/packgen/var/make_var_data.py into data/testdata/market-risk/ and served by the pack's Delta connector risk-store, the P&L vectors
as a list<double> column (``vectors:``). Positions are Drishti's: derived from the trades by the pack's rules when a date loads and
served from the engine by the var-positions connector. No kind stores a VaR figure: the pages show the engine's, computed when opened."""
from __future__ import annotations

#: kind -> (mnemonic, label, id pattern, id field)
KINDS = {
    "position": ("POS", "Position", "^POS-", "POSITION_ID"),
    "portfolio": ("PORT", "Portfolio", "^PF-", "PORTFOLIO_ID"),
    "pnl-vector": ("PNLV", "P&L vector (509 scenarios)", "^PV-", "VECTOR_ID"),
    "scenario-set": ("SSET", "Scenario set", "^HS-", "SCENARIO_SET_ID"),
    "risk-hierarchy": ("HIER", "Risk hierarchy node", "^(MGMT|LEGAL)~", "NODE_ID"),
}
#: kinds not served by risk-store: the connector each is routed to
SERVED_BY = {"position": "var-positions"}
#: the connectors besides risk-store: the positions the server derives, from the VaR engine's memory
CONNECTORS = {"var-positions": {"plugin": "var-positions", "enabled": "${DRISHTI_VAR_ENABLED:true}", "kinds": ["position"]}}
#: link fields the kinds carry (field -> (kind, label)); the hierarchy fields (TT_BOOK, LEGAL_ENTITY, COUNTERPARTY) link through the
#: core and trading packs' own fields
FIELDS = {"POSITION_ID": ("position", "Position"), "PORTFOLIO_ID": ("portfolio", "Portfolio"), "NODE_ID": ("risk-hierarchy", "Hierarchy node"),
          "TRADE_IDS": ("trade", "Trade")}
_HIER = ["LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO"]
#: the risk-store layout of each kind: promoted columns, and the vector columns (list<double>, out of the document)
LAYOUT = {
    "pnl-vector": {"columns": ["TRADE_ID", "SECURITY_ID", "INSTRUMENT", *_HIER, "STRATEGY", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY",
                               "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TAGS", "TRADE_STATUS", "MATURITY_DATE", "STATUS",
                               "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"],
                   "vectors": ["PNL"]},
    "risk-hierarchy": {"columns": ["NODE_ID", "HIERARCHY", "LEVEL", "PARENT_ID", "NAME", "LIMIT_VAR"]},
    "portfolio": {"columns": ["PORTFOLIO_ID", "NAME", "TYPE", "OWNER", "LIMIT_VAR"]},
}
#: the search form (FORM market-risk) of each kind: its key columns
FORM_COLUMNS = {"position": ["DIRECTION", "DESK", "TT_BOOK", "PRODUCT_TYPE", "CURRENCY", "SECURITY_ID"],
                "portfolio": ["NAME", "TYPE", "OWNER", "LIMIT_VAR"],
                "pnl-vector": ["TRADE_ID", "SECURITY_ID", "DESK", "TT_BOOK"],
                "scenario-set": ["BUSINESS_DATE"],
                "risk-hierarchy": ["HIERARCHY", "LEVEL", "NAME", "LIMIT_VAR"]}
BADGES = {"position": "fmt($.QUANTITY, 'compact')", "portfolio": "$.TYPE"}
EXAMPLES = [("PORT PF-RATES-DESK", "Portfolio · its positions, longs and shorts, VaR and component VaR (computed as you open it)"),
            ("PORT PF-ASHA-UNWIND", "A basket of positions and trades · overlaps the rates desk")]
