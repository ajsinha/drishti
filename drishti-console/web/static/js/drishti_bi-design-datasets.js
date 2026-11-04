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
/* The designer with several datasets (BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 16): the Fields pane's "Add dataset" and one tree for each, the dataset a visual reads (`dataset:` on
   the visual, blank for the report's own), the refusal of a field of another dataset in a visual that already reads one, and the LINKS panel that suggests
   fields of different datasets that mean the same (ticked ones are written to the report's `links:`) and offers a composed dataset built on them as a draft
   to review. The engine, the validator and the viewer already understand all of it; this file is the author's way in. Same-origin JSON only. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;
  const strip = (r) => String(r || '').replace(/^file:dataset:/, '');

  let all = [];                        // every dataset the person can see, usable or not (the picker greys the unusable with the reason)
  let list = [];                       // the readable datasets: [{id, name, title, valid}]
  const cache = new Map();             // canonical id -> {ref, id, title, dataset, idx}
  let extra = [];                      // canonical ids shown in the Fields pane besides the main one
  D.dsVersion = 0;

  // ---- who is who ----------------------------------------------------------------------------------------------------
  D.canon = function (ref) {
    if (!ref) { return D.dataset.id; }
    const r = strip(ref);
    const d = list.find((x) => strip(x.id) === r || x.name === r || x.title === r);
    if (d) { return d.id; }
    return strip(D.dataset.id) === r || D.dataset.name === r || D.model.dataset === ref ? D.dataset.id : ref;
  };
  D.isOtherRef = (ref) => !!ref && D.canon(ref) !== D.dataset.id;
  D.isOtherDataset = (v) => D.isOtherRef(v.dataset);
  D.sameDs = (a, b) => D.canon(a || '') === D.canon(b || '');
  const entryOf = (ref) => (D.isOtherRef(ref) ? cache.get(D.canon(ref)) : null);
  D.dsOf = (v) => entryOf(v && v.dataset) || { dataset: D.dataset, idx: D.idx };
  D.idxOf = (v) => D.dsOf(v).idx;
  D.idxForRef = (ref) => (entryOf(ref) || { idx: D.idx }).idx;
  D.dsTitle = function (ref) {
    if (!D.isOtherRef(ref)) { return D.dataset.title || strip(D.dataset.id) || D.model.dataset; }
    const e = cache.get(D.canon(ref)), d = list.find((x) => x.id === D.canon(ref));
    return (e && e.title) || (d && d.title) || strip(ref);
  };
  D.withIdx = function (idx, fn) { const was = D.curIdx; D.curIdx = idx; try { return fn(); } finally { D.curIdx = was || null; } };
  const hasFields = (v) => Object.keys(v.wells || {}).some((w) => (v.wells[w] || []).length);
  /** The refusal text when `from`'s field may not join the visual (it already reads another dataset), else ''. */
  D.dsConflict = function (v, from) {
    if (!hasFields(v) || D.sameDs(v.dataset || '', from || '')) { return ''; }
    return 'Refused: this visual reads ' + D.dsTitle(v.dataset || '') + '; put ' + D.dsTitle(from || '') + ' fields in another visual, or make a composed dataset.';
  };
  /** An empty visual takes the dataset of the field dropped on it (blank when it is the report's main one). Call inside D.change. */
  D.dsClaim = function (v, from) {
    if (hasFields(v)) { return; }
    if (D.isOtherRef(from)) { v.dataset = strip(from); } else { delete v.dataset; }
  };
  D.datasetUsed = (ref) => D.model.pages.some((p) => p.visuals.some((v) => v.dataset && D.canon(v.dataset) === D.canon(ref)));
  D.extraEntries = () => extra.map((id) => cache.get(id)).filter(Boolean);

  // ---- loading and the Fields pane -----------------------------------------------------------------------------------
  function load(ref) {
    const id = D.canon(ref);
    if (cache.has(id)) { return Promise.resolve(cache.get(id)); }
    return B.api('GET', '/reports/dataset?ref=' + encodeURIComponent(ref)).then((ds) => {
      const e = { ref: strip(id), id, title: ds.title || (list.find((x) => x.id === id) || {}).title || strip(id), dataset: ds, idx: B.index(ds) };
      cache.set(id, e);
      return e;
    });
  }
  function refresh() {
    D.dsVersion++;
    if (D.drawFields) { D.drawFields(); }
    drawPicker();
    drawLinks();
    D.emit('model', 'datasets');
  }
  D.addDataset = function (ref) {
    const id = D.canon(ref);
    if (id === D.dataset.id || extra.indexOf(id) >= 0) { return Promise.resolve(); }
    return load(ref).then(() => { extra.push(id); refresh(); D.say('Added ' + D.dsTitle(ref) + ' to the Fields pane. Drag its fields into an empty visual.'); }).catch((e) => D.say(e.message, true));
  };
  D.removeDataset = function (ref) {
    if (D.datasetUsed(ref)) { D.say('A visual reads ' + D.dsTitle(ref) + ': it cannot be removed.', true); return; }
    extra = extra.filter((x) => x !== D.canon(ref));
    refresh();
  };

  function drawPicker() {
    const sel = D.q('[data-dataset-picker]');
    if (!sel) { return; }
    B.clear(sel);
    sel.appendChild(h('option', { value: '', text: 'Choose a dataset' }));
    all.filter((d) => d.id !== D.dataset.id && extra.indexOf(d.id) < 0).forEach((d) => {
      const why = B.datasetWhy(d);
      sel.appendChild(h('option', { value: strip(d.id), text: d.title + (why ? ' (' + why + ')' : ''), disabled: !!why }));
    });
  }

  // ---- links ---------------------------------------------------------------------------------------------------------
  function columnsOf(entry) {
    const out = [];
    (entry.dataset.tables || []).forEach((t) => (t.columns || []).forEach((c) => out.push({ name: t.name + '.' + c.name, col: c.name, type: c.type, desc: (c.description || '').trim().toLowerCase(), sem: c.semantic || c.concept || '' })));
    return out;
  }
  function entries() {
    return [{ ref: '', id: D.dataset.id, title: D.dsTitle(''), dataset: D.dataset, idx: D.idx }].concat(D.extraEntries());
  }
  /** Pairs of fields of different datasets with the same name and type, or the same stated meaning. */
  function suggestions() {
    const es = entries(), out = [], seen = new Set();
    for (let i = 0; i < es.length; i++) {
      for (let j = i + 1; j < es.length; j++) {
        const bs = columnsOf(es[j]);
        columnsOf(es[i]).forEach((a) => {
          bs.forEach((b) => {
            const sameName = a.name === b.name && a.type === b.type;
            const sameMeaning = !sameName && a.type === b.type && ((a.sem && a.sem === b.sem) || (a.desc && a.desc === b.desc));
            if (!sameName && !sameMeaning) { return; }
            const key = [a.name, b.name].join('|') + '#' + i + '#' + j;
            if (seen.has(key)) { return; }
            seen.add(key);
            out.push({ fields: [a.name, b.name], from: es[i], to: es[j], reason: sameName ? 'same name and type' : 'same meaning' });
          });
        });
      }
    }
    return out;
  }
  const sameFields = (x, y) => x.length === y.length && x.every((f) => y.indexOf(f) >= 0);
  const linked = (fields) => (D.model.links || []).some((l) => sameFields(l.fields, fields));

  function drawLinks() {
    const box = D.q('[data-links-panel]');
    if (!box) { return; }
    B.clear(box);
    const es = entries();
    box.hidden = es.length < 2;
    if (es.length < 2) { return; }
    box.appendChild(h('h3', { class: 'bi-fhead', text: 'Links between datasets' }));
    box.appendChild(h('p', { class: 'text-muted-d', text: 'Fields of different datasets that mean the same thing. Tick one to link it: a slicer or a click on one dataset then filters the visuals of the other.' }));
    const sg = suggestions();
    const ul = h('ul', { class: 'bi-linklist', 'data-link-suggestions': '' });
    sg.forEach((s) => {
      const id = 'biLink' + ul.children.length;
      const cb = h('input', { type: 'checkbox', id, 'data-link-tick': s.fields.join(' = ') });
      cb.checked = linked(s.fields);
      cb.addEventListener('change', () => D.change(cb.checked ? 'Add a link' : 'Remove a link', (m) => {
        if (cb.checked) { if (!linked(s.fields)) { (m.links = m.links || []).push({ fields: s.fields.slice() }); } } else { m.links = (m.links || []).filter((l) => !sameFields(l.fields, s.fields)); if (!m.links.length) { delete m.links; } }
      }));
      ul.appendChild(h('li', {}, cb, h('label', { for: id, text: ' ' + s.fields.join(' = ') + ' (' + s.from.title + ' and ' + s.to.title + ': ' + s.reason + ')' })));
    });
    if (!sg.length) { ul.appendChild(h('li', { class: 'text-muted-d', text: 'No field with the same name and type, or the same meaning, in these datasets. Write a link in the Report settings by hand, or make a composed dataset.' })); }
    box.appendChild(ul);
    const mine = D.model.links || [];
    if (mine.length) {
      box.appendChild(h('h4', { class: 'bi-fhead', text: 'Links in this report' }));
      const cur = h('ul', { class: 'bi-linklist', 'data-links-current': '' });
      mine.forEach((l, i) => {
        const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove the link ' + l.fields.join(' = '), 'data-link-remove': String(i) }, B.icon('x-lg'));
        x.addEventListener('click', () => D.change('Remove a link', (m) => { m.links.splice(i, 1); if (!m.links.length) { delete m.links; } }));
        cur.appendChild(h('li', {}, h('code', { text: l.fields.join(' = ') }), ' ', x));
      });
      box.appendChild(cur);
    }
    const make = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-make-composed': '', text: 'Make a composed dataset from these' });
    make.addEventListener('click', () => composedDraft(sg));
    box.appendChild(make);
    box.appendChild(h('div', { 'data-composed-draft': '', hidden: true }));
  }
  let usedSig = '';
  D.on('model', (label) => {
    if (label === 'datasets') { usedSig = extra.map((id) => D.datasetUsed(id)).join(); return; }
    drawLinks();
    const sig = extra.map((id) => D.datasetUsed(id)).join();           // a dataset's Remove button follows whether a visual still reads it
    if (sig !== usedSig) { usedSig = sig; if (D.drawFields) { D.drawFields(); } }
  });

  /** A composed dataset (BI_GUIDE.md › Datasets: the YAML, the expression language and the security rules, section 11.1) on the first ticked link, or the first suggestion: shown as text for review, created as a draft only on the button. */
  function composedDraft(sg) {
    const tick = sg.find((s) => linked(s.fields)) || sg[0];
    const host = D.q('[data-composed-draft]');
    B.clear(host);
    host.hidden = false;
    if (!tick) { host.appendChild(h('p', { class: 'bi-warn', text: 'There is no pair of fields to join on yet: link two fields first, or write the dataset by hand on the Datasets page.' })); return; }
    const alias = (e, n) => (B.slug(strip(e.id)).replace(/-/g, '_') || 'src') + '_' + n;
    const keyName = tick.fields[0].split('.').slice(1).join('_');
    const src = (e, field, n) => {
      const ms = (e.dataset.measures || []).slice(0, 1).map((m) => m.name);
      return '  ' + alias(e, n) + ':\n    dataset: ' + strip(e.id) + '\n    by: [' + JSON.stringify(field + ' as ' + keyName) + ']\n' + (ms.length ? '    measures: [' + ms.map((x) => JSON.stringify(x)).join(', ') + ']\n' : '') + '    key: [' + keyName + ']\n';
    };
    const a = alias(tick.from, 1), b = alias(tick.to, 2);          // numbered, so two readings of one dataset keep apart
    const name = 'composed-' + B.slug(strip(tick.from.id)) + '-' + B.slug(strip(tick.to.id));
    const yaml = 'rachana: 1\ndataset: ' + name + '\ntitle: ' + JSON.stringify('Composed: ' + tick.from.title + ' and ' + tick.to.title) + '\nfrom:\n' + src(tick.from, tick.fields[0], 1) + src(tick.to, tick.fields[1], 2) +
      'join:\n  - { left: ' + a + ', right: ' + b + ', on: ['+ keyName + '], type: left }\n';
    host.appendChild(h('p', { class: 'text-muted-d', text: 'A draft to review, not yet saved. Creating it adds a dataset draft to your workspace and opens it in the Model view.' }));
    host.appendChild(h('pre', { class: 'bi-composed-yaml', 'data-composed-yaml': '', text: yaml }));
    const go = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-composed-create': '', text: 'Create the draft and review it' });
    go.addEventListener('click', () => {
      B.api('POST', '/workspaces/me/items', { kind: 'dataset', title: 'Composed: ' + tick.from.title + ' and ' + tick.to.title, description: 'Started from the designer\'s links.', body: yaml })
        .then((it) => { window.location.href = '/bi/m/' + B.id2path(it.id); }).catch((e) => D.say(e.message, true));
    });
    host.appendChild(go);
  }

  // ---- start ---------------------------------------------------------------------------------------------------------
  D.modules.push(function () {
    cache.set(D.dataset.id, { ref: '', id: D.dataset.id, title: D.dataset.title || strip(D.dataset.id), dataset: D.dataset, idx: D.idx });
    const toggle = D.q('[data-add-dataset]'), form = D.q('[data-add-dataset-form]');
    if (toggle) {
      toggle.addEventListener('click', () => { form.hidden = !form.hidden; if (!form.hidden) { D.q('[data-dataset-picker]').focus(); } });
      D.q('[data-dataset-add-go]').addEventListener('click', () => {
        const sel = D.q('[data-dataset-picker]');
        if (!sel.value) { D.say('Choose a dataset to add.', true); return; }
        D.addDataset(sel.value).then(() => { form.hidden = true; });
      });
    }
    B.api('GET', '/datasets?drafts=1').then((r) => {
      all = r.datasets || [];
      list = all.filter((d) => !B.datasetWhy(d));
      const refs = [];
      D.model.pages.forEach((p) => p.visuals.forEach((v) => { if (v.dataset && D.isOtherRef(v.dataset) && refs.indexOf(D.canon(v.dataset)) < 0) { refs.push(D.canon(v.dataset)); } }));
      (new URLSearchParams(window.location.search).get('also') || '').split(',').map((x) => x.trim()).filter(Boolean).forEach((x) => { const id = D.canon(x); if (id !== D.dataset.id && refs.indexOf(id) < 0 && list.some((d) => d.id === id)) { refs.push(id); } });          // New report: Also add
      return Promise.all(refs.map((id) => load(id).then(() => { if (extra.indexOf(id) < 0) { extra.push(id); } }).catch(() => {})));
    }).catch(() => {}).then(() => { refresh(); });
  });
})();
