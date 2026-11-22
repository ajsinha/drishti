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

"""The lane's console (TEST_HARNESS.md, "Lane servers"): a console process of its own in front of the lane's server, started by
wb_live.lane_stack and stopped by tools/lane_servers.py. Not collected as tests.

    python lane_console.py <server URL> <port> <auth 0|1> <token secret>"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE)]


def console_settings(backend: str, auth: bool, token_secret: str):
    """The console's settings for a test stack: the default configuration in front of ``backend``, sign-in on when ``auth``."""
    from core.config import Settings, load_settings

    data = load_settings(HERE.parent / "config").as_dict()
    data.setdefault("backend", {})["url"] = backend
    if auth:
        data["auth"] = {**data.get("auth", {}), "enabled": True, "session_secret": "s" * 40, "token_secret": token_secret, "secure_cookie": False}
    return Settings(data)


def main(backend: str, port: int, auth: bool, token_secret: str) -> None:
    import uvicorn

    from core.app import create_app

    uvicorn.run(create_app(console_settings(backend, auth, token_secret)), host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2)


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3] == "1", sys.argv[4])
