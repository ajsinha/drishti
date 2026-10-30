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

"""The exposure engine's kinds in the counterparty-risk pack (docs/guides/EXPOSURE_GUIDE.md): what make_packs.py adds to the pack's manifest
beside the taxonomy's kinds, as var_kinds.py does for VaR. The engine's inputs (the simulation run and every trade's MtM on every path,
``simulation-run`` and ``exposure-path``) are written by tools/packgen/ccr/make_ccr_data.py into data/testdata/counterparty-risk/ and
served by the pack's Delta connector exposure-store from the risk lake's domain (``risk/``), the paths as a list<double> column
(``vectors: [MTM]``). No kind stores an exposure figure: the computed kind exposure-result (EXP <scope> <GO>) is answered by the
server's Java engine through the reactive connector exposure-engine, read as the viewer; exposure-whatif (XWIF <scope> <GO>) by the Python
exposure engine in the compute pool exposure (the exact pre-deal what-if: candidates' paths added to their netting sets, from a private
sandbox), through the reactive connector exposure-whatif; the netting-set and counterparty pages show
the engine's figures computed as they open (EXPOSURE_ENGINE). The stored exposure-profile kind was retired in 2.9.0 (EXPOSURE_GUIDE.md section 22).
The published official end-of-day results of every netting set, counterparty and group (risk-results/exposure-official-cpty, written by
the server's ExposurePublisher, EXPOSURE_GUIDE.md section 20) are the read-only kind exposure-official-cpty of the Delta connector
exposure-results, for BI and other readers only: no engine reads it (tools/test_contracts.py)."""
from __future__ import annotations

#: kind -> (mnemonic, label, id pattern, id field)
KINDS = {
    "simulation-run": ("XRUN", "Exposure simulation run", "^XR-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]+$", "RUN_ID"),
    "exposure-path": ("XPATH", "Exposure path (a trade's MtM on every path at a grid date)", "^XR-[^~]+~.+~[0-9]+$", "PATH_ROW_ID"),
    # the computed kind: its id is a scope (NSET:NS-ALDERSHOT-FRA), so no id pattern claims it
    "exposure-result": ("EXP", "Exposure profile of a scope (computed)", None, None),
    "exposure-whatif": ("XWIF", "Exposure what-if before dealing (computed, Python)", None, None),
    # the published official results: one row per netting set, counterparty or group, business date and result version; no pattern
    "exposure-official-cpty": ("XOFC", "Exposure official results by netting set and counterparty (published)", None, None),
    # the netting-set and counterparty MtM totals derived from the trade valuations (COMPUTED_ATTRIBUTES.md section 8.1, Q1 and Q2):
    # ids are the netting set's and the counterparty's own ids, so no pattern claims them (NS- and CP- name the stored kinds)
    "nset-mtm": ("NSMTM", "Netting-set MtM totals (derived from the trade valuations)", None, "NETTING_SET_ID"),
    "cpty-mtm": ("CPMTM", "Counterparty MtM totals (derived from the trade valuations)", None, "COUNTERPARTY"),
}
# the engine's inputs (2.9.7, E0: the current MtM and the membership come from trade-valuation, not the netting set's stored copies)
_INPUTS = ["simulation-run", "exposure-path", "netting-set", "csa", "collateral-balance", "trade-valuation", "credit-limit"]
#: Live (COMPUTED_ATTRIBUTES.md section 3, decided): a live status and not past the maturity date; a live-status trade past its maturity
#: date is a row with LIVE false, in no total, counted in PAST_MATURITY_COUNT (a data-quality warning)
_LIVE_STATUS = "nonblank($.NETTING_SET) && ($.TRADE_STATUS == 'NEW' || $.TRADE_STATUS == 'VALIDATED' || $.TRADE_STATUS == 'AMENDED')"
_NOT_PAST = "!nonblank($.MATURITY_DATE) || days($.VALUATION_DATE, $.MATURITY_DATE) >= 0"
_LIVE = "$.TRADES[?@.LIVE]"
_POS = "$.TRADES[?@.LIVE && @.MTM > 0]"
_NOTE = ("size($.MTM_CURRENCIES) == 1 && first($.MTM_CURRENCIES) == 'USD' ? 'USD: every member in USD, no conversion' : "
         "'not converted: members in ' + size($.MTM_CURRENCIES) + ' currencies, the converted total comes from the netting-set engine'")
