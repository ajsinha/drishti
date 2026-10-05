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
/* The Notes tab (QA UX-06): the design's notes, written in markdown, with a preview next to them. They travel to the reviewer with the
 * proposal. A licence comment at the top (an example's README carries one) is not shown and is not kept when you edit.
 *
 *   new WB.Notes(pane, store, initial) -> {text(), render()}                                                                  */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  /** The text without a leading <!-- ... --> comment that carries the licence header. */
  WB.stripLicence = function (t) {
    return String(t || '').replace(/^\s*<!--[\s\S]*?-->\s*/, function (m) { return /Copyright|PROPRIETARY|Project Drishti/i.test(m) ? '' : m; });
  };

  /** A small, safe markdown reader: headings, bullets, code, paragraphs, **bold**, `code` and [links](http...). Builds DOM; no HTML is taken from the text. */
  WB.markdown = function (text, into) {
    into.textContent = '';
    function inline(parent, s) {
      var re = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\((?:https?:\/\/|\/)[^)\s]+\))/g, last = 0, m;
      while ((m = re.exec(s))) {
        if (m.index > last) { parent.appendChild(document.createTextNode(s.slice(last, m.index))); }
        var t = m[0], n;
        if (t[0] === '*') { n = WB.el('strong', null, t.slice(2, -2)); }
        else if (t[0] === '`') { n = WB.el('code', null, t.slice(1, -1)); }
        else { var mm = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(t); n = WB.el('a', 'lnk', mm[1], { href: mm[2], rel: 'noopener' }); }
        parent.appendChild(n); last = m.index + t.length;
      }
      if (last < s.length) { parent.appendChild(document.createTextNode(s.slice(last))); }
    }
    var lines = WB.stripLicence(text).split('\n'), i = 0, para = [], list = null;
    function flush() { if (para.length) { var p = WB.el('p'); inline(p, para.join(' ')); into.appendChild(p); para = []; } list = null; }
    while (i < lines.length) {
      var ln = lines[i++], h = /^(#{1,6})\s+(.*)$/.exec(ln), li = /^\s*[-*]\s+(.*)$/.exec(ln);
      if (/^```/.test(ln)) {
        flush(); var code = [];
        while (i < lines.length && !/^```/.test(lines[i])) { code.push(lines[i++]); }
        i++; into.appendChild(WB.el('pre', 'mono', code.join('\n')));
      } else if (h) { flush(); var hh = WB.el('h' + Math.min(h[1].length + 1, 6)); inline(hh, h[2]); into.appendChild(hh); }
      else if (li) { if (!list) { flush(); list = WB.el('ul'); into.appendChild(list); } var l = WB.el('li'); inline(l, li[1]); list.appendChild(l); }
      else if (!ln.trim()) { flush(); }
      else { list = null; para.push(ln.trim()); }
    }
    flush();
  };

  WB.Notes = function (pane, store, initial) {
    var area = pane.querySelector('[data-notes-src]'), view = pane.querySelector('[data-notes-view]'), timer = 0, saved = null;
    area.value = WB.stripLicence(initial);
    saved = area.value;
    function render() { WB.markdown(area.value, view); if (!area.value.trim()) { view.appendChild(WB.el('p', 'text-muted-d', 'No notes yet. Say what this screen is for; the reviewer reads this with the proposal.')); } }
    function save() {
      timer = 0;
      if (area.value === saved) { return Promise.resolve(); }
      var text = area.value;
      return WB.call('PATCH', '/build/designs/' + encodeURIComponent(store.state.id), { notes: text }).then(function (r) {
        if (!r.ok) { store.emit('say', 'The notes were not saved: ' + WB.why(r), true); return; }
        saved = text; store.emit('say', 'Notes saved.');
      });
    }
    area.addEventListener('input', function () { render(); clearTimeout(timer); timer = setTimeout(save, 900); });
    area.addEventListener('blur', function () { if (timer) { clearTimeout(timer); save(); } });
    render();
    return { pending: function () { return !!timer; }, text: function () { return area.value; }, render: render, flush: function () { clearTimeout(timer); return save(); } };
  };
})();
