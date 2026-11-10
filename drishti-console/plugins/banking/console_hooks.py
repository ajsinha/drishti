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


"""What the banking module registers in the console's generic code (core/plugins.py): its reserved words, its console commands, its pages
on a phone, its connector action."""
from core import plugins

plugins.word("VAR", ("/var", "/api/var/", "/admin/scopes"))
plugins.word("EXP", ("/exposure", "/api/exposure/"), mnemonic=True)
plugins.word("SMAT", ("/smat", "/api/smat/"))
plugins.word("PLAY", ("/play", "/api/play/"))

plugins.console_command("SMAT", "market-risk", "Scenario matrix", "One trade, position or VaR node: its scenario P&L by scenario day across the "
                        "last N business dates (20; at most 60), each date's VaR rank and ES tail outlined, a row charted against -VaR; P&L, PV or the change; CSV and Arrow",
                        "SMAT DESK:RATES 20", "var-result", ("VAR",))
plugins.console_command("PLAY", "market-risk", "VaR Playground", "A VaR scope or a few positions: every scenario P&L, the rank, VaR, ES, components, "
                        "incremental VaR and both 10-day methods worked in a spreadsheet-like grid with the explanation beside it (500 positions at most, the "
                        "rest in Rest of scope), exported as an .xlsx of real formulas equal to the engine to the cent", "PLAY VAR DESK RATES", "var-result", ("VAR",))
plugins.console_command("VAR EXPLAIN", "market-risk", "Why VaR moved", "A VaR scope between two business dates: the change in causes that add up exactly "
                        "(restatement, hierarchy and rules, trades out, the window, the market, trades in), who drove each step, the order-free view by part, and "
                        "the working as an .xlsx of formulas", "VAR EXPLAIN BANK", "var-result", ("VAR",))

plugins.phone_page("var", {"tiles": ("must", "Headline VaR, ES and limit use"), "method": ("must", "How VaR is computed"),
                           "path-dist": ("glance", "Distribution of scenario P&L"), "path-tail": ("more", "Worst 25 scenarios"),
                           "path-series": ("more", "P&L by scenario date"), "path-table": ("more", "The P&L path, scenario by scenario"),
                           "hist": ("more", "VaR / ES history"), "contrib": ("more", "Contributions"), "captures": ("more", "Captured results"),
                           "ccy": ("more", "In other currencies"), "tree": ("more", "Scopes"),
                           "whatif": ("desk", "What-if"), "basket": ("desk", "Construct your basket")})
plugins.phone_page("exposure", {"tiles": ("must", "CE, EPE and peak PFE"), "chart": ("glance", "Profile"), "tabs": ("more", "Netting sets, trades and limits"),
                                "tree": ("more", "Scopes"), "fan": ("desk", "Paths")})

plugins.connector_action("var-pv-stream", "Apply now", "Apply the waiting window of {name} now, as one intraday batch",
                         "The PV topic's waiting window applied now, as one intraday batch (VAR_GUIDE.md section 8.4)", "/api/var/intraday/streams/{name}/apply")
