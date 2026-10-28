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
/* The designer copilot (docs/guides/BI_GUIDE.md section 9): "Suggest visuals" proposes visuals, wells and filters for the dataset and the goal you type, each as YAML the
   report checker has passed, and "Explain in plain words" explains the Check panel's problems. The model sees names, types and descriptions, never a row. A suggestion
   changes nothing until you press Add on it (a normal, undoable edit of the draft); an explanation changes nothing at all. Same-origin JSON only. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const canvas = document.getElementById('biCanvas');
  if (!B || !B.ai || !B.design || !canvas || canvas.getAttribute('data-bi-page') !== 'designer') { return; }
  const D = B.design, ai = B.ai, h = B.h;
  const bar = document.querySelector('.bi-dactions');
  const checkBox = document.querySelector('[data-check-box]');
  if (!bar || !checkBox) { return; }
  const fail = (el, e) => B.say(el, (e && e.code ? e.code + ' ' : '') + ((e && (e.detail || e.message)) || 'The AI call failed.'), true);

  // ---- Suggest visuals ----------------------------------------------------------------------------------------------------------------------
  const open = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ai-suggest': '', hidden: '' }, B.icon('lightbulb'), ' Suggest visuals');
  const goal = h('textarea', { class: 'studio-in', rows: '2', maxlength: '600', 'data-ai-goal': '', 'aria-label': 'What do you want to see?', placeholder: 'What do you want to see? For example: where the exposure sits, and a way to filter it by desk' });
  const msg = h('p', { class: 'bi-msg', role: 'status', 'aria-live': 'polite', 'data-ai-suggest-msg': '' });
  const list = h('ul', { class: 'bi-ai-suggestions', 'data-ai-suggestions': '' });
  const go = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-ai-suggest-go': '' }, 'Suggest');
  const close = h('button', { type: 'button', class: 'btn-pill btn-ghost' }, 'Close');
  const box = h('section', { class: 'bi-checkpanel bi-ai-box', 'data-ai-suggest-box': '', hidden: '', 'aria-label': 'Suggest visuals' }, h('h2', { text: 'Suggest visuals' }),
    h('p', { class: 'text-muted-d', text: 'The model sees the names, types and descriptions of this report’s dataset, never a row. Each suggestion has passed the report check; nothing is added until you press Add.' }),
    goal, h('p', {}, go, ' ', close), msg, list);
  bar.insertBefore(open, bar.firstChild);
  checkBox.parentNode.insertBefore(box, checkBox);
  ai.on(() => { open.hidden = !(ai.enabled && ai.assist); });
  open.addEventListener('click', () => { box.hidden = false; goal.focus(); });
  close.addEventListener('click', () => { box.hidden = true; });

  /** Puts a suggested visual at the bottom of the page being designed (one undoable step), as a drop of a field would. */
  function add(item, btn) {
    let made = null;
    D.change('Add the suggested ' + item.type, (m) => {
      const page = m.pages.find((p) => p.id === D.pageId) || m.pages[0];
      const v = JSON.parse(JSON.stringify(item.visual));
      v.at = Object.assign({}, v.at, { x: Math.min(v.at.x || 0, 12 - (v.at.w || 3)), y: D.firstFreeY(page) });
      let id = v.id, n = 2;
      while (page.visuals.some((x) => x.id === id)) { id = v.id + '-' + n++; }
      v.id = id;
      page.visuals.push(v);
      D.settle(page, v);
      made = v;
    });
    if (made) { D.select(made.id); btn.disabled = true; btn.textContent = 'Added'; B.say(msg, 'Added to the page. It is not saved until you save.'); }
  }
  go.addEventListener('click', () => {
    const text = goal.value.trim();
    if (!text) { B.say(msg, 'Say what you want to see first.', true); return; }
    go.disabled = true;
    B.clear(list);
    B.say(msg, 'Thinking…');
    D.flushYaml().then((yaml) => B.api('POST', '/ai/suggest', { dataset: D.model.dataset || D.model.model || (B.modelBinding && B.modelBinding.ref) || '', goal: text, yaml })).then((r) => {
      B.say(msg, r.suggestions.length ? r.suggestions.length + ' suggested. Nothing is applied until you press Add.' : 'No suggestion this time: try describing the goal differently.');
      r.suggestions.forEach((s) => {
        const addBtn = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ai-add': s.id }, 'Add to this page');
        addBtn.disabled = D.readOnly;
        addBtn.addEventListener('click', () => add(s, addBtn));
        list.appendChild(h('li', { class: 'bi-ai-suggestion', 'data-ai-suggestion': s.kind }, h('span', { class: 'bi-badge', text: s.kind === 'filter' ? 'filter' : s.type }), ' ', h('strong', { text: s.title }),
          s.why ? h('p', { class: 'text-muted-d', text: s.why }) : null, h('pre', { class: 'mono bi-ai-yaml', text: s.yaml }), addBtn));
      });
    }).catch((e) => fail(msg, e)).then(() => { go.disabled = false; });
  });

  // ---- Explain the Check panel in plain words -------------------------------------------------------------------------------------------------
  const ex = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ai-explain-checks': '', hidden: '' }, B.icon('lightbulb'), ' Explain in plain words');
  const exMsg = h('p', { class: 'bi-msg', role: 'status', 'aria-live': 'polite', 'data-ai-checks-msg': '' });
  const exList = h('ul', { class: 'bi-warn', 'data-ai-checks': '' });
  const closeBtn = checkBox.querySelector('[data-check-close]');
  checkBox.insertBefore(h('div', { class: 'bi-ai-checks' }, ex, exMsg, exList), closeBtn);
  ai.on(() => { ex.hidden = !(ai.enabled && ai.assist); });
  ex.addEventListener('click', () => {
    ex.disabled = true;
    B.clear(exList);
    B.say(exMsg, 'Asking…');
    D.flushYaml().then((yaml) => B.api('POST', '/ai/explain-checks', { yaml })).then((r) => {
      const ps = r.problems || [];
      B.say(exMsg, ps.length ? 'Explained by an AI model from the problem codes and your dataset’s names. Nothing was changed.' : 'There is nothing to explain: the draft is valid.');
      ps.forEach((p) => {
        if (!p.explain) { return; }
        exList.appendChild(h('li', { class: 'bi-problem bi-problem-' + p.level, 'data-code': p.code }, h('strong', { text: p.code + ' ' }), h('code', { text: p.path || '' }), h('p', { text: p.explain }),
          p.fix ? h('p', {}, h('em', { text: 'To fix: ' }), p.fix) : null));
      });
    }).catch((e) => fail(exMsg, e)).then(() => { ex.disabled = false; });
  });
})();
