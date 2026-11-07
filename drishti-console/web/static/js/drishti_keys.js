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
/* One keymap (2.9.11.f). Every key a page answers is registered here, with what it does, its scope and its conditions, and one listener on the
   document dispatches them; the `?` overlay lists the keys of the screen you are on from the same registrations.

     Drishti.keys.register({ id: 'calc.toggle', keys: ['Alt+C'], scope: 'screen', area: 'view', group: 'View', description: 'Calc: Python on this view',
                             typing: true, when: function (e) { ... }, run: function (e) { ... } });
     Drishti.keys.describe({ id: 'table.walk', keys: ['ArrowUp', 'ArrowDown'], scope: 'panel', area: 'table', ... })   // a widget's own key, listed

   - keys: canonical chords: modifiers in the order Ctrl, Alt, Shift, Meta, then the key (a letter or digit in capitals, else its
     KeyboardEvent.key: F1, Escape, ArrowLeft, Enter, ?, /). A printed sign (? / +) never names Shift. Alt+<letter or digit> is read from the
     key's position, so Option on a Mac works as Alt.
   - scope: dialog (while its dialog is open: `open()`), panel (while the focus is inside `within`), screen (the page that registered it),
     global (every page). The most specific scope that wants the key takes it: dialog, panel, screen, then global.
   - area: the dialog's, panel's or screen's name; two registrations may not claim the same chord in the same scope and area
     (tests/test_keymap.py fails the build, and the registry names the pair in Drishti.keys.conflicts()).
   - typing: false (the default) leaves the key to a text field, select or editable element that has the focus, so typing never triggers a
     command and screen readers' own keys (which never reach the page) are untouched. A key already taken by a widget (defaultPrevented) is left.
   - when(e): an extra condition; run(e) returning false passes the key on to the next registration. */
