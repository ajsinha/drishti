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
/* Shipping a design (step 8): the Ship menu (export a pack fragment, share a read-only link, bind to a file on a development server),
 * the design's lifecycle status (draft, checked, proposed P-n, live vN) and the file sync that brings an IDE edit back into the design.
 * The server decides every right and says why not; this draws and forwards.
 *
 *   new WB.Ship(root, store, {yaml}) -> {menu(), bound(), saveFile(), paint(status), sync()}                                       */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var POLL_MS = 3000;

  WB.Ship = function (root, store, hooks) {
    var $ = function (s) { return root.querySelector(s); };
    var init = JSON.parse(root.dataset.init || '{}');
    var id = store.state.id, base = '/build/designs/' + encodeURIComponent(id);
    var chip = $('[data-status-chip]'), shipB = $('[data-ship-menu]'), saveB = $('[data-save]'), box = $('[data-share-box]');
    var bound = init.boundFile || '', shared = !!init.shared, enabled = !!init.fileBinding;
    var say = function (t, bad) { store.emit('say', t, bad); };

    // ---- status ---------------------------------------------------------------------------------------------------------
    function paint(status) {
      if (!chip) { return; }
      var m = /^(\w+)\((.*)\)$/.exec(status || ''), word = (m ? m[1] : status) || 'draft', arg = m ? m[2] : '';
      chip.textContent = '';
      chip.className = 'bs-role wb-state wb-state-' + word;
      if (word === 'proposed') {
        chip.appendChild(document.createTextNode('proposed '));
        chip.appendChild(WB.el('a', 'lnk', arg, { href: '/build/reviews/' + encodeURIComponent(arg) }));
        chip.title = 'Waiting for an approver';
      } else if (word === 'live') {
        chip.textContent = 'live ' + arg;
        chip.title = 'Approved: views use this version';
      } else {
        chip.textContent = word;
        chip.title = word === 'checked' ? 'The last check is green for this revision' : 'Edited since it was last checked or proposed';
      }
      chip.hidden = false;
    }
    store.on('doc', function () { paint(store.state.status); });
    store.on('checked', function () { paint(store.state.status); });
    paint(store.state.status);

    // ---- sharing --------------------------------------------------------------------------------------------------------
    function paintShare(url) {
      if (!box) { return; }
      box.hidden = !shared;
      if (url) { box.querySelector('[data-share-url]').value = url; }
      if (shared && !url) { box.querySelector('[data-share-url]').value = ''; box.querySelector('[data-share-url]').placeholder = 'The link was shown when it was made. Renew it to see a new one.'; }
    }
    function share() {
      return WB.call('POST', base + '/share').then(function (r) {
        if (!r.ok) { say(WB.why(r), true); return; }
        shared = true; paintShare(r.body.url);
        var copied = navigator.clipboard ? navigator.clipboard.writeText(r.body.url).then(function () { return true; }, function () { return false; }) : Promise.resolve(false);
        copied.then(function (ok) { say('Read-only link made' + (ok ? ' and copied' : '') + '. It shows the Sutra, the operations and the sample names, never the samples. Revoke it any time.'); });
      });
    }
    function revoke() {
      return WB.call('DELETE', base + '/share').then(function (r) {
        if (!r.ok) { say(WB.why(r), true); return; }
        shared = false; paintShare(); say('The link is revoked: it shows nothing now.');
      });
    }
    if (box) {
      box.querySelector('[data-share-copy]').addEventListener('click', function () {
        var input = box.querySelector('[data-share-url]');
        if (!input.value) { say('The link is only shown when it is made: renew it to get a new one.', true); return; }
        input.select();
        (navigator.clipboard ? navigator.clipboard.writeText(input.value) : Promise.reject()).then(function () { say('Link copied.'); }, function () { say('Press Ctrl+C to copy the selected link.'); });
      });
      box.querySelector('[data-share-revoke]').addEventListener('click', revoke);
      paintShare();
    }

    // ---- file binding ---------------------------------------------------------------------------------------------------
    function paintBound() {
      if (saveB) {
        var label = saveB.lastChild;
        if (bound) { saveB.title = 'Write the Sutra to ' + bound + ' (Ctrl+S); the hot reload makes views use it'; saveB.dataset.bound = '1'; }
        else { delete saveB.dataset.bound; }
        if (label && label.nodeType === 3) { label.textContent = bound ? ' Save to file' : (init.review ? ' Submit for review' : ' Save'); }
      }
      var chipB = $('[data-bound-chip]');
      if (chipB) { chipB.hidden = !bound; chipB.textContent = bound ? 'file ' + bound : ''; }
    }
    function bind() {
      var file = window.prompt('Bind this design to a file under the Sutra directory' + (init.dirs && init.dirs.length ? ' (' + init.dirs[0] + ')' : '') +
        '. For example market/my-view.v1.sutra.yaml. An existing file is read into the design; saving writes it.', bound);
      if (!file) { return Promise.resolve(); }
      return hooks.yaml.flush().then(function () { return WB.call('POST', base + '/bind', { file: file.trim() }); }).then(function (r) {
        if (!r.ok) { say(WB.why(r), true); return; }
        bound = r.body.boundFile || ''; paintBound();
        return reload('Bound to ' + bound + '.');
      });
    }
    function unbind() {
      return WB.call('DELETE', base + '/bind').then(function (r) {
        if (!r.ok) { say(WB.why(r), true); return; }
        bound = ''; paintBound(); say('Unbound: saving goes to the registry again.');
      });
    }
    function reload(message) {
      return WB.call('GET', store.base).then(function (g) {
        if (g.ok) {
          store.adopt({ rev: g.body.rev, yaml: g.body.sutra, status: g.body.status, opsAt: g.body.opsAt, opsCount: (g.body.ops || []).length, problems: [] }, 'reload');
          store.refresh();
        }
        if (message) { say(message); }
      });
    }
    function saveFile() {
      return hooks.yaml.flush().then(function () { say('Writing ' + bound + '...'); return WB.call('POST', base + '/save-file'); }).then(function (r) {
        if (!r.ok) { say(WB.why(r) + ((r.body.problems || []).length ? ' ' + r.body.problems[0].message : ''), true); return false; }
        say('Wrote ' + bound + '. The hot reload makes views use it.'); return true;
      });
    }
    var polling = false;
    function sync() {
      if (!bound || polling || document.hidden) { return Promise.resolve(); }
      polling = true;
      return WB.call('GET', base + '/sync').then(function (r) {
        polling = false;
        if (!r.ok) { return; }
        if (r.body.missing) { return; }
        if (r.body.changed) { return reload(bound + ' changed on disk: loaded it into the design. Undo (Ctrl+Z) brings your version back.'); }
      }, function () { polling = false; });
    }
    if (enabled) { setInterval(sync, POLL_MS); window.addEventListener('focus', sync); }       // a no-op until the design is bound

    // ---- the menu -------------------------------------------------------------------------------------------------------
    function menu() {
      var items = [
        { label: 'Export as a pack fragment (.zip)', detail: 'pack.yaml stub, the Sutra, tests with expect.yaml, three samples, README', value: 'export' },
        { label: 'Import a pack folder or zip…', detail: 'Opens New screen: each Sutra becomes a design with its samples', value: 'import' },
        { label: shared ? 'Renew the read-only link' : 'Create a read-only link', detail: 'Sutra, operations and sample names only; the recipient uses their own data', value: 'share' }
      ];
      if (shared) { items.push({ label: 'Revoke the read-only link', detail: 'It stops working at once', value: 'revoke' }); }
      if (enabled) {
        items.push({ label: bound ? 'Bind to another file…' : 'Bind to a file…', detail: 'Development servers only: save writes the file, edits in your IDE come back', value: 'bind' });
        if (bound) { items.push({ label: 'Save to ' + bound, detail: 'Write the Sutra to the file now', value: 'savefile' }, { label: 'Unbind from ' + bound, detail: 'Saving goes to the registry again', value: 'unbind' }); }
      }
      WB.menu.open({ title: 'Ship', anchor: shipB, filter: true, items: items, onPick: function (it) {
        if (it.value === 'export') { window.location.href = base + '/export'; say('Exporting the pack fragment...'); }
        else if (it.value === 'import') { window.location.href = '/build/new#import'; }
        else if (it.value === 'share') { share(); }
        else if (it.value === 'revoke') { revoke(); }
        else if (it.value === 'bind') { bind(); }
        else if (it.value === 'savefile') { saveFile(); }
        else if (it.value === 'unbind') { unbind(); }
      } });
    }
    if (shipB) { shipB.addEventListener('click', menu); }
    paintBound();

    return { menu: menu, bound: function () { return bound; }, saveFile: saveFile, paint: paint, sync: sync, share: share, revoke: revoke, bind: bind };
  };
})();
