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
/* Wave B, what every consumer of a model shares (docs/architecture/BI_DESIGN.md sections 4.5 to 4.7, console tasks 3 to 5; docs/guides/BI_GUIDE.md section 25):

     B.consume.timePicker(root, onChange)   now (live), as of a date, or a window: the three times one plan answers in. A past as-of date never changes, so it is
                                            asked once; now and a window are followed.
     B.consume.paramBar(host, described, onChange)  the model's parameters as slicers (a what-if number as a slider, a field parameter as a choice of fields, text,
                                            dates, yes or no) and its calculation groups as "Show as"; the values ride on the query as MQ v2 `params` and `calc`.
     B.consume.follow(mq, o)                a standing query (POST /mq/subscribe) whose answers the server PUSHES over the console's one channel (GET /mq/stream as
                                            `mq:<id>`): nothing polls. o.onAnswer gets {rows | diff | value, cursor, mode}; o.onState the state.
     B.consume.chip(frame)                  the state chip under a visual: Live, Throttled, Every few seconds (tick), Stale, Not updating, or As of a date. Words and an
                                            icon, never colour alone.
     B.consume.explain(frame, mq)           the Explain panel under a visual: the plan's tiers (source, Java, console), each scan's layer, the timings and the plan as
                                            text, from the server's options.explain (masked predicates and other readers' rules are never shown).

   The browser computes nothing here: it asks, follows and draws. Everything is built with DOM calls and textContent. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const C = (B.consume = B.consume || {});
  const h = (...a) => B.h(...a);
  const today = () => new Date().toISOString().slice(0, 10);

  // ---- time --------------------------------------------------------------------------------------------------------------
  /** The time picker inside `root` ([data-time-mode], [data-date] for as of, [data-date-from] and [data-date-to] for a window). */
  C.timePicker = function (root, onChange) {
    const mode = B.q('[data-time-mode]', root), at = B.q('[data-date]', root), from = B.q('[data-date-from]', root), to = B.q('[data-date-to]', root);
    const value = () => {
      const m = mode ? mode.value : (at && at.value ? 'asof' : 'now');
      if (m === 'asof' && at && at.value) { return { mode: 'asof', asOf: at.value }; }
      if (m === 'window' && from && to && from.value && to.value) { return { mode: 'window', from: from.value <= to.value ? from.value : to.value, to: from.value <= to.value ? to.value : from.value }; }
      return { mode: 'now' };
    };
    let last = JSON.stringify(value());
    const fire = () => { const v = value(), s = JSON.stringify(v); if (s !== last) { last = s; root.setAttribute('data-time', v.mode); onChange(v); } };
    const show = () => {
      const m = mode ? mode.value : 'asof';
      if (at) { at.hidden = !!mode && m !== 'asof'; }
      if (from) { from.hidden = m !== 'window'; }
      if (to) { to.hidden = m !== 'window'; }
    };
    if (mode) {
      mode.addEventListener('change', () => {
        if (mode.value === 'asof' && at && !at.value) { at.value = today(); }
        if (mode.value === 'window' && from && to && !from.value) { const d = new Date(); to.value = today(); d.setDate(d.getDate() - 7); from.value = d.toISOString().slice(0, 10); }
        show();
        fire();
      });
    }
    [at, from, to].forEach((x) => { if (x) { x.addEventListener('change', fire); } });
    show();
    root.setAttribute('data-time', value().mode);
    return {
      value,
      reset() { if (mode) { mode.value = 'now'; } [at, from, to].forEach((x) => { if (x && x !== at) { x.value = ''; } }); show(); last = JSON.stringify(value()); root.setAttribute('data-time', value().mode); }
    };
  };
  /** The report state's dates for a time: as of D is the one day D; a window its two ends; now none (the latest the data holds). */
  C.datesOf = (t) => (t && t.mode === 'asof' ? { from: t.asOf, to: t.asOf } : t && t.mode === 'window' ? { from: t.from, to: t.to } : null);
  /** Whether a time can be followed: a past day never changes (the server refuses DRS-9183), now and a window can. */
  C.followable = (t) => !t || t.mode !== 'asof' || t.asOf >= today();

  // ---- parameters and calculation groups -------------------------------------------------------------------------------------
  /**
   * The parameter slicers and Show as controls of a model, in `host`. `described` is the Fields shape (parameters, calcGroups) or the server's describe.
   * @returns {params() -> {name: value} of the values that differ from the defaults, calc() -> {Group: Item}, active() -> bool, reset()}
   */
  C.paramBar = function (host, described, onChange) {
    const params = (described && described.parameters) || [];
    const groups = (described && described.calcGroups) || [];
    const vals = {}, calc = {};
    B.clear(host);
    if (!params.length && !groups.length) { host.hidden = true; return { params: () => ({}), calc: () => ({}), active: () => false, reset() {} }; }
    host.hidden = false;
    host.appendChild(h('span', { class: 'bi-param-word', text: 'Parameters' }));
    const changed = () => onChange && onChange();
    params.forEach((p) => {
      const id = 'bp-' + p.name.replace(/[^A-Za-z0-9_]/g, '_');
      const title = p.title || p.name;
      let ctl;
      const dflt = p.default;
      if (p.kind === 'field') {
        ctl = h('select', { class: 'studio-select', id, 'data-param': p.name }, (p.fields || []).map((f) => h('option', { value: f, text: B.label ? B.label(f) : f, selected: f === dflt })));
        ctl.addEventListener('change', () => { if (ctl.value === dflt) { delete vals[p.name]; } else { vals[p.name] = ctl.value; } changed(); });
      } else if (p.kind === 'boolean') {
        ctl = h('select', { class: 'studio-select', id, 'data-param': p.name }, h('option', { value: 'false', text: 'no', selected: !dflt }), h('option', { value: 'true', text: 'yes', selected: !!dflt }));
        ctl.addEventListener('change', () => { const v = ctl.value === 'true'; if (v === !!dflt) { delete vals[p.name]; } else { vals[p.name] = v; } changed(); });
      } else if ((p.kind === 'number' || p.kind === 'integer') && p.min != null && p.max != null) {
        const out = h('output', { class: 'bi-param-out', for: id, 'data-param-out': p.name, text: String(dflt) });
        const range = h('input', { type: 'range', class: 'bi-param-range', id, 'data-param': p.name, min: p.min, max: p.max, step: p.step || (p.kind === 'integer' ? 1 : 'any'), value: dflt });
        range.addEventListener('input', () => { out.textContent = range.value; });
        range.addEventListener('change', () => { const v = Number(range.value); if (v === Number(dflt)) { delete vals[p.name]; } else { vals[p.name] = p.kind === 'integer' ? Math.round(v) : v; } changed(); });
        ctl = h('span', { class: 'bi-param-slider' }, range, out);
      } else if (p.values && p.values.length) {
        ctl = h('select', { class: 'studio-select', id, 'data-param': p.name }, p.values.map((v) => h('option', { value: String(v), text: String(v), selected: String(v) === String(dflt) })));
        ctl.addEventListener('change', () => { if (ctl.value === String(dflt)) { delete vals[p.name]; } else { vals[p.name] = typeof dflt === 'number' ? Number(ctl.value) : ctl.value; } changed(); });
      } else {
        const type = p.kind === 'date' ? 'date' : p.kind === 'text' ? 'text' : 'number';
        ctl = h('input', { class: 'studio-in bi-param-in', type, id, 'data-param': p.name, value: dflt == null ? '' : dflt, step: p.step || 'any' });
        ctl.addEventListener('change', () => {
          const v = type === 'number' ? Number(ctl.value) : ctl.value;
          if (ctl.value === '' || String(v) === String(dflt)) { delete vals[p.name]; } else { vals[p.name] = p.kind === 'integer' ? Math.round(v) : v; }
          changed();
        });
      }
      host.appendChild(h('label', { class: 'bi-tool bi-param', for: id, title: p.description || '' }, title + ' ', ctl));
    });
    groups.forEach((g) => {
      const items = (g.items || []).map((i) => (typeof i === 'string' ? i : i.name)).filter(Boolean);
      const id = 'bcg-' + g.name.replace(/[^A-Za-z0-9_]/g, '_');
      const ctl = h('select', { class: 'studio-select', id, 'data-calc-group': g.name }, h('option', { value: '', text: 'as it is' }), items.map((i) => h('option', { value: i, text: i })));
      ctl.addEventListener('change', () => { if (ctl.value) { calc[g.name] = ctl.value; } else { delete calc[g.name]; } changed(); });
      host.appendChild(h('label', { class: 'bi-tool bi-param', for: id, title: g.description || '' }, 'Show as (' + g.name + ') ', ctl));
    });
    return {
      params: () => Object.assign({}, vals),
      calc: () => Object.assign({}, calc),
      active: () => Object.keys(vals).length + Object.keys(calc).length > 0,
      reset() { Object.keys(vals).forEach((k) => delete vals[k]); Object.keys(calc).forEach((k) => delete calc[k]); B.qa('[data-param], [data-calc-group]', host).forEach((c) => { c.value = c.defaultValue !== undefined && c.tagName === 'INPUT' ? c.defaultValue : (B.q('option[selected]', c) || {}).value || ''; }); }
    };
  };
  /** A model query with the parameter values and calculation items on it (MQ v2); the query unchanged when there are none. */
  C.withParams = function (mq, params, calc) {
    const hasP = params && Object.keys(params).length, hasC = calc && Object.keys(calc).length;
    if (!mq || (!hasP && !hasC)) { return mq; }
    const q = JSON.parse(JSON.stringify(mq));
    q.mq = 2;
    if (hasP) { q.params = Object.assign({}, q.params || {}, params); }
    if (hasC) { q.calc = Object.assign({}, q.calc || {}, calc); }
    return q;
  };
  /** A query asked at a time: `time:` as the model query says it (the report's own dates otherwise). */
  C.atTime = function (mq, t) {
    if (!mq || !t) { return mq; }
    const q = JSON.parse(JSON.stringify(mq));
    if (t.mode === 'asof') { q.time = { 'as-of': t.asOf }; } else if (t.mode === 'window') { q.time = { window: { from: t.from, to: t.to } }; } else if (q.time && q.time['as-of']) { delete q.time; }
    return q;
  };

  // ---- standing queries ------------------------------------------------------------------------------------------------------
  // keepalive: a DELETE sent while the tab closes (pagehide) still reaches the server, so a closed tab never holds standing queries
  const drop = (id) => fetch('/bi/api' + B.viewPath('/mq/subscribe/' + encodeURIComponent(id)), { method: 'DELETE', credentials: 'same-origin', keepalive: true, headers: { Accept: 'application/json' } }).catch(() => {});
  /** A refusal for want of room (DRS-9181 the server's, DRS-9182 yours): said as such, never a bare "Not updating". */
  const FULL = { 'DRS-9181': 'The server follows as many live queries as it may; this visual was asked once. It updates again when others close.',
    'DRS-9182': 'You follow too many live queries (other tabs or pages); close a tab, or wait a minute for closed ones to be freed, then reload.' };
  /** How long after its last change a followed visual still says Live; past it, Listening (the stream is up, the data has not moved). */
  const LIVE_FOR = 60000;
  /**
   * Follows a model query: the server pushes its answers. o: {throttle ('1s'), deliver ('rows' | 'diff' | 'value'), staleSeconds (30), onAnswer(d), onState(state, why, info)}.
   * States: connecting, live, throttled, tick, stale, failed. @returns {close(), id()}
   */
  C.follow = function (mq, o) {
    let sq = null, off = null, closed = false, heard = Date.now(), state = 'connecting', changed = 0;
    const set = (s, why, info) => { state = s; if (o.onState) { o.onState(s, why || '', info || {}); } };
    const stale = (o.staleSeconds || 30) * 1000;
    const timer = window.setInterval(() => {
      if (!closed && sq && (state === 'live' || state === 'quiet' || state === 'throttled' || state === 'tick') && Date.now() - heard > stale) { set('stale', 'nothing heard for ' + Math.round((Date.now() - heard) / 1000) + ' s'); }
      else if (!closed && sq && state === 'live' && Date.now() - changed > LIVE_FOR) { set('quiet', 'Following: no change for ' + Math.round((Date.now() - changed) / 1000) + ' s'); }
    }, 1000);
    B.api('POST', '/mq/subscribe', { query: mq, throttle: o.throttle || '1s', deliver: o.deliver || 'rows' }).then((r) => {
      if (closed) { drop(r.subscription); return; }
      sq = r.subscription;
      heard = Date.now();
      set(r.mode === 'tick' ? 'tick' : 'quiet', '', r);                 // Live only once changes flow: subscribed and waiting is Listening
      if (!window.DrishtiChannel) { set('failed', 'the console\'s live channel did not load'); return; }
      off = window.DrishtiChannel.subscribe('mq:' + sq, {
        answer(d) { heard = changed = Date.now(); if (state !== 'throttled') { set(d && d.mode === 'tick' ? 'tick' : 'live', '', d); } if (o.onAnswer) { o.onAnswer(d || {}); } },
        status(d) { heard = Date.now(); d = d || {}; set(d.state === 'failed' ? 'failed' : d.state === 'throttled' ? 'throttled' : d.state === 'tick' || d.mode === 'tick' ? 'tick' : Date.now() - changed < LIVE_FOR ? 'live' : 'quiet', d.error || d.reason || '', d); },
        gone(d) { set('failed', (d && d.detail) || 'the stream ended'); },
        error() { set('stale', 'the connection to the console dropped; trying again'); }
      });
    }, (e) => (FULL[e.code] ? set('full', FULL[e.code], { code: e.code }) : set('failed', e.detail || e.message, { code: e.code })));
    return { close() { closed = true; window.clearInterval(timer); if (off) { off(); } if (sq) { drop(sq); } }, id: () => sq, state: () => state };
  };

  // ---- the state chip --------------------------------------------------------------------------------------------------------
  const WORDS = { connecting: 'Connecting', live: 'Live', quiet: 'Listening', throttled: 'Throttled', tick: 'Every few seconds', stale: 'Stale', failed: 'Not updating', full: 'Too many live queries: close a tab', asof: 'As of', window: 'Window', once: 'Asked once' };
  const ICONS = { connecting: 'hourglass-split', live: 'broadcast', quiet: 'pause-circle', throttled: 'speedometer2', tick: 'clock-history', stale: 'exclamation-triangle', failed: 'x-octagon', full: 'stack', asof: 'calendar-event', window: 'calendar-range', once: 'check2' };
  /** The chip under a visual (in its frame's foot); `set(state, why, info)`. */
  C.chip = function (frame) {
    let el = frame.foot.querySelector('.bi-state-chip');
    if (!el) { el = h('span', { class: 'bi-state-chip', role: 'status', 'data-state': 'connecting' }); frame.foot.appendChild(el); }
    return {
      el,
      set(state, why, info) {
        if (!el.isConnected) { frame.foot.appendChild(el); }
        const text = state === 'asof' && info && info.asOf ? 'As of ' + info.asOf : state === 'window' && info && info.from ? info.from + ' to ' + info.to : WORDS[state] || state;
        B.clear(el).append(B.icon(ICONS[state] || 'dot'), ' ' + text + (state === 'live' && info && info.lagMillis != null ? ' · ' + info.lagMillis + ' ms' : ''));
        el.className = 'bi-state-chip bi-state-' + state;
        el.setAttribute('data-state', state);
        el.title = why || (state === 'quiet' ? 'Following: the server pushes each change; none has come yet, so the picture is up to date' : state === 'live' ? 'The server pushes each change' + (info && info.mode ? ' (' + info.mode + ')' : '') : state === 'tick' ? 'Worked out again every few seconds (windows, time intelligence, Python, or a busy model)' : '');
        if (info && info.mode) { el.setAttribute('data-mode', info.mode); }
      }
    };
  };

  // ---- explain -----------------------------------------------------------------------------------------------------------------
  const TIER = { source: 'the data store', java: 'the server (Java)', console: 'the console (Python)' };
  function nodeRows(n, depth, out) {
    if (!n) { return out; }
    out.push(h('tr', { 'data-explain-op': n.op || '' }, h('td', { class: 'mono', text: ' '.repeat(depth * 2) + (n.op || '') + (n.table ? ' ' + n.table : n.alias ? ' ' + n.alias : '') }),
      h('td', { text: n.tier ? TIER[n.tier] || n.tier : '' }), h('td', { text: n.layer || n.rollup || '' }),
      h('td', { text: n.rules ? n.rules + ' rule' + (n.rules > 1 ? 's' : '') + ' applied' : n.denied ? 'no rows for you' : '' })));
    (n.inputs || []).forEach((c) => nodeRows(c, depth + 1, out));
    return out;
  }
  /** Shows (or hides, a second time) the Explain panel of a visual: its query asked again with options.explain. */
  C.explain = function (frame, mq) {
    const open = frame.el.querySelector(':scope > .bi-plan-panel');
    if (open) { open.remove(); return Promise.resolve(null); }
    const panel = h('section', { class: 'bi-plan-panel', 'data-explain': 'loading', 'aria-label': 'How this visual was answered' }, h('p', { class: 'text-muted-d', text: 'Asking the server how it answers this…' }));
    frame.el.appendChild(panel);
    if (!mq) { B.clear(panel).appendChild(h('p', { class: 'text-muted-d', text: 'This visual has no model query to explain.' })); panel.setAttribute('data-explain', 'none'); return Promise.resolve(null); }
    const q = JSON.parse(JSON.stringify(mq));
    q.options = Object.assign({}, q.options || {}, { explain: true });
    return B.mq.ask(q).then((a) => {
      const m = a.meta || {};
      B.clear(panel);
      panel.setAttribute('data-explain', 'ready');
      const tiers = m.tiers || {};
      const t = m.timings || {};
      const close = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-explain-close': '', text: 'Close' });
      close.addEventListener('click', () => panel.remove());
      panel.append(h('div', { class: 'bi-plan-head' }, h('h3', { text: 'How this was answered' }), close),
        h('p', { class: 'bi-explain-tiers', 'data-explain-tiers': Object.keys(tiers).join(' ') }, Object.keys(tiers).map((k) => h('span', { class: 'bi-chip', 'data-tier': k }, B.icon(k === 'console' ? 'filetype-py' : k === 'source' ? 'database' : 'cpu'), ' ' + (TIER[k] || k) + ': ' + tiers[k] + ' step' + (tiers[k] > 1 ? 's' : '')))),
        h('p', { class: 'bi-explain-times', 'data-explain-times': '' }, [
          'Plan ' + String(m.plan || '').slice(0, 12), m.cached ? 'from the cache' : 'worked out', t.layer ? 'layer ' + t.layer : '', t.engineMillis != null ? 'engine ' + t.engineMillis + ' ms' : '',
          t.pythonMillis ? 'Python ' + t.pythonMillis + ' ms' : '', m.millis != null ? 'in all ' + m.millis + ' ms' : '', a.rowCount != null ? a.rowCount + ' rows' : ''].filter(Boolean).join(' · ')),
        m.explain ? h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl bi-explain-tree', 'data-plain': '' }, h('caption', { class: 'visually-hidden', text: 'The plan' }),
          h('thead', {}, h('tr', {}, ['Step', 'Runs on', 'Layer', 'Security'].map((x) => h('th', { scope: 'col', text: x })))), h('tbody', {}, nodeRows(m.explain, 0, [])))) : null,
        m.explainText ? h('details', {}, h('summary', { text: 'The plan as text' }), h('pre', { class: 'bi-explain-text', 'data-explain-text': '', text: m.explainText })) : null,
        (m.warnings || []).length ? h('p', { class: 'text-muted-d', text: 'Warnings: ' + m.warnings.join('; ') }) : null);
      return a;
    }, (e) => { B.clear(panel).appendChild(h('p', { class: 'bi-msg bi-bad', role: 'alert', text: (e.code ? e.code + ' ' : '') + (e.detail || e.message) })); panel.setAttribute('data-explain', 'failed'); return null; });
  };
})();
