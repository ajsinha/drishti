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

"""Drishti console entry point.

    python console/run_drishti_web.py [--server.port=17480] [--backend.url=http://...]
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import uvicorn  # noqa: E402

from core.app import create_app  # noqa: E402
from core.config import load_settings  # noqa: E402


def main() -> None:
    settings = load_settings(HERE / "config", sys.argv[1:])
    app = create_app(settings)
    uvicorn.run(app, host=settings.get("server.host"), port=int(settings.get("server.port")),
                log_level="info", access_log=False)


if __name__ == "__main__":
    main()
