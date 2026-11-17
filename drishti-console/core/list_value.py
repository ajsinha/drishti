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

"""A list attribute's value in the panel macros (the list format: ``["fx","rates"]``, ``[6, 7]``), by drishti-core's rule (isListValue):
a JSON array of plain values (no objects or arrays) is a list, drawn as chips; anything else is text. The console's pages and the render
parity harness install the same filter and test, so the console's macros and the client-side renderer agree."""
from __future__ import annotations

import json


def items(text):
    """The plain values of a list value's JSON text, or None when the text is not one."""
    if not isinstance(text, str) or not text.strip().startswith("["):
        return None
    try:
        got = json.loads(text)
    except ValueError:
        return None
    return got if isinstance(got, list) and all(not isinstance(x, (dict, list)) for x in got) else None


def is_list_value(text) -> bool:
    """The Jinja test ``list_value``: whether the text is a list value."""
    return items(text) is not None


def list_words(text):
    """The Jinja filter ``list_words``: a list value as words (before drishti-core turns it into chips); other text as it is."""
    got = items(text)
    return text if got is None else ", ".join(str(x) for x in got if x is not None)


def install(env) -> None:
    env.filters["list_words"] = list_words
    env.tests["list_value"] = is_list_value
