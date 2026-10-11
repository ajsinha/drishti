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
/* Rupaka phase 5 (RUPAKA.md section 12, RUPAKA_POC.md): the Perspective grid and pivot of a report or board. Perspective (vendored, FINOS, Apache-2.0) runs in this
   page as WebAssembly with its engine in a worker of this origin; this file feeds it:

     first load   the visual's rows as Apache Arrow, masked for the person looking (POST /bi/api/reports/<id>/query?format=arrow): Perspective takes the bytes as they are;
     live update  on each tick, only the rows whose updateSeq is above the one the last answer ended at (JSON), applied by key (id); when rows have left the table
                  (evicted, deleted: the table's epoch changed) the whole set is read again and replaces the table;
     fallback     if Perspective cannot start in this tab (the second-tab trap of RUPAKA_POC.md section 8, a browser without WebAssembly), the first rows are shown in
                  Drishti's own table with a line that says so, and stay up to date the same way.

   Only the report viewer and boards load this file, and only for a page that holds a Perspective visual (the console gives such a page, and no other, the narrow
   policy of core/perspective_csp.py). Pivoting is Perspective's, in the browser, on the rows this person may see. No CDN, no blob worker, no inline script. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const P = (B.perspective = B.perspective || {});
  const h = B.h;
  const root = document.getElementById('biCanvas');
  const BASE = (root && root.getAttribute('data-perspective')) || '';

  P.available = () => !!BASE && typeof WebAssembly === 'object';

  let engine = null;
  /** The engine: the viewer element, the datagrid plugin and the client of the worker, started once per page. */
  function boot() {
    if (engine) { return engine; }
    engine = (async () => {
      await import(BASE + '/perspective-viewer/cdn/perspective-viewer.js');
      await import(BASE + '/datagrid/cdn/perspective-viewer-datagrid.js');
      const perspective = (await import(BASE + '/perspective/cdn/perspective.js')).default;
      const worker = new Worker(BASE + '/perspective/cdn/perspective-server.worker.js', { type: 'module' });
      return perspective.worker(Promise.resolve(worker));
    })();
    engine.catch(() => { engine = null; });
    return engine;
  }
  P.boot = boot;

  const theme = () => (/light|paper/.test(document.documentElement.dataset.theme || '') ? 'Pro Light' : 'Pro Dark');
  const short = (f) => String(f).slice(String(f).indexOf('.') + 1);
  const stylesReady = () => Promise.all(B.qa('link[rel=stylesheet]').map((l) => (l.sheet ? null : new Promise((r) => { l.addEventListener('load', r); l.addEventListener('error', r); }))));

  /** The viewer's settings for a visual: the columns of a grid; the rows, columns and aggregates of a pivot (sum for numbers, count for the rest). */
  async function config(spec, table) {
    const f = spec.format || {};
    const base = { plugin: 'Datagrid', settings: false, theme: theme() };
    if (spec.type === 'perspective-grid') {
      return Object.assign(base, { columns: (spec.wells.columns || []).map(short) }, f.sort ? { sort: f.sort } : {});
    }
    const schema = await table.schema();
    const aggregates = {};
    (spec.wells.values || []).map(short).forEach((c) => {
      const named = f.aggregates && (f.aggregates[spec.wells.values.find((w) => short(w) === c)] || f.aggregates[c]);
      aggregates[c] = named || (schema[c] === 'integer' || schema[c] === 'float' ? 'sum' : 'count');
    });
    return Object.assign(base, { group_by: (spec.wells.rows || []).map(short), split_by: (spec.wells.columns || []).map(short), columns: Object.keys(aggregates), aggregates });
  }

  /** The state of one table of a live answer: the counters the next "since" is built from. */
  function tableOf(live, spec) {
    if (!live || !live.tables) { return null; }
    const name = String((spec.wells.columns || spec.wells.rows || spec.wells.values || [''])[0]).split('.')[0];
    return live.tables.find((t) => t.table === name) || live.tables[0] || null;
  }

  /**
   * One Perspective visual.
   * @param spec the visual; @param frame its frame (bi-frame.js); @param ask(format, extraState) the rows: format 'arrow' resolves {buffer, live, rows}, 'json' the usual answer
   * @param ctx the drawing context (the dataset index, the palette ...), used by the plain-table fallback
   */
  P.start = function (spec, frame, ask, ctx) {
    const box = h('div', { class: 'bi-psp', 'data-psp': 'starting' });
    let table = null, viewer = null, seq = 0, epoch = null, rows = 0, dead = false, fallback = false, started = false, lastRes = null;

    const foot = (n, live) => {
      const t = tableOf(live, spec);
      frame.foot.textContent = '';
      frame.foot.appendChild(h('span', { class: 'bi-vis-ms', 'data-ms': '0', text: n + (n === 1 ? ' row' : ' rows') + ' · Perspective' + (t ? ' · live' : '') }));
      if (fallback) { frame.foot.appendChild(h('span', { class: 'bi-vis-masked', text: ' · plain table' })); }
    };
    const shell = () => frame.result({ rowCount: rows, meta: { millis: 0, cached: false, layer: 'live', masked: [] } }, (host) => {
      B.clear(host).appendChild(box);
      return { dispose() { ctl.dispose(); }, resize() {} };
    });

    async function build(first) {
      const client = await boot();
      await stylesReady();
      table = await client.table(first.buffer, { index: 'id' });
      viewer = document.createElement('perspective-viewer');
      viewer.setAttribute('aria-label', spec.alt || spec.title || 'Perspective');
      B.clear(box).appendChild(viewer);
      await viewer.load(table);
      await viewer.restore(await config(spec, table));
      box.setAttribute('data-psp', 'ready');
      document.body.setAttribute('data-psp-ready', 'true');
    }

    function remember(live, n) {
      const t = tableOf(live, spec);
      if (t) { seq = t.seq; epoch = t.epoch; }
      rows = n;
      foot(n, live);
    }

    /** A plain table of the first rows, when Perspective cannot start here. */
    async function plain(why) {
      fallback = true;
      box.setAttribute('data-psp', 'fallback');
      try { if (viewer) { await viewer.delete(); } } catch (e) { /* it never started */ }
      const res = await ask('json', { limit: 500 });
      lastRes = res;
      rows = res.rowCount;
      B.clear(box);
      box.appendChild(h('p', { class: 'bi-vis-note', role: 'status', 'data-psp-note': '', text: 'Perspective could not start in this tab (' + why + '): showing the first ' + res.rowCount + ' rows as a table.' }));
      const inner = h('div', { class: 'bi-psp-plain' });
      box.appendChild(inner);
      B.visuals.dataTable(inner, Object.assign({}, spec, { type: 'table' }), Object.assign({}, ctx, { interactive: false }), res, true);
      remember(res.live, res.rowCount);
    }

    const ctl = {
      async load() {
        started = true;
        shell();
        try {
          if (!P.available()) { throw new Error('this page may not run WebAssembly'); }
          const first = await ask('arrow');
          await build(first);
          remember(first.live, first.rows);
        } catch (e) {
          if (e && e.status) { throw e; }                                  // the server refused: the frame shows its code and words
          await plain(e && e.message ? String(e.message).split('\n')[0].slice(0, 120) : 'unknown error');
        }
      },
      /** A new full read (a slicer moved): replaces the table's rows. */
      async reload() {
        if (!started) { return ctl.load(); }
        if (fallback) { return plain('see above'); }
        const first = await ask('arrow');
        await table.replace(first.buffer);
        remember(first.live, first.rows);
      },
      /** A tick: only what changed since, by key; the whole set again when rows have left. */
      async refresh() {
        if (!started || dead) { return; }
        if (fallback) { return plain('see above'); }
        const res = await ask('json', { since: seq });
        const t = tableOf(res.live, spec);
        if (t && epoch !== null && t.epoch !== epoch) { return ctl.reload(); }
        if (res.rowCount) {
          const names = res.columns.map((c) => c.name);
          await table.update(res.rows.map((r) => { const o = {}; names.forEach((n, i) => { o[n] = r[i]; }); return o; }));
        }
        if (t) { seq = t.seq; epoch = t.epoch; }
        const size = await table.size();
        rows = size;
        foot(size, res.live);
        box.setAttribute('data-psp-rows', String(size));
        box.setAttribute('data-psp-changed', String(res.rowCount));
      },
      /** Rows now in the table, for tests and for the line under the visual. */
      size: () => (table ? table.size() : Promise.resolve(lastRes ? lastRes.rowCount : 0)),
      viewer: () => viewer,
      isPlain: () => fallback,
      dispose() {
        dead = true;
        const v = viewer, t = table;
        viewer = null;
        table = null;
        Promise.resolve().then(async () => { try { if (v) { await v.delete(); } if (t) { await t.delete(); } } catch (e) { /* already gone */ } });
      }
    };
    return ctl;
  };

  /** The Arrow answer of a visual: the bytes, the table's live state (X-Bi-Live) and the rows. Rejects like B.api (code, status, detail). */
  B.arrow = function (path, body) {
    return fetch('/bi/api' + path, { method: 'POST', headers: { Accept: 'application/vnd.apache.arrow.stream', 'Content-Type': 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then((r) => {
        if (!r.ok) {
          return r.json().catch(() => ({})).then((data) => {
            const e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText));
            e.code = data.code; e.status = r.status; e.detail = data.detail || r.statusText;
            throw e;
          });
        }
        let live = null;
        try { live = JSON.parse(r.headers.get('x-bi-live') || 'null'); } catch (e) { live = null; }
        return r.arrayBuffer().then((buffer) => ({ buffer, live, rows: Number(r.headers.get('x-bi-rows') || 0) }));
      });
  };
})();
