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
/* What a board's data and chips are, in words, and which chips suit a piece of data: shared by the Wiring view (ui-board-wiring.js) and the
   guided New board flow (ui-board-guided.js). No DOM.
   - fits(chip, attrs): the Chip Store palette's "Fits this data" rule (ui-board-palette.js): every required column of every required data pin
     can be fed by an attribute of a type it accepts.
   - rank(chips, attrs, shape): the chips that fit, best first, each with its role on a new board: the "main" chip shows the data, a "detail"
     (one record) follows the selection of the main table.
   - shortlist(ranked, mains, details): a varied few of them (one per category), for a person to tick.
   - autoMap(pins, attrs): pin column -> supplier attribute, by type and then by name.
   - noun(supply), sourceLabel(supply), words(name): plain words for a supply, a data box and a pin. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const ACCEPTS = { id: ['id'], date: ['date'], datetime: ['date'], measure: ['measure'], number: ['measure'], dimension: ['dimension', 'id', 'date'], text: ['dimension', 'id', 'date', 'measure'], bool: ['dimension'] };
  /** What an out pin's type may feed, kept to the sensible pairs (a selection never feeds a text option). */
  const FEEDS = { id: ['id', 'filter'], date: ['date', 'filter', 'period'], period: ['period', 'filter'], filter: ['filter'], dimension: ['dimension', 'filter'], measure: ['measure', 'number'] };
  /** The attribute names a pin column likes best, beyond its own name (a label is a name, an amount a notional). */
  const SAME = { label: /name|label|title/i, title: /title|deal|name/i, amount: /amount|notional|value/i, value: /value|amount|notional/i, group: /ccy|currency|group|type/i };
  const typeOf = (s) => String((s && typeof s === 'object' ? s.type : s) || '').split('<')[0];

  /** Pins as one list, from the Chip Store listing ([{name, type, columns}]) or a chip document ({name: {type, shape}}). */
  function pinList(pins, dir) {
    const raw = (pins || {})[dir] || [];
    const list = Array.isArray(raw) ? raw : Object.keys(raw).map((n) => Object.assign({ name: n }, raw[n]));
    return list.map((p) => {
      const cols = p.columns || p.shape || {}, columns = {};
      Object.keys(cols).forEach((c) => { const x = cols[c] || {}; columns[c] = { type: typeOf(x), required: !!x.required, many: !!x.many }; });
      return { name: p.name, type: typeOf(p), role: p.role || '', required: !!p.required, of: p.of || '', columns };
    });
  }
  const dataPins = (pins) => pinList(pins, 'in').filter((p) => p.role === 'data' || p.type === 'rowset' || p.type === 'document');

  function fits(c, attrs) {
    if (!attrs) { return true; }
    return dataPins(c.pins).filter((p) => p.required).every((p) => Object.keys(p.columns).filter((col) => p.columns[col].required).every((col) => {
      const want = ACCEPTS[p.columns[col].type] || [p.columns[col].type];
      return attrs.some((a) => want.indexOf(a.type) >= 0);
    }));
  }

  /** pin.column -> attribute (a list for a column that takes many). Required columns first; an attribute is used once while others remain. */
  function autoMap(pins, attrs) {
    const map = {}, used = new Set();
    if (!attrs) { return map; }
    dataPins(pins).forEach((p) => {
      const cols = Object.keys(p.columns).sort((a, b) => Number(p.columns[b].required) - Number(p.columns[a].required));
      cols.forEach((col) => {
        const spec = p.columns[col], want = ACCEPTS[spec.type] || [spec.type];
        const ok = attrs.filter((a) => want.indexOf(a.type) >= 0 && !used.has(a.name));
        const like = SAME[col] || new RegExp(col.replace(/[^a-z0-9]/gi, ''), 'i'), named = (a) => like.test(a.name + ' ' + (a.label || ''));
        const order = Array.from(new Set(ok.filter((a) => a.type === spec.type && named(a)).concat(ok.filter(named), ok.filter((a) => a.type === spec.type), ok)));
        if (!order.length) { return; }
        if (spec.many) {
          const pick = order.slice(0, 3);
          pick.forEach((a) => used.add(a.name));
          map[p.name + '.' + col] = pick.map((a) => a.name);
        } else { used.add(order[0].name); map[p.name + '.' + col] = order[0].name; }
      });
    });
    return map;
  }

  /** The chips that fit the data, best first. shape: "many" (a search: rows) or "one" (one record). */
  function rank(chips, attrs, shape) {
    const out = [];
    (chips || []).forEach((c) => {
      if (c.latest == null) { return; }
      const dp = dataPins(c.pins).filter((p) => p.required);
      if (!dp.length || !fits(c, attrs)) { return; }
      const doc = dp.some((p) => p.type === 'document'), rows = dp.some((p) => p.type === 'rowset');
      const cols = [].concat(...dp.map((p) => Object.keys(p.columns).map((k) => p.columns[k])));
      const fed = cols.filter((x) => (attrs || []).some((a) => (ACCEPTS[x.type] || [x.type]).indexOf(a.type) >= 0)).length;
      const gives = pinList(c.pins, 'out').some((p) => p.type === 'id');
      const follows = pinList(c.pins, 'in').some((p) => p.type === 'id' && p.role !== 'data'), detail = shape === 'many' && doc && !rows;
      let s = 40 * (cols.length ? fed / cols.length : 0) + (c.certified ? 5 : 0);
      if (shape === 'one') { s += doc ? 30 : 0; } else { s += rows ? 25 : 0; s += rows && gives ? 15 : 0; s += detail ? (follows ? 25 : -30) : 0; }
      if (/^(bi|panel)-/.test(c.chip)) { s -= 20; }                                 // the BI and panel wrappers: offered after the purpose-made chips
      if (dp.some((p) => p.type !== 'rowset' && p.type !== 'document')) { s -= 30; }  // a single figure needs a choice of measure first
      out.push({ chip: c.chip, ref: c.chip + '@' + c.latest, title: c.title || cap(words(c.chip)), purpose: c.purpose || '', score: Math.round(s),
        role: detail && follows ? 'detail' : 'main', gives, pins: c.pins, category: c.category || '' });
    });
    return out.sort((a, b) => b.score - a.score || a.chip.localeCompare(b.chip));
  }

  /** A short, varied list from rank(): the best chip of each category (up to `mains`), then the best details that follow a selection. */
  function shortlist(ranked, mains, details) {
    const seen = new Set(), out = [];
    ranked.filter((c) => c.role === 'main').forEach((c) => { if (out.length < mains && !seen.has(c.category || c.chip)) { seen.add(c.category || c.chip); out.push(c); } });
    return out.concat(ranked.filter((c) => c.role === 'detail').slice(0, details));
  }

  const words = (name) => String(name || '').replace(/[-_.]+/g, ' ').trim();
  const cap = (s) => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  const plural = (s) => /s$/.test(s) ? s : (/[^aeiou]y$/.test(s) ? s.slice(0, -1) + 'ies' : s + 's');
  /** {one, many, pack, kind, search, what}: a supply's data in words (trading/trade-search -> trade, trades, trading). */
  function noun(supply) {
    supply = supply || {};
    const ref = supply.connector || supply.live || supply.dataset || '';
    if (ref) {
      const parts = ref.indexOf('/') >= 0 ? ref.split('/') : ['', ref], k = parts[1];
      const search = /-search$/.test(k), base = words(k.replace(/-search$/, ''));
      return { one: base, many: plural(base), pack: parts[0], kind: k, search, what: supply.live ? 'live' : 'connector' };
    }
    if (supply.model) { return { one: 'row', many: words(supply.model) + ' figures', pack: 'BI', kind: supply.model, search: true, what: 'BI model' }; }
    return null;
  }
  /** The data box of a supply: "Trades (trading)" over "connector · trade-search". */
  function sourceLabel(supply) {
    const n = noun(supply);
    if (!n) { return null; }
    return { title: (n.search ? cap(n.many) : 'One ' + n.one) + (n.pack ? ' (' + n.pack + ')' : ''), sub: n.what + ' · ' + n.kind };
  }
  const compatible = (from, to) => (FEEDS[from] || [from]).indexOf(to) >= 0;

  U.suggest = { ACCEPTS, FEEDS, pinList, dataPins, fits, autoMap, rank, shortlist, noun, sourceLabel, words, cap, plural, compatible, typeOf };
})();
