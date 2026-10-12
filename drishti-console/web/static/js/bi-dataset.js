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
/* Rupaka phase 2 (docs/architecture/RUPAKA.md sections 6 and 7): the datasets list, a model started from the packs, and one dataset's page: a minimal
   read-only Model view and a query preview grid. Same-origin JSON only (/bi/api/datasets/..., /bi/api/model/...), passed on to the server under the
   signed-in user's identity; the server applies the row rules and masks. Everything is built with DOM calls and textContent, never markup strings. */
(function () {
  'use strict';

  var canvas = document.getElementById('biCanvas');
  if (!canvas) { return; }
  var page = canvas.getAttribute('data-bi-page');
  var isDataset = canvas.hasAttribute('data-item') && canvas.getAttribute('data-item') !== '';
  if (page !== 'datasets') { return; }

  function h(tag, attrs) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { el.textContent = attrs[k]; } else if (k === 'class') { el.className = attrs[k]; } else if (attrs[k] === true) { el.setAttribute(k, ''); } else if (attrs[k] !== false && attrs[k] != null) { el.setAttribute(k, attrs[k]); }
    });
    (function add(list) {
      list.forEach(function (c) {
        if (c == null || c === false) { return; }
        if (Array.isArray(c)) { add(c); } else { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
      });
    })(Array.prototype.slice.call(arguments, 2));
    return el;
  }
  function q(sel, scope) { return (scope || canvas).querySelector(sel); }
  function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }
  function icon(name) { return h('i', { class: 'bi bi-' + name, 'aria-hidden': 'true' }); }
  function say(text, bad) {
    var m = q('[data-msg]');
    m.textContent = text || '';
    m.className = m.className.replace(/\s*bi-bad/, '') + (bad ? ' bi-bad' : '');
  }
  function api(method, path, body) {
    var opt = { method: method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    return fetch('/bi/api' + path, opt).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) { var e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText)); e.code = data.code; e.status = r.status; throw e; }
        return data;
      });
    });
  }
  function fail(e) { say(e.message, true); }
  function id2path(id) { return encodeURIComponent(id).replace(/%3A/g, ':'); }
  function badge(text, cls, ic) { return h('span', { class: 'bi-badge ' + (cls || '') }, ic ? icon(ic) : null, ' ' + text); }
  function num(v) { return typeof v === 'number' ? (Number.isInteger(v) ? v.toLocaleString('en-US') : v.toLocaleString('en-US', { maximumFractionDigits: 2 })) : String(v); }

  // -- the list -----------------------------------------------------------------------------------------------------------
  function list() {
    var box = q('[data-datasets]');
    api('GET', '/datasets').then(function (d) {
      clear(box);
      d.datasets.forEach(function (x) {
        box.appendChild(h('article', { class: 'bi-card', 'data-id': x.id, 'data-kind': 'dataset' },
          h('header', {}, h('span', { class: 'bi-kind' }, icon('hdd-stack'), ' dataset')),
          h('h3', {}, h('a', { href: '/bi/d/' + id2path(x.id), text: x.title })),
          x.description ? h('p', { class: 'bi-card-desc', text: x.description }) : null,
          h('p', { class: 'bi-card-badges' }, x.endorsement === 'promoted' ? badge('promoted', 'bi-promoted', 'bookmark-star') : x.endorsement === 'certified' ? badge('certified', 'bi-certified', 'patch-check') : null,
            x.sample ? badge('sample', '', 'mortarboard') : null, x.valid ? null : badge(x.errors + ' error' + (x.errors === 1 ? '' : 's'), 'bi-status', 'exclamation-triangle')),
          h('p', { class: 'bi-card-meta', text: x.tables + ' table' + (x.tables === 1 ? '' : 's') + ' · ' + x.relationships + ' relationship' + (x.relationships === 1 ? '' : 's') + ' · ' + x.measures + ' measures' })));
      });
      q('[data-empty]').hidden = d.datasets.length > 0;
    }).catch(fail);
    api('GET', '/me').then(function (me) {
      if (me.powers.indexOf('bi-author') >= 0) { q('[data-start]').hidden = false; startPanel(); }
    }).catch(fail);
  }

  function startPanel() {
    var kinds = q('[data-kinds]'), picked = {};
    api('GET', '/model/kinds').then(function (d) {
      d.tables.forEach(function (t) {
        var box = h('input', { type: 'checkbox', value: t.kind, 'data-kind': t.kind });
        box.addEventListener('change', function () { picked[t.kind] = box.checked; });
        kinds.appendChild(h('label', { class: 'bi-checkrow' }, box, ' ' + t.kind + ' ', h('small', { class: 'text-muted-d', text: t.pack + ' · ' + t.columns.length + ' columns' + (t.data ? '' : ' · no data now') })));
      });
    }).catch(fail);
    q('[data-start-run]').addEventListener('click', function () {
      var chosen = Object.keys(picked).filter(function (k) { return picked[k]; });
      if (!chosen.length) { say('Choose at least one kind.', true); return; }
      say('Reading the packs and the data...');
      api('POST', '/model/start', { kinds: chosen, name: chosen.join('-') }).then(function (d) {
        say('Draft ready: ' + d.tables.length + ' tables, ' + d.relationships.length + ' relationships from the packs, ' + d.proposals.length + ' proposed from the data.');
        q('[data-draft]').hidden = false;
        var tb = q('[data-draft-links] tbody'); clear(tb);
        d.relationships.forEach(function (l) { tb.appendChild(h('tr', {}, h('td', { class: 'mono', text: l.fromTable + '.' + l.fromColumn }), h('td', { class: 'mono', text: l.toTable + '.' + l.toColumn }), h('td', { text: l.cardinality }), h('td', { text: l.crossPack ? 'yes' : 'no' }))); });
        var pb = q('[data-draft-proposals] tbody'); clear(pb);
        d.proposals.forEach(function (l) {
          pb.appendChild(h('tr', { 'data-proposal': l.fromTable + '.' + l.fromColumn }, h('td', { class: 'mono', text: l.fromTable + '.' + l.fromColumn }), h('td', { class: 'mono', text: l.toTable + '.' + l.toColumn }),
            h('td', { text: Math.round(l.matchRate * 100) + '% (' + l.matched + ' of ' + l.sampled + ')' }), h('td', { text: l.cardinality }), h('td', { text: l.basis })));
        });
        q('[data-draft-none]').hidden = d.proposals.length > 0 || d.relationships.length > 0;
        var w = q('[data-draft-warnings]'); clear(w);
        d.warnings.forEach(function (x) { w.appendChild(h('li', { text: x })); });
        q('[data-draft-yaml]').textContent = d.yaml;
        var save = q('[data-draft-save]'); save.hidden = false;
        save.onclick = function () {
          api('POST', '/workspaces/me/items', { kind: 'dataset', title: 'New model from ' + chosen.join(', '), description: 'Started from the packs.', body: d.yaml }).then(function (it) {
            window.location.href = '/bi/d/' + id2path(it.id);
          }).catch(fail);
        };
      }).catch(fail);
    });
  }

  // -- one dataset --------------------------------------------------------------------------------------------------------
  function one() {
    var id = canvas.getAttribute('data-item'), model = null;
    var path = '/datasets/' + id2path(id);

    function draw(m) {
      model = m;
      document.title = m.title + ' · BI';
      q('[data-title]').textContent = m.title;
      q('[data-desc]').textContent = m.description || '';
      var badges = q('[data-badges]'); clear(badges);
      badges.appendChild(m.valid ? badge('valid', 'bi-promoted', 'check-circle') : badge('has errors', 'bi-status', 'exclamation-triangle'));
      if (m.sample) { badges.appendChild(document.createTextNode(' ')); badges.appendChild(badge('sample', '', 'mortarboard')); }
      var pr = q('[data-problems]'); clear(pr);
      m.problems.forEach(function (p) { pr.appendChild(h('li', {}, h('b', { class: 'mono', text: p.code + ' ' + (p.path || '') }), ' ' + p.message + (p.level === 'warning' ? ' (warning)' : ''))); });
      q('[data-problems-sec]').hidden = m.problems.length === 0;
      if (!m.tables) { return; }
      q('[data-meta]').textContent = 'dataset ' + m.name + ' · version ' + m.version + ' · engine ' + m.engine + ' · ' + m.tables.length + ' tables · ' + m.measures.length + ' measures';
      built(m); tables(m); relationships(m); measures(m); security(m); layers(m); refresh(m); refreshNow(); preview(m);
    }

    /** A composed or derived dataset (RUPAKA.md section 20.10): the datasets it asks as you and how the answers are joined, or the Python Transform that reads them. */
    function built(m) {
      var c = m.composition, sec = q('[data-built-sec]');
      sec.hidden = !c;
      if (!c) { return; }
      q('[data-built-note]').textContent = c.kind === 'composed'
        ? 'This dataset has no tables of its own. Each source below is asked as you, so its row rules and masks apply before the answers are joined into the table "joined".'
        : 'This dataset is made by the Python Transform ' + c.transform + ': each source below is asked as you, the answers are handed to the Transform as pandas frames, and the frame it returns is the table "result", kept for you until a source changes.';
      var tb = q('[data-built] tbody'); clear(tb);
      c.sources.forEach(function (s) {
        tb.appendChild(h('tr', { 'data-source': s.alias }, h('th', { scope: 'row', class: 'mono', text: s.alias }), h('td', {}, h('a', { href: '/bi/d/' + id2path(s.dataset), text: s.dataset })),
          h('td', { class: 'mono', text: (s.by.length ? s.by : s.fields).join(', ') }), h('td', { class: 'mono', text: s.measures.join(', ') }), h('td', { class: 'mono', text: s.filters.join('; ') })));
      });
      var js = q('[data-built-joins]'); clear(js);
      (c.joins || []).forEach(function (j) { js.appendChild(h('li', {}, h('code', { text: j.left + ' ' + j.type + ' join ' + j.right + ' on ' + j.on.join(', ') }))); });
      var run = q('[data-run-wrap]');
      run.hidden = c.kind !== 'derived';
      if (c.kind === 'derived') {
        api('GET', '/me').then(function (me) {
          if (me.powers.indexOf('bi-author') < 0) { run.hidden = true; return; }
          q('[data-run-transform]').onclick = function () {
            q('[data-run-result]').textContent = 'Running the Transform...';
            api('POST', path + '/transform/run', {}).then(function (r) {
              q('[data-run-result]').textContent = r.rows + ' rows, ' + r.columns.map(function (x) { return x.name + ' (' + x.type + ')'; }).join(', ') + (r.cached ? ' · already kept for you' : ' · ' + r.millis + ' ms');
              api('GET', path).then(draw);
            }).catch(function (e) { q('[data-run-result]').textContent = e.message; });
          };
        }).catch(function () { run.hidden = true; });
      }
    }

    function tables(m) {
      var box = q('[data-tables]'); clear(box);
      m.tables.forEach(function (t) {
        var rows = t.columns.map(function (c) {
          return h('tr', {}, h('th', { scope: 'row', class: 'mono', text: c.name }), h('td', { class: 'mono', text: c.path }), h('td', { text: c.type }),
            h('td', { text: [c.key ? 'key' : '', c.calculated ? 'calculated: ' + c.expr : ''].filter(Boolean).join(', ') }),
            h('td', {}, c.masked ? badge('masked for you', 'bi-status', 'lock') : null), h('td', { text: c.description || '' }));
        });
        box.appendChild(h('details', { class: 'bi-table', 'data-table': t.name, open: m.tables.length <= 2 },
          h('summary', {}, h('b', { class: 'mono', text: t.name }), ' · ' + t.kind + (t.each ? ' (one row per element of ' + t.each + ')' : '') + ' · connector ' + (t.connector || 'any') + ' · ' + t.mode + ' · ' + t.columns.length + ' columns',
            t.access === 'ok' ? null : badge('you may not open this kind', 'bi-status', 'slash-circle')),
          h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl', 'data-plain': true },
            h('caption', { class: 'visually-hidden', text: 'Columns of ' + t.name }),
            h('thead', {}, h('tr', {}, ['Column', 'Document path', 'Type', 'Notes', 'Access', 'Description'].map(function (x) { return h('th', { scope: 'col', text: x }); }))),
            h('tbody', {}, rows)))));
      });
    }

    function relationships(m) {
      var tb = q('[data-relationships] tbody'); clear(tb);
      m.relationships.forEach(function (r) {
        tb.appendChild(h('tr', {}, h('td', { class: 'mono', text: r.from }), h('td', { class: 'mono', text: r.to }), h('td', { text: r.cardinality }), h('td', { text: r.direction }), h('td', { text: r.active ? 'yes' : 'no' })));
      });
      q('[data-empty-rel]').hidden = m.relationships.length > 0;
    }

    function measures(m) {
      var tb = q('[data-measures-table] tbody'); clear(tb);
      m.measures.forEach(function (x) {
        tb.appendChild(h('tr', { 'data-measure': x.name }, h('th', { scope: 'row', text: x.name }), h('td', { class: 'mono', text: x.expr || ('python: ' + x.python + ' (stored, not run)') }),
          h('td', { text: (x.rollups || []).join(', ') }), h('td', { text: x.description || '' })));
      });
    }

    function security(m) {
      var s = m.security;
      q('[data-security-note]').textContent = s.rowRulesEnforced ? 'Row rules are evaluated on the server for each reader; a reader with no rule for their roles sees no rows.' : 'Server security is off: row rules are not applied.';
      var tb = q('[data-rules] tbody'); clear(tb);
      s.rows.forEach(function (r) { tb.appendChild(h('tr', {}, h('th', { scope: 'row', text: r.role }), h('td', { class: 'mono', text: r.where }), h('td', { text: r.appliesToYou ? 'yes' : 'no' }))); });
      var mk = q('[data-masked]');
      mk.textContent = s.masked.length ? 'Masked columns (shown as ••• without the raw power): ' + s.masked.join(', ') + '.' : 'No columns listed as masked (the server\'s redact list still applies).';
    }

    function layers(m) {
      var hs = q('[data-hierarchies]'); clear(hs);
      Object.keys(m.hierarchies || {}).forEach(function (k) { hs.appendChild(h('li', {}, h('b', { text: k }), ': ' + m.hierarchies[k].join(' > '))); });
      var tb = q('[data-layers] tbody'); clear(tb);
      (m.layers || []).forEach(function (t) {
        var rows = t.partitions.reduce(function (a, p) { return a + p.rows; }, 0);
        var where = t.partitions.map(function (p) { return p.date; }).join(', ') || 'none yet: built on the first query';
        if (t.live) {                                    // a live table is in memory, kept up to date by the feed (phase 5)
          rows = t.live.rows || 0;
          where = t.live.state === 'not started' ? 'in memory: not started yet (it starts with the first query or stream)'
            : 'in memory, ' + t.live.state + ': ' + num(t.live.subscribed) + ' of ' + num(t.live.entities) + ' entities push their changes' + (t.live.lastUpdate ? ', last change ' + new Date(t.live.lastUpdate).toTimeString().slice(0, 8) : '');
        }
        tb.appendChild(h('tr', {}, h('th', { scope: 'row', text: t.table }), h('td', { text: t.mode }), h('td', { text: where }), h('td', { text: num(rows) })));
      });
    }

    /** When the dataset refreshes by itself and how the last refresh went (the schedule, status, last and next run, rows, time and failures). */
    function refresh(m) {
      var r = m.refresh, tb = q('[data-refresh] tbody'), sec = q('[data-refresh-sec]'), note = q('[data-refresh-note]');
      clear(tb);
      sec.hidden = !r;
      if (!r) { return; }
      var when = function (x) { return x ? new Date(x).toLocaleString() : '–'; };
      var line = function (what, st) {
        var bad = st.status === 'failed' || st.status === 'retrying' || st.status === 'invalid';
        tb.appendChild(h('tr', { 'data-refresh-row': what, 'data-status': st.status || '' }, h('th', { scope: 'row', text: what }), h('td', { class: 'mono', text: st.schedule || '' }),
          h('td', {}, badge(st.status || 'waiting', bad ? 'bi-status' : 'bi-promoted', bad ? 'exclamation-triangle' : 'arrow-repeat'), st.error ? h('small', { class: 'text-muted-d', text: ' ' + st.error }) : null),
          h('td', { text: when(st.lastRun) }), h('td', { text: st.retryAt ? 'retry ' + when(st.retryAt) : st.schedule === 'after-load' || /^after-load/.test(st.schedule || '') ? 'at the next data load' : when(st.nextRun) }),
          h('td', { text: st.lastRows == null ? '–' : num(st.lastRows) }), h('td', { text: st.lastDurationMs == null ? '–' : num(st.lastDurationMs) + ' ms' }), h('td', { text: String(st.failures || 0) })));
      };
      if (r.dataset) { line('dataset', r.dataset); }
      (r.tables || []).forEach(function (t) { line('table ' + t.table, t); });
      var own = r.dataset || {};
      q('[data-refresh-when]').textContent = 'Last refresh: ' + when(own.lastRun) + ' · Next: ' + (own.retryAt ? 'retry ' + when(own.retryAt) : own.nextRun ? when(own.nextRun) : r.scheduled ? 'worked out at the next scheduler look' : 'no schedule');
      history(own.history || []);
      note.textContent = r.scheduled
        ? 'Layers are rebuilt on the schedule (' + r.zone + ' time, ' + r.calendar + ' calendar for business days); a failed run is tried again with the shared retry policy.'
        : 'No refresh schedule: the layers are rebuilt when a data load for a kind this dataset reads arrives, or when an author asks for a refresh (POST /datasets/{id}/refresh).';
      sec.querySelector('table').hidden = tb.children.length === 0;
    }

    /** The recent runs of the dataset's refresh, newest first (kept across a restart by the server). */
    function history(list) {
      var tb = q('[data-refresh-history] tbody'); clear(tb);
      list.forEach(function (x) {
        var bad = x.status !== 'ok';
        tb.appendChild(h('tr', { 'data-history-row': x.status }, h('th', { scope: 'row', text: new Date(x.at).toLocaleString() }), h('td', { text: x.trigger || '' }),
          h('td', {}, badge(x.status, bad ? 'bi-status' : 'bi-promoted', bad ? 'exclamation-triangle' : 'check-circle')), h('td', { text: x.date || '' }),
          h('td', { text: x.status === 'ok' ? num(x.rows) : '–' }), h('td', { text: x.status === 'ok' ? num(x.durationMs) + ' ms' : '–' }), h('td', { text: x.error || '' })));
      });
      q('[data-refresh-history]').hidden = list.length === 0;
      q('[data-refresh-history-head]').hidden = list.length === 0;
    }

    /** Refresh now (BI authors): the server refreshes in the background; this page asks for the state until it is no longer running. */
    function refreshNow() {
      var btn = q('[data-refresh-now]'), msg = q('[data-refresh-msg]');
      btn.hidden = true;
      api('GET', '/me').then(function (me) {
        if (me.powers.indexOf('bi-author') < 0) { return; }
        btn.hidden = false;
        btn.onclick = function () {
          btn.disabled = true; msg.textContent = 'Refreshing...';
          api('POST', path + '/refresh', {}).then(function () {
            var tries = 0;
            (function poll() {
              api('GET', path + '/refresh').then(function (st) {
                var d = st.dataset || {};
                if (d.status === 'running' && tries++ < 120) { setTimeout(poll, 1000); return; }
                btn.disabled = false;
                msg.textContent = d.status === 'ok' ? 'Refreshed ' + num(d.lastRows) + ' rows in ' + num(d.lastDurationMs) + ' ms.' : 'Refresh ' + (d.status || 'did not finish') + (d.error ? ': ' + d.error : '') + '.';
                api('GET', path).then(draw);
              }).catch(function (e) { btn.disabled = false; msg.textContent = e.message; });
            })();
          }).catch(function (e) { btn.disabled = false; msg.textContent = e.message; });
        };
      }).catch(function () {});
    }

    function preview(m) {
      var form = q('[data-preview]'), fields = q('[data-fields]'), meas = q('[data-measures]'), ff = q('[data-ffield]');
      clear(fields); clear(meas); clear(ff); ff.appendChild(h('option', { value: '', text: 'none' }));
      m.tables.forEach(function (t) {
        t.columns.forEach(function (c) {
          var name = t.name + '.' + c.name;
          fields.appendChild(h('option', { value: name, text: name + (c.masked ? ' (masked)' : '') }));
          ff.appendChild(h('option', { value: name, text: name }));
          if (c.type === 'date' || c.type === 'timestamp') {
            ['year', 'quarter', 'month'].forEach(function (p) { fields.appendChild(h('option', { value: name + '.' + p, text: name + '.' + p })); });
          }
        });
      });
      m.measures.forEach(function (x) { if (x.expr) { meas.appendChild(h('option', { value: x.name, text: x.name })); } });
      form.onsubmit = function (e) {
        e.preventDefault();
        var body = { fields: sel(fields), measures: sel(meas), filters: [], limit: parseInt(form.elements.limit.value, 10) || 100 };
        if (!body.fields.length && !body.measures.length) { say('Choose at least one field or measure.', true); return; }
        if (form.elements.ffield.value && form.elements.fvalue.value !== '') {
          var op = form.elements.fop.value, v = form.elements.fvalue.value;
          var values = op === 'in' ? v.split(',').map(function (x) { return x.trim(); }) : [isNaN(Number(v)) ? v : Number(v)];
          body.filters.push({ field: form.elements.ffield.value, op: op, values: values });
        }
        if (form.elements.from.value) { body.dates = { from: form.elements.from.value }; if (form.elements.to.value) { body.dates.to = form.elements.to.value; } }
        say('Running...');
        api('POST', path + '/query' + (form.elements.explain.checked ? '?explain=true' : ''), body).then(grid).catch(function (e) { say(e.message, true); });
      };
    }

    function sel(select) { return Array.prototype.slice.call(select.selectedOptions).map(function (o) { return o.value; }); }

    function grid(a) {
      var head = q('[data-grid] thead'), body = q('[data-grid] tbody'); clear(head); clear(body);
      head.appendChild(h('tr', {}, a.columns.map(function (c) { return h('th', { scope: 'col', class: c.type === 'string' ? '' : 'num' }, c.name, c.masked ? [' ', icon('lock')] : null); })));
      a.rows.forEach(function (r) {
        body.appendChild(h('tr', {}, r.map(function (v, i) { return h('td', { class: a.columns[i].type === 'string' ? '' : 'num', text: v === null ? '' : num(v) }); })));
      });
      q('[data-grid-wrap]').hidden = false;
      var m = a.meta;
      say('');
      q('[data-result-meta]').textContent = a.rowCount + ' row' + (a.rowCount === 1 ? '' : 's') + ' · answered from the ' + m.layer + ' layer' + (m.rollup ? ' (rollup ' + m.rollup + ')' : '') + ' · ' + m.millis + ' ms'
        + (m.cached ? ' · from the cache' : '') + (m.dates.length ? ' · dates ' + m.dates.join(', ') : '') + (m.masked.length ? ' · masked: ' + m.masked.join(', ') : '');
      var w = q('[data-result-warnings]'); clear(w);
      m.warnings.forEach(function (x) { w.appendChild(h('li', { text: x })); });
      var sql = q('[data-sql]');
      sql.hidden = !m.sql; sql.textContent = m.sql || '';
    }

    api('GET', path).then(draw).catch(function (e) { say(e.message, true); q('[data-title]').textContent = 'Not found'; });
    var mv = q('[data-model-view]');
    if (mv) {
      mv.href = '/bi/m/' + id2path(id);
      api('GET', '/me').then(function (me) { mv.hidden = me.powers.indexOf('bi-author') < 0; }).catch(function () {});
    }
  }

  if (isDataset) { one(); } else { list(); }
})();
