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

"""The exposure engine's kinds in the counterparty-risk pack (docs/contracts/EXP.md): what make_packs.py adds to the pack's manifest
beside the taxonomy's kinds, as var_kinds.py does for VaR. The engine's inputs (the simulation run and every trade's MtM on every path,
``simulation-run`` and ``exposure-path``) are written by tools/packgen/ccr/make_ccr_data.py into data/testdata/counterparty-risk/ and
served by the pack's Delta connector exposure-store from the risk lake's domain (``risk/``), the paths as a list<double> column
(``vectors: [MTM]``). No kind stores an exposure figure: the computed kind exposure-result (EXP <scope> <GO>) is answered by the
server's Java engine through the reactive connector exposure-engine, read as the viewer; the netting-set and counterparty pages show
the engine's figures computed as they open (EXPOSURE_ENGINE). The stored exposure-profile kind was retired in 2.9.0 (EXP.md section 14)."""
from __future__ import annotations

#: kind -> (mnemonic, label, id pattern, id field)
KINDS = {
    "simulation-run": ("XRUN", "Exposure simulation run", "^XR-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]+$", "RUN_ID"),
    "exposure-path": ("XPATH", "Exposure path (a trade's MtM on every path at a grid date)", "^XR-[^~]+~.+~[0-9]+$", "PATH_ROW_ID"),
    # the computed kind: its id is a scope (NSET:NS-ALDERSHOT-FRA), so no id pattern claims it
    "exposure-result": ("EXP", "Exposure profile of a scope (computed)", None, None),
}
_INPUTS = ["simulation-run", "exposure-path", "netting-set", "csa", "collateral-balance"]
_ROOT = "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"
#: the connectors the kinds are served by
SERVED_BY = {"simulation-run": "exposure-store", "exposure-path": "exposure-store", "exposure-result": "exposure-engine"}
CONNECTORS = {
    "exposure-store": {
        "plugin": "delta", "enabled": "${DRISHTI_LAKE_ENABLED:true}", "kinds": ["simulation-run", "exposure-path"],
        "settings": {"root": _ROOT, "domain": "risk", "layout": {
            "simulation-run": {"columns": ["RUN_ID", "CALIBRATION", "PATHS", "STATUS", "BATCH_ID"]},
            "exposure-path": {"columns": ["TRADE_ID", "NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK", "ASSET_CLASS", "PRODUCT",
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
                                                 "run": {"type": "text", "default": ""}},
                                      "result": {"required": ["SCOPE", "STATUS", "PROFILE"],
                                                 "types": {"EPE": "number", "PEAK_PFE": "number"}}}},
        "engine": {"type": "java", "class": "com.ash.drishti.server.exposure.ExposureResultEngine", "version": "1.0"},
        "triggers": [{"on": "data-version", "kinds": _INPUTS, "affects": "all"}],
        "coalesce": {"quiet": "1s", "max-wait": "10s"},
        "cache": {"results": 1000, "ttl": "1h"},
        "limits": {"timeout": "60s", "wait": "5s", "max-inflight-per-user": 4},
    },
}
#: link fields the kinds carry (field -> (kind, label)); NETTING_SET, COUNTERPARTY and LEGAL_ENTITY link through the packs' own fields
FIELDS = {"RUN_ID": ("simulation-run", "Simulation run")}
#: the search form of each stored kind: its key columns
FORM_COLUMNS = {"simulation-run": ["CALIBRATION", "PATHS", "STATUS"],
                "exposure-path": ["TRADE_ID", "NETTING_SET", "COUNTERPARTY", "GRID_DATE", "RUN_ID"]}
COMPUTED = ("exposure-result",)
EXAMPLES = [("EXP NSET:NS-ALDERSHOT-FRA", "Exposure profile of a netting set · EE, PFE 95 and 99, EPE, EEPE computed from the paths"),
            ("EXP CPTY:CP-ALDERSHOT", "A counterparty's exposure · its four netting sets summed path by path (PFE is not a sum of PFEs)")]
#: the kinds whose data this generator does not write (tools/packgen/ccr/make_ccr_data.py does): no samples from make_data.py
INPUT_KINDS = ("simulation-run", "exposure-path")
