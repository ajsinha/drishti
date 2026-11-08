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
/* Conditional formatting in the Format pane (R6, 2.4.0; docs/architecture/ARCHITECTURE.md §28 section 11.1, gap 6), as the format schema's `rules` control: a list of rules, each with what it colours
   (a measure of the visual, or a field for a colour per value) and its kind. A rule colours the text or the background when a measure (this one or another
   one of the model) compares with a number; a colour scale runs from a low to a high colour, with a middle colour if wanted, over the visual's own values (or
   fixed values); icons put an arrow, a light, a sign or a flag by the number against one or two thresholds; a colour per value colours the values of a field.
   Each rule is a live drishti-inputs Form (B.formatPane.form) whose fields follow its kind, every control keeping its data-rule-* hook; nothing is typed but
   the numbers. The 2.1 short form (`conditional:`) is a Form of repeatable rows. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const P = B.formatPane;
  const OPS = ['lt', 'le', 'gt', 'ge', 'eq', 'ne', 'between'], OP_WORDS = ['<', '≤', '>', '≥', '=', '≠', 'between'];
  const KINDS = ['rule', 'gradient', 'icons', 'values'], KIND_WORDS = ['Rule', 'Colour scale', 'Icons', 'Colour per value'];

  function defaults(type, env) {
    const gb = B.theme.goodBad({ theme: env.theme, t: env.tokens });
    const m = (env.measures || [])[0] || '', f = (env.fields || [])[0] || '';
    if (type === 'gradient') { return { type, apply: m, min: P.hex(gb.bad), mid: '#ffffff', max: P.hex(gb.good) }; }
    if (type === 'icons') { return { type, apply: m, icons: 'arrows', thresholds: [0, 1000000] }; }
    if (type === 'values') { return { type, apply: f, colours: {} }; }
    return { type: 'rule', apply: m, op: 'lt', value: 0, colour: P.hex(gb.bad) };
  }

  const pick = (title, values, words, hook, more) => Object.assign({ type: 'string', title, enum: values, enumLabels: words, widget: 'select', allowEmpty: false, hook }, more || {});
  const num = (title, hook, more) => Object.assign({ type: 'number', title, lazy: true, hook }, more || {});
  const col = (title, hook) => ({ type: 'string', title, widget: 'colour', hook });
  const tick = (title, hook) => ({ type: 'boolean', title, hook });
  const given = (x) => typeof x === 'number' && !isNaN(x), ok = (x) => x === undefined || x === null || given(x);

  /** One rule's fields, its value in the form and what each field's change does to the rule ({fields, value, on}). */
  function ruleForm(r, env, change, put, measuresOfModel) {
    const targets = r.type === 'values' ? env.fields || [] : env.measures || [];
    const fields = {
      type: pick('Kind of rule', KINDS, KIND_WORDS, 'rule-kind'),
      apply: pick('What it colours', targets, targets.map(B.label), 'rule-apply')
    };
    const value = { type: r.type, apply: r.apply };
    const on = {
      type: (x) => put(Object.assign(defaults(x, env), { apply: x === 'values' ? (env.fields || [])[0] || r.apply : (env.measures || []).indexOf(r.apply) >= 0 ? r.apply : (env.measures || [])[0] })),
      apply: (x) => change({ apply: x })
    };
    const add = (key, field, v, fn) => { fields[key] = field; if (v !== undefined && v !== null && v !== '') { value[key] = v; } on[key] = fn; };
    if (r.type === 'rule') {
      const others = measuresOfModel.filter((m) => m !== r.apply);
      add('on', pick('When this measure', others, others, 'rule-on', { allowEmpty: true, emptyLabel: 'its own value' }), r.on, (x) => change({ on: x || undefined }));
      add('op', pick('Comparison', OPS, OP_WORDS, 'rule-op'), r.op || 'lt', (x) => change({ op: x }));
      add('value', num('Value', 'rule-value'), r.value, (x) => { if (ok(x)) { change({ value: Number(x) || 0 }); } });
      if (r.op === 'between') { add('to', num('and', 'rule-to'), r.to, (x) => { if (ok(x)) { change({ to: Number(x) || 0 }); } }); }
      add('colour', col('Text colour', 'rule-colour'), r.colour, (c) => change({ colour: c || undefined }));
      add('background', col('Background colour', 'rule-background'), r.background, (c) => change({ background: c || undefined }));
    } else if (r.type === 'gradient') {
      const three = !!r.mid;
      add('min', col('Colour of the lowest value', 'rule-min'), r.min, (c) => change({ min: c || r.min }));
      add('three', tick('A middle colour (three colours)', 'rule-three'), three, (x) => change({ mid: x ? '#ffffff' : undefined, midValue: x ? r.midValue : undefined }));
      if (three) { add('mid', col('Colour of the middle value', 'rule-mid'), r.mid, (c) => change({ mid: c || r.mid })); }
      add('max', col('Colour of the highest value', 'rule-max'), r.max, (c) => change({ max: c || r.max }));
      add('target', pick('Colours the', ['font'], ['text'], 'rule-target', { allowEmpty: true, emptyLabel: 'background' }), r.target === 'font' ? 'font' : '', (x) => change({ target: x || undefined }));
      add('minValue', num('Lowest value', 'rule-min-value', { placeholder: 'lowest shown' }), r.minValue, (x) => { if (ok(x)) { change({ minValue: given(x) ? x : undefined }); } });
      add('maxValue', num('Highest value', 'rule-max-value', { placeholder: 'highest shown', description: 'Empty: the lowest and highest values the visual shows.' }), r.maxValue,
        (x) => { if (ok(x)) { change({ maxValue: given(x) ? x : undefined }); } });
    } else if (r.type === 'icons') {
      const t = r.thresholds || [0];
      add('icons', pick('Icons', B.visuals.ICON_SETS, B.visuals.ICON_SETS, 'rule-icons'), r.icons || 'arrows', (x) => change({ icons: x }));
      add('t1', num('Low below', 'rule-t1'), t[0], (x) => { if (ok(x)) { change({ thresholds: [Number(x) || 0].concat(t.length > 1 ? [t[1]] : []) }); } });
      add('t2', num('High from', 'rule-t2', { description: 'Below the first: low; from the second (or the first, alone): high; in between: middle.' }), t.length > 1 ? t[1] : undefined,
        (x) => { if (ok(x)) { change({ thresholds: given(x) ? [t[0], x] : [t[0]] }); } });
      add('reverse', tick('Low is good (reverse the icons)', 'rule-reverse'), !!r.reverse, (x) => change({ reverse: x || undefined }));
    } else if (r.type === 'values') {
      add('colours', { type: 'string', title: 'Colours', widget: 'colour-map', noLabel: true, 'x-map': { legend: 'Colour per value', placeholder: 'A value of ' + B.label(r.apply || 'the field'), label: 'Value to colour',
        addText: 'Colour it', next: () => '#1565c0', hooks: { box: 'data-rule-colours', row: 'data-rule-colour-of', colour: 'data-rule-value-colour', name: 'data-rule-value-name', add: 'data-rule-value-add' } } },
      r.colours, (x) => change({ colours: x || {} }));
      add('target', pick('Colours the', ['background'], ['background'], 'rule-target', { allowEmpty: true, emptyLabel: 'text' }), r.target === 'background' ? 'background' : '', (x) => change({ target: x || undefined }));
    }
    return { fields, value, on };
  }

  /** One rule: its Form and the button that removes it; `put(rule)` replaces it (null removes it). */
  function ruleEditor(r, i, env, put, measuresOfModel) {
    const box = h('div', { class: 'bi-rule', 'data-rule': String(i), 'data-rule-type': r.type, role: 'group', 'aria-label': 'Rule ' + (i + 1) });
    const del = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Remove rule ' + (i + 1), 'data-rule-remove': String(i) }, B.icon('x-lg'));
    del.addEventListener('click', () => put(null));
    box.appendChild(h('div', { class: 'bi-rule-head' }, h('strong', { text: 'Rule ' + (i + 1) }), del));
    const host = h('div', { class: 'bi-fform' });
    box.appendChild(host);
    const f = ruleForm(r, env, (patch) => put(Object.assign({}, r, patch)), put, measuresOfModel);
    P.form(host, { fields: f.fields, value: f.value, on: f.on, label: 'Rule ' + (i + 1), env });
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
    let kind = 'gradient';
    const bar = h('div', { class: 'bi-rule-add' });
    P.form(bar, { fields: { kind: pick('Kind of the new rule', KINDS, KIND_WORDS, 'add-rule-kind') }, value: { kind }, on: { kind: (x) => { kind = x; } }, label: 'A new rule' });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-add-rule': '' }, B.icon('plus-lg'), ' Add a rule');
    add.addEventListener('click', () => set(list.concat([defaults(kind, env)])));
    bar.appendChild(add);
    box.appendChild(bar);
    if (prop['x-hint']) { box.appendChild(h('small', { class: 'text-muted-d', text: prop['x-hint'] })); }
    return box;
  });

  /** The 2.1 short form: rows of a measure, a comparison, a value and the colour of the text (a Form's repeatable rows: add, remove, move). */
  P.control('conditional', (prop, key, value, env, set) => {
    const ms = env.measures || [];
    if (!ms.length) { return null; }
    const box = h('div', { class: 'bi-fform', 'data-conditional': '' });
    P.form(box, {
      label: 'Conditional colours', env,
      fields: { conditional: { type: 'array', title: 'Conditional colours', addLabel: 'Add a rule', hook: 'conditional-rows', items: { type: 'object', title: 'Colour', properties: {
        measure: pick('Measure', ms, ms, 'conditional-measure', { default: ms[0] }),
        op: pick('Comparison', OPS.slice(0, 6), OP_WORDS.slice(0, 6), 'conditional-op', { default: 'lt' }),
        value: num('Value', 'conditional-value', { default: 0 }),
        colour: Object.assign(col('Colour', 'conditional-colour'), { default: '#b3261e' })
      } } } },
      value: { conditional: Array.isArray(value) ? value : [] },
      on: { conditional: (list) => { if (!(list || []).some((c) => typeof c.value === 'number' && isNaN(c.value))) { set(list && list.length ? list.map(tidy) : ''); } } }
    });
    return box;
  });
})();
