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
/* The model designer's Fields tab and the rest of its completion (docs/architecture/BI_DESIGN.md sections 5.1, 8.1 and 12.2), on the designer's object
   (window.drishtiModel, bi-model-designer.js). Fields: each column's and measure's properties (display name, description, format, folder, hidden,
   sort-by, data category, default summarisation), which every report, board and AI answer inherits from `describe` and never re-declares (`fields:` in
   the YAML); perspectives (`perspectives:`), the field lists a report or a person picks. Security: `hidden-for` (a field or measure that does not exist
   for some roles) and Preview as a role (the server's answer as that role, rows and masks). Contract: what the draft changes against the published
   version, the semver bump it needs (one click), and who is affected. */
(function () {
  'use strict';
  const M = window.drishtiModel, B = window.drishtiBi;
  if (!M || !B) { return; }
  const h = M.h, q = M.q;
  const FORMATS = ['', 'amount0', 'amount2', 'signed0', 'signed2', 'pct', 'integer', 'decimal2', 'date', 'text'];
  const CATEGORIES = ['', 'country', 'region', 'state', 'city', 'postcode', 'address', 'latitude', 'longitude', 'place', 'url', 'image-url', 'currency', 'percentage', 'identifier'];
  const SUMMARISE = [['', 'from the column'], ['sum', 'sum'], ['avg', 'average'], ['min', 'minimum'], ['max', 'maximum'], ['count', 'count'], ['distinct', 'distinct count'], ['none', 'do not summarise']];
  const PROPS = ['display', 'description', 'format', 'folder', 'hidden', 'sort-by', 'category', 'summarise'];
  const sel = (options, value, attrs) => h('select', Object.assign({ class: 'studio-select' }, attrs || {}), options.map((o) => { const [v, t] = Array.isArray(o) ? o : [o, o || '—']; return h('option', { value: v, text: t, selected: String(value == null ? '' : value) === v }); }));
  const allFields = () => { const out = []; Object.keys(M.infos).forEach((a) => (M.infos[a].columns || []).forEach((c) => out.push({ name: a + '.' + c.name, alias: a, col: c }))); return out; };
  const measureNames = () => Object.keys(M.doc.measures || {}).concat(((M.check && M.check.measures) || []).filter((n) => !(M.doc.measures || {})[n]));
  let roleList = null, filterBy = '';
  const openPersp = new Set();
  B.layer.get('roles').then((r) => { roleList = r; }).catch(() => {});
  const roles = () => {
    const known = new Set((roleList && roleList.roles) || []);
    Object.values(((M.doc.security || {}).rows) || {}).forEach((rs) => (rs || []).forEach((r) => [].concat(r.role).forEach((x) => known.add(x))));
    return Array.from(known).sort();
  };

  /** One field's (or measure's) property, edited without redrawing the tab (so typing keeps its place); an empty value takes the key out. */
  function setProp(kind, name, key, value) {
    M.edit((doc) => {
      if (kind === 'measure') {
        const m = (doc.measures = doc.measures || {})[name] = typeof (doc.measures || {})[name] === 'object' && doc.measures[name] ? doc.measures[name] : { expr: (doc.measures || {})[name] || '' };
        if (value === '' || value === false) { delete m[key]; } else { m[key] = value; }
        return;
      }
      doc.fields = doc.fields || {};
      const p = doc.fields[name] = doc.fields[name] || {};
      if (value === '' || value === false) { delete p[key]; } else { p[key] = value; }
      if (!Object.keys(p).length) { delete doc.fields[name]; }
      if (!Object.keys(doc.fields).length) { delete doc.fields; }
    }, true);
  }

  // ---- the Fields tab ------------------------------------------------------------------------------------------------
  function drawFields() {
    const pane = B.clear(q('[data-fields-pane]'));
    const persp = M.doc.perspectives || {};
    const folders = new Set();
    Object.values(M.doc.fields || {}).forEach((p) => { if (p && p.folder) { folders.add(p.folder); } });
    Object.values(M.doc.measures || {}).forEach((m) => { if (m && m.folder) { folders.add(m.folder); } });
    const show = sel([['', 'every field']].concat(Object.keys(persp).map((p) => [p, 'the perspective ' + ((persp[p] || {}).title || p)])), filterBy, { 'data-fields-filter': '', 'aria-label': 'Show' });
    show.addEventListener('change', () => { filterBy = show.value; drawFields(); });
    pane.append(h('p', { class: 'text-muted-d', text: 'Properties set here belong to the model: every report, board and answer that reads it shows the display name, the description, the format and the folder, and leaves hidden fields out. A report never re-declares them.' }),
      h('p', {}, h('label', {}, 'Show ', show)), h('datalist', { id: 'mdFolders' }, Array.from(folders).map((f) => h('option', { value: f }))));
    const inP = (kind, name) => { if (!filterBy) { return true; } const p = persp[filterBy] || {}; return (kind === 'measure' ? p.measures || [] : p.fields || []).indexOf(name) >= 0; };
    const tb = h('tbody');
    const row = (kind, name, props, info) => {
      const ro = M.readOnly;
      const txt = (key, ph) => { const x = h('input', { class: 'studio-in', value: props[key] || '', placeholder: ph || '', 'data-fp': key, 'aria-label': key + ' of ' + name, disabled: ro, list: key === 'folder' ? 'mdFolders' : null }); x.addEventListener('change', () => setProp(kind, name, key, x.value.trim())); return x; };
      const pick = (key, opts) => { const x = sel(opts, props[key] || '', { 'data-fp': key, 'aria-label': key + ' of ' + name, disabled: ro }); x.addEventListener('change', () => setProp(kind, name, key, x.value)); return x; };
      const hid = h('input', { type: 'checkbox', checked: !!props.hidden, 'data-fp': 'hidden', 'aria-label': 'Hide ' + name, disabled: ro });
      hid.addEventListener('change', () => setProp(kind, name, 'hidden', hid.checked));
      const sortBy = kind === 'field' ? pick('sort-by', [['', '—']].concat((M.infos[info.alias].columns || []).filter((c) => c.name !== info.col.name).map((c) => [info.alias + '.' + c.name, c.name]))) : h('span', { class: 'text-muted-d', text: '—' });
      const cell = (label, node) => h('td', { 'data-label': label }, node);
      tb.appendChild(h('tr', { 'data-field-row': name, 'data-kind': kind }, h('th', { scope: 'row', 'data-label': kind === 'measure' ? 'Measure' : 'Field' }, h('code', { text: name }),
        info && info.col ? h('small', { class: 'text-muted-d', text: ' ' + [info.col.type, info.col.meaning || info.col.role].filter(Boolean).join(' · ') }) : h('small', { class: 'text-muted-d', text: ' measure' })),
      cell('Display name', kind === 'measure' ? h('span', { class: 'text-muted-d', text: '(its name)' }) : txt('display', info.col.name)), cell('Description', txt('description')), cell('Format', pick('format', FORMATS.map((f) => [f, f || 'from the meaning']))),
      cell('Folder', txt('folder')), cell('Hidden', hid), cell('Sort by', sortBy), cell('Category', kind === 'measure' ? h('span', { text: '—' }) : pick('category', CATEGORIES)),
      cell('Summarise', kind === 'measure' ? h('span', { text: '—' }) : pick('summarise', SUMMARISE))));
    };
    allFields().filter((f) => inP('field', f.name)).forEach((f) => row('field', f.name, (M.doc.fields || {})[f.name] || {}, f));
    Object.keys(M.doc.measures || {}).filter((n) => inP('measure', n)).forEach((n) => row('measure', n, typeof M.doc.measures[n] === 'object' ? M.doc.measures[n] : {}, null));
    pane.appendChild(h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl bi-tbl bi-lw-cols bi-md-fields', 'data-plain': '', 'data-fields-table': '' }, h('caption', { class: 'visually-hidden', text: 'Field properties' }),
      h('thead', {}, h('tr', {}, ['Field', 'Display name', 'Description', 'Format', 'Folder', 'Hidden', 'Sort by', 'Category', 'Summarise'].map((t) => h('th', { scope: 'col', text: t })))), tb)));
    drawPerspectives(pane);
  }

  function drawPerspectives(pane) {
    const persp = M.doc.perspectives || {};
    const box = h('section', { class: 'bi-sec', 'data-perspectives': '', 'data-keep': '' }, h('h3', { text: 'Perspectives' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'A perspective is a named field list for one audience (a credit officer, a desk head): a report picks one and its Fields pane shows only those. It is a convenience, not security: hide a field or use hidden-for (Security) to keep it from someone.' }));
    Object.keys(persp).forEach((pn) => {
      const p = persp[pn] || {};
      const tick = (key, name) => {
        const x = h('input', { type: 'checkbox', checked: (p[key] || []).indexOf(name) >= 0, 'data-persp-pick': pn + ':' + name, disabled: M.readOnly });
        x.addEventListener('change', () => {
          M.edit((doc) => { const t = doc.perspectives[pn]; t[key] = (t[key] || []).filter((v) => v !== name).concat(x.checked ? [name] : []); }, true);
          const t = (M.doc.perspectives || {})[pn] || {}, count = q('[data-perspective="' + pn + '"] [data-persp-count]');
          if (count) { count.textContent = ' ' + ((t.fields || []).length + (t.measures || []).length) + ' items'; }
        });
        return h('label', { class: 'bi-checkrow' }, x, ' ' + name);
      };
      const title = h('input', { class: 'studio-in', value: p.title || '', 'data-persp-title': pn, 'aria-label': 'Title of ' + pn, disabled: M.readOnly });
      title.addEventListener('change', () => M.edit((doc) => { if (title.value.trim()) { doc.perspectives[pn].title = title.value.trim(); } else { delete doc.perspectives[pn].title; } }, true));
      const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-persp-remove': pn, text: 'Remove', disabled: M.readOnly });
      rm.addEventListener('click', () => M.edit((doc) => { delete doc.perspectives[pn]; if (!Object.keys(doc.perspectives).length) { delete doc.perspectives; } }));
      const det = h('details', { class: 'bi-md-persp', 'data-perspective': pn, open: openPersp.has(pn) || null }, h('summary', {}, h('strong', { text: p.title || pn }), h('small', { class: 'text-muted-d', 'data-persp-count': '', text: ' ' + ((p.fields || []).length + (p.measures || []).length) + ' items' })),
        h('div', { class: 'bi-md-row' }, h('label', {}, 'Title ', title), rm),
        h('fieldset', {}, h('legend', { text: 'Measures' }), h('div', { class: 'bi-layer-picks' }, measureNames().map((n) => tick('measures', n)))),
        h('fieldset', {}, h('legend', { text: 'Fields' }), h('div', { class: 'bi-layer-picks' }, allFields().map((f) => tick('fields', f.name)))));
      det.addEventListener('toggle', () => { if (det.open) { openPersp.add(pn); } else { openPersp.delete(pn); } });
      box.appendChild(det);
    });
    if (!M.readOnly) {
      const name = h('input', { class: 'studio-in mono', 'data-persp-new': '', placeholder: 'credit-officer', 'aria-label': 'Name of the new perspective' });
      const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-persp-add': '', text: 'New perspective' });
      add.addEventListener('click', () => {
        const n = B.slug(name.value || '');
        if (!n || (M.doc.perspectives || {})[n]) { B.near(add, 'A new name: letters, digits and -.', true); return; }
        M.edit((doc) => { doc.perspectives = doc.perspectives || {}; doc.perspectives[n] = { title: name.value.trim(), fields: [], measures: [] }; });
        openPersp.add(n);
      });
      box.appendChild(h('p', { class: 'bi-md-row' }, name, add));
    }
    pane.appendChild(box);
  }

  // ---- security: hidden-for and Preview as ---------------------------------------------------------------------------
  const hiddenFor = () => [].concat(((M.doc.security || {})['hidden-for']) || []);
  function drawSecurityExtras() {
    const pane = q('[data-security-pane]');
    if (!pane || q('[data-hidden-for]', pane)) { return; }
    const list = h('ul', { class: 'bi-layer-list' });
    hiddenFor().forEach((x, i) => {
      const rm = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove this entry', 'data-hidden-remove': String(i), disabled: M.readOnly }, B.icon('x-lg'));
      rm.addEventListener('click', () => M.edit((doc) => { const a = [].concat(doc.security['hidden-for']); a.splice(i, 1); if (a.length) { doc.security['hidden-for'] = a; } else { delete doc.security['hidden-for']; } }));
      list.appendChild(h('li', { 'data-hidden-entry': String(i) }, h('strong', { text: [].concat(x.roles || []).join(', ') }), ' do not see ', h('code', { text: [].concat(x.fields || [], x.measures || []).join(', ') }), ' ', rm));
    });
    if (!list.children.length) { list.appendChild(h('li', { class: 'text-muted-d', text: 'Nothing hidden: every reader sees every field and measure their rows allow.' })); }
    const box = h('section', { class: 'bi-sec bi-form', 'data-hidden-for': '', 'data-keep': '' }, h('h3', { text: 'Hidden from some roles' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'For these roles the field or measure does not exist: it is left out of the Fields pane, of SQL and of AI answers, and naming it is "not found". Masking (above) shows that a value exists; hiding does not.' }), list);
    if (!M.readOnly) {
      const who = h('input', { class: 'studio-in', 'data-hidden-roles': '', list: 'mdHiddenRoles', placeholder: 'external', 'aria-label': 'Roles (comma-separated)' });
      const what = h('div', { class: 'bi-layer-picks', role: 'group', 'aria-label': 'Fields and measures to hide' },
        allFields().map((f) => h('label', { class: 'bi-checkrow' }, h('input', { type: 'checkbox', value: f.name, 'data-hidden-pick': f.name, 'data-kind': 'field' }), ' ' + f.name))
          .concat(measureNames().map((n) => h('label', { class: 'bi-checkrow' }, h('input', { type: 'checkbox', value: n, 'data-hidden-pick': n, 'data-kind': 'measure' }), ' ' + n))));
      const go = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-hidden-add': '', text: 'Hide them' });
      go.addEventListener('click', () => {
        const rs = who.value.split(',').map((x) => x.trim()).filter(Boolean);
        const picked = B.qa('[data-hidden-pick]', what).filter((c) => c.checked);
        if (!rs.length || !picked.length) { B.near(go, 'Name the roles and tick what they must not see.', true); return; }
        const entry = { roles: rs };
        const fs = picked.filter((c) => c.getAttribute('data-kind') === 'field').map((c) => c.value), ms = picked.filter((c) => c.getAttribute('data-kind') === 'measure').map((c) => c.value);
        if (fs.length) { entry.fields = fs; }
        if (ms.length) { entry.measures = ms; }
        M.edit((doc) => { doc.security = doc.security || {}; doc.security['hidden-for'] = [].concat(doc.security['hidden-for'] || [], [entry]); });
        B.near(go, 'Hidden from ' + rs.join(', ') + '.', false);
      });
      box.append(h('datalist', { id: 'mdHiddenRoles' }, roles().map((r) => h('option', { value: r }))), h('label', { class: 'bi-frow' }, h('span', { text: 'Roles' }), who),
        h('fieldset', {}, h('legend', { text: 'What they must not see' }), what), go);
    }
    pane.appendChild(box);
    pane.appendChild(previewAs());
  }
  /** Preview as a role: the server answers the model query as that role would get it (rows and masks); a server that cannot says so, never shows yours. */
  function previewAs() {
    const role = sel(roles().map((r) => [r, r]), '', { 'data-preview-role': '', 'aria-label': 'Role' });
    const go = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-preview-as': '', text: 'Preview' });
    const out = h('div', { class: 'tbl-wrap', 'data-preview-as-out': '', 'aria-live': 'polite' });
    go.addEventListener('click', () => {
      if (!role.value) { B.near(go, 'Choose a role.', true); return; }
      if (M.dirty) { B.near(go, 'Preview reads the saved draft: Save first.', true); return; }
      const by = M.tryField(), m = measureNames()[0];
      const query = { mq: 1, select: { fields: by ? [by] : [], measures: m ? [m] : [] }, limit: 12 };
      B.busy(go, () => B.api('POST', '/models/' + encodeURIComponent(M.doc.model + '@' + String(M.doc.version || '1').split('.')[0]) + '/preview-as', { role: role.value, query }).then((a) => {
        B.clear(out).appendChild(h('table', { class: 'tbl bi-tbl bi-md-mini', 'data-plain': '', 'data-previewed-as': role.value },
          h('caption', { class: 'text-muted-d', text: 'As ' + role.value + ((a.meta || {}).masked && a.meta.masked.length ? '; masked: ' + a.meta.masked.join(', ') : '') + ((a.meta || {}).hidden && a.meta.hidden.length ? '; hidden: ' + a.meta.hidden.join(', ') : '') }),
          h('thead', {}, h('tr', {}, (a.columns || []).map((c) => h('th', { scope: 'col', text: c.name || c })))),
          h('tbody', {}, (a.rows || []).map((r) => h('tr', {}, r.map((v) => h('td', { text: v == null ? '—' : typeof v === 'number' ? B.fmt(v) : String(v) })))))));
      }).catch((e) => { B.clear(out).appendChild(h('p', { class: 'bi-near bi-bad', 'data-preview-as-error': e.code || '', text: e.status === 404 || e.status === 405 ? 'This server cannot preview as another role yet: the answer is never shown as yours instead.' : e.message })); }));
    });
    return h('section', { class: 'bi-sec bi-form', 'data-preview-as-box': '', 'data-keep': '' }, h('h3', { text: 'Preview as a role' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'The saved draft\'s first measure by a suggested field, answered by the server as the role would get it: its rows, masks and hidden fields.' }),
      h('div', { class: 'bi-md-row' }, h('label', {}, 'Role ', role), go), out);
  }

  // ---- contract: the diff, the bump, the impact ----------------------------------------------------------------------
  let diffTimer = null, diffSeq = 0;
  function drawContractExtras() {
    const pane = q('[data-contract-pane]');
    if (!pane || !M.item) { return; }
    let box = q('[data-contract-diff]', pane);
    if (!box) { box = h('section', { class: 'bi-sec', 'data-contract-diff': '', 'aria-live': 'polite' }, h('h3', { text: 'Changes since the published version' }), h('p', { class: 'text-muted-d', text: 'Comparing…' })); pane.insertBefore(box, pane.firstChild); }
    window.clearTimeout(diffTimer);
    diffTimer = window.setTimeout(() => {
      const mine = ++diffSeq;
      B.layer.post('contract', { id: M.item.id, doc: M.doc }).then((r) => {
        if (mine !== diffSeq || !box.isConnected) { return; }
        const head = box.firstChild;
        B.clear(box).appendChild(head);
        if (!r.published) { box.appendChild(h('p', { class: 'text-muted-d', 'data-contract-first': '', text: 'Never published: the first publish sets the contract. Version ' + (M.doc.version || '1.0.0') + ' is fine.' })); return; }
        if (!r.changes.length) { box.appendChild(h('p', { class: 'bi-ok', 'data-contract-same': '', text: 'No change from the published version ' + r.from + '.' })); return; }
        box.appendChild(h('ul', { class: 'bi-layer-list', 'data-contract-changes': '' }, r.changes.map((c) => h('li', { 'data-change-level': c.level }, h('span', { class: 'bi-badge' + (c.level === 'major' ? ' bi-warnbadge' : ''), text: c.level === 'none' ? 'no version' : c.level }), ' ' + c.text, c.usedBy && c.usedBy.length ? h('small', { class: 'text-muted-d', 'data-change-used-by': String(c.usedBy.length), text: ' · used by ' + c.usedBy.slice(0, 5).map((u) => u.title).join(', ') + (c.usedBy.length > 5 ? '…' : '') }) : null))));
        const words = { major: 'a new major version: consumers stay on ' + r.ref + ' until they move', minor: 'a minor version: consumers get the additions', patch: 'a patch', none: 'no new version' };
        const p = h('p', { 'data-contract-bump': r.bump }, 'These changes need ', h('strong', { text: words[r.bump] }), '. ');
        if (r.bump !== 'none' && !r.ok && !M.readOnly) {
          const use = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-contract-use': r.next, text: 'Use version ' + r.next });
          use.addEventListener('click', () => M.edit((doc) => { doc.version = r.next; }));
          p.append('The draft says ' + r.version + '. ', use);
        } else if (r.bump !== 'none') { p.append('The draft\'s version ' + r.version + ' is right.'); }
        box.appendChild(p);
        if (r.known && r.consumers.length) {
          box.appendChild(h('p', { 'data-contract-impact': String(r.consumers.length) }, h('strong', { text: r.consumers.length + ' item' + (r.consumers.length === 1 ? ' reads ' : 's read ') + r.ref + ': ' }),
            r.consumers.slice(0, 12).map((c) => c.title + ' (' + c.kind + ')').join(', ') + (r.consumers.length > 12 ? '…' : '') + '.'));
        }
      }).catch((e) => { if (box.isConnected) { box.appendChild(h('p', { class: 'bi-near bi-bad', text: e.message })); } });
    }, 400);
  }

  function redraw(fromTab) {
    if (!M.doc) { return; }
    const fp = q('[data-fields-pane]');
    const typing = fp.contains(document.activeElement) && /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement.tagName) && document.activeElement.type !== 'checkbox';
    if (M.tab === 'fields' && (fromTab || !typing)) { drawFields(); }        // a Check landing while someone types never redraws under them
    if (M.tab === 'security') { drawSecurityExtras(); }
    if (M.tab === 'contract') { drawContractExtras(); }
  }
  M.on('redraw', () => redraw(false));
  M.on('tab', () => redraw(true));
})();
