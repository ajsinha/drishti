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
/* The model layer's start page and dataset page (docs/architecture/BI_MODEL_SPEC.md sections 7 and 9.1): the pack-generated datasets (Use starts a report
   on the implicit model at once), the person's datasets and the models; New model; and one dataset's grain, keys, meanings, time mode and security. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const canvas = document.getElementById('biCanvas');
  if (!canvas || !B || !B.layer) { return; }
  const h = B.h, q = (s) => B.q(s, canvas);
  const say = (t, bad) => B.say(q('[data-msg]'), t, bad);
  const badge = (text, cls, icon) => h('span', { class: 'bi-badge ' + (cls || '') }, icon ? B.icon(icon) : null, ' ' + text);
  const MODE = { live: 'live', snapshot: 'snapshot (one per business date)', history: 'history (a time window)', cached: 'snapshot (one per business date)' };

  /** Starts a report on a model or a dataset's implicit model: asks where, makes the draft, opens the designer. */
  function startReport(anchor, target, title) {
    return B.askWorkspace(anchor, 'Start the report', 'Start the report in').then((ws) => B.busy(anchor, () => B.layer.post('report', Object.assign({ title: title, workspace: ws }, target))))
      .then((r) => { window.location.href = r.href; }).catch((e) => { if (!e.cancelled) { B.near(anchor, e.message, true); } });
  }
  function makeModel(anchor, from, title) {
    return B.askWorkspace(anchor, 'Make the model', 'Make the model in').then((ws) => B.busy(anchor, () => B.layer.post('new', { from: from, title: title, workspace: ws })))
      .then((r) => { window.location.href = r.href; }).catch((e) => { if (!e.cancelled) { B.near(anchor, e.message, true); } });
  }

  // ---- the start page ------------------------------------------------------------------------------------------------
  function startPage(root) {
    let all = { packs: [], items: [], models: [] };
    function card(d, kind) {
      const use = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-use': d.name, text: 'Use' });
      use.addEventListener('click', () => startReport(use, kind === 'model' ? { model: d.query || d.name + '@1' } : { dataset: d.name }, d.title + ' report'));
      const open = kind === 'model' ? (d.kind === 'compat' ? '/bi/d/' + B.id2path(d.id) : '/bi/md/' + B.id2path(d.id)) : '/bi/ds/' + encodeURIComponent(kind === 'pack' ? d.name : d.id);
      const meta = kind === 'pack' ? d.pack + ' pack · ' + (MODE[d.mode] || d.mode) + ' · ' + d.columns + ' columns' : kind === 'model' ? (d.kind === 'compat' ? 'dataset (version 1, read as a model)' : 'model ' + (d.version || '')) + ' · ' + (d.status || '') : (d.status || '');
      return h('article', { class: 'bi-card', 'data-layer-card': d.name, 'data-kind': kind, 'data-search': (d.name + ' ' + d.title + ' ' + (d.pack || '') + ' ' + (d.kind || '')).toLowerCase() },
        h('h3', {}, h('a', { href: open, text: d.title })),
        h('p', { class: 'bi-card-meta mono', text: kind === 'model' ? (d.name || '') : d.name }),
        h('p', { class: 'bi-card-badges' }, d.endorsement === 'certified' ? badge('certified', 'bi-certified', 'patch-check') : null, kind === 'pack' ? badge('read-only', '', 'lock') : null),
        h('p', { class: 'bi-card-meta', text: meta }),
        d.description ? h('p', { class: 'bi-card-desc text-muted-d', text: String(d.description).slice(0, 160) }) : null,
        h('p', { class: 'bi-actions' }, use, ' ', h('a', { class: 'btn-pill btn-ghost bi-small', href: open, text: kind === 'model' && d.kind !== 'compat' ? 'Open the designer' : 'Open' })));
    }
    function draw() {
      const t = (q('[data-search]').value || '').trim().toLowerCase();
      const match = (d) => !t || (d.name + ' ' + d.title + ' ' + (d.pack || '') + ' ' + (d.kind || '')).toLowerCase().indexOf(t) >= 0;
      [['items', 'item'], ['models', 'model']].forEach(([key, kind]) => {
        const box = B.clear(q('[data-' + key + ']'));
        const list = all[key].filter(match);
        list.forEach((d) => box.appendChild(card(d, kind)));
        q('[data-' + key + '-empty]').hidden = list.length > 0;
      });
      // the packs' datasets, one collapsible group per pack (a kind with no columns has nothing to read and is left out)
      const packs = B.clear(q('[data-packs]')), by = {};
      all.packs.filter((d) => d.columns == null || d.columns > 0).filter(match).forEach((d) => { (by[d.pack] = by[d.pack] || []).push(d); });
      Object.keys(by).sort().forEach((pk) => {
        const cards = h('div', { class: 'bi-cards' }, by[pk].map((d) => card(d, 'pack')));
        packs.appendChild(h('details', { class: 'bi-packgroup', 'data-pack-group': pk, open: !!t || Object.keys(by).length === 1 }, h('summary', {}, h('strong', { text: pk }), h('small', { class: 'text-muted-d', text: ' ' + by[pk].length + (by[pk].length === 1 ? ' dataset' : ' datasets') })), cards));
      });
      q('[data-packs-empty]').hidden = Object.keys(by).length > 0;
    }
    q('[data-search]').addEventListener('input', draw);
    // New model: a title, a workspace, the datasets to start with
    const form = q('[data-new-model-form]');
    q('[data-new-model]').addEventListener('click', () => {
      form.hidden = false;
      B.fillWorkspaces(q('[data-nm-ws]')).catch(() => {});
      const picks = B.clear(q('[data-nm-picks]'));
      all.packs.concat(all.items).forEach((d) => picks.appendChild(h('label', { class: 'bi-checkrow', 'data-search': (d.name + ' ' + d.title).toLowerCase() },
        h('input', { type: 'checkbox', value: d.kind && !d.pack ? d.id : d.name, 'data-nm-pick': d.name }), ' ' + d.title + ' ', h('small', { class: 'text-muted-d mono', text: d.name }))));
      q('[data-nm-title]').focus();
    });
    q('[data-nm-search]').addEventListener('input', (e) => { const t = e.target.value.toLowerCase(); B.qa('[data-nm-picks] label', canvas).forEach((l) => { l.hidden = !!t && l.getAttribute('data-search').indexOf(t) < 0; }); });
    q('[data-nm-cancel]').addEventListener('click', () => { form.hidden = true; });
    q('[data-nm-go]').addEventListener('click', () => {
      const go = q('[data-nm-go]');
      const title = q('[data-nm-title]').value.trim();
      if (!title) { B.near(go, 'Give the model a title.', true); q('[data-nm-title]').focus(); return; }
      const datasets = B.qa('[data-nm-pick]', canvas).filter((c) => c.checked).map((c) => c.value);
      B.busy(go, () => B.layer.post('new', { title, workspace: q('[data-nm-ws]').value || 'me', datasets })).then((r) => { window.location.href = r.href; }).catch((e) => B.near(go, e.message, true));
    });
    B.layer.get('datasets').then((d) => {
      all = { packs: d.packs || [], items: (d.items || []).map((x) => Object.assign({ kind: 'implicit' }, x)), models: d.models || [] };
      draw();
      root.setAttribute('data-ready', 'yes');
    }).catch((e) => say(e.message, true));
  }

  // ---- one dataset ---------------------------------------------------------------------------------------------------
  function datasetPage(root) {
    const name = decodeURIComponent(canvas.getAttribute('data-item') || '');
    B.layer.get('dataset?name=' + encodeURIComponent(name)).then((d) => {
      const i = d.info;
      q('[data-title]').textContent = i.title;
      document.title = i.title + ' · ' + document.title;
      q('[data-desc]').textContent = i.description || '';
      const badges = B.clear(q('[data-badges]'));
      if (i.endorsement === 'certified') { badges.appendChild(badge('certified', 'bi-certified', 'patch-check')); }
      if (i.generated) { badges.appendChild(badge('generated by the ' + i.source.pack + ' pack', '', 'box-seam')); }
      if (i.readOnly) { badges.appendChild(badge('read-only', '', 'lock')); }
      badges.appendChild(badge('version ' + i.version, '', 'tag'));
      const facts = B.clear(q('[data-facts]'));
      const fact = (k, v, key) => { facts.appendChild(h('dt', { text: k })); facts.appendChild(h('dd', { 'data-fact': key }, v)); };
      fact('Name', h('code', { text: i.name }), 'name');
      fact('One row is', i.grain.says || '(not said)', 'grain');
      fact('Keys', h('span', {}, (i.grain.keys.length ? i.grain.keys : ['id']).map((k) => h('span', { class: 'bi-chip mono', text: k }))), 'keys');
      fact('Time', (MODE[i.time.mode] || i.time.mode) + (i.time.asOf ? ' · a query without a date reads the ' + (i.time.asOf === 'business-date' ? 'latest business date with rows' : 'latest load') : ''), 'time');
      fact('Source', i.source.type === 'pack' ? 'the ' + i.source.kind + ' kind of the ' + i.source.pack + ' pack' + (i.source.connector ? ', through ' + i.source.connector : '') : i.source.type === 'connector' ? 'a query in ' + i.source.connector
        : i.source.type === 'python' ? 'the Python Transform ' + i.source.python : i.source.type === 'model' ? 'the answer of ' + i.source.model : i.extends ? 'narrows ' + i.extends : i.source.type, 'source');
      if (i.refresh) { fact('Refresh', typeof i.refresh === 'string' ? i.refresh : (i.refresh['on-load'] ? 'when new data loads' : JSON.stringify(i.refresh)), 'refresh'); }
      const tb = B.clear(q('[data-columns] tbody'));
      i.columns.forEach((c) => {
        const add = c.additive === true ? 'yes' : c.additive === false ? 'never' : c.additive && c.additive['over-time'] ? 'not over time (' + c.additive['over-time'] + ')' : (['amount', 'quantity'].indexOf(c.role) >= 0 ? 'yes' : '');
        const def = c.aggregate ? c.aggregate + ' of ' + c.name : '';
        tb.appendChild(h('tr', { 'data-column': c.name }, h('th', { scope: 'row', class: 'mono', text: c.name }), h('td', { text: c.type }), h('td', { text: c.role }),
          h('td', { text: c.meaning || '' }), h('td', { text: c.references ? c.references + ' →' : '' }), h('td', { text: c.currency ? 'currency: ' + c.currency : c.unitOf || '' }),
          h('td', { text: add }), h('td', { text: def })));
      });
      const sec = B.clear(q('[data-security]'));
      (i.rows || []).forEach((r) => sec.appendChild(h('li', {}, 'Role ', h('strong', { text: [].concat(r.role).join(', ') }), ' sees rows where ', h('code', { text: r.where }))));
      if (i.masked.length) { sec.appendChild(h('li', {}, 'Masked for readers without raw access: ', h('code', { text: i.masked.join(', ') }))); }
      if (!sec.children.length) { sec.appendChild(h('li', { text: i.generated ? 'The pack\'s entitlements decide who may read it (whoever may open the ' + i.source.kind + ' kind). No row rule: a model adds one when it needs one.' : 'No row rule: everyone who may read the dataset sees every row.' })); }
      q('[data-yaml]').textContent = d.yaml;
      q('[data-yaml-note]').textContent = i.readOnly ? 'Written by Drishti from the pack; read-only. To change it, Narrow it: a dataset of your own that extends this one.' : 'The dataset as stored.';
      q('[data-narrow]').href = '/bi/datasets/new-layer?extends=' + encodeURIComponent(i.name);
      q('[data-use]').addEventListener('click', (e) => startReport(e.currentTarget, { dataset: i.name }, i.title + ' report'));
      q('[data-make-model]').addEventListener('click', (e) => makeModel(e.currentTarget, i.item || i.name, i.title + ' model'));
      root.setAttribute('data-ready', 'yes');
    }).catch((e) => { say(e.message, true); root.setAttribute('data-ready', 'error'); });
  }

  const start = canvas.querySelector('[data-models-root]');
  if (start) { startPage(start); }
  const one = canvas.querySelector('[data-layer-dataset]');
  if (one) { datasetPage(one); }
})();
