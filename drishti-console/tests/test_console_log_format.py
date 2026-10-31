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
"""The console's log carries a timestamp on every line, uvicorn's and its own (2.9.7, RESOURCES_AND_SCALE.md 6.4, D9)."""
import io
import logging
import logging.config
import re

import run_drishti_web


def test_every_line_has_a_timestamp():
    cfg = run_drishti_web.log_config()
    assert all("%(asctime)s" in f["fmt"] for f in cfg["formatters"].values())
    assert cfg["loggers"][""]["handlers"] == ["default"]
    out = io.StringIO()
    fmt = logging.Formatter(cfg["formatters"]["default"]["fmt"].replace("%(levelprefix)s", "%(levelname)s:"), cfg["formatters"]["default"]["datefmt"])
    h = logging.StreamHandler(out)
    h.setFormatter(fmt)
    log = logging.getLogger("drishti.test.log-format")
    log.addHandler(h)
    log.setLevel(logging.INFO)
    log.propagate = False
    log.info("a console line")
    assert re.match(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{4} INFO: a console line", out.getvalue())
