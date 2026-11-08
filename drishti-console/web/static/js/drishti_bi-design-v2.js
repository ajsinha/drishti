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
/* The report designer's report schema v2 parts (R6 and R7, 2.4.0): how a selection on a visual acts on the others (Edit interactions: a list in Format, and
   the Power BI way on the canvas, a Filter / Highlight / None choice over each other visual), the tooltip page a visual opens over its marks, the page's
   layout (grid or board) and whether it is a tooltip page, the report's theme (a theme item or a palette) and its board settings, and the tile editor of a
   board page (the report and visual a tile shows, and its alert thresholds), with a live preview of the tile on the canvas. Everything is a choice from a
   list, each part a live drishti-inputs Form (B.formatPane.form) whose controls keep their data-* hooks: nothing here needs the YAML tab. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;
  const P = B.formatPane;
  const MODES = ['filter', 'highlight', 'none'];
  const OPS = ['lt', 'le', 'gt', 'ge', 'eq', 'ne'], OP_WORDS = ['<', '≤', '>', '≥', '=', '≠'];
  /** A live Form (drishti-inputs, B.formatPane.form) in its own block of the pane; answers the block. */
  const formIn = (parent, o, cls) => { const host = h('div', { class: 'bi-fform' + (cls ? ' ' + cls : '') }); parent.appendChild(host); P.form(host, o); return host; };
  let editInteractions = false;
  let themesP = null, choicesP = null;
  const sources = new Map();          // a tile's report id -> its answer (model, dataset), for the canvas preview

  const others = (v) => D.page().visuals.filter((x) => x.id !== v.id && ['static', 'board'].indexOf((D.catalogue.visuals[x.type] || {}).group) < 0);
  const interaction = (src, target) => ((D.page().interactions || {})[src] || {})[target] || '';
  function setInteraction(src, target, mode) {
    D.change('Edit interactions', () => {
      const p = D.page();
      const all = p.interactions = p.interactions || {};
      const row = all[src] = all[src] || {};
      if (mode) { row[target] = mode; } else { delete row[target]; }
      if (!Object.keys(row).length) { delete all[src]; }
      if (!Object.keys(all).length) { delete p.interactions; }
    });
  }

  /** Format, Interactions: how a selection (or a slicer's choice) on `v` acts on each other visual of the page. */
  D.interactionsEditor = function (fmt, v) {
    const list = others(v);
    if (!list.length) { return; }
    const box = h('details', { class: 'bi-fsection', 'data-format-section': 'interactions', open: true }, h('summary', { text: 'Interactions' }));
    box.appendChild(h('p', { class: 'text-muted-d', text: (v.type === 'slicer' ? 'A choice in this slicer' : 'A selection on this visual') + ' acts on:' }));
    const fields = {}, value = {}, on = {};
    list.forEach((t, i) => {
      fields['t' + i] = { type: 'string', title: t.title || t.id, enum: MODES, widget: 'select', emptyLabel: 'the viewer\'s choice (filter)', hook: { 'data-interaction': t.id } };
      value['t' + i] = interaction(v.id, t.id) || undefined;
      on['t' + i] = (x) => setInteraction(v.id, t.id, x || '');
    });
    formIn(box, { fields, value, on, label: 'Interactions' });
    const toggle = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-edit-interactions-toggle': '', 'aria-pressed': editInteractions ? 'true' : 'false' }, B.icon('bounding-box-circles'),
      editInteractions ? ' Stop editing on the canvas' : ' Edit on the canvas');
    toggle.addEventListener('click', () => { editInteractions = !editInteractions; overlays(); D.emit('select', D.selected); });
    box.appendChild(toggle);
    fmt.appendChild(box);
  };

  /** On the canvas, over each other visual: Filter, Highlight, None for the selected one (Power BI's Edit interactions). */
  function overlays() {
    B.qa('[data-interaction-bar]').forEach((n) => n.remove());
    const grid = D.q('[data-canvas-grid]');
    grid.classList.toggle('bi-editing-interactions', editInteractions);
    const v = D.sel();
    if (!editInteractions || !v) { return; }
    others(v).forEach((t) => {
      const el = B.q('[data-visual="' + t.id + '"]', grid);
      if (!el) { return; }
      const now = interaction(v.id, t.id) || 'filter';
      const bar = h('div', { class: 'bi-ix-bar', 'data-interaction-bar': t.id, role: 'group', 'aria-label': 'How ' + (v.title || v.id) + ' acts on ' + (t.title || t.id) });
      [['filter', 'funnel', 'Filter'], ['highlight', 'brush', 'Highlight'], ['none', 'slash-circle', 'None']].forEach(([mode, icon, label]) => {
        const b = h('button', { type: 'button', class: 'bi-ix-btn', 'aria-pressed': now === mode ? 'true' : 'false', 'data-ix': mode, title: label, 'aria-label': label }, B.icon(icon));
        b.addEventListener('click', (e) => { e.stopPropagation(); setInteraction(v.id, t.id, mode === 'filter' ? '' : mode); });
        bar.appendChild(b);
      });
      el.appendChild(bar);
    });
  }

  /** Format, Tooltip: the tooltip page drawn over this visual's marks, or a new one. */
  D.tooltipPicker = function (fmt, v) {
    const tips = D.model.pages.filter((p) => p.tooltip);
    const tip = { type: 'string', title: 'Tooltip page', enum: tips.map((p) => p.id), enumLabels: tips.map((p) => p.title), widget: 'select', emptyLabel: 'none', hook: 'data-tooltip-page',
      description: 'Shown over a mark when the pointer rests on it, filtered to the mark.' };
    const make = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-new-tooltip-page': '' }, B.icon('chat-square-quote'), ' New tooltip page');
    make.addEventListener('click', () => {
      let n = 1;
      while (D.model.pages.some((p) => p.id === 'tip' + n)) { n++; }
      D.change('Add a tooltip page', (m) => {
        m.pages.push({ id: 'tip' + n, title: 'Tooltip ' + n, tooltip: true, visuals: [] });
        v.tooltip = 'tip' + n;
      });
      D.say('Tooltip page "Tooltip ' + n + '" added: open its tab to put a few cards or a small chart on it. It shows over a mark of this visual, filtered to that mark.');
    });
    const box = h('details', { class: 'bi-fsection', 'data-format-section': 'tooltip', open: true }, h('summary', { text: 'Tooltip' }));
    formIn(box, { fields: { tooltip: tip }, value: { tooltip: v.tooltip }, label: 'Tooltip', on: { tooltip: (x) => D.change('Tooltip page', () => { if (x) { v.tooltip = x; } else { delete v.tooltip; } }) } });
    box.appendChild(make);
    fmt.appendChild(box);
  };

  /** Format with no visual selected, the page: its layout (grid, board or statement) and whether it is a tooltip page. */
  D.pageV2 = function (fmt, p) {
    formIn(fmt, {
      label: 'The page',
      fields: {
        layout: { type: 'string', title: 'Layout', enum: ['grid', 'board', 'statement'], enumLabels: ['grid (a report page)', 'board (a wall of tiles, kiosk and rotation)', 'statement (paper: page-exact PDF and Excel)'],
          widget: 'select', allowEmpty: false, hook: 'data-page-layout',
          description: 'A board page holds tiles (visuals of other reports, with alert colours) beside the report\'s own visuals, and turns in kiosk mode. A statement page is paper: its visuals are bands laid out page by page (the Paper tab sets the paper and previews it); a subscription sends each person their own.' },
        tooltip: { type: 'boolean', title: 'Use as a tooltip page (shown over a mark, not as a tab)', hook: 'data-page-tooltip' }
      },
      value: { layout: p.layout || 'grid', tooltip: !!p.tooltip },
      on: {
        layout: (x) => D.change('Page layout', () => { if (x === 'board' || x === 'statement') { p.layout = x; delete p.tooltip; } else { delete p.layout; } }),
        tooltip: (on) => D.change('Tooltip page', () => { if (on) { p.tooltip = true; delete p.layout; } else { delete p.tooltip; } })
      }
    });
  };

  /** Format with no visual selected, the report: its theme (a theme item, or a palette) and, with board pages, the kiosk rotation. */
  D.reportV2 = function (fmt, m) {
    const cur = (m.theme || {}).use || '';
    const host = h('div', { class: 'bi-report-theme' });
    fmt.appendChild(host);
    themesP = themesP || B.api('GET', '/themes').catch(() => []);
    themesP.then((list) => {
      const ids = list.map((t) => t.name), words = list.map((t) => t.title + (t.shipped ? '' : ' (' + t.status + ')'));
      if (cur && ids.indexOf(cur) < 0) { ids.push(cur); words.push(cur + ' (not found)'); }
      formIn(host, {
        label: 'The report\'s theme',
        fields: { theme: { type: 'string', title: 'Theme', enum: ids, enumLabels: words, widget: 'select', emptyLabel: 'none (the palette below)', hook: 'data-report-theme',
          description: 'Colours, type and the format defaults of each kind of visual; a visual\'s own format wins.' } },
        value: { theme: cur || undefined },
        on: { theme: (x) => D.change('Theme', () => {
          const t = Object.assign({}, m.theme);
          if (x) { t.use = x; } else { delete t.use; }
          if (Object.keys(t).length) { m.theme = t; } else { delete m.theme; }
        }) }
      });
      host.appendChild(h('a', { class: 'bi-small', href: '/bi/design/theme/new', 'data-new-theme': '', text: 'Make a theme' }));
    });
    if (m.pages.some((p) => p.layout === 'board')) {
      formIn(fmt, {
        label: 'Kiosk',
        fields: { rotate: { type: 'integer', title: 'Kiosk: next page every (seconds)', minimum: 3, maximum: 3600, lazy: true, hook: 'data-board-rotate', description: 'Empty: the console\'s default.' } },
        value: { rotate: parseInt((m.board || {}).rotate, 10) || undefined },
        on: { rotate: (n) => D.change('Board rotation', () => {
          const b = Object.assign({}, m.board);
          if (n >= 3 && n <= 3600) { b.rotate = Math.round(n) + 's'; } else { delete b.rotate; }
          if (Object.keys(b).length) { m.board = b; } else { delete m.board; }
        }) }
      });
    }
  };

  // ---- tiles (R7) ------------------------------------------------------------------------------------------------------------------

  const choices = () => (choicesP = choicesP || B.api('GET', '/boards/reports').catch(() => []));
  const shortRef = (id) => String(id).replace(/^file:report:/, '');
  const findChoice = (list, ref) => list.find((c) => c.id === ref || c.id === 'file:report:' + ref || c.title === ref);

  /** Format of a tile: the report and visual it shows, and its alert (amber and red thresholds, with an optional notification). */
  D.tileEditor = function (fmt, v) {
    const from = v.from || {};
    const box = h('details', { class: 'bi-fsection', 'data-format-section': 'tile', open: true }, h('summary', { text: 'Tile' }));
    const source = h('div', { class: 'bi-tile-source' });
    box.appendChild(source);
    choices().then((list) => {
      const reps = list.filter((c) => c.id !== D.itemId), c = findChoice(list, from.report || ''), vs = (c && c.visuals) || [];
      const at = from.visual && c ? ((vs.find((x) => x.id === from.visual && (!from.page || x.page === from.page)) || {}).page + '/' + from.visual) : undefined;
      formIn(source, {
        label: 'What the tile shows',
        fields: {
          report: { type: 'string', title: 'Report', enum: reps.map((x) => x.id), enumLabels: reps.map((x) => x.title + (x.draft ? ' (draft)' : '')), widget: 'select', emptyLabel: 'Choose a report', hook: { 'data-tile-report': '', 'aria-label': 'Report the tile shows' } },
          visual: { type: 'string', title: 'Visual', enum: vs.map((x) => x.page + '/' + x.id), enumLabels: vs.map((x) => x.title + ' · ' + x.type + ' (' + x.page + ')'), widget: 'select', emptyLabel: 'Choose a visual',
            hook: { 'data-tile-visual': '', 'aria-label': 'Visual the tile shows' } }
        },
        value: { report: c ? c.id : undefined, visual: at },
        on: {
          report: (x) => D.change('Tile report', () => { v.from = { report: shortRef(x || ''), visual: '' }; }),
          visual: (x) => D.change('Tile visual', () => {
            const [page, id] = String(x || '').split('/');
            v.from = { report: (v.from || {}).report || shortRef(c ? c.id : ''), page, visual: id };
            const t = vs.find((y) => y.page === page && y.id === id);
            if (!v.title && t && t.title) { v.title = t.title; }
          })
        }
      });
    });
    // an alert is written once it has a threshold; what is set before that (Alert on, the hysteresis, notify) waits here, per tile, so it is not lost
    const drafts = (D.tileAlertDrafts = D.tileAlertDrafts || {});
    const al = v.alert || drafts[v.id] || {};
    const put = (patch) => D.change('Tile alert', () => {
      const a = Object.assign({}, v.alert || drafts[v.id] || {}, patch);
      Object.keys(a).forEach((k) => { if (a[k] === undefined || a[k] === '') { delete a[k]; } });
      if (a.warn || a.crit) { v.alert = a; delete drafts[v.id]; } else { delete v.alert; drafts[v.id] = a; }
    });
    const level = (name, label) => {
      const r = al[name] || {};
      return [
        [name + 'Op', { type: 'string', title: label + ' when', enum: OPS, enumLabels: OP_WORDS, widget: 'select', allowEmpty: false, hook: 'data-tile-' + name + '-op' }, r.op || 'lt',
          (x) => put({ [name]: Object.assign({ value: 0 }, r, { op: x }) })],
        [name + 'Value', { type: 'number', title: label + ' threshold', lazy: true, hook: 'data-tile-' + name + '-value', description: name === 'warn' ? 'Amber.' : 'Red.' }, r.value,
          (x) => { if (x === undefined || !isNaN(x)) { put({ [name]: x === undefined ? undefined : { op: r.op || 'lt', value: Number(x) || 0 } }); } }]
      ];
    };
    const rows = [['on', { type: 'string', title: 'Alert on', lazy: true, hook: 'data-tile-alert-on', placeholder: 'the first number it shows', description: 'The measure (or column) the thresholds compare.' }, al.on,
      (x) => put({ on: String(x || '').trim() || undefined })]]
      .concat(level('warn', 'Warning'), level('crit', 'Critical'), [
        ['hysteresis', { type: 'number', title: 'Hysteresis', minimum: 0, lazy: true, hook: 'data-tile-hysteresis', description: 'How far back inside a threshold the value must come before the colour drops a level.' }, al.hysteresis,
          (x) => { if (x === undefined || !isNaN(x)) { put({ hysteresis: x === undefined ? undefined : Math.max(0, Number(x) || 0) }); } }],
        ['notify', { type: 'boolean', title: 'Notify (the inbox) when it turns red', hook: 'data-tile-notify' }, !!al.notify, (on) => put({ notify: on ? true : undefined })]
      ]);
    const fields = {}, value = {}, on = {};
    rows.forEach(([k, f, x, fn]) => { fields[k] = f; if (x !== undefined && x !== null && x !== '') { value[k] = x; } on[k] = fn; });
    formIn(box, { fields, value, on, label: 'The tile\'s alert' });
    fmt.appendChild(box);
  };

  /** The canvas preview of a tile: its report's visual, asked as the author, or a note of what it will show. */
  D.tilePreview = function (frame, v) {
    const from = v.from || {};
    const note = (text) => { B.clear(frame.body).appendChild(h('div', { class: 'bi-vis-empty bi-dropnote', role: 'status', 'data-tile-placeholder': '' }, B.icon('window-stack'), ' ' + text)); frame.foot.textContent = ''; frame.el.classList.remove('bi-vis-busy'); };
    if (!from.report || !from.visual) { note('Tile: choose the report and the visual it shows in Format.'); return; }
    if (!D.preview) { note('Tile: ' + from.report + ' › ' + from.visual + ' (preview is off)'); return; }
    frame.busy();
    choices().then((list) => {
      const c = findChoice(list, from.report);
      if (!c) { note('Tile: no report ' + from.report + ' that you can open.'); return null; }
      const target = (c.visuals || []).find((x) => x.id === from.visual && (!from.page || x.page === from.page));
      if (!target) { note('Tile: ' + c.title + ' has no visual ' + from.visual + '.'); return null; }
      const src = sources.get(c.id) || B.api('GET', '/reports/' + B.id2path(c.id));
      sources.set(c.id, src);
      return src.then((rep) => {
        const pg = rep.report.pages.find((p) => p.id === target.page);
        const spec = Object.assign(B.clone(pg.visuals.find((x) => x.id === target.id)), { title: v.title || '' });
        const ctx = { index: B.index(rep.dataset || { measures: [], tables: [] }), palette: B.theme.palette(rep.theme, D.tokens), t: D.tokens, interactive: false, theme: rep.theme };
        if (['text', 'image', 'button'].indexOf(spec.type) >= 0) { frame.result(null, (host) => B.visuals.draw(host, spec, ctx, null)); return; }
        return B.enqueue(() => B.api('POST', '/reports/' + B.id2path(c.id) + '/query', { page: target.page, visual: target.id, state: {} })).then((res) => {
          frame.result(res, (host) => B.visuals.draw(host, spec, ctx, res));
          frame.el.setAttribute('data-alert', v.alert && res && B.live ? B.live.level(v.alert, res, '') : '');
        });
      });
    }).catch((e) => frame.error(e));
  };

  D.modules.push(function () {
    D.on('select', overlays);
    D.on('model', () => setTimeout(overlays, 0));
    D.on('page', () => setTimeout(overlays, 0));
  });
})();
