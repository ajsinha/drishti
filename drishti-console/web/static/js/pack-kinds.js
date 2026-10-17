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
/* Build -> New pack, step 2: one card per kind with what was detected (key, links, match column, date column, mnemonic) and the controls to
 * change it, and a small graph of the kinds and their links. A change is reported to the page (onChange), which asks the console for a new
 * plan; the cards are drawn again from it. Everything drawn from the plan goes in as text. */
(function () {
  'use strict';
  var U = window.DrishtiPackUI, el = U.el;
  var SVG = 'http://www.w3.org/2000/svg';

  function svg(tag, attrs, text) {
    var e = document.createElementNS(SVG, tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined) { e.textContent = text; }
    return e;
  }

  /** The graph: kinds as boxes on a circle, a link as an arrow from the kind that holds the field to the kind it points at. */
  function graph(plan) {
    var kinds = plan.kinds.map(function (k) { return k.kind; });
    var n = kinds.length, W = 560, H = n > 2 ? 300 : 150, cx = W / 2, cy = H / 2, rx = W / 2 - 90, ry = H / 2 - 34;
    var pos = {};
    kinds.forEach(function (k, i) {
      var a = (2 * Math.PI * i) / Math.max(n, 1) - Math.PI / 2;
      pos[k] = n === 1 ? { x: cx, y: cy } : { x: cx + rx * Math.cos(a), y: cy + ry * Math.sin(a) };
    });
    var s = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, class: 'pk-graph', role: 'img', 'aria-label': 'Kinds and the links between them' });
    var defs = svg('defs'), mk = svg('marker', { id: 'pkArrow', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' });
    mk.appendChild(svg('path', { d: 'M0 0L10 5L0 10z', class: 'pk-arrow' }));
    defs.appendChild(mk);
    s.appendChild(defs);
    s.appendChild(svg('title', {}, plan.edges.length ? plan.edges.map(function (e) { return e.from + ' to ' + e.to + ' by ' + e.field; }).join('; ') : 'No links between the kinds'));
    var BW = 112, BH = 28;
    plan.edges.forEach(function (e) {
      var a = pos[e.from], b = pos[e.to];
      if (!a || !b) { return; }
      if (e.from === e.to) {
        s.appendChild(svg('path', { d: 'M' + (a.x + 30) + ' ' + (a.y - BH / 2) + ' c 28 -34 70 -6 36 16', class: 'pk-edge', 'marker-end': 'url(#pkArrow)', fill: 'none' }));
        return;
      }
      var dx = b.x - a.x, dy = b.y - a.y, len = Math.sqrt(dx * dx + dy * dy) || 1, ux = dx / len, uy = dy / len;
      var k = Math.min(BW / 2 / Math.abs(ux || 1e-6), BH / 2 / Math.abs(uy || 1e-6)) + 3;
      s.appendChild(svg('line', { x1: a.x + ux * k, y1: a.y + uy * k, x2: b.x - ux * k, y2: b.y - uy * k, class: 'pk-edge', 'marker-end': 'url(#pkArrow)' }));
      s.appendChild(svg('text', { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 - 3, class: 'pk-edge-t', 'text-anchor': 'middle' }, e.field));
    });
    kinds.forEach(function (k) {
      var p = pos[k], g = svg('g', {});
      g.appendChild(svg('rect', { x: p.x - BW / 2, y: p.y - BH / 2, width: BW, height: BH, rx: 7, class: 'pk-node' }));
      g.appendChild(svg('text', { x: p.x, y: p.y + 4, 'text-anchor': 'middle', class: 'pk-node-t' }, k.length > 16 ? k.slice(0, 15) + '…' : k));
      s.appendChild(g);
    });
    return s;
  }

  function field(label, control, hint, cls) {
    var id = 'pkf' + Math.random().toString(36).slice(2, 8);
    control.id = id;
    return el('div', { class: 'pk-field ' + (cls || '') }, [el('label', { for: id, text: label }), control, hint ? el('p', { class: 'pk-hint text-muted-d', text: hint }) : null]);
  }

  function scalarTop(k) {
    return k.fields.filter(function (f) { return f.path.indexOf('.') < 0 && f.path.indexOf('[') < 0 && /^(string|integer|number|)$/.test(f.type) && f.role !== 'object'; });
  }

  /** A page per value (a derived kind): what it groups, how many pages the samples make, and its mnemonic. */
  function derivedCard(plan, k, change) {
    var id = k.origKind || k.kind, d = k.derived;
    var c = el('article', { class: 'pk-card pk-derived', 'aria-labelledby': 'pkh-' + id, 'data-derived': d.from });
    c.appendChild(el('div', { class: 'pk-card-h' }, [el('h3', { id: 'pkh-' + id, class: 'pk-kind', text: k.title }), el('span', { class: 'bs-role', text: 'page per value' })]));
    c.appendChild(el('p', { class: 'pk-desc', text: 'A page for each ' + (d.label || '').toLowerCase() + ' of a ' + d.from + ': its ' + d.from + ' documents with their count and totals, built by the server from the documents themselves (no extra feed). '
      + U.plural(k.samples, 'page') + ' in the samples. The ' + d.from + ' view links to it; it links back to each document.' }));
    var mn = el('input', { type: 'text', class: 'studio-in mono', value: k.mnemonic, maxlength: '8', autocomplete: 'off', 'data-focus': 'mn-' + id });
    mn.addEventListener('change', function () { change(id, { mnemonic: mn.value.trim().toUpperCase() }); });
    c.appendChild(el('div', { class: 'pk-grid' }, [field('Mnemonic', mn, 'Typed before a value: ' + k.mnemonic + ' <' + (d.label || 'value').toLowerCase() + '>.')]));
    return c;
  }

  /** "Split into Sutras by": one type column, one Sutra per value found (every row counted), with each type's row count. */
  function splitBox(plan, k, change) {
    var id = k.origKind || k.kind, cur = (k.match || [])[0] || '';
    var box = el('div', { class: 'pk-split', 'data-split': id });
    box.appendChild(el('h4', { class: 'bs-h2', text: 'Split into Sutras by a type column' }));
    var opts = [{ value: '', label: '(none) one Sutra for every ' + k.kind }];
    var seen = {};
    (k.matchOptions || []).forEach(function (o) { seen[o.field] = 1; opts.push({ value: o.field, label: o.field + ' (' + U.plural(o.values.length, 'type') + (o.typeish ? ')' : ')') }); });
    if (cur && !seen[cur]) { opts.push({ value: cur, label: cur }); }
    var sel = U.select(opts, cur, { 'data-focus': 'match-' + id, 'aria-label': 'Type column of ' + k.kind });
    sel.addEventListener('change', function () { change(id, { match: sel.value ? [sel.value] : [] }); });
    box.appendChild(field('Type column', sel, 'One Sutra per value found in the whole file (every row is counted, rare types too). A catch-all Sutra is added only when some rows have no value.'));
    var counts = cur && k.matchCounts ? k.matchCounts[cur] : null;
    if (counts) {
      var vals = Object.keys(counts).filter(function (v) { return v !== ''; }).sort(function (a, b) { return counts[b] - counts[a]; });
      var blanks = counts[''] || 0;
      box.appendChild(el('p', { class: 'pk-types-h', 'data-types-summary': true, text: U.plural(vals.length, 'type') + ' → ' + U.plural(k.sutras.length, 'Sutra') + (blanks ? ' (' + U.num(blanks) + ' rows have no ' + cur + ': they get the catch-all ' + k.kind + '-default)' : '') }));
      var t = el('table', { class: 'tbl adm-tbl pk-types', 'data-plain': true }, [el('thead', {}, [el('tr', {}, [el('th', { text: cur }), el('th', { class: 'num', text: 'Rows' }), el('th', { text: 'Sutra' })])])]);
      var tb = el('tbody');
      vals.forEach(function (v) {
        var s = k.sutras.filter(function (x) { return !x.fallback && x.where.indexOf("'" + v.replace(/'/g, "\\'") + "'") >= 0; })[0];
        tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: v }), el('td', { class: 'num mono', text: U.num(counts[v]) }), el('td', { class: 'mono', text: s ? s.name : '' })]));
      });
      t.appendChild(tb);
      box.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
    }
    return box;
  }

  /** Pages for each value of a field: one checkbox per field that groups the documents (counterparty, portfolio, index ...). */
  function relatedBox(k, change) {
    var id = k.origKind || k.kind, on = (k.related || []).map(function (r) { return r.path; });
    var box = el('fieldset', { class: 'pk-related', 'data-related': id }, [el('legend', { text: 'Pages for each value (counterparty, portfolio, index …)' })]);
    (k.relatedOptions || []).forEach(function (o) {
      var cid = 'pkr-' + id + '-' + o.path.replace(/[^A-Za-z0-9]/g, '_');
      var cb = el('input', { type: 'checkbox', id: cid, 'data-focus': 'rel-' + id + '-' + o.path, value: o.path });
      cb.checked = on.indexOf(o.path) >= 0;
      cb.addEventListener('change', function () {
        var now = Array.prototype.filter.call(box.querySelectorAll('input[type=checkbox]'), function (x) { return x.checked; }).map(function (x) { return x.value; });
        change(id, { related: now });
      });
      box.appendChild(el('div', { class: 'pk-check' }, [cb, el('label', { for: cid }, [el('b', { text: o.label }), ' ', el('span', { class: 'text-muted-d', text: '(' + o.path + ': ' + o.why + ')' })])]));
    });
    return box;
  }

  function card(plan, k, S, change) {
    var id = k.origKind || k.kind;
    if (k.derived && k.derived.from) { return derivedCard(plan, k, change); }
    var kindNames = plan.kinds.map(function (x) { return x.kind; });
    var scal = scalarTop(k);
    var c = el('article', { class: 'pk-card' + (k.keyAmbiguous || !k.key ? ' pk-needs' : ''), 'aria-labelledby': 'pkh-' + id });
    var head = el('div', { class: 'pk-card-h' }, [
      el('h3', { id: 'pkh-' + id, class: 'pk-kind', text: k.title || k.kind }),
      el('span', { class: 'bs-role', text: U.plural(k.sutras.length, 'Sutra') })]);
    c.appendChild(head);
    var src = [];
    if (k.source) { src.push('schema ' + k.source); }
    var files = (S.planFiles || []).filter(function (f) { return f.kind === k.kind; });
    if (files.length) { src.push('data ' + files.map(function (f) { return f.name + ' (' + U.num(f.rows) + ' rows, ' + U.num(f.sampled) + ' sampled)'; }).join(', ')); }
    else { src.push('no data: synthetic samples from the schema'); }
    c.appendChild(el('p', { class: 'pk-src text-muted-d', text: src.join(' · ') + ' · kind taken from ' + (k.kindWhy || 'the data file name') }));
    var owner = (plan.conflicts.kinds || {})[k.kind];
    if (owner) {
      c.classList.add('pk-needs');
      c.appendChild(el('p', { class: 'pk-warn', role: 'alert', text: 'The pack "' + owner + '" on the server already defines the kind "' + k.kind + '". If this new pack is "' + owner + '" itself (a new version), that is fine; two unrelated packs may not define the same kind, so otherwise rename this kind below.' }));
    }
    if (k.description) { c.appendChild(el('p', { class: 'pk-desc', text: k.description })); }

    var grid = el('div', { class: 'pk-grid' });
    var nameIn = el('input', { type: 'text', class: 'studio-in mono', value: k.kind, autocomplete: 'off', maxlength: '64', 'data-focus': 'name-' + id });
    nameIn.addEventListener('change', function () { change(id, { kind: nameIn.value.trim() || k.kind }); });
    grid.appendChild(field('Kind name', nameIn, 'Letters, digits, . _ - ; the name of the entity this pack describes.'));

    var keyOpts = [];
    (k.keyCandidates || []).concat(scal.map(function (f) { return f.path; })).forEach(function (p) { if (keyOpts.indexOf(p) < 0) { keyOpts.push(p); } });
    if (k.key && keyOpts.indexOf(k.key) < 0) { keyOpts.unshift(k.key); }
    var keySel = U.select([{ value: '', label: '(choose the key)' }].concat(keyOpts.map(function (p) { return { value: p, label: p }; })), k.key || '', { 'data-focus': 'key-' + id });
    keySel.addEventListener('change', function () { change(id, { key: keySel.value }); });
    grid.appendChild(field('Key field', keySel, k.key ? (k.keyAmbiguous ? 'Ambiguous: ' + (k.keyCandidates || []).join(', ') + ' all look like ids. Choose one.' : 'Chosen because it is ' + k.keyWhy + '.') : 'No field looks like an id: choose the one whose value is different for every document.', k.keyAmbiguous || !k.key ? 'pk-warn' : ''));

    var matchOpts = [{ value: '', label: '(none) one Sutra for the kind' }];
    var cur = (k.match || []).join(',');
    var seen = {};
    (k.matchOptions || []).forEach(function (o) { seen[o.field] = 1; matchOpts.push({ value: o.field, label: o.field + ' (' + U.plural(o.sutras, 'Sutra') + ')' }); });
    if (cur && !seen[cur]) { matchOpts.push({ value: cur, label: cur + ' (' + U.plural(k.sutras.length, 'Sutra') + ')' }); }
    if (k.synthetic) {                       // schemas only: the old control; with data the split box below lists every type
      var matchSel = U.select(matchOpts, cur, { 'data-focus': 'match-' + id });
      matchSel.addEventListener('change', function () { change(id, { match: matchSel.value ? [matchSel.value] : [] }); });
      grid.appendChild(field('Split into Sutras by', matchSel, k.matchWhy + '. A catch-all Sutra is always added.'));
    }

    var dateFields = k.fields.filter(function (f) { return f.path.indexOf('.') < 0 && (f.role === 'date' || f.role === 'datetime' || f.format === 'date' || f.format === 'date-time'); });
    var dateOpts = [{ value: '', label: '(none) no dated store' }];
    if (!k.synthetic) { dateOpts.push({ value: '@snapshot', label: 'Snapshot: the day the file is loaded' }); }
    var dateSel = U.select(dateOpts.concat(dateFields.map(function (f) { return { value: f.path, label: f.path + ' (each document\'s own date)' }; })),
      k.snapshot ? '@snapshot' : (k.date || ''), { 'data-focus': 'date-' + id });
    dateSel.addEventListener('change', function () { change(id, { date: dateSel.value }); });
    grid.appendChild(field('Business date', dateSel, (k.snapshot ? 'Snapshot. ' : '') + (k.dateWhy || '') + (k.dateWhy ? '.' : '')));

    var mn = el('input', { type: 'text', class: 'studio-in mono', value: k.mnemonic, maxlength: '8', autocomplete: 'off', 'data-focus': 'mn-' + id });
    mn.addEventListener('change', function () { change(id, { mnemonic: mn.value.trim().toUpperCase() }); });
    var clash = (plan.conflicts.mnemonics || []).indexOf(k.mnemonic) >= 0;
    grid.appendChild(field('Mnemonic', mn, clash ? 'Another pack already uses ' + k.mnemonic + ': choose another.' : 'Typed before an id: ' + k.mnemonic + ' ' + (k.key ? '<' + k.key + '>' : '<id>') + '.', clash ? 'pk-warn' : ''));
    if (!k.synthetic) { c.appendChild(splitBox(plan, k, change)); }
    c.appendChild(grid);
    if (!k.synthetic && (k.relatedOptions || []).length) { c.appendChild(relatedBox(k, change)); }

    // links
    var links = el('div', { class: 'pk-links' }, [el('h4', { class: 'bs-h2', text: 'Links to other kinds' })]);
    if (!k.links.length) { links.appendChild(el('p', { class: 'text-muted-d', text: 'No field refers to another kind yet.' })); }
    k.links.forEach(function (l) {
      var sel = U.select([{ value: '', label: '(not a link)' }].concat(kindNames.map(function (n) { return { value: n, label: n }; })), l.to, { 'data-focus': 'link-' + id + '-' + l.field, 'aria-label': 'Kind that ' + l.field + ' refers to' });
      sel.addEventListener('change', function () { var ls = {}; ls[l.field] = sel.value || null; change(id, { links: ls }); });
      links.appendChild(el('div', { class: 'pk-link' }, [el('code', { text: l.field }), ' → ', sel, el('span', { class: 'pk-hint text-muted-d', text: l.why })]));
    });
    var linked = k.links.map(function (l) { return l.field; });
    var addable = scal.filter(function (f) { return f.path !== k.key && linked.indexOf(f.path) < 0 && f.type !== 'number'; });
    if (addable.length && kindNames.length) {
      var addF = U.select([{ value: '', label: 'Add a link: field…' }].concat(addable.map(function (f) { return { value: f.path, label: f.path }; })), '', { 'aria-label': 'Field to make a link', 'data-focus': 'addf-' + id });
      var addK = U.select(kindNames.map(function (n) { return { value: n, label: n }; }), kindNames[0], { 'aria-label': 'Kind the new link points at' });
      var addB = el('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Add link' });
      addB.addEventListener('click', function () { if (addF.value) { var ls = {}; ls[addF.value] = addK.value; change(id, { links: ls }); } });
      links.appendChild(el('div', { class: 'pk-link pk-add' }, [addF, ' → ', addK, addB]));
    }
    c.appendChild(links);

    if (k.sutras.length) {
      c.appendChild(el('p', { class: 'pk-sutras text-muted-d' }, ['Sutras: ', el('span', { class: 'mono', text: k.sutras.map(function (s) { return s.name; }).join(', ') })]));
    }
    if (k.controls && k.controls.length) {
      c.appendChild(el('p', { class: 'pk-sutras text-muted-d', text: 'Enumerated fields that could become dropdown controls when the controls feature lands: ' + k.controls.map(function (x) { return x.field; }).join(', ') + '.' }));
    }
    if (k.warnings.length) {
      var ul = el('ul', { class: 'pk-warns', 'aria-label': 'Things to look at for ' + k.kind });
      k.warnings.forEach(function (w) { ul.appendChild(el('li', { text: w })); });
      c.appendChild(ul);
    }
    var det = el('details', { class: 'pk-fields' }, [el('summary', { text: 'Fields (' + k.fields.length + ')' })]);
    var t = el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('thead', {}, [el('tr', {}, ['Path', 'Label', 'Type', 'Format', 'Filled', 'Description'].map(function (h) { return el('th', { text: h }); }))])]);
    var tb = el('tbody');
    k.fields.forEach(function (f) {
      var filled = f.hidden ? 'blank everywhere: not shown' : (f.fill === undefined || k.synthetic ? '' : Math.round(f.fill * 100) + '%');
      tb.appendChild(el('tr', f.hidden ? { class: 'pk-blank' } : {}, [el('td', { class: 'mono', text: f.path + (f.required ? ' *' : '') }), el('td', { text: f.label }), el('td', { text: f.type + (f.enum.length ? ' enum' : '') }),
        el('td', { text: f.fmt || f.format || '' }), el('td', { class: 'num', text: filled }), el('td', { text: f.description })]));
    });
    t.appendChild(tb);
    det.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
    c.appendChild(det);
    return c;
  }

  /** Draws step 2 into `host`. S: the page state; change(originalKind, patch): the page's handler. */
  function render(host, S, change, assignFile) {
    U.clear(host);
    var plan = S.plan;
    if (!plan.kinds.length) { host.appendChild(el('p', { class: 'text-muted-d', text: 'No kind could be read from these files. Go back and add a schema or a data file.' })); return; }
    var top = el('div', { class: 'pk-top' });
    top.appendChild(el('div', {}, [el('h3', { class: 'bs-h2', text: U.plural(plan.kinds.length, 'kind') + ', ' + U.plural(plan.sutraCount, 'Sutra') + ' to create' }), graph(plan),
      plan.edges.length ? el('ul', { class: 'pk-edges', 'aria-label': 'Links' }, plan.edges.map(function (e) { return el('li', {}, [el('code', { text: e.from }), ' → ', el('code', { text: e.to }), ' by ' + e.field]); }))
        : el('p', { class: 'text-muted-d', text: 'No links between the kinds were found.' })]));
    host.appendChild(top);
    if ((S.planFiles || []).length) {
      var kindNames = plan.kinds.map(function (x) { return x.kind; });
      var box = el('div', { class: 'pk-assign' }, [el('h3', { class: 'bs-h2', text: 'Which kind is each data file?' })]);
      S.planFiles.forEach(function (f) {
        var sel = U.select(kindNames.map(function (n) { return { value: n, label: n }; }).concat([{ value: '__new', label: '(a new kind named after the file)' }]), f.kind, { 'aria-label': 'Kind of ' + f.name, 'data-focus': 'file-' + f.name });
        sel.addEventListener('change', function () { assignFile(f.name, sel.value); });
        box.appendChild(el('div', { class: 'pk-link' }, [el('code', { text: f.name }), el('span', { class: 'text-muted-d', text: U.num(f.rows) + ' rows' }), ' → ', sel]));
      });
      host.appendChild(box);
    }
    plan.kinds.forEach(function (k) { host.appendChild(card(plan, k, S, change)); });
  }

  window.DrishtiPackKinds = { render: render, graph: graph };
})();
