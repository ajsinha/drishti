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
/* UI Board P4b, Chip mode's Release step (UI_BOARD.md 9, 12.2, 12.6): the chip's versions and their states, and the lifecycle steps through
   P2's routes and powers (POST /ui/chips/<name>/<version>/submit | approve | reject | publish | certify; four eyes: nobody approves their own).
   Before a new version is released: where it is used (the boards on each earlier version) and what breaks against the latest released one
   (removed or retyped pins). A breaking change is a new major version: it may not claim compatible-with the earlier one (CP-06 here, DRS-2078
   at publish), so boards stay on their version until their owner moves them; a compatible one lets boards follow by themselves. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, CE = U && U.chipEditor, root = document.querySelector('[data-ub-design]');
  if (!CE) { return; }
  const h = U.h, clear = U.clear, box = root.querySelector('[data-ce-release]');
  let versions = [];

  function load() {
    if (!U.chipModel.NAME.test(CE.name())) { versions = []; paint(); return Promise.resolve(); }
    return CE.api('GET', '/ui/api/chips/' + encodeURIComponent(CE.name())).then((b) => { versions = (b && b.versions) || []; paint(); }).catch(() => { versions = []; paint(); });
  }
  function act(action, n, comment) {
    return CE.api('POST', '/ui/api/chips/' + encodeURIComponent(CE.name()) + '/' + n + '/' + action, comment ? { comment } : {})
      .then((st) => { CE.say(CE.name() + '@' + n + ': ' + ((st && st.state) || action) + '.'); return load(); })
      .catch((e) => { CE.fail(e); if (e.status === 403 && /four|own/i.test(e.detail || '')) { CE.say((e.code || '') + ': four eyes: a reviewer other than the author approves it.', true); } });
  }
  function paint() {
    clear(box);
    const n = CE.doc.version, mine = versions.find((v) => v.version === n), state = mine ? mine.state : 'draft';
    const s = CE.server || {};
    // where used and compatibility
    const wu = h('fieldset', { class: 'ub-fs', 'data-ce-where-used': true }, h('legend', { text: 'Where used' }));
    const used = (s.whereUsed || []).filter((v) => (v.boards || []).length || v.others);
    if (!used.length) { wu.appendChild(h('p', { class: 'text-muted-d', text: 'No saved board uses any version of this chip yet.' })); }
    used.forEach((v) => wu.appendChild(h('p', {}, h('b', { text: 'Version ' + v.version + ': ' }), (v.boards || []).join(', ') + (v.others ? ' and ' + v.others + ' board' + (v.others === 1 ? '' : 's') + ' you may not open' : ''))));
    box.appendChild(wu);
    const cf = h('fieldset', { class: 'ub-fs', 'data-ce-compat': true }, h('legend', { text: 'Compatibility' }));
    if (s.previous) {
      const breaking = s.breaking || [];
      const claim = Array.isArray(CE.doc['compatible-with']) && CE.doc['compatible-with'].indexOf(s.previous) >= 0;
      if (breaking.length) {
        cf.appendChild(h('p', { class: 'ub-bad-text', 'data-ce-breaking': true, text: 'Breaking against version ' + s.previous + ': ' + breaking.join('; ') + '. This is a new major version: boards on ' + s.previous + ' keep it until their owner moves them.' }));
      } else {
        cf.appendChild(h('p', { class: 'ub-ok', text: 'Compatible with version ' + s.previous + ': nothing removed or retyped.' }));
      }
      const cb = h('input', { type: 'checkbox', 'data-ce-compatible': true });
      cb.checked = claim; cb.disabled = breaking.length > 0 && !claim;
      cb.addEventListener('change', () => {
        const list = (Array.isArray(CE.doc['compatible-with']) ? CE.doc['compatible-with'] : []).filter((x) => x !== s.previous);
        if (cb.checked) { list.push(s.previous); }
        if (list.length) { CE.doc['compatible-with'] = list; } else { delete CE.doc['compatible-with']; }
        CE.changed('release');
      });
      cf.appendChild(h('label', { class: 'ub-field-inline' }, cb, ' compatible-with: [' + s.previous + '] (boards on ' + s.previous + ' follow this version by themselves)'));
    } else {
      cf.appendChild(h('p', { class: 'text-muted-d', text: 'No earlier released version: nothing to be compatible with.' }));
    }
    box.appendChild(cf);
    // versions and steps
    const vf = h('fieldset', { class: 'ub-fs' }, h('legend', { text: 'Versions' }));
    if (!versions.length) { vf.appendChild(h('p', { class: 'text-muted-d', text: 'Nothing submitted yet.' })); }
    versions.forEach((v) => vf.appendChild(h('p', { 'data-ce-version-row': String(v.version), 'data-state': v.state }, h('b', { class: 'mono', text: CE.name() + '@' + v.version }), ' · ' + v.state + (v.author ? ' · by ' + v.author : '') + (v.reviewer ? ' · reviewed by ' + v.reviewer : '') + (v.certifier ? ' · certified by ' + v.certifier : ''))));
    box.appendChild(vf);
    const steps = h('div', { class: 'ub-row', 'data-ce-steps-for': String(n), 'data-state': state });
    const btn = (action, label, accent) => {
      const b = h('button', { type: 'button', class: 'btn-pill ' + (accent ? 'btn-accent' : 'btn-ghost'), 'data-ce-act': action, text: label });
      b.addEventListener('click', () => {
        if (action === 'submit') {
          const errors = CE.problems.filter((p) => p.level !== 'warning');
          if (errors.length) { CE.say('Fix the ' + errors.length + ' problem' + (errors.length === 1 ? '' : 's') + ' first: ' + errors[0].rule + ' ' + errors[0].message, true); return; }
          CE.save().then((ok) => { if (ok) { act('submit', n); } });
          return;
        }
        const comment = action === 'reject' ? window.prompt('Why? (the author reads it)') : null;
        if (action === 'reject' && !comment) { return; }
        act(action, n, comment);
      });
      steps.appendChild(b);
    };
    if (state === 'draft') { btn('submit', 'Submit ' + CE.name() + '@' + n + ' for review', true); }
    if (state === 'in-review') { btn('approve', 'Approve', true); btn('reject', 'Reject'); }
    if (state === 'approved') { btn('publish', 'Publish', true); }
    if (state === 'published') { btn('certify', 'Certify', true); btn('deprecate', 'Deprecate'); }
    if (state === 'certified') { btn('deprecate', 'Deprecate'); }
    box.appendChild(h('p', { class: 'text-muted-d', text: 'Version ' + n + ' is ' + state + '. Submit runs the publish check (CP-01 to CP-05) on the server; a reviewer other than you approves; publish makes it usable on any board; a certifier certifies it.' }));
    box.appendChild(steps);
  }
  CE.reloadVersions = load;
  CE.on((src) => {
    if (src === 'step:release' || src === 'load') { load(); return; }
    if (CE.step === 'release' && (src === 'server' || src === 'toolbar')) { paint(); }
  });
})();
