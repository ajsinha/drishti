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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The Format pane generated from the format schemas (report schema v2, R6, 2.4.0): GET /bi/api/reports/format-schemas gives each visual family's sections and
   properties (types, enums, ranges, labels, hints), and this draws them as drishti-inputs' Form, one live form per section (a checkbox for a boolean, a choice
   for an enum, a number with its range read loosely, a group for an object such as the value axis or the axis titles, rows of checkboxes for data bars and a
   slicer's windows), and the custom widgets a property names (x-control: the palette's swatches and a colour, colours per series, a JSON object, a Vega-Lite
   specification). A property the visual's type does not use is left out. Each control carries the data attribute its x-hook names (else data-format-<key>),
   so tutorials and tests find it. A key whose default is on stores only false, one whose default is off only true; '' removes a key. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const P = (B.formatPane = B.formatPane || {});
  const HEX = /^#[0-9a-fA-F]{6}$/;
  let loaded = null;
  const widgets = {};

  /** The schemas, asked once per page. */
  P.schemas = () => (loaded = loaded || B.api('GET', '/reports/format-schemas'));
  /** Registers a custom widget for an x-control: fn(prop, key, value, env, set) returns the element (set('') removes the key). */
  P.control = (name, fn) => { widgets[name] = fn; };

  const kebab = (k) => String(k).replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
  const hookOf = (prop, key) => (prop['x-hook'] ? prop['x-hook'] : 'format-' + kebab(key));
  const appliesTo = (prop, type) => !Array.isArray(prop['x-types']) || !prop['x-types'].length || prop['x-types'].indexOf(type) >= 0;
  P.kebab = kebab;
  const isColour = (prop) => prop['x-control'] === 'colour' || (prop.type === 'string' && /#\[0-9a-fA-F\]\{6\}|0-9a-fA-F/.test(prop.pattern || ''));

  /** A colour that may be unset: the colour, and a button that clears it (a custom widget of the forms here). */
  function colour(value, onChange, attrs) {
    const set = HEX.test(value || '');
    const i = h('input', Object.assign({ type: 'color', class: 'bi-colour', value: set ? value : '#000000', 'data-unset': set ? null : '' }, attrs || {}));
    i.addEventListener('change', () => onChange(i.value));
    const x = h('button', { type: 'button', class: 'bi-chipbtn', 'aria-label': 'Clear this colour', title: 'Clear', disabled: !set }, B.icon('x-lg'));
    x.addEventListener('click', () => onChange(''));
    return { el: h('span', { class: 'bi-colourpick' }, i, x), input: i };
  }

  /** One property as a field of the section's form schema. */
  function fieldOf(prop, key, env) {
    const title = prop['x-label'] || key, hook = hookOf(prop, key), hint = prop['x-hint'];
    const xc = prop['x-control'];
    if (xc === 'slicer-mode') {
      return { type: 'string', title: title === key ? 'Mode' : title, widget: 'select', allowEmpty: false, hook: hook === 'format-mode' ? 'format-mode' : hook, enum: ['list', 'dropdown', 'range', 'relative-date'],
        enumLabels: ['list', 'dropdown', 'range (numbers and dates)', 'relative date (a date field)'], 'x-kind': 'slicer-mode' };
    }
    if (xc === 'relative-default' || xc === 'relative-options') {
      if ((env.format || {}).mode !== 'relative-date') { return null; }
      const all = B.visuals.RELATIVE, offered = (env.format.options || []).length ? env.format.options : all;
      if (xc === 'relative-default') { return { type: 'string', title: 'Starts as', widget: 'select', emptyLabel: 'no default (all dates)', enum: offered, enumLabels: offered.map(B.visuals.relativeLabel), hook: 'format-default', 'x-kind': xc }; }
      return { type: 'array', title: 'Windows it offers', items: { type: 'string', enum: all, enumLabels: all.map(B.visuals.relativeLabel) }, widget: 'checkboxes', optionHook: 'option', hook: 'format-options', 'x-kind': xc };
    }
    if (xc === 'measures') {
      const ms = env.measures || [];
      return ms.length ? { type: 'array', title: title, items: { type: 'string', enum: ms }, widget: 'checkboxes', optionHook: 'databar', hook: 'format-data-bars', 'x-kind': 'list' } : null;
    }
    if (xc === 'axis-titles') {
      return { type: 'object', title: title, 'x-kind': 'object', properties: { x: { type: 'string', title: 'Category axis title', hook: 'format-axis-x', lazy: true }, y: { type: 'string', title: 'Value axis title', hook: 'format-axis-y', lazy: true } } };
    }
    if (xc === 'json') { return { type: 'string', title: title, description: hint, widget: 'json', hook: hook, placeholder: '{"bins": 24}', 'x-kind': 'json' }; }
    if (xc && widgets[xc]) { return { type: 'string', title: title, widget: 'x-' + xc, noLabel: true, 'x-prop': prop, 'x-key': key, 'x-kind': 'custom' }; }
    if (isColour(prop)) { return { type: 'string', title: title, description: hint, widget: 'colour', hook: hook, 'x-kind': 'colour' }; }
    if (prop.type === 'boolean') { return { type: 'boolean', title: title, hook: hook, 'x-kind': 'boolean', 'x-default': prop.default === true }; }
    if (Array.isArray(prop.enum)) {
      return { type: typeof prop.enum[0] === 'number' ? 'number' : 'string', title: title, description: hint, enum: prop.enum, widget: 'select', emptyLabel: 'default', hook: hook, 'x-kind': 'plain' };
    }
    if (prop.type === 'integer' || prop.type === 'number') {
      return { type: prop.type, title: title, description: hint, minimum: prop.minimum == null ? undefined : prop.minimum, maximum: prop.maximum == null ? undefined : prop.maximum, lazy: true, hook: hook, 'x-kind': 'plain' };
    }
    if (prop.type === 'object' && prop.properties) {
      const props = {};
      Object.keys(prop.properties).forEach((k) => {
        const sub = fieldOf(Object.assign({ 'x-hook': hook + '-' + kebab(k) }, prop.properties[k]), k, env);
        if (sub) { props[k] = sub; }
      });
      return { type: 'object', title: title, description: hint, hook: hook, properties: props, 'x-kind': 'object' };
    }
    if (prop.type === 'string') { return { type: 'string', title: title, description: hint, maxLength: prop.maxLength || undefined, lazy: true, hook: hook, 'x-kind': 'text' }; }
    return { type: 'string', title: title, description: hint, widget: 'json', hook: hook, placeholder: '{"bins": 24}', 'x-kind': 'json' };
  }

  /** A field's value in the form, from the stored format. */
  function valueOf(fs, v) {
    if (fs['x-kind'] === 'boolean') { return v == null ? fs['x-default'] : v; }
    if (fs['x-kind'] === 'object') { const o = {}; const cur = v && typeof v === 'object' ? v : {}; Object.keys(fs.properties).forEach((k) => { const x = valueOf(fs.properties[k], cur[k]); if (x !== undefined) { o[k] = x; } }); return o; }
    if (fs['x-kind'] === 'slicer-mode') { return v || 'list'; }
    if (fs['x-kind'] === 'relative-options') { return v && v.length ? v : B.visuals.RELATIVE; }
    return v == null || v === '' ? undefined : v;
  }

  /** The value stored for a field ('' removes the key). */
  function stored(fs, v) {
    if (fs['x-kind'] === 'boolean') { return v === fs['x-default'] ? '' : v; }
    if (fs['x-kind'] === 'object') {
      const o = {};
      Object.keys(fs.properties || {}).forEach((k) => { const x = stored(fs.properties[k], (v || {})[k]); if (x !== '' && x !== undefined && x !== null) { o[k] = x; } });
      return Object.keys(o).length ? o : '';
    }
    if (fs['x-kind'] === 'relative-options') { const all = B.visuals.RELATIVE, ordered = all.filter((x) => (v || []).indexOf(x) >= 0); return ordered.length === all.length ? '' : ordered; }
    if (fs['x-kind'] === 'list') { return v && v.length ? v : ''; }
    if (v === undefined || v === null) { return ''; }
    return typeof v === 'string' ? v.trim() : v;
  }

  /**
   * Colours by value (a legend value's colours, a conditional rule's colour per value of a field): a colour per name (clearing it takes the
   * name out) and a name to add. A custom widget of the format forms ('colour-map', its options in the field's x-map).
   *
   * @param o { legend, names (shown even without a colour), hint, placeholder, label, addText, next(count) (the new name's colour), hooks {box, row, colour, name, add} }
   */
  function colourMap(value, set, o) {
    const cur = value && typeof value === 'object' ? value : {}, hk = o.hooks || {};
    const box = h('fieldset', { class: 'bi-fieldset', [hk.box]: '' }, h('legend', { text: o.legend }));
    const names = (o.names || []).concat(Object.keys(cur)).filter((x, i, a) => a.indexOf(x) === i);
    names.forEach((n) => box.appendChild(h('label', { class: 'bi-frow', [hk.row]: n }, h('span', { text: n }), colour(cur[n] || '', (c) => {
      const next = Object.assign({}, cur);
      if (c) { next[n] = c; } else { delete next[n]; }
      set(Object.keys(next).length ? next : '');
    }, { [hk.colour]: n, 'aria-label': 'Colour of ' + n }).el)));
    const name = h('input', { type: 'text', class: 'studio-in', placeholder: o.placeholder, 'aria-label': o.label, [hk.name]: '' });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: o.addText, [hk.add]: '' });
    add.addEventListener('click', () => { const n = name.value.trim(); if (n && !cur[n]) { set(Object.assign({}, cur, { [n]: o.next(names.length) })); } });
    box.appendChild(h('div', { class: 'bi-cond' }, name, add));
    if (o.hint) { box.appendChild(h('small', { class: 'text-muted-d', text: o.hint })); }
    return box;
  }

  /** The custom widgets of the format forms (drishti-inputs widgets): a colour, a JSON object, and the registered x-controls. */
  function formWidgets(env) {
    const out = {
      colour: (w) => { const c = colour(w.value, (x) => w.set(x || undefined), { id: w.id, 'aria-label': w.label }); return { el: c.el, input: c.input }; },
      'colour-map': (w) => ({ el: colourMap(w.value, (x) => w.set(x || undefined), w.field['x-map']) }),
      json: (w) => {
        const i = h('input', { type: 'text', class: 'studio-in', id: w.id, autocomplete: 'off', placeholder: w.field.placeholder, value: w.value ? JSON.stringify(w.value) : '' });
        i.addEventListener('change', () => {
          if (i.value.trim() === '') { w.set(undefined); return; }
          try { const o = JSON.parse(i.value); if (o && typeof o === 'object' && !Array.isArray(o)) { w.set(o); } } catch (e) { /* the last good value is kept until it parses */ }
        });
        return { el: i, input: i };
      }
    };
    Object.keys(widgets).forEach((name) => {
      out['x-' + name] = (w) => ({ el: widgets[name](w.field['x-prop'], w.field['x-key'], w.value, env, (x) => env.setFmt(w.field['x-key'], x)) || h('span', { hidden: true }) });
    });
    return out;
  }

  /**
   * A live Form (drishti-inputs, embedded: no <form> of its own) for the designer's other panes: the page, the report, a tile, a theme, a
   * conditional formatting rule. Its widgets are the format forms' (a colour that may be unset, a list of colours by value).
   *
   * @param o { fields (the properties of an object schema), value, label, on (key -> fn(value, form): the change of a top-level field), widgets }
   * @returns the Form
   */
  P.form = function (host, o) {
    const I = window.Drishti.elements.inputs;
    const form = new I.Form(host, { schema: { type: 'object', properties: o.fields }, value: o.value || {}, embedded: true, label: o.label || 'Settings', widgets: Object.assign(formWidgets(o.env || {}), o.widgets || {}) });
    form.on('change', (e) => { const key = e.path.split(/[.[]/)[0], fn = (o.on || {})[key]; if (fn) { fn(form.getField(key), form); } });
    return form;
  };

  /**
   * Draws the format of visual `v` into `host`, a section per group of its family in the family's order, each section a live Form.
   *
   * @param env { setFmt(key, value), format (the visual's format), type, palette, measures, fields, theme, open (section name -> open) }
   */
  P.draw = function (host, env) {
    const I = window.Drishti.elements && window.Drishti.elements.inputs;
    return P.schemas().then((s) => {
      const fam = s.families[s.types[env.type]];
      if (!fam || !I) { return; }
      const f = env.format || {};
      const groups = {};
      Object.keys(fam.properties).forEach((k) => {
        const prop = fam.properties[k];
        if (!appliesTo(prop, env.type) || (env.skip || []).indexOf(k) >= 0) { return; }
        (groups[prop['x-group'] || 'More'] = groups[prop['x-group'] || 'More'] || []).push(k);
      });
      const order = (fam.order || []).concat(Object.keys(groups).filter((g) => (fam.order || []).indexOf(g) < 0));
      const ws = formWidgets(env);
      order.forEach((g) => {
        if (!groups[g]) { return; }
        const slug = B.slug(g);
        const det = h('details', { class: 'bi-fsection', 'data-format-section': slug, open: env.open ? env.open(g) : true }, h('summary', { text: g }));
        const props = {}, value = {};
        groups[g].forEach((k) => { const fs = fieldOf(fam.properties[k], k, env); if (fs) { props[k] = fs; value[k] = valueOf(fs, f[k]); } });
        if (Object.keys(props).length) {
          const box = h('div', { class: 'bi-fform' });
          det.appendChild(box);
          const form = new I.Form(box, { schema: { type: 'object', properties: props }, value: value, live: true, label: g, widgets: ws });
          form.on('change', (e) => {
            const key = e.path.split(/[.[]/)[0], fs = props[key];
            if (!fs || fs['x-kind'] === 'custom') { return; }
            const x = stored(fs, form.getField(key));
            if (fs['x-kind'] === 'slicer-mode' && env.slicerMode) { env.slicerMode(x || 'list'); return; }
            env.setFmt(key, x);
            if (fs['x-kind'] === 'relative-options' && env.format.default && x && x.indexOf(env.format.default) < 0) { env.setFmt('default', ''); }
          });
        }
        det.addEventListener('toggle', () => { if (env.toggled) { env.toggled(g, det.open); } });
        host.appendChild(det);
      });
    }).catch((e) => { host.appendChild(h('p', { class: 'bi-vis-note', text: 'The format settings did not load: ' + e.message })); });
  };

  // ---- the custom widgets the schemas name ------------------------------------------------------------------------------

  P.control('swatches', (prop, key, value, env, set) => {
    const r = h('div', { class: 'bi-frow' }, h('span', { text: prop['x-label'] || 'Colour' }));
    const sw = h('div', { class: 'bi-swatches', role: 'group', 'aria-label': 'Theme colours' });
    (env.palette || []).forEach((c, i) => {
      const hex = P.hex(c);
      const b = h('button', { type: 'button', class: 'bi-swatch', 'aria-label': 'Theme colour ' + (i + 1), title: c, 'aria-pressed': value === hex ? 'true' : 'false', 'data-colour': c });
      b.style.background = c;
      b.addEventListener('click', () => set(hex));
      sw.appendChild(b);
    });
    const none = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', text: 'Theme default' });
    none.addEventListener('click', () => set(''));
    r.appendChild(sw);
    r.appendChild(colour(value, set, { 'aria-label': 'Another colour', 'data-format-colour-custom': '' }).el);
    r.appendChild(none);
    return r;
  });

  P.control('series-colours', (prop, key, value, env, set) => colourMap(value, set, {
    legend: prop['x-label'] || 'Colours', names: env.series || [], hint: prop['x-hint'], placeholder: 'A legend value', label: 'A value of the legend field to colour', addText: 'Colour this value',
    next: (n) => (env.palette && P.hex(env.palette[n % env.palette.length])) || '#1f4e79', hooks: { box: 'data-format-series-colours', row: 'data-series-row', colour: 'data-series-colour', name: 'data-series-name', add: 'data-series-add' }
  }));

  P.control('vega-spec', (prop, key, value, env, set) => {
    const box = h('textarea', { class: 'studio-in bi-vegalite-spec', rows: '10', spellcheck: 'false', 'data-format-vega-spec': '', 'aria-label': 'Vega-Lite specification (JSON)' });
    box.value = value ? JSON.stringify(value, null, 2) : '';
    const note = h('small', { class: 'text-muted-d', 'data-vega-note': '', text: 'The data is the rows of the wells: put no data in it. Refer to fields by the name after the table (desk, not trades.desk) and to measures by their name. drishti-chart draws it; a feature it does not translate is refused with what to use instead.' });
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

  /** A colour as #rrggbb (a palette colour may be rgb(...)). */
  P.hex = function (c) {
    if (HEX.test(c)) { return c.toLowerCase(); }
    const m = /rgba?\((\d+)[ ,]+(\d+)[ ,]+(\d+)/.exec(c || '');
    return m ? '#' + [m[1], m[2], m[3]].map((n) => ('0' + (+n).toString(16)).slice(-2)).join('') : c;
  };
})();
