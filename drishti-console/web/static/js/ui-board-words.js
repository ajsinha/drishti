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
/* Concept A, the Page view's plain words (UI_BOARD_GUIDE.md "The designer"): a board document said the way a person reads a page. Where a
   block's data comes from ("this trade", "trades of this book", "the trading-risk model"), what it follows ("the trade you click in Trades"),
   which fields it shows, and the moves under the pills: the sources a block can take, the blocks it can follow, a block swapped for another chip,
   the page re-flowed after a reorder or a resize. Pure functions over the document and the chips' documents: drishtiUiBoard.words. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const typeOf = (p) => (p && (typeof p.type === 'string' ? p.type.replace(/<.*$/, '') : (p.type && p.type.type))) || '';
  const plain = (s) => String(s || '').replace(/[-_]+/g, ' ').replace(/\b(search|list)$/i, '').trim();
  const plural = (s) => (/s$/.test(s) ? s : s + 's');

  /** The noun of a connector: trading/trade-search -> trade. */
  function noun(connector) { const last = String(connector || '').split('/').pop(); return plain(last.replace(/-search$/, '')) || 'record'; }
  /** The board parameter a query reads, if any: {"id": "%{board.params.id}"} -> ["id", "id"] (the query key, the parameter). */
  function paramOf(query) {
    for (const k of Object.keys(query || {})) {
      const m = /^%\{board\.params\.([a-z0-9-]+)\}$/.exec(String(query[k]));
      if (m) { return [k, m[1]]; }
    }
    return null;
  }

  /** A supply in words. */
  function source(s) {
    s = s || {};
    if (s.connector) {
      const n = noun(s.connector), p = paramOf(s.query), search = /-search$/.test(s.connector) || /search/.test(s.connector);
      if (p && !search) { return 'this ' + n; }
      if (p) { return plural(n) + ' of this ' + plain(p[1]); }
      return search ? plural(n) + ' (search ' + s.connector + ')' : 'the ' + n + ' (' + s.connector + ')';
    }
    if (s.model) { return 'the ' + s.model + ' model'; }
    if (s.live) { return 'the live topic ' + s.live; }
    return '';
  }

  function chipOf(D, inst) { return D.chipDoc(((D.doc.chips || {})[inst] || {}).use) || {}; }
  function titleOf(D, inst) { const i = (D.doc.chips || {})[inst] || {}; return i.title || chipOf(D, inst).title || inst; }

  /** What a selection out pin carries, in words: selected (id of rows.id) of a trades table -> "trade". */
  function thing(D, inst, outPin) {
    const i = (D.doc.chips || {})[inst] || {}, s = i.supply || {};
    if (s.connector) { return noun(s.connector); }
    return plain(outPin) === 'selected' ? 'row' : plain(outPin);
  }

  /** The traces feeding inst, said as what it follows. */
  function follows(D, inst) {
    const ts = (D.doc.traces || []).filter((t) => t.to.split('.')[0] === inst && !/^board\./.test(t.from));
    if (!ts.length) { return ''; }
    const seen = {};
    return ts.map((t) => {
      const src = t.from.split('.')[0], pin = t.from.split('.').slice(1).join('.');
      const k = src + '.' + pin; if (seen[k]) { return null; } seen[k] = true;
      return 'the ' + thing(D, src, pin) + ' you click in ' + titleOf(D, src);
    }).filter(Boolean).join(', and ');
  }

  /** Where a block's data comes from, in words: its own supply, or the block it follows. */
  function fedBy(D, inst) {
    const i = (D.doc.chips || {})[inst] || {};
    const own = source(i.supply), f = follows(D, inst), s = i.supply || {};
    if (own && f && s.connector && !paramOf(s.query) && !/search/.test(s.connector)) { return 'the ' + noun(s.connector) + ' it follows'; }
    if (own) { return own; }
    return f ? 'what it follows' : '';
  }

  /** The data pins of a block not fed by a trace, with their columns: [{pin, spec, cols: [[key, colSpec]]}]. */
  function dataPins(D, inst) {
    const i = (D.doc.chips || {})[inst] || {}, pins = (chipOf(D, inst).pins || {}).in || {};
    const fed = new Set((D.doc.traces || []).filter((t) => t.to.split('.')[0] === inst).map((t) => t.to.split('.').slice(1).join('.')));
    return Object.keys(pins).filter((p) => pins[p].role === 'data' && !fed.has(p)).map((p) => {
      const shape = pins[p].shape || {};
      const cols = Object.keys(shape).length ? Object.keys(shape).map((c) => [p + '.' + c, shape[c]]) : [[p, pins[p]]];
      return { pin: p, spec: pins[p], cols, map: i.map || {} };
    });
  }

  /** The supplier fields a block shows, in order, deduplicated (attribute names). */
  function shown(D, inst) {
    const out = [];
    dataPins(D, inst).forEach((d) => d.cols.forEach(([k]) => {
      const v = d.map[k];
      (Array.isArray(v) ? v : (v == null ? [] : [v])).forEach((a) => { if (out.indexOf(a) < 0) { out.push(a); } });
    }));
    return out;
  }
  /** A field name as a person reads it: COUNTERPARTY_NAME -> Counterparty name; cashflows.PAY_DATE -> Pay date. */
  function field(name, attrs) {
    const a = (attrs || []).find((x) => x.name === name);
    if (a && a.label && a.label !== a.name) { return a.label; }
    const s = String(name).split('.').pop().replace(/_/g, ' ').toLowerCase();
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  /** The blocks inst can follow: [{from, to, text}] for each other block's out pin that fits one of inst's in pins (a control first). */
  function followable(D, inst) {
    const mine = (chipOf(D, inst).pins || {}).in || {}, out = [];
    const ins = Object.keys(mine).sort((a, b) => (mine[a].role === 'control' ? 0 : 1) - (mine[b].role === 'control' ? 0 : 1));
    Object.keys(D.doc.chips || {}).forEach((src) => {
      if (src === inst) { return; }
      const outs = (chipOf(D, src).pins || {}).out || {};
      Object.keys(outs).forEach((o) => {
        const ft = typeOf(outs[o]);
        if (ft === 'rowset') { return; }
        const to = ins.find((p) => mine[p].role !== 'data' && U.compatible(ft, typeOf(mine[p])) && ['id', 'filter'].indexOf(typeOf(mine[p])) >= 0);
        if (!to) { return; }
        out.push({ from: src + '.' + o, to: inst + '.' + to, fromType: ft, toType: typeOf(mine[to]), text: 'The ' + thing(D, src, o) + ' you click in ' + titleOf(D, src) });
      });
    });
    return out;
  }

  /** The sources on this page (each distinct supply of a block) and the page's board parameters, as choices for "Its data". */
  function sources(D) {
    const seen = {}, out = [];
    Object.keys(D.doc.chips || {}).forEach((n) => {
      const s = (D.doc.chips[n] || {}).supply;
      if (!s) { return; }
      const k = JSON.stringify(s);
      if (!seen[k]) { seen[k] = true; out.push({ supply: JSON.parse(k), text: source(s), used: titleOf(D, n) }); }
    });
    return out;
  }

  /** The reading order of the page: the desktop areas by row then column, then any block without an area. */
  function order(D) {
    const areas = (((D.doc.layout || {}).desktop || {}).areas || []).filter((a) => (D.doc.chips || {})[a.chip]);
    const list = areas.slice().sort((a, b) => (a.at[1] - b.at[1]) || (a.at[0] - b.at[0])).map((a) => a.chip);
    Object.keys(D.doc.chips || {}).forEach((n) => { if (list.indexOf(n) < 0) { list.push(n); } });
    return list;
  }
  /** Lays the blocks out again in this order: each keeps its width (columns of 12) and height, packed into rows from the top left. */
  function reflow(D, list) {
    const l = D.doc.layout = D.doc.layout || {};
    const g = l.desktop = l.desktop || { columns: 12, areas: [] }, cols = g.columns || 12;
    const old = {};
    (g.areas || []).forEach((a) => { old[a.chip] = a; });
    let row = 1, x = 1, tall = 0;
    g.areas = list.map((n) => {
      const o = old[n] || { span: [6, 4] }, w = Math.min(cols, Math.max(1, o.span[0])), hgt = o.span[1] || 4;
      if (x + w - 1 > cols) { row += tall; x = 1; tall = 0; }
      const a = { chip: n, at: [x, row], span: [w, hgt] };
      x += w; tall = Math.max(tall, hgt);
      return a;
    });
    const ph = l.phone = l.phone || { order: [], collapsed: [] };
    ph.order = list.slice();
    ph.collapsed = (ph.collapsed || []).filter((n) => list.indexOf(n) >= 0);
  }
  /** Half (six of twelve) or the full width. */
  function setWidth(D, inst, full) {
    const g = ((D.doc.layout || {}).desktop || {}), cols = g.columns || 12;
    const a = (g.areas || []).find((x) => x.chip === inst);
    if (!a) { return; }
    a.span = [full ? cols : Math.round(cols / 2), a.span[1] || 4];
    reflow(D, order(D));
  }
  function isFull(D, inst) {
    const g = ((D.doc.layout || {}).desktop || {}), a = (g.areas || []).find((x) => x.chip === inst);
    return !!a && a.span[0] > (g.columns || 12) / 2;
  }

  /** Swaps a block's chip: the map keys the new chip also has stay; traces into pins it lacks move to a pin that fits, or go. */
  function swap(D, inst, ref, newChip) {
    const i = D.doc.chips[inst], pins = (newChip.pins || {}), ins = pins.in || {}, outs = pins.out || {};
    i.use = ref;
    if (i.map) {
      const keep = {};
      Object.keys(i.map).forEach((k) => {
        const [p, c] = k.split('.');
        const spec = ins[p];
        if (spec && (!c || (spec.shape || {})[c])) { keep[k] = i.map[k]; }
      });
      i.map = keep;
      if (!Object.keys(keep).length) { delete i.map; }
    }
    if (i.controls) { Object.keys(i.controls).forEach((k) => { if (!ins[k]) { delete i.controls[k]; } }); if (!Object.keys(i.controls).length) { delete i.controls; } }
    D.doc.traces = (D.doc.traces || []).filter((t) => {
      if (t.to.split('.')[0] === inst) {
        const p = t.to.split('.').slice(1).join('.');
        if (ins[p]) { return true; }
        const alt = Object.keys(ins).find((q) => ins[q].role !== 'data' && ['id', 'filter'].indexOf(typeOf(ins[q])) >= 0);
        if (alt) { t.to = inst + '.' + alt; return true; }
        return false;
      }
      if (t.from.split('.')[0] === inst) { return !!outs[t.from.split('.').slice(1).join('.')]; }
      return true;
    });
  }

  /** The type of a chip's main data pin, from the store's listing (pins.in: [{name, type, role}]) or a chip document. */
  function mainType(c) {
    const ins = (c && c.pins && c.pins.in) || [];
    if (Array.isArray(ins)) { const d = ins.find((p) => p.role === 'data'); return d ? d.type : ''; }
    const k = Object.keys(ins).find((p) => ins[p].role === 'data');
    return k ? typeOf(ins[k]) : '';
  }

  U.words = { typeOf, noun, source, follows, fedBy, dataPins, shown, field, followable, sources, order, reflow, setWidth, isFull, swap, mainType, titleOf, chipOf, paramOf };
})();
