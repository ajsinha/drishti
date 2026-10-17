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
/* Admin → Packs → a pack → Load data: the kinds this pack loads into its File connector (and where), a data file chosen here and sent
   to the server as it is read from disk (the browser streams it: a large file is never held whole), the progress, then what the server did:
   rows per business date, rows refused and why, the checks against the pack's Sutras (types with no Sutra, fields no Sutra shows), and the
   load records (refresh, verify) with a link to open a record. Text from the server goes into the page as text only. */
(function () {
  'use strict';
  var box = document.querySelector('[data-pack-data]');
  if (!box) { return; }
  var pack = box.getAttribute('data-pack-data');
  var base = '/admin/api/packs/' + encodeURIComponent(pack) + '/data';
  var targetsEl = box.querySelector('[data-data-targets]'), form = box.querySelector('[data-data-form]'), fileIn = box.querySelector('[data-data-file]');
  var kindSel = box.querySelector('[data-data-kind]'), kindBox = box.querySelector('[data-data-kind-box]'), dateIn = box.querySelector('[data-data-date]');
  var dateBox = box.querySelector('[data-data-date-box]'), go = box.querySelector('[data-data-load]'), cancel = box.querySelector('[data-data-cancel]');
  var prog = box.querySelector('[data-data-prog]'), msg = box.querySelector('[data-data-msg]'), out = box.querySelector('[data-data-result]');
  var kinds = [], xhr = null, maxMb = 2048;

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { e.textContent = attrs[k]; } else if (k === 'class') { e.className = attrs[k]; } else if (attrs[k] != null && attrs[k] !== false) { e.setAttribute(k, attrs[k] === true ? '' : attrs[k]); }
    });
    (kids || []).forEach(function (c) { if (c != null) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }
  function say(t, bad) { msg.textContent = t; msg.classList.toggle('t-bad', !!bad); msg.classList.toggle('t-ok', !bad && !!t); }
  function num(n) { return Number(n || 0).toLocaleString('en-US'); }
  function mb(b) { return (b / 1048576).toFixed(b < 10485760 ? 1 : 0) + ' MB'; }
  function today() { var d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }

  function current() { var k = kindSel.value; return kinds.filter(function (x) { return x.kind === k; })[0]; }
  function sync() {
    var k = current();
    dateBox.hidden = !(k && k.snapshot);
    go.disabled = !(fileIn.files && fileIn.files[0]) || !k || !!k.problem || !!xhr;
  }

  fetch(base, { headers: { Accept: 'application/json' } }).then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); }).then(function (r) {
    while (targetsEl.firstChild) { targetsEl.removeChild(targetsEl.firstChild); }
    if (!r.ok) { targetsEl.appendChild(el('p', { class: 't-bad', text: (r.body && r.body.detail) || 'Could not read what this pack loads.' })); return; }
    kinds = r.body.kinds || [];
    maxMb = r.body.maxMb || maxMb;
    if (!kinds.length) {
      targetsEl.appendChild(el('p', { class: 'text-muted-d', text: 'This pack names no kind to load (its pack.yaml has no ingest: section). Packs made by Build → New pack from data files have one.' }));
      return;
    }
    var t = el('table', { class: 'tbl adm-tbl det-tbl', 'data-plain': true }, [el('caption', { class: 'visually-hidden', text: 'What this pack loads' }),
      el('thead', {}, [el('tr', {}, ['Kind', 'Connector', 'Folder', 'Id', 'Business date'].map(function (h) { return el('th', { scope: 'col', text: h }); }))])]);
    var tb = el('tbody');
    kinds.forEach(function (k) {
      tb.appendChild(el('tr', { 'data-data-target': k.kind }, k.problem ? [el('th', { scope: 'row', class: 'mono', text: k.kind }), el('td', { colspan: '4', class: 't-warn', text: k.problem })]
        : [el('th', { scope: 'row', class: 'mono', text: k.kind }), el('td', { class: 'mono', text: k.connector }), el('td', { class: 'mono pd-folder', text: k.folder }),
          el('td', { class: 'mono', text: k.key }), el('td', { text: k.snapshot ? 'snapshot: the day you choose below' : (k.date || '') })]));
      kindSel.appendChild(el('option', { value: k.kind, text: k.kind + (k.problem ? ' (cannot be loaded here)' : '') }));
    });
    t.appendChild(tb);
    targetsEl.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
    targetsEl.appendChild(el('p', { class: 'text-muted-d', text: 'Up to ' + num(maxMb) + ' MB per file (drishti.loads.upload-max-mb).' }));
    kindBox.hidden = kinds.length < 2;
    dateIn.value = r.body.businessDate || today();
    form.hidden = false;
    sync();
  });

  fileIn.addEventListener('change', function () { out.hidden = true; say(''); sync(); });
  kindSel.addEventListener('change', sync);

  function result(b) {
    while (out.firstChild) { out.removeChild(out.firstChild); }
    var days = Object.keys(b.days || {});
    out.appendChild(el('p', { 'data-data-summary': true }, [el('b', { text: num(b.rows) + ' ' + b.kind + ' rows loaded' }),
      ' into ' + b.connector + ' for ' + (days.length === 1 ? days[0] : days.length + ' business dates') + (b.rejected ? '; ' + num(b.rejected) + ' refused' : '') +
      ' (' + mb(b.bytes) + ' in ' + (b.millis / 1000).toFixed(1) + ' s).']));
    if (b.rejections && b.rejections.length) {
      out.appendChild(el('ul', { class: 'det-list t-warn', 'aria-label': 'Rows refused' }, b.rejections.map(function (x) { return el('li', { text: x }); })));
    }
    if (days.length > 1) {
      out.appendChild(el('ul', { class: 'det-list', 'aria-label': 'Rows per business date' }, days.map(function (d) { return el('li', { class: 'mono', text: d + ': ' + num(b.days[d]) }); })));
    }
    if ((b.checks || []).length) {
      out.appendChild(el('h3', { class: 'ws-h', text: 'Checked against the Sutras' }));
      out.appendChild(el('ul', { class: 'det-list', 'data-data-checks': true }, b.checks.map(function (c) {
        return el('li', { class: c.level === 'bad' ? 't-bad' : c.level === 'warn' ? 't-warn' : '' }, [el('span', { class: 'st' + (c.level === 'bad' ? ' st-bad' : c.level === 'warn' ? ' st-warn' : ''), text: c.level }), ' ' + c.message]);
      })));
    } else {
      out.appendChild(el('p', { class: 't-ok', text: 'Every row has a Sutra of its type, and every field the rows fill is shown.' }));
    }
    out.appendChild(el('p', { class: 'adm-actions' }, [el('a', { class: 'fk', href: '/admin/loads/' + encodeURIComponent(b.pack), text: 'Data loads of ' + b.pack }),
      ' ', el('a', { class: 'fk', href: '/search?q=' + encodeURIComponent(b.kind), text: 'Search ' + b.kind })]));
    out.hidden = false;
  }

  go.addEventListener('click', function () {
    var f = fileIn.files && fileIn.files[0], k = current();
    if (!f || !k) { return; }
    if (f.size > maxMb * 1048576) { say(f.name + ' is ' + mb(f.size) + ', over the ' + num(maxMb) + ' MB limit.', true); return; }
    var q = '?kind=' + encodeURIComponent(k.kind) + '&file=' + encodeURIComponent(f.name.replace(/[^\x20-\x7e]/g, '_')) + (k.snapshot && dateIn.value ? '&date=' + encodeURIComponent(dateIn.value) : '');
    xhr = new XMLHttpRequest();
    xhr.open('POST', base + q);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.setRequestHeader('Accept', 'application/json');
    prog.hidden = false; prog.value = 0; cancel.hidden = false; go.disabled = true; out.hidden = true;
    say('Sending ' + f.name + ' (' + mb(f.size) + ')…');
    xhr.upload.onprogress = function (e) {
      if (e.lengthComputable) {
        prog.value = Math.round(100 * e.loaded / e.total);
        say(e.loaded < e.total ? 'Sending ' + f.name + ': ' + prog.value + '% (' + mb(e.loaded) + ' of ' + mb(e.total) + ')…' : 'Sent: the server is writing the rows and checking them…');
      }
    };
    xhr.onload = function () {
      var b = {};
      try { b = JSON.parse(xhr.responseText || '{}'); } catch (e) { b = {}; }
      var ok = xhr.status >= 200 && xhr.status < 300;
      xhr = null; prog.hidden = true; cancel.hidden = true; sync();
      if (!ok) { say((window.drsMessage ? window.drsMessage({ code: b.code || 'HTTP', detail: b.detail }, 'The load failed') : (b.detail || 'The load failed')), true); return; }
      say('Loaded.');
      result(b);
    };
    xhr.onerror = function () { xhr = null; prog.hidden = true; cancel.hidden = true; sync(); say('The connection was lost while sending: nothing was changed. Load the file again.', true); };
    xhr.onabort = function () { xhr = null; prog.hidden = true; cancel.hidden = true; sync(); say('Cancelled: nothing was changed (a day is replaced only when its whole file is written).'); };
    xhr.send(f);
  });
  cancel.addEventListener('click', function () { if (xhr) { xhr.abort(); } });
})();
