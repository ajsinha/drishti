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
/* The Subscribe form's "When": choices (daily at, business days at, weekly on, monthly on the last business day, hourly) that write the schedule text
   (docs/guides/DISTRIBUTION.md, schedules); the text itself stays editable under Advanced. The form also says which model the report reads, so a person
   knows what the figures are asked of. Text only, never markup. */
(function () {
  'use strict';
  var form = document.querySelector('[data-subscribe-form]');
  var raw = form && form.elements.schedule;
  if (!form || !raw) { return; }
  var kind = form.querySelector('[data-when-kind]'), time = form.querySelector('[data-when-time]'), day = form.querySelector('[data-when-day]'), shown = form.querySelector('[data-when-says]');
  if (!kind) { return; }
  var timeRow = form.querySelector('[data-when-time-row]'), dayRow = form.querySelector('[data-when-day-row]');

  function build() {
    var t = time.value || '07:30';
    switch (kind.value) {
      case 'daily': return 'daily ' + t;
      case 'business-days': return 'business-days ' + t;
      case 'weekly': return 'weekly ' + day.value + ' ' + t + ' business';
      case 'month-end': return 'monthly last ' + t + ' business';
      case 'hourly': return 'hourly';
      default: return raw.value;
    }
  }
  function words() {
    var t = time.value || '07:30', d = day.options[day.selectedIndex] ? day.options[day.selectedIndex].textContent : '';
    return { daily: 'Every day at ' + t, 'business-days': 'Every business day at ' + t, weekly: 'Every ' + d + ' at ' + t + ' (the next business day when it is a holiday)',
      'month-end': 'On the last business day of the month at ' + t, hourly: 'Every hour', custom: 'The schedule text below' }[kind.value];
  }
  function sync() {
    var custom = kind.value === 'custom';
    timeRow.hidden = custom || kind.value === 'hourly';
    dayRow.hidden = kind.value !== 'weekly';
    if (!custom) { raw.value = build(); }
    shown.textContent = words() + '.';
  }
  /** Reads an existing schedule back into the choices; anything else is custom. */
  function read() {
    var s = raw.value.trim(), m;
    if ((m = /^daily (\d\d:\d\d)$/.exec(s))) { kind.value = 'daily'; time.value = m[1]; } else if ((m = /^business-days (\d\d:\d\d)$/.exec(s))) { kind.value = 'business-days'; time.value = m[1]; } else if ((m = /^weekly (mon|tue|wed|thu|fri|sat|sun) (\d\d:\d\d)( business)?$/.exec(s))) { kind.value = 'weekly'; day.value = m[1]; time.value = m[2]; } else if ((m = /^monthly last (\d\d:\d\d) business$/.exec(s))) { kind.value = 'month-end'; time.value = m[1]; } else if (s === 'hourly') { kind.value = 'hourly'; } else if (s) { kind.value = 'custom'; } else { kind.value = 'business-days'; }
    sync();
  }
  [kind, time, day].forEach(function (el) { el.addEventListener('change', sync); el.addEventListener('input', sync); });
  raw.addEventListener('input', function () { var s = raw.value; kind.value = 'custom'; sync(); raw.value = s; });
  read();

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
