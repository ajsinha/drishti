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
/* Wave C (2.4.0), the Transform editor (docs/guides/PYTHON_BI.md section 17; docs/architecture/BI_DESIGN.md section 11.1, items P1, P2, P3, P5). The page holds the Transform as a spec
   ({name, title, inputs[{name, input}], steps[{label, code}], keys, columns}); every change asks the console to run the steps (POST /bi/transform/steps:
   the inputs are asked of the server as you, then run() runs once in the sandbox with each step's frame described) and to write the YAML it is saved as
   (POST /bi/transform/compose). Steps come from the snippets library (POST /bi/transform/snippet writes each value as a literal) or are written by hand.
   Nothing is computed here; the page draws. Text only, never markup. */
(function () {
  'use strict';
  const B = window.drishtiBi, root = document.getElementById('tx');
  if (!B || !root || !B.txView) { return; }
  const h = B.h, q = (s) => B.q(s, root), qa = (s) => B.qa(s, root), V = B.txView;
  const mayRun = root.getAttribute('data-may-python') === 'true';
  const S = { spec: { name: '', title: '', inputs: [], steps: [], params: {}, keys: [], columns: [] }, run: null, sel: { what: 'step', index: -1 }, tab: 'rows',
    snippets: [], items: { python: null, dataset: null }, editing: -1, seq: 0 };
  const msg = document.querySelector('[data-msg]');
  const say = (t, bad) => B.say(msg, t, bad);
  window.drishtiTransform = S;                                       // the browser tests read the state

  function post(path, body) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then((r) => r.json().catch(() => ({})).then((d) => {
        if (!r.ok) { const e = new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText)); e.code = d.code; e.traceback = d.traceback; throw e; }
        return d;
      }));
  }
  const names = () => S.spec.inputs.map((i) => i.name);
  const slug = (t) => String(t || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64);

  // ---- names ---------------------------------------------------------------------------------------------------------------------------------------
  const titleIn = q('[data-title]'), nameIn = q('[data-name]');
  titleIn.addEventListener('input', () => { S.spec.title = titleIn.value; if (!nameIn.dataset.touched) { nameIn.value = slug(titleIn.value); S.spec.name = nameIn.value; } changed(false); });
  nameIn.addEventListener('input', () => { nameIn.dataset.touched = '1'; S.spec.name = slug(nameIn.value); changed(false); });

  // ---- inputs --------------------------------------------------------------------------------------------------------------------------------------
  const kindSel = q('[data-ikind]'), modelSel = q('[data-imodel]'), fieldsBox = q('[data-ifields-box]'), fieldsEl = q('[data-ifields]'), addBtn = q('[data-iadd]');
  let groups = [];
  function mode() { return (qa('[data-isrc]').find((r) => r.checked) || {}).value || 'kind'; }
  function showMode(reload) {
    const k = mode() === 'kind';
    qa('[data-kind-row]').forEach((e) => { e.hidden = !k; });
    qa('[data-model-row]').forEach((e) => { e.hidden = k; });
    if (reload) { loadFields(k ? kindSel.value : modelSel.value); }
  }
  qa('[data-isrc]').forEach((r) => r.addEventListener('change', () => showMode(true)));
  function loadSources() {
    return B.layer.groups().then((gs) => {
      groups = gs;
      const packs = gs.filter((g) => /^Pack /.test(g.label));
      B.clear(kindSel);
      kindSel.appendChild(h('option', { value: '', text: '(choose a kind)' }));
      packs.forEach((g) => kindSel.appendChild(h('optgroup', { label: g.label }, g.items.map((i) => h('option', { value: i.value, text: i.label + ' (' + i.value.replace(/^dataset:pack\./, '') + ')' })))));
      B.layer.fillSelect(modelSel, gs, '(choose a model or a dataset)');
    });
  }
  function loadFields(value) {
    B.clear(fieldsEl); fieldsBox.hidden = true; addBtn.disabled = true;
    if (!value) { return Promise.resolve(); }
    const ref = mode() === 'kind' ? value : B.layer.refOf(value);
    return B.layer.get('fields?ref=' + encodeURIComponent(ref)).then((m) => {
      (m.tables || []).forEach((t, ti) => {
        if (mode() === 'kind' && ti > 0) { return; }                // a kind input reads the kind's own table
        const cols = (t.columns || []).filter((c) => c.name !== 'id');
        fieldsEl.appendChild(h('div', { class: 'tx-ftable', 'data-tx-table': t.name }, h('strong', { text: t.name }), cols.map((c, i) => h('label', { class: 'tx-check' },
          h('input', { type: 'checkbox', name: 'f', value: mode() === 'kind' ? c.name : t.name + '.' + c.name, checked: ti === 0 && i < 12 }), ' ' + c.name + (c.masked ? ' (masked)' : '')))));
      });
      fieldsBox.hidden = false; addBtn.disabled = false;
      root.setAttribute('data-fields-for', value);
      const nm = q('[data-iname]');
      if (!nm.value) { nm.value = (value.split('.').pop() || 'input').replace(/[^A-Za-z0-9_]/g, '_').replace(/^(\d)/, '_$1') + (/s$/.test(value) ? '' : 's'); }
    }).catch((e) => say(e.message, true));
  }
  kindSel.addEventListener('change', () => loadFields(kindSel.value));
  modelSel.addEventListener('change', () => loadFields(modelSel.value));
  q('[data-input-form]').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const nm = q('[data-iname]').value.trim();
    const fields = qa('[data-ifields] input[name=f]:checked').map((i) => i.value);
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(nm) || ['df', 'params', 'pd', 'np', 're'].indexOf(nm) >= 0) { B.near(addBtn, 'The name is a Python name (letters, digits, underscore), not df, params, pd, np or re.', true); return; }
    if (names().indexOf(nm) >= 0) { B.near(addBtn, 'There is an input named ' + nm + ' already.', true); return; }
    if (!fields.length) { B.near(addBtn, 'Tick at least one field.', true); return; }
    let input;
    if (mode() === 'kind') {
      const parts = kindSel.value.replace(/^dataset:pack\./, '').split('.');
      input = { kind: parts[1], pack: parts[0], fields };
      const conn = q('[data-iconnector]').value.trim();
      if (conn) { input.connector = conn; }
    } else {
      input = { model: B.layer.refOf(modelSel.value), select: { fields, measures: [] }, shape: 'rows' };
    }
    S.spec.inputs.push({ name: nm, input });
    if (S.spec.inputs.length === 1 && !S.spec.steps.length) { S.spec.steps.push({ label: 'Start from ' + nm, code: 'df = ' + nm + '.copy()' }); S.sel = { what: 'step', index: 0 }; }
    q('[data-iname]').value = ''; q('[data-iconnector]').value = '';
    if (!S.spec.title) { titleIn.value = 'Prep of ' + nm; S.spec.title = titleIn.value; if (!nameIn.dataset.touched) { nameIn.value = slug(titleIn.value); S.spec.name = nameIn.value; } }
    B.near(addBtn, 'Added the input ' + nm + '.', false);
    changed(true);
  });
  function inputLine(i) {
    const s = i.input || {};
    return s.kind ? 'the kind ' + s.kind + (s.pack ? ' (pack ' + s.pack + ')' : '') + ' through ' + (s.connector || 'whichever connector serves it') + (s.fields ? ' · ' + s.fields.length + ' fields' : '')
      : 'the model ' + (s.model || '?') + ((s.select || {}).fields ? ' · ' + s.select.fields.length + ' fields' : '');
  }
  function drawInputs() {
    const ul = q('[data-inputs]');
    B.clear(ul);
    S.spec.inputs.forEach((i, k) => {
      const ran = S.run && (S.run.inputs || [])[k];
      const line = ran ? ran.rows.toLocaleString('en-US') + ' rows' + (ran.cut ? ' (the first ' + S.run.previewRows.toLocaleString('en-US') + ')' : '') + (ran.masked && ran.masked.length ? ' · masked for you: ' + ran.masked.join(', ') : '') + (ran.asOf ? ' · as of ' + ran.asOf : '') : '';
      const on = S.sel.what === 'input' && S.sel.index === k;
      const li = h('li', { class: 'tx-item' + (on ? ' tx-on' : ''), 'data-input': i.name },
        h('button', { type: 'button', class: 'tx-pick', 'aria-pressed': on ? 'true' : 'false', title: 'Preview this input' }, h('code', { text: i.name }), h('span', { class: 'text-muted-d', text: ' ' + inputLine(i) }),
          line ? h('small', { class: 'tx-badge', 'data-input-rows': '', text: line }) : null),
        h('button', { type: 'button', class: 'bx-mini', 'aria-label': 'Remove the input ' + i.name, title: 'Remove', 'data-remove-input': '' }, '×'));
      li.querySelector('.tx-pick').addEventListener('click', () => { S.sel = { what: 'input', index: k }; drawAll(); });
      li.querySelector('[data-remove-input]').addEventListener('click', () => { S.spec.inputs.splice(k, 1); S.sel = { what: 'step', index: S.spec.steps.length - 1 }; changed(true); });
      ul.appendChild(li);
    });
  }

  // ---- steps ---------------------------------------------------------------------------------------------------------------------------------------
  function stepRun(k) { return S.run ? (S.run.steps || []).find((s) => s.index === k) : null; }
  function drawSteps() {
    const ol = q('[data-steps]');
    B.clear(ol);
    S.spec.steps.forEach((s, k) => {
      const r = stepRun(k), failed = S.run && S.run.failed && S.run.failed.index === k;
      const on = S.sel.what === 'step' && S.sel.index === k;
      const state = failed ? 'failed' : r ? (r.rows != null ? r.rows.toLocaleString('en-US') + ' rows · ' + (r.columns || []).length + ' cols' : (r.note || 'ran')) : S.run ? 'not run' : '';
      const li = h('li', { class: 'tx-item tx-step' + (on ? ' tx-on' : '') + (failed ? ' tx-failed' : ''), 'data-step': String(k) },
        h('button', { type: 'button', class: 'tx-pick', 'aria-pressed': on ? 'true' : 'false', title: 'Show this step\'s rows and profile' },
          h('span', { class: 'tx-num', text: String(k + 1) }), h('span', { class: 'tx-label', text: s.label }),
          state ? h('small', { class: 'tx-badge' + (failed ? ' bi-bad' : ''), 'data-step-state': '', text: state }) : null),
        h('span', { class: 'tx-tools' },
          h('button', { type: 'button', class: 'bx-mini', 'aria-label': 'Edit step ' + (k + 1), title: 'Edit', 'data-edit': '' }, '✎'),
          h('button', { type: 'button', class: 'bx-mini', 'aria-label': 'Move step ' + (k + 1) + ' up', title: 'Up', 'data-up': '', disabled: k === 0 }, '↑'),
          h('button', { type: 'button', class: 'bx-mini', 'aria-label': 'Move step ' + (k + 1) + ' down', title: 'Down', 'data-down': '', disabled: k === S.spec.steps.length - 1 }, '↓'),
          h('button', { type: 'button', class: 'bx-mini', 'aria-label': 'Remove step ' + (k + 1), title: 'Remove', 'data-remove': '' }, '×')));
      li.querySelector('.tx-pick').addEventListener('click', () => { S.sel = { what: 'step', index: k }; drawAll(); });
      li.querySelector('[data-edit]').addEventListener('click', () => openCoder(k));
      li.querySelector('[data-up]').addEventListener('click', () => move(k, -1));
      li.querySelector('[data-down]').addEventListener('click', () => move(k, 1));
      li.querySelector('[data-remove]').addEventListener('click', () => { S.spec.steps.splice(k, 1); S.sel = { what: 'step', index: Math.min(k, S.spec.steps.length - 1) }; changed(true); });
      ol.appendChild(li);
    });
    q('[data-undo]').disabled = S.spec.steps.length === 0;
  }
  function move(k, d) {
    const j = k + d;
    if (j < 0 || j >= S.spec.steps.length) { return; }
    const t = S.spec.steps[k]; S.spec.steps[k] = S.spec.steps[j]; S.spec.steps[j] = t;
    S.sel = { what: 'step', index: j };
    changed(true);
  }
  /** Where a new step goes: after the step being shown, else at the end. */
  function insertAt() { return S.sel.what === 'step' && S.sel.index >= 0 && S.sel.index < S.spec.steps.length - 1 ? S.sel.index + 1 : S.spec.steps.length; }
  function addStep(step) {
    const at = insertAt();
    S.spec.steps.splice(at, 0, { label: step.label, code: step.code, snippet: step.snippet });
    S.sel = { what: 'step', index: at };
    changed(true);
  }
  q('[data-undo]').addEventListener('click', () => { S.spec.steps.pop(); S.sel = { what: 'step', index: S.spec.steps.length - 1 }; changed(true); });

  // ---- hand-written steps -------------------------------------------------------------------------------------------------------------------------
  const coder = q('[data-coder]');
  function openCoder(k) {
    S.editing = k;
    q('[data-picker]').hidden = true;
    coder.hidden = false;
    q('[data-code-label]').value = k >= 0 ? S.spec.steps[k].label : '';
    q('[data-code]').value = k >= 0 ? S.spec.steps[k].code : '';
    q('[data-code-verb]').textContent = k >= 0 ? 'Change the step' : 'Add the step';
    q('[data-code]').focus();
  }
  q('[data-open-code]').addEventListener('click', () => openCoder(-1));
  q('[data-coder-close]').addEventListener('click', () => { coder.hidden = true; });
  coder.addEventListener('submit', (ev) => {
    ev.preventDefault();
    const code = q('[data-code]').value.replace(/\s+$/, ''), label = q('[data-code-label]').value.trim() || 'My step';
    if (!code.trim()) { B.near(q('[data-code-add]'), 'Write the step\'s Python first.', true); return; }
    if (S.editing >= 0) { S.spec.steps[S.editing] = { label, code }; S.sel = { what: 'step', index: S.editing }; changed(true); } else { addStep({ label, code }); }
    coder.hidden = true;
  });

  // ---- snippets ------------------------------------------------------------------------------------------------------------------------------------
  const picker = q('[data-picker]'), snipSel = q('[data-snippet]'), paramsEl = q('[data-params]');
  function loadSnippets() {
    return fetch('/bi/transform/snippets', { credentials: 'same-origin' }).then((r) => r.json()).then((d) => {
      S.snippets = d.snippets || [];
      B.clear(snipSel);
      snipSel.appendChild(h('option', { value: '', text: '(choose a snippet)' }));
      const byGroup = {};
      S.snippets.forEach((s) => { (byGroup[s.group] = byGroup[s.group] || []).push(s); });
      Object.keys(byGroup).forEach((g) => snipSel.appendChild(h('optgroup', { label: g }, byGroup[g].map((s) => h('option', { value: s.id, text: s.title })))));
    });
  }
  /** The columns the new step starts from: those of the step it goes after (else of the first input). */
  function columnsHere() {
    const before = stepRun(insertAt() - 1);
    if (before && before.columns) { return before.columns.map((c) => c.name); }
    const first = S.run && (S.run.inputs || [])[0];
    return first ? first.columns.map((c) => c.name) : [];
  }
  function drawParams(s, preset) {
    B.clear(paramsEl);
    q('[data-snippet-help]').textContent = s ? s.description : '';
    if (!s) { return; }
    const cols = columnsHere(), inputs = names();
    s.params.forEach((p) => {
      const id = 'txp-' + p.name;
      let control;
      const def = preset && preset[p.name] != null ? preset[p.name] : p.default;
      if (p.kind === 'column') {
        control = h('select', { class: 'studio-select', id, 'data-param': p.name }, cols.map((c) => h('option', { value: c, text: c, selected: c === def })));
      } else if (p.kind === 'columns') {
        control = h('div', { class: 'tx-multi', id, 'data-param': p.name, 'data-multi': '' }, cols.map((c) => h('label', { class: 'tx-check' }, h('input', { type: 'checkbox', value: c, checked: Array.isArray(def) && def.indexOf(c) >= 0 }), ' ' + c)));
      } else if (p.kind === 'input') {
        control = h('select', { class: 'studio-select', id, 'data-param': p.name }, inputs.map((c, i) => h('option', { value: c, text: c, selected: c === def || (def == null && i === inputs.length - 1) })));
      } else if (p.kind === 'choice' || p.kind === 'operator') {
        control = h('select', { class: 'studio-select', id, 'data-param': p.name }, p.options.map((o) => h('option', { value: o, text: o, selected: o === def })));
      } else if (p.kind === 'bool') {
        control = h('input', { type: 'checkbox', id, 'data-param': p.name, checked: def !== false });
      } else if (p.kind === 'mapping') {
        control = h('textarea', { class: 'studio-in mono', id, rows: '3', 'data-param': p.name, placeholder: 'B = BUY\nS = SELL' });
      } else {
        control = h('input', { class: 'studio-in' + (p.kind === 'number' ? '' : ' mono'), id, 'data-param': p.name, type: p.kind === 'number' ? 'number' : 'text', autocomplete: 'off',
          value: def == null ? '' : Array.isArray(def) ? def.join(', ') : String(def), placeholder: p.kind === 'values' ? 'a, b, c' : '' });
      }
      paramsEl.appendChild(h('label', { class: p.kind === 'bool' ? 'tx-check' : '', for: id }, p.kind === 'bool' ? [control, ' ' + p.label] : [p.label, control]));
    });
  }
  function paramValues(s) {
    const out = {};
    s.params.forEach((p) => {
      const el = paramsEl.querySelector('[data-param="' + p.name + '"]');
      if (!el) { return; }
      if (p.kind === 'columns') { out[p.name] = B.qa('input:checked', el).map((i) => i.value); }
      else if (p.kind === 'bool') { out[p.name] = el.checked; }
      else if (p.kind === 'number') { out[p.name] = el.value === '' ? null : Number(el.value); }
      else if (p.kind === 'values') { out[p.name] = el.value.split(',').map((x) => x.trim()).filter(Boolean); }
      else if (p.kind === 'mapping') {
        const m = {};
        el.value.split('\n').forEach((ln) => { const i = ln.indexOf('='); if (i > 0) { m[ln.slice(0, i).trim()] = ln.slice(i + 1).trim(); } });
        out[p.name] = m;
      } else { out[p.name] = el.value; }
    });
    return out;
  }
  function openPicker(id, preset) {
    coder.hidden = true;
    picker.hidden = false;
    if (id) { snipSel.value = id; }
    drawParams(S.snippets.find((s) => s.id === snipSel.value), preset);
    snipSel.focus();
  }
  q('[data-open-snippets]').addEventListener('click', () => openPicker());
  q('[data-picker-close]').addEventListener('click', () => { picker.hidden = true; });
  snipSel.addEventListener('change', () => drawParams(S.snippets.find((s) => s.id === snipSel.value)));
  function renderSnippet(id, params) { return post('/bi/transform/snippet', { id, params, inputs: names() }); }
  picker.addEventListener('submit', (ev) => {
    ev.preventDefault();
    const s = S.snippets.find((x) => x.id === snipSel.value);
    const btn = q('[data-snippet-add]');
    if (!s) { B.near(btn, 'Choose a snippet.', true); return; }
    B.busy(btn, () => renderSnippet(s.id, paramValues(s)).then((step) => { picker.hidden = true; addStep(step); }).catch((e) => B.near(btn, e.message, true)));
  });
  function fixType(column, type) {
    const id = type === 'date' ? 'type-fix-date' : 'type-fix-number';
    renderSnippet(id, { column }).then(addStep).catch((e) => say(e.message, true));
  }

  // ---- running and drawing -------------------------------------------------------------------------------------------------------------------------
  let timer = null;
  /** The spec changed: redraw now; with `rerun`, run the steps again (soon, so typing a name does not run anything). */
  function changed(rerun) {
    drawAll();
    clearTimeout(timer);
    timer = setTimeout(() => (rerun ? run() : yaml()), rerun ? 150 : 400);
  }
  function specToSend() {
    const last = S.run && S.run.complete && (S.run.steps || []).slice(-1)[0];
    if (last && last.columns) { S.spec.columns = last.columns; }
    S.spec.keys = keysTicked();
    return S.spec;
  }
  function run() {
    if (!S.spec.inputs.length) { q('[data-run-meta]').textContent = 'Add an input to start.'; yaml(); return Promise.resolve(); }
    if (!mayRun) { yaml(); return Promise.resolve(); }
    const me = ++S.seq;
    const meta = q('[data-run-meta]');
    meta.textContent = 'Running the steps on the rows you may see…';
    root.setAttribute('data-running', 'true');
    return post('/bi/transform/steps', { spec: specToSend() }).then((a) => {
      if (me !== S.seq) { return; }
      S.run = a;
      const rowsIn = (a.inputs || []).map((i) => i.name + ' ' + i.rows.toLocaleString('en-US')).join(', ');
      const ms = (a.steps || []).reduce((t, s) => t + (s.ms || 0), 0);
      meta.textContent = (a.complete ? 'Ran ' + (a.steps || []).length + ' steps' : 'Stopped at step ' + (a.failed.index + 1)) + ' on ' + rowsIn + ' rows in ' + Math.round(ms) + ' ms'
        + ' (inputs asked as you in ' + a.askedMs + ' ms; Python ' + a.ms + ' ms in all).';
      if (S.sel.what === 'step' && (S.sel.index < 0 || S.sel.index >= S.spec.steps.length)) { S.sel.index = S.spec.steps.length - 1; }
      drawAll(); yaml();
    }).catch((e) => {
      if (me !== S.seq) { return; }
      S.run = null;
      meta.textContent = e.message;
      say(e.message, true);
      drawAll();
    }).finally(() => { if (me === S.seq) { root.setAttribute('data-running', 'false'); root.setAttribute('data-ran', String(me)); } });
  }
  function drawPreview() {
    const rowsEl = q('[data-prows]'), profEl = q('[data-pprofile]'), errEl = q('[data-step-error]');
    errEl.hidden = true; B.clear(errEl);
    let view = null, masked = [];
    if (S.run) {
      if (S.sel.what === 'input') { view = (S.run.inputs || [])[S.sel.index]; masked = view ? view.masked : []; }
      else { view = stepRun(S.sel.index); masked = [].concat(...(S.run.inputs || []).map((i) => i.masked || [])); }
      const f = S.run.failed;
      if (f && S.sel.what === 'step' && f.index === S.sel.index) {
        errEl.hidden = false;
        errEl.appendChild(h('strong', { text: 'Step ' + (f.index + 1) + ' failed' + (f.line ? ' at line ' + f.line : '') + ': ' }));
        errEl.appendChild(h('span', { text: f.error }));
        if (f.traceback) { errEl.appendChild(h('pre', { class: 'bx-pre', text: f.traceback })); }
      }
    }
    V.rows(rowsEl, view, masked);
    V.profile(profEl, view && view.profile, { masked, onFix: S.sel.what === 'step' ? fixType : null });
    V.keys(q('[data-keys]'), S.sel.what === 'input' && S.run ? S.run.keys : []);
    rowsEl.hidden = S.tab !== 'rows'; profEl.hidden = S.tab !== 'profile';
    qa('[data-ptab]').forEach((b) => b.setAttribute('aria-selected', b.getAttribute('data-ptab') === S.tab ? 'true' : 'false'));
  }
  qa('[data-ptab]').forEach((b) => b.addEventListener('click', () => { S.tab = b.getAttribute('data-ptab'); drawPreview(); }));
  function drawKeys() {
    const box = q('[data-keyopts]'), set = q('[data-keyset]');
    const last = S.run && S.run.complete && (S.run.steps || []).slice(-1)[0];
    const ticked = keysTicked();
    B.clear(box);
    set.hidden = !(last && last.columns);
    if (set.hidden) { return; }
    const unique = new Set(((last.profile || {}).columns || []).filter((c) => c.unique).map((c) => c.name));
    const want = ticked.length ? ticked : (S.spec.keys && S.spec.keys.length ? S.spec.keys : [...unique].slice(0, 1));
    last.columns.forEach((c) => box.appendChild(h('label', { class: 'tx-check' }, h('input', { type: 'checkbox', value: c.name, checked: want.indexOf(c.name) >= 0, 'data-key': '' }),
      ' ' + c.name + (unique.has(c.name) ? ' (unique here)' : ''))));
    B.qa('[data-key]', box).forEach((i) => i.addEventListener('change', () => { S.spec.keys = keysTicked(); yaml(); }));
  }
  function keysTicked() { return B.qa('[data-key]:checked', root).map((i) => i.value); }
  function drawAll() {
    drawInputs(); drawSteps(); drawPreview(); drawKeys();
    q('[data-save]').disabled = !S.spec.inputs.length || !S.spec.steps.length;
  }

  // ---- the YAML: shown, editable, read back --------------------------------------------------------------------------------------------------------
  function yaml() {
    if (!S.spec.inputs.length) { return Promise.resolve(); }
    return post('/bi/transform/compose', { spec: specToSend() }).then((c) => {
      q('[data-yaml-python]').value = c.python;
      q('[data-yaml-dataset]').value = c.dataset;
      S.composed = c;
    }).catch((e) => say(e.message, true));
  }
  q('[data-yaml-read]').addEventListener('click', () => {
    const btn = q('[data-yaml-read]');
    B.busy(btn, () => post('/bi/transform/parse', { python: q('[data-yaml-python]').value, dataset: q('[data-yaml-dataset]').value }).then((d) => {
      open(d.spec);
      B.near(btn, 'Read back: ' + d.spec.steps.length + ' steps over ' + d.spec.inputs.length + ' inputs.', false);
    }).catch((e) => B.near(btn, e.message, true)));
  });
  function open(spec) {
    S.spec = Object.assign({ params: {}, keys: [], columns: [] }, spec);
    titleIn.value = S.spec.title || ''; nameIn.value = S.spec.name || ''; nameIn.dataset.touched = '1';
    S.sel = { what: 'step', index: S.spec.steps.length - 1 };
    S.run = null;
    changed(true);
  }

  // ---- saving: two drafts in your workspace --------------------------------------------------------------------------------------------------------
  q('[data-save]').addEventListener('click', () => {
    const btn = q('[data-save]'), out = q('[data-save-msg]');
    B.busy(btn, () => post('/bi/transform/compose', { spec: specToSend() }).then((c) => {
      const title = c.spec.title || c.spec.name;
      const savePy = S.items.python ? B.api('PUT', '/items/' + B.id2path(S.items.python), { body: c.python })
        : B.api('POST', '/workspaces/me/items', { kind: 'python', title, description: 'A Python Transform built in the Transform editor.', body: c.python });
      return savePy.then((py) => {
        S.items.python = py.id || S.items.python;
        const saveDs = S.items.dataset ? B.api('PUT', '/items/' + B.id2path(S.items.dataset), { body: c.dataset })
          : B.api('POST', '/workspaces/me/items', { kind: 'dataset', title, description: 'The rows of the Python Transform ' + c.spec.name + ', run as each reader.', body: c.dataset });
        return saveDs;
      }).then((ds) => {
        S.items.dataset = ds.id || S.items.dataset;
        const a = q('[data-open-python]'), b = q('[data-open-dataset]');
        a.href = '/bi/i/' + B.id2path(S.items.python); a.hidden = false;
        b.href = '/bi/i/' + B.id2path(S.items.dataset); b.hidden = false;
        out.textContent = 'Saved as two drafts in your workspace: the Python item and the dataset. Check and publish the Python item first, then the dataset.';
        history.replaceState(null, '', '/bi/transform?item=' + encodeURIComponent(S.items.python) + '&dataset=' + encodeURIComponent(S.items.dataset));
      });
    }).catch((e) => { out.textContent = e.message; B.near(btn, e.message, true); }));
  });

  // ---- start ---------------------------------------------------------------------------------------------------------------------------------------
  const params = new URLSearchParams(window.location.search);
  Promise.all([loadSources(), loadSnippets()]).then(() => {
    showMode(false);
    root.setAttribute('data-loaded', 'true');
    const item = params.get('item'), ds = params.get('dataset');
    if (!item) { drawAll(); return null; }
    return Promise.all([B.api('GET', '/items/' + B.id2path(item)), ds ? B.api('GET', '/items/' + B.id2path(ds)) : Promise.resolve(null)]).then((got) => {
      S.items.python = got[0].can && got[0].can.edit === false ? null : item;
      S.items.dataset = got[1] && !(got[1].can && got[1].can.edit === false) ? ds : null;
      return post('/bi/transform/parse', { python: got[0].body || '', dataset: got[1] ? got[1].body || '' : '' }).then((d) => open(d.spec));
    });
  }).catch((e) => say(e.message, true));
})();
