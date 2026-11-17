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

"""The stand-in server of Drishti Trading's landings (test_edition.py, test_edition_trading_browser.py). Who "my" is, the Trader desk's and
Desk head's figures, the person's trading role and the new-trade check are the trading module's, worked out on the server
(/api/v1/trading/landing, THIN_CONSOLE_DESIGN.md step S8a): this stand-in replays the module's own recorded answers
(answers/landings/trading.json, written by TradingLandingRecordTest over the module's stand-in server: the desk DESK-RATES of T. Brennan,
A. Shah's books BOOK-RATES-1 and -2, K. Moreau's BOOK-RATES-3, twelve trades, their VaR, limits and counterparty exposure; see
landing_answers.py), per person, lens and View as choice, and holds no figure of its own. The person's recent commands are answered here.
The business date is 2026-09-30 (FakeBackend's)."""
from __future__ import annotations

from conftest import FakeBackend
from landing_answers import Recorded

LANDINGS = Recorded("trading", "/trading/landing")


class TradingBackend(FakeBackend):
    """The server's answers to Drishti Trading's landings: the module's recorded answers, read as the person (``asked``: the user and roles
    of every landing call, so a test sees whose call it was)."""

    def __init__(self):
        super().__init__()
        self.asked: list[tuple] = []

    async def engine(self, method, path, ident, /, body=None, raw=False, accept="", timeout=None, **params):
        if LANDINGS.answers(path):
            self.asked.append((getattr(ident, "user", None), tuple(getattr(ident, "roles", ()) or ()), method, path))
            return await LANDINGS.call(self, method, path, ident, body, params)
        return await super().engine(method, path, ident, body=body, raw=raw, accept=accept, timeout=timeout, **params)

    async def command_history(self, ident=None):
        return ["TRD MX-20000001", "ECON CAN", "VAR BANK", "BOOK BOOK-RATES-1", "TRD MX-20000001", "SCN SCN-2008", "CTRD CP-ALDERSHOT"]
