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
/* Rupaka phase 3 (RUPAKA.md section 6.2): the Model view of a dataset - tables as boxes with their columns, relationships as lines with their cardinality and
   filter direction, a drag from a column onto another column to relate them, relationships proposed from the data that you accept one by one, and the YAML
   beside it: the diagram and the text are two views of one draft, and an edit in either shows in the other. Positions are kept in the YAML under `layout:`.
   Only the `relationships:` and `layout:` blocks are rewritten by the diagram; the rest of the file (comments included) is left as it is. Same-origin JSON only. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'datasets' || !canvas.querySelector('[data-model-root]')) { return; }
  const B = window.drishtiBi;
  const h = B.h, q = (s, sc) => B.q(s, sc || canvas);
  const itemId = canvas.getAttribute('data-item');
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  const shell = canvas.closest('.bi-shell');
  if (shell) { shell.classList.add('bi-shell-design'); }

  const BOX_W = 224;
  let item = null, model = null, rels = [], layout = {}, editor = null, readOnly = false, dirty = false, selectedRel = -1, validateTimer = null, ignore = false;
  const boxes = new Map();                       // table name -> {el, list}
  const NS = 'http://www.w3.org/2000/svg';
  const inner = q('[data-diagram-inner]');
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'bi-lines');
  svg.setAttribute('aria-hidden', 'true');
  inner.appendChild(svg);
  const ta = q('[data-yaml]');
  const text = () => (editor ? editor.getValue() : ta.value);
  const setText = (t) => { ignore = true; if (editor) { editor.setValue(t); } else { ta.value = t; } ignore = false; };

  // ---- the YAML blocks the diagram owns ------------------------------------------------------------------------------
  const TOP_KEY = /^[A-Za-z_][\w-]*\s*:/;
  function findBlock(lines, key) {
    const start = lines.findIndex((l) => new RegExp('^' + key + '\\s*:').test(l));
    if (start < 0) { return null; }
    let end = start + 1;
    while (end < lines.length && !TOP_KEY.test(lines[end])) { end++; }
    while (end > start + 1 && (lines[end - 1].trim() === '' || /^#/.test(lines[end - 1]))) { end--; }       // blank lines and comments of the next section stay with it
    return { start, end };
  }
  /** Replaces (or adds, before the first later section) the top-level block `key`; an empty list of lines removes it. */
  function putBlock(src, key, blockLines) {
    const lines = src.split('\n');
    const at = findBlock(lines, key);
    if (at) { lines.splice(at.start, at.end - at.start, ...blockLines); } else if (blockLines.length) {
      const order = ['measures', 'hierarchies', 'security', 'rollups', 'layout'];
      let ins = -1;
      for (let i = order.indexOf(key) + 1; i < order.length && ins < 0; i++) { const b = findBlock(lines, order[i]); if (b) { ins = b.start; } }
      if (key === 'relationships') { ins = lines.findIndex((l) => /^(measures|hierarchies|security|rollups|layout)\s*:/.test(l)); }
      if (ins < 0) { while (lines.length && lines[lines.length - 1].trim() === '') { lines.pop(); } lines.push('', ...blockLines, ''); } else { lines.splice(ins, 0, ...blockLines, ''); }
    }
    return lines.join('\n');
  }
  const flowRel = (r) => '  - { from: ' + r.from + ', to: ' + r.to + ', cardinality: ' + r.cardinality + (r.direction && r.direction !== 'single' ? ', direction: ' + r.direction : '') + (r.active === false ? ', active: false' : '') + ' }';
  function writeRels() {
    setText(putBlock(text(), 'relationships', rels.length ? ['relationships:', ...rels.map(flowRel)] : []));
    afterEdit();
  }
  function writeLayout() {
    const names = Object.keys(layout);
    setText(putBlock(text(), 'layout', names.length ? ['layout:                          # where the Model view draws each table (written by the diagram)', ...names.map((n) => '  ' + n + ': { x: ' + layout[n].x + ', y: ' + layout[n].y + ' }')] : []));
    afterEdit();
  }
  function readLayout(src) {
    const out = {};
    const lines = src.split('\n');
    const at = findBlock(lines, 'layout');
    if (at) { lines.slice(at.start + 1, at.end).forEach((l) => { const m = /^\s+([A-Za-z_]\w*)\s*:\s*\{\s*x\s*:\s*(-?\d+)\s*,\s*y\s*:\s*(-?\d+)\s*\}/.exec(l); if (m) { out[m[1]] = { x: +m[2], y: +m[3] }; } }); }
    return out;
  }

  function afterEdit() {
    dirty = true;
    q('[data-state]').textContent = 'Unsaved changes';
    clearTimeout(validateTimer);
    validateTimer = setTimeout(validate, 350);
  }

  // ---- drawing -------------------------------------------------------------------------------------------------------
  /** Where tables go when the YAML says nothing: each group of related tables in a band of its own, its best connected table in the middle, neighbours on both
   *  sides, theirs further out (fewer crossings); tables related to nothing in a last band, side by side. */
  function autoPlace(tables) {
    const adj = {};
    tables.forEach((t) => { adj[t.name] = []; });
    rels.forEach((r) => {
      const a = r.from.split('.')[0], b = r.to.split('.')[0];
      if (adj[a] && adj[b] && a !== b) { adj[a].push(b); adj[b].push(a); }
    });
    const names = tables.map((t) => t.name).sort((x, y) => adj[y].length - adj[x].length);
    const cols = {};
    tables.forEach((t) => { cols[t.name] = t.columns.length; });
    const height = (n) => 52 + Math.min(cols[n] * 24, 224) + 34;                        // a box: its header, its columns (the list scrolls past 14 rem), and a gap
    const placed = {};
    const seen = new Set();
    const COL = BOX_W + 56;
    let y = 24;
    names.filter((n) => adj[n].length).forEach((root) => {
      if (seen.has(root)) { return; }
      const col = { [root]: 0 };
      seen.add(root);
      let frontier = [root], flip = 1;
      while (frontier.length) {
        const next = [];
        frontier.forEach((n) => adj[n].filter((m) => !seen.has(m)).forEach((m) => {
          seen.add(m);
          const side = n === root ? (flip = -flip) : Math.sign(col[n]) || 1;
          col[m] = col[n] + side;
          next.push(m);
        }));
        frontier = next;
      }
      const lo = Math.min(...Object.values(col)), at = {};
      Object.keys(col).sort((x, z) => adj[z].length - adj[x].length).forEach((n) => {
        const c = col[n] - lo;
        at[c] = at[c] === undefined ? y : at[c];
        placed[n] = { x: 24 + c * COL, y: at[c] };
        at[c] += height(n);
      });
      y = Math.max(...Object.values(at));
    });
    names.filter((n) => !seen.has(n)).forEach((n, i) => { placed[n] = { x: 24 + i * COL, y }; });
    return placed;
  }

  function draw() {
    boxes.forEach((b) => b.el.remove());
    boxes.clear();
    const tables = model.tables;
    const auto = autoPlace(tables);
    tables.forEach((t) => {
      const pos = layout[t.name] || auto[t.name];
      const list = h('ul', { role: 'list' });
      t.columns.forEach((c) => {
        const li = h('li', { class: 'bi-col', tabindex: '0', draggable: readOnly ? 'false' : 'true', 'data-column': t.name + '.' + c.name,
          'aria-label': t.name + '.' + c.name + ', ' + c.type + (c.key ? ', key' : '') + (readOnly ? '' : '. Drag onto a column of another table to relate them') },
          B.icon(c.key ? 'key' : c.calculated ? 'calculator' : 'dot'), h('span', { text: c.name }), h('span', { class: 'bi-coltype', text: c.type }));
        li.addEventListener('dragstart', (e) => { e.dataTransfer.setData('application/x-drishti-col', t.name + '.' + c.name); e.dataTransfer.effectAllowed = 'link'; });
        li.addEventListener('dragover', (e) => { if ([...e.dataTransfer.types].indexOf('application/x-drishti-col') >= 0) { e.preventDefault(); li.classList.add('bi-over'); } });
        li.addEventListener('dragleave', () => li.classList.remove('bi-over'));
        li.addEventListener('drop', (e) => {
          li.classList.remove('bi-over');
          const from = e.dataTransfer.getData('application/x-drishti-col');
          if (from) { e.preventDefault(); proposeRelationship(from, t.name + '.' + c.name); }
        });
        li.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !readOnly) { e.preventDefault(); armColumn(t.name + '.' + c.name); } });
        list.appendChild(li);
      });
      const head = h('header', {}, B.icon('table'), h('span', { text: t.name }), h('small', { text: t.kind || '' }));
      const el = h('div', { class: 'bi-tbox', 'data-table': t.name, role: 'group', 'aria-label': 'Table ' + t.name }, head, list);
      el.style.left = pos.x + 'px';
      el.style.top = pos.y + 'px';
      inner.appendChild(el);
      boxes.set(t.name, { el, list, pos: { x: pos.x, y: pos.y } });
      list.addEventListener('scroll', lines);
      dragBox(t.name, head, el);
    });
    lines();
    drawRelList();
    fillSelects();
  }

  function dragBox(name, head, el) {
    head.addEventListener('pointerdown', (e) => {
      if (e.button !== 0 || readOnly) { return; }
      e.preventDefault();
      const b = boxes.get(name), start = { x: e.clientX, y: e.clientY, px: b.pos.x, py: b.pos.y };
      head.setPointerCapture(e.pointerId);
      const move = (ev) => {
        b.pos = { x: Math.max(0, Math.round((start.px + ev.clientX - start.x) / 10) * 10), y: Math.max(0, Math.round((start.py + ev.clientY - start.y) / 10) * 10) };
        el.style.left = b.pos.x + 'px';
        el.style.top = b.pos.y + 'px';
        lines();
      };
      const up = () => {
        head.removeEventListener('pointermove', move);
        head.removeEventListener('pointerup', up);
        if (b.pos.x !== start.px || b.pos.y !== start.py) { layout[name] = b.pos; Object.keys(layout).forEach((k) => { if (!boxes.has(k)) { delete layout[k]; } }); boxes.forEach((bb, n) => { if (!layout[n]) { layout[n] = bb.pos; } }); writeLayout(); }
      };
      head.addEventListener('pointermove', move);
      head.addEventListener('pointerup', up);
    });
  }

  /** The point on a table's box where a column's line starts: the box's edge nearest the other table, at the height of the column. */
  function anchor(col, towards) {
    const [t, c] = [col.split('.')[0], col.split('.').slice(1).join('.')];
    const b = boxes.get(t);
    if (!b) { return null; }
    const li = b.list.querySelector('[data-column="' + col.replace(/"/g, '') + '"]');
    const lo = b.list.offsetTop, hi = b.list.offsetTop + b.list.clientHeight;
    const top = b.pos.y + (li ? Math.min(Math.max(li.offsetTop - b.list.scrollTop + li.offsetHeight / 2, lo), hi) : lo);
    const other = boxes.get(towards.split('.')[0]);
    const right = other && other.pos.x > b.pos.x ? true : other && other.pos.x === b.pos.x ? true : false;
    return { x: b.pos.x + (right ? b.el.offsetWidth : 0), y: top, right };
  }

  function lines() {
    while (svg.firstChild) { svg.removeChild(svg.firstChild); }
    let w = 600, hh = 400;
    boxes.forEach((b) => { w = Math.max(w, b.pos.x + b.el.offsetWidth + 40); hh = Math.max(hh, b.pos.y + b.el.offsetHeight + 40); });
    inner.style.width = w + 'px';
    inner.style.height = hh + 'px';
    svg.setAttribute('width', w);
    svg.setAttribute('height', hh);
    rels.forEach((r, i) => {
      const a = anchor(r.from, r.to), b = anchor(r.to, r.from);
      if (!a || !b) { return; }
      const dx = Math.max(40, Math.abs(b.x - a.x) / 2);
      const d = 'M ' + a.x + ' ' + a.y + ' C ' + (a.x + (a.right ? dx : -dx)) + ' ' + a.y + ', ' + (b.x + (b.right ? dx : -dx)) + ' ' + b.y + ', ' + b.x + ' ' + b.y;
      const g = document.createElementNS(NS, 'g');
      g.setAttribute('data-rel', String(i));
      const path = document.createElementNS(NS, 'path');
      path.setAttribute('d', d);
      path.setAttribute('class', (r.active === false ? 'bi-line-off ' : '') + (i === selectedRel ? 'bi-line-sel' : ''));
      const hit = document.createElementNS(NS, 'path');
      hit.setAttribute('d', d);
      hit.setAttribute('class', 'bi-line-hit');
      hit.addEventListener('click', () => { selectedRel = i; lines(); drawRelList(); });
      g.appendChild(path); g.appendChild(hit);
      const [many1, many2] = ({ 'many-to-one': [true, false], 'one-to-many': [false, true], 'one-to-one': [false, false], 'many-to-many': [true, true] })[r.cardinality] || [true, false];
      const label = (p, many) => {
        const t = document.createElementNS(NS, 'text');
        t.setAttribute('x', p.x + (p.right ? 8 : -8));
        t.setAttribute('y', p.y - 6);
        t.setAttribute('text-anchor', p.right ? 'start' : 'end');
        t.textContent = many ? '*' : '1';
        g.appendChild(t);
      };
      const both = r.direction === 'both';
      label(a, many1);
      label(b, many2);
      const title = document.createElementNS(NS, 'title');
      title.textContent = r.from + ' \u2192 ' + r.to + ': ' + r.cardinality + (both ? ', filters both ways' : ', filters one way') + (r.active === false ? ', inactive' : '');
      g.appendChild(title);
      if (i === selectedRel) {
        const mid = document.createElementNS(NS, 'text');
        mid.setAttribute('x', (a.x + b.x) / 2);
        mid.setAttribute('y', (a.y + b.y) / 2 - 8);
        mid.setAttribute('text-anchor', 'middle');
        mid.textContent = r.cardinality + (both ? ' \u00b7 both ways' : ' \u00b7 one way') + (r.active === false ? ' \u00b7 inactive' : '');
        g.appendChild(mid);
      }
      svg.appendChild(g);
    });
  }

  // ---- relationships: the list, the form, proposals ------------------------------------------------------------------
  function drawRelList() {
    const ul = B.clear(q('[data-rels]'));
    q('[data-no-rels]').hidden = rels.length > 0;
    rels.forEach((r, i) => {
      const card = r.cardinality, dir = r.direction || 'single';
      const sc = h('select', { class: 'studio-select', 'aria-label': 'Cardinality of ' + r.from + ' to ' + r.to, 'data-cardinality': '' }, ['many-to-one', 'one-to-many', 'one-to-one', 'many-to-many'].map((c) => h('option', { value: c, text: c })));
      sc.value = card;
      const sd = h('select', { class: 'studio-select', 'aria-label': 'Filter direction', 'data-direction': '' }, h('option', { value: 'single', text: 'filters one way' }), h('option', { value: 'both', text: 'filters both ways' }));
      sd.value = dir;
      const act = h('input', { type: 'checkbox', 'aria-label': 'Active' });
      act.checked = r.active !== false;
      const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-remove-rel': String(i), text: 'Remove' });
      const li = h('li', { class: 'bi-rel' + (i === selectedRel ? ' bi-selected' : ''), 'data-rel-row': String(i) }, h('code', { text: r.from + ' → ' + r.to }), h('div', { class: 'bi-cond2' }, sc, sd), h('label', { class: 'bi-checkrow' }, act, ' active'), rm);
      if (readOnly) { [sc, sd, act, rm].forEach((x) => { x.disabled = true; }); }
      li.addEventListener('click', (e) => { if (!e.target.closest('select,input,button')) { selectedRel = i; lines(); drawRelList(); } });
      sc.addEventListener('change', () => { r.cardinality = sc.value; writeRels(); draw(); });
      sd.addEventListener('change', () => { r.direction = sd.value; writeRels(); draw(); });
      act.addEventListener('change', () => { r.active = act.checked; writeRels(); draw(); });
      rm.addEventListener('click', () => { rels.splice(i, 1); selectedRel = -1; writeRels(); draw(); say('Relationship removed. The YAML shows the change.'); });
      ul.appendChild(li);
    });
  }

  function colInfo(ref) {
    const [t, ...rest] = ref.split('.');
    const tb = model.tables.find((x) => x.name === t);
    return tb ? tb.columns.find((c) => c.name === rest.join('.')) || null : null;
  }

  /** A drag ended on a column (or the form was filled): show the proposal with a guessed cardinality; nothing is written until Add is pressed. */
  function proposeRelationship(from, to) {
    if (from.split('.')[0] === to.split('.')[0]) { say('A relationship joins two different tables.', true); return; }
    if (rels.some((r) => (r.from === from && r.to === to) || (r.from === to && r.to === from))) { say('These two columns are already related.', true); return; }
    const toCol = colInfo(to), fromCol = colInfo(from);
    const guess = toCol && (toCol.key || toCol.name === 'id') ? (fromCol && fromCol.key && fromCol.name === toCol.name ? 'one-to-one' : 'many-to-one') : fromCol && (fromCol.key || fromCol.name === 'id') ? 'one-to-many' : 'many-to-many';
    q('[data-nr-from-t]').value = from.split('.')[0];
    fillColumns(q('[data-nr-from-c]'), from.split('.')[0]);
    q('[data-nr-from-c]').value = from.split('.').slice(1).join('.');
    q('[data-nr-to-t]').value = to.split('.')[0];
    fillColumns(q('[data-nr-to-c]'), to.split('.')[0]);
    q('[data-nr-to-c]').value = to.split('.').slice(1).join('.');
    q('[data-nr-card]').value = guess;
    q('[data-nr-dir]').value = 'single';
    say('Check the cardinality, then press Add relationship.');
    q('[data-nr-add]').focus();
  }
  let armed = null;
  function armColumn(ref) {
    if (!armed) { armed = ref; say(ref + ' chosen. Choose the column to relate it to and press Enter on it.'); return; }
    const from = armed;
    armed = null;
    proposeRelationship(from, ref);
  }

  function fillColumns(sel, table) {
    B.clear(sel);
    const t = model.tables.find((x) => x.name === table);
    (t ? t.columns : []).forEach((c) => sel.appendChild(h('option', { value: c.name, text: c.name })));
  }
  /** Fills the form's table and column lists from the model, keeping what the person chose (a redraw after an edit must not undo a drag that just filled the form). */
  function fillSelects() {
    ['from', 'to'].forEach((k) => {
      const ts = q('[data-nr-' + k + '-t]'), cs = q('[data-nr-' + k + '-c]');
      const keepT = ts.value, keepC = cs.value;
      B.clear(ts);
      model.tables.forEach((t) => ts.appendChild(h('option', { value: t.name, text: t.name })));
      ts.value = model.tables.some((t) => t.name === keepT) ? keepT : (model.tables[0] ? model.tables[0].name : '');
      fillColumns(cs, ts.value);
      if (keepC && [...cs.options].some((o) => o.value === keepC)) { cs.value = keepC; }
    });
  }

  function bindForm() {
    ['from', 'to'].forEach((k) => q('[data-nr-' + k + '-t]').addEventListener('change', (e) => fillColumns(q('[data-nr-' + k + '-c]'), e.target.value)));
    q('[data-nr-add]').addEventListener('click', () => {
      const from = q('[data-nr-from-t]').value + '.' + q('[data-nr-from-c]').value, to = q('[data-nr-to-t]').value + '.' + q('[data-nr-to-c]').value;
      if (from.split('.')[0] === to.split('.')[0]) { say('A relationship joins two different tables.', true); return; }
      if (rels.some((r) => r.from === from && r.to === to)) { say('That relationship exists.', true); return; }
      rels.push({ from, to, cardinality: q('[data-nr-card]').value, direction: q('[data-nr-dir]').value, active: true });
      selectedRel = rels.length - 1;
      writeRels();
      draw();
      say('Added ' + from + ' → ' + to + '. It is in the YAML; Save keeps it.');
    });
    q('[data-infer]').addEventListener('click', infer);
  }

  function infer() {
    const kinds = [...new Set(model.tables.map((t) => t.kind).filter(Boolean))];
    const list = B.clear(q('[data-proposals]'));
    say('Looking at the data…');
    B.api('POST', '/model/infer', { kinds }).then((d) => {
      say(d.proposals.length ? d.proposals.length + ' proposed from the data. Nothing changes until you accept one.' : 'No relationships to propose: the packs’ links and the data match what you have.');
      d.proposals.forEach((p) => {
        const norm = (k) => String(k || '').replace(/-/g, '_');           // the proposals name a table as its kind with underscores
        const ft = model.tables.find((t) => norm(t.kind) === p.fromTable || t.name === p.fromTable), tt = model.tables.find((t) => norm(t.kind) === p.toTable || t.name === p.toTable);
        const from = ft ? ft.name + '.' + p.fromColumn : null, to = tt ? tt.name + '.' + p.toColumn : null;
        const have = rels.some((r) => r.from === from && r.to === to);
        const ok = from && to && colInfo(from) && (colInfo(to) || /\.id$/.test(to)) && !have;
        const acc = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-accept': p.fromTable + '.' + p.fromColumn, text: have ? 'Already there' : ok ? 'Accept' : 'Column not in your table', disabled: !ok || readOnly });
        acc.addEventListener('click', () => {
          rels.push({ from, to, cardinality: p.cardinality || 'many-to-one', direction: 'single', active: true });
          writeRels();
          draw();
          acc.disabled = true;
          acc.textContent = 'Accepted';
        });
        list.appendChild(h('li', { class: 'bi-rel', 'data-proposal': p.fromTable + '.' + p.fromColumn }, h('code', { text: (from || p.fromTable + '.' + p.fromColumn) + ' → ' + (to || p.toTable + '.' + p.toColumn) }),
          h('small', { class: 'text-muted-d', text: Math.round(p.matchRate * 100) + '% of ' + p.sampled + ' values match (' + (p.cardinality || '') + ')' }), acc));
      });
    }).catch((e) => say(e.message, true));
  }

  // ---- the model from the YAML ---------------------------------------------------------------------------------------
  function validate() {
    B.api('POST', '/datasets/validate', { yaml: text() }).then((r) => {
      const box = B.clear(q('[data-problems]'));
      (r.problems || []).forEach((p) => box.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
      if (r.model) {
        model = { tables: r.model.tables };
        rels = r.model.relationships.map((x) => ({ from: x.from, to: x.to, cardinality: x.cardinality, direction: x.direction, active: x.active }));
        layout = readLayout(text());
        draw();
      }
      q('[data-valid]').textContent = r.valid ? 'Valid' : 'Has errors';
    }).catch((e) => say(e.message, true));
  }

  function save() {
    B.api('PUT', '/items/' + B.id2path(itemId), { body: text() }).then((it) => { item = it; dirty = false; q('[data-state]').textContent = 'Saved'; say('Saved as a draft. Publish it from the item page.'); }).catch((e) => say(e.message, true));
  }
  function saveCopy() {
    B.api('POST', '/workspaces/me/items', { kind: 'dataset', title: (item.title || 'Dataset') + ' (copy)', description: item.description || '', body: text() }).then((it) => { window.location.href = '/bi/m/' + B.id2path(it.id); }).catch((e) => say(e.message, true));
  }

  // ---- start ---------------------------------------------------------------------------------------------------------
  B.api('GET', '/items/' + B.id2path(itemId)).then((it) => {
    if (it.kind !== 'dataset') { throw new Error('This item is not a dataset.'); }
    item = it;
    readOnly = !!it.shipped || !(it.can && it.can.edit);
    q('[data-title]').textContent = it.title;
    q('[data-dataset-link]').href = '/bi/d/' + B.id2path(itemId);
    q('[data-readonly]').hidden = !readOnly;
    q('[data-save-copy]').hidden = !readOnly;
    q('[data-save]').disabled = readOnly;
    if (readOnly) { q('[data-add-wrap]').hidden = true; q('[data-infer]').hidden = true; }
    q('[data-state]').textContent = readOnly ? 'Read-only (shipped)' : 'Saved';
    ta.value = it.body || '';
    if (window.CodeMirror) {
      editor = window.CodeMirror.fromTextArea(ta, { mode: 'rachana-yaml', lineNumbers: true, lineWrapping: true, indentUnit: 2, tabSize: 2, readOnly, viewportMargin: Infinity });
      editor.on('change', () => { if (!ignore) { afterEdit(); } });
    } else { ta.addEventListener('input', () => { if (!ignore) { afterEdit(); } }); ta.readOnly = readOnly; }
    q('[data-save]').addEventListener('click', save);
    q('[data-save-copy]').addEventListener('click', saveCopy);
    bindForm();
    window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
    window.addEventListener('resize', lines);
    dirty = false;
    return B.api('POST', '/datasets/validate', { yaml: it.body || '' });
  }).then((r) => {
    const box = q('[data-problems]');
    (r.problems || []).forEach((p) => box.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
    q('[data-valid]').textContent = r.valid ? 'Valid' : 'Has errors';
    if (r.model) {
      model = { tables: r.model.tables };
      rels = r.model.relationships.map((x) => ({ from: x.from, to: x.to, cardinality: x.cardinality, direction: x.direction, active: x.active }));
      layout = readLayout(text());
      draw();
    } else { say('The YAML has errors the diagram cannot draw past; fix them in the YAML.', true); }
    q('[data-model-root]').setAttribute('data-ready', 'yes');
  }).catch((e) => say(e.message, true));
})();
