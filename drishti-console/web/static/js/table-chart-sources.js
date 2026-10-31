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
/* Chart any table, the source adapters (docs/guides/RACHANA_REFERENCE.md › Chart any table: the design, section 8.4): what each kind of table hands the chart
   dialog (table-chart.js), which never looks at a table's DOM itself. Every adapter answers the same small interface:
   {identity, title, context, filters, shown, total, approximate, rows() -> Promise<{fields, rows}>, all(settings) -> Promise|null}.
   - search: a search's results and pick lists, from the raw values search.html writes in data-chart-rows (the shown page); every
     match through the search pivot (/api/pivot/search/{kind}) when the kind's pack says pivot: and the chart's fields are its fields.
   - records: a Sutra table whose panel offers a Pivot tab: its raw rows from /api/pivot/records/... (all of them on Chart all N).
   - history: an entity over time, from the raw values history.html writes in data-hx-chart.
   - bi: a BI table or matrix visual, from its last answer (columns with type and masked, rows).
   - dom: any other table, from the cells' text through colfilter.js's parser (1.5m -> 1,500,000): figures as displayed.
   Masked cells stay the server's mask (•••); the engine never charts them. */
(function () {
  'use strict';
  var MASK = '•••';
  function CF() { return (window.drishtiModules || {}).colFilter; }
  function norm(s) { return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }

  /** The heading text of a table's columns, without the filter buttons and sort marks tables.js adds. */
  function headTexts(t) {
    var row = t.tHead && t.tHead.rows.length ? t.tHead.rows[0] : null;
    return row ? Array.prototype.map.call(row.cells, function (h) {
      var c = h.cloneNode(true);
      c.querySelectorAll('button, .cf-btn, .visually-hidden, [aria-hidden="true"]').forEach(function (x) { x.remove(); });
      return { text: norm(c.textContent) || norm(h.getAttribute('title')), masked: !!h.querySelector('.bi-eye-slash') || /\bmasked\b/i.test(h.getAttribute('title') || '') };
    }) : [];
  }
  function unique(names) {
    var seen = {};
    return names.map(function (n, i) {
      var b = n || 'Column ' + (i + 1), k = b, j = 2;
      while (seen[k]) { k = b + ' (' + j++ + ')'; }
      seen[k] = true;
      return k;
    });
  }
  /** A cell's text as a value: null when blank, the mask, an ISO date as text, a number (amounts with k/m/bn, %, separators), or text. */
  function cellValue(text) {
    var t = norm(text), cf = CF();
    if (!t) { return null; }
    if (t.indexOf(MASK) >= 0) { return MASK; }
    var kind = cf ? cf.kindOf(t) : 'text';
    if (kind === 'date') { return t.slice(0, 19); }
    if (kind === 'num') { var v = cf.value(t); return v.n; }
    return t;
  }
  function titleOf(t) {
    var p = t.closest('.pnl, section[data-panel]'), h = p && p.querySelector('.pnl-h h3, h3, h4');
    var tx = norm(h && h.textContent) || norm(t.getAttribute('aria-label')).split(':')[0];
    if (!tx || tx === 'Table') { var h1 = document.querySelector('main h1'); tx = norm(h1 && h1.textContent) || 'Table'; }
    return tx;
  }
  /** Rows of a table a reader has filtered, in words for the status line. */
  function filterWords(filtered) { return filtered ? 'the table’s filters' : ''; }

  // ---- dom: any table, from the cells' text ------------------------------------------------------------------------------------
  function dom(t, o) {
    o = o || {};
    var heads = headTexts(t), names = unique(heads.map(function (h) { return h.text; }));
    var all = function () { return Array.prototype.slice.call(t.tBodies[0] ? t.tBodies[0].rows : []).filter(function (r) { return !r.classList.contains('more'); }); };
    var kept = function () { return (o.visible ? o.visible() : all().filter(function (r) { return !r.__out; })); };
    var more = t.querySelector('tfoot tr.more td');
    var src = {
      kind: 'dom', identity: null, title: o.title || titleOf(t), context: o.context || {}, filters: filterWords(o.filtered),
      shown: kept().length, total: all().length, approximate: true, more: more ? norm(more.textContent) : '',
      rows: function () {
        var list = kept(), n = names.length, ids = new Array(n).fill(0);
        var rows = list.map(function (r) {
          var out = new Array(n);
          for (var i = 0; i < n; i++) {
            var c = r.cells[i];
            out[i] = c ? cellValue(c.textContent) : null;
            if (c && c.querySelector('a[href^="/v/"]')) { ids[i]++; }
          }
          return out;
        });
        var fields = names.map(function (name, i) {
          return { name: name, label: name, masked: heads[i] && heads[i].masked ? true : undefined, id: list.length && ids[i] * 2 >= list.length ? true : undefined };
        });
        return Promise.resolve({ fields: fields, rows: rows });
      },
      all: null
    };
    return src;
  }

  /** Fields named by their labels (as the table heads them), unique; the rows are positional, so only the names change. */
  function byLabel(fields) {
    var names = unique((fields || []).map(function (f) { return f.label || f.name; }));
    return (fields || []).map(function (f, i) { return Object.assign({}, f, { name: names[i], path: f.name }); });
  }

  // ---- records: a Sutra table that offers a Pivot tab ----------------------------------------------------------------------------
  function records(t, o, view, panelId, fetchJson) {
    var base = dom(t, o), cache = null;
    var url = '/api/pivot/records/' + encodeURIComponent(view.getAttribute('data-kind')) + '/' + encodeURIComponent(view.getAttribute('data-id')) + '/' + encodeURIComponent(panelId);
    function read() {
      if (!cache) {
        cache = fetchJson(url).then(function (b) {
          if (!b || !Array.isArray(b.rows)) { throw new Error((b && (b.detail || b.message)) || 'The panel’s rows could not be read'); }
          return b;
        });
        cache.catch(function () { cache = null; });
      }
      return cache;
    }
    var src = Object.assign({}, base, {
      kind: 'records', identity: 'panel:' + (view.getAttribute('data-sutra') || view.getAttribute('data-kind')) + '/' + panelId, approximate: !!o.filtered,
      context: { kind: view.getAttribute('data-kind'), id: view.getAttribute('data-id'), asOf: view.getAttribute('data-page-asof') || null },
      rows: function () {
        if (o.filtered) { return base.rows(); }                   // the reader's filters are on the cells: chart what the table counts
        return read().then(function (b) {
          src.total = b.total != null ? b.total : b.rows.length;
          return { fields: byLabel(b.fields), rows: b.rows.slice(0, Math.max(base.shown, 0)), truncated: false };
        }, function () { src.approximate = true; return base.rows(); });
      },
      all: o.filtered ? null : function () {
        return read().then(function (b) { return { records: { fields: byLabel(b.fields), rows: b.rows, total: b.total, truncated: !!b.truncated } }; });
      }
    });
    return src;
  }

  // ---- search results and pick lists ---------------------------------------------------------------------------------------------
  function search(t, o, data, fetchJson) {
    var fields = data.fields || [], rows = data.rows || [];
    var visible = function () {
      var idx = [];
      Array.prototype.forEach.call(t.tBodies[0].rows, function (r) {
        var i = parseInt(r.getAttribute('data-i'), 10);
        if (!r.__out && isFinite(i) && rows[i]) { idx.push(i); }
      });
      return idx.sort(function (a, b) { return a - b; });
    };
    var pivotFields = {};
    (data.pivot || []).forEach(function (f) { pivotFields[f] = true; });
    var paths = {};
    fields.forEach(function (f) { paths[f.name] = f.path || f.name; });
    function asPivot(name) { var n = String(paths[name] || name || '').replace(/^\$\./, ''); return pivotFields[n] ? n : null; }
    var src = {
      kind: 'search', identity: data.kind ? 'search:' + data.kind : null, title: o.title || ('Search: ' + (data.q || '')), context: { kind: data.kind || null },
      filters: filterWords(o.filtered), shown: visible().length, total: data.matched != null ? data.matched : rows.length, approximate: false,
      rows: function () { var idx = visible(); return Promise.resolve({ fields: fields, rows: idx.map(function (i) { return rows[i]; }) }); },
      /** Every match on the business date, aggregated by the search pivot (the pack's pivot: is its consent); null when it cannot. */
      allFor: function (settings) {
        if (!data.pivot || !data.kind || o.filtered) { return 'Charting every match needs the pack’s pivot:'; }
        if (settings.type === 'scatter' && (settings.series[0] || {}).agg === 'none') { return 'A scatter of points charts the shown rows only'; }
        var need = [settings.x.field].concat(settings.split ? [settings.split] : []).concat(settings.series.map(function (e) { return e.field; }).filter(Boolean));
        var miss = need.filter(function (f) { return !asPivot(f); });
        if (miss.length) { return 'Charting every match works on the pack’s pivot: fields; ' + miss.join(', ') + ' is not one'; }
        var xv = rows.map(function (r) { return r[fields.map(function (f) { return f.name; }).indexOf(settings.x.field)]; }).filter(function (v) { return v != null; });
        if (xv.length && xv.every(function (v) { return /^\d{4}-\d{2}-\d{2}/.test(String(v)); }) && settings.x.bucket !== 'day') {
          return 'Charting every match by a date works by day: choose By day';
        }
        return null;
      },
      all: !data.pivot || !data.kind ? null : function (settings, arrangement) {
        var body = { q: data.q, rows: arrangement.rows.map(asPivot), columns: arrangement.columns.map(asPivot),
          values: arrangement.values.map(function (v) { return { field: v.field === '·rows' ? asPivot(settings.x.field) : asPivot(v.field), agg: v.field === '·rows' ? 'count' : v.agg }; }), filters: [] };
        return fetchJson('/api/pivot/search/' + encodeURIComponent(data.kind), { method: 'POST', body: body }).then(function (b) {
          if (!b || !Array.isArray(b.rowKeys)) { throw new Error((b && (b.detail || b.message)) || 'The search pivot did not answer'); }
          return { cube: b };
        });
      }
    };
    return src;
  }

  // ---- history: an entity over time ----------------------------------------------------------------------------------------------
  function history(data, o) {
    return { kind: 'history', identity: 'history:' + (data.kind || '') + '/' + (data.shape || 'document'), title: o.title || 'History',
      context: { kind: data.kind, id: data.id }, filters: '', shown: (data.rows || []).length, total: (data.rows || []).length, approximate: false,
      rows: function () { return Promise.resolve({ fields: data.fields || [], rows: data.rows || [] }); }, all: null };
  }

  // ---- bi: a BI table or matrix, from its last answer ------------------------------------------------------------------------------
  function bi(res, o) {
    var cols = (res && res.columns) || [], masked = {};
    cols.forEach(function (c, i) { if (c.masked) { masked[i] = true; } });
    var fields = cols.map(function (c) { return { name: c.name, label: c.label || c.name, type: c.type, masked: c.masked ? true : undefined, id: c.key ? true : undefined }; });
    var rows = ((res && res.rows) || []).map(function (r) { return r.map(function (v, i) { return masked[i] ? MASK : v; }); });
    var total = res && res.rowCount != null ? Math.max(res.rowCount, rows.length) : rows.length;
    return { kind: 'bi', identity: o.identity || null, title: o.title || 'Visual', context: {}, filters: '', shown: rows.length, total: total, approximate: false,
      rows: function () { return Promise.resolve({ fields: fields, rows: rows }); }, all: null };
  }

  /** The adapter for a table the enhancer (tables.js) holds: search, records or dom. */
  function forTable(t, o, fetchJson) {
    var raw = t.getAttribute('data-chart-rows');
    if (raw) { try { return search(t, o, JSON.parse(raw), fetchJson); } catch (e) { /* the page's own data is broken: chart the cells */ } }
    var panel = t.closest('section.pnl[data-panel]'), view = t.closest('.view[data-view]');
    var host = panel && panel.querySelector('[data-pivot-panel]');
    if (host && view && panel.querySelectorAll('table.tbl:not([data-plain])').length === 1) { return records(t, o, view, panel.getAttribute('data-panel'), fetchJson); }
    return dom(t, o);
  }

  var api = { forTable: forTable, dom: dom, records: records, search: search, history: history, bi: bi, cellValue: cellValue, headTexts: headTexts };
  (window.drishtiModules = window.drishtiModules || {}).tableChartSources = api;
  window.drishtiTableChartSources = api;
})();
