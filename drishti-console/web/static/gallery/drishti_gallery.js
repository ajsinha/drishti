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
/* The gallery (ELEMENTS.md section 10, mock-up g4): the live documentation of every component, an Elements app drawn with drishti-core.
   Two modes of one component, A the default (O6):
     mode 'playground' (g4 A): three panes: the searchable list of libraries and components with version and badge; the live component
       with its code tabs (web component, JavaScript, Sutra, AG Grid adapter) written from the object it was given, and the status line
       (size, tests, accessibility); the options generated from the typed JSDoc, the event log;
     mode 'docs' (g4 B): a documentation page per component: runnable examples with "Open in playground", the API generated from the
       JSDoc, the accessibility contract (the keys and the ARIA smoke check) and the library's changelog.
   Everything it says about a library comes from the catalogue (drishti_catalogue.js, generated with ELEMENTS_API.md); the demos
   (drishti_gallery-demos*.js) run on the sample data and stand-in server of drishti_gallery-data.js, so the page needs no server and opens
   from the file system too (the maps then cannot read their shapes: a browser refuses file:// reads). The address keeps the state:
   #mode=docs&c=pivot&tab=api&o={"layout":"terminal"}. window.__gallery is the instance (the browser test drives it). */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const MODES = ['playground', 'docs'];
  const DOC_TABS = [['examples', 'Examples'], ['api', 'API'], ['a11y', 'Accessibility'], ['changelog', 'Changelog'], ['page', 'Playground page']];
  const BADGES = { none: ['ok', 'no server needed'], server: ['srv', 'needs a Drishti server'], licence: ['lic', 'banking module licence'] };
  const kebab = (n) => 'drishti:' + n.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());

  class Gallery {
    /**
     * @param {HTMLElement} el where the gallery lives
     * @param {{mode: string, catalogue: Object, demos: Object[], elements: Object, data: Object, code: Object, mapsBase: string}} o
     */
    constructor(el, o) {
      this.el = el;
      this.doc = el.ownerDocument;
      this.o = o;
      this.E = o.elements;
      this.h = this.E.core.h;
      this.cat = o.catalogue;
      this.code = o.code;
      const order = (d) => this.cat.libraries.findIndex((l) => l.name === d.library);
      this.demos = o.demos.filter((d) => this.lib(d.library)).map((d, i) => [d, i]).sort((a, b) => order(a[0]) - order(b[0]) || a[1] - b[1]).map((x) => x[0]);
      this.values = {};
      this.mounted = [];
      this.events = [];
      this.downloads = [];
      this.memoStore = new Map();
      this.audits = {};
      const s = this.readAddress();
      this.mode = MODES.indexOf(s.mode) >= 0 ? s.mode : (MODES.indexOf(o.mode) >= 0 ? o.mode : 'playground');
      this.id = this.demos.some((d) => d.id === s.c) || this.planned(s.c) ? s.c : (this.demo(o.start || 'grid') || this.demos[0]).id;
      this.codeTab = 'element';
      this.docTab = DOC_TABS.some((t) => t[0] === s.tab) ? s.tab : 'examples';
      this.q = '';
      if (s.o && this.demo()) { this.values[this.id] = Object.assign(this.initial(this.demo()), s.o); }
      this.ctx = this.makeContext();
      this.build();
      this.render();
    }

    /* ---- lookups ---- */
    lib(name) { return this.cat.libraries.find((l) => l.name === name) || null; }
    planned(name) { return this.cat.planned.find((p) => p.name === name) || null; }
    demo(id) { return this.demos.find((d) => d.id === (id || this.id)) || null; }
    module(file) { for (const l of this.cat.libraries) { const m = l.modules.find((x) => x.file === file); if (m) { return { lib: l, module: m }; } } return null; }
    constant(name) { for (const l of this.cat.libraries) { if (l.constants[name]) { return l.constants[name]; } } return []; }
    options(d) { return this.code.optionsOf(this.cat, d.typesFrom || d.library, d.types || []); }
    eventNames(d) { return [].concat(...(d.events || []).map((c) => this.constant(c)), d.domEvents || []); }

    initial(d) {
      const v = {};
      (d.extra || []).forEach((x) => { const vals = this.extraValues(x); v[x.name] = vals.length ? vals[0] : ''; });
      return Object.assign(v, d.values || {});
    }
    extraValues(x) { return typeof x.control.values === 'function' ? x.control.values(this.ctx) : x.control.values; }
    valuesOf(d) { if (!this.values[d.id]) { this.values[d.id] = this.initial(d); } return this.values[d.id]; }

    makeContext() {
      const self = this;
      return {
        E: this.E, data: this.o.data, code: this.code, h: this.h, mapsBase: this.o.mapsBase, downloads: this.downloads,
        getRowId: (r) => r.ID,
        download: (name, content) => { self.downloads.push({ name: name, size: content && (content.size || content.length) }); self.log('download', { name: name }); },
        memo: (key, make) => { if (!self.memoStore.has(key)) { self.memoStore.set(key, make()); } return self.memoStore.get(key); },
        log: (name, detail) => self.log(name, detail)
      };
    }

    readAddress() {
      const out = {};
      const take = (s) => new URLSearchParams(s).forEach((v, k) => { out[k] = v; });
      try { take(this.doc.defaultView.location.search.slice(1)); take(this.doc.defaultView.location.hash.slice(1)); } catch (e) { /* no address */ }
      if (out.o) { try { out.o = JSON.parse(out.o); } catch (e) { out.o = null; } }
      return out;
    }

    writeAddress() {
      const w = this.doc.defaultView, d = this.demo();
      const p = new URLSearchParams();
      p.set('mode', this.mode);
      p.set('c', this.id);
      if (this.mode === 'docs') { p.set('tab', this.docTab); }
      if (d) {
        const init = this.initial(d), v = this.valuesOf(d), diff = {};
        Object.keys(v).forEach((k) => { if (v[k] !== init[k]) { diff[k] = v[k]; } });
        if (Object.keys(diff).length) { p.set('o', JSON.stringify(diff)); }
      }
      try { w.history.replaceState(null, '', '#' + p.toString()); } catch (e) { /* a file:// page in some browsers */ }
    }

    /* ---- the frame ---- */
    build() {
      const doc = this.doc, h = this.h;
      this.el.textContent = '';
      this.el.classList.add('gx');
      this.modeBtns = MODES.map((m) => h(doc, 'button', { cls: 'gx-seg-btn', text: m === 'playground' ? 'Playground' : 'Documentation', attrs: { type: 'button', 'data-mode': m },
        on: { click: () => this.setMode(m) } }));
      this.themeBtn = h(doc, 'button', { cls: 'gx-btn', text: 'Light / dark', attrs: { type: 'button', 'data-action': 'theme' }, on: { click: () => this.toggleTheme() } });
      const top = h(doc, 'header', { cls: 'gx-top' }, [h(doc, 'span', { cls: 'gx-brand', text: 'DRISHTI' }), h(doc, 'span', { cls: 'gx-top-title', text: 'Elements · the gallery' }),
        h(doc, 'span', { cls: 'gx-train', text: 'release train ' + this.cat.elements }),
        h(doc, 'span', { cls: 'gx-seg', attrs: { role: 'group', 'aria-label': 'Mode' } }, this.modeBtns), h(doc, 'span', { cls: 'gx-sp' }), this.themeBtn]);
      this.search = h(doc, 'input', { cls: 'gx-search', attrs: { type: 'search', placeholder: 'Search components…', 'aria-label': 'Search components' },
        on: { input: (e) => { this.q = e.target.value.trim().toLowerCase(); this.renderNav(); } } });
      this.navList = h(doc, 'div', { cls: 'gx-nav-list' });
      this.nav = h(doc, 'nav', { cls: 'gx-nav', attrs: { 'aria-label': 'Libraries and components' } }, [this.search, this.navList]);
      this.main = h(doc, 'main', { cls: 'gx-main', attrs: { tabindex: '-1' } });
      this.side = h(doc, 'aside', { cls: 'gx-side', attrs: { 'aria-label': 'Options and events' } });
      this.body = h(doc, 'div', { cls: 'gx-body' }, [this.nav, this.main, this.side]);
      this.el.appendChild(top);
      this.el.appendChild(this.body);
    }

    render() {
      this.unmount();
      this.el.setAttribute('data-mode', this.mode);
      this.modeBtns.forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === this.mode)));
      this.renderNav();
      const p = this.planned(this.id);
      if (p) { this.renderPlanned(p); } else if (this.mode === 'playground') { this.renderPlayground(); } else { this.renderDocs(); }
      this.writeAddress();
    }

    setMode(mode) { if (MODES.indexOf(mode) >= 0 && mode !== this.mode) { this.mode = mode; this.render(); } }
    select(id) { if (id !== this.id) { this.id = id; this.events = []; this.render(); } }
    toggleTheme() { const C = this.E.core; C.setTheme(C.scheme(C.currentTheme(this.doc)) === 'light' ? 'terminal' : 'light', { persist: false }); }

    /** Sets an option (or an example control) of the current component; the component and its code follow. */
    setOption(name, value) {
      const d = this.demo();
      this.valuesOf(d)[name] = value;
      this.log('option', { [name]: value });
      if (this.mode === 'playground') { this.unmount(); this.renderPlayground(); } else { this.render(); }
      this.writeAddress();
    }

    renderNav() {
      const doc = this.doc, h = this.h, q = this.q;
      this.navList.textContent = '';
      const hit = (t) => !q || t.toLowerCase().indexOf(q) >= 0;
      this.cat.libraries.forEach((l) => {
        const items = this.demos.filter((d) => d.library === l.name && (hit(d.title) || hit(l.name) || hit(d.summary || '')));
        if (!items.length) { return; }
        this.navList.appendChild(h(doc, 'section', { cls: 'gx-nav-lib', attrs: { 'aria-label': l.name } }, [
          h(doc, 'h2', { cls: 'gx-nav-head' }, [h(doc, 'span', { text: l.name }), h(doc, 'span', { cls: 'gx-ver', text: l.version }), this.badge(l.server)]),
          h(doc, 'ul', {}, items.map((d) => h(doc, 'li', {}, [h(doc, 'button', { cls: 'gx-nav-item', text: d.title, attrs: { type: 'button', 'data-c': d.id, 'aria-current': d.id === this.id ? 'page' : null },
            on: { click: () => this.select(d.id) } }, [h(doc, 'span', { cls: 'gx-kind', text: d.kind === 'element' ? '</>' : 'fn', attrs: { 'aria-hidden': 'true' } })])])))]));
      });
      const planned = this.cat.planned.filter((p) => hit(p.name) || hit(p.what));
      if (planned.length) {
        this.navList.appendChild(h(doc, 'section', { cls: 'gx-nav-lib gx-nav-planned', attrs: { 'aria-label': 'Not built yet' } }, [h(doc, 'h2', { cls: 'gx-nav-head', text: 'Not built yet' }),
          h(doc, 'ul', {}, planned.map((p) => h(doc, 'li', {}, [h(doc, 'button', { cls: 'gx-nav-item', text: p.name, attrs: { type: 'button', 'data-c': p.name, 'aria-current': p.name === this.id ? 'page' : null },
            on: { click: () => this.select(p.name) } }, [this.badge(p.server)])])))]));
      }
      if (!this.navList.children.length) { this.navList.appendChild(h(doc, 'p', { cls: 'gx-note', text: 'Nothing matches "' + q + '".' })); }
    }

    badge(server) { const b = BADGES[server] || BADGES.none; return this.h(this.doc, 'span', { cls: 'gx-badge gx-badge-' + b[0], text: b[1] }); }

    hero(d) {
      const doc = this.doc, h = this.h, l = this.lib(d.library), m = d.about ? this.module(d.about) : null;
      return h(doc, 'div', { cls: 'gx-hero' }, [
        h(doc, 'h1', { cls: 'gx-title' }, [d.title, h(doc, 'span', { cls: 'gx-hero-lib', text: l.name + ' ' + l.version }), this.badge(l.server)]),
        h(doc, 'p', { cls: 'gx-summary', text: d.summary || '' }),
        m && m.module.about ? h(doc, 'details', { cls: 'gx-about' }, [h(doc, 'summary', { text: 'From ' + m.module.file }), this.prose(m.module.about)]) : null]);
    }

    prose(text) {
      const doc = this.doc;
      const p = this.h(doc, 'p', { cls: 'gx-prose' });
      this.code.codeSpans(text).forEach((s) => p.appendChild(s.code ? this.h(doc, 'code', { text: s.text }) : doc.createTextNode(s.text)));
      return p;
    }

    /* ---- A: the playground ---- */
    renderPlayground() {
      const doc = this.doc, h = this.h, d = this.demo(), v = this.valuesOf(d);
      this.main.textContent = '';
      this.side.textContent = '';
      this.side.hidden = false;
      this.stage = h(doc, 'div', { cls: 'gx-stage', attrs: { 'data-demo': d.id, role: 'region', 'aria-label': d.title + ', live' } });
      this.status = h(doc, 'div', { cls: 'gx-status', attrs: { role: 'status' } });
      this.main.appendChild(this.hero(d));
      this.main.appendChild(this.stage);
      this.main.appendChild(this.codeBlock(d, v));
      this.main.appendChild(this.status);
      const m = this.mountDemo(d, v, this.stage);
      this.renderStatus(d, m);
      this.side.appendChild(this.optionsPanel(d, v));
      this.evlog = h(doc, 'ol', { cls: 'gx-evlog', attrs: { 'aria-label': 'Events', 'aria-live': 'off' } });
      this.side.appendChild(h(doc, 'section', { cls: 'gx-panel' }, [h(doc, 'h2', { text: 'Events' }), this.evlog]));
      this.renderLog();
      this.side.appendChild(this.docsLinks(d));
    }

    mountDemo(d, values, host) {
      const t0 = Date.now();
      let m;
      try {
        m = d.mount(host, values, this.ctx);
      } catch (e) {
        host.appendChild(this.h(this.doc, 'p', { cls: 'gx-error', text: d.title + ' could not be drawn: ' + (e && e.message ? e.message : e) }));
        m = { target: host, element: host, ready: Promise.reject(e), destroy: () => {} };
        m.ready.catch(() => {});
      }
      m.ms = Date.now() - t0;
      const offs = [];
      const names = this.eventNames(d);
      if (m.target && names.length) {
        names.forEach((n) => { const fn = (e) => { if (n !== 'rendered') { this.log(n, e.detail); } }; m.target.addEventListener(kebab(n), fn); offs.push(() => m.target.removeEventListener(kebab(n), fn)); });
      }
      m.off = () => offs.forEach((f) => f());
      this.mounted.push(m);
      return m;
    }

    unmount() { this.mounted.forEach((m) => { try { m.off(); m.destroy(); } catch (e) { /* already gone */ } }); this.mounted = []; }

    renderStatus(d, m) {
      const doc = this.doc, h = this.h, l = this.lib(d.library), t = l.tests;
      const audit = h(doc, 'span', { cls: 'gx-a11y', text: 'accessibility: checking…' });
      this.status.textContent = '';
      [h(doc, 'b', { text: l.name + ' ' + l.version }), h(doc, 'span', { text: 'bundle ' + this.code.sizeText(l.bundle.bytes) + ', ' + l.bundle.lines.toLocaleString('en-US') + ' lines; ES modules, no build step' }),
        h(doc, 'span', { text: 'tests: ' + t.node + ' in Node' + (t.browser.length ? ', ' + t.browser.join(', ') : '') + ', this gallery\'s browser file' }), audit,
        h(doc, 'span', { cls: 'gx-ms', text: 'drawn in ' + m.ms + ' ms' })].forEach((x) => this.status.appendChild(x));
      this.checkWhenReady(d, m, (r) => { audit.textContent = 'accessibility: ' + (r.problems.length ? r.problems.length + ' problems' : 'no problems') + ' (keyboard/ARIA smoke check of ' + r.checked + ' elements, ' + r.focusable + ' Tab stops)'; audit.classList.toggle('gx-bad', r.problems.length > 0); });
    }

    checkWhenReady(d, m, done) {
      const w = this.doc.defaultView;
      Promise.resolve(m.ready).catch(() => {}).then(() => new Promise((r) => w.requestAnimationFrame(() => w.requestAnimationFrame(r)))).then(() => {
        if (!m.target || !m.target.isConnected) { return; }
        const r = this.code.audit(m.target);
        this.audits[d.id] = r;
        done(r);
      });
    }

    codeTabs(d) {
      const tabs = [['element', 'Web component'], ['js', 'JavaScript'], ['sutra', 'Sutra']];
      if (d.ag) { tabs.push(['ag', this.cat.agGrid.built ? 'AG Grid adapter' : 'AG Grid adapter (planned)']); }
      return tabs;
    }

    snippets(d, v) {
      let c;
      if (d.code) { c = d.code(v, this.ctx); } else { c = this.code.elementCode(d.build(v, this.ctx)); }
      const out = { element: c.element || '// a function of ' + d.library + ': see the JavaScript tab', js: c.js || '' };
      const s = d.sutra ? d.sutra(v, this.ctx) : null;
      out.sutra = s || '# No Sutra panel kind draws this yet: use the web component or the JavaScript in a page.';
      if (d.ag) { const b = d.build(v, this.ctx); out.ag = this.code.agOptions(Object.assign({}, b.options, b.jsOptions), b.names, d.agExtra ? d.agExtra(v) : null); }
      return out;
    }

    codeBlock(d, v) {
      const doc = this.doc, h = this.h, tabs = this.codeTabs(d), code = this.snippets(d, v);
      if (!tabs.some((t) => t[0] === this.codeTab)) { this.codeTab = 'element'; }
      const pre = h(doc, 'pre', { cls: 'gx-code', attrs: { tabindex: '0', role: 'tabpanel', id: 'gx-code-panel', 'aria-label': 'Code' } });
      const extra = h(doc, 'div', { cls: 'gx-code-extra' });
      const show = () => {
        pre.textContent = code[this.codeTab];
        extra.textContent = '';
        if (this.codeTab === 'ag') { extra.appendChild(this.agTable()); }
        list.querySelectorAll('[role="tab"]').forEach((b) => { const on = b.dataset.tab === this.codeTab; b.setAttribute('aria-selected', String(on)); b.setAttribute('tabindex', on ? '0' : '-1'); });
      };
      const list = h(doc, 'div', { cls: 'gx-tabs', attrs: { role: 'tablist', 'aria-label': 'Code' } }, tabs.map((t) => h(doc, 'button', { cls: 'gx-tab', text: t[1],
        attrs: { type: 'button', role: 'tab', 'data-tab': t[0], 'aria-controls': 'gx-code-panel' }, on: { click: () => { this.codeTab = t[0]; show(); } } })));
      this.E.core.rovingTabindex(list, { selector: '[role="tab"]', onMove: (b) => { this.codeTab = b.dataset.tab; show(); } });
      const copy = h(doc, 'button', { cls: 'gx-btn gx-copy', text: 'Copy', attrs: { type: 'button', 'data-action': 'copy' }, on: { click: () => this.copy(code[this.codeTab]) } });
      const box = h(doc, 'section', { cls: 'gx-codebox', attrs: { 'aria-label': 'Code' } }, [h(doc, 'div', { cls: 'gx-codehead' }, [list, copy]), pre, extra]);
      show();
      return box;
    }

    agTable() {
      const doc = this.doc, h = this.h, ag = this.cat.agGrid;
      return h(doc, 'details', { cls: 'gx-ag', attrs: { open: true } }, [h(doc, 'summary', { text: (ag.built ? 'The adapter\'s mapping (drishti_grid-ag-map.js; the migration guide: docs/guides/EMBED_GUIDE.md)' : 'Planned: the adapter is not built yet. Its mapping (ELEMENTS.md section 6)') + ', version 1 scope: ' + ag.scope }),
        h(doc, 'table', { cls: 'gx-table' }, [h(doc, 'thead', {}, [h(doc, 'tr', {}, [h(doc, 'th', { text: 'AG Grid Enterprise option (public API shape)', attrs: { scope: 'col' } }), h(doc, 'th', { text: 'State', attrs: { scope: 'col' } }), h(doc, 'th', { text: 'drishti-grid', attrs: { scope: 'col' } })])]),
          h(doc, 'tbody', {}, ag.mapping.map((r) => h(doc, 'tr', {}, [h(doc, 'td', {}, [this.prose(r.ag)]), h(doc, 'td', { text: r.state || '' }), h(doc, 'td', {}, [this.prose(r.drishti)])])))]),
        ag.notCovered ? h(doc, 'p', { cls: 'gx-note' }, ['Not covered in version 1 (named in the console when used): ', this.prose(ag.notCovered)]) : null]);
    }

    copy(text) {
      const w = this.doc.defaultView;
      const done = () => { this.E.core.toast({ text: 'Copied', timeout: 1500 }); this.log('copied', { chars: text.length }); };
      try { w.navigator.clipboard.writeText(text).then(done, done); } catch (e) { done(); }
    }

    optionsPanel(d, v) {
      const doc = this.doc, h = this.h, rows = [];
      const control = (name, ctl, desc, typeText, def) => {
        const id = 'gx-opt-' + d.id + '-' + name;
        let input = null;
        const cur = v[name];
        if (ctl && ctl.kind === 'select') {
          const vals = typeof ctl.values === 'function' ? ctl.values(this.ctx) : ctl.values;
          const unset = cur === undefined || cur === '' ? [{ value: '', label: def !== undefined && def !== null ? '(default ' + def + ')' : '(not set)' }] : [];   // drishti-inputs' Select
          input = this.E.inputs.selectElement(doc, { label: name, options: unset.concat(vals.map((x) => ({ value: String(x), label: String(x) }))),
            value: unset.length ? '' : String(cur) }, { id: id, 'data-opt': name });
          input.component.on('change', (e) => this.setOption(name, e.value));
        } else if (ctl && ctl.kind === 'switch') {
          const on = cur === undefined ? this.code.defaultValue(def) === true : !!cur;
          input = h(doc, 'input', { attrs: { id: id, type: 'checkbox', 'data-opt': name, checked: on ? true : null }, on: { change: (e) => this.setOption(name, e.target.checked) } });
        } else if (ctl && (ctl.kind === 'number' || ctl.kind === 'text')) {
          input = h(doc, 'input', { attrs: { id: id, type: ctl.kind === 'number' ? 'number' : 'text', 'data-opt': name, value: cur === undefined ? '' : String(cur), placeholder: def || '' },
            on: { change: (e) => this.setOption(name, ctl.kind === 'number' ? (e.target.value === '' ? undefined : +e.target.value) : e.target.value) } });
        }
        return h(doc, 'div', { cls: 'gx-opt' + (input ? '' : ' gx-opt-code') }, [
          h(doc, 'div', { cls: 'gx-opt-head' }, [input ? h(doc, 'label', { text: name, attrs: { for: id } }) : h(doc, 'span', { cls: 'gx-opt-name', text: name }), input]),
          h(doc, 'div', { cls: 'gx-opt-type', text: typeText + (def !== undefined && def !== null ? ' = ' + def : '') }),
          desc ? h(doc, 'div', { cls: 'gx-opt-desc', text: desc }) : null]);
      };
      (d.extra || []).forEach((x) => rows.push(control(x.name, x.control, x.desc, 'example', undefined)));
      const opts = this.options(d);
      const typedRows = opts.map((p) => control(p.name, p.control, p.desc, p.type + ' · ' + p.from, p.default));
      return h(doc, 'section', { cls: 'gx-panel gx-options' }, [h(doc, 'h2', { text: 'Options' }),
        rows.length ? h(doc, 'h3', { cls: 'gx-sub', text: 'The example' }) : null].concat(rows,
        [opts.length ? h(doc, 'h3', { cls: 'gx-sub', text: 'Options, from the JSDoc of ' + Array.from(new Set(opts.map((p) => p.from))).join(' and ') }) : null], typedRows,
        [opts.length ? h(doc, 'p', { cls: 'gx-note', text: 'Options with no control (objects, functions) are set in code: they are in the code above.' }) : null]));
    }

    docsLinks(d) {
      const doc = this.doc, h = this.h;
      const file = (text, path) => h(doc, 'li', {}, [text + ' ', h(doc, 'code', { text: path })]);
      return h(doc, 'section', { cls: 'gx-panel' }, [h(doc, 'h2', { text: 'Docs' }), h(doc, 'ul', { cls: 'gx-links' }, [
        h(doc, 'li', {}, [h(doc, 'button', { cls: 'gx-linkbtn', text: 'This component\'s documentation page', attrs: { type: 'button', 'data-action': 'to-docs' }, on: { click: () => this.setMode('docs') } })]),
        file('API reference:', 'docs/guides/ELEMENTS_API.md'), file('Design:', 'docs/architecture/ELEMENTS.md')])]);
    }

    log(name, detail) {
      let text = '';
      try { text = detail === undefined ? '' : typeof detail === 'string' ? detail : JSON.stringify(detail, (k, x) => (x && x.nodeType ? '<' + x.tagName + '>' : Array.isArray(x) && x.length > 6 ? x.slice(0, 6).concat(['… ' + x.length]) : x)); } catch (e) { text = String(detail); }
      this.events.unshift({ name: name, text: (text || '').slice(0, 160), at: new Date().toISOString().slice(11, 19) });
      this.events.length = Math.min(this.events.length, 60);
      if (this.evlog) { this.renderLog(); }
    }

    renderLog() {
      const doc = this.doc, h = this.h;
      this.evlog.textContent = '';
      if (!this.events.length) { this.evlog.appendChild(h(doc, 'li', { cls: 'gx-note', text: 'Use the component: what it emits appears here.' })); return; }
      this.events.slice(0, 30).forEach((e) => this.evlog.appendChild(h(doc, 'li', {}, [h(doc, 'span', { cls: 'gx-at', text: e.at }), h(doc, 'b', { text: e.name }), h(doc, 'span', { cls: 'gx-detail', text: e.text })])));
    }

    /* ---- B: the documentation page ---- */
    renderDocs() {
      const doc = this.doc, h = this.h, d = this.demo();
      this.main.textContent = '';
      this.side.textContent = '';
      this.side.hidden = true;
      this.evlog = null;
      this.main.appendChild(this.hero(d));
      const panel = h(doc, 'div', { cls: 'gx-docpanel', attrs: { role: 'tabpanel', id: 'gx-doc-panel', tabindex: '0' } });
      const list = h(doc, 'div', { cls: 'gx-tabs gx-doctabs', attrs: { role: 'tablist', 'aria-label': 'Documentation' } }, DOC_TABS.map((t) => h(doc, 'button', { cls: 'gx-tab', text: t[1],
        attrs: { type: 'button', role: 'tab', 'data-tab': t[0], 'aria-selected': String(t[0] === this.docTab), tabindex: t[0] === this.docTab ? '0' : '-1', 'aria-controls': 'gx-doc-panel' },
        on: { click: () => { if (this.docTab !== t[0]) { this.docTab = t[0]; this.render(); } } } })));
      this.main.appendChild(list);
      this.main.appendChild(panel);
      if (this.docTab === 'examples') { this.renderExamples(d, panel); } else if (this.docTab === 'page') { GAL.renderPage(this, d, panel); } else if (this.docTab === 'api') { this.renderApi(d, panel); } else if (this.docTab === 'a11y') { this.renderA11y(d, panel); } else { this.renderChangelog(d, panel); }
    }

    renderExamples(d, panel) {
      const doc = this.doc, h = this.h, l = this.lib(d.library);
      (d.examples || [{ title: d.title, values: {} }]).forEach((ex, i) => {
        const v = Object.assign(this.initial(d), ex.values);
        const stage = h(doc, 'div', { cls: 'gx-stage gx-stage-ex', attrs: { 'data-demo': d.id, 'data-example': String(i) } });
        const card = h(doc, 'article', { cls: 'gx-example', attrs: { 'aria-label': ex.title } }, [h(doc, 'header', { cls: 'gx-ex-head' }, [h(doc, 'h2', { text: ex.title }), h(doc, 'span', { cls: 'gx-sp' }),
          h(doc, 'button', { cls: 'gx-btn', text: 'Open in playground', attrs: { type: 'button', 'data-action': 'open-playground' }, on: { click: () => { this.values[d.id] = v; this.mode = 'playground'; this.render(); } } })]), stage,
          h(doc, 'pre', { cls: 'gx-code gx-code-ex', text: this.snippets(d, v).js, attrs: { tabindex: '0', 'aria-label': ex.title + ', JavaScript' } }),
          h(doc, 'footer', { cls: 'gx-ex-foot', text: 'runs in the browser · ' + BADGES[l.server][1] })]);
        panel.appendChild(card);
        this.mountDemo(d, v, stage);
      });
    }

    typeTable(t) {
      const doc = this.doc, h = this.h;
      return h(doc, 'table', { cls: 'gx-table gx-api' }, [h(doc, 'caption', { text: t.name + (t.desc ? ': ' + t.desc : '') + ' (' + t.module + ')' }),
        h(doc, 'thead', {}, [h(doc, 'tr', {}, ['Option', 'Type', 'Default', 'What it does'].map((x) => h(doc, 'th', { text: x, attrs: { scope: 'col' } })))]),
        h(doc, 'tbody', {}, t.props.map((p) => h(doc, 'tr', {}, [h(doc, 'td', {}, [h(doc, 'code', { text: p.name })]), h(doc, 'td', {}, [h(doc, 'code', { text: p.type })]),
          h(doc, 'td', { text: p.default === null || p.default === undefined ? (p.optional ? '' : 'required') : p.default }), h(doc, 'td', { text: p.desc })])))]);
    }

    renderApi(d, panel) {
      const doc = this.doc, h = this.h, l = this.lib(d.typesFrom || d.library);
      (d.types || []).forEach((tn) => { const t = l.types.find((x) => x.name === tn); if (t) { panel.appendChild(this.typeTable(t)); } });
      const names = this.eventNames(d);
      if (names.length) {
        panel.appendChild(h(doc, 'h2', { text: 'Events' }));
        panel.appendChild(h(doc, 'p', { cls: 'gx-note', text: 'On the component\'s event bus by name, and as DOM events on its element (they bubble).' }));
        panel.appendChild(h(doc, 'table', { cls: 'gx-table gx-api' }, [h(doc, 'thead', {}, [h(doc, 'tr', {}, ['Event', 'DOM event'].map((x) => h(doc, 'th', { text: x, attrs: { scope: 'col' } })))]),
          h(doc, 'tbody', {}, names.map((n) => h(doc, 'tr', {}, [h(doc, 'td', {}, [h(doc, 'code', { text: n })]), h(doc, 'td', {}, [h(doc, 'code', { text: kebab(n) })])])))]));
      }
      const own = this.lib(d.library), mod = d.about;
      const syms = own.symbols.filter((s) => s.module === mod);
      if (syms.length) {
        panel.appendChild(h(doc, 'h2', { text: 'Functions and classes of ' + mod }));
        syms.forEach((s) => panel.appendChild(this.symbolBlock(s)));
      }
      panel.appendChild(h(doc, 'p', { cls: 'gx-note', text: 'Generated by tools/docs/elements_api.py from the typed JSDoc of ' + own.name + ' ' + own.version + ', the same source as ELEMENTS_API.md (--check fails the build when a public symbol has no typed comment).' }));
    }

    symbolBlock(s) {
      const doc = this.doc, h = this.h;
      const sig = (n, args) => n + '(' + (args || []).join(', ') + ')';
      const fn = (title, x) => h(doc, 'div', { cls: 'gx-sym' }, [h(doc, 'h3', {}, [h(doc, 'code', { text: title })]), x.desc ? this.prose(x.desc) : null,
        x.params && x.params.length ? h(doc, 'ul', { cls: 'gx-params' }, x.params.map((p) => h(doc, 'li', {}, [h(doc, 'code', { text: p.name }), ' ', h(doc, 'span', { cls: 'gx-type', text: p.type }), p.desc ? ' ' + p.desc : '']))) : null,
        x.returns ? h(doc, 'p', { cls: 'gx-returns' }, ['Returns ', h(doc, 'code', { text: x.returns.type }), x.returns.desc ? ': ' + x.returns.desc : '']) : null]);
      if (s.kind === 'class') {
        return h(doc, 'div', { cls: 'gx-class' }, [fn('class ' + s.name, s)].concat(s.methods.map((m) => fn((m.name === 'constructor' ? 'new ' + s.name : (m.static ? 'static ' : '') + s.name + '.' + m.name) + '(' + m.args.join(', ') + ')', m))));
      }
      return fn(s.kind === 'function' ? sig(s.name, s.args) : s.name + (s.type ? ' : ' + s.type : ''), s);
    }

    renderA11y(d, panel) {
      const doc = this.doc, h = this.h;
      panel.appendChild(h(doc, 'h2', { text: 'The keyboard' }));
      (d.keys || []).forEach((file) => { const m = this.module(file); if (m) { panel.appendChild(h(doc, 'pre', { cls: 'gx-keys', text: m.module.about, attrs: { 'aria-label': 'Keys, from ' + file } })); } });
      if (d.functionKeys && this.E.grid && this.E.grid.FUNCTION_KEYS) {
        panel.appendChild(h(doc, 'table', { cls: 'gx-table' }, [h(doc, 'caption', { text: 'Function keys (the terminal layout\'s bar; FUNCTION_KEYS)' }),
          h(doc, 'thead', {}, [h(doc, 'tr', {}, ['Key', 'Does'].map((x) => h(doc, 'th', { text: x, attrs: { scope: 'col' } })))]),
          h(doc, 'tbody', {}, this.E.grid.FUNCTION_KEYS.map((k) => h(doc, 'tr', {}, [h(doc, 'td', {}, [h(doc, 'kbd', { text: k.key })]), h(doc, 'td', { text: k.label })])))]));
      }
      if (!(d.keys || []).length) { panel.appendChild(h(doc, 'p', { text: 'A function: no keys of its own. Every control it draws is a native button or input.' })); }
      panel.appendChild(h(doc, 'h2', { text: 'Roles, names and the smoke check' }));
      const out = h(doc, 'div', { cls: 'gx-audit', attrs: { 'aria-live': 'polite' }, text: 'Checking a live example…' });
      panel.appendChild(out);
      const stage = h(doc, 'div', { cls: 'gx-stage gx-stage-ex', attrs: { 'data-demo': d.id } });
      panel.appendChild(stage);
      const m = this.mountDemo(d, this.initial(d), stage);
      this.checkWhenReady(d, m, (r) => {
        out.textContent = '';
        out.appendChild(h(doc, 'p', { text: (r.problems.length ? r.problems.length + ' problems' : 'No problems') + ' in ' + r.checked + ' elements; ' + r.focusable + ' Tab stops. Roles: ' + Object.keys(r.roles).sort().map((k) => k + ' ' + r.roles[k]).join(', ') + '.' }));
        if (r.problems.length) { out.appendChild(h(doc, 'ul', {}, r.problems.map((p) => h(doc, 'li', { text: p.rule + ': ' + p.what })))); }
        out.appendChild(h(doc, 'p', { cls: 'gx-note', text: 'No axe is vendored: the gallery checks names, roles, ARIA attributes and references, ids, the Tab order and a grid\'s rows on every demo; the browser test adds the keys. Contrast is the theme\'s (Theme tokens).' }));
      });
    }

    renderChangelog(d, panel) {
      const doc = this.doc, h = this.h, l = this.lib(d.library);
      if (!l.changelog.length) { panel.appendChild(h(doc, 'p', { text: 'No entries yet.' })); return; }
      panel.appendChild(h(doc, 'table', { cls: 'gx-table gx-changelog' }, [h(doc, 'thead', {}, [h(doc, 'tr', {}, ['Version', 'Release', 'Changes'].map((x) => h(doc, 'th', { text: x, attrs: { scope: 'col' } })))]),
        h(doc, 'tbody', {}, l.changelog.map((c) => h(doc, 'tr', {}, [h(doc, 'td', { text: c.version || '' }), h(doc, 'td', { text: c.release }), h(doc, 'td', {}, [this.prose(c.text)])])))]));
      panel.appendChild(h(doc, 'p', { cls: 'gx-note', text: 'From the product CHANGELOG (one home): semantic versions per library; a released version never changes; a breaking change only in a new major.' }));
    }

    renderPlanned(p) {
      const doc = this.doc, h = this.h;
      this.main.textContent = '';
      this.side.textContent = '';
      this.side.hidden = true;
      this.main.appendChild(h(doc, 'div', { cls: 'gx-hero' }, [h(doc, 'h1', { cls: 'gx-title' }, [p.name, h(doc, 'span', { cls: 'gx-hero-lib', text: 'library ' + p.number + ' of 8' }), this.badge(p.server)]),
        this.prose(p.what), h(doc, 'p', { cls: 'gx-note', text: 'Not in this build. Server: ' + p.serverText + '. Its pages appear here when its folder is in the manifest (drishti_elements.json): the gallery lists what the catalogue holds.' })]));
    }

    /** Ends the gallery: every demo unmounted. */
    destroy() { this.unmount(); this.el.textContent = ''; }
  }

  GAL.Gallery = Gallery;
  GAL.MODES = MODES;
  /**
   * Starts the gallery in an element with the page's libraries, catalogue, demos and data.
   * @param {HTMLElement} el
   * @param {Object} [o] mode ('playground', the default, or 'docs'), mapsBase (the map shapes' folder)
   * @returns {Gallery}
   */
  GAL.start = function (el, o) {
    return new Gallery(el, Object.assign({ mode: 'playground', catalogue: GAL.catalogue, demos: GAL.demos, elements: NS.elements, data: GAL.data, code: GAL.code }, o));
  };
}(typeof window !== 'undefined' ? window : globalThis));