DERIVED = {
    "nset-mtm": {
        "from": "trade-valuation", "where": _LIVE_STATUS, "group-by": "$.NETTING_SET", "id-field": "NETTING_SET_ID", "members": "TRADE_IDS",
        "max-members": 100000, "rows-field": "TRADES",
        "fields": {"STATUS_LIVE_COUNT": "count", "COUNTERPARTY": "first $.COUNTERPARTY", "COUNTERPARTIES": "distinct $.COUNTERPARTY",
                   "MTM_CURRENCIES": "distinct $.MTM_CURRENCY", "VALUATION_DATES": "distinct $.VALUATION_DATE"},
        "rows": {"TRADE_ID": "$.TRADE_ID", "TRADE_STATUS": "$.TRADE_STATUS", "PRODUCT_TYPE": "$.PRODUCT_TYPE", "PRODUCT_GROUP": "$.PRODUCT_GROUP",
                 "CURRENCY": "$.CURRENCY", "BOOK": "$.PORTFOLIO", "NOTIONAL": "$.NOTIONAL", "MATURITY_DATE": "$.MATURITY_DATE", "MTM": "$.MTM",
                 "MTM_CURRENCY": "$.MTM_CURRENCY", "LIVE": _NOT_PAST},
        "then": {"TRADE_COUNT": f"size({_LIVE})", "NET_MTM": f"sum({_LIVE}, 'MTM')", "GROSS_POSITIVE_MTM": f"sum({_POS}, 'MTM')",
                 "GROSS_NEGATIVE_MTM": "sum($.TRADES[?@.LIVE && @.MTM < 0], 'MTM')",
                 "CE_UNCOLLATERALISED": f"max(sum({_LIVE}, 'MTM'), 0)",
                 "NETTING_BENEFIT": f"sum({_POS}, 'MTM') - max(sum({_LIVE}, 'MTM'), 0)",
                 "NET_TO_GROSS": f"sum({_POS}, 'MTM') > 0 ? max(sum({_LIVE}, 'MTM'), 0) / sum({_POS}, 'MTM') : 0",
                 "BY_PRODUCT_GROUP": f"group({_LIVE}, 'PRODUCT_GROUP', 'MTM')",
                 "PAST_MATURITY_COUNT": "size($.TRADES[?!@.LIVE])", "PAST_MATURITY_MTM": "sum($.TRADES[?!@.LIVE], 'MTM')",
                 "PAST_MATURITY": "$.TRADES[?!@.LIVE]", "SUM_CURRENCY": "'USD'", "CURRENCY_NOTE": _NOTE},
        "sort": {"TRADES": "MTM desc", "BY_PRODUCT_GROUP": "value desc"},
    },
    "cpty-mtm": {
        # from the trade valuations too: a derived kind cannot read another derived kind of its connector (COMPUTED_ATTRIBUTES.md
        # section 8.1, check (a)); CE_UNCOLLATERALISED takes each netting set's positive net first (exposures never net across sets)
        "from": "trade-valuation", "where": _LIVE_STATUS, "group-by": "$.COUNTERPARTY", "id-field": "COUNTERPARTY", "members": "TRADE_IDS",
        "max-members": 100000, "rows-field": "TRADES",
        "fields": {"STATUS_LIVE_COUNT": "count", "MTM_CURRENCIES": "distinct $.MTM_CURRENCY", "VALUATION_DATES": "distinct $.VALUATION_DATE"},
        "rows": {"TRADE_ID": "$.TRADE_ID", "NETTING_SET": "$.NETTING_SET", "MTM": "$.MTM", "LIVE": _NOT_PAST},
        "then": {"TRADE_COUNT": f"size({_LIVE})", "NET_MTM": f"sum({_LIVE}, 'MTM')", "GROSS_POSITIVE_MTM": f"sum({_POS}, 'MTM')",
                 "GROSS_NEGATIVE_MTM": "sum($.TRADES[?@.LIVE && @.MTM < 0], 'MTM')",
                 "NETTING_SETS": f"group({_LIVE}, 'NETTING_SET', 'MTM')",
                 "NETTING_SET_COUNT": f"size(group({_LIVE}, 'NETTING_SET', 'MTM'))",
                 "CE_UNCOLLATERALISED": f"sum(group({_LIVE}, 'NETTING_SET', 'MTM')[?@.value > 0], 'value')",
                 "PAST_MATURITY_COUNT": "size($.TRADES[?!@.LIVE])", "SUM_CURRENCY": "'USD'", "CURRENCY_NOTE": _NOTE},
        "sort": {"NETTING_SETS": "value desc", "TRADES": "MTM desc"},
    },
}
_ROOT = "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"
#: the connectors the kinds are served by
SERVED_BY = {"simulation-run": "exposure-store", "exposure-path": "exposure-store", "exposure-result": "exposure-engine",
             "exposure-whatif": "exposure-whatif", "exposure-official-cpty": "exposure-results", "nset-mtm": "ccr-derived",
             "cpty-mtm": "ccr-derived"}