(function () {
  'use strict';
  var D = window.Drishti;
  if (D.keys) { return; }
  var SCOPES = { dialog: 0, panel: 1, screen: 2, global: 3 };
  var MODS = ['Ctrl', 'Alt', 'Shift', 'Meta'];
  var regs = [], conflicts = [];

  function canonical(chord) {
    var parts = String(chord).split('+'), key = parts.pop();
    if (key === '' && parts.length && parts[parts.length - 1] === '') { parts.pop(); key = '+'; }      // 'Ctrl++'
    var mods = MODS.filter(function (m) { return parts.indexOf(m) >= 0; });
    if (key.length === 1) {
      key = key.toUpperCase();
      if (!/[A-Z0-9]/.test(key)) { mods = mods.filter(function (m) { return m !== 'Shift'; }); }
    }
    return mods.concat([key]).join('+');
  }

  function chordOf(e) {
    var key = e.key || '';
    if (key === 'Dead' || key === 'Unidentified' || (e.altKey && key.length === 1) || (e.shiftKey && key.length === 1 && /^Digit/.test(e.code || ''))) {
      var m = /^Key([A-Z])$/.exec(e.code || '') || /^Digit([0-9])$/.exec(e.code || '');
      if (m) { key = m[1]; }
    }
    if (key === ' ') { key = 'Space'; }
    var mods = [];
    if (e.ctrlKey) { mods.push('Ctrl'); }
    if (e.altKey) { mods.push('Alt'); }
    if (e.shiftKey) { mods.push('Shift'); }
    if (e.metaKey) { mods.push('Meta'); }
    return canonical(mods.concat([key]).join('+'));
  }

  function typing(t) {
    if (!t || t.nodeType !== 1) { return false; }
    if (t.isContentEditable) { return true; }
    var tag = t.tagName;
    if (tag === 'TEXTAREA' || tag === 'SELECT') { return true; }
    if (tag === 'INPUT') { return !/^(button|submit|reset|checkbox|radio|range|color|file|image)$/i.test(t.type || 'text'); }
    var role = t.getAttribute('role');
    return role === 'textbox' || role === 'combobox' || role === 'searchbox';
  }

  function same(a, b) {
    return a.scope === b.scope && a.area === b.area && a.keys.some(function (k) { return b.keys.indexOf(k) >= 0; });
  }

  function add(def, listed) {
    if (!def || !def.id || !def.keys || !def.keys.length || !(def.scope in SCOPES)) {
      throw new Error('Drishti.keys: a registration needs id, keys and a scope (dialog, panel, screen, global)');
    }
    var r = {
      id: def.id, keys: def.keys.map(canonical), scope: def.scope, area: def.scope === 'global' ? '*' : String(def.area || ''),
      group: def.group || '', description: def.description || '', typing: !!def.typing, when: def.when || null, run: listed ? null : def.run,
      keepDefault: !!def.keepDefault, open: def.open || null, within: def.within || null, capture: !!def.capture, hidden: !!def.hidden, label: def.label || ''
    };
    def.keys.forEach(function (k) { if (canonical(k) !== k) { console.error('Drishti.keys: write ' + canonical(k) + ', not ' + k + ' (' + def.id + ')'); } });
    regs.forEach(function (o) {
      if (o.id !== r.id && same(o, r)) {
        conflicts.push([o.id, r.id]);
        console.error('Drishti.keys: ' + r.id + ' claims a key of ' + o.id + ' in the same scope (' + r.scope + ' ' + r.area + ')');
      }
    });
    regs = regs.filter(function (o) { return o.id !== r.id; });
    regs.push(r);
    return function () { regs = regs.filter(function (o) { return o !== r; }); };
  }

  function active(r, e) {
    if (r.scope === 'dialog' && r.open && !r.open()) { return false; }
    if (r.scope === 'panel' && r.within) {
      var box = typeof r.within === 'string' ? document.querySelector(r.within) : (typeof r.within === 'function' ? r.within() : r.within);
      var at = e ? e.target : document.activeElement;
      if (!box || !at || !box.contains(at)) { return false; }
    }
    return !r.when || !!r.when(e);
  }

  function dispatch(capture) {
    return function (e) {
      if (e.defaultPrevented || e.isComposing) { return; }
      var chord = chordOf(e), writing = typing(e.target);
      var wanted = regs.filter(function (r) {
        return r.run && r.capture === capture && r.keys.indexOf(chord) >= 0 && (r.typing || !writing);
      }).sort(function (a, b) { return SCOPES[a.scope] - SCOPES[b.scope]; });
      for (var i = 0; i < wanted.length; i++) {
        var r = wanted[i];
        if (!active(r, e)) { continue; }
        if (r.run(e, chord) === false) { continue; }
        if (!r.keepDefault) { e.preventDefault(); }
        return;
      }
    };
  }
  document.addEventListener('keydown', dispatch(true), true);
  document.addEventListener('keydown', dispatch(false));

  // ---- the ? overlay: the keys of this screen, grouped -------------------------------------------------------------------------------
  var overlay = null, back = null;
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function kbd(chord) { return chord.split('+').map(function (p) { return '<kbd>' + esc(p === 'Escape' ? 'Esc' : p.replace(/^Arrow/, '')) + '</kbd>'; }).join('+'); }
  var HEADS = { dialog: 'In an open dialog', panel: 'In a panel or field', screen: 'On this screen', global: 'Everywhere' };
  function shown() {
    return regs.filter(function (r) {
      if (r.hidden) { return false; }
      if (r.scope === 'dialog' && r.open && !r.open()) { return false; }
      return !(r.scope === 'panel' && r.within && typeof r.within === 'string' && !document.querySelector(r.within));
    });
  }
  function draw() {
    var by = {};
    shown().forEach(function (r) { (by[r.scope] = by[r.scope] || []).push(r); });
    return ['screen', 'panel', 'dialog', 'global'].filter(function (s) { return by[s]; }).map(function (s) {
      var groups = {};
      by[s].forEach(function (r) { var gname = r.group || HEADS[s]; (groups[gname] = groups[gname] || []).push(r); });
      return '<section class="drishti_keys-scope" data-keys-scope="' + s + '"><h3>' + esc(HEADS[s]) + '</h3>' + Object.keys(groups).map(function (gname) {
        return (gname !== HEADS[s] ? '<h4>' + esc(gname) + '</h4>' : '') + '<dl>' + groups[gname].map(function (r) {
          return '<div data-key-id="' + esc(r.id) + '"><dt>' + (r.label ? esc(r.label) : r.keys.map(kbd).join(' ')) + '</dt><dd>' + esc(r.description) + '</dd></div>';
        }).join('') + '</dl>';
      }).join('') + '</section>';
    }).join('');
  }
  function closeOverlay() {
    if (!overlay || overlay.hidden) { return; }
    overlay.hidden = true;
    if (back && back.focus) { back.focus(); }
  }
  function openOverlay() {
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.className = 'drishti_keys-overlay';
      overlay.setAttribute('role', 'dialog');
      overlay.setAttribute('aria-modal', 'true');
      overlay.setAttribute('aria-labelledby', 'drishtiKeysTitle');
      overlay.setAttribute('data-keys-overlay', '');
      overlay.hidden = true;
      overlay.addEventListener('click', function (e) { if (e.target === overlay || e.target.closest('[data-keys-close]')) { closeOverlay(); } });
      document.body.appendChild(overlay);
    }
    back = document.activeElement;
    overlay.innerHTML = '<div class="drishti_keys-card" tabindex="-1"><header><h2 id="drishtiKeysTitle">Keys on this screen</h2>' +
      '<button type="button" class="btn-pill btn-ghost" data-keys-close aria-label="Close">Esc</button></header>' +
      '<div class="drishti_keys-body">' + draw() + '</div>' +
      '<p class="drishti_keys-foot">Keys never act while you type in a field. Every key: Help → User guide → Keyboard.</p></div>';
    overlay.hidden = false;
    overlay.querySelector('.drishti_keys-card').focus();
  }
  function toggleOverlay() { if (overlay && !overlay.hidden) { closeOverlay(); } else { openOverlay(); } }

  D.keys = {
    register: function (def) { return add(def, false); },
    describe: function (def) { return add(def, true); },
    list: function () { return shown().map(function (r) { return { id: r.id, keys: r.keys.slice(), scope: r.scope, area: r.area, group: r.group, description: r.description }; }); },
    conflicts: function () { return conflicts.slice(); },
    chord: chordOf,
    typing: typing,
    overlay: { open: openOverlay, close: closeOverlay, toggle: toggleOverlay, isOpen: function () { return !!overlay && !overlay.hidden; } }
  };

  document.addEventListener('click', function (e) {                    // the footer's "Keys" button (and any [data-keys-open])
    var b = e.target.closest && e.target.closest('[data-keys-open]');
    if (b) { e.preventDefault(); openOverlay(); }
  });
  D.keys.register({ id: 'keys.overlay', keys: ['?'], scope: 'global', group: 'Help', description: 'This list: the keys of the screen you are on', run: toggleOverlay });
  D.keys.register({ id: 'keys.overlay.close', keys: ['Escape'], scope: 'dialog', area: 'keys', description: 'Close this list', typing: true,
    open: function () { return D.keys.overlay.isOpen(); }, run: closeOverlay });
}());
