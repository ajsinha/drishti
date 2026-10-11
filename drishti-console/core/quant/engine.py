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

"""The console's QuantEngine slot (docs/architecture/QUANT_SERVICE.md section 16): what an Analyse Python cell reaches through ``quant.price(...)``.

An engine prices an *instrument* (a name such as ``black_scholes``) from keyword parameters and returns a number. The console ships a small stand-in
(``standin.py``) so notebooks and tests run anywhere; a real quant library (QLRisk) plugs in by implementing this interface and being named in
``bi.explore.quant_engine`` (see docs/architecture/QUANT_SERVICE.md section 16). Engines are pure functions of their inputs: no network, no files, no state."""
from __future__ import annotations

from abc import ABC, abstractmethod


class QuantError(ValueError):
    """The call is wrong (unknown instrument, a missing or impossible parameter). The message is shown to the author of the cell."""


class QuantEngine(ABC):
    """A pricing engine for Python cells."""

    name = "engine"

    @abstractmethod
    def instruments(self) -> dict[str, list[str]]:
        """The instruments this engine prices, each with the parameter names it takes: ``{"npv": ["rate", "cashflows"]}``."""

    @abstractmethod
    def price(self, instrument: str, **params) -> float:
        """The price (present value) of ``instrument`` for ``params``. Raises :class:`QuantError` for a call it cannot make."""

    def describe(self) -> dict:
        return {"engine": self.name, "instruments": self.instruments()}
