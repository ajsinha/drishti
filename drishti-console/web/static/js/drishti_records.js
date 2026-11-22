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
/* Records everywhere: every table of records of the console is drishti-grid's LiveSetGrid (live and static mode), mounted by this one file. A page marks
   a host with data-records and one of three things: data-live-set (the name of a live table of the server: the rows are read through the Data Protocol,
   /api/rdp/tables/<name>/query with the declaration's query (a FORM's filter, its order), its changes follow on the console's live channel (live:<name>)
   while the table is live; the grid's record panel writes through /api/rdp/tables/<name>/write; data-drishti-offset and data-limit name the page of a larger set), data-rows (a result without a record key:
   a JSON array of plain rows, shown in the grid's static mode: no merge, no edit, the panel shows the row's JSON) or nothing (a script calls
   Drishti.records.mount(host, {rows, columns, title}) with rows it has read). data-spec holds the set's declaration (or, static, {title, columns}). The
   grid's own look, its sort, filter, grouping, record panel and Pivot, are the same everywhere; nothing of a table is drawn by a page. */
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
(function () {
  'use strict';
  var CORE = window.Drishti.elements.core;    // the vendored Drishti Elements' sheets, by drishti-core's static root (base.html loads it first)
  var CSS = [CORE.staticUrl('elements/drishti-grid/drishti_grid.css'), CORE.staticUrl('elements/drishti-inputs/drishti_inputs.css'), drishti_url('/static/css/drishti_records.css')];
  var tag = document.querySelector('script[src*="drishti_records.js"]'), version = ((tag && tag.getAttribute('src')) || '').split('?')[1] || '';
  var NONE = { subscribe: function () { return function () {}; } };

  function styles() {
    CSS.forEach(function (href) {
      var name = href.split('/').pop();
      if (document.querySelector('link[href*="' + name + '"]')) { return; }
      var l = document.createElement('link');
      l.rel = 'stylesheet'; l.href = drishti_url(href + (version ? '?' + version : ''));
      document.head.appendChild(l);
    });
  }
  /** Chart these rows: the held rows as the columns draw them, handed to the one chart dialog (Chart any table, drishti_table-chart.js). */
  function chart(x) {
    var tables = (window.drishtiModules || {}).tables, grid = window.Drishti.elements && window.Drishti.elements.grid;
    if (!tables || !grid) { return; }
    var cols = (x.columns || []).filter(function (c) { return c.field; });
    var res = { columns: cols.map(function (c) { return { name: c.label || c.field, label: c.label || c.field, type: c.type === 'number' || c.type === 'amount' ? 'number' : 'text', key: c.type === 'id' }; }),
      rows: x.rows.map(function (r) { return cols.map(function (c) { var v = r[c.field]; return v && typeof v === 'object' ? grid.nestedText(v) : v; }); }) };
    tables.openChart(function (S) { return S.bi(res, { title: x.title || 'Results' }); }, document.activeElement);
  }
  /** Fills the window: a list of records is as tall as what is left of the viewport under its start, less what follows it in the page (a pager, the footer),
      so the heading and toolbar stay and the rows scroll inside the grid, never the page too. It follows the window's size. A phone keeps the page's flow. */
  var fitted = [];
  function after(host) {                                   // the height of what follows the host in the page: its later siblings and its parents' bottoms, up to the body
    var below = 0, n = host, cs, sib;
    while (n && n !== document.body) {
      for (sib = n.nextElementSibling; sib; sib = sib.nextElementSibling) {
        cs = getComputedStyle(sib);
        if (cs.display === 'none' || cs.position === 'fixed' || cs.position === 'absolute' || sib.tagName === 'SCRIPT') { continue; }
        below += sib.getBoundingClientRect().height + (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0);
      }
      n = n.parentElement;
      if (n && n !== document.body) { cs = getComputedStyle(n); below += (parseFloat(cs.paddingBottom) || 0) + (parseFloat(cs.borderBottomWidth) || 0) + (parseFloat(cs.marginBottom) || 0); }
    }
    return below;
  }
  function fit(host) {
    if (!host.isConnected || !host.offsetParent) { return; }
    if (window.innerWidth <= 900) { host.style.height = ''; return; }
    var r = host.getBoundingClientRect(), room = window.innerHeight - (r.top + window.pageYOffset) - after(host) - (parseFloat(getComputedStyle(host).marginBottom) || 0) - 4;
    host.style.height = Math.max(320, Math.floor(room)) + 'px';
  }
  function fitAll() { fitted = fitted.filter(function (h) { return h.isConnected; }); fitted.forEach(fit); }
  window.addEventListener('resize', fitAll);
  function fill(host) { if (fitted.indexOf(host) < 0) { fitted.push(host); } host.setAttribute('data-records-fill', ''); fit(host); setTimeout(function () { fit(host); }, 60); }
  function json(text, fallback) { try { return text ? JSON.parse(text) : fallback; } catch (e) { return fallback; } }

  /** A ULID (sortable change id): a retry of one change reuses it. */
  function ulid() {
    var A = '0123456789ABCDEFGHJKMNPQRSTVWXYZ', t = Date.now(), out = '', i;
    for (i = 0; i < 10; i++) { out = A.charAt(t % 32) + out; t = Math.floor(t / 32); }
    var r = new Uint8Array(16);
    (window.crypto || {}).getRandomValues ? window.crypto.getRandomValues(r) : r.forEach(function (x, j) { r[j] = Math.floor(Math.random() * 256); });
    for (i = 0; i < 16; i++) { out += A.charAt(r[i] % 32); }
    return out;
  }
  function b64url(text) { return btoa(unescape(encodeURIComponent(text))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }
  /** One live table of the Data Protocol as the grid's live mode reads it: api (rows, row, edit, window) and channel (hello, rows, resync, gone).
      Rows are {id, version, seq, doc}: the key's values, the row version column (none: -1, every change replaces), the row by column name. A patch's
      update carries the fields that changed: it is merged into the row held. */
  function rdpTable(name, query, key, version, live) {
    var base = drishti_url('/api/rdp'), held = new Map(), total = null;
    function call(method, path, body) {
      return fetch(base + path, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
        body: body ? JSON.stringify(body) : undefined }).then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) {
          if (!r.ok) { var er = j.error || j, e = new Error(er.message || er.detail || ('HTTP ' + r.status)); e.code = er.code; e.status = r.status; e.current = er.current; throw e; }
          return j;
        });
      });
    }
    function idOf(row) { return key.map(function (k) { return row[k] === null || row[k] === undefined ? '' : String(row[k]); }).join('|'); }
    function asRow(doc) { return { id: idOf(doc), version: version && doc[version] !== null && doc[version] !== undefined ? doc[version] : -1, seq: 0, doc: doc }; }
    function rowsOf(page) {
      var cols = (page.columns || []).map(function (c) { return typeof c === 'string' ? c : c.name; });
      return (page.values || []).map(function (v) { var d = {}; cols.forEach(function (c, i) { d[c] = v[i]; }); return d; });
    }
    function keyFilter(id) {
      var parts = String(id).split('|'), args = key.map(function (k, i) { return { op: '==', args: [{ col: k }, { lit: parts[i] }] }; });
      return args.length === 1 ? args[0] : { op: '&&', args: args };
    }
    var api = {
      rows: function (p) {
        var body = { limit: (p && p.limit) || 500 };
        if (query.filter) { body.filter = query.filter; }
        if (p && p.sort) { body.sort = [{ col: p.sort, dir: p.descending ? 'desc' : 'asc' }]; } else if (query.sort) { body.sort = query.sort; }
        if (p && p.offset) { body.offset = p.offset; }
        return call('POST', '/tables/' + encodeURIComponent(name) + '/query', body).then(function (page) {
          var docs = rowsOf(page);
          held.clear();
          docs.forEach(function (d) { held.set(idOf(d), d); });
          total = typeof page.total === 'number' ? page.total : docs.length;
          return { live: live, total: total, seq: (page.version || {}).seq || 0, rows: docs.map(asRow) };
        });
      },
      row: function (id) {
        return call('POST', '/tables/' + encodeURIComponent(name) + '/query', { filter: keyFilter(id), limit: 1 }).then(function (page) {
          var d = rowsOf(page)[0];
          if (!d) { var e = new Error('the record is no longer in this table'); e.status = 404; throw e; }
          var r = asRow(d);
          r.seq = (page.version || {}).seq || 0;
          return r;
        });
      },
      edit: function (b) {
        var set = {}, from = {};
        (b.items || []).forEach(function (i) { set[i.field] = i.to; from[i.field] = i.from; });
        var change = { id: b.id || ulid(), op: 'update', key: String(b.entity).split('|'), basedOn: b.basedOn, set: set, from: from };
        if (b.reason) { change.reason = b.reason; }
        return call('POST', '/tables/' + encodeURIComponent(name) + '/write', change).then(function (a) {
          return { changes: [{ id: a.change || change.id, state: a.state === 'held' ? 'awaiting' : a.state, message: a.message }] };
        });
      },
      window: function () { return Promise.resolve({}); }        // the stream sends the table's changes (no window): rows the grid does not hold
                                                                  // move its count and "N new", which reads the newest page again
    };
    function patch(h, p) {
      if (Array.isArray(p.reset)) { h.resync(); return; }
      var upserts = [], removes = [], fresh = true;
      (p.remove || []).forEach(function (k) { var id = (Array.isArray(k) ? k : [k]).map(function (x) { return x === null || x === undefined ? '' : String(x); }).join('|'); held.delete(id); removes.push(id); });
      (p.update || []).concat(p.add || []).forEach(function (r) { var id = idOf(r); if (held.has(id)) { fresh = false; } var d = Object.assign({}, held.get(id) || {}, r); held.set(id, d); upserts.push(asRow(d)); });
      if (typeof p.total === 'number') { total = p.total; }
      h.rows({ upserts: upserts, removes: removes, countOnly: !removes.length && fresh, top: (p.top || []).map(function (k) { return (Array.isArray(k) ? k : [k]).map(String).join('|'); }), total: total === null ? undefined : total });
    }
    var channel = {
      subscribe: function (h) {
        var sub = 'live:' + name + (query.filter ? '~' + b64url(JSON.stringify(query.filter)) : '');
        var off = window.Drishti.Channel.subscribe(sub, {
          msg: function (m) { patch(h, (m && m.data) || {}); },
          gap: function () { h.resync(); },
          gone: function (m) { h.gone(m); },
          error: function () { h.error(); }
        });
        setTimeout(function () { h.hello({ stream: name, live: live }); }, 0);   // the minimal stream has no hello of its own: subscribed is following
        return off;
      }
    };
    return { api: api, channel: channel };
  }

  /** Draws one table of records into host. o: {set, live, spec, rows, columns, title, offset, limit, layout, keyField, rowActions, small}. Answers the LiveSetGrid (or null). */
  function mount(host, o) {
    var grid = window.Drishti.elements && window.Drishti.elements.grid;
    if (!grid || !grid.LiveSetGrid) { host.textContent = 'The grid did not load: reload the page.'; return null; }
    styles();
    host.classList.add('rc-host');
    if (o.small) { host.classList.add('rc-small'); }
    var opts = { spec: o.spec || { title: o.title, columns: o.columns }, offset: o.offset || 0, snapshotRows: o.limit || undefined, layout: o.layout || 'wells', onChart: chart, keyField: o.keyField, rowActions: o.rowActions };
    if (o.set) {
      var spec = opts.spec, t = rdpTable(o.set, spec.query || {}, spec.key && spec.key.length ? spec.key : ['id'], spec.version || null, !!o.live);
      opts.api = Object.assign(grid.httpLiveApi({ id: o.set }), t.api);    // the change history and the tray are the write-back's (/api/changes)
      opts.channel = o.live && window.Drishti.Channel ? t.channel : NONE;
    } else {
      opts.rows = o.rows || [];
    }
    var live = new grid.LiveSetGrid(host, opts);
    host.setAttribute('data-records-mode', o.set ? 'engine' : 'static');
    live.start().then(function () {
      if (opts.channel === NONE) { live.setState('live'); }     // no stream will ever say hello: the snapshot is the answer (STATIC), never a lasting CONNECTING
      host.setAttribute('data-records-ready', 'true');
    },
      function (e) { host.textContent = (e && e.message) || 'The rows could not be read.'; });
    host.drishtiRecords = live;
    if (o.fill || host.hasAttribute('data-records-fill')) { fill(host); }
    return live;
  }

  /** A result as a host element, drawn on the next tick (when the caller has put it in the page): columns [{name, label?, type?, masked?}], rows as arrays
      in the columns' order (an answer of an SQL query, a preview, a BI answer). A masked column stays the server's mask. o: {title, small, columns: false}. */
  function table(columns, rows, o) {
    o = o || {};
    var host = document.createElement('div'), seen = {};
    var cols = (columns || []).map(function (c, i) {
      var name = typeof c === 'string' ? c : (c.label || c.name || ('Column ' + (i + 1)));
      var label = name, n = 2;
      while (seen[label]) { label = name + ' (' + n++ + ')'; }
      seen[label] = true;
      var type = typeof c === 'string' ? '' : c.type;
      return { field: 'c' + i, label: label, type: type === 'string' || type === 'text' ? 'text' : type === 'number' || type === 'decimal' || type === 'integer' || type === 'amount' ? 'number' : undefined };
    });
    var objs = (rows || []).map(function (r) { var x = {}; cols.forEach(function (c, i) { x[c.field] = r[i]; }); return x; });
    setTimeout(function () { mount(host, { rows: objs, spec: { title: o.title || '', columns: cols }, small: o.small !== false }); }, 0);
    return host;
  }

  function auto(root) {
    (root || document).querySelectorAll('[data-records]:not([data-records-mode])').forEach(function (host) {
      var set = host.getAttribute('data-live-set') || '', spec = json(host.getAttribute('data-spec'), null), hq = host.getAttribute('data-history') || '';
      // a pick list of a history command (TRD MX-2000 HIST 3M): each record's panel opens its history over the range asked
      var history = hq && spec && spec.kind ? [{ label: 'History', run: function (r) { window.location.href = drishti_url('/v/' + encodeURIComponent(spec.kind) + '/' + encodeURIComponent(r.__id) + '/history?' + hq); } }] : undefined;
      mount(host, { set: set, live: host.getAttribute('data-live') === 'on', spec: spec, rows: set ? null : json(host.getAttribute('data-rows'), []),
        offset: +host.getAttribute('data-drishti-offset') || 0, limit: +host.getAttribute('data-limit') || 0, title: host.getAttribute('data-title') || '', rowActions: history });
    });
  }
  window.Drishti.records = { mount: mount, auto: auto, table: table };
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', function () { auto(); }); } else { auto(); }
})();
