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

"""The Elements API range a module is built for: ``>=<major>.<minor> <<major+1>`` of the one Elements version (static/elements/drishti_elements.json,
"elements"). Every generator that writes a module.yaml uses this, so the range follows the one version source."""
import json
from pathlib import Path

MANIFEST = Path(__file__).resolve().parents[2] / "drishti-console" / "web" / "static" / "elements" / "drishti_elements.json"


def elements_range() -> str:
    major, minor = json.loads(MANIFEST.read_text(encoding="utf-8"))["elements"].split(".")[:2]
    return f">={major}.{minor} <{int(major) + 1}"
