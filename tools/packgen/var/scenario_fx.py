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

"""The demo batch's scenario PVs and scenario FX (docs/guides/VAR_GUIDE.md sections 15.9 and 15.10; release 2.9.4).

A bank may send a trade's scenario P&L as **scenario PVs** in the trade's own currency (the ``scenario-pv`` kind) instead of a P&L vector,
with each currency's **scenario FX** when it has it (``fx-scenario``). The demo's Endur trades come that way (``PV_SYSTEMS``); every other
trade keeps its P&L vector, so the demo holds both forms on one date, as a bank may send them.

- **Scenario FX.** For each currency of ``CURRENCIES``: today's rate is the market-data pack's ``fx-spot`` mid on the business date, as USD
  per one unit (``FX-EURUSD`` 1.174; ``FX-USDJPY`` 148.2 is 1 / 148.2), and the rate under scenario j moves with the factor model's FX
  factor of the currency on that day: ``RATE × (1 + VOL × move[FX.<ccy>][j])`` (factors.Market: correlated, fat-tailed, clustered), so an
  FX trade and its currency's rate move together. Rounded to 10 decimals.
- **Scenario PVs.** A trade's factor-model P&L in USD, ``v(j)`` (positions.Batch), is the move of its value in its own currency at today's
  rate: ``PV(j) = (PV_USD + v(j)) / RATE`` and ``PV(base) = PV_USD / RATE``, rounded to cents. The engine then computes
  ``P&L(j) = PV(j) × FX(j) − PV(base) × RATE`` in USD: for a **USD trade** that is ``v(j)`` exactly (its current vector); for another
  currency it adds the currency's move on the trade's value (FX translation), as a bank's own revaluation would.

``engine_pnl`` computes, in Python, exactly what the server computes from the files, so the generator's checks and the figures in the
guides are the engine's."""
from __future__ import annotations

import factors as F

#: the booking systems whose batch sends scenario PVs in the demo (every other one sends P&L vectors)
PV_SYSTEMS = ("ENDUR",)
#: the currencies whose scenario FX the demo batch supplies, with a daily volatility of the rate (relative)
VOLS = {"EUR": 0.0055, "GBP": 0.0060, "JPY": 0.0065, "AUD": 0.0075, "CAD": 0.0045, "CHF": 0.0060}
CURRENCIES = tuple(sorted(VOLS))


def today_rates(spots: dict[str, dict]) -> dict[str, float]:
    """USD per one unit of each currency on the business date, from the fx-spot documents (pairs against USD only)."""
    out = {}
    for d in spots.values():
        conv = d.get("CONVENTIONS") or {}
        base, quote, mid = conv.get("BASE"), conv.get("QUOTE"), d.get("MID")
        if not mid:
            continue
        if quote == "USD" and base and base != "USD":
            out[base] = float(mid)
        elif base == "USD" and quote and quote != "USD":
            out[quote] = 1.0 / float(mid)
    return out


class Fx:
    """The demo's scenario FX: ``rate[c]`` today and ``rates[c][j]`` under each day of the market (scenario days, then the business date)."""

    def __init__(self, market: F.Market, today: dict[str, float]):
        missing = [c for c in CURRENCIES if c not in today]
        if missing:
            raise SystemExit(f"no fx-spot rate on the business date for {missing}: the scenario FX starts from it")
        self.n = market.n
        self.rate = {c: round(today[c], 10) for c in CURRENCIES}
        self.rates = {c: [round(self.rate[c] * (1.0 + VOLS[c] * market.moves[f"FX.{c}"][j]), 10) for j in range(market.n + 1)]
                      for c in CURRENCIES}

    def has(self, ccy: str) -> bool:
        return ccy == "USD" or ccy in self.rate

    def today(self, ccy: str) -> float:
        return 1.0 if ccy == "USD" else self.rate[ccy]

    def at(self, ccy: str, j: int) -> float:
        return 1.0 if ccy == "USD" else self.rates[ccy][j]

    def rows(self, cob: str, scenario_set: str, batch: str) -> list[dict]:
        """The fx-scenario documents of the date: one per currency, its rate today and under each scenario (USD per unit)."""
        return [{"FX_SCENARIO_ID": f"FXS-{c}-{scenario_set}", "BUSINESS_DATE": cob, "SCENARIO_SET_ID": scenario_set, "CURRENCY": c,
                 "QUOTE": f"USD per 1 {c}", "RATE": self.rate[c], "RATES": self.rates[c][:self.n], "SOURCE": "demo-factor-model",
                 "BATCH_ID": batch} for c in CURRENCIES]


def scenario_pvs(fx: Fx, ccy: str, pv_usd: float, usd_pnl: list[float]) -> tuple[float, list[float]]:
    """The trade's base PV and its PV under each day, in its own currency: its USD value moved by its factor-model P&L, at today's rate."""
    r0 = fx.today(ccy)
    return round(pv_usd / r0, 2), [round((pv_usd + x) / r0, 2) for x in usd_pnl]


def engine_pnl(fx: Fx, ccy: str, base_pv: float, pvs: list[float]) -> list[float]:
    """The USD P&L the server computes from the files (VarIntake, ScenarioFx.pnlFromPv): PV(j) × FX(j) − PV(base) × FX(base)."""
    today = base_pv * fx.today(ccy)
    return [pvs[j] * fx.at(ccy, j) - today for j in range(len(pvs))]
