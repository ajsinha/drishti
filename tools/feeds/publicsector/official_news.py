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

"""The official-news feed: press releases and notices of central banks, regulators and governments from their own RSS 2.0, RSS 1.0
(RDF) and Atom feeds and the US Federal Register's API, for the official-news pack (config/feeds/official-news.yaml). Incremental: a
release already kept (by its link) is not added again; the kept releases (history/<publisher>.json) are trimmed to `keep-days` and
`keep-per-source`; no new release anywhere, nothing written. Writes the press-release kind (one a release: title, date, link, summary)
and the release-source kind (one a publisher: its terms and its latest releases with their links)."""
from __future__ import annotations

import hashlib
import html
import json
import re
import xml.etree.ElementTree as ET
from datetime import date, datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

from base import FeedError, PublicFeed

_TAG = re.compile(r"<[^>]+>")
_COMMENT = re.compile(r"<!--.*?-->", re.S)


def text_of(s: str | None, limit: int = 400) -> str | None:
    """Plain text from a feed's HTML fragment: comments and tags removed, entities decoded, spaces folded, cut at `limit`."""
    if not s:
        return None
    t = html.unescape(_TAG.sub(" ", _COMMENT.sub(" ", html.unescape(s))))
    t = " ".join(t.split())
    return (t[: limit - 1] + "…") if len(t) > limit else (t or None)


def when(s: str | None) -> str | None:
    """A feed's date (RFC 822, ISO 8601, or a plain date) as an ISO date-time in UTC."""
    if not s:
        return None
    s = s.strip()
    try:
        d = parsedate_to_datetime(s)
    except (TypeError, ValueError):
        try:
            d = datetime.fromisoformat(s.replace("Z", "+00:00"))
        except ValueError:
            return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=timezone.utc)
    return d.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_feed(raw: bytes) -> list[dict]:
    """RSS 2.0, RSS 1.0 (RDF) or Atom -> [{title, link, published, summary, category}]. Text before the XML (a CMS's debug comments)
    and any DOCTYPE are dropped first; no entity is fetched."""
    t = raw.decode("utf-8", "replace")
    start = min([i for i in (t.find("<?xml"), t.find("<rss"), t.find("<rdf:RDF"), t.find("<feed")) if i >= 0] or [0])
    t = re.sub(r"<!DOCTYPE[^>]*>", "", t[start:], flags=re.I)
    t = re.sub(r"^<\?xml[^>]*\?>", "", t.lstrip())
    root = ET.fromstring(t)
    items = []
    for el in root.iter():
        if _local(el.tag) not in ("item", "entry"):
            continue
        f: dict = {}
        for c in el:
            name = _local(c.tag)
            if name == "link":
                href = c.get("href")
                if href and c.get("rel", "alternate") == "alternate":
                    f.setdefault("link", href)
                elif (c.text or "").strip():
                    f.setdefault("link", c.text.strip())
            elif name in ("title", "description", "summary", "category"):
                f.setdefault(name, "".join(c.itertext()))
            elif name in ("pubDate", "date", "published", "updated", "issued"):
                f.setdefault("date", (c.text or "").strip())
        if not f.get("link") and el.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}about"):
            f["link"] = el.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}about")
        if f.get("title") and f.get("link"):
            items.append({"title": " ".join(f["title"].split()), "link": f["link"].strip(), "published": when(f.get("date")),
                          "summary": text_of(f.get("description") or f.get("summary")), "category": text_of(f.get("category"), 80)})
    return items


def parse_federal_register(raw: bytes) -> list[dict]:
    out = []
    for r in (json.loads(raw).get("results") or []):
        agencies = ", ".join(a.get("name") or a.get("raw_name") or "" for a in r.get("agencies") or [] if a)
        out.append({"title": " ".join((r.get("title") or "").split()), "link": r.get("html_url"),
                    "published": when(r.get("publication_date")), "summary": text_of(r.get("abstract")),
                    "category": " · ".join(x for x in (r.get("type"), agencies) if x) or None})
    return [x for x in out if x["title"] and x["link"]]


def release_id(publisher: str, link: str) -> str:
    return f"PREL-{publisher}-{hashlib.sha1(link.encode()).hexdigest()[:10].upper()}"


