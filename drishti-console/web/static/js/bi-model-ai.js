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
/* The model designer's AI helper (docs/guides/BI_GUIDE.md section 29.10), on the designer's object (window.drishtiModel, bi-model-designer.js): the server proposes
   relationships, measures (with descriptions) and field descriptions for the saved model from its names, types and descriptions (never a value), each checked
   against the model; Accept makes it one undoable edit of the draft (relationships:, measures:, fields:), and Save keeps it. */
(function () {
  'use strict';
  const M = window.drishtiModel, B = window.drishtiBi;
  const canvas = document.getElementById('biCanvas');
  if (!M || !B || !canvas) { return; }
  const h = M.h, q = M.q;
  const btn = q('[data-ai-helper]'), box = q('[data-ai-box]');
  if (!btn || !box) { return; }
  const itemId = canvas.getAttribute('data-item');

  B.api('GET', '/ai/status').then((s) => { btn.hidden = !(s.enabled && s.assist); }).catch(() => {});

  function accept(it) {
    if (it.kind === 'relationship') {
      const r = it.data;
      if ((M.doc.relationships || []).some((x) => x.from === r.from && x.to === r.to)) { return false; }
      M.edit((doc) => { (doc.relationships = doc.relationships || []).push({ from: r.from, to: r.to }); });
    } else if (it.kind === 'measure') {
      if ((M.doc.measures || {})[it.data.name]) { return false; }
      M.edit((doc) => { doc.measures = doc.measures || {}; doc.measures[it.data.name] = it.data.description ? { expr: it.data.expr, description: it.data.description } : { expr: it.data.expr }; });
    } else {
      M.edit((doc) => { doc.fields = doc.fields || {}; doc.fields[it.data.field] = Object.assign({}, doc.fields[it.data.field] || {}, { description: it.data.description }); });
    }
    return true;
  }

  btn.addEventListener('click', () => {
    const list = B.clear(q('[data-ai-items]'));
    box.hidden = false;
    M.say('Asking the AI helper about the saved model (it sees names, types and descriptions, never a value)...');
    btn.disabled = true;
    B.api('POST', '/ai/dataset-helper', { dataset: itemId }).then((d) => {
      M.say(d.items.length ? d.items.length + ' proposed. Nothing changes until you accept one.' : 'Nothing to propose: the model is already described.');
      d.items.forEach((it) => {
        const acc = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-ai-accept': it.id, text: 'Accept', disabled: M.readOnly });
        acc.addEventListener('click', () => {
          const done = accept(it);
          acc.disabled = true;
          acc.textContent = done ? 'Accepted' : 'Already there';
          M.say(done ? 'Added to the draft; Save keeps it (Undo takes it back).' : 'The draft has that already.', !done);
        });
        list.appendChild(h('li', { class: 'bi-rel', 'data-ai-item': it.kind }, h('span', { class: 'bi-badge', text: it.kind }), ' ', h('strong', { text: it.label }),
          it.why ? h('small', { class: 'text-muted-d', text: ' ' + it.why }) : null, h('pre', { class: 'mono bi-ai-yaml', text: it.yaml }), acc));
      });
    }).catch((e) => M.say((e.code ? e.code + ' ' : '') + (e.message || 'the helper failed'), true)).then(() => { btn.disabled = false; });
  });
  q('[data-ai-close]').addEventListener('click', () => { box.hidden = true; });
})();
