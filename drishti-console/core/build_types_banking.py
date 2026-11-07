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

"""Banking's object types on the Build home (core/build_registry.py): what a risk person builds. The domain split
(docs/architecture/DOMAIN_MODULES.md) moves this file into the banking module whole; the core names none of it."""
from __future__ import annotations

from urllib.parse import urlencode

from core.build_registry import Item, ObjectType, register
from core.build_types import _ms, _rows
from routes.common import ident


async def baskets(request) -> list[Item]:
    """The VaR baskets the person may open (their own and those shared with them): a scope of positions and trades kept by name."""
    out = []
    for b in _rows(await request.app.state.backend.var("GET", "/baskets", ident(request)), "baskets"):
        name = str(b.get("name") or "")
        if name:
            owner = str(b.get("owner") or "")
            ref = f"{owner}/{name}" if owner else name
            out.append(Item("var-baskets", str(b.get("title") or name), "VaR basket", f"v{b['version']}" if b.get("version") else "saved",
                            "/var?" + urlencode({"scope": ref}), changed=_ms(b.get("updated") or b.get("saved")), by=owner))
    return out


register(ObjectType("var-baskets", "analysis", "VaR baskets", "basket", "shape", domain="banking", requires=("word:VAR",),
                    new="/var", new_label="VaR basket", source=baskets, blurb="Positions and trades kept by name, for VaR on any set"))
