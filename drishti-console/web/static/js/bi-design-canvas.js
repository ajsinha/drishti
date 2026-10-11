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
/* Rupaka phase 3 (RUPAKA.md section 9): the designer's canvas - the page's visuals on the 12-column grid, drawn with real data (and the time each query took),
   dragged and resized with the pointer and with the keyboard, and the rules that put a field into a visual: a field dropped on the canvas makes the best
   visual for it; dropped on a visual (or its well) it joins that visual. The same wells logic serves the Fields pane's Add buttons. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;
  const MIME = 'application/x-drishti-field';

  D.modules.push(function () {
    const grid = D.q('[data-canvas-grid]');
    const empty = D.q('[data-canvas-empty]');
    const frames = new Map();                    // visual id -> {frame, key, token, res}
    D.timings = new Map();

    // ---- fields into wells -------------------------------------------------------------------------------------------
    D.kindOf = (name) => (D.idx.measures[name] ? 'measure' : 'field');
    D.isDate = (field) => { const c = B.column(D.idx, field); return !!c && (!!c.part || c.type === 'date' || c.type === 'timestamp'); };
    D.wellSpecs = (type) => (D.catalogue.visuals[type] || { wells: {} }).wells;
    const accepts = (spec, kind) => spec.kind === 'any' || spec.kind === kind;

    /** Puts `name` in the visual's well (replacing the item when the well holds one and is full). */
    D.putInWell = function (v, wellName, name) {
      const spec = D.wellSpecs(v.type)[wellName];
      if (!spec || !accepts(spec, D.kindOf(name))) { return false; }
      const list = (v.wells[wellName] = v.wells[wellName] || []);
      if (list.indexOf(name) >= 0) { return true; }
      if (spec.max === 1) { list.splice(0, list.length, name); } else if (spec.max > 0 && list.length >= spec.max) { list.splice(spec.max - 1, 1, name); } else { list.push(name); }
      return true;
    };

    /** The first well, in the type's own order, that takes this kind and has room; else the first that takes it. */
    D.bestWell = function (type, wells, name) {
      const specs = D.wellSpecs(type), kind = D.kindOf(name);
      const names = Object.keys(specs).filter((w) => accepts(specs[w], kind));
      const room = names.find((w) => !specs[w].max || (wells[w] || []).length < specs[w].max);
      return room || names[0] || null;
    };

    D.addToVisual = function (v, name) {
      const w = D.bestWell(v.type, v.wells, name);
      if (!w) { return false; }
      return D.putInWell(v, w, name);
    };

    /** A better type for a visual made by dropping fields, as the fields arrive. */
    function autoRetype(v, extra) {
      if (!D.auto.has(v.id)) { return; }
      const all = Object.keys(v.wells).reduce((a, k) => a.concat(v.wells[k]), []).concat(extra ? [extra] : []).filter((x, i, a) => a.indexOf(x) === i);
      const fields = all.filter((x) => D.kindOf(x) === 'field'), measures = all.filter((x) => D.kindOf(x) === 'measure');
      let type = v.type, wells = null;
      if (!fields.length && measures.length) { type = measures.length === 1 ? 'card' : 'multirow-card'; wells = { values: measures }; }
      else if (fields.length === 1 && measures.length) { type = D.isDate(fields[0]) ? 'line' : 'bar'; wells = { axis: fields, values: measures }; }
      else if (fields.length === 2 && measures.length && !D.isDate(fields[0])) { type = 'matrix'; wells = { rows: [fields[0]], columns: [fields[1]], values: measures }; }
      else if (fields.length && !measures.length) { type = 'table'; wells = { columns: fields }; }
      if (wells && (type !== v.type || JSON.stringify(wells) !== JSON.stringify(v.wells))) {
        const was = v.type;
        v.type = type;
        v.wells = wells;
        if (type !== was) {
          const [w, hh] = D.defaultSize(type);
          v.at.w = w;
          v.at.h = hh;
          v.at.x = Math.min(v.at.x, 12 - w);
          if (v.id.indexOf(was + '-') === 0) { const old = v.id; v.id = D.newVisualId(type); D.auto.delete(old); D.auto.add(v.id); }
          D.settle(D.page(), v);
        }
      }
    }

    /** A field dropped, or added: into the given visual, else the selected one when `useSelected`, else a new best visual at the cell. */
    D.addField = function (name, kind, opt) {
      opt = opt || {};
      const target = opt.visual ? D.visual(opt.visual) : opt.useSelected ? D.sel() : null;
      if (kind === 'hierarchy') {
        if (!target) { D.say('Choose a visual first, then add the hierarchy.', true); return; }
        D.change('Set the hierarchy', () => { target.hierarchy = name; });
        return;
      }
      if (target && target.type !== 'text' && target.type !== 'image' && target.type !== 'button') {
        const done = D.change('Add ' + B.label(name), () => { if (D.auto.has(target.id)) { autoRetype(target, name); } else { D.addToVisual(target, name); } });
        if (!done) { D.say(B.label(name) + ' does not fit a ' + target.type + ': try another visual or well.', true); }
        D.select(target.id);
        return;
      }
      const type = D.kindOf(name) === 'measure' ? 'card' : 'table';
      const tmp = { type, wells: {} };
      D.addToVisual(tmp, name);
      const v = D.addVisual(type, opt.x, opt.y, tmp.wells, 'Add ' + B.label(name));
      if (v) { D.auto.add(v.id); }
    };

    D.removeFromWell = function (v, wellName, name) {
      D.change('Remove ' + B.label(name), () => { v.wells[wellName] = (v.wells[wellName] || []).filter((x) => x !== name); if (!v.wells[wellName].length) { delete v.wells[wellName]; } D.auto.delete(v.id); });
    };

    // ---- drawing -----------------------------------------------------------------------------------------------------
    const metrics = () => {
      const w = grid.clientWidth;
      const col = (w - 11 * B.GAP_PX) / 12;
      return { stepX: col + B.GAP_PX, stepY: B.ROW_PX + B.GAP_PX, col };
    };

    function place(el, at) {
      el.style.gridColumn = (at.x + 1) + ' / span ' + at.w;
      el.style.gridRow = (at.y + 1) + ' / span ' + at.h;
    }

    function previewKey(v) {
      const p = D.page();
      const c = Object.assign({}, v);
      delete c.at;
      return JSON.stringify({ c, pf: p.filters || [], rf: D.model.filters || [], d: D.model.dates || null, ds: D.model.dataset });
    }

    function describe(v) {
      const parts = Object.keys(v.wells || {}).map((w) => w + ': ' + v.wells[w].map(B.label).join(', '));
      return parts.join(' · ') || 'Drop a field here';
    }

    function ctxFor(v) {
      return { index: D.idx, palette: D.palette, t: D.tokens, interactive: false, selected: null, slicer: null, onMark: null, navigate: null };
    }

    function drawEntry(id) {
      const e = frames.get(id);
      const v = D.visual(id);
      if (!e || !v) { return; }
      const group = (D.catalogue.visuals[v.type] || {}).group;
      const spec = Object.assign({}, v, { title: v.title || '' });
      if (group === 'static') { e.frame.result(null, (host) => B.visuals.draw(host, spec, ctxFor(v), null)); return; }
      const missing = Object.keys(D.wellSpecs(v.type)).filter((w) => D.wellSpecs(v.type)[w].required && !(v.wells[w] || []).length);
      if (missing.length) {
        B.clear(e.frame.body).appendChild(h('div', { class: 'bi-vis-empty bi-dropnote', role: 'status' }, B.icon('plus-circle'), ' Add a ' + (D.wellSpecs(v.type)[missing[0]].kind === 'measure' ? 'measure' : 'field') + ' to the ' + missing[0] + ' well', h('br'), describe(v)));
        e.frame.foot.textContent = '';
        e.frame.el.classList.remove('bi-vis-busy');
        return;
      }
      if (!D.preview) {
        B.clear(e.frame.body).appendChild(h('div', { class: 'bi-vis-empty' }, B.icon('eye-slash'), ' Preview is off · ' + v.type, h('br'), describe(v)));
        e.frame.foot.textContent = 'No query run';
        return;
      }
      const token = ++e.token;
      e.frame.busy();
      B.enqueue(() => B.api('POST', '/reports/query', { report: D.model, page: D.pageId, visual: id, state: {} })).then((res) => {
        if (token !== e.token) { return; }
        D.timings.set(id, res.meta.millis);
        e.frame.result(res, (host) => B.visuals.draw(host, spec, ctxFor(v), res));
      }, (err) => { if (token === e.token) { e.frame.error(err); } });
    }

    function makeFrame(v) {
      const del = h('button', { type: 'button', class: 'bi-vis-btn', 'aria-label': 'Delete this visual', title: 'Delete', 'data-act': 'delete' }, B.icon('trash'));
      const dup = h('button', { type: 'button', class: 'bi-vis-btn', 'aria-label': 'Duplicate this visual', title: 'Duplicate', 'data-act': 'duplicate' }, B.icon('files'));
      const f = B.frame(v, { buttons: [dup, del] });
      f.el.classList.add('bi-dvis');
      f.el.tabIndex = 0;
      f.head.classList.add('bi-dhandle');
      const rs = h('div', { class: 'bi-resize', 'aria-hidden': 'true', 'data-resize': '' });
      f.el.appendChild(rs);
      del.addEventListener('click', (e) => { e.stopPropagation(); remove(v.id); });
      dup.addEventListener('click', (e) => { e.stopPropagation(); duplicate(v.id); });
      f.el.addEventListener('click', () => { if (D.selected !== v.id) { D.select(v.id); } });
      f.el.addEventListener('focus', () => { if (D.selected !== v.id) { D.select(v.id); } });
      f.el.addEventListener('keydown', (e) => keys(v.id, e));
      pointerDrag(f.el, f.head, v.id, 'move');
      pointerDrag(f.el, rs, v.id, 'resize');
      f.el.addEventListener('dragover', (e) => { if (e.dataTransfer && [...e.dataTransfer.types].indexOf(MIME) >= 0) { e.preventDefault(); f.el.classList.add('bi-drop'); } });
      f.el.addEventListener('dragleave', () => f.el.classList.remove('bi-drop'));
      f.el.addEventListener('drop', (e) => {
        f.el.classList.remove('bi-drop');
        const p = payload(e);
        if (p) { e.preventDefault(); e.stopPropagation(); D.addField(p.name, p.kind, { visual: v.id }); }
      });
      return f;
    }

    function render() {
      const page = D.page();
      const ids = new Set(page.visuals.map((v) => v.id));
      frames.forEach((e, id) => { if (!ids.has(id) || e.page !== page.id) { e.frame.dispose(); e.frame.el.remove(); frames.delete(id); } });
      page.visuals.slice().sort((a, b) => a.at.y - b.at.y || a.at.x - b.at.x).forEach((v) => {
        let e = frames.get(v.id);
        if (e && e.type !== v.type) { e.frame.dispose(); e.frame.el.remove(); frames.delete(v.id); e = null; }          // another type is another frame (its class and its look)
        if (!e) {
          e = { frame: makeFrame(v), key: null, token: 0, page: page.id, type: v.type };
          frames.set(v.id, e);
          grid.appendChild(e.frame.el);
        }
        place(e.frame.el, v.at);
        e.frame.el.classList.toggle('bi-selected', D.selected === v.id);
        e.frame.el.setAttribute('aria-label', (v.title || v.type) + ' (' + v.type + '), column ' + (v.at.x + 1) + ' row ' + (v.at.y + 1) + ', ' + v.at.w + ' wide, ' + v.at.h + ' high. Arrow keys move it, Shift with arrows resizes it, Delete removes it');
        e.frame.title.textContent = v.title || '';
        const key = previewKey(v) + '|' + D.preview;
        if (e.key !== key) { e.key = key; drawEntry(v.id); }
      });
      empty.hidden = page.visuals.length > 0;
      D.emit('rendered');
    }
    D.on('model', render);
    D.on('page', render);
    D.on('preview', render);
    D.on('select', () => { frames.forEach((e, id) => e.frame.el.classList.toggle('bi-selected', D.selected === id)); });
    if (window.ResizeObserver) { new ResizeObserver(() => frames.forEach((e) => e.frame.resize())).observe(grid); }

    // ---- actions on a visual -----------------------------------------------------------------------------------------
    function remove(id) {
      const next = D.page().visuals.filter((v) => v.id !== id)[0];
      D.change('Delete a visual', (m) => { const p = m.pages.find((x) => x.id === D.pageId); p.visuals = p.visuals.filter((v) => v.id !== id); });
      D.select(next ? next.id : null);
      D.say('Visual deleted. Undo brings it back.');
    }
    function duplicate(id) {
      let made = null;
      D.change('Duplicate a visual', (m) => {
        const p = m.pages.find((x) => x.id === D.pageId);
        const src = p.visuals.find((v) => v.id === id);
        made = B.clone(src);
        made.id = D.newVisualId(src.type);
        made.at.y = src.at.y + src.at.h;
        p.visuals.push(made);
        D.settle(p, made);
      });
      if (made) { D.select(made.id); }
    }
    D.removeVisual = remove;

    function move(id, dx, dy, dw, dh) {
      D.change('Move a visual', (m) => {
        const p = m.pages.find((x) => x.id === D.pageId);
        const v = p.visuals.find((x) => x.id === id);
        v.at.w = Math.max(1, Math.min(12, v.at.w + dw));
        v.at.h = Math.max(1, Math.min(40, v.at.h + dh));
        v.at.x = Math.max(0, Math.min(12 - v.at.w, v.at.x + dx));
        v.at.y = Math.max(0, v.at.y + dy);
        D.settle(p, v);
      });
    }

    function keys(id, e) {
      if (e.target !== e.currentTarget) { return; }
      const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
      if (step) {
        e.preventDefault();
        if (e.shiftKey) { move(id, 0, 0, step[0], step[1]); } else { move(id, step[0], step[1], 0, 0); }
        D.say((e.shiftKey ? 'Resized to ' : 'Moved to column ') + (e.shiftKey ? D.visual(id).at.w + ' by ' + D.visual(id).at.h : (D.visual(id).at.x + 1) + ', row ' + (D.visual(id).at.y + 1)) + '.');
        const el = frames.get(id) && frames.get(id).frame.el;
        if (el) { el.focus(); }
      } else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); remove(id); }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd') { e.preventDefault(); duplicate(id); }
      else if (e.key === 'Enter') { e.preventDefault(); const t = D.q('[data-format-title]'); if (t) { t.focus(); } }
    }

    // ---- the pointer: move by the header, resize by the corner --------------------------------------------------------
    function pointerDrag(el, handle, id, mode) {
      handle.addEventListener('pointerdown', (e) => {
        if (e.button !== 0 || e.target.closest('button')) { return; }
        const v = D.visual(id);
        if (!v || D.readOnly) { return; }
        e.preventDefault();
        D.select(id);
        const m = metrics();
        const start = { x: e.clientX, y: e.clientY, at: Object.assign({}, v.at) };
        let at = Object.assign({}, v.at);
        handle.setPointerCapture(e.pointerId);
        el.classList.add('bi-dragging');
        const onMove = (ev) => {
          const cx = Math.round((ev.clientX - start.x) / m.stepX), cy = Math.round((ev.clientY - start.y) / m.stepY);
          if (mode === 'move') { at = { x: Math.max(0, Math.min(12 - start.at.w, start.at.x + cx)), y: Math.max(0, start.at.y + cy), w: start.at.w, h: start.at.h }; }
          else { at = { x: start.at.x, y: start.at.y, w: Math.max(1, Math.min(12 - start.at.x, start.at.w + cx)), h: Math.max(1, Math.min(40, start.at.h + cy)) }; }
          place(el, at);
        };
        const onUp = () => {
          handle.removeEventListener('pointermove', onMove);
          handle.removeEventListener('pointerup', onUp);
          handle.removeEventListener('pointercancel', onUp);
          el.classList.remove('bi-dragging');
          if (!B.same(at, start.at)) {
            D.change(mode === 'move' ? 'Move a visual' : 'Resize a visual', (mm) => {
              const p = mm.pages.find((x) => x.id === D.pageId);
              const live = p.visuals.find((x) => x.id === id);
              live.at = at;
              D.settle(p, live);
            });
          } else { place(el, start.at); }
        };
        handle.addEventListener('pointermove', onMove);
        handle.addEventListener('pointerup', onUp);
        handle.addEventListener('pointercancel', onUp);
      });
    }

    // ---- drops on the canvas -----------------------------------------------------------------------------------------
    function payload(e) {
      try { const t = e.dataTransfer.getData(MIME); return t ? JSON.parse(t) : null; } catch (err) { return null; }
    }
    grid.addEventListener('dragover', (e) => { if (e.dataTransfer && [...e.dataTransfer.types].indexOf(MIME) >= 0) { e.preventDefault(); } });
    grid.addEventListener('drop', (e) => {
      const p = payload(e);
      if (!p) { return; }
      e.preventDefault();
      const r = grid.getBoundingClientRect(), m = metrics();
      D.addField(p.name, p.kind, { x: Math.max(0, Math.min(11, Math.floor((e.clientX - r.left) / m.stepX))), y: Math.max(0, Math.floor((e.clientY - r.top) / m.stepY)) });
    });
    D.MIME = MIME;
  });
})();
