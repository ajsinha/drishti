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

"""Identifiers with real check digits, so samples pass the validation real systems apply."""
from __future__ import annotations

import hashlib
import string

_ALNUM = string.digits + string.ascii_uppercase


def _digits(s: str) -> str:
    return "".join(str(_ALNUM.index(c)) for c in s)


def lei(seed: str) -> str:
    """A 20-character ISO 17442 LEI: 4-char LOU prefix, '00', 12-char entity part, 2 check digits (ISO 7064 MOD 97-10)."""
    h = hashlib.sha256(seed.encode()).hexdigest().upper()
    body = "5493" + "00" + "".join(_ALNUM[int(h[i:i + 2], 16) % 36] for i in range(0, 24, 2))
    check = 98 - int(_digits(body + "00")) % 97
    return f"{body}{check:02d}"


def lei_valid(code: str) -> bool:
    return len(code) == 20 and int(_digits(code)) % 97 == 1


def isin(country: str, nsin: str) -> str:
    """ISIN = country + 9-char NSIN + Luhn check digit over the letter-expanded string."""
    body = (country + nsin.rjust(9, "0"))[:11]
    digits = _digits(body)
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2 == 0:
            d *= 2
            d = d - 9 if d > 9 else d
        total += d
    return body + str((10 - total % 10) % 10)


def cusip(base8: str) -> str:
    """CUSIP with its modulus-10 double-add-double check digit."""
    total = 0
    for i, c in enumerate(base8[:8]):
        v = int(c) if c.isdigit() else ord(c) - 55 if c.isalpha() else {"*": 36, "@": 37, "#": 38}[c]
        if i % 2:
            v *= 2
        total += v // 10 + v % 10
    return base8[:8] + str((10 - total % 10) % 10)


def uti(reporting_lei: str, trade_id: str) -> str:
    """A CPMI-IOSCO unique transaction identifier: the generating entity's LEI plus up to 32 unique characters."""
    return reporting_lei + hashlib.sha1(trade_id.encode()).hexdigest().upper()[:20]


def upi(asset_class: str, instrument: str) -> str:
    """A 12-character ANNA DSB-style unique product identifier (QZ prefix)."""
    h = hashlib.sha1(f"{asset_class}|{instrument}".encode()).hexdigest().upper()
    return "QZ" + "".join(_ALNUM[int(h[i:i + 2], 16) % 36] for i in range(0, 20, 2))


def bic(bank: str, country: str, location: str = "33") -> str:
    """An 11-character SWIFT BIC (bank code, country, location, XXX branch)."""
    code = "".join(c for c in bank.upper() if c.isalpha())[:4].ljust(4, "X")
    return f"{code}{country}{location}XXX"
