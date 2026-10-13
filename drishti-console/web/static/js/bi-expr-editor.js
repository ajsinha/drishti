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
/* The ExpressionEditor (docs/architecture/BI_DESIGN.md sections 3.3.8 and 8.1; docs/guides/EXPRESSIONS.md): one editor for every DEL expression a person
   writes (a measure, a derived column, a dataset filter, a row rule, a report's own measure). It completes the fields (with type, meaning and format), the
   measures (writing measure('…')), the functions of the server's catalogue (signature, one line, one example, a link to the guide) and the keyword arguments
   of the call the caret is in; the call's card stays beside the input while you type its arguments. The console never parses DEL for meaning: as you type,
   the server's /bi/api/expr/validate says what is wrong, with the fix as a button, and Try it shows the server's answer for the current selection, as you.
   Measure references read as chips under the input. Everything is built with DOM calls and textContent; same-origin JSON only. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = (...a) => B.h(...a);
  const GUIDE = '/help/expressions';
  const WORDS = { aggregate: ['True', 'False', 'None', 'ALL', 'and', 'or', 'not', 'in', 'if', 'else', 'is'], row: ['True', 'False', 'None', 'and', 'or', 'not', 'in', 'if', 'else', 'is'] };
  let catalogue = null, uid = 0;

  /** The server's function catalogue (bi/expr/functions.yaml), read once per page: [{name, signature, description, example, examples, scopes, keywords, link}]. */
  function functions() {
    if (!catalogue) {
      catalogue = B.api('GET', '/expr/functions').then((r) => (r.functions || r || []).filter((f) => f && f.name && f.available !== false).map((f) => {
        const sig = String(f.signature || f.name + '(…)');
        const kw = f.keywords || (sig.match(/\b([a-z_]+)=/g) || []).map((k) => k.slice(0, -1));
        const ex = [].concat(f.examples || f.example || []).map((e) => (typeof e === 'string' ? e : e && (e.expr || e.text) || '')).filter(Boolean);
        return { name: f.name, signature: sig, description: f.description || '', example: ex[0] || '', examples: ex, scopes: [].concat(f.scopes || []), keywords: kw,
          returns: f.returns || '', family: f.family || '', link: GUIDE + '#' + (f.anchor || f.name.replace(/[^a-z0-9_]+/gi, '').toLowerCase()) };
      })).catch(() => []);
    }
    return catalogue;
  }

  /** The word before the caret and what it is: a measure name inside measure('…'), a keyword of the call the caret is in, or a name. */
  function context(text, at) {
    const before = text.slice(0, at);
    const inMeasure = /measure\(\s*(['"])([^'"]*)$/.exec(before);
    if (inMeasure) { return { kind: 'measure-name', word: inMeasure[2], start: at - inMeasure[2].length, quote: inMeasure[1] }; }
    const m = /[A-Za-z_][A-Za-z0-9_.]*$/.exec(before);
    const word = m ? m[0] : '';
    return { kind: 'name', word, start: at - word.length, call: callAt(before) };
  }
  /** The function whose parentheses the caret is inside (strings skipped), and how many arguments are behind it. */
  function callAt(before) {
    let depth = 0, args = 0, quote = '';
    for (let i = before.length - 1; i >= 0; i--) {
      const c = before[i];
      if (quote) { if (c === quote) { quote = ''; } continue; }
      if (c === '\'' || c === '"') { quote = c; continue; }
      if (c === ')' || c === ']') { depth++; } else if (c === '[' && depth > 0) { depth--; } else if (c === '(') {
        if (depth === 0) { const n = /([A-Za-z_][A-Za-z0-9_.]*)\s*$/.exec(before.slice(0, i)); return n ? { name: n[1], args } : null; }
        depth--;
      } else if (c === ',' && depth === 0) { args++; }
    }
    return null;
  }
  /** The measure names an expression refers to (for the chips), with where each sits. */
  function measureRefs(text) {
    const out = [], re = /measure\(\s*(['"])((?:(?!\1).)*)\1\s*\)/g;
    let m;
    while ((m = re.exec(text))) { out.push({ name: m[2], start: m.index, end: m.index + m[0].length }); }
    return out;
  }

  /**
   * Attaches the editor to an input or a textarea.
   * @param input the element (its value is the expression)
   * @param opts {scope: 'aggregate'|'row'|'rule', fields: () => [{name, type, meaning, format, description}], measures: () => [{name, description}] | [names],
   *              target: () => {model?, yaml?, dataset?} (what the server resolves names against), onCommit(value), tryIt(expr) => Promise<{text, rows?}>,
   *              label: words for the screen reader}
   */
  B.exprEditor = function (input, opts) {
    opts = opts || {};
    const scope = opts.scope || 'aggregate';
    const id = 'xe' + (++uid);
    const list = h('ul', { class: 'bi-xe-list', role: 'listbox', id: id + '-list', hidden: true, 'aria-label': 'Suggestions' });
    const card = h('div', { class: 'bi-xe-card', 'data-xe-card': '', hidden: true, 'aria-live': 'polite' });
    const chips = h('p', { class: 'bi-xe-chips', 'data-xe-chips': '', hidden: true });
    const probs = h('ul', { class: 'bi-xe-problems', 'data-xe-problems': '', 'aria-live': 'polite' });
    const tryOut = h('div', { class: 'bi-xe-tryout', 'data-xe-tryout': '', 'aria-live': 'polite' });
    const tryBtn = opts.tryIt ? h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small bi-xe-try', 'data-xe-try': '' }, B.icon('play'), ' Try it') : null;
    const help = h('a', { class: 'bi-xe-help', href: GUIDE, target: '_blank', rel: 'noopener', 'data-xe-guide': '', title: 'The expression guide: every function, with examples' }, B.icon('question-circle'), h('span', { class: 'visually-hidden', text: 'Expression guide' }));
    const wrap = h('div', { class: 'bi-xe', 'data-xe': scope });
    input.parentNode.insertBefore(wrap, input);
    wrap.append(h('div', { class: 'bi-xe-row' }, input, help, tryBtn), list, card, chips, probs, tryOut);
    input.classList.add('bi-xe-in', 'mono');
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-controls', list.id);
    input.setAttribute('spellcheck', 'false');
    input.setAttribute('autocomplete', 'off');
    if (opts.label && !input.getAttribute('aria-label')) { input.setAttribute('aria-label', opts.label); }
    let items = [], active = -1, timer = null, seq = 0, lastChecked = null, fns = [];
    functions().then((f) => { fns = f.filter((x) => !x.scopes.length || x.scopes.indexOf(scope) >= 0 || (scope === 'rule' && x.scopes.indexOf('row') >= 0)); });

    const fieldList = () => (typeof opts.fields === 'function' ? opts.fields() : opts.fields || []).map((f) => (typeof f === 'string' ? { name: f } : f));
    const measureList = () => (typeof opts.measures === 'function' ? opts.measures() : opts.measures || []).map((m) => (typeof m === 'string' ? { name: m } : m));

    function suggestions() {
      const at = input.selectionStart == null ? input.value.length : input.selectionStart;
      const c = context(input.value, at);
      const w = c.word.toLowerCase();
      const out = [];
      if (c.kind === 'measure-name') {
        measureList().filter((m) => m.name.toLowerCase().indexOf(w) >= 0).slice(0, 30).forEach((m) => out.push({ kind: 'measure', label: m.name, detail: m.description || 'a measure of the model', insert: m.name.replace(/'/g, '\\\'') + c.quote + ')', from: c.start, closeQuote: true }));
        return { c, out };
      }
      const call = c.call && fns.find((f) => f.name === c.call.name);
      if (call && c.word.indexOf('.') < 0) {
        call.keywords.filter((k) => k.indexOf(w) === 0).forEach((k) => out.push({ kind: 'keyword', label: k + '=', detail: 'a keyword of ' + call.name + '()', insert: k + '=', from: c.start }));
      }
      if (scope !== 'row' && scope !== 'rule' && 'measure'.indexOf(w) === 0 && w.length) {
        measureList().slice(0, 12).forEach((m) => out.push({ kind: 'measure', label: m.name, detail: m.description || 'measure(\'' + m.name + '\')', insert: 'measure(\'' + m.name.replace(/'/g, '\\\'') + '\')', from: c.start }));
      }
      if (w.length || c.call) {
        fieldList().filter((f) => f.name.toLowerCase().indexOf(w) === 0 || (w && f.name.toLowerCase().split('.').pop().indexOf(w) === 0)).slice(0, 40)
          .forEach((f) => out.push({ kind: 'field', label: f.name, detail: [f.type, f.meaning, f.format].filter(Boolean).join(' · ') + (f.description ? ' — ' + f.description : ''), insert: f.name, from: c.start }));
        if (c.word.indexOf('.') < 0) {
          fns.filter((f) => f.name.indexOf(w) === 0).slice(0, 30).forEach((f) => out.push({ kind: 'function', label: f.name, detail: f.signature, fn: f, insert: f.name + '(', from: c.start }));
          (WORDS[scope === 'aggregate' ? 'aggregate' : 'row'] || []).filter((k) => w && k.toLowerCase().indexOf(w) === 0 && k !== c.word).forEach((k) => out.push({ kind: 'word', label: k, detail: '', insert: k, from: c.start }));
        }
        if (scope !== 'row' && scope !== 'rule' && w && 'measure'.indexOf(w) !== 0) {
          measureList().filter((m) => m.name.toLowerCase().indexOf(w) === 0).slice(0, 12).forEach((m) => out.push({ kind: 'measure', label: m.name, detail: 'measure(\'' + m.name + '\')', insert: 'measure(\'' + m.name.replace(/'/g, '\\\'') + '\')', from: c.start }));
        }
      }
      return { c, out: out.slice(0, 60) };
    }
    const ICON = { field: 'fonts', measure: 'calculator', function: 'braces', keyword: 'at', word: 'type' };
    function open(force) {
      const s = suggestions();
      items = s.out;
      showCard(s.c);
      if (!items.length || (!force && !s.c.word && s.c.kind !== 'measure-name' && !(s.c.call && /[(,=]\s*$/.test(input.value.slice(0, input.selectionStart))))) { close(); return; }
      B.clear(list);
      items.forEach((it, i) => {
        const li = h('li', { role: 'option', id: id + '-o' + i, class: 'bi-xe-opt bi-xe-' + it.kind, 'data-xe-opt': it.label, 'aria-selected': 'false' },
          B.icon(ICON[it.kind] || 'dot'), h('span', { class: 'bi-xe-label mono', text: it.label }), it.detail ? h('small', { class: 'bi-xe-detail', text: it.detail }) : null);
        li.addEventListener('mousedown', (e) => { e.preventDefault(); accept(i); });
        li.addEventListener('mouseenter', () => mark(i));
        list.appendChild(li);
      });
      list.hidden = false;
      input.setAttribute('aria-expanded', 'true');
      mark(0);
    }
    function close() { list.hidden = true; items = []; active = -1; input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); }
    function mark(i) {
      active = i;
      B.qa('[role=option]', list).forEach((li, j) => li.setAttribute('aria-selected', j === i ? 'true' : 'false'));
      const li = list.children[i];
      if (li) { input.setAttribute('aria-activedescendant', li.id); li.scrollIntoView({ block: 'nearest' }); }
      const it = items[i];
      if (it && it.fn) { drawCard(it.fn); }
    }
    function accept(i) {
      const it = items[i];
      if (!it) { return; }
      const at = input.selectionStart == null ? input.value.length : input.selectionStart;
      let rest = input.value.slice(at);
      if (it.closeQuote) { rest = rest.replace(/^[^'")]*['"]?\s*\)?/, ''); }
      input.value = input.value.slice(0, it.from) + it.insert + rest;
      const caret = it.from + it.insert.length;
      input.setSelectionRange(caret, caret);
      close();
      input.focus();
      changed();
      if (it.kind === 'function') { window.setTimeout(() => open(true), 0); }
    }
    function drawCard(f) {
      B.clear(card).append(h('code', { class: 'bi-xe-sig', text: f.signature }), f.returns ? h('small', { class: 'text-muted-d', text: ' → ' + f.returns }) : null,
        f.description ? h('p', { text: f.description }) : null, f.example ? h('p', {}, h('small', { text: 'Example ' }), h('code', { text: f.example })) : null,
        h('a', { href: f.link, target: '_blank', rel: 'noopener', 'data-xe-card-link': f.name, text: 'More in the guide' }));
      card.setAttribute('data-xe-card', f.name);
      card.hidden = false;
    }
    function showCard(c) {
      const f = c && c.call && fns.find((x) => x.name === c.call.name);
      if (f) { drawCard(f); } else if (!items.length || !(items[active] && items[active].fn)) { card.hidden = true; }
    }

    function drawChips() {
      const refs = measureRefs(input.value);
      B.clear(chips);
      chips.hidden = !refs.length;
      if (!refs.length) { return; }
      chips.append(h('small', { class: 'text-muted-d', text: 'Measures used: ' }));
      refs.forEach((r) => {
        const known = measureList().some((m) => m.name === r.name);
        const b = h('button', { type: 'button', class: 'bi-chip bi-xe-chip' + (known ? '' : ' bi-xe-chip-unknown'), 'data-xe-chip': r.name, title: known ? 'Select it in the formula' : 'No measure has this name' }, B.icon('calculator'), ' ' + r.name);
        b.addEventListener('click', () => { input.focus(); input.setSelectionRange(r.start, r.end); });
        chips.appendChild(b);
      });
    }

    /** The server's check of the expression, debounced; problems listed with the fix as a button. */
    function validate(now) {
      window.clearTimeout(timer);
      return new Promise((resolve) => {
        timer = window.setTimeout(() => {
          const text = input.value.trim();
          if (!text) { B.clear(probs); input.removeAttribute('aria-invalid'); lastChecked = null; resolve(null); return; }
          const mine = ++seq;
          const body = Object.assign({ expr: text, scope }, (opts.target && opts.target()) || {});
          B.api('POST', '/expr/validate', body).then((r) => { if (mine === seq) { lastChecked = r; drawProblems(r); } resolve(r); })
            .catch((e) => { if (mine === seq) { drawProblems({ valid: false, problems: [{ code: e.code || '', level: e.status === 404 ? 'info' : 'error', message: e.status === 404 ? 'The server cannot check expressions yet: Check runs when you save.' : e.detail || e.message, fix: e.fix }] }); } resolve(null); });
        }, now ? 0 : 350);
      });
    }
    function drawProblems(r) {
      B.clear(probs);
      const list2 = (r && r.problems) || [];
      input.setAttribute('aria-invalid', list2.some((p) => (p.level || 'error') === 'error') ? 'true' : 'false');
      list2.forEach((p) => {
        const repl = p.replacement || (p.fix && typeof p.fix === 'object' ? p.fix.expr || p.fix.text : '');
        const fixWords = typeof p.fix === 'string' ? p.fix : p.fix && p.fix.label ? p.fix.label : '';
        let btn = null;
        if (repl) {
          btn = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-xe-fix': p.code || 'fix', title: 'Replace the formula with the fix' }, B.icon('magic'), ' Use ', h('code', { text: repl.length > 60 ? repl.slice(0, 57) + '…' : repl }));
          btn.addEventListener('click', () => { input.value = repl; changed(); commit(); input.focus(); });
        }
        probs.appendChild(h('li', { class: 'bi-problem bi-problem-' + (p.level || 'error'), 'data-xe-problem': p.code || '' },
          p.code ? h('strong', { text: p.code + ' ' }) : null, p.message || '', fixWords ? h('span', { class: 'text-muted-d', text: ' ' + fixWords }) : null, btn ? ' ' : null, btn));
      });
      if (r && r.valid && !list2.length) {
        probs.appendChild(h('li', { class: 'bi-ok bi-xe-ok', 'data-xe-ok': '' }, B.icon('check2'), ' Checks clean' + (r.type ? ': gives ' + (r.type.name || r.type) : '') + '.'));
      }
    }
    function changed() { drawChips(); validate(); if (opts.onInput) { opts.onInput(input.value); } }
    function commit() { if (opts.onCommit) { opts.onCommit(input.value.trim()); } }

    input.addEventListener('input', () => { open(false); changed(); });
    input.addEventListener('click', () => { const c = context(input.value, input.selectionStart || 0); showCard(c); });
    input.addEventListener('keydown', (e) => {
      if (e.key === ' ' && e.ctrlKey) { e.preventDefault(); open(true); return; }
      if (list.hidden) {
        if (e.key === 'Enter' && input.tagName === 'INPUT') { commit(); }
        return;
      }
      if (e.key === 'ArrowDown') { e.preventDefault(); mark(Math.min(items.length - 1, active + 1)); } else if (e.key === 'ArrowUp') { e.preventDefault(); mark(Math.max(0, active - 1)); } else if (e.key === 'Enter' || e.key === 'Tab') {
        if (active >= 0) { e.preventDefault(); accept(active); }
      } else if (e.key === 'Escape') { e.preventDefault(); close(); }
    });
    input.addEventListener('blur', () => window.setTimeout(() => { if (document.activeElement !== input) { close(); } }, 150));
    input.addEventListener('change', commit);
    if (tryBtn) {
      tryBtn.addEventListener('click', () => {
        const text = input.value.trim();
        if (!text) { B.near(tryBtn, 'Write the formula first.', true); return; }
        B.busy(tryBtn, () => opts.tryIt(text).then((r) => {
          B.clear(tryOut);
          if (r && r.rows && r.columns) {
            tryOut.appendChild(h('table', { class: 'tbl bi-tbl bi-md-mini', 'data-plain': '', 'data-xe-tried': '' }, h('caption', { class: 'text-muted-d', text: r.caption || 'As you, from the server' }),
              h('thead', {}, h('tr', {}, r.columns.map((c) => h('th', { scope: 'col', text: c.name || c })))),
              h('tbody', {}, r.rows.slice(0, 12).map((row) => h('tr', {}, row.map((v) => h('td', { text: v == null ? '—' : typeof v === 'number' ? B.fmt(v) : String(v) })))))));
          } else {
            tryOut.appendChild(h('p', { 'data-xe-tried': '', text: (r && r.text) || '' }));
          }
        }).catch((e) => { B.clear(tryOut).appendChild(h('p', { class: 'bi-near bi-bad', 'data-xe-tried': '', text: e.message })); }));
      });
    }
    drawChips();
    if (input.value.trim()) { validate(); }
    const api = { input, wrap, validate: () => validate(true), last: () => lastChecked, refresh: () => { drawChips(); }, close };
    input.drishtiExpr = api;
    return api;
  };
  B.exprEditor.functions = functions;
  B.exprEditor.measureRefs = measureRefs;
  B.exprEditor.callAt = callAt;
})();
