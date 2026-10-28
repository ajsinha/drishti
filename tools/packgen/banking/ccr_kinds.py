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
}
_INPUTS = ["simulation-run", "exposure-path", "netting-set", "csa", "collateral-balance"]
_ROOT = "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"
#: the connectors the kinds are served by
SERVED_BY = {"simulation-run": "exposure-store", "exposure-path": "exposure-store", "exposure-result": "exposure-engine",
             "exposure-whatif": "exposure-whatif", "exposure-official-cpty": "exposure-results"}
#: the published columns of risk-results/exposure-official-cpty that readers scan (ExposurePublisher.CPTY_COLUMNS less the JSON texts)
OFFICIAL_COLUMNS = ["HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL", "COUNTERPARTY", "LEGAL_ENTITY", "RESULT_VERSION", "RUN_ID",
                    "PATHS", "CE", "EPE", "EEPE", "PEAK_EE", "PEAK_PFE_95", "PEAK_PFE_99", "EAD_IMM", "PEAK_PFE_DATE", "PFE_95_1Y",
                    "PFE_95_5Y", "EE_1Y", "EE_5Y", "NETTING_SETS", "TRADES", "FAILED_TRADES", "PARTIAL_SETS"]
CONNECTORS = {
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
                "exposure-official-cpty": ["NODE_ID", "HIERARCHY", "COUNTERPARTY", "NODE_NAME", "RESULT_VERSION"]}
COMPUTED = ("exposure-result", "exposure-whatif")
EXAMPLES = [("EXP NSET:NS-ALDERSHOT-FRA", "Exposure profile of a netting set · EE, PFE 95 and 99, EPE, EEPE computed from the paths"),
            ("EXP CPTY:CP-ALDERSHOT", "A counterparty's exposure · its four netting sets summed path by path (PFE is not a sum of PFEs)")]
#: the kinds whose data this generator does not write (tools/packgen/ccr/make_ccr_data.py does): no samples from make_data.py
INPUT_KINDS = ("simulation-run", "exposure-path")
