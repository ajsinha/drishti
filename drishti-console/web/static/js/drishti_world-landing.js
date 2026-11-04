/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* Drishti World's Home (home/world_landing.html; EDITIONS.md › The design as built, section 7): fills the landing's panels from the public-data packs' own
   answers, through the console's /api/home/search (the server's search grammar, for a command the edition shows) and /api/raw: country
   groups (ECG) and economies (ECON: growth, inflation, debt to GDP, the growth path), the IMF outlook's vintage (FCST), the quotes of the
   market snapshot (QUOTE), news topics and their stories (TOPIC, NEWS), the filings feed (FILING, EDGAR), US companies by sector (USEQ),
   funds and the indexes they track (FUND, TRACK), and the counts of each. Each panel is painted when its answer arrives; one refused or
   empty says so in place and the others carry on. Nothing is invented: a figure the packs do not serve is left out. Widths are set
   through the CSSOM (the page's policy allows no inline style). */
(function () {
  "use strict";
  var root = document.querySelector("[data-world-landing]");
  if (!root) return;
  var landing = root.getAttribute("data-world-landing");
  var $ = function (sel) { return root.querySelector(sel); };
  var $$ = function (sel) { return Array.prototype.slice.call(root.querySelectorAll(sel)); };
  var G7 = ["CAN", "FRA", "DEU", "ITA", "JPN", "GBR", "USA"];
  var G20 = ["ARG", "AUS", "BRA", "CAN", "CHN", "FRA", "DEU", "IND", "IDN", "ITA", "JPN", "KOR", "MEX", "RUS", "SAU", "ZAF", "TUR", "GBR", "USA"];

  function get(path) {
    return fetch(path, { headers: { Accept: "application/json" }, credentials: "same-origin" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) {
        if (!r.ok) { var e = new Error((b && b.detail) || ("HTTP " + r.status)); e.code = b && b.code; throw e; }
        return b;
      });
    });
  }
  var cache = {};
  function search(q) { return cache[q] || (cache[q] = get("/api/home/search?q=" + encodeURIComponent(q))); }
  function raw(kind, id) { return get("/api/raw/" + encodeURIComponent(kind) + "/" + encodeURIComponent(id)); }
  function rows(answer) {
    return (answer && answer.rows || []).map(function (r) {
      var v = {}, vals = r.values || {};
      Object.keys(vals).forEach(function (k) { v[k.replace(/^\$\./, "")] = vals[k]; });
      v._id = (r.ref || {}).id || r.title;
      return v;
    });
  }
  function num(v) { return v === null || v === undefined || v === "" || isNaN(v) ? null : Number(v); }
  function pct(v, d) { v = num(v); return v === null ? "—" : v.toFixed(d === undefined ? 1 : d) + "%"; }
  function signed(v, unit) {
    v = num(v);
    if (v === null) return "—";
    var t = Math.abs(v).toFixed(1);
    return (Number(t) === 0 ? "" : v > 0 ? "+" : "−") + t + (unit || "");
  }
  function money(v) {
    v = num(v);
    if (v === null) return "—";
    var a = Math.abs(v);
    if (a >= 1e12) return "$" + (a / 1e12).toFixed(2) + "tn";
    if (a >= 1e9) return "$" + (a / 1e9).toFixed(1) + "bn";
    if (a >= 1e6) return "$" + (a / 1e6).toFixed(1) + "m";
    return "$" + Math.round(a);
  }
  function count(v) { v = num(v); return v === null ? "—" : Math.round(v).toLocaleString("en-US"); }
  function text(sel, v) { $$(sel).forEach(function (e) { e.textContent = v; }); }
  function el(tag, cls, txt) { var e = document.createElement(tag); if (cls) e.className = cls; if (txt !== undefined) e.textContent = txt; return e; }
  function go(cmd) { return "/go?q=" + encodeURIComponent(cmd + " <GO>"); }
  function link(href, label, cls) { var a = el("a", cls || "", label); a.href = href; return a; }
  function said(target, err, cols) {
    if (!target) return;
    target.textContent = "";
    var msg = "Not available: " + (err && err.message || err);
    if (target.tagName === "TBODY") { var tr = el("tr"), td = el("td", "wl-muted", msg); td.colSpan = cols || 4; tr.appendChild(td); target.appendChild(tr); }
    else target.appendChild(el(target.tagName === "UL" ? "li" : "p", "wl-muted", msg));
  }
  function cell(tr, content, cls) { var td = el("td", cls || ""); if (content && content.nodeType) td.appendChild(content); else td.textContent = content; tr.appendChild(td); }
  function tone(e, v) { v = num(v); if (v !== null) e.classList.add(v > 0 ? "up" : v < 0 ? "down" : "flat"); return e; }

  // the meta line: the business date and the IMF outlook's vintage (the largest economy's forecast), the snapshot's time (quotes)
  if ($("[data-wl-date]")) {
    raw("economic-forecast", "FCST-USA-WEO").then(function (f) {
      text("[data-wl-date]", (f.provenance || {}).businessDate || "—");
      if ((f.data || {}).VINTAGE) text("[data-wl-vintage]", f.data.VINTAGE);
    }).catch(function () { text("[data-wl-date]", "—"); });
  }

  // the quotes of the market snapshot (A: breadth and the biggest mover; the meta line; C: what changed)
  var quotes = landing === "explorer" ? Promise.resolve([]) : search("QUOTE where PRICE > 0 and AS_OF != '-' order by CHANGE_PCT desc limit 200").then(rows);
  quotes.then(function (q) {
    var asOf = (q[0] || {}).AS_OF;
    if (asOf) text("[data-wl-snapshot]", "the market snapshot of " + String(asOf).split(" (")[0]);
  }).catch(function () {});

  function desk() {
    // World GDP growth: the World country group, as the pack computes it (GDP-weighted)
    search("ECG limit 100").then(function (a) {
      var w = rows(a).filter(function (g) { return g._id === "ECG-WLD"; })[0];
      if (!w) { text("[data-wl-world]", "—"); text("[data-wl-world-s]", "no World group"); return; }
      text("[data-wl-world]", pct(w.GROWTH));
      text("[data-wl-world-s]", count(w.ECONOMIES) + " economies, GDP-weighted · ECG ECG-WLD");
    }).catch(function (e) { text("[data-wl-world]", "—"); text("[data-wl-world-s]", e.message); });

    quotes.then(function (q) {
      var up = q.filter(function (x) { return num(x.CHANGE_PCT) > 0; }).length, down = q.filter(function (x) { return num(x.CHANGE_PCT) < 0; }).length;
      text("[data-wl-breadth]", up + " · " + down);
      text("[data-wl-breadth-s]", q.length + " quotes" + (q[0] && q[0].AS_OF ? " · " + String(q[0].AS_OF).split(" (")[0] : ""));
      var m = q.slice().sort(function (a, b) { return Math.abs(num(b.CHANGE_PCT) || 0) - Math.abs(num(a.CHANGE_PCT) || 0); })[0];
      if (!m) { text("[data-wl-mover]", "—"); return; }
      var v = $("[data-wl-mover]");
      v.textContent = (m.SYMBOL || m._id) + " ";
      v.appendChild(tone(el("small", "", signed(m.CHANGE_PCT, "%")), m.CHANGE_PCT));
      text("[data-wl-mover-s]", (m.NAME || "") + " · " + (num(m.PRICE) === null ? "" : "price " + Number(m.PRICE).toFixed(2)));
      var t = $("[data-wl-tile=mover]");
      if (t) t.href = go("QUOTE " + (m.SYMBOL || m._id));
    }).catch(function (e) { text("[data-wl-breadth]", "—"); text("[data-wl-breadth-s]", e.message); text("[data-wl-mover]", "—"); });

    latestFilings().then(function (f) {
      text("[data-wl-filings]", count(f.n));
      text("[data-wl-filings-s]", "filed " + f.date + ", the latest day in the EDGAR feed");
    }).catch(function (e) { text("[data-wl-filings]", "—"); text("[data-wl-filings-s]", e.message); });

    search("FUND where NET_ASSETS > 0 order by NET_ASSETS desc limit 1").then(function (a) {
      var top = rows(a)[0];
      text("[data-wl-funds-n]", count(a.matched));
      text("[data-wl-funds-s]", top ? "largest " + top._id + " · " + money(top.NET_ASSETS) : "");
    }).catch(function (e) { text("[data-wl-funds-n]", "—"); text("[data-wl-funds-s]", e.message); });

    // the economies that moved: the twelve largest, this year's growth against the year before (each one's growth path)
    var moved = $("[data-wl-moved]");
    search("ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 12").then(function (a) {
      var list = rows(a);
      return Promise.all(list.map(function (e) {
        return raw("economy", e._id).then(function (r) {
          var path = ((r.data || {}).GROWTH_PATH || []).filter(function (p) { return num(p.GROWTH) !== null; });
          var last = path[path.length - 1], prev = path[path.length - 2];
          e.delta = last && prev ? num(last.GROWTH) - num(prev.GROWTH) : null;
          e.year = last ? last.YEAR : "";
          return e;
        }).catch(function () { e.delta = null; return e; });
      }));
    }).then(function (list) {
      moved.textContent = "";
      var shown = list.filter(function (e) { return e.delta !== null; })
        .sort(function (a, b) { return Math.abs(b.delta) - Math.abs(a.delta); }).slice(0, 6);
      if (!shown.length) { said(moved, "no growth path served", 4); return; }
      shown.forEach(function (e) {
        var tr = el("tr"), name = el("span");
        name.appendChild(link(go("ECON " + (e.ISO3 || e._id)), "ECON " + (e.ISO3 || e._id), "mono"));
        name.appendChild(document.createTextNode(" " + (e.NAME || "")));
        cell(tr, name);
        cell(tr, pct(e.GROWTH), "n");
        cell(tr, pct(e.INFLATION), "n");
        cell(tr, tone(el("span", "", signed(e.delta)), e.delta), "n");
        moved.appendChild(tr);
      });
    }).catch(function (e) { said(moved, e, 4); });

    // today's news by topic: each topic's latest stories (the edition's home.topics), newest first
    var feed = $("[data-wl-news]");
    var wanted = (root.getAttribute("data-topics") || "").split(",").filter(Boolean);
    search("TOPIC order by NEWS_ID limit 100").then(function (a) {
      var topics = rows(a).filter(function (t) { return !wanted.length || wanted.indexOf(t._id) >= 0; }).slice(0, 8);
      return Promise.all(topics.map(function (t) {
        return raw("news", t.NEWS_ID).then(function (n) {
          return ((n.data || {}).ITEMS || []).slice(0, 2).map(function (it) { return { topic: t, item: it }; });
        }).catch(function () { return []; });
      }));
    }).then(function (lists) {
      var all = [].concat.apply([], lists).sort(function (a, b) { return String(b.item.PUBLISHED || "").localeCompare(String(a.item.PUBLISHED || "")); }).slice(0, 8);
      feed.textContent = "";
      if (!all.length) { said(feed, "no stories for these topics yet"); return; }
      all.forEach(function (x) {
        var li = el("li"), when = String(x.item.PUBLISHED || "");
        var time = el("time", "mono", when.slice(11, 16) || when.slice(0, 10));
        time.setAttribute("datetime", when.replace(" UTC", "Z").replace(" ", "T"));
        time.title = when;
        li.appendChild(time);
        li.appendChild(link(go("TOPIC " + x.topic._id), x.topic.NAME || x.topic._id, "wl-tag"));
        var story = x.item.LINK ? link(x.item.LINK, x.item.TITLE || "") : el("span", "", x.item.TITLE || "");
        if (x.item.LINK) { story.target = "_blank"; story.rel = "noopener noreferrer"; }
        if (x.item.SOURCE) story.title = x.item.SOURCE;
        li.appendChild(story);
        feed.appendChild(li);
      });
    }).catch(function (e) { said(feed, e); });
  }

  // the latest day of the filings feed and how many filings it holds
  function latestFilings() {
    return search("FILING order by FILED desc limit 1").then(function (a) {
      var d = (rows(a)[0] || {}).FILED;
      if (!d || !/^\d{4}-\d{2}-\d{2}$/.test(d)) throw new Error("no filing dates");
      return search("FILING where FILED = '" + d + "' limit 1").then(function (b) { return { date: d, n: b.matched, total: a.matched }; });
    });
  }

  function explorer() {
    $$("[data-wl-count]").forEach(function (b) {
      var m = b.getAttribute("data-wl-count");
      search(m + " limit 1").then(function (a) { b.textContent = count(a.matched); }).catch(function () { b.textContent = "—"; });
    });
  }

  function map() {
    var group = "region", measure = "growth";
    var MEASURES = { growth: { field: "GROWTH", label: "growth" }, inflation: { field: "INFLATION", label: "inflation" }, debt: { field: "DEBT_TO_GDP", label: "debt to GDP" } };
    var GROUPS = { region: "region", income: "income group", g7: "the G7", g20: "the G20" };
    var box = $("[data-wl-regions]");
    var data = Promise.all([search("ECON where GDP_USD_BN > 0 order by GDP_USD_BN desc limit 400").then(rows), search("ECG limit 100").then(rows).catch(function () { return []; })]);
    function weighted(list, field) {
      var w = 0, s = 0;
      list.forEach(function (e) { var v = num(e[field]), g = num(e.GDP_USD_BN); if (v !== null && g) { w += g; s += v * g; } });
      return w ? s / w : null;
    }
    function tile(name, href, figures, n, hot) {
      var a = link(href, "", "wl-reg" + (hot ? " hot" : ""));
      a.appendChild(el("b", "", name));
      a.appendChild(el("span", "mono", "growth " + pct(figures.GROWTH) + " · infl " + pct(figures.INFLATION)));
      a.appendChild(el("span", "mono", "debt " + pct(figures.DEBT_TO_GDP, 0) + " of GDP" + (n ? " · " + n + (n === 1 ? " economy" : " economies") : "")));
      return a;
    }
    function paint() {
      text("[data-wl-group-title]", GROUPS[group]);
      text("[data-wl-measure-title]", MEASURES[measure].label + (group === "g7" || group === "g20" ? ", each economy" : ", GDP-weighted"));
      data.then(function (d) {
        var econ = d[0], groups = d[1], f = MEASURES[measure].field, tiles = [];
        if (group === "g7" || group === "g20") {
          var members = group === "g7" ? G7 : G20;
          econ.filter(function (e) { return members.indexOf(e.ISO3) >= 0; }).forEach(function (e) {
            tiles.push({ name: e.NAME, href: go("ECON " + e.ISO3), fig: e, n: 0, v: num(e[f]) });
          });
        } else {
          var key = group === "region" ? "REGION_NAME" : "INCOME_NAME", by = {};
          econ.forEach(function (e) { if (e[key]) (by[e[key]] = by[e[key]] || []).push(e); });
          Object.keys(by).forEach(function (name) {
            var g = groups.filter(function (x) { return x.NAME === name; })[0], list = by[name];
            var fig = { GROWTH: weighted(list, "GROWTH"), INFLATION: weighted(list, "INFLATION"), DEBT_TO_GDP: weighted(list, "DEBT_TO_GDP") };
            tiles.push({ name: name, href: g ? go("ECG " + g._id) : go("ECON where " + key + " = '" + name.replace(/'/g, "") + "'"), fig: fig, n: list.length, v: fig[f] });
          });
        }
        tiles.sort(function (a, b) { return (b.v === null ? -1e9 : b.v) - (a.v === null ? -1e9 : a.v); });
        box.textContent = "";
        if (!tiles.length) { said(box, "no economies served"); return; }
        tiles.forEach(function (t, i) { box.appendChild(tile(t.name, t.href, t.fig, t.n, i === 0 && t.v !== null)); });
      }).catch(function (e) { said(box, e); });
    }
    function seg(sel, attr, set) {
      $$(sel + " button").forEach(function (b) {
        b.addEventListener("click", function () {
          set(b.getAttribute(attr));
          $$(sel + " button").forEach(function (x) { var on = x === b; x.classList.toggle("on", on); x.setAttribute("aria-pressed", on ? "true" : "false"); });
          paint();
        });
      });
    }
    seg("[data-wl-groups]", "data-group", function (v) { group = v; });
    seg("[data-wl-measures]", "data-measure", function (v) { measure = v; });
    paint();

    // US sectors: the companies' revenue summed by SIC division, as shares of the whole
    var bars = $("[data-wl-sectors]");
    search("USEQ where REVENUE > 0 and SECTOR != '' order by REVENUE desc limit 1000").then(rows).then(function (list) {
      var by = {}, total = 0;
      list.forEach(function (c) { var r = num(c.REVENUE); if (r) { by[c.SECTOR] = (by[c.SECTOR] || 0) + r; total += r; } });
      var top = Object.keys(by).sort(function (a, b) { return by[b] - by[a]; }).slice(0, 6);
      bars.textContent = "";
      if (!top.length || !total) { said(bars, "no company revenue served"); return; }
      var max = by[top[0]];
      top.forEach(function (s) {
        var r = link(go("USEQ where SECTOR = '" + s.replace(/'/g, "") + "' order by REVENUE desc"), "", "wl-bar");
        r.appendChild(el("span", "wl-bar-l", s));
        var track = el("span", "wl-track"), fill = el("span", "wl-fill");
        fill.style.width = (by[s] / max * 100).toFixed(1) + "%";
        track.appendChild(fill);
        r.appendChild(track);
        r.appendChild(el("span", "wl-bar-v mono", Math.round(by[s] / total * 100) + "%"));
        r.title = money(by[s]) + " of " + money(total) + " revenue, " + list.length + " companies";
        bars.appendChild(r);
      });
    }).catch(function (e) { said(bars, e); });

    // the largest funds, with the index each tracks (TRACK names the indexes)
    var funds = $("[data-wl-funds]");
    Promise.all([search("FUND where NET_ASSETS > 0 and INDEX_ID != '-' order by NET_ASSETS desc limit 5").then(rows),
                 search("TRACK limit 50").then(rows).catch(function () { return []; })]).then(function (d) {
      var names = {};
      d[1].forEach(function (t) { names[t.INDEX || t._id] = t.NAME; });
      funds.textContent = "";
      if (!d[0].length) { said(funds, "no funds served", 4); return; }
      d[0].forEach(function (f) {
        var tr = el("tr");
        cell(tr, link(go("FUND " + f._id), f._id, "mono"));
        cell(tr, f.INDEX_ID ? (names[f.INDEX_ID] || f.INDEX_ID) : (f.TYPE || "—"));
        cell(tr, count(f.HOLDING_COUNT), "n");
        cell(tr, money(f.NET_ASSETS), "n");
        tr.title = f.NAME || "";
        funds.appendChild(tr);
      });
    }).catch(function (e) { said(funds, e, 4); });

    // what changed: the latest of each source (the IMF outlook's vintage, the filings feed, the market snapshot, GLEIF)
    var changes = $("[data-wl-changes]"), lines = [];
    function add(rank, when, what, href) { lines.push({ rank: rank, when: when, what: what, href: href }); }
    Promise.all([
      raw("economic-forecast", "FCST-USA-WEO").then(function (f) { if ((f.data || {}).VINTAGE) add(1, "outlook", "IMF outlook: " + f.data.VINTAGE, go("FCST FCST-USA-WEO")); }).catch(function () {}),
      latestFilings().then(function (f) { add(2, f.date.slice(5), count(f.n) + " filings on " + f.date + " (" + count(f.total) + " in the feed)", go("EDGAR 8-K")); }).catch(function () {}),
      raw("filing-feed", "10-K").then(function (r) {
        var last = ((r.data || {}).ROWS || [])[0];
        if (last) add(3, String(last.FILED || "").slice(5), "Latest annual report: " + (last.COMPANY_NAME || last.COMPANY) + " (10-K)", go("USEQ " + last.COMPANY));
      }).catch(function () {}),
      quotes.then(function (q) {
        var up = q.filter(function (x) { return num(x.CHANGE_PCT) > 0; }).length;
        if (q.length) add(4, "prices", "Market snapshot" + (q[0].AS_OF ? " of " + String(q[0].AS_OF).split(" (")[0] : "") + ": " + up + " of " + q.length + " quotes up", go("QUOTE where PRICE > 0 order by CHANGE_PCT desc"));
      }).catch(function () {}),
      Promise.all([search("LEI limit 1"), search("LEIG limit 1")]).then(function (d) {
        add(5, "GLEIF", count(d[0].matched) + " legal entities in " + count(d[1].matched) + " groups on file", go("LEIC US"));
      }).catch(function () {})
    ]).then(function () {
      changes.textContent = "";
      if (!lines.length) { said(changes, "nothing served yet"); return; }
      lines.sort(function (a, b) { return a.rank - b.rank; }).forEach(function (l) {
        var li = el("li");
        li.appendChild(el("time", "mono", l.when));
        li.appendChild(l.href ? link(l.href, l.what) : el("span", "", l.what));
        changes.appendChild(li);
      });
    });
  }

  if (landing === "explorer") explorer();
  else if (landing === "map") map();
  else desk();
})();
