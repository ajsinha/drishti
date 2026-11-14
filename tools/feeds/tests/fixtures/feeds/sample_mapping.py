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

"""A test feed (never shipped in config/feeds): a recorded JSON API of identifier mappings, one page per day, pulled incrementally.
The tests serve its responses from recorded files (tests/fixtures/feeds/responses/); it is also the worked example of
docs/guides/WORLD_DATA_GUIDE.md "Writing a feed"."""
from __future__ import annotations

from datetime import timedelta

from feedbase import Feed, FeedError

BASE = "https://mappings.example.test/api/v1"


class SampleMapping(Feed):
    def plan(self, run):
        start, end = run.window("mapping")
        return [f"{BASE}/mappings?date={d}" for d in _days(start, end)]

    def pull(self, run):
        start, end = run.window("mapping")
        token = run.key("token")                                   # ${SAMPLE_FEED_TOKEN}; optional in the feed file
        headers = {"Authorization": f"Bearer {token}"} if token else None
        for day in _days(start, end):
            doc = run.get_json(f"{BASE}/mappings?date={day}", name=f"mappings-{day}.json", headers=headers)
            if doc is None:
                raise FeedError(f"no answer for {day}")
            rows = [{"IDENTIFIER": r["id"], "TYPE": r["type"], "ENTITY_LEI": r["lei"], "ENTITY_NAME": r["name"]} for r in doc["data"]]
            run.emit("mapping", rows, business_date=day)
            if doc.get("countries"):
                run.emit("country", [{"COUNTRY": c} for c in doc["countries"]])
        run.mark("mapping", end)


def _days(start, end):
    d = start
    while d <= end:
        yield d
        d += timedelta(days=1)
