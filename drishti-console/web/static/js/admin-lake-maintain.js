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
/* Admin → Lakes → Maintain: pick tables (all, by domain, one by one), dry run or maintain now with progress per table and cancel, settings per
   table, schedules (a day-and-time picker that writes the cron text, or after every build) and the history. Everything goes through
   /admin/api/lakes/maintain/... (routes/lake_maint_routes.py); the engine is tools/lake/maintain.py, the same as `drishti.py lake maintain`. */
(function () {
  'use strict';
  var box = document.querySelector('[data-maint]');
  if (!box || !box.querySelector('[data-mt-run]')) { return; }
  var API = '/admin/api/lakes/maintain';
  var $ = function (sel) { return box.querySelector(sel); };
  var $$ = function (sel) { return Array.prototype.slice.call(box.querySelectorAll(sel)); };
  var msg = $('[data-mt-msg]');
  var state = { overview: null, run: null, timer: null, dirty: {}, cronEdited: false };
  var DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('t-bad', !!bad); }
  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }
  function api(method, url, body) {
    return fetch(url, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json', 'Accept': 'application/json' } : { 'Accept': 'application/json' },
      body: body ? JSON.stringify(body) : undefined }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) { throw new Error((j && j.detail) || ('the console said ' + r.status)); }
        return j;
      });
    });
  }
  function fmt(n) { return n === null || n === undefined ? '–' : Number(n).toLocaleString(); }
  function mb(n) { return n === null || n === undefined ? '–' : (Number(n) < 1 ? (Number(n) * 1024).toFixed(1) + ' KB' : Number(n).toFixed(2) + ' MB'); }

  // the selection ------------------------------------------------------------------------------------------------
  function picks() { return $$('[data-mt-pick]'); }
  function selection() {
    var out = {};
    picks().forEach(function (c) { if (c.checked) { (out[c.getAttribute('data-root')] = out[c.getAttribute('data-root')] || []).push(c.value); } });
    return out;
  }
  function count(sel) { return Object.keys(sel).reduce(function (n, r) { return n + sel[r].length; }, 0); }
  function settingsOf(root, table) {
    var t = ((state.overview || {}).tables || []).filter(function (x) { return x.root === root && x.table === table; })[0];
    return t ? t.settings : null;
  }
  var cliTimer = null;
  function selectionChanged() {
    var sel = selection(), n = count(sel), running = !!(state.run && state.run.state === 'running');
    $('[data-mt-count]').textContent = n ? n + ' table' + (n === 1 ? '' : 's') + ' selected' : 'No table selected';
    ['[data-mt-dry]', '[data-mt-run]', '[data-mt-save]'].forEach(function (s) { $(s).disabled = !n || (running && s !== '[data-mt-save]'); });
    $('[data-mt-add]').disabled = !n || Object.keys(sel).length !== 1;
    var all = picks();
    $('[data-mt-all]').checked = all.length > 0 && all.every(function (c) { return c.checked; });
    $('[data-mt-all]').indeterminate = n > 0 && n < all.length;
    if (n === 1) { fillSettings(settingsOf(Object.keys(sel)[0], sel[Object.keys(sel)[0]][0])); }
    clearTimeout(cliTimer);
    cliTimer = setTimeout(function () {
      if (!n) { return; }
      api('POST', API + '/command', { tables: sel }).then(function (j) { $('[data-mt-cli]').textContent = j.command; }).catch(function () {});
    }, 150);
  }
  box.addEventListener('change', function (ev) { if (ev.target.matches('[data-mt-pick]')) { selectionChanged(); } });
  $('[data-mt-all]').addEventListener('change', function (ev) {
    picks().forEach(function (c) { c.checked = ev.target.checked; });
    selectionChanged();
  });
  $('[data-mt-domain]').addEventListener('change', function (ev) {
    var d = ev.target.value;
    if (!d) { return; }
    picks().forEach(function (c) { c.checked = c.getAttribute('data-domain') === d; });
    selectionChanged();
  });
  $('[data-mt-copy]').addEventListener('click', function () {
    var text = $('[data-mt-cli]').textContent;
    if (navigator.clipboard) { navigator.clipboard.writeText(text).then(function () { say('Command copied.'); }, function () { say('Select the command and copy it.'); }); }
  });

  // runs ---------------------------------------------------------------------------------------------------------
  function start(dry) {
    var sel = selection();
    if (!count(sel)) { return; }
    say('');
    api('POST', API + '/runs', { tables: sel, dryRun: dry }).then(function (run) {
      state.run = run;
      $('[data-mt-runbox]').hidden = false;
      $('[data-mt-run-title]').textContent = dry ? 'Dry run: what maintenance would do (nothing is changed)' : 'Maintenance';
      $('[data-mt-result] tbody').textContent = '';
      selectionChanged();
      poll();
    }).catch(function (e) { say(e.message, true); });
  }
  $('[data-mt-dry]').addEventListener('click', function () { start(true); });
  $('[data-mt-run]').addEventListener('click', function () { start(false); });
  $('[data-mt-cancel]').addEventListener('click', function () {
    if (!state.run) { return; }
    api('POST', API + '/runs/' + state.run.id + '/cancel', {}).then(function (r) { show(r); }).catch(function (e) { say(e.message, true); });
  });
  function poll() {
    clearTimeout(state.timer);
    api('GET', API + '/runs/' + state.run.id).then(function (r) {
      show(r);
      if (r.state === 'running') { state.timer = setTimeout(poll, 700); return; }
      refresh();
    }).catch(function (e) { say(e.message, true); });
  }
  function show(r) {
    state.run = r;
    var bar = $('[data-mt-bar]'), text = $('[data-mt-run-text]');
    bar.value = r.total ? Math.round(100 * r.done / r.total) : 0;
    if (r.state === 'running') {
      text.textContent = (r.cancelling ? 'Stopping after this table… ' : '') + r.done + ' of ' + r.total + ' table(s) done' + (r.current ? ', now ' + r.current : '');
    } else {
      bar.value = 100;
      var o = (r.entry || {}).outcome || r.state;
      text.textContent = r.error ? 'Failed: ' + r.error : (r.dryRun ? 'Dry run finished' : 'Finished') + ': ' + o + (r.state === 'cancelled' ? ' (cancelled: the tables not reached were left as they were)' : '');
      text.classList.toggle('t-bad', !!r.error || o === 'failed');
    }
    $('[data-mt-cancel]').hidden = r.state !== 'running';
    var body = $('[data-mt-result] tbody');
    body.textContent = '';
    (r.results || []).forEach(function (t) { body.appendChild(resultRow(t)); });
  }
  function resultRow(t) {
    var tr = el('tr');
    var th = el('th', { scope: 'row' });
    th.appendChild(el('span', { 'class': 'mono' }, t.name));
    tr.appendChild(th);
    var b = t.before || {}, a = t.estimate || t.after || {};
    var est = t.estimate ? ' (estimate)' : '';
    if (t.skipped && !t.error) {
      var td = el('td', { colspan: '5', 'class': 'text-muted-d' }, 'Skipped: ' + t.skipped);
      tr.appendChild(td);
      return tr;
    }
    tr.appendChild(el('td', { 'class': 'mono' }, t.error && !t.before ? '–' : fmt(b.files) + ' → ' + fmt(a.files) + est));
    tr.appendChild(el('td', { 'class': 'mono' }, t.error && !t.before ? '–' : mb(b.mb) + ' → ' + mb(a.mb) + est));
    var ret = t.retention;
    tr.appendChild(el('td', {}, !ret ? 'keeps every date' : (ret.dates && ret.dates.length ? (t.dryRun ? 'would drop ' : 'dropped ') + ret.dates.length + ' date(s): ' +
      ret.dates.slice(0, 3).join(', ') + (ret.dates.length > 3 ? ' …' : '') : 'nothing before ' + ret.cutoff)));
    var v = t.vacuum;
    tr.appendChild(el('td', {}, !v ? 'no vacuum' : (v.dry_run ? 'would delete ' : 'deleted ') + fmt(v.files) + ' file(s)' + (v.mb ? ', ' + mb(v.mb) : '') + ' unused for ' + v.hours + ' h'));
    var sig = (t.signal || [])[0];
    tr.appendChild(el('td', { 'class': t.error ? 't-bad' : '' }, t.error ? t.error : (t.dryRun ? 'would run (nothing changed)' : t.forced ? 'done (forced)' : 'done') +
      (sig ? (sig.ok ? '; readers told (' + sig.date + ')' : '; signal failed: ' + sig.error) : '')));
    return tr;
  }

  // settings -----------------------------------------------------------------------------------------------------
  function fillSettings(s) {
    if (!s) { return; }
    state.dirty = {};
    $('[data-mt-set="keep-business-days"]').value = s['keep-business-days'] === null ? '' : s['keep-business-days'];
    $('[data-mt-set="target-file-mb"]').value = s['target-file-mb'];
    $('[data-mt-set="vacuum-hours"]').value = s['vacuum-hours'] === null ? '' : s['vacuum-hours'];
    $('[data-mt-set="checkpoint"]').checked = !!s.checkpoint;
    vacuumWarn();
  }
  function vacuumWarn() {
    var v = $('[data-mt-set="vacuum-hours"]').value, safe = (state.overview || {}).vacuumSafeHours || 168;
    $('[data-mt-vacuum-warn]').hidden = v === '' || Number(v) >= safe;
  }
  $$('[data-mt-set]').forEach(function (i) {
    i.addEventListener(i.type === 'checkbox' ? 'change' : 'input', function () { state.dirty[i.getAttribute('data-mt-set')] = true; vacuumWarn(); });
  });
  $('[data-mt-save]').addEventListener('click', function () {
    var settings = {};
    Object.keys(state.dirty).forEach(function (k) {
      var i = $('[data-mt-set="' + k + '"]');
      settings[k] = i.type === 'checkbox' ? i.checked : i.value;
    });
    if (!Object.keys(settings).length) { say('Change a setting first.'); return; }
    api('PUT', API + '/settings', { tables: selection(), settings: settings }).then(function (j) {
      state.dirty = {};
      say('Saved for ' + j.tables + ' table(s).' + (j.warnings && j.warnings.length ? ' Note: ' + j.warnings.join('; ') : ''));
      refresh();
    }).catch(function (e) { say(e.message, true); });
  });

  // schedules ----------------------------------------------------------------------------------------------------
  function pickedDays() { return $$('[data-mt-day]').filter(function (c) { return c.checked; }).map(function (c) { return c.value; }); }
  function cronFromPicker() {
    var at = ($('[data-mt-at]').value || '02:30').split(':'), days = pickedDays().map(function (d) { return DAYS.indexOf(d); }).sort();
    return Number(at[1]) + ' ' + Number(at[0]) + ' * * ' + (days.length === 7 ? '*' : days.join(','));
  }
  function friendly(cron) {
    var f = cron.trim().split(/\s+/);
    if (f.length !== 5 || !/^\d+$/.test(f[0]) || !/^\d+$/.test(f[1]) || f[2] !== '*' || f[3] !== '*') { return 'cron ' + cron; }
    var at = ('0' + f[1]).slice(-2) + ':' + ('0' + f[0]).slice(-2);
    if (f[4] === '*') { return 'every day ' + at; }
    var names = { 0: 'Sun', 1: 'Mon', 2: 'Tue', 3: 'Wed', 4: 'Thu', 5: 'Fri', 6: 'Sat', 7: 'Sun' }, days = [];
    f[4].split(',').forEach(function (p) {
      var r = p.split('-');
      for (var d = Number(r[0]); d <= Number(r[r.length - 1]); d++) { if (days.indexOf(d % 7) < 0) { days.push(d % 7); } }
    });
    days.sort(function (a, b) { return ((a + 6) % 7) - ((b + 6) % 7); });
    var run = days.length > 2 && days.every(function (d, k) { return k === 0 || ((d + 6) % 7) === ((days[k - 1] + 6) % 7) + 1; });
    return (run ? names[days[0]] + '–' + names[days[days.length - 1]] : days.map(function (d) { return names[d]; }).join(', ')) + ' ' + at;
  }
  function pickerChanged() {
    state.cronEdited = false;
    $('[data-mt-cron]').value = cronFromPicker();
    $('[data-mt-friendly]').textContent = pickedDays().length ? friendly($('[data-mt-cron]').value) : 'Pick at least one day.';
  }
  $$('[data-mt-day]').forEach(function (c) { c.addEventListener('change', pickerChanged); });
  $('[data-mt-at]').addEventListener('input', pickerChanged);
  $('[data-mt-cron]').addEventListener('input', function () { state.cronEdited = true; $('[data-mt-friendly]').textContent = friendly($('[data-mt-cron]').value); });
  $$('[data-mt-mode]').forEach(function (r) {
    r.addEventListener('change', function () { $('[data-mt-timepick]').hidden = r.value === 'after-build' && r.checked; });
  });
  $('[data-mt-add]').addEventListener('click', function () {
    var name = $('[data-mt-sname]').value.trim();
    if (!name) { say('Name the schedule first.', true); $('[data-mt-sname]').focus(); return; }
    var after = $$('[data-mt-mode]').some(function (r) { return r.checked && r.value === 'after-build'; });
    var body = { name: name, tables: selection() };
    if (after) { body.afterBuild = true; } else if (state.cronEdited) { body.cron = $('[data-mt-cron]').value; } else { body.days = pickedDays(); body.at = $('[data-mt-at]').value; }
    api('POST', API + '/schedules', body).then(function (s) {
      say('Schedule ' + s.name + ' created: ' + s.friendly + '.');
      $('[data-mt-sname]').value = '';
      refresh();
    }).catch(function (e) { say(e.message, true); });
  });
  function renderSchedules(list) {
    var body = $('[data-mt-schedules] tbody');
    body.textContent = '';
    if (!list.length) { body.appendChild(el('tr')).appendChild(el('td', { colspan: '6', 'class': 'text-muted-d' }, 'No schedule yet: pick tables, then create one below.')); return; }
    list.forEach(function (s) {
      var tr = el('tr', { 'data-mt-schedule': s.name });
      var th = el('th', { scope: 'row' }, s.name);
      if (s.paused) { th.appendChild(el('span', { 'class': 'mt-paused' }, ' paused')); }
      tr.appendChild(th);
      var when = el('td', {}, s.friendly);
      if (s.when !== 'after-build') { when.appendChild(el('br')); when.appendChild(el('small', { 'class': 'mono text-muted-d' }, s.when)); }
      tr.appendChild(when);
      tr.appendChild(el('td', { 'class': 'mono' }, (s.tables || []).join(', ')));
      tr.appendChild(el('td', { 'class': 'mono' }, s.paused ? '–' : (s.next || (s.when === 'after-build' ? 'after a build' : '–'))));
      tr.appendChild(el('td', { 'class': 'mono' }, s.lastRun ? s.lastRun.replace('T', ' ').slice(0, 16) : '–'));
      var act = el('td', { 'class': 'mt-actions' });
      var p = el('button', { type: 'button', 'class': 'btn-pill btn-sm', 'data-mt-pause': s.name }, s.paused ? 'Resume' : 'Pause');
      p.addEventListener('click', function () {
        api('POST', API + '/schedules/pause', { root: s.root, name: s.name, paused: !s.paused }).then(function () { say('Schedule ' + s.name + (s.paused ? ' resumed.' : ' paused.')); refresh(); })
          .catch(function (e) { say(e.message, true); });
      });
      var d = el('button', { type: 'button', 'class': 'btn-pill btn-sm', 'data-mt-delete': s.name }, 'Delete');
      d.addEventListener('click', function () {
        if (!window.confirm('Delete the schedule ' + s.name + '? The tables keep their settings.')) { return; }
        api('POST', API + '/schedules/delete', { root: s.root, name: s.name }).then(function () { say('Schedule ' + s.name + ' deleted.'); refresh(); })
          .catch(function (e) { say(e.message, true); });
      });
      act.appendChild(p);
      act.appendChild(d);
      tr.appendChild(act);
      body.appendChild(tr);
    });
  }

  // history and refresh ------------------------------------------------------------------------------------------
  function renderHistory(rows) {
    var body = $('[data-mt-history] tbody');
    body.textContent = '';
    if (!rows.length) { body.appendChild(el('tr')).appendChild(el('td', { colspan: '6', 'class': 'text-muted-d' }, 'No maintenance has run yet.')); return; }
    rows.forEach(function (h) {
      var tabs = h.tables || [], sum = function (side, k) { return tabs.reduce(function (n, t) { return n + (((t[side] || {})[k]) || 0); }, 0); };
      var after = h.dryRun ? 'estimate' : 'after';
      var tr = el('tr');
      var when = el('th', { scope: 'row', 'class': 'mono' }, (h.at || '').replace('T', ' ').slice(0, 19));
      if (h.dryRun) { when.appendChild(el('span', { 'class': 'mt-paused' }, ' dry run')); }
      tr.appendChild(when);
      tr.appendChild(el('td', {}, (h.by || '–') + (h.schedule ? ' (schedule ' + h.schedule + ')' : '')));
      tr.appendChild(el('td', { 'class': 'mono' }, tabs.map(function (t) { return t.name; }).join(', ')));
      tr.appendChild(el('td', { 'class': 'mono' }, fmt(sum('before', 'files')) + ' → ' + fmt(sum(after, 'files'))));
      tr.appendChild(el('td', { 'class': 'mono' }, mb(sum('before', 'mb')) + ' → ' + mb(sum(after, 'mb'))));
      var errs = tabs.filter(function (t) { return t.error; }).map(function (t) { return t.name + ': ' + t.error; });
      var out = el('td', { 'class': h.outcome === 'ok' ? '' : 't-bad' }, h.outcome + (h.note ? ' — ' + h.note : '') + (errs.length ? ' — ' + errs.join('; ') : ''));
      tr.appendChild(out);
      body.appendChild(tr);
    });
  }
  function settingsText(s) {
    return (s['keep-business-days'] === null ? 'keep all dates' : Number(s['keep-business-days']).toLocaleString() + ' business days') + ' · ' + s['target-file-mb'] + ' MB files · ' +
      (s.checkpoint ? 'checkpoint' : 'no checkpoint') + ' · ' + (s['vacuum-hours'] === null ? 'no vacuum' : 'vacuum ' + s['vacuum-hours'] + ' h');
  }
  function refresh() {
    return api('GET', API).then(function (o) {
      state.overview = o;
      o.tables.forEach(function (t) {
        var row = Array.prototype.slice.call(document.querySelectorAll('[data-lake-table]')).filter(function (r) {
          return r.getAttribute('data-lake-table') === t.table && r.getAttribute('data-root') === t.root;
        })[0];
        if (!row) { return; }
        var f = row.querySelector('[data-mt-files]'), z = row.querySelector('[data-mt-size]'), s = row.querySelector('[data-mt-settings]');
        if (f && t.files !== undefined) { f.textContent = t.files; }
        if (z && t.size) { z.textContent = t.size; }
        if (s && t.settings) { s.textContent = settingsText(t.settings); }
      });
      renderSchedules(o.schedules || []);
      renderHistory(o.history || []);
      var live = (o.running || [])[0];
      if (live && !state.run) { state.run = live; $('[data-mt-runbox]').hidden = false; poll(); }
      selectionChanged();
    }).catch(function (e) { say(e.message, true); });
  }
  refresh();
})();
