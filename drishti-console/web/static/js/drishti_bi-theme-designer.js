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
/* The theme designer in BI Studio (report schema v2, R6, 2.4.0; docs/guides/BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 5.2): a theme item's series colours (add, remove, move), its page,
   text, good and bad colours, its type, and the format defaults of each kind of visual, drawn with the same generated Format pane as the report designer
   (so a theme can set only what a visual accepts), with a preview of a chart, a table and a card in the theme. The server checks the theme
   (POST /bi/api/themes/validate) as you go; Save stores the item, Publish makes it the version reports read. /bi/design/theme/new makes a theme in your
   private workspace, starting from the shipped Corporate one. The YAML tab shows the same theme as text. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'theme-designer') { return; }
  const B = window.drishtiBi;
  const h = B.h, q = (s) => B.q(s, canvas);
  const P = B.formatPane;
  const itemId = canvas.getAttribute('data-item');
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  const FAMILIES = [['chart', 'column', 'Charts (bar, column, line, area, combo)'], ['other-chart', 'pie', 'Other charts (pie, scatter, map ...)'], ['card', 'card', 'Cards and KPIs'],
    ['table', 'table', 'Tables'], ['matrix', 'matrix', 'Matrices'], ['slicer', 'slicer', 'Slicers']];
  const STARTER = 'rachana: 1\ntheme: my-theme\ntitle: My theme\npalette: ["#1f4e79", "#2e75b6", "#9dc3e6", "#c55a11", "#70ad47", "#7f7f7f"]\ngood: "#2e7d32"\nbad: "#c62828"\n';
  let model = null, item = null, dirty = false, schemas = null, checkTimer = 0;

  // ---- YAML: the theme written the way a person writes it ------------------------------------------------------------------------
  const scalar = (v) => (typeof v === 'number' || typeof v === 'boolean' ? String(v) : /^[A-Za-z_][A-Za-z0-9_ .-]*$/.test(String(v)) && !/^(true|false|null|yes|no|on|off|y|n)$/i.test(String(v)) ? String(v) : JSON.stringify(String(v)));
  const flow = (v) => (Array.isArray(v) ? '[' + v.map(flow).join(', ') + ']' : v && typeof v === 'object' ? '{ ' + Object.keys(v).map((k) => k + ': ' + flow(v[k])).join(', ') + ' }' : scalar(v));
  function yamlOf(m) {
    const lines = ['rachana: 1'];
    ['theme', 'version', 'title', 'description', 'sample', 'tags', 'palette', 'background', 'foreground', 'good', 'bad', 'font'].forEach((k) => { if (m[k] != null && m[k] !== '' && k !== 'rachana') { lines.push(k + ': ' + flow(m[k])); } });
    if (m.visuals && Object.keys(m.visuals).length) {
      lines.push('visuals:');
      Object.keys(m.visuals).forEach((k) => lines.push('  ' + k + ': ' + flow(m.visuals[k])));
    }
    return lines.join('\n') + '\n';
  }

  function change(fn) {
    fn(model);
    dirty = true;
    q('[data-state]').textContent = 'Unsaved changes';
    draw();
  }

  // ---- the editor ------------------------------------------------------------------------------------------------------------------
  function drawPalette(box) {
    const list = h('ol', { class: 'bi-theme-palette', 'data-theme-palette-list': '' });
    (model.palette || []).forEach((c, i) => {
      const up = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Move colour ' + (i + 1) + ' earlier', disabled: i === 0 }, B.icon('chevron-up'));
      const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove colour ' + (i + 1), disabled: model.palette.length <= 3, 'data-theme-remove-colour': String(i) }, B.icon('x-lg'));
      up.addEventListener('click', () => change((m) => { const t = m.palette[i - 1]; m.palette[i - 1] = m.palette[i]; m.palette[i] = t; }));
      x.addEventListener('click', () => change((m) => { m.palette.splice(i, 1); }));
      const inp = h('input', { type: 'color', value: c, 'aria-label': 'Series colour ' + (i + 1), 'data-theme-colour': String(i) });
      inp.addEventListener('change', () => change((m) => { m.palette[i] = inp.value; }));
      list.appendChild(h('li', {}, inp, h('code', { text: ' ' + c + ' ' }), up, x));
    });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-theme-add-colour': '', disabled: (model.palette || []).length >= 12 }, B.icon('plus-lg'), ' Add a colour');
    add.addEventListener('click', () => change((m) => { (m.palette = m.palette || []).push('#888888'); }));
    box.appendChild(h('fieldset', { class: 'bi-fieldset' }, h('legend', { text: 'Series colours (3 to 12, in order)' }), list, add));
  }

  function drawColours(box) {
    [['background', 'Page and visual background'], ['foreground', 'Text'], ['good', 'Good (a KPI above target, a scale\'s high end)'], ['bad', 'Bad']].forEach(([k, label]) => {
      box.appendChild(P.row(label, P.colour(model[k], (c) => change((m) => { if (c) { m[k] = c; } else { delete m[k]; } }), { ['data-theme-' + k]: '', 'aria-label': label })));
    });
    const font = model.font || {};
    box.appendChild(P.row('Type', P.select([['', 'the console\'s'], ['sans', 'sans'], ['serif', 'serif'], ['mono', 'mono'], ['system', 'system']], font.family || '', (x) => change((m) => {
      const f = Object.assign({}, m.font);
      if (x) { f.family = x; } else { delete f.family; }
      if (Object.keys(f).length) { m.font = f; } else { delete m.font; }
    }), { 'data-theme-font': '' })));
    box.appendChild(P.row('Text size (px)', P.text(font.size, (x) => change((m) => {
      const f = Object.assign({}, m.font), n = parseInt(x, 10);
      if (n >= 10 && n <= 18) { f.size = n; } else { delete f.size; }
      if (Object.keys(f).length) { m.font = f; } else { delete m.font; }
    }), { type: 'number', min: '10', max: '18', 'data-theme-font-size': '' })));
  }

  function drawDefaults(box) {
    box.appendChild(h('h3', { class: 'bi-fhead', text: 'Format defaults for each kind of visual' }));
    box.appendChild(h('p', { class: 'text-muted-d', text: 'What every visual of the kind starts with in a report that uses this theme; a visual\'s own format wins.' }));
    FAMILIES.forEach(([fam, type, title]) => {
      const cur = (model.visuals || {})[fam] || {};
      const det = h('details', { class: 'bi-fsection bi-theme-family', 'data-theme-family': fam, open: Object.keys(cur).length > 0 }, h('summary', { text: title + (Object.keys(cur).length ? ' · ' + Object.keys(cur).length + ' set' : '') }));
      box.appendChild(det);
      P.draw(det, {
        type, format: cur, palette: model.palette || [], measures: [], fields: [], series: [], theme: model, skip: ['rules', 'conditional', 'dataBars', 'spec', 'python', 'params', 'default', 'options', 'mode', 'syncGroup', 'scope'],
        setFmt: (k, x) => change((m) => {
          const v = Object.assign({}, (m.visuals || {})[fam]);
          if (x === '' || x == null) { delete v[k]; } else { v[k] = x; }
          m.visuals = Object.assign({}, m.visuals);
          if (Object.keys(v).length) { m.visuals[fam] = v; } else { delete m.visuals[fam]; }
          if (!Object.keys(m.visuals).length) { delete m.visuals; }
        })
      });
    });
  }

  function drawPreview() {
    const box = B.clear(q('[data-theme-preview]'));
    B.theme.apply(box, model);
    const pal = (model.palette || []).length ? model.palette : B.palette('default');
    box.appendChild(h('div', { class: 'bi-swatches', 'aria-label': 'The series colours' }, pal.map((c) => { const s = h('span', { class: 'bi-swatch', title: c }); s.style.background = c; return s; })));
    const cats = ['North', 'South', 'East', 'West'];
    const res = { columns: [{ name: 'sample.REGION' }, { name: 'Sales' }, { name: 'Margin' }], rows: cats.map((c, i) => [c, [42, 31, 27, 19][i] * 1e6, [12, -4, 7, 3][i] * 1e5]) };          // no meta: a sample has no query line
    const ctx = { index: { measures: { Sales: { name: 'Sales', expr: 'sum(x)' }, Margin: { name: 'Margin', expr: 'sum(y)' } }, columns: { 'sample.REGION': { type: 'string' } }, hierarchies: {} },
      palette: pal, t: B.tokens(), interactive: false, theme: model };
    const fams = { column: 'chart', table: 'table', card: 'card' };
    [['column', { axis: ['sample.REGION'], values: ['Sales', 'Margin'] }, 'Sample: sales and margin by region', 6], ['table', { columns: ['sample.REGION', 'Sales', 'Margin'] }, 'Sample table', 6], ['card', { values: ['Sales'] }, 'Sample card', 3]]
      .forEach(([type, wells, title, w]) => {
        const spec = B.theme.spec({ id: 'sample-' + type, type, title, wells, at: { x: 0, y: 0, w, h: 4 } }, model, fams);
        const frame = B.frame(spec, {});
        frame.el.classList.add('bi-theme-sample');
        frame.el.setAttribute('data-theme-sample', type);
        box.appendChild(frame.el);
        frame.result(Object.assign({ rowCount: res.rows.length }, type === 'card' ? { columns: [{ name: 'Sales' }], rows: [[119e6]] } : res), (host, r) => B.visuals.draw(host, spec, ctx, r));
      });
  }

  function draw() {
    const ed = B.clear(q('[data-theme-editor]'));
    ed.appendChild(P.row('Title', P.text(model.title, (x) => change((m) => { m.title = x.trim() || m.title; }), { 'data-theme-title': '' })));
    ed.appendChild(P.row('Name', P.text(model.theme, (x) => change((m) => { m.theme = B.slug(x) || m.theme; }), { 'data-theme-key': '' }), 'What a report writes: theme: ' + model.theme));
    ed.appendChild(P.row('Description', P.text(model.description, (x) => change((m) => { if (x.trim()) { m.description = x.trim(); } else { delete m.description; } }))));
    drawPalette(ed);
    drawColours(ed);
    drawDefaults(ed);
    drawPreview();
    const ta = q('[data-theme-yaml]');
    if (document.activeElement !== ta) { ta.value = yamlOf(model); }
    clearTimeout(checkTimer);
    checkTimer = setTimeout(check, 400);
  }

  function check() {
    return B.api('POST', '/themes/validate', { yaml: yamlOf(model) }).then((r) => {
      const ul = B.clear(q('[data-theme-problems]'));
      (r.problems || []).forEach((p) => ul.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)));
      if (B.studio && B.studio.problems) { B.studio.problems(r.problems || [], (list, box) => list.forEach((p) => box.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path }), ': ' + p.message)))); }
      q('[data-theme-valid]').textContent = r.valid ? 'The theme is valid.' : 'The theme has errors: see the list.';
      return r;
    });
  }

  function save() {
    const btn = q('[data-save]');
    return B.busy(btn, () => check().then((r) => {
      if (!r.valid) { throw new Error('Fix the errors first: ' + (r.problems.find((p) => p.level === 'error') || {}).message); }
      return B.saveItem(itemId, { body: yamlOf(model), title: model.title }, item && item.etag, btn);
    }).then((it) => { item = it; dirty = false; q('[data-state]').textContent = 'Saved'; B.near(btn, 'Saved. Publish it so that reports read this version.', false); }))
      .catch((e) => { if (!e.cancelled) { B.near(btn, e.message, true); } });
  }

  function start() {
    q('[data-save]').addEventListener('click', save);
    q('[data-publish]').addEventListener('click', () => {
      const btn = q('[data-publish]');
      if (item && item.personal && !item.shipped) {                         // a private workspace cannot publish: say what to do, as the report designer does
        B.near(btn, 'This theme is in your private workspace, which cannot publish; you can use it in your own reports already. Move it to a shared workspace (Move on its item page), then publish it there.', true);
        return;
      }
      const go = () => B.api('POST', '/items/' + B.id2path(itemId) + '/' + (item && item.workspaceReviewRequired ? 'propose' : 'publish'), {}).then((it) => { const proposed = item && item.workspaceReviewRequired; item = it; B.near(btn, proposed ? 'Proposed for review: once approved, reports that name ' + model.theme + ' draw with it.' : 'Published: reports that name ' + model.theme + ' draw with it.', false); });
      B.busy(btn, () => (dirty ? save().then(go) : go())).catch((e) => B.near(btn, e.message, true));
    });
    const ta = q('[data-theme-yaml]');
    ta.addEventListener('change', () => B.api('POST', '/themes/validate', { yaml: ta.value }).then((r) => {
      if (r.theme) { model = r.theme; dirty = true; draw(); say(''); } else { say((r.problems[0] || {}).message || 'The YAML does not read as a theme.', true); }
    }));
    window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
  }

  if (itemId === 'new') {                                                  // a new theme in your private workspace, starting from the shipped Corporate theme
    B.api('GET', '/themes/corporate').then((t) => Object.assign({}, t, { theme: 'my-theme', title: 'My theme', sample: undefined, tags: undefined, description: 'Made from Corporate.' }), () => null)
      .then((t) => B.writable().then((wss) => { const shared = wss.find((w) => !w.personal); return B.api('POST', '/workspaces/' + encodeURIComponent(shared ? shared.id : 'me') + '/items', { kind: 'theme', title: 'My theme', description: '', body: t ? yamlOf(t) : STARTER }); }))      // a shared workspace when you have one: a theme is published for others
      .then((it) => { window.location.replace('/bi/design/theme/' + B.id2path(it.id)); })
      .catch((e) => say(e.message, true));
    return;
  }
  Promise.all([B.api('GET', '/items/' + B.id2path(itemId)), P.schemas()]).then(([it, sc]) => {
    item = it;
    schemas = sc;
    if (it.kind !== 'theme') { throw new Error('This item is not a theme.'); }
    q('[data-title]').textContent = it.title;
    return B.api('POST', '/themes/validate', { yaml: it.body || STARTER });
  }).then((r) => {
    model = r.theme || { rachana: 1, theme: 'my-theme', title: item.title, palette: ['#1f4e79', '#2e75b6', '#9dc3e6'] };
    delete model.rachana;
    delete model.version;
    start();
    draw();
    if (B.studio && B.studio.attach) { B.studio.attach(item); }
    q('[data-state]').textContent = item.status === 'published' ? 'Published' : 'Draft';
    q('[data-theme-root]').setAttribute('data-ready', 'yes');
  }).catch((e) => say(e.message, true));
})();