class OfficialNews(PublicFeed):
    name = "official-news"

    def pull(self, state: dict) -> dict[str, list[dict]]:
        sel = self.selection
        keep_days, keep_n = int(sel.get("keep-days", 90)), int(sel.get("keep-per-source", 100))
        cutoff = (date.fromisoformat(self.as_of) - timedelta(days=keep_days)).isoformat()
        home = Path(state["dir"]) / "history"
        releases, sources, added, failed = [], [], 0, []
        seen = state.setdefault("seen", {})                  # every release id ever kept, by publisher (bounded): dropped ones stay seen
        for s in self.spec.get("sources") or []:
            if s.get("enabled", True) is False:
                continue
            try:
                raw = self.fetch.raw(s["feed"], headers={"Accept": "application/rss+xml, application/atom+xml, application/xml, application/json, */*"})
                got = parse_federal_register(raw) if s.get("format") == "federal-register" else parse_feed(raw)
            except (FeedError, ET.ParseError, ValueError) as e:
                failed.append(f"{s['id']}: {e}")
                self.log(f"  {s['id']}: {e}")
                got = None
            file = home / f"{s['id']}.json"
            try:
                kept = {r["URL"]: r for r in json.loads(file.read_text(encoding="utf-8"))}
            except (OSError, ValueError):
                kept = {}
            known = seen.setdefault(s["id"], [])
            for it in got or []:
                rid = release_id(s["id"], it["link"])
                if it["link"] not in kept and rid not in known:
                    added += 1
                    kept[it["link"]] = self._release(s, it)
                    known.append(rid)
            del known[:-2000]
            mine = sorted((r for r in kept.values() if (r.get("PUBLISHED") or self.as_of) >= cutoff),
                          key=lambda r: r.get("PUBLISHED") or "", reverse=True)[:keep_n]
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(json.dumps(mine, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
            releases += mine
            sources.append(self._source(s, mine, got is None))
        if failed and len(failed) == len(sources):
            raise FeedError("every publisher failed: " + "; ".join(failed))
        if added == 0 and state.get("lastRun") and not state.get("force"):
            self.log("  no new release since the last pull")
            return {}
        state.pop("force", None)
        return {"press-release": releases, "release-source": sources}

    def _release(self, s: dict, it: dict) -> dict:
        return {"RELEASE_ID": release_id(s["id"], it["link"]), "TITLE": it["title"], "PUBLISHED": it["published"],
                "DATE": (it["published"] or "")[:10] or None, "URL": it["link"], "SUMMARY": it["summary"], "CATEGORY": it["category"],
                "RELEASE_SOURCE": f"PRESS-{s['id']}", "PUBLISHER_NAME": s["title"], "PUBLISHER_TYPE": s["type"], "COUNTRY": s["country"],
                "INTERNAL_ONLY": bool(s.get("internal-only")),
                "PROVENANCE": self.provenance(s["id"], dataset=f"{s['title']}: {s.get('feed-title') or 'press releases'}", url=it["link"])}

    def _source(self, s: dict, mine: list[dict], failed: bool) -> dict:
        cats: dict[str, int] = {}
        for r in mine:
            c = (r.get("CATEGORY") or "Uncategorised").split(" · ")[0]
            cats[c] = cats.get(c, 0) + 1
        return {"SOURCE_ID": f"PRESS-{s['id']}", "NAME": s["title"], "PUBLISHER_TYPE": s["type"], "COUNTRY": s["country"],
                "WEBSITE": s.get("url"), "FEED_URL": s["feed"], "LICENCE": s.get("licence"), "TERMS_URL": s.get("terms"),
                "INTERNAL_ONLY": bool(s.get("internal-only")), "WHY_INTERNAL": s.get("why-internal"),
                "RELEASES": len(mine), "LAST_PUBLISHED": mine[0].get("PUBLISHED") if mine else None,
                "LAST_PULL": "failed: kept releases shown" if failed else "ok",
                "LATEST": [{"PUBLISHED": r["PUBLISHED"], "TITLE": r["TITLE"], "URL": r["URL"], "CATEGORY": r.get("CATEGORY"),
                            "PRESS_RELEASE": r["RELEASE_ID"]} for r in mine[:25]],
                "BY_CATEGORY": [{"CATEGORY": k, "COUNT": v} for k, v in sorted(cats.items(), key=lambda kv: -kv[1])][:12],
                "PROVENANCE": self.provenance(s["id"], dataset=f"{s['title']}: {s.get('feed-title') or 'press releases'}", url=s.get("url"))}
