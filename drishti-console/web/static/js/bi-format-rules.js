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
/* Conditional formatting in the Format pane (R6, 2.4.0; BI_PARITY_PLAN gap 6), as the format schema's `rules` control: a list of rules, each with what it colours
   (a measure of the visual, or a field for a colour per value) and its kind. A rule colours the text or the background when a measure (this one or another
   one of the model) compares with a number; a colour scale runs from a low to a high colour, with a middle colour if wanted, over the visual's own values (or
   fixed values); icons put an arrow, a light, a sign or a flag by the number against one or two thresholds; a colour per value colours the values of a field.
   Everything is chosen from lists and colour pickers: nothing is typed but the numbers. The 2.1 short form (`conditional:`) keeps its editor. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const P = B.formatPane;
  const OPS = [['lt', '<'], ['le', '≤'], ['gt', '>'], ['ge', '≥'], ['eq', '='], ['ne', '≠'], ['between', 'between']];
  const KINDS = [['rule', 'Rule'], ['gradient', 'Colour scale'], ['icons', 'Icons'], ['values', 'Colour per value']];

  function defaults(type, env) {
    const gb = B.theme.goodBad({ theme: env.theme, t: env.tokens });
    const m = (env.measures || [])[0] || '', f = (env.fields || [])[0] || '';
    if (type === 'gradient') { return { type, apply: m, min: P.hex(gb.bad), mid: '#ffffff', max: P.hex(gb.good) }; }
    if (type === 'icons') { return { type, apply: m, icons: 'arrows', thresholds: [0, 1000000] }; }
    if (type === 'values') { return { type, apply: f, colours: {} }; }
    return { type: 'rule', apply: m, op: 'lt', value: 0, colour: P.hex(gb.bad) };
  }

  /** One rule's editor; `put(rule)` replaces it (null removes it). */
  function ruleEditor(r, i, env, put, measuresOfModel) {
    const box = h('div', { class: 'bi-rule', 'data-rule': String(i), 'data-rule-type': r.type, role: 'group', 'aria-label': 'Rule ' + (i + 1) });
    const change = (patch) => put(Object.assign({}, r, patch));
    const kind = P.select(KINDS, r.type, (x) => put(Object.assign(defaults(x, env), { apply: x === 'values' ? (env.fields || [])[0] || r.apply : (env.measures || []).indexOf(r.apply) >= 0 ? r.apply : (env.measures || [])[0] })),
      { 'data-rule-kind': '', 'aria-label': 'Kind of rule' });
    const targets = r.type === 'values' ? env.fields || [] : env.measures || [];
    const apply = P.select(targets.map((x) => [x, B.label(x)]), r.apply, (x) => change({ apply: x }), { 'data-rule-apply': '', 'aria-label': 'What it colours' });
    const del = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove rule ' + (i + 1), 'data-rule-remove': String(i) }, B.icon('x-lg'));
    del.addEventListener('click', () => put(null));
    box.appendChild(h('div', { class: 'bi-cond' }, kind, apply, del));
    if (r.type === 'rule') {
      const on = P.select([['', 'its own value']].concat(measuresOfModel.filter((m) => m !== r.apply).map((m) => [m, m])), r.on || '', (x) => change({ on: x || undefined }),
        { 'data-rule-on': '', 'aria-label': 'When this measure' });
      const op = P.select(OPS, r.op || 'lt', (x) => change({ op: x }), { 'data-rule-op': '', 'aria-label': 'Comparison' });
      const v = P.text(r.value, (x) => change({ value: Number(x) || 0 }), { type: 'number', step: 'any', 'data-rule-value': '', 'aria-label': 'Value' });
      box.appendChild(P.row('When', h('span', { class: 'bi-cond' }, on, op, v, r.op === 'between' ? P.text(r.to, (x) => change({ to: Number(x) || 0 }), { type: 'number', step: 'any', 'data-rule-to': '', 'aria-label': 'and' }) : null)));
      box.appendChild(P.row('Text colour', P.colour(r.colour, (c) => change({ colour: c || undefined }), { 'data-rule-colour': '', 'aria-label': 'Text colour' })));
      box.appendChild(P.row('Background', P.colour(r.background, (c) => change({ background: c || undefined }), { 'data-rule-background': '', 'aria-label': 'Background colour' })));
    } else if (r.type === 'gradient') {
      const three = !!r.mid;
      box.appendChild(P.row('Lowest', P.colour(r.min, (c) => change({ min: c || r.min }), { 'data-rule-min': '', 'aria-label': 'Colour of the lowest value' })));
      box.appendChild(P.checkbox('A middle colour (three colours)', three, (on) => change({ mid: on ? '#ffffff' : undefined, midValue: on ? r.midValue : undefined }), { 'data-rule-three': '' }));
      if (three) { box.appendChild(P.row('Middle', P.colour(r.mid, (c) => change({ mid: c || r.mid }), { 'data-rule-mid': '', 'aria-label': 'Colour of the middle value' }))); }
      box.appendChild(P.row('Highest', P.colour(r.max, (c) => change({ max: c || r.max }), { 'data-rule-max': '', 'aria-label': 'Colour of the highest value' })));
      box.appendChild(P.row('Colours the', P.select([['', 'background'], ['font', 'text']], r.target === 'font' ? 'font' : '', (x) => change({ target: x || undefined }), { 'data-rule-target': '' })));
      const fixed = P.text(r.minValue, (x) => change({ minValue: x === '' ? undefined : Number(x) }), { type: 'number', step: 'any', placeholder: 'lowest shown', 'aria-label': 'Lowest value', 'data-rule-min-value': '' });
      const fixedHi = P.text(r.maxValue, (x) => change({ maxValue: x === '' ? undefined : Number(x) }), { type: 'number', step: 'any', placeholder: 'highest shown', 'aria-label': 'Highest value', 'data-rule-max-value': '' });
      box.appendChild(P.row('From and to', h('span', { class: 'bi-cond' }, fixed, fixedHi), 'Empty: the lowest and highest values the visual shows.'));
    } else if (r.type === 'icons') {
      box.appendChild(P.row('Icons', P.select(B.visuals.ICON_SETS.map((x) => [x, x]), r.icons || 'arrows', (x) => change({ icons: x }), { 'data-rule-icons': '' })));
      const t = r.thresholds || [0];
      const t1 = P.text(t[0], (x) => change({ thresholds: [Number(x) || 0].concat(t.length > 1 ? [t[1]] : []) }), { type: 'number', step: 'any', 'aria-label': 'Low below', 'data-rule-t1': '' });
      const t2 = P.text(t.length > 1 ? t[1] : '', (x) => change({ thresholds: x === '' ? [t[0]] : [t[0], Number(x) || 0] }), { type: 'number', step: 'any', 'aria-label': 'High from', 'data-rule-t2': '' });
      box.appendChild(P.row('Low below, high from', h('span', { class: 'bi-cond' }, t1, t2), 'Below the first: low; from the second (or the first, alone): high; in between: middle.'));
      box.appendChild(P.checkbox('Low is good (reverse the icons)', !!r.reverse, (on) => change({ reverse: on || undefined }), { 'data-rule-reverse': '' }));
    } else if (r.type === 'values') {
      const cs = r.colours || {};
      Object.keys(cs).forEach((val) => {
        const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove the colour of ' + val }, B.icon('x-lg'));
        x.addEventListener('click', () => { const n = Object.assign({}, cs); delete n[val]; change({ colours: n }); });
        box.appendChild(h('div', { class: 'bi-cond', 'data-rule-colour-of': val }, h('span', { text: val }), P.colour(cs[val], (c) => { const n = Object.assign({}, cs); if (c) { n[val] = c; } else { delete n[val]; } change({ colours: n }); }, { 'aria-label': 'Colour of ' + val }), x));
      });
      const name = h('input', { type: 'text', class: 'studio-in', placeholder: 'A value of ' + B.label(r.apply || 'the field'), 'aria-label': 'Value to colour', 'data-rule-value-name': '' });
      const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-rule-value-add': '', text: 'Colour it' });
      add.addEventListener('click', () => { const v = name.value.trim(); if (v) { change({ colours: Object.assign({}, cs, { [v]: '#1565c0' }) }); } });
      box.appendChild(h('div', { class: 'bi-cond' }, name, add));
      box.appendChild(P.row('Colours the', P.select([['', 'text'], ['background', 'background']], r.target === 'background' ? 'background' : '', (x) => change({ target: x || undefined }), { 'data-rule-target': '' })));
    }
    return box;
  }

  /** Removes the keys a change set to undefined, so the YAML carries only what the rule says. */
  const tidy = (r) => { const o = {}; Object.keys(r).forEach((k) => { if (r[k] !== undefined && r[k] !== '') { o[k] = r[k]; } }); return o; };

  P.control('rules', (prop, key, value, env, set) => {
    if (!(env.measures || []).length && !(env.fields || []).length) { return null; }
    const list = Array.isArray(value) ? value : [];
    const box = h('fieldset', { class: 'bi-fieldset bi-rules', 'data-rules': '' }, h('legend', { text: prop['x-label'] || 'Conditional formatting' }));
    const measuresOfModel = env.modelMeasures || env.measures || [];
    list.forEach((r, i) => box.appendChild(ruleEditor(r, i, env, (next) => {
      const out = list.slice();
      if (next) { out[i] = tidy(next); } else { out.splice(i, 1); }
      set(out.length ? out : '');
    }, measuresOfModel)));
    const pick = P.select(KINDS, 'gradient', () => {}, { 'data-add-rule-kind': '', 'aria-label': 'Kind of the new rule' });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-add-rule': '' }, B.icon('plus-lg'), ' Add a rule');
    add.addEventListener('click', () => set(list.concat([defaults(pick.value, env)])));
    box.appendChild(h('div', { class: 'bi-cond' }, pick, add));
    if (prop['x-hint']) { box.appendChild(h('small', { class: 'text-muted-d', text: prop['x-hint'] })); }
    return box;
  });

  /** The 2.1 short form: a measure, a comparison, a value and the colour of the text. */
  P.control('conditional', (prop, key, value, env, set) => {
    const ms = env.measures || [];
    if (!ms.length) { return null; }
    const list = Array.isArray(value) ? value : [];
    const box = h('fieldset', { class: 'bi-fieldset', 'data-conditional': '' }, h('legend', { text: 'Conditional colours' }));
    const put = (i, patch) => { const out = list.slice(); out[i] = Object.assign({}, out[i], patch); set(out); };
    list.forEach((c, i) => {
      const r = h('div', { class: 'bi-cond' },
        P.select(ms.map((m) => [m, m]), c.measure, (x) => put(i, { measure: x }), { 'aria-label': 'Measure' }),
        P.select(OPS.slice(0, 6), c.op, (x) => put(i, { op: x }), { 'aria-label': 'Comparison' }),
        P.text(c.value, (x) => put(i, { value: Number(x) }), { type: 'number', 'aria-label': 'Value' }),
        h('input', { type: 'color', value: c.colour || '#b3261e', 'aria-label': 'Colour', 'data-conditional-colour': String(i) }));
      B.q('input[type=color]', r).addEventListener('change', (e) => put(i, { colour: e.target.value }));
      const del = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove this conditional colour' }, B.icon('x-lg'));
      del.addEventListener('click', () => { const out = list.slice(); out.splice(i, 1); set(out.length ? out : ''); });
      r.appendChild(del);
      box.appendChild(r);
    });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-add-conditional': '', text: 'Add a rule' });
    add.addEventListener('click', () => set(list.concat([{ measure: ms[0], op: 'lt', value: 0, colour: '#b3261e' }])));
    box.appendChild(add);
    return box;
  });
})();
