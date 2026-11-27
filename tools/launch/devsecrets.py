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

"""The development secrets: the ONE place they are spelled. ``drishti.py start --demo`` gives them to the server and the console, the IDE
launchers carry them, and a bare ``run_drishti_web.py`` (no --settings, the demo profile) takes them, so a server and a console started
for development on one machine always agree. Only where no secret is set; NOT FOR PRODUCTION (each run that uses them says so).
Standard library only: the console reads this file by its path (drishti-console/run_drishti_web.py)."""
from __future__ import annotations

DEV = {"DRISHTI_SEED_PASSWORD": "drishti-dev-admin123",
       "DRISHTI_TOKEN_SECRET": "drishti-dev-only-token-secret-change-me-0123456789",
       "DRISHTI_SESSION_SECRET": "drishti-dev-only-session-secret-change-me-0123456789",
       "DRISHTI_NET_SECRET": "drishti-dev-only-cluster-key-change-me-0123456789"}
#: the secrets a console needs to match its server (the service identity and the session cookie)
CONSOLE = ("DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET")


def apply(env: dict, names) -> list:
    """The development secrets into ``env``, only where none is set; the names set."""
    out = []
    for n in names:
        if not (env.get(n) or "").strip():
            env[n] = DEV[n]
            out.append(n)
    return out


def warning(names) -> str:
    """The one line a run using them prints (the same words for the server and the console)."""
    return (f"DEVELOPMENT secrets in use (NOT FOR PRODUCTION): {' '.join(names)}. Put your own in an --env-file for anything shared.")
