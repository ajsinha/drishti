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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The Subscribe form's "When": drishti-inputs' Form, live inside the page's form (how often, the day for a weekly one, the time with
   the time picker; the day and the time show only when they apply), writing the schedule text (docs/guides/BI_GUIDE.md, schedules);
   the text itself stays editable under Advanced, and a text the choices cannot say reads back as "on a schedule I write". The form also
   says which model the report reads, so a person knows what the figures are asked of. Text only, never markup. */
(function () {
  'use strict';
  var form = document.querySelector('[data-subscribe-form]');
  var raw = form && form.elements.schedule;
  var host = form && form.querySelector('[data-when-form]');
  var I = window.Drishti.elements && window.Drishti.elements.inputs;
  if (!form || !raw || !host || !I) { return; }
  var shown = form.querySelector('[data-when-says]');
  var DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], DAY_WORDS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  var schema = { type: 'object', required: ['kind'], properties: {
    kind: { type: 'string', title: 'Send', hook: 'when-kind', allowEmpty: false,
      enum: ['daily', 'business-days', 'weekly', 'month-end', 'hourly', 'custom'],
      enumLabels: ['every day', 'every business day', 'every week on', 'on the last business day of the month', 'every hour', 'on a schedule I write (advanced)'], widget: 'select' },
    day: { type: 'string', title: 'Day', hook: 'when-day', allowEmpty: false, enum: DAYS, enumLabels: DAY_WORDS, widget: 'select', when: { field: 'kind', equals: 'weekly' } },
    time: { type: 'time', title: 'At', hook: 'when-time', when: { field: 'kind', notIn: ['hourly', 'custom'] } } } };

  function build(v) {
    var t = v.time || '07:30';
    switch (v.kind) {
      case 'daily': return 'daily ' + t;
      case 'business-days': return 'business-days ' + t;
      case 'weekly': return 'weekly ' + (v.day || 'mon') + ' ' + t + ' business';
      case 'month-end': return 'monthly last ' + t + ' business';
      case 'hourly': return 'hourly';
      default: return raw.value;
    }
  }
  function words(v) {
    var t = v.time || '07:30', d = DAY_WORDS[DAYS.indexOf(v.day || 'mon')];
    return { daily: 'Every day at ' + t, 'business-days': 'Every business day at ' + t, weekly: 'Every ' + d + ' at ' + t + ' (the next business day when it is a holiday)',
      'month-end': 'On the last business day of the month at ' + t, hourly: 'Every hour', custom: 'The schedule text below' }[v.kind];
  }
  /** An existing schedule read back into the choices; anything else is custom. */
  function read(s) {
    var m;
    s = (s || '').trim();
    if ((m = /^daily (\d\d:\d\d)$/.exec(s))) { return { kind: 'daily', day: 'mon', time: m[1] }; }
    if ((m = /^business-days (\d\d:\d\d)$/.exec(s))) { return { kind: 'business-days', day: 'mon', time: m[1] }; }
    if ((m = /^weekly (mon|tue|wed|thu|fri|sat|sun) (\d\d:\d\d)( business)?$/.exec(s))) { return { kind: 'weekly', day: m[1], time: m[2] }; }
    if ((m = /^monthly last (\d\d:\d\d) business$/.exec(s))) { return { kind: 'month-end', day: 'mon', time: m[1] }; }
    if (s === 'hourly') { return { kind: 'hourly', day: 'mon', time: '07:30' }; }
    return s ? { kind: 'custom', day: 'mon', time: '07:30' } : { kind: 'business-days', day: 'mon', time: '07:30' };
  }
  var when = new I.Form(host, { schema: schema, value: read(raw.value), embedded: true, label: 'When', layout: 'columns' });
  function sync() {
    var v = when.getField ? { kind: when.getField('kind'), day: when.getField('day'), time: when.getField('time') } : when.getValue();
    if (v.kind !== 'custom') { raw.value = build(v); }
    shown.textContent = words(v) + '.';
  }
  when.on('change', sync);
  raw.addEventListener('input', function () { var s = raw.value; if (when.getField('kind') !== 'custom') { when.setField('kind', 'custom'); } raw.value = s; sync(); });
  sync();

  // the model the report reads
  var note = form.querySelector('[data-model-note]');
  try {
    var target = JSON.parse(form.elements.target.value || '{}');
    if (note && target.type === 'report' && target.id) {
      fetch('/bi/api/reports/' + encodeURIComponent(target.id).replace(/%3A/g, ':'), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (rep) {
          var r = rep && rep.report;
          if (!r) { return; }
          var m = r.model || (r.dataset ? '' : '');
          note.textContent = m ? 'The figures are asked of the model ' + m + ', as each recipient.' : r.dataset ? 'The figures are asked of the dataset ' + r.dataset + ', as each recipient.' : '';
        }).catch(function () {});
    }
  } catch (e) { /* no target to describe */ }
})();
