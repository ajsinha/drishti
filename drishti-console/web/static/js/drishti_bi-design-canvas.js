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
/* Rupaka phase 3 (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 9): the designer's canvas - the page's visuals on the 12-column grid, drawn with real data (and the time each query took),
   and the rules that put a field into a visual: a field dropped on the canvas makes the best visual for it; dropped on a visual (or its well) it joins that
   visual. The same wells logic serves the Fields pane's Add buttons. The canvas itself is drishti-designer's LayoutCanvas (grid): selecting (Shift or Ctrl for
   more), moving by the heading and resizing by the corner with the pointer, snapped to the grid, and the keys (arrows move, Shift+arrows resize, Delete,
   Ctrl+D, Enter to Format); each move is one step of the designer's undo, settled as a report page settles. */
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
    /** What a field parameter of the model (param.<name>, 2.6.0) holds: 'measure' when every choice is a measure, 'field' when every one is a field, else 'any'. */
    D.paramKind = (name) => {
      const p = ((D.dataset && D.dataset.parameters) || []).find((x) => x.kind === 'field' && 'param.' + x.name === name);
      if (!p) { return null; }
      const ms = (p.fields || []).map((f) => !!(D.curIdx || D.idx).measures[f]);
      return ms.every(Boolean) ? 'measure' : ms.some(Boolean) ? 'any' : 'field';
    };
    D.kindOf = (name) => (String(name).indexOf('param.') === 0 && D.paramKind(name) ? D.paramKind(name) : (D.curIdx || D.idx).measures[name] ? 'measure' : 'field');
    D.isDate = (field) => { const c = B.column(D.curIdx || D.idx, field); return !!c && (!!c.part || c.type === 'date' || c.type === 'timestamp'); };
    D.wellSpecs = (type) => (D.catalogue.visuals[type] || { wells: {} }).wells;
    const accepts = (spec, kind) => spec.kind === 'any' || spec.kind === kind;          // a field parameter of fields and measures ('any') goes in a well of either only

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
      if (!D.auto.has(v.id)) { return false; }
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
          D.followType(v, type);
          D.settle(D.page(), v);
        }
      }
      return !!wells;
    }

    /** Sets the dataset a visual reads: blank when it is the report's main one. */
    function setDs(v, ref) { if (!ref || !D.isOtherRef(ref)) { delete v.dataset; } else { v.dataset = ref; } }

    /** A field dropped, or added: into the given visual, else the selected one when `useSelected`, else a new best visual at the cell. `opt.dataset` is the
        dataset the field belongs to ('' for the report's main one). A visual that already holds fields reads one dataset: a field of another is refused. */
    D.addField = function (name, kind, opt) {
      opt = opt || {};
      let target = opt.visual ? D.visual(opt.visual) : opt.useSelected ? D.sel() : null;
      // a second measure added from the Fields list (a click or Enter) while a card is selected makes a card of its own beside it: one card, one
      // number. Dropping it onto the card's well still adds it there (a multi-row card), on purpose.
      if (!opt.visual && target && target.type === 'card' && kind === 'measure' && ((target.wells || {}).values || []).length) {
        const beside = target;
        target = null;
        if (opt.x == null) { opt = Object.assign({}, opt, { x: Math.min(11, beside.at.x + beside.at.w) % 12, y: beside.at.y }); }
      }
      const from = opt.dataset || '';
      const where = target ? 'the ' + target.type + ' ' + (target.title ? '"' + target.title + '"' : target.id) : 'the canvas' + (opt.x != null ? ' (column ' + (opt.x + 1) + ', row ' + (opt.y + 1) + ')' : '');
      D.curIdx = D.idxForRef(from);
      try {
        if (kind === 'hierarchy') {
          if (!target) { D.say('Drop a hierarchy onto a visual, not onto ' + where + '.', true); return; }
          D.change('Set the hierarchy', () => { target.hierarchy = name; });
          return;
        }
        if (target && target.type !== 'text' && target.type !== 'image' && target.type !== 'button') {
          const has = Object.keys(target.wells || {}).some((w) => (target.wells[w] || []).length);
          const clash = D.dsConflict(target, from);
          if (clash) {
            D.say(clash, true);
            D.select(target.id);
            return;
          }
          let fits = true;
          const done = D.change('Add ' + B.label(name), () => {
            const was = target.dataset;
            if (!has) { setDs(target, from); }
            D.curIdx = D.idxForRef(from);
            const handled = D.auto.has(target.id) && autoRetype(target, name);
            if (!handled) { fits = D.addToVisual(target, name); }
            if (!handled && !fits) { if (was === undefined) { delete target.dataset; } else { target.dataset = was; } }      // a refused drop changes nothing: the draft is not marked unsaved
          });
          if (!done) {
            const present = Object.keys(target.wells || {}).some((w) => (target.wells[w] || []).indexOf(name) >= 0);
            const wells = Object.keys(D.wellSpecs(target.type)).join(', ');
            D.say(present ? B.label(name) + ' is already in ' + where + '.'
              : B.label(name) + ' does not fit ' + where + ' (' + (fits ? 'nothing changed' : 'its wells are ' + wells + ', and none takes a ' + D.kindOf(name)) + '): drop it onto a visual\'s well, or onto an empty part of the canvas to start a new visual.', !present);
          }
          D.select(target.id);
          return;
        }
        if (target) { D.say(B.label(name) + ' cannot go into ' + where + ': it takes no fields. Drop it onto a chart, card or table, or onto the canvas.', true); return; }
        const type = D.kindOf(name) === 'measure' ? 'card' : 'table';
        const tmp = { type, wells: {} };
        D.addToVisual(tmp, name);
        const v = D.addVisual(type, opt.x, opt.y, tmp.wells, 'Add ' + B.label(name));
        if (v) { D.auto.add(v.id); if (D.isOtherRef(from)) { D.change('Set the dataset', () => { const mv = D.visual(v.id); if (mv) { setDs(mv, from); } }); } }
      } finally { D.curIdx = null; }
    };

    D.removeFromWell = function (v, wellName, name) {
      D.change('Remove ' + B.label(name), () => { v.wells[wellName] = (v.wells[wellName] || []).filter((x) => x !== name); if (!v.wells[wellName].length) { delete v.wells[wellName]; } D.auto.delete(v.id); });
    };

    // ---- drawing -----------------------------------------------------------------------------------------------------

    function previewKey(v) {
      const p = D.page();
      const c = Object.assign({}, v);
      delete c.at;
      return JSON.stringify({ c, pf: p.filters || [], rf: D.model.filters || [], d: D.model.dates || null, ds: D.model.dataset, dv: D.dsVersion || 0 });
    }

    function describe(v) {
      const parts = Object.keys(v.wells || {}).map((w) => w + ': ' + v.wells[w].map(B.label).join(', '));
      return parts.join(' · ') || 'Drop a field here';
    }

    function ctxFor(v) {
      return { index: D.idxOf(v), palette: D.palette, t: D.tokens, interactive: false, selected: null, slicer: null, onMark: null, navigate: null, theme: D.theme };
    }

    function drawEntry(id) {
      const e = frames.get(id);
      const v = D.visual(id);
      if (!e || !v) { return; }
      const group = (D.catalogue.visuals[v.type] || {}).group;
      const spec = B.theme.spec(Object.assign({}, v, { title: v.title || '' }), D.theme, D.families);          // the theme's visual defaults under the visual's own format (2.4.0)
      B.visuals.frameFormat(e.frame, spec);                                                                    // the frame of the format (subtitle, border ...) follows each change
      if (group === 'board') { D.tilePreview(e.frame, v); return; }                                            // a tile (R7): its report's visual, asked as the author
      if (group === 'static' || group === 'entity') { e.frame.result(null, (host) => B.visuals.draw(host, spec, ctxFor(v), null)); return; }
      if (group === 'python') {                                           // Python runs when the report is viewed (server or browser), never while designing
        const item = (v.format && v.format.python) || ((v.wells.values || [])[0] ? 'the measure ' + v.wells.values[0] : '');
        B.clear(e.frame.body).appendChild(h('div', { class: 'bi-vis-empty bi-dropnote', role: 'status', 'data-py-placeholder': '' }, B.icon('filetype-py'), ' Python visual' + (item ? ' · ' + item : ''), h('br'),
          item ? 'It runs when the report is opened, on the rows the viewer may see.' : 'Name the Python item in Format, or put a Python measure in the values well.'));
        e.frame.foot.textContent = '';
        e.frame.el.classList.remove('bi-vis-busy');
        return;
      }
      if (v.type === 'vega-lite' && !(v.format && v.format.spec) && !(v.wells && Object.keys(v.wells).some((w) => (v.wells[w] || []).length))) {
        B.clear(e.frame.body).appendChild(h('div', { class: 'bi-vis-empty bi-dropnote', role: 'status', 'data-vega-placeholder': '' }, B.icon('code-slash'), ' Vega-Lite visual needs a specification and a field', h('br'),
          'Drop a field or a measure on it for its data, and write the specification in Format (spec) or the YAML tab.'));
        e.frame.foot.textContent = '';
        e.frame.el.classList.remove('bi-vis-busy');
        return;
      }
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
      B.enqueue(() => previewQuery(id)).then((res) => {
        if (token !== e.token) { return; }
        D.timings.set(id, res.meta.millis);
        (D.lastMq = D.lastMq || new Map()).set(id, res.mq || null);                       // what the Studio's Query pane shows for the selected visual
        D.emit('query', id);                                                                 // the Query pane redraws: the answer may come after the visual was selected
        e.frame.result(res, (host) => B.visuals.draw(host, spec, ctxFor(v), res));
      }, (err) => { if (token === e.token) { e.frame.error(err); } });
    }

    /** True when a visual cannot be asked yet: a required well is empty. The server refuses a report with any error, so the preview leaves such visuals out. */
    function incomplete(v) {
      const specs = D.wellSpecs(v.type) || {};
      return Object.keys(specs).some((w) => specs[w].required && !((v.wells || {})[w] || []).length);
    }

    /** The preview asks for one visual, with the incomplete visuals left out, so one unfinished visual does not blank the others. If the server names another
        visual's problem (a field its dataset lacks), that visual is left out too and the question is asked again. The published view draws every visual that has no error of its own. */
    function previewQuery(id) {
      const skipped = new Set();
      const attempt = (n) => {
        const copy = JSON.parse(JSON.stringify(D.model));
        copy.pages.forEach((pg) => { pg.visuals = pg.visuals.filter((x) => x.id === id || (!skipped.has(x.id) && !incomplete(x))); });
        return B.api('POST', '/reports/query', { report: copy, page: D.pageId, visual: id, state: {} }).catch((err) => {
          const m = /pages\[(\d+)\]\.visuals\[(\d+)\]/.exec(err.message || '');
          const other = m && copy.pages[+m[1]] && copy.pages[+m[1]].visuals[+m[2]];
          if (other && other.id !== id && n < 6) { skipped.add(other.id); return attempt(n + 1); }
          throw err;
        });
      };
      return attempt(0);
    }

    function makeFrame(v) {
      const del = h('button', { type: 'button', class: 'bi-vis-btn', 'aria-label': 'Delete this visual', title: 'Delete', 'data-act': 'delete' }, B.icon('trash'));
      const dup = h('button', { type: 'button', class: 'bi-vis-btn', 'aria-label': 'Duplicate this visual', title: 'Duplicate', 'data-act': 'duplicate' }, B.icon('files'));
      const f = B.frame(v, { buttons: [dup, del], onRetry: () => { const e = frames.get(v.id); if (e) { e.key = null; drawEntry(v.id); } } });          // a design error card has Retry too: fix the model, then ask again
      f.el.classList.add('bi-dvis');
      f.head.classList.add('bi-dhandle');
      del.addEventListener('click', (e) => { e.stopPropagation(); remove(v.id); });
      dup.addEventListener('click', (e) => { e.stopPropagation(); duplicate(v.id); });
      return f;
    }

    /** The small tag on a visual that reads a dataset other than the report's main one. */
    function dsTag(e, v) {
      let tag = e.frame.el.querySelector('[data-dataset-tag]');
      const other = D.isOtherDataset(v);
      if (!other) { if (tag) { tag.remove(); } return; }
      if (!tag) { tag = h('span', { class: 'bi-dstag', 'data-dataset-tag': '' }); e.frame.el.appendChild(tag); }
      tag.textContent = D.dsTitle(v.dataset);
      tag.setAttribute('title', 'This visual reads the dataset ' + v.dataset);
    }

    // the layout canvas (drishti-designer): the page's visuals as items of a grid layout; the designer's undo keeps each step (stack: null)
    const K = window.Drishti.elements.designer;
    const layout = new K.LayoutModel({ cols: 12, maxH: 40, stack: null });
    const selection = new K.Selection();
    const vlabel = (it) => { const v = D.visual(it.id); return v ? (v.title || v.type) + ' (' + v.type + ')' : it.id; };
    const canvas = new K.LayoutCanvas(grid, { model: layout, selection, rowPx: B.ROW_PX, gap: B.GAP_PX, handle: '.bi-dhandle', dropType: MIME, label: vlabel,
      createItem: (it) => {
        const v = D.visual(it.id), e = { frame: makeFrame(v), key: null, token: 0, page: D.pageId, type: v.type };
        frames.set(v.id, e);
        return e.frame.el;
      },
      updateItem: (it, el) => {
        const v = D.visual(it.id), e = frames.get(it.id);
        if (!v || !e) { return; }
        el.setAttribute('data-visual', v.id);
        e.frame.title.textContent = v.title || '';
        dsTag(e, v);
        const key = previewKey(v) + '|' + D.preview;
        if (e.key !== key) { e.key = key; drawEntry(v.id); }
      },
      removeItem: (id) => { const e = frames.get(id); if (e) { e.frame.dispose(); frames.delete(id); } },
      onAction: (type) => {
        const id = selection.primary();
        if (type === 'delete' && id) { remove(id); return true; }
        if (type === 'duplicate' && id) { duplicate(id); return true; }
        if (type === 'open') { D.showPane('format'); const t = D.q('[data-format-title]'); if (t) { t.focus(); } return true; }
        if (['undo', 'redo', 'copy', 'cut', 'paste', 'selectAll', 'group', 'ungroup', 'zorder'].indexOf(type) >= 0) { return false; }     // the designer's own keys (Ctrl+Z, Ctrl+Y)
        return undefined;
      } });
    D.canvas = canvas;
    layout.on('commit', (c) => {                      // a move or a resize: one step of the designer's history; the grid is already settled
      const moved = c.after.filter((it) => c.ids.indexOf(it.id) >= 0 || c.before.some((b) => b.id === it.id && (b.x !== it.x || b.y !== it.y || b.w !== it.w || b.h !== it.h)));
      const label = c.label.indexOf('Resize') === 0 ? 'Resize a visual' : 'Move a visual';
      if (!D.change(label, (m) => { const p = m.pages.find((x) => x.id === D.pageId); moved.forEach((it) => { const v = p.visuals.find((x) => x.id === it.id); if (v) { v.at = { x: it.x, y: it.y, w: it.w, h: it.h }; } }); })) { render(); }
      const v = D.visual(c.ids[0]);
      if (v) { D.say((label === 'Resize a visual' ? 'Resized to ' + v.at.w + ' by ' + v.at.h : 'Moved to column ' + (v.at.x + 1) + ', row ' + (v.at.y + 1)) + '.'); }
    });
    selection.on('change', (s) => { if (s.source !== 'designer' && D.selected !== s.primary) { D.select(s.primary); } });
    canvas.on('drop', (d) => {                        // a field, or a visual type from the palette: onto a visual it joins it, onto the canvas it starts one
      const p = d.payload;
      if (p.vtype) { D.addVisual(p.vtype, d.cell.x, d.cell.y); return; }
      if (d.item) { D.addField(p.name, p.kind, { visual: d.item, dataset: p.dataset || '' }); return; }
      D.addField(p.name, p.kind, Object.assign({ dataset: p.dataset || '' }, d.cell));
    });

    function render() {
      const page = D.page();
      frames.forEach((e, id) => { const v = page.visuals.find((x) => x.id === id); if (!v || e.page !== page.id || e.type !== v.type) { canvas.forget(id); } });     // another type is another frame (its class and its look)
      canvas.o.readOnly = !!D.readOnly;
      layout.load(page.visuals.map((v) => ({ id: v.id, x: v.at.x, y: v.at.y, w: v.at.w, h: v.at.h })));
      selection.set(D.selected && D.visual(D.selected) ? D.selected : null, 'designer');
      empty.hidden = page.visuals.length > 0;
      D.emit('rendered');
    }
    D.on('model', render);
    D.on('page', render);
    D.on('preview', render);
    D.on('select', (id) => { if (selection.primary() !== id) { selection.set(id, 'designer'); } });
    if (window.ResizeObserver) { new ResizeObserver(() => frames.forEach((e) => e.frame.resize())).observe(grid); }

    // ---- actions on a visual -----------------------------------------------------------------------------------------
    function remove(id) {
      const next = D.page().visuals.filter((v) => v.id !== id)[0];
      if (D.readOnly || !D.change('Delete a visual', (m) => { const p = m.pages.find((x) => x.id === D.pageId); p.visuals = p.visuals.filter((v) => v.id !== id); })) { return; }       // refused (frozen, shipped): D.change said so, and nothing was deleted
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

    D.MIME = MIME;
  });
})();