#: the published columns of risk-results/exposure-official-cpty that readers scan (ExposurePublisher.CPTY_COLUMNS less the JSON texts)
OFFICIAL_COLUMNS = ["HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL", "COUNTERPARTY", "LEGAL_ENTITY", "RESULT_VERSION", "RUN_ID",
                    "PATHS", "CE", "EPE", "EEPE", "PEAK_EE", "PEAK_PFE_95", "PEAK_PFE_99", "EAD_IMM", "PEAK_PFE_DATE", "PFE_95_1Y",
                    "PFE_95_5Y", "EE_1Y", "EE_5Y", "NETTING_SETS", "TRADES", "FAILED_TRADES", "PARTIAL_SETS"]
CONNECTORS = {
    # the quick wins Q1 and Q2 (COMPUTED_ATTRIBUTES.md sections 8.1 and 9): built once per business date and data version, shared by every
    # reader (decision 6: visible to everyone), shown beside the stored netting-set and counterparty figures until the NSET engine
    "ccr-derived": {
        "plugin": "derived", "enabled": "${DRISHTI_CCR_DERIVED_ENABLED:true}",
        "description": "Netting-set and counterparty MtM totals derived from the trade valuations of live trades (a live status, not past "
                       "maturity), once per business date: shown beside the stored figures until the netting-set engine computes them",
        "kinds": ["nset-mtm", "cpty-mtm"],
        "settings": {"refresh": "60s", **DERIVED},
    },
    # read only: the table the server's ExposurePublisher appends the official captures to (EXPOSURE_GUIDE.md section 20); BI reads it, no engine does
    "exposure-results": {
        "plugin": "delta", "enabled": "${DRISHTI_EXPOSURE_ENABLED:true}",
        "description": "The official end-of-day exposure of every netting set, counterparty and group the server publishes "
                       "(risk-results/exposure-official-cpty), read only: for BI and other readers, never an engine's input",
        "kinds": ["exposure-official-cpty"],
        "settings": {"root": _ROOT, "domain": "risk-results", "layout": {"exposure-official-cpty": {"columns": OFFICIAL_COLUMNS}}},
    },
    "exposure-store": {
        "plugin": "delta", "enabled": "${DRISHTI_LAKE_ENABLED:true}", "kinds": ["simulation-run", "exposure-path"],
        "settings": {"root": _ROOT, "domain": "risk", "layout": {
            "simulation-run": {"columns": ["RUN_ID", "CALIBRATION", "PATHS", "STATUS", "BATCH_ID"]},
            "exposure-path": {"columns": ["TRADE_ID", "NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK", "LIMIT_LETTER", "ASSET_CLASS", "PRODUCT",
                                          "SOURCE_SYSTEM", "MATURITY_DATE", "NOTIONAL", "GRID_INDEX", "GRID_DATE", "RUN_ID", "BATCH_ID", "STATUS"],
                              "vectors": ["MTM"]}}},
    },
    "exposure-engine": {
        "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_EXPOSURE_ENABLED:true}",
        "description": "Exposure profiles (EE, ENE, EEE, PFE 95 and 99, EPE, EEPE) of any counterparty scope, computed when asked by the "
                       "server's exposure engine from the exposure paths, as the viewer: EXP <scope> <GO>",
        "kinds": {"exposure-result": {"compute": "exposure-result",
                                      "params": {"q": {"type": "decimal", "default": 0.95, "min": 0.5, "max": 0.9999},
                                                 "collateral": {"type": "enum", "options": ["lagged", "current", "none"], "default": "lagged"},
                                                 "run": {"type": "text", "default": ""},
                                                 "detail": {"type": "enum", "options": ["none", "whatif", "set"], "default": "none"},
                                                 "sets": {"type": "text", "default": ""}},
                                      "result": {"required": ["SCOPE", "STATUS", "PROFILE"],
                                                 "types": {"EPE": "number", "PEAK_PFE": "number"}}}},
        "engine": {"type": "java", "class": "com.ash.drishti.server.exposure.ExposureResultEngine", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": _INPUTS, "affects": "all"}],
        "coalesce": {"quiet": "1s", "max-wait": "10s"},
        "cache": {"results": 1000, "ttl": "1h"},
        "limits": {"timeout": "60s", "wait": "5s", "max-inflight-per-user": 4},
    },
    "exposure-whatif": {
        "plugin": "reactive", "mode": "reactive", "enabled": "${DRISHTI_EXPOSURE_ENABLED:true}",
        "description": "A scope's exposure before and after candidate trades (a private sandbox), exact: each candidate's paths on the run "
                       "added to its netting set, collateral and exposure computed again by the Python exposure engine in the compute pool "
                       "exposure from the scope's exposure-result: XWIF <scope> <GO>",
        "kinds": {"exposure-whatif": {"compute": "exposure-whatif",
                                      "params": {"sandbox": {"type": "sandbox-ref"},
                                                 "q": {"type": "decimal", "default": 0.95, "min": 0.5, "max": 0.9999},
                                                 "collateral": {"type": "enum", "options": ["lagged", "current", "none"], "default": "lagged"},
                                                 "sets": {"type": "text", "default": ""}},
                                      "result": {"required": ["BEFORE_PEAK_PFE", "AFTER_PEAK_PFE"],
                                                 "types": {"BEFORE_PEAK_PFE": "number", "AFTER_PEAK_PFE": "number"}}}},
        "reads": [{"kind": "exposure-result", "as": "base",
                   "id": "{id}~collateral={params.collateral}~detail=whatif~q={params.q}~sets={params.sets}"},
                  {"kind": "exposure-result", "as": "sets", "ids": "{inputs.base.WHATIF_SETS}", "limit": 50}],
        "engine": {"type": "python", "entry": "drishti_compute.exposure.whatif:ExposureWhatIfEngine", "pool": "exposure", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": _INPUTS, "affects": "all"}],
        "coalesce": {"quiet": "1s", "max-wait": "10s"},
        "cache": {"results": 200, "ttl": "1h"},
        "limits": {"timeout": "60s", "wait": "250ms", "max-inflight-per-user": 2},
    },
}
#: link fields the kinds carry (field -> (kind, label)); NETTING_SET, COUNTERPARTY and LEGAL_ENTITY link through the packs' own fields
FIELDS = {"RUN_ID": ("simulation-run", "Simulation run"),
          # a trade page's Risk links (risk-links@1): set on the view by the exposure engine when the run has the trade
          "EXPOSURE_RESULT_ID": ("exposure-result", "Exposure of this trade")}
#: the search form of each stored kind: its key columns
FORM_COLUMNS = {"simulation-run": ["CALIBRATION", "PATHS", "STATUS"],
                "exposure-path": ["TRADE_ID", "NETTING_SET", "COUNTERPARTY", "GRID_DATE", "RUN_ID"],
                "exposure-official-cpty": ["NODE_ID", "HIERARCHY", "COUNTERPARTY", "NODE_NAME", "RESULT_VERSION"],
                "nset-mtm": ["COUNTERPARTY", "NET_MTM", "TRADE_COUNT", "GROSS_POSITIVE_MTM", "PAST_MATURITY_COUNT"],
                "cpty-mtm": ["NET_MTM", "TRADE_COUNT", "NETTING_SET_COUNT", "CE_UNCOLLATERALISED"]}
COMPUTED = ("exposure-result", "exposure-whatif")
EXAMPLES = [("EXP NSET:NS-ALDERSHOT-FRA", "Exposure profile of a netting set · EE, PFE 95 and 99, EPE, EEPE computed from the paths"),
            ("EXP CPTY:CP-ALDERSHOT", "A counterparty's exposure · its four netting sets summed path by path (PFE is not a sum of PFEs)")]
#: the kinds whose data this generator does not write (tools/packgen/ccr/make_ccr_data.py does): no samples from make_data.py
INPUT_KINDS = ("simulation-run", "exposure-path")
