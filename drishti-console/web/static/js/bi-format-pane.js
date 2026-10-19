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
/* The Format pane generated from the format schemas (report schema v2, R6, 2.4.0): GET /bi/api/reports/format-schemas gives each visual family's sections and
   properties (types, enums, ranges, labels, hints), and this draws the controls for the selected visual: a checkbox for a boolean, a choice for an enum, a
   number with its range, a colour, a group for an object (the value axis, small multiples), and the custom controls a property names (x-control: the rules of
   conditional formatting, the palette's swatches, colours per series, data bars, a slicer's windows, a JSON object). A property the visual's type does not use
   is left out. Each control carries the data attribute its x-hook names (else data-format-<key>), so tutorials and tests find it. The theme designer draws
   a family's defaults with the same function. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const P = (B.formatPane = B.formatPane || {});
  const HEX = /^#[0-9a-fA-F]{6}$/;
  let loaded = null;
  const controls = {};

  /** The schemas, asked once per page. */
  P.schemas = () => (loaded = loaded || B.api('GET', '/reports/format-schemas'));
  /** Registers a custom control: fn(prop, key, value, env) returns the element. */
  P.control = (name, fn) => { controls[name] = fn; };

  const kebab = (k) => String(k).replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
  const hook = (prop, key) => (prop['x-hook'] ? 'data-' + prop['x-hook'] : 'data-format-' + kebab(key));
  const appliesTo = (prop, type) => !Array.isArray(prop['x-types']) || !prop['x-types'].length || prop['x-types'].indexOf(type) >= 0;
  P.kebab = kebab;

  function row(label, input, hint) {
    return h('label', { class: 'bi-frow' }, h('span', { text: label }), input, hint ? h('small', { class: 'text-muted-d', text: hint }) : null);
  }
  P.row = row;
  function checkbox(label, value, onChange, attrs) {
    const c = h('input', Object.assign({ type: 'checkbox' }, attrs || {}));
    c.checked = !!value;
    c.addEventListener('change', () => onChange(c.checked));
    return h('label', { class: 'bi-checkrow' }, c, ' ' + label);
  }
  P.checkbox = checkbox;
  function select(options, value, onChange, attrs) {
    const s = h('select', Object.assign({ class: 'studio-select' }, attrs || {}), options.map((o) => h('option', { value: o[0], text: o[1] })));
    s.value = value == null ? '' : String(value);
    s.addEventListener('change', () => onChange(s.value));
    return s;
  }
  P.select = select;
  function text(value, onChange, attrs) {
    const i = h('input', Object.assign({ type: 'text', class: 'studio-in', autocomplete: 'off', value: value == null ? '' : String(value) }, attrs || {}));
    i.addEventListener('change', () => onChange(i.value));
    return i;
  }
  P.text = text;
  /** A colour that may be unset: the colour, and a button that clears it. */
  function colour(value, onChange, attrs) {
    const set = HEX.test(value || '');
    const i = h('input', Object.assign({ type: 'color', class: 'bi-colour', value: set ? value : '#000000', 'data-unset': set ? null : '' }, attrs || {}));
    i.addEventListener('change', () => onChange(i.value));
    const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Clear this colour', title: 'Clear', disabled: !set }, B.icon('x-lg'));
    x.addEventListener('click', () => onChange(''));
    return h('span', { class: 'bi-colourpick' }, i, x);
  }
  P.colour = colour;
  const num = (s, integer) => { if (s === '' || s == null) { return ''; } const n = Number(s); return isFinite(n) ? (integer ? Math.round(n) : n) : ''; };

  /** One property's control; `set(value)` stores it ('' or null removes it). */
  function control(prop, key, value, env, set) {
    const label = prop['x-label'] || key;
    const attrs = { [hook(prop, key)]: '', 'aria-label': label };
    if (prop['x-control'] && controls[prop['x-control']]) { return controls[prop['x-control']](prop, key, value, env, set); }
    if (prop['x-control'] === 'colour' || prop.type === 'string' && /#\[0-9a-fA-F\]\{6\}|0-9a-fA-F/.test(prop.pattern || '')) {
      return row(label, colour(value, set, attrs), prop['x-hint']);
    }
    if (prop.type === 'boolean') {                                         // a key whose default is on stores only `false`; one whose default is off stores only `true`
      const dflt = prop.default === true;
      return checkbox(label, value == null ? dflt : value, (on) => set(on === dflt ? '' : on), attrs);
    }
    if (Array.isArray(prop.enum)) {
      return row(label, select([['', 'default']].concat(prop.enum.map((e) => [String(e), String(e)])), value, (x) => set(x), attrs), prop['x-hint']);
    }
    if (prop.type === 'integer' || prop.type === 'number') {
      const a = Object.assign({ type: 'number', step: prop.type === 'integer' ? '1' : 'any' }, attrs);
      if (prop.minimum != null) { a.min = String(prop.minimum); }
      if (prop.maximum != null) { a.max = String(prop.maximum); }
      return row(label, text(value, (x) => set(num(x, prop.type === 'integer')), a), prop['x-hint']);
    }
    if (prop.type === 'object' && prop.properties) {
      const box = h('fieldset', { class: 'bi-fieldset', [hook(prop, key)]: '' }, h('legend', { text: label }));
      const cur = value && typeof value === 'object' ? value : {};
      Object.keys(prop.properties).forEach((k) => {
        const sub = prop.properties[k];
        box.appendChild(control(Object.assign({ 'x-hook': (prop['x-hook'] || 'format-' + kebab(key)) + '-' + kebab(k) }, sub), k, cur[k], env, (x) => {
          const next = Object.assign({}, cur);
          if (x === '' || x == null) { delete next[k]; } else { next[k] = x; }
          set(Object.keys(next).length ? next : '');
        }));
      });
      if (prop['x-hint']) { box.appendChild(h('small', { class: 'text-muted-d', text: prop['x-hint'] })); }
      return box;
    }
    if (prop.type === 'string') { return row(label, text(value, (x) => set(x.trim()), Object.assign({ maxlength: prop.maxLength ? String(prop.maxLength) : null }, attrs)), prop['x-hint']); }
    return row(label, text(value == null ? '' : JSON.stringify(value), (x) => { try { set(x.trim() ? JSON.parse(x) : ''); } catch (e) { /* kept until it parses */ } }, attrs), prop['x-hint']);
  }
  P.controlFor = control;

  /**
   * Draws the format of visual `v` into `host`, a section per group of its family in the family's order.
   *
   * @param env { setFmt(key, value), format (the visual's format), type, palette, measures, fields, theme, open (section name -> open) }
   */
  P.draw = function (host, env) {
    return P.schemas().then((s) => {
      const fam = s.families[s.types[env.type]];
      if (!fam) { return; }
      const f = env.format || {};
      const groups = {};
      Object.keys(fam.properties).forEach((k) => {
        const prop = fam.properties[k];
        if (!appliesTo(prop, env.type) || (env.skip || []).indexOf(k) >= 0) { return; }
        (groups[prop['x-group'] || 'More'] = groups[prop['x-group'] || 'More'] || []).push(k);
      });
      const order = (fam.order || []).concat(Object.keys(groups).filter((g) => (fam.order || []).indexOf(g) < 0));
      order.forEach((g) => {
        if (!groups[g]) { return; }
        const slug = B.slug(g);
        const det = h('details', { class: 'bi-fsection', 'data-format-section': slug, open: env.open ? env.open(g) : true }, h('summary', { text: g }));
        groups[g].forEach((k) => {
          const el = control(fam.properties[k], k, f[k], env, (x) => env.setFmt(k, x));
          if (el) { det.appendChild(el); }
        });
        det.addEventListener('toggle', () => { if (env.toggled) { env.toggled(g, det.open); } });
        host.appendChild(det);
      });
    }).catch((e) => { host.appendChild(h('p', { class: 'bi-vis-note', text: 'The format settings did not load: ' + e.message })); });
  };

  // ---- the custom controls the schemas name ------------------------------------------------------------------------------

  P.control('swatches', (prop, key, value, env, set) => {
    const r = h('div', { class: 'bi-frow' }, h('span', { text: prop['x-label'] || 'Colour' }));
    const sw = h('div', { class: 'bi-swatches', role: 'group', 'aria-label': 'Theme colours' });
    (env.palette || []).forEach((c, i) => {
      const hex = B.formatPane.hex(c);
      const b = h('button', { type: 'button', class: 'bi-swatch', 'aria-label': 'Theme colour ' + (i + 1), title: c, 'aria-pressed': value === hex ? 'true' : 'false', 'data-colour': c });
      b.style.background = c;
      b.addEventListener('click', () => set(hex));
      sw.appendChild(b);
    });
    const none = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Theme default' });
    none.addEventListener('click', () => set(''));
    r.appendChild(sw);
    r.appendChild(colour(value, set, { 'aria-label': 'Another colour', 'data-format-colour-custom': '' }));
    r.appendChild(none);
    return r;
  });

  P.control('series-colours', (prop, key, value, env, set) => {
    const cur = value && typeof value === 'object' ? value : {};
    const box = h('fieldset', { class: 'bi-fieldset', 'data-format-series-colours': '' }, h('legend', { text: prop['x-label'] || 'Colours' }));
    const names = (env.series || []).concat(Object.keys(cur)).filter((x, i, a) => a.indexOf(x) === i);
    names.forEach((n, i) => box.appendChild(row(n, colour(cur[n] || '', (c) => {
      const next = Object.assign({}, cur);
      if (c) { next[n] = c; } else { delete next[n]; }
      set(Object.keys(next).length ? next : '');
    }, { 'data-series-colour': n, 'aria-label': 'Colour of ' + n }))));
    const name = h('input', { type: 'text', class: 'studio-in', placeholder: 'A legend value', 'aria-label': 'A value of the legend field to colour', 'data-series-name': '' });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Colour this value', 'data-series-add': '' });
    add.addEventListener('click', () => { const n = name.value.trim(); if (n) { set(Object.assign({}, cur, { [n]: (env.palette && B.formatPane.hex(env.palette[names.length % env.palette.length])) || '#1f4e79' })); } });
    box.appendChild(h('div', { class: 'bi-cond' }, name, add));
    if (prop['x-hint']) { box.appendChild(h('small', { class: 'text-muted-d', text: prop['x-hint'] })); }
    return box;
  });

  P.control('axis-titles', (prop, key, value, env, set) => {
    const at = value && typeof value === 'object' ? value : {};
    const put = (k, x) => { const next = Object.assign({}, at); if (x) { next[k] = x; } else { delete next[k]; } set(Object.keys(next).length ? next : ''); };
    return h('div', {}, row('Category axis title', text(at.x, (x) => put('x', x.trim()), { 'data-format-axis-x': '' })), row('Value axis title', text(at.y, (x) => put('y', x.trim()), { 'data-format-axis-y': '' })));
  });

  P.control('measures', (prop, key, value, env, set) => {
    const ms = env.measures || [];
    if (!ms.length) { return null; }
    const box = h('fieldset', { class: 'bi-fieldset', 'data-format-data-bars': '' }, h('legend', { text: prop['x-label'] || key }));
    ms.forEach((m) => box.appendChild(checkbox(m, (value || []).indexOf(m) >= 0, (on) => {
      const cur = (value || []).filter((x) => x !== m).concat(on ? [m] : []);
      set(cur.length ? cur : '');
    }, { 'data-databar': m })));
    return box;
  });

  P.control('json', (prop, key, value, env, set) => row(prop['x-label'] || key, text(value ? JSON.stringify(value) : '', (x) => {
    if (x.trim() === '') { set(''); return; }
    try { const o = JSON.parse(x); if (o && typeof o === 'object' && !Array.isArray(o)) { set(o); } } catch (e) { /* the last good value is kept until it parses */ }
  }, { [hook(prop, key)]: '', placeholder: '{"bins": 24}' }), prop['x-hint']));

  P.control('vega-spec', (prop, key, value, env, set) => {
    const box = h('textarea', { class: 'studio-in bi-vega-spec', rows: '10', spellcheck: 'false', 'data-format-vega-spec': '', 'aria-label': 'Vega-Lite specification (JSON)' });
    box.value = value ? JSON.stringify(value, null, 2) : '';
    const note = h('small', { class: 'text-muted-d', 'data-vega-note': '', text: 'The data is the rows of the wells: put no data in it. Refer to fields by the name after the table (desk, not trades.desk) and to measures by their name.' });
    box.addEventListener('change', () => {
      if (box.value.trim() === '') { set(''); note.textContent = ''; return; }
      try {
        const o = JSON.parse(box.value);
        if (!o || typeof o !== 'object' || Array.isArray(o)) { throw new Error('not an object'); }
        set(o);
        note.textContent = 'Saved in the report.';
      } catch (e) { note.textContent = 'Not valid JSON yet (' + e.message + '); the last good specification is kept.'; }
    });
    return h('label', { class: 'bi-frow' }, h('span', { text: 'Vega-Lite specification (JSON)' }), box, note);
  });

  P.control('slicer-mode', (prop, key, value, env, set) => row(prop['x-label'] || 'Mode', select([['list', 'list'], ['dropdown', 'dropdown'], ['range', 'range (numbers and dates)'], ['relative-date', 'relative date (a date field)']],
    value || 'list', (x) => { if (env.slicerMode) { env.slicerMode(x); } else { set(x); } }, { 'data-format-mode': '' })));

  P.control('relative-default', (prop, key, value, env, set) => {
    if ((env.format || {}).mode !== 'relative-date') { return null; }
    const all = B.visuals.RELATIVE, now = (env.format.options || []).length ? env.format.options : all;
    return row('Starts as', select([['', 'no default (all dates)']].concat(now.map((o) => [o, B.visuals.relativeLabel(o)])), value || '', (x) => set(x), { 'data-format-default': '' }));
  });

  P.control('relative-options', (prop, key, value, env, set) => {
    if ((env.format || {}).mode !== 'relative-date') { return null; }
    const all = B.visuals.RELATIVE, now = value && value.length ? value : all;
    const box = h('fieldset', { class: 'bi-fieldset', 'data-format-options': '' }, h('legend', { text: 'Windows it offers' }));
    all.forEach((o) => box.appendChild(checkbox(B.visuals.relativeLabel(o), now.indexOf(o) >= 0, (on) => {
      const cur = now.filter((x) => x !== o).concat(on ? [o] : []);
      const ordered = all.filter((x) => cur.indexOf(x) >= 0);
      set(ordered.length === all.length ? '' : ordered);
      if (env.format.default && ordered.length && ordered.indexOf(env.format.default) < 0) { env.setFmt('default', ''); }
    }, { 'data-option': o })));
    return box;
  });

  /** A colour as #rrggbb (a palette colour may be rgb(...)). */
  P.hex = function (c) {
    if (HEX.test(c)) { return c.toLowerCase(); }
    const m = /rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)/.exec(c || '');
    return m ? '#' + [m[1], m[2], m[3]].map((n) => ('0' + (+n).toString(16)).slice(-2)).join('') : c;
  };
})();
