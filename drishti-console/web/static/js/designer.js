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
/* The Sutra designer's Customize mode (docs/guides/SCREEN_DESIGNER.md section 31): the real page, with real data as the viewer, edited in place.
   Customize makes a draft of this page's Sutra; each change is one step the console applies to the draft and the page is drawn again
   with it. Panels get a handle (drag, or the arrows), Hide and, for tables, Columns; the side panel holds "When I click", "Add to this
   page" and what is hidden. Done keeps the draft as the user's own page; Cancel forgets it. Plain words only: no pins, wires or YAML
   unless "Show the Sutra" is asked for. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  if (!view) { return; }
  var kind = view.dataset.kind, id = view.dataset.id, page = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id);

  function api(method, url, body) {
    return fetch(url, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json' } : {},
                        body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { if (!r.ok) { throw new Error(j.detail || 'Something went wrong'); } return j; }); });
  }
  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { e.textContent = attrs[k]; } else if (k === 'on') { Object.keys(attrs.on).forEach(function (ev) { e.addEventListener(ev, attrs.on[ev]); }); }
      else if (attrs[k] != null) { e.setAttribute(k, attrs[k]); }
    });
    (kids || []).forEach(function (c) { if (c) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }

  var open = document.querySelector('[data-customize-open]');
  if (open) {
    open.addEventListener('click', function () {
      open.disabled = true;
      api('POST', '/designer/customize', { kind: kind, id: id }).then(function (r) { location.href = r.url; })
        .catch(function (e) { open.disabled = false; alert(e.message); });
    });
  }
  if (!view.hasAttribute('data-customizing')) { return; }

  var draft = view.dataset.design, state = null, msg, side;
  function say(text, ok) { msg.textContent = text || ''; msg.className = 'dz-msg' + (ok ? ' dz-ok' : ''); }
  function step(body) {
    body.kind = kind; body.id = id;
    say('Saving…', true);
    return api('POST', '/designer/op/' + encodeURIComponent(draft), body).then(function () { location.reload(); })
      .catch(function (e) { say(e.message); throw e; });
  }

  /* the yellow bar */
  msg = el('span', { 'class': 'dz-msg', role: 'status', 'aria-live': 'polite' });
  var bar = el('div', { 'class': 'dz-bar', role: 'region', 'aria-label': 'Customizing this page' }, [
    el('span', { 'class': 'dz-bar-words' }, [el('i', { 'class': 'bi bi-sliders', 'aria-hidden': 'true' }), ' Customizing · only you see changes until you share']),
    msg,
    el('span', { 'class': 'dz-grow' }),
    el('a', { 'class': 'dz-btn dz-quiet', href: '/designer/new', text: 'Describe a change' }),
    el('button', { type: 'button', 'class': 'dz-btn', 'data-dz-cancel': '', text: 'Cancel', on: { click: function () {
      api('POST', '/designer/cancel/' + encodeURIComponent(draft)).finally(function () { location.href = page; }); } } }),
    el('button', { type: 'button', 'class': 'dz-btn dz-primary', 'data-dz-done': '', text: 'Done', on: { click: function () {
      say('Keeping your page…', true);
      api('POST', '/designer/done/' + encodeURIComponent(draft), { kind: kind }).then(function () { location.href = page; })
        .catch(function (e) { say(e.message); }); } } })
  ]);
  view.insertBefore(bar, view.firstChild);
  document.body.classList.add('dz-on');

  /* the side panel */
  side = el('aside', { 'class': 'dz-side', 'aria-label': 'Customize' });
  var grid = view.querySelector('.vgrid');
  var wrap = el('div', { 'class': 'dz-wrap' });
  grid.parentNode.insertBefore(wrap, grid);
  wrap.appendChild(grid); wrap.appendChild(side);
  var drop = el('div', { 'class': 'dz-drop', 'data-dz-drop': '', tabindex: '-1', text: 'Drop here, or pick from the list on the right' });
  (view.querySelector('.vmain') || grid).appendChild(drop);

  function sectionsOf(part) {
    return part.ids.map(function (pid) { return document.getElementById('p-' + pid); }).filter(Boolean);
  }

  function columnsBox(part, sec) {
    var t = part.table, box = el('div', { 'class': 'dz-cols', role: 'group', 'aria-label': 'Columns' });
    var chosen = t.chosen.slice();
    t.fields.forEach(function (f) {
      var cb = el('input', { type: 'checkbox', value: f });
      cb.checked = chosen.indexOf(f) >= 0;
      box.appendChild(el('label', {}, [cb, ' ' + f.replace(/_/g, ' ').toLowerCase()]));
    });
    box.appendChild(el('button', { type: 'button', 'class': 'dz-btn dz-primary', 'data-dz-cols-apply': '', text: 'Show these columns', on: { click: function () {
      var picked = [].slice.call(box.querySelectorAll('input:checked')).map(function (i) { return i.value; });
      var order = chosen.filter(function (f) { return picked.indexOf(f) >= 0; }).concat(picked.filter(function (f) { return chosen.indexOf(f) < 0; }));
      step({ op: 'columns', panel: t.id, fields: order }).catch(function () {});
    } } }));
    sec.appendChild(box);
  }

  function tools(part, sec, first) {
    var n = state.parts.length;
    var handle = el('span', { 'class': 'dz-handle', draggable: 'true', title: 'Drag to move', 'aria-hidden': 'true', text: '⋮⋮' });
    handle.addEventListener('dragstart', function (e) { e.dataTransfer.setData('text/x-dz-part', String(part.index)); e.dataTransfer.effectAllowed = 'move'; });
    var row = el('div', { 'class': 'dz-tools', 'data-dz-part': String(part.index) }, [
      handle,
      el('strong', { text: part.title }),
      el('span', { 'class': 'dz-grow' }),
      el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-up': '', 'aria-label': 'Move ' + part.title + ' up', disabled: part.index === 0 ? '' : null, text: '↑',
                     on: { click: function () { step({ op: 'move', index: part.index, to: part.index - 1 }).catch(function () {}); } } }),
      el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-down': '', 'aria-label': 'Move ' + part.title + ' down', text: '↓',
                     on: { click: function () { step({ op: 'move', index: part.index, to: part.index + 1 }).catch(function () {}); } } }),
      (part.table || part.tables.length) && first ? el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-columns': '', text: 'Columns', on: { click: function () {
        if (part.table) { var old = sec.querySelector('.dz-cols'); if (old) { old.remove(); } else { columnsBox(part, sec); } return; }
        step({ op: 'unpack', index: part.index }).catch(function () {});              // a used table is written out first, then it takes columns
      } } }) : null,
      first ? el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-hide': '', text: 'Hide', on: { click: function () { step({ op: 'hide', index: part.index, title: part.title }).catch(function () {}); } } }) : null
    ]);
    if (part.index === 0) { row.querySelector('[data-dz-up]').disabled = true; }
    if (part.index === n - 1) { row.querySelector('[data-dz-down]').disabled = true; }
    sec.classList.add('dz-panel');
    sec.insertBefore(row, sec.firstChild);
    sec.addEventListener('dragover', function (e) { if (e.dataTransfer.types.indexOf('text/x-dz-part') >= 0 || e.dataTransfer.types.indexOf('text/x-dz-kit') >= 0) { e.preventDefault(); sec.classList.add('dz-over'); } });
    sec.addEventListener('dragleave', function () { sec.classList.remove('dz-over'); });
    sec.addEventListener('drop', function (e) {
      sec.classList.remove('dz-over');
      var from = e.dataTransfer.getData('text/x-dz-part'), kit = e.dataTransfer.getData('text/x-dz-kit');
      if (from !== '') { e.preventDefault(); if (+from !== part.index) { step({ op: 'move', index: +from, to: part.index }).catch(function () {}); } }
      else if (kit) { e.preventDefault(); var k = JSON.parse(kit); step({ op: 'add', ref: k.ref, 'with': k['with'], at: part.index }).catch(function () {}); }
    });
  }

  function select(label, options, attr) {
    var s = el('select', { 'class': 'dz-pill', 'aria-label': label });
    s.setAttribute(attr, '');
    options.forEach(function (o) { s.appendChild(el('option', { value: o[0], text: o[1] })); });
    return s;
  }

  function sidePanel() {
    side.innerHTML = '';
    /* When I click */
    var tables = state.tables.map(function (t) { return ['t:' + t.id, t.title]; });
    state.parts.forEach(function (p) { if (!p.table && p.tables.length) { tables.push(['u:' + p.index + ':' + p.tables[0], p.title]); } });
    side.appendChild(el('h3', { 'class': 'dz-h', text: 'When I click' }));
    if (!tables.length) {
      side.appendChild(el('p', { 'class': 'dz-note', text: 'This page has no table to click.' }));
    } else {
      var from = select('the table to click', tables, 'data-dz-click-from');
      var what = select('what to show', [['row', 'the row’s details']], 'data-dz-click-show');
      var where = select('where to show it', Object.keys(state.places).map(function (k) { return [k, state.places[k]]; }), 'data-dz-click-place');
      var set = state.tables.filter(function (t) { return t.click; })[0];
      if (set) { from.value = 't:' + set.id; where.value = set.place || 'expand-row'; }
      side.appendChild(el('div', { 'class': 'dz-sentence' }, ['a row in ', from, ' show ', what, ' in ', where]));
      side.appendChild(el('div', { 'class': 'dz-row' }, [
        el('button', { type: 'button', 'class': 'dz-btn dz-primary', 'data-dz-click-apply': '', text: 'Make it so', on: { click: function () {
          var v = from.value;
          if (v.indexOf('u:') === 0) {                    // a used table: written out first, then it is clickable
            var bits = v.split(':');
            say('Opening up the table…', true);
            api('POST', '/designer/op/' + encodeURIComponent(draft), { op: 'unpack', index: +bits[1], kind: kind, id: id })
              .then(function () { return step({ op: 'click', panel: bits[2], place: where.value }); }).catch(function (e) { say(e.message); });
          } else { step({ op: 'click', panel: v.slice(2), place: where.value }).catch(function () {}); }
        } } }),
        set ? el('button', { type: 'button', 'class': 'dz-btn', text: 'Stop', on: { click: function () { step({ op: 'unclick', panel: set.id }).catch(function () {}); } } }) : null
      ]));
    }
    /* Add to this page */
    side.appendChild(el('h3', { 'class': 'dz-h', text: 'Add to this page' }));
    var q = el('input', { type: 'search', 'class': 'dz-search', placeholder: 'Search: limits, chart, map…', 'aria-label': 'Search what to add', 'data-dz-search': '' });
    var list = el('div', { 'class': 'dz-kit', 'data-dz-kit': '' });
    side.appendChild(q); side.appendChild(list);
    function show(items) {
      list.innerHTML = '';
      if (!items.length) { list.appendChild(el('p', { 'class': 'dz-note', text: 'Nothing matches: try other words.' })); }
      items.forEach(function (k) {
        var card = el('button', { type: 'button', 'class': 'dz-card' + (k.fit ? ' dz-fits' : ''), draggable: 'true', 'data-dz-add': k.ref, title: k.summary }, [
          el('strong', { text: k.title }), el('span', { text: ' · ' + k.summary })]);
        card.addEventListener('dragstart', function (e) { e.dataTransfer.setData('text/x-dz-kit', JSON.stringify(k)); });
        card.addEventListener('click', function () { step({ op: 'add', ref: k.ref, 'with': k['with'] }).catch(function () {}); });
        list.appendChild(card);
      });
    }
    show(state.kit);
    var timer;
    q.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () {
        api('GET', '/designer/kit/' + encodeURIComponent(draft) + '?q=' + encodeURIComponent(q.value) + '&kind=' + encodeURIComponent(kind) + '&id=' + encodeURIComponent(id))
          .then(function (r) { show(r.results); }).catch(function (e) { say(e.message); });
      }, 200);
    });
    /* hidden */
    if (state.hidden.length) {
      side.appendChild(el('h3', { 'class': 'dz-h', text: 'Hidden' }));
      state.hidden.forEach(function (h) {
        side.appendChild(el('div', { 'class': 'dz-row' }, [el('span', { 'class': 'dz-grow', text: h.title }),
          el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-show': h.title, text: 'Show', on: { click: function () { step({ op: 'show', hidden: h.index }).catch(function () {}); } } })]));
      });
    }
    /* your page */
    var foot = el('div', { 'class': 'dz-foot' });
    if (state.mine) {
      foot.appendChild(el('button', { type: 'button', 'class': 'dz-btn', 'data-dz-reset': '', text: 'Reset to the pack’s page', on: { click: function () {
        if (!confirm('Forget your version of this page and use the pack’s page again?')) { return; }
        api('POST', '/designer/reset', { kind: kind }).then(function () { return api('POST', '/designer/cancel/' + encodeURIComponent(draft)); })
          .then(function () { location.href = page; }).catch(function (e) { say(e.message); });
      } } }));
      foot.appendChild(el('button', { type: 'button', 'class': 'dz-btn', 'data-dz-share': '', text: 'Share', on: { click: function () {
        api('POST', '/build/designs/' + encodeURIComponent(state.mine.id) + '/share').then(function (r) {
          say('Anyone you send this read-only link to can see your page’s design: ' + r.url, true);
        }).catch(function (e) { say(e.message); });
      } } }));
    }
    var yaml = el('details', { 'class': 'dz-yaml' }, [el('summary', { text: 'Show the Sutra' }), el('pre', { 'class': 'mono', text: state.yaml || '' })]);
    foot.appendChild(yaml);
    side.appendChild(foot);
  }

  drop.addEventListener('dragover', function (e) { e.preventDefault(); drop.classList.add('dz-over'); });
  drop.addEventListener('dragleave', function () { drop.classList.remove('dz-over'); });
  drop.addEventListener('drop', function (e) {
    e.preventDefault(); drop.classList.remove('dz-over');
    var kit = e.dataTransfer.getData('text/x-dz-kit'), from = e.dataTransfer.getData('text/x-dz-part');
    if (kit) { var k = JSON.parse(kit); step({ op: 'add', ref: k.ref, 'with': k['with'] }).catch(function () {}); }
    else if (from !== '') { step({ op: 'move', index: +from, to: state.parts.length - 1 }).catch(function () {}); }
  });

  api('GET', '/designer/state/' + encodeURIComponent(draft) + '?kind=' + encodeURIComponent(kind) + '&id=' + encodeURIComponent(id)).then(function (s) {
    state = s;
    s.parts.forEach(function (part) { sectionsOf(part).forEach(function (sec, i) { tools(part, sec, i === 0); }); });
    sidePanel();
    view.setAttribute('data-dz-ready', '');
  }).catch(function (e) { say(e.message); });
})();
