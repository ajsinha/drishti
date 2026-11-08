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

"""The feeds' tests run on recorded responses only. `--live` also runs the tests marked `live`: one smoke per feed against its real
source, for a person to run by hand (never in a drill):

    python3 -m pytest tools/feeds/tests/test_live.py --live                 every feed
    python3 -m pytest tools/feeds/tests/test_live.py --live -k gleif        one
"""
import pytest


def pytest_addoption(parser):
    parser.addoption("--live", action="store_true", default=False, help="also run the live smokes: the real sources, over the network")


def pytest_configure(config):
    config.addinivalue_line("markers", "live: a live smoke against a feed's real source (only with --live)")


def pytest_collection_modifyitems(config, items):
    if config.getoption("--live"):
        return
    skip = pytest.mark.skip(reason="a live smoke: run by hand with --live")
    for item in items:
        if "live" in item.keywords:
            item.add_marker(skip)
