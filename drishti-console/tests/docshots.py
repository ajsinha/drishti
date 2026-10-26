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


"""Where a browser test puts its documentation pictures: the docs folder only when DRISHTI_SHOTS is set (retaking the pictures on
purpose), otherwise a scratch folder under the system's temporary folder, so a test run (the drill among them) never rewrites the
committed images. tools/drill.sh removes the scratch folder (``drishti-*``) at its end."""
from __future__ import annotations

import os
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def docs_img(rel: str) -> Path:
    """``docs/<rel>`` when DRISHTI_SHOTS is set, else ``<tmp>/drishti-doc-shots/<rel>``."""
    return (ROOT / "docs" / rel) if os.environ.get("DRISHTI_SHOTS") else Path(tempfile.gettempdir()) / "drishti-doc-shots" / rel
