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
/* UI Board P4b, the chip designer: Chip mode of Build's one designer (UI_BOARD.md 10, 12.6). One chip document, edited by five steps and the
   shared YAML / JSON tab: Pins (ui-chip-pins.js: the pin table), Body (ui-chip-body.js: the screen designer's panel palette and inspector over
   the chip's pins), Manual (ui-chip-pins.js: the manual form), Test bench (ui-chip-bench.js) and Release (ui-chip-release.js). This file holds
   the document and what they share: the toolbar (name, version, title, category; New, Open my draft, Save draft), the step tabs, the YAML tab
   in Chip mode, the Problems (the browser's check of ui-chip-model.js at once, the server's POST /ui/chips/check on pause, which adds CP-05
   and CP-06), the palette's store chips opened as a copy, and the Candidates list (the converter's board-local chips, GET /ui/candidates).
   Entry points kept from P4: drishtiUiBoard.designer.setMode('chip'), /build/chip/<name>, the ub:mode event, [data-ub-yaml],
   designer.onChipPick(ref), [data-ce-bench]. Exposes drishtiUiBoard.chipEditor. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, root = document.querySelector('[data-ub-design]');
  if (!root || !U || !U.designer || !U.chipModel) { return; }
  const D = U.designer, M = U.chipModel, h = U.h, clear = U.clear, $ = (s) => root.querySelector(s);
  const nameIn = $('[data-ce-name]'), verIn = $('[data-ce-version]'), titleIn = $('[data-ce-title]'), catIn = $('[data-ce-category]');
  const yamlBox = $('[data-ub-yaml]'), yamlNote = $('[data-ub-yaml-note]');
  const listeners = [];
  const CE = U.chipEditor = { doc: M.blank(''), etag: null, yaml: '', problems: [], server: { problems: [], whereUsed: [], breaking: null, previous: null }, step: 'pins' };

  CE.say = D.say;
  CE.api = D.api;
  /** A raw YAML body (a draft as the person wrote it), with If-Match. */
  CE.putYaml = (url, text, headers) => fetch(url, { method: 'PUT', credentials: 'same-origin', headers: Object.assign({ 'Content-Type': 'application/yaml' }, headers || {}), body: text })
    .then((r) => r.text().then((t) => { let b = null; try { b = t ? JSON.parse(t) : null; } catch (e) { b = { detail: t }; } if (!r.ok) { b = b || {}; b.status = r.status; b.code = b.code || 'HTTP-' + r.status; throw b; } return { b: b || {}, etag: r.headers.get('ETag') }; }));
  CE.fail = (e) => D.say((e.code || 'error') + ': ' + (e.detail || '') + (e.problems && e.problems.length ? ' · ' + e.problems.map((p) => p.rule + ' ' + (p.message || '')).join(' · ') : ''), true);
  CE.on = (fn) => listeners.push(fn);
  CE.name = () => String(CE.doc.chip || '').trim();
  CE.ref = () => CE.name() + '@' + CE.doc.version;

  // ---- the one document: every change goes through changed() ----------------------------------------------------------------------------
  let yamlTimer = 0, serverTimer = 0, serverSeq = 0;
  CE.changed = (source, opts) => {
    if (source !== 'toolbar') { fillToolbar(); }
    root.setAttribute('data-chip-dirty', 'true');
    recheck();
    listeners.forEach((fn) => { try { fn(source); } catch (e) { if (window.console) { console.error(e); } } });
    if (source !== 'yaml') { clearTimeout(yamlTimer); yamlTimer = setTimeout(writeYaml, 120); }
    if (!(opts && opts.quiet)) { clearTimeout(serverTimer); serverTimer = setTimeout(serverCheck, 700); }
  };
  CE.load = (doc, how) => {
    CE.doc = Object.assign(M.blank(''), doc || {});
    CE.doc.pins = Object.assign({ in: {}, out: {} }, CE.doc.pins || {});
    CE.doc.pins.in = CE.doc.pins.in || {}; CE.doc.pins.out = CE.doc.pins.out || {};
    CE.doc.manual = Object.assign(M.blank('').manual, CE.doc.manual || {});
    CE.doc.body = CE.doc.body || { panels: [] };
    CE.yaml = '';
    CE.server = { problems: [], whereUsed: [], breaking: null, previous: null };
    CE.changed('load');
    if (how) { D.say(how); }
  };

  function fillToolbar() {
    if (document.activeElement !== nameIn) { nameIn.value = CE.doc.chip || ''; }
    if (document.activeElement !== verIn) { verIn.value = CE.doc.version || 1; }
    if (document.activeElement !== titleIn) { titleIn.value = CE.doc.title || ''; }
    if (document.activeElement !== catIn) { catIn.value = CE.doc.category || ''; }
  }
  nameIn.addEventListener('input', () => { CE.doc.chip = nameIn.value.trim(); CE.changed('toolbar'); if (D.mode === 'chip') { history.replaceState(null, '', '/build/chip/' + encodeURIComponent(CE.name() || 'new')); } });
  verIn.addEventListener('input', () => { const n = parseInt(verIn.value, 10); CE.doc.version = isNaN(n) ? verIn.value : n; CE.changed('toolbar'); });
  titleIn.addEventListener('input', () => { CE.doc.title = titleIn.value; CE.changed('toolbar'); });
  catIn.addEventListener('input', () => { CE.doc.category = catIn.value.trim(); CE.changed('toolbar'); });

  // ---- problems: the browser's at once, the server's on pause ---------------------------------------------------------------------------
  const STEP_OF = (path) => /^pins/.test(path) ? 'pins' : /^manual\.examples/.test(path) ? 'manual' : /^manual/.test(path) ? 'manual' : /^body/.test(path) ? 'body' : /^compatible-with/.test(path) ? 'release' : 'pins';
  function recheck() {
    const local = M.check(CE.doc).concat(M.compositeProblems(CE.doc, (ref) => D.chipDoc(ref)));
    const fromServer = (CE.server.problems || []).filter((p) => /^CP-0[56]/.test(p.rule) && !local.some((q) => q.rule === p.rule && q.path === p.path));
    CE.problems = local.concat(fromServer);
    const box = $('[data-ce-problems]');
    clear(box);
    const errors = CE.problems.filter((p) => p.level !== 'warning');
    CE.problems.forEach((p) => {
      const li = h('li', { class: p.level === 'warning' ? 'bi-warn' : 'ub-bad-text', 'data-rule': p.rule, 'data-path': p.path || '' });
      const go = h('button', { type: 'button', class: 'lnk', text: p.rule + ' · ' + (p.path || 'chip') });
      go.addEventListener('click', () => CE.goto(STEP_OF(p.path || ''), p.path));
      li.appendChild(go);
      li.appendChild(document.createTextNode(' ' + p.message + (p.fix ? ' (' + p.fix + ')' : '')));
      box.appendChild(li);
    });
    $('[data-ce-problem-count]').textContent = errors.length ? errors.length + ' error' + (errors.length === 1 ? '' : 's') : (CE.problems.length ? 'warnings only' : 'no problems: ready to submit');
    root.querySelectorAll('[data-ce-step]').forEach((b) => {
      const s = b.getAttribute('data-ce-step'), n = errors.filter((p) => STEP_OF(p.path || '') === s).length, badge = b.querySelector('[data-ce-count]');
      if (badge) { badge.textContent = n ? String(n) : ''; badge.hidden = !n; }
      b.classList.toggle('ub-bad-text', n > 0);
    });
  }
  CE.recheck = recheck;
  CE.problemsAt = (prefix) => CE.problems.filter((p) => (p.path || '') === prefix || (p.path || '').indexOf(prefix + '.') === 0 || (p.path || '').indexOf(prefix + '[') === 0);
  function serverCheck() {
    if (!M.NAME.test(CE.name()) || !Number.isInteger(CE.doc.version)) { return; }
    const seq = ++serverSeq;
    fetch('/ui/api/chips/check', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(CE.doc) })
      .then((r) => r.json().then((b) => ({ ok: r.ok, b })))
      .then(({ ok, b }) => {
        if (seq !== serverSeq || !ok || !b) { return; }
        CE.server = { problems: b.problems || [], whereUsed: b.whereUsed || [], breaking: b.breaking || null, previous: b.previous || null, claims: !!b.claimsCompatible };
        recheck();
        listeners.forEach((fn) => { try { fn('server'); } catch (e) { /* a part failed to redraw */ } });
      }).catch(() => {});
  }
  CE.serverCheck = serverCheck;

  // ---- steps ------------------------------------------------------------------------------------------------------------------------------
  CE.goto = (step, path) => {
    CE.step = step;
    root.querySelectorAll('[data-ce-step]').forEach((b) => b.setAttribute('aria-selected', String(b.getAttribute('data-ce-step') === step)));
    root.querySelectorAll('[data-ce-pane]').forEach((p) => { p.hidden = p.getAttribute('data-ce-pane') !== step; });
    D.tab('canvas');
    listeners.forEach((fn) => { try { fn('step:' + step); } catch (e) { /* a part failed to redraw */ } });
    if (path) {
      const el = root.querySelector('[data-ce-pane="' + step + '"] [data-path="' + path + '"], [data-ce-pane="' + step + '"] [data-path^="' + path + '"]');
      if (el) { el.focus && el.focus(); }
    }
  };
  root.querySelectorAll('[data-ce-step]').forEach((b) => b.addEventListener('click', () => CE.goto(b.getAttribute('data-ce-step'))));

  // ---- the YAML / JSON tab in Chip mode --------------------------------------------------------------------------------------------------
  function writeYaml() {
    return D.api('POST', '/ui/api-yaml/dump', { document: CE.doc, yaml: CE.yaml, kind: 'chip' }).then((r) => {
      CE.yaml = r.yaml;
      if (D.mode === 'chip' && document.activeElement !== yamlBox) { yamlBox.value = r.yaml; }
    }).catch(() => {});
  }
  CE.writeYaml = writeYaml;
  let readTimer = 0;
  yamlBox.addEventListener('input', () => {
    if (D.mode !== 'chip') { return; }
    clearTimeout(readTimer);
    readTimer = setTimeout(() => {
      const text = yamlBox.value;
      D.api('POST', '/ui/api-yaml/load', { yaml: text }).then((r) => {
        yamlNote.textContent = 'In step with the chip designer.'; yamlNote.classList.remove('bi-bad');
        CE.yaml = text;
        CE.doc = r.document || CE.doc;
        CE.changed('yaml');
      }).catch((e) => { yamlNote.textContent = (e.line ? 'Line ' + e.line + ', column ' + e.column + ': ' : '') + (e.detail || 'not YAML'); yamlNote.classList.add('bi-bad'); });
    }, 350);
  });
  root.addEventListener('ub:mode', (e) => {
    if (e.detail === 'chip') { yamlBox.value = CE.yaml || ''; writeYaml(); $('[data-ub-yaml-help]').textContent = 'The chip document (manual, pins, body): the same chip as the five steps. Edit either; the text is read on pause.'; } else { $('[data-ub-yaml-help]').textContent = 'The same document as the design view: edit either. The text is read on pause (YAML, or JSON, which is YAML too).'; }
  });

  // ---- New, Open my draft, Save draft ---------------------------------------------------------------------------------------------------
  CE.open = (name) => D.api('GET', '/ui/api/chips/' + encodeURIComponent(name) + '/draft').then((b) => {
    return D.api('POST', '/ui/api-yaml/load', { yaml: b.yaml || '' }).then((r) => { CE.load(r.document, 'Opened your draft of ' + name + '.'); CE.yaml = b.yaml || ''; CE.etag = b.__etag || b.etag || null; if (CE.etag && CE.etag[0] !== '"') { CE.etag = '"' + CE.etag + '"'; } root.removeAttribute('data-chip-dirty'); });
  });
  $('[data-ce-new]').addEventListener('click', () => { CE.etag = null; CE.load(M.blank(''), 'A new chip: name it, then declare its pins.'); CE.goto('pins'); nameIn.focus(); });
  $('[data-ce-open]').addEventListener('click', () => { if (!CE.name()) { D.say('Name the chip first.', true); nameIn.focus(); return; } CE.open(CE.name()).catch(CE.fail); });
  CE.save = () => {
    if (!M.NAME.test(CE.name())) { D.say('Name the chip first: lower-case letters, digits and dashes.', true); nameIn.focus(); return Promise.resolve(false); }
    return writeYaml().then(() => CE.putYaml('/ui/api/chips/' + encodeURIComponent(CE.name()) + '/draft', CE.yaml || JSON.stringify(CE.doc), CE.etag ? { 'If-Match': CE.etag } : {}))
      .then(({ etag }) => { CE.etag = etag || CE.etag; root.removeAttribute('data-chip-dirty'); D.say('Saved your draft of ' + CE.ref() + '.'); return true; })
      .catch((e) => { if (e.code === 'DRS-5057') { D.say('DRS-5057: your draft changed elsewhere. Open my draft reloads it (copy your changes from the YAML tab first).', true); } else { CE.fail(e); } return false; });
  };
  $('[data-ce-save]').addEventListener('click', CE.save);
  window.addEventListener('keydown', (e) => { if (D.mode === 'chip' && (e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); CE.save(); } });

  // ---- the palette: a store chip opens as a copy; the Candidates list ------------------------------------------------------------------
  D.onChipPick = (from) => {
    const m = /^([a-z0-9-]+)@(\d+)$/.exec(from || '');
    if (!m) { return; }
    D.api('GET', '/ui/api/chips/' + m[1] + '/' + m[2]).then((b) => {
      const doc = JSON.parse(JSON.stringify(b.document || {}));
      doc.chip = CE.name() && CE.name() !== m[1] ? CE.name() : m[1] + '-mine';
      doc.version = 1;
      delete doc['compatible-with'];
      CE.etag = null;
      CE.load(doc, 'A copy of ' + from + ' as ' + doc.chip + '@1: change it, then Save draft keeps it.');
      D.tab('yaml');
    }).catch(CE.fail);
  };
  const candBox = $('[data-ce-candidates]'), candQ = $('[data-ce-cand-q]');
  let candidates = null;
  function paintCandidates() {
    clear(candBox);
    const q = (candQ.value || '').trim().toLowerCase();
    const list = (candidates || []).filter((c) => !q || (c.pack + ' ' + c.board + ' ' + c.chip + ' ' + (c.from || '')).toLowerCase().indexOf(q) >= 0);
    if (!candidates) { candBox.appendChild(h('li', { class: 'text-muted-d', text: 'Loading the candidates…' })); return; }
    if (!list.length) { candBox.appendChild(h('li', { class: 'text-muted-d', text: candidates.length ? 'No candidate matches.' : 'No board-local chips: every converted screen uses Chip Store chips.' })); return; }
    list.slice(0, 200).forEach((c) => {
      const b = h('button', { type: 'button', class: 'btn-pill btn-ghost ub-cand', 'data-candidate': c.pack + '/' + c.board + '/' + c.chip, title: (c.reason || '') + ' · ' + c.screen },
        h('b', { class: 'mono', text: c.chip }), h('small', { class: 'text-muted-d', text: ' ' + c.pack + ' · ' + c.board }));
      b.addEventListener('click', () => CE.openCandidate(c.pack, c.board, c.chip));
      candBox.appendChild(h('li', {}, b));
    });
  }
  candQ.addEventListener('input', paintCandidates);
  function loadCandidates() {
    if (candidates) { return; }
    candidates = null; paintCandidates();
    D.api('GET', '/ui/api/candidates').then((list) => { candidates = Array.isArray(list) ? list : []; paintCandidates(); })
      .catch((e) => { candidates = []; clear(candBox); candBox.appendChild(h('li', { class: 'text-muted-d', text: (e.code || '') + ' ' + (e.detail || 'the candidates could not be read') })); });
  }
  /** A board-local chip opened to be made generic: renamed (no local- prefix), version 1, its manual prompts showing what is missing. */
  CE.openCandidate = (pack, board, chip) => D.api('GET', '/ui/api/candidates/' + [pack, board, chip].map(encodeURIComponent).join('/')).then((b) => {
    const doc = JSON.parse(JSON.stringify(b.document || {}));
    doc.chip = (pack + '-' + String(chip).replace(/^local-/, '')).slice(0, 64);
    doc.version = 1;
    doc.category = doc.category === 'local' ? 'tables' : doc.category;
    CE.etag = null;
    CE.load(doc, 'The candidate ' + chip + ' of ' + board + ' is open as ' + doc.chip + '@1: make its pins generic, complete its manual, then Save draft.');
    CE.goto('pins');
  }).catch(CE.fail);

  root.addEventListener('ub:mode', (e) => { if (e.detail === 'chip') { loadCandidates(); } });

  // ---- start ------------------------------------------------------------------------------------------------------------------------------
  const start = root.getAttribute('data-chip'), qs = new URLSearchParams(location.search);
  CE.load(M.blank(start || ''));
  CE.goto('pins');
  if (D.mode === 'chip') {
    loadCandidates();
    const cand = (qs.get('candidate') || '').split('/');
    if (cand.length === 3) { CE.openCandidate(cand[0], cand[1], cand[2]); } else if (start) { CE.open(start).catch(() => D.say('No draft of ' + start + ' yet: this is a new chip.')); }
  }
})();
