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
/* The connector settings form, generated from a connector type's own description (GET /admin/connectors/api/plugins): a fieldset per group, a secret a
   reference field (${ENV_VAR} or file:/path, never the value), a boolean or a list of values a select with the default first, and the settings the type has
   no field for as rows. One definition for Admin -> Connectors (editing a connector) and the connector designer (creating one): drishti-inputs' Form. */
(function () {
  'use strict';
  var GROUPS = [['connection', 'Connection'], ['tls', 'TLS'], ['proxy', 'Proxy'], ['mapping', 'What it reads'], ['tuning', 'Tuning'], ['pool', 'Connection pool'], ['s3', 'S3 storage'], ['security', 'Security'], ['general', 'General']];
  var SECRET_OK = /^(\$\{[A-Za-z_][A-Za-z0-9_]*:?\}|file:.+)$/;

  /** One setting's field in the form. */
  function settingField(spec) {
    var t = spec.type || 'string', dflt = spec.default != null ? 'default (' + spec.default + ')' : 'default';
    var fs = { type: 'string', title: spec.name + (spec.required ? ' (required)' : ''), mono: t === 'path' || !!spec.secret,
      description: spec.description + (spec.secret ? ' A credential is never written into a connector file: use ${ENV_VAR} or file:/path.' : ''),
      hook: { 'data-setting': spec.name } };
    if (spec.required) { fs.hook['aria-required'] = 'true'; }
    if (t === 'boolean' || t.indexOf('enum:') === 0) {
      fs.widget = 'select'; fs.allowEmpty = true; fs.emptyLabel = dflt;
      fs['enum'] = t === 'boolean' ? ['true', 'false'] : t.slice(5).split('|');
    } else {
      fs.placeholder = spec.secret ? '${ENV_VAR} or file:/run/secrets/name' : (spec.default != null ? String(spec.default) : '');
      if (spec.secret) { fs.format = 'password'; fs.hook['data-secret'] = ''; }
    }
    return fs;
  }

  /** The schema and the value of the settings form for a type's description and the settings so far: {schema, value, keys} (keys: a setting's name -> {path, name}). */
  function schemaFor(spec, settings, proxyNote) {
    var by = {}, props = {}, value = {}, keys = {};
    settings = settings || {};
    ((spec && spec.settings) || []).forEach(function (s) { if (s.name.slice(-2) !== '.*') { (by[s.group] = by[s.group] || []).push(s); } });
    GROUPS.concat(Object.keys(by).filter(function (g) { return !GROUPS.some(function (x) { return x[0] === g; }); }).map(function (g) { return [g, g]; })).forEach(function (g, gi) {
      if (!by[g[0]]) { return; }
      var gk = 'g' + gi, group = { type: 'object', title: g[1], properties: {}, hook: { 'data-group': g[0], 'class': 'conn-set' } };
      if (g[0] === 'proxy' && proxyNote) { group.description = proxyNote; }
      value[gk] = {};
      by[g[0]].forEach(function (s, si) {
        var k = 's' + si;
        keys[s.name] = { path: gk + '.' + k, name: s.name };
        group.properties[k] = settingField(s);
        if (settings[s.name] != null) { value[gk][k] = String(settings[s.name]); }
      });
      props[gk] = group;
    });
    props.extra = { type: 'array', title: 'Other settings', addLabel: 'Add setting', itemHook: 'extra-row', hook: { 'data-group': 'other', 'class': 'conn-set' },
      description: 'Settings the plugin reads that have no field above, such as layout.trade.columns or mode.trade.',
      items: { type: 'object', title: 'Setting', properties: {
        key: { type: 'string', title: 'Setting name', mono: true, placeholder: 'setting.name', hook: { 'data-extra-key': '' } },
        value: { type: 'string', title: 'Setting value', mono: true, hook: { 'data-extra-val': '' } } } } };
    value.extra = Object.keys(settings).filter(function (k) { return !keys[k]; }).map(function (k) { return { key: k, value: String(settings[k]) }; });
    return { schema: { type: 'object', properties: props }, value: value, keys: keys };
  }

  /** Draws the form in `host`: {form, keys}. */
  function build(host, spec, settings, proxyNote) {
    var s = schemaFor(spec, settings, proxyNote);
    host.textContent = '';
    var form = new window.Drishti.elements.inputs.Form(host, { schema: s.schema, value: s.value, live: true, label: 'The plugin\'s settings', pathAttr: 'data-conn-path' });
    return { form: form, keys: s.keys };
  }

  /** The settings the form holds: {name: value} without the empty ones, the extra rows included. */
  function read(form, keys) {
    var out = {};
    Object.keys(keys).forEach(function (name) {
      var v = String(form.getField(keys[name].path) || '').trim();
      if (v !== '') { out[name] = v; }
    });
    (form.getField('extra') || []).forEach(function (r) {
      var k = String(r.key || '').trim(), v = String(r.value || '').trim();
      if (k && v !== '') { out[k] = v; }
    });
    return out;
  }

  window.Drishti.connSettings = { GROUPS: GROUPS, SECRET_OK: SECRET_OK, settingField: settingField, schemaFor: schemaFor, build: build, read: read };
})();
