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
/* Build -> New pack, step 4: every generated Sutra, rendered on its samples. A list of the Sutras on the left (with how many of its panel by
 * sample cells rendered, were empty or failed), tabs on the right: the Sutra rendered on a sample (the server's own preview), its YAML, the
 * About text it carries, its samples (real, example or synthetic, labelled) and the panel by sample check. The server renders the preview;
 * the page only places it. */
(function () {
  'use strict';
  var U = window.DrishtiPackUI, el = U.el;
  var TABS = [['preview', 'Preview'], ['yaml', 'Sutra'], ['about', 'About text'], ['samples', 'Samples'], ['checks', 'Checks']];

  function badge(text, cls) { return el('span', { class: 'pk-badge ' + (cls || ''), text: text }); }

  function counts(s) {
    var c = s.counts || {};
    var out = [];
    if (s.problems && s.problems.length) { out.push(badge('problem', 'bad')); }
    if (c.error) { out.push(badge(c.error + ' failed', 'bad')); }
    if (c.empty) { out.push(badge(c.empty + ' empty', 'warn')); }
    if (!out.length) { out.push(badge('ok', 'ok')); }
    return out;
  }

  function checksTable(s) {
    var chk = s.check;
    if (!chk || !chk.panels) { return el('p', { class: 'text-muted-d', text: s.problems && s.problems.length ? 'The server refused this Sutra; see the Sutra tab.' : 'No check result.' }); }
    var names = (chk.samples || []).map(function (x) { return x.name; });
    var t = el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('thead', {}, [el('tr', {}, [el('th', { text: 'Panel' })].concat(names.map(function (n) { return el('th', { text: n }); })))])]);
    var tb = el('tbody');
    chk.panels.forEach(function (p) {
      tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: p.id })].concat((p.cells || []).map(function (c) {
        return el('td', {}, [badge(c.status === 'ok' ? 'ok' : c.status === 'empty' ? 'empty' : c.status, c.status === 'ok' ? 'ok' : c.status === 'empty' ? 'warn' : 'bad'), c.message ? el('span', { class: 'pk-hint text-muted-d', text: ' ' + c.message }) : null]);
      }))));
    });
    t.appendChild(tb);
    return el('div', { class: 'tbl-wrap' }, [t]);
  }

  function aboutView(s) {
    var a = s.about || {};
    var box = el('div', { class: 'pk-about' });
    box.appendChild(el('h4', { class: 'bs-h2', text: 'About this page: ' + (a.title || s.kind) }));
    box.appendChild(el('p', { class: 'pk-about-t', text: a.about || '' }));
    var g = a.glossary || {};
    var keys = Object.keys(g);
    if (keys.length) {
      var t = el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('thead', {}, [el('tr', {}, ['Field', 'Term', 'Means', 'Unit'].map(function (h) { return el('th', { text: h }); }))])]);
      var tb = el('tbody');
      keys.forEach(function (k) {
        var todo = /^TODO/.test(g[k].means || '');
        tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: k }), el('td', { text: g[k].term }), el('td', { class: todo ? 'prob' : '', text: g[k].means }), el('td', { text: g[k].unit || '' })]));
      });
      t.appendChild(tb);
      box.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
      box.appendChild(el('p', { class: 'text-muted-d', text: 'Rows that say TODO had no description in the schema. Add "description" to the field and generate again, or fill config/about.yaml in the pack.' }));
    }
    return box;
  }

  function samplesView(s) {
    var ul = el('ul', { class: 'pk-samples', 'aria-label': 'Samples of ' + s.name });
    s.samples.forEach(function (x) {
      ul.appendChild(el('li', {}, [el('code', { text: x.name + '.json' }), ' ', badge(x.source, x.source === 'synthetic' ? 'warn' : 'ok'),
        x.source === 'synthetic' ? el('span', { class: 'pk-hint text-muted-d', text: ' generated from the schema; says nothing about your data' }) : null]));
    });
    return ul;
  }

  /** Mounts the result of a finished job into `host`. h: {preview(sutra, sample) -> promise, open(sutra) -> promise}. */
  function mount(host, job, S, h) {
    U.clear(host);
    var sutras = job.sutras || [];
    if (!sutras.length) { host.appendChild(el('p', { class: 'text-muted-d', text: 'Nothing was drafted.' })); return; }
    var tot = { error: 0, empty: 0, ok: 0, problems: 0 };
    sutras.forEach(function (s) { tot.error += s.counts.error || 0; tot.empty += s.counts.empty || 0; tot.ok += s.counts.ok || 0; tot.problems += (s.problems || []).length ? 1 : 0; });
    var syn = sutras.filter(function (s) { return s.synthetic; }).length;
    host.appendChild(el('p', { class: 'pk-sum', role: 'status' }, [
      el('b', { text: U.plural(sutras.length, 'Sutra') + ' drafted in ' + job.seconds + ' s. ' }),
      tot.ok + ' panel-by-sample cells rendered, ' + tot.empty + ' empty, ' + tot.error + ' failed' + (tot.problems ? ', ' + U.plural(tot.problems, 'Sutra') + ' refused by the server' : '') + '. ',
      syn ? U.plural(syn, 'Sutra') + ' ' + (syn === 1 ? 'is' : 'are') + ' shown on synthetic samples only (generated from the schema).' : 'Every Sutra was tested on real documents.']));
    var warns = (S.plan && S.plan.warnings) || [];
    if (warns.length) {
      var d = el('details', { class: 'pk-warnbox' }, [el('summary', { text: U.plural(warns.length, 'warning') + ' from the plan' })]);
      var ul = el('ul', { class: 'pk-warns' });
      warns.forEach(function (w) { ul.appendChild(el('li', { text: w })); });
      d.appendChild(ul);
      host.appendChild(d);
    }
    var wrap = el('div', { class: 'pk-md' });
    var list = el('ul', { class: 'pk-list', 'aria-label': 'Sutras' });
    var detail = el('div', { class: 'pk-detail' });
    wrap.appendChild(list);
    wrap.appendChild(detail);
    host.appendChild(wrap);
    var cur = null, tab = 'preview';
    var buttons = {};

    function select(name, focus) {
      cur = sutras.filter(function (s) { return s.name === name; })[0];
      Object.keys(buttons).forEach(function (k) { buttons[k].setAttribute('aria-current', k === name ? 'true' : 'false'); });
      draw();
      if (focus) { buttons[name].focus(); }
    }

    sutras.forEach(function (s) {
      var b = el('button', { type: 'button', class: 'pk-item', 'aria-current': 'false', 'data-sutra': s.name }, [el('span', { class: 'mono pk-item-n', text: s.name }),
        el('span', { class: 'pk-item-b' }, counts(s).concat(s.synthetic ? [badge('synthetic', 'warn')] : []))]);
      b.addEventListener('click', function () { select(s.name); });
      b.addEventListener('keydown', function (e) {
        var i = sutras.indexOf(cur);
        if (e.key === 'ArrowDown' && i < sutras.length - 1) { e.preventDefault(); select(sutras[i + 1].name, true); }
        if (e.key === 'ArrowUp' && i > 0) { e.preventDefault(); select(sutras[i - 1].name, true); }
      });
      buttons[s.name] = b;
      list.appendChild(el('li', {}, [b]));
    });

    function draw() {
      U.clear(detail);
      var s = cur;
      detail.appendChild(el('div', { class: 'pk-detail-h' }, [el('h3', { class: 'pk-detail-t', text: s.name }),
        el('span', { class: 'text-muted-d', text: s.where ? 'matches ' + s.where : 'catch-all for ' + s.kind })]));
      var tabs = el('div', { class: 'pk-tabs', role: 'tablist', 'aria-label': 'Views of ' + s.name });
      TABS.forEach(function (t) {
        var b = el('button', { type: 'button', role: 'tab', id: 'pkt-' + t[0], 'aria-selected': t[0] === tab ? 'true' : 'false', 'aria-controls': 'pkp', tabindex: t[0] === tab ? '0' : '-1', class: 'pk-tab', text: t[1] });
        b.addEventListener('click', function () { tab = t[0]; draw(); document.getElementById('pkt-' + tab).focus(); });
        b.addEventListener('keydown', function (e) {
          var i = TABS.map(function (x) { return x[0]; }).indexOf(tab), n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : -1;
          if (n >= 0 && n < TABS.length) { e.preventDefault(); tab = TABS[n][0]; draw(); document.getElementById('pkt-' + tab).focus(); }
        });
        tabs.appendChild(b);
      });
      detail.appendChild(tabs);
      var panel = el('div', { id: 'pkp', role: 'tabpanel', 'aria-labelledby': 'pkt-' + tab, class: 'pk-panel' });
      detail.appendChild(panel);
      if (tab === 'yaml') {
        panel.appendChild(s.yaml ? el('pre', { class: 'bs-yaml', tabindex: '0', 'aria-label': 'Sutra text', text: s.yaml }) : el('p', { class: 'prob', text: (s.problems[0] && s.problems[0].message) || 'The server could not draft this Sutra.' }));
        if (s.problems && s.problems.length && s.yaml) {
          var ul = el('ul', { class: 'pk-warns', role: 'alert' });
          s.problems.forEach(function (p) { ul.appendChild(el('li', { text: (p.code ? p.code + ' ' : '') + (p.message || p.detail || '') + (p.location ? ' (line ' + p.location.line + ')' : '') })); });
          panel.appendChild(ul);
        }
      } else if (tab === 'about') { panel.appendChild(aboutView(s)); }
      else if (tab === 'samples') { panel.appendChild(samplesView(s)); }
      else if (tab === 'checks') { panel.appendChild(checksTable(s)); }
      else { drawPreview(panel, s); }
      var open = el('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Open in the workbench to refine' });
      var openMsg = el('span', { class: 'bs-status', role: 'status', 'aria-live': 'polite' });
      open.addEventListener('click', function () {
        open.disabled = true;
        h.open(s.name).then(function (r) {
          open.disabled = false;
          U.clear(openMsg);
          if (!r.ok) { U.say(openMsg, U.why(r), true); return; }
          openMsg.appendChild(document.createTextNode('Opened as a design. '));
          openMsg.appendChild(el('a', { class: 'lnk', href: r.body.url, target: '_blank', rel: 'noopener', text: 'Open the workbench' }));
        });
      });
      detail.appendChild(el('p', { class: 'bs-actions' }, [open, openMsg]));
    }

    function drawPreview(panel, s) {
      if (!s.yaml) { panel.appendChild(el('p', { class: 'prob', text: 'No preview: the server could not draft this Sutra.' })); return; }
      var sel = U.select(s.samples.map(function (x) { return { value: x.name, label: x.name + ' (' + x.source + ')' }; }), s.samples[0].name, { 'aria-label': 'Sample to show the Sutra on' });
      var box = el('div', { class: 'bs-preview pk-pv', 'aria-live': 'polite' });
      panel.appendChild(el('div', { class: 'bs-bar' }, [el('label', { text: 'Rendered on sample', for: 'pkSel' }), sel]));
      sel.id = 'pkSel';
      panel.appendChild(box);
      function load() {
        U.say(box, 'Rendering…');
        h.preview(s.name, sel.value).then(function (r) {
          U.clear(box);
          if (!r.ok) { box.appendChild(el('p', { class: 'prob', text: U.why(r) })); return; }
          box.innerHTML = r.body.previewHtml || '';        // the server's own rendering (escaped by its template), as the workbench draws it
          if (r.body.source === 'synthetic') { box.insertBefore(el('p', { class: 'pk-synth', text: 'Synthetic sample: generated from the schema, not your data.' }), box.firstChild); }
        });
      }
      sel.addEventListener('change', load);
      load();
    }
    select(sutras[0].name);
  }

  window.DrishtiPackPreview = { mount: mount };
})();
