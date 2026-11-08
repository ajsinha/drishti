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
/* The theme designer in BI Studio (report schema v2, R6, 2.4.0; docs/guides/BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 5.2): a theme item's name,
   series colours (add, remove, move), its page, text, good and bad colours and its type (one live drishti-inputs Form), and the format defaults of each kind of visual, drawn with the same generated Format pane as the report designer
   (so a theme can set only what a visual accepts), with a preview of a chart, a table and a card in the theme. The server checks the theme
   (POST /bi/api/themes/validate) as you go; Save stores the item, Publish makes it the version reports read. /bi/design/theme/new makes a theme in your
   private workspace, starting from the shipped Corporate one. The Studio's YAML panel (Visual, YAML or Both) shows the same theme as text, both ways. */
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

  // ---- YAML: drishti-designer's YamlPane in the Studio shell's YAML panel, both ways: a change on the visual side writes it again; an
  // edit of the YAML is read by the server after a pause and becomes the theme (or its problem shows at its line, the theme unchanged) --
  const K = window.Drishti.elements.designer;
  const ORDER = ['theme', 'version', 'title', 'description', 'sample', 'tags', 'palette', 'background', 'foreground', 'good', 'bad', 'font', 'visuals'];
  function textOf(m) {
    const o = { rachana: 1 };
    ORDER.forEach((k) => { if (m[k] != null && m[k] !== '' && !(k === 'visuals' && !Object.keys(m[k]).length)) { o[k] = m[k]; } });
    return K.dumpYaml(o);
  }
  function readTheme(text) {
    return B.api('POST', '/themes/validate', { yaml: text }).then((r) => {
      if (!r.theme) {                                                      // the problem at the line of its path
        const p = (r.problems || [])[0] || {}, at = p.path ? K.yamlRanges(text).get(p.path) : null;
        throw { message: p.message || 'The YAML does not read as a theme.', line: at ? at[0] + 1 : 1 };
      }
      const t = Object.assign({}, r.theme);
      delete t.rachana;
      delete t.version;
      return t;
    });
  }
  let pane = null;

  function change(fn) {
    fn(model);
    dirty = true;
    if (B.studio && B.studio.state) { B.studio.state('Unsaved changes', item && item.status); }
    draw();
  }

  // ---- the editor: one live Form (drishti-inputs, B.formatPane.form) for the theme's name, its series colours (3 to 12, in order:
  // add, remove, move) and its colours and type; the format defaults below are the generated Format pane --------------------------------
  const FONTS = ['sans', 'serif', 'mono', 'system'];
  const COLOURS = [['background', 'Page and visual background'], ['foreground', 'Text'], ['good', 'Good (a KPI above target, a scale\'s high end)'], ['bad', 'Bad']];
  const setFont = (k, x) => change((m) => {
    const f = Object.assign({}, m.font);
    if (x !== undefined) { f[k] = x; } else { delete f[k]; }
    if (Object.keys(f).length) { m.font = f; } else { delete m.font; }
  });

  function drawSettings(box) {
    const font = model.font || {};
    const fields = {
      title: { type: 'string', title: 'Title', lazy: true, hook: 'theme-title' },
      theme: { type: 'string', title: 'Name', lazy: true, hook: 'theme-key', description: 'What a report writes: theme: ' + model.theme },
      description: { type: 'string', title: 'Description', lazy: true, hook: 'theme-description' },
      palette: { type: 'array', title: 'Series colours (3 to 12, in order)', minItems: 3, maxItems: 12, addLabel: 'Add a colour', hook: 'theme-palette-list',
        items: { type: 'object', title: 'Colour', properties: { colour: { type: 'string', title: 'Colour', widget: 'colour', hook: 'theme-colour', default: '#888888' } } } }
    };
    const value = { title: model.title, theme: model.theme, description: model.description, palette: (model.palette || []).map((c) => ({ colour: c })), fontFamily: font.family, fontSize: font.size };
    const on = {
      title: (x) => change((m) => { m.title = String(x || '').trim() || m.title; }),
      theme: (x) => change((m) => { m.theme = B.slug(x || '') || m.theme; }),
      description: (x) => change((m) => { if (String(x || '').trim()) { m.description = x.trim(); } else { delete m.description; } }),
      palette: (list) => change((m) => { m.palette = (list || []).map((c, i) => (c && c.colour) || (m.palette || [])[i] || '#888888'); }),
      fontFamily: (x) => setFont('family', x || undefined),
      fontSize: (x) => setFont('size', typeof x === 'number' && x >= 10 && x <= 18 ? Math.round(x) : undefined)
    };
    COLOURS.forEach(([k, label]) => {
      fields[k] = { type: 'string', title: label, widget: 'colour', hook: 'theme-' + k };
      value[k] = model[k];
      on[k] = (c) => change((m) => { if (c) { m[k] = c; } else { delete m[k]; } });
    });
    fields.fontFamily = { type: 'string', title: 'Type', enum: FONTS, widget: 'select', emptyLabel: 'the console\'s', hook: 'theme-font' };
    fields.fontSize = { type: 'integer', title: 'Text size (px)', minimum: 10, maximum: 18, lazy: true, hook: 'theme-font-size' };
    const host = h('div', { class: 'bi-fform', 'data-theme-settings': '' });
    box.appendChild(host);
    P.form(host, { fields, value, on, label: 'The theme' });
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
    drawSettings(ed);
    drawDefaults(ed);
    drawPreview();
    if (pane) { pane.setModel(model); }
    clearTimeout(checkTimer);
    checkTimer = setTimeout(check, 400);
  }

  function check() {
    return B.api('POST', '/themes/validate', { yaml: textOf(model) }).then((r) => {
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
      return B.saveItem(itemId, { body: textOf(model), title: model.title }, item && item.etag, btn);
    }).then((it) => { item = it; dirty = false; if (B.studio && B.studio.state) { B.studio.state('Saved', it.status); } B.near(btn, 'Saved. Publish it so that reports read this version.', false); }))
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
    const shell = B.studio && B.studio.shell;
    const host = h('div', { 'data-theme-yaml': '' });
    shell.yamlBody.appendChild(host);
    shell.setOpen(true);
    pane = new K.YamlPane(host, { label: 'Theme YAML', height: 300, delay: 400, toYaml: textOf, fromYaml: readTheme });
    pane.on('model', (e) => { model = e.model; dirty = true; if (B.studio.state) { B.studio.state('Unsaved changes', item && item.status); } say(''); draw(); });
    shell.on('mode', () => pane.refresh());
    shell.on('yaml', () => pane.refresh());
    window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
  }

  if (itemId === 'new') {                                                  // a new theme in your private workspace, starting from the shipped Corporate theme
    B.api('GET', '/themes/corporate').then((t) => Object.assign({}, t, { theme: 'my-theme', title: 'My theme', sample: undefined, tags: undefined, description: 'Made from Corporate.' }), () => null)
      .then((t) => B.writable().then((wss) => { const shared = wss.find((w) => !w.personal); return B.api('POST', '/workspaces/' + encodeURIComponent(shared ? shared.id : 'me') + '/items', { kind: 'theme', title: 'My theme', description: '', body: t ? textOf(t) : STARTER }); }))      // a shared workspace when you have one: a theme is published for others
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
    if (B.studio && B.studio.state) { B.studio.state(item.status === 'published' ? 'Published' : 'Draft', item.status); }
    q('[data-theme-root]').setAttribute('data-ready', 'yes');
  }).catch((e) => say(e.message, true));
})();
