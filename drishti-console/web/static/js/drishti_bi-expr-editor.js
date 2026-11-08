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
/* The ExpressionEditor (docs/architecture/BI_DESIGN.md sections 3.3.8 and 8.1; docs/guides/RACHANA_REFERENCE.md): one editor for every DEL expression a person
   writes (a measure, a derived column, a dataset filter, a row rule, a report's own measure), on drishti-inputs' CodeEditor in Rachana (upgradeCodeArea: one line
   for an input, the whole text for a text area; the element stays the value's holder). Its completion hook offers the fields (with type, meaning and format), the
   measures (writing measure('…')), the functions of the server's catalogue (signature and one line) and the keyword arguments of the call the caret is in, as a
   name is typed, after "(", "," or "=" in a call, or on Ctrl+Space; the call's card (signature, one example, a link to the guide) stays beside it while you type
   its arguments. The console never parses DEL for meaning: as you type,
   the server's /bi/api/expr/validate says what is wrong, with the fix as a button, and Try it shows the server's answer for the current selection, as you.
   Measure references read as chips under the input. Wave B: the filter-context and balance functions (calc, share_of, closing ...) come from the same
   catalogue; opts.names adds names of the model that are not fields (param.shock, SELECTED in a calculation group's item) and opts.keywordValues the
   values a keyword takes (via='r_5': the model's relationship ids; over=/ignore=: ALL). Everything is built with DOM calls and textContent; same-origin JSON only. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = (...a) => B.h(...a);
  const GUIDE = '/help/rachana-reference';
  const WORDS = { aggregate: ['true', 'false', 'null', 'ALL', 'in', 'not in'], row: ['true', 'false', 'null', 'in', 'not in'] };   // Rachana's words (&&, ||, !, ?: are operators)
  let catalogue = null, uid = 0;

  /** The server's function catalogue (Rachana's signatures), read once per page: [{name, signature, description, example, examples, scopes, keywords, link}]. */
  function functions() {
    if (!catalogue) {
      // only what works now: a function of a later wave (status B, C) is refused by the server (DRS-9129), so it is not offered
      catalogue = B.api('GET', '/expr/functions').then((r) => (r.functions || r || []).filter((f) => f && f.name && (f.status || 'built') === 'built').map((f) => {
        const sig = String(f.signature || f.name + '(…)');
        const kw = (f.params || []).filter((p) => p.keywordOnly || p.default !== undefined).map((p) => p.name).filter((n) => /^[a-z_]+$/.test(n));
        if (!kw.length) { (sig.match(/\b([a-z_]+)=/g) || []).forEach((k) => kw.push(k.slice(0, -1))); }
        const ex = [].concat(f.examples || f.example || []).map((e) => (typeof e === 'string' ? e : e && (e.expr || e.text) || '')).filter(Boolean);
        return { name: f.name, signature: sig, description: f.description || '', example: ex[0] || '', examples: ex, scopes: [].concat(f.scopes || []), keywords: kw,
          returns: f.returns || '', family: f.family || '', link: GUIDE + '#fn-' + f.name.replace(/_/g, '-') };
      })).catch(() => []);
    }
    return catalogue;
  }

  /** The word before the caret and what it is: a measure name inside measure('…'), a keyword of the call the caret is in, or a name. */
  function context(text, at) {
    const before = text.slice(0, at);
    const inMeasure = /measure\(\s*(['"])([^'"]*)$/i.exec(before);
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
    const card = h('div', { class: 'bi-xe-card', 'data-xe-card': '', hidden: true, 'aria-live': 'polite' });
    const chips = h('p', { class: 'bi-xe-chips', 'data-xe-chips': '', hidden: true });
    const probs = h('ul', { class: 'bi-xe-problems', 'data-xe-problems': '', 'aria-live': 'polite' });
    const tryOut = h('div', { class: 'bi-xe-tryout', 'data-xe-tryout': '', 'aria-live': 'polite' });
    const tryBtn = opts.tryIt ? h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small bi-xe-try', 'data-xe-try': '' }, B.icon('play'), ' Try it') : null;
    const help = h('a', { class: 'bi-xe-help', href: GUIDE, target: '_blank', rel: 'noopener', 'data-xe-guide': '', title: 'The expression guide: every function, with examples' }, B.icon('question-circle'), h('span', { class: 'visually-hidden', text: 'Expression guide' }));
    const wrap = h('div', { class: 'bi-xe', 'data-xe': scope });
    const row = h('div', { class: 'bi-xe-row' });
    input.parentNode.insertBefore(wrap, input);
    row.appendChild(input);
    wrap.append(row, card, chips, probs, tryOut);
    if (opts.label && !input.getAttribute('aria-label')) { input.setAttribute('aria-label', opts.label); }
    // the code editor: drishti-inputs' one-line (an input) or whole (a text area) Rachana editor, the element kept as the value's holder
    input.drishtiCodeOptions = {
      complete: (c) => completeAt(c),
      completeAfter: (before) => /[(,=]\s*$/.test(before) && !!callAt(before),
      keys: input.tagName === 'INPUT' ? { Enter: () => commit() } : {}
    };
    input.setAttribute('data-di-code', 'rachana');
    const editor = window.Drishti.elements.inputs.upgradeCodeArea(input);
    row.append(help, tryBtn || '');
    const cm = () => editor.cm;
    const caret = () => (cm() ? cm().indexFromPos(cm().getCursor()) : input.value.length);
    let timer = null, seq = 0, lastChecked = null, fns = [];
    // opts.words (the statement's keywords), opts.functions === false (no DEL functions: Drishti SQL), opts.check(text) (another validator), opts.measureInsert(name)
    if (opts.functions !== false) { functions().then((f) => { fns = f.filter((x) => !x.scopes.length || x.scopes.indexOf(scope) >= 0 || (scope === 'rule' && x.scopes.indexOf('row') >= 0)); }); }
    const mIns = opts.measureInsert || ((n) => 'measure(\'' + n.replace(/'/g, '\\\'') + '\')');
    const wordList = opts.words || WORDS[scope === 'aggregate' ? 'aggregate' : 'row'] || [];

    const fieldList = () => (typeof opts.fields === 'function' ? opts.fields() : opts.fields || []).map((f) => (typeof f === 'string' ? { name: f } : f));
    const nameList = () => (typeof opts.names === 'function' ? opts.names() : opts.names || []).map((n) => (typeof n === 'string' ? { name: n } : n));
    const measureList = () => (typeof opts.measures === 'function' ? opts.measures() : opts.measures || []).map((m) => (typeof m === 'string' ? { name: m } : m));

    function suggestions(at) {
      const c = context(input.value, at);
      const w = c.word.toLowerCase();
      const out = [];
      if (c.kind === 'measure-name') {
        measureList().filter((m) => m.name.toLowerCase().indexOf(w) >= 0).slice(0, 30).forEach((m) => out.push({ kind: 'measure', label: m.name, detail: m.description || 'a measure of the model', insert: m.name.replace(/'/g, '\\\'') + c.quote + ')', from: c.start, closeQuote: true }));
        return { c, out };
      }
      const call = c.call && fns.find((f) => f.name === c.call.name);
      // the value of a keyword: via='…' takes a relationship's id, over= / ignore= take ALL (or a list of fields, completed as fields)
      const kwAt = /\b([a-z_]+)=\s*(['"]?)([A-Za-z0-9_.]*)$/.exec(input.value.slice(0, at));
      if (call && kwAt) {
        const vals = kwAt[1] === 'via' ? (opts.keywordValues ? opts.keywordValues('via') : []) : /^(over|ignore)$/.test(kwAt[1]) ? ['ALL'] : [];
        vals.filter((v) => String(v).toLowerCase().indexOf(kwAt[3].toLowerCase()) === 0).forEach((v) => out.push({
          kind: 'keyword', label: kwAt[1] === 'via' ? '\'' + v + '\'' : String(v), detail: kwAt[1] === 'via' ? 'a relationship of the model' : 'every field: the filters all go',
          insert: kwAt[1] === 'via' && !kwAt[2] ? '\'' + v + '\'' : String(v) + (kwAt[2] || ''), from: at - kwAt[3].length }));
        if (out.length) { return { c, out }; }
      }
      if (call && c.word.indexOf('.') < 0) {
        call.keywords.filter((k) => k.indexOf(w) === 0).forEach((k) => out.push({ kind: 'keyword', label: k + '=', detail: 'a keyword of ' + call.name + '()', insert: k + '=', from: c.start }));
      }
      if (scope !== 'row' && scope !== 'rule' && 'measure'.indexOf(w) === 0 && w.length) {
        measureList().slice(0, 12).forEach((m) => out.push({ kind: 'measure', label: m.name, detail: m.description || 'measure(\'' + m.name + '\')', insert: mIns(m.name), from: c.start }));
      }
      if (w.length || c.call) {
        const callName = c.call ? c.call.name : '';
        const first = !c.call || c.call.args === 0;
        // count(trade): a dataset (its alias) is what count takes first, so the model's aliases come first there
        if (/^(count|count_rows|countrows|distinct_count)$/.test(callName) && first && c.word.indexOf('.') < 0) {
          Array.from(new Set(fieldList().map((f) => f.name.split('.')[0]).filter(Boolean))).filter((a) => a.toLowerCase().indexOf(w) === 0)
            .forEach((a) => out.push({ kind: 'table', label: a, detail: 'count the rows of ' + a, insert: a, from: c.start }));
        }
        const fnItems = c.word.indexOf('.') < 0 ? fns.filter((f) => f.name.indexOf(w) === 0).slice(0, 30).map((f) => ({ kind: 'function', label: f.name, detail: f.signature, fn: f, insert: f.name + '(', from: c.start })) : [];
        let fieldItems = fieldList().filter((f) => f.name.toLowerCase().indexOf(w) === 0 || (w && f.name.toLowerCase().split('.').pop().indexOf(w) === 0))
          .map((f) => ({ kind: 'field', label: f.name, type: f.type, detail: [f.type, f.meaning, f.format].filter(Boolean).join(' · ') + (f.description ? ' — ' + f.description : ''), insert: f.name, from: c.start }));
        if (/^(sum|avg|average|min|max|median|stdev|variance|percentile)$/.test(callName) && first) {            // sum(: the numbers first, text last
          const num = (f) => (/^(int|integer|long|decimal|double|float|number|numeric)/i.test(String(f.type || '')) ? 0 : 1);
          fieldItems = fieldItems.map((f, i) => [f, i]).sort((a, b) => num(a[0]) - num(b[0]) || a[1] - b[1]).map((x) => x[0]);
        }
        fieldItems = fieldItems.slice(0, 40);
        // a prefix that starts a function name puts the functions above the fields (fields are reached by their dataset's alias anyway)
        // a field whose whole name starts with the prefix (status for "sta") first; then the functions ("su" gives sum before trade.summary); then the
        // fields matched only by their column (trade.summary for "su")
        const whole = fieldItems.filter((f) => w && f.label.toLowerCase().indexOf(w) === 0), part = fieldItems.filter((f) => whole.indexOf(f) < 0);
        if (w.length) { whole.concat(fnItems, part).forEach((x) => out.push(x)); } else { fieldItems.forEach((x) => out.push(x)); fnItems.forEach((x) => out.push(x)); }
        if (c.word.indexOf('.') < 0) {
          wordList.filter((k) => w && k.toLowerCase().indexOf(w) === 0 && k !== c.word).forEach((k) => out.push({ kind: 'word', label: k, detail: '', insert: k, from: c.start }));
        }
        nameList().filter((n) => w && n.name.toLowerCase().indexOf(w) === 0 && n.name !== c.word).slice(0, 20)
          .forEach((n) => out.push({ kind: 'word', label: n.name, detail: n.detail || '', insert: n.name, from: c.start }));
        if (scope !== 'row' && scope !== 'rule' && w && 'measure'.indexOf(w) !== 0) {
          measureList().filter((m) => m.name.toLowerCase().indexOf(w) === 0).slice(0, 12).forEach((m) => out.push({ kind: 'measure', label: m.name, detail: 'measure(\'' + m.name + '\')', insert: mIns(m.name), from: c.start }));
        }
      }
      // a capital first letter is a measure's name more often than a function's (functions are lower case): the measures come first then
      if (/^[A-Z]/.test(c.word)) { out.sort((a, b) => (a.kind === 'measure' ? 0 : 1) - (b.kind === 'measure' ? 0 : 1)); }
      return { c, out: out.slice(0, 60) };
    }
    /** The completion hook: the suggestions at the caret as the code editor's entries (where its word starts, on the caret's line). */
    function completeAt(ctx) {
      const lineStart = cm() ? cm().indexFromPos({ line: ctx.lineNo, ch: 0 }) : 0, at = lineStart + ctx.ch;
      const s = suggestions(at);
      showCard(s.c);
      if (!ctx.forced && !s.c.word && s.c.kind !== 'measure-name' && !(s.c.call && /[(,=]\s*$/.test(input.value.slice(0, at)))) { return []; }
      const from = s.out.length ? s.out[0].from - lineStart : ctx.ch;
      const rest = input.value.slice(at), to = s.out.length && s.out[0].closeQuote ? ctx.ch + (/^[^'")]*['"]?\s*\)?/.exec(rest) || [''])[0].length : undefined;
      return s.out.map((it) => ({ name: it.label, description: it.detail || '', kind: it.kind, insert: it.insert.replace(/\|/g, ''), from, to, fn: it.fn }));
    }
    editor.on('completed', (e) => { if (e.entry && e.entry.kind === 'function') { window.setTimeout(() => editor.complete(true), 0); } });
    function drawCard(f) {
      B.clear(card).append(h('code', { class: 'bi-xe-sig', text: f.signature }), f.returns ? h('small', { class: 'text-muted-d', text: ' → ' + f.returns }) : null,
        f.description ? h('p', { text: f.description }) : null, f.example ? h('p', {}, h('small', { text: 'Example ' }), h('code', { text: f.example })) : null,
        h('a', { href: f.link, target: '_blank', rel: 'noopener', 'data-xe-card-link': f.name, text: 'More in the guide' }));
      card.setAttribute('data-xe-card', f.name);
      card.hidden = false;
    }
    function showCard(c) {
      const f = c && c.call && fns.find((x) => x.name === c.call.name);
      if (f) { drawCard(f); } else { card.hidden = true; }
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
        b.addEventListener('click', () => { editor.focus(); if (cm()) { cm().setSelection(cm().posFromIndex(r.start), cm().posFromIndex(r.end)); } });
        chips.appendChild(b);
      });
    }

    /** The server's check of the expression, debounced; problems listed with the fix as a button. */
    function validate(now) {
      window.clearTimeout(timer);
      return new Promise((resolve) => {
        timer = window.setTimeout(() => {
          const text = input.value.trim();
          if (!text) { B.clear(probs); editor.setInvalid(false); lastChecked = null; resolve(null); return; }
          const mine = ++seq;
          const body = Object.assign({ expr: text, scope }, (opts.target && opts.target()) || {});
          (opts.check ? opts.check(text) : B.api('POST', '/expr/validate', body)).then((r) => { if (mine === seq) { lastChecked = r; drawProblems(r); } resolve(r); })
            .catch((e) => { if (mine === seq) { drawProblems({ valid: false, problems: [{ code: e.code || '', level: e.status === 404 ? 'info' : 'error', message: e.status === 404 ? 'The server cannot check expressions yet: Check runs when you save.' : e.detail || e.message, fix: e.fix }] }); } resolve(null); });
        }, now ? 0 : 350);
      });
    }
    /** The whole expression with the server's fix applied, for the fix button: the converter's text for the old syntax; "write: X" for the whole
     *  expression; a `fragment` in the fix put in place of the problem's span (at.start..at.end), e.g. "wrap it: `sum(trades.mtm)`". */
    function fixed(r, p) {
      const text = input.value.trim(), fix = typeof p.fix === 'string' ? p.fix : '', at = p.at || {};
      if (r && r.converted && (p.code === 'DRS-9100' || r.syntax === 'legacy')) { return r.converted; }
      // "write: X", "wrap it: X" (or X in backticks): X replaces the problem's span (at.start..at.end), or the whole expression when the span is all of it
      const said = /`([^`]+)`/.exec(fix) || /^[A-Za-z][A-Za-z ]{0,20}:\s*(.+)$/.exec(fix);
      if (!said) { return ''; }
      const frag = said[1].trim();
      if (!at.end || (at.start === 0 && at.end >= text.length)) { return frag; }
      return at.end > at.start && at.end <= text.length ? text.slice(0, at.start) + frag + text.slice(at.end) : '';
    }
    /** Fixes the console can offer itself when the server's problem has no replacement: an unclosed "(", "=" written for "==", a name the server
     *  cannot find (DRS-9152) with the nearest field or measure. Every fix is only offered; the server checks the result again. */
    function localFix(text, p) {
      const msg = String(p.message || '') + ' ' + String(typeof p.fix === 'string' ? p.fix : '');
      let open = 0, quote = '';
      for (const c of text) { if (quote) { if (c === quote) { quote = ''; } } else if (c === '\'' || c === '"') { quote = c; } else if (c === '(') { open++; } else if (c === ')') { open--; } }
      if (open > 0 && (/\)|paren|unclosed|end of (the )?(expression|input)|expected/i.test(msg) || p.code === 'DRS-9101' || p.code === 'DRS-9100')) { return { text: text + ')'.repeat(open), why: 'close the bracket' }; }
      const eq = text.replace(/(\b[A-Za-z_]\w*\.[A-Za-z_]\w*\s*)=(?!=)/g, '$1==');
      if (eq !== text && /=|compar|assign|syntax|expected/i.test(msg)) { return { text: eq, why: 'compare with ==' }; }
      if (p.code === 'DRS-9152') {
        const bad = (/'([^']+)'/.exec(msg) || [])[1];
        if (bad) {
          const said = /did you mean\s+'?([A-Za-z_][\w. ]*?)'?[?.]?(\s|$)/i.exec(msg);
          const names = fieldList().map((f) => f.name).concat(measureList().map((m) => m.name));
          const best = said ? said[1] : nearest(bad, names);
          if (best && best !== bad && text.indexOf(bad) >= 0) { return { text: text.split(bad).join(best), why: 'use ' + best }; }
        }
      }
      return null;
    }
    function nearest(word, names) {
      const d = (a, b) => { const m = []; for (let i = 0; i <= a.length; i++) { m[i] = [i]; } for (let j = 1; j <= b.length; j++) { m[0][j] = j; }
        for (let i = 1; i <= a.length; i++) { for (let j = 1; j <= b.length; j++) { m[i][j] = Math.min(m[i - 1][j] + 1, m[i][j - 1] + 1, m[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)); } } return m[a.length][b.length]; };
      let best = '', score = 99;
      names.forEach((n) => { const s = d(word.toLowerCase(), n.toLowerCase()); if (s < score) { score = s; best = n; } });
      return score <= Math.max(2, Math.floor(word.length / 4)) ? best : '';
    }
    /** A fix that sums, averages or ranges over a text column is no fix: it is never offered. */
    function sane(repl) {
      const types = {};
      fieldList().forEach((f) => { types[f.name] = String(f.type || '').toLowerCase(); });
      const re = /\b(sum|avg|average|min|max|median|stdev|variance)\(\s*([A-Za-z_]\w*\.[A-Za-z_]\w*)/g;
      let m;
      while ((m = re.exec(repl))) { if (/^(string|text|bool|boolean)$/.test(types[m[2]] || '')) { return false; } }
      return true;
    }
    function drawProblems(r) {
      B.clear(probs);
      // the server checks against the saved item: a measure or field this page has but has not saved yet is not "not found" (opts.known names them)
      const known = opts.known ? opts.known() : null;
      const unsaved = (p) => p.code === 'DRS-9152' && known && /'([^']+)'/.test(p.message || '') && known.has(/'([^']+)'/.exec(p.message)[1]);
      const list2 = ((r && r.problems) || []).filter((p) => !unsaved(p));
      if (r && r.problems && list2.length < r.problems.length && !list2.some((p) => (p.level || 'error') === 'error')) { r = Object.assign({}, r, { valid: true, pending: true }); }
      editor.setInvalid(list2.some((p) => (p.level || 'error') === 'error'));
      list2.forEach((p) => {
        let repl = p.replacement || (p.fix && typeof p.fix === 'object' ? p.fix.expr || p.fix.text : '') || fixed(r, p);
        if (repl && !sane(repl)) { repl = ''; }
        if (!repl) { const lf = localFix(input.value.trim(), p); if (lf && sane(lf.text)) { repl = lf.text; } }
        let fixWords = typeof p.fix === 'string' ? p.fix : p.fix && p.fix.label ? p.fix.label : '';
        if (fixWords && !repl && /\b(sum|avg)\(/.test(fixWords) && !sane(fixWords)) { fixWords = ''; }
        let btn = null;
        if (repl) {
          btn = h('button', { type: 'button', class: 'btn-pill btn-accent bi-small', 'data-xe-fix': p.code || 'fix', title: 'Replace the formula with the fix' }, B.icon('magic'), ' Use ', h('code', { text: repl.length > 60 ? repl.slice(0, 57) + '…' : repl }));
          btn.addEventListener('click', () => { input.value = repl; changed(); commit(); editor.focus(); });
        }
        probs.appendChild(h('li', { class: 'bi-problem bi-problem-' + (p.level || 'error'), 'data-xe-problem': p.code || '' },
          // the fix in words only when there is no button that says it (no "write: X  Use X"), set apart from the message (no "ends add ')'")
          p.code ? h('strong', { text: p.code + ' ' }) : null, p.message || '', fixWords && !btn ? h('span', { class: 'text-muted-d', 'data-xe-fix-words': '', text: ' — ' + fixWords }) : null, btn ? ' ' : null, btn));
      });
      if (r && r.valid && !list2.length) {
        probs.appendChild(h('li', { class: 'bi-ok bi-xe-ok', 'data-xe-ok': '' }, B.icon('check2'), (r.pending ? ' Checks clean as far as the saved draft goes: save to check the names you added since' : ' Checks clean' + (r.type ? ': gives ' + (r.type.name || r.type) : '')) + '.'));
      }
    }
    function changed() { drawChips(); validate(); if (opts.onInput) { opts.onInput(input.value); } }
    function commit() { if (opts.onCommit) { opts.onCommit(input.value.trim()); } }

    input.addEventListener('input', changed);
    editor.on('cursor', () => { if (cm() && cm().hasFocus()) { showCard(context(input.value, caret())); } });
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
    if (input.value.trim() && opts.checkAtStart) { validate(); }            // a formula already written is checked by the item's own Check; the editor checks what you type
    const api = { input, wrap, editor, validate: () => validate(true), last: () => lastChecked, refresh: () => { drawChips(); validate(); }, close: () => editor.pop.close() };
    input.drishtiExpr = api;
    return api;
  };
  B.exprEditor.functions = functions;
  B.exprEditor.measureRefs = measureRefs;
  B.exprEditor.callAt = callAt;
})();
