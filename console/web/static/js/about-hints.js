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
/* About this page, panel and field level (docs/architecture/CONTEXT_HELP.md): the `?` on a panel header opens a small popover (the
   panel's own text, what its fields mean, why it is empty or inferred, and two links), and a strip label, kv label or table
   header whose field has a glossary entry gets a dotted underline and a tooltip on hover (after 400 ms) or focus. Enter on such a
   label opens the drawer at its entry; on a phone a tap pins the tooltip. Everything comes from the drawer's one fetch
   (about.js: window.drishtiAbout), never from the view's own JSON, and every word is drawn with textContent. Without JavaScript
   the `?` stays a link to the panel kind's guide, as before. */
(function () {
  'use strict';
  var api = window.drishtiAbout;
  if (!api || !document.querySelector('[data-view]')) { return; }
  var HOVER_MS = 400;
  var tip = null, tipFor = null, pinned = false, hoverTimer = null, pop = null, popFor = null, pointer = '';
  var terms = {};                                                         // field key -> entry

  function h(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) { n.className = cls; }
    if (text !== undefined && text !== null) { n.textContent = text; }
    return n;
  }
  function visible(n) { return !!n && !n.hidden; }

  // ---- what an entry says -------------------------------------------------------------------------------------------
  function lines(t, into) {
    if (t.means) { into.appendChild(h('p', 'about-pop-means', t.means)); }
    var u = [];
    if (t.unit) { u.push('Unit: ' + t.unit); }
    if (t.sign) { u.push('Sign: ' + t.sign); }
    if (u.length) { into.appendChild(h('p', 'about-pop-unit', u.join(' · '))); }
    if (t.formula) {
      var f = h('p', 'about-pop-unit', 'Formula: ');
      f.appendChild(h('code', null, t.formula));
      into.appendChild(f);
    }
    if (t.values) { Object.keys(t.values).forEach(function (k) { into.appendChild(h('p', 'about-pop-unit', k + ': ' + t.values[k])); }); }
    if (t.masked) { into.appendChild(h('p', 'about-hidden', 'Hidden for your role')); }
  }

  // ---- placement: below the opener, flipped above when there is no room, clamped to the window ----------------------
  function place(box, anchor) {
    var r = anchor.getBoundingClientRect();
    box.style.left = '0px'; box.style.top = '0px';
    var w = box.offsetWidth, hh = box.offsetHeight;
    var x = Math.min(Math.max(8, r.left), Math.max(8, window.innerWidth - w - 8));
    var y = r.bottom + 6;
    if (y + hh > window.innerHeight - 8) { y = Math.max(8, r.top - hh - 6); }
    box.style.left = x + 'px'; box.style.top = y + 'px';
  }

  // ---- field tooltips -----------------------------------------------------------------------------------------------
  function ensureTip() {
    if (!tip) {
      tip = h('div', 'about-tip'); tip.id = 'aboutTip'; tip.setAttribute('role', 'tooltip'); tip.hidden = true;
      document.body.appendChild(tip);
    }
    return tip;
  }
  function hideTip() {
    clearTimeout(hoverTimer); hoverTimer = null;
    if (tip) { tip.hidden = true; }
    if (tipFor) { tipFor.removeAttribute('aria-describedby'); }
    tipFor = null; pinned = false;
  }
  function showTip(el, pin) {
    var t = terms[el.getAttribute('data-gloss')];
    if (!t) { return; }
    hideTip();
    var box = ensureTip();
    box.textContent = '';
    box.appendChild(h('p', 'about-pop-term', t.term || t.label));
    lines(t, box);
    if (pin) {
      var more = h('button', 'about-more', 'More in About this page'); more.type = 'button';
      more.addEventListener('click', function () { var k = el.getAttribute('data-gloss'); hideTip(); api.reveal({ term: k }); });
      box.appendChild(more);
    }
    box.hidden = false; place(box, el);
    el.setAttribute('aria-describedby', 'aboutTip');
    tipFor = el; pinned = !!pin;
  }
  function glossOf(e) { return e.target && e.target.closest ? e.target.closest('.about-gloss') : null; }

  document.addEventListener('mouseover', function (e) {
    var g = glossOf(e);
    if (!g || g === tipFor) { return; }
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(function () { showTip(g, false); }, HOVER_MS);
  });
  document.addEventListener('mouseout', function (e) {
    var g = glossOf(e);
    if (!g) { return; }
    clearTimeout(hoverTimer); hoverTimer = null;
    if (!pinned && g === tipFor) { hideTip(); }
  });
  document.addEventListener('focusin', function (e) { var g = glossOf(e); if (g) { showTip(g, false); } });
  document.addEventListener('focusout', function (e) { var g = glossOf(e); if (g && !pinned && g === tipFor) { hideTip(); } });
  document.addEventListener('pointerdown', function (e) { pointer = e.pointerType || ''; }, true);
  document.addEventListener('scroll', function () { if (tipFor) { hideTip(); } }, true);
  document.addEventListener('keydown', function (e) {
    var g = glossOf(e);
    if (g && e.key === 'Enter' && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault(); e.stopPropagation();
      var k = g.getAttribute('data-gloss'); hideTip(); api.reveal({ term: k });
    }
  }, true);

  // ---- panel popover ------------------------------------------------------------------------------------------------
  function closePop(restore) {
    if (!visible(pop)) { return; }
    pop.hidden = true;
    var a = popFor; popFor = null;
    if (a) { a.setAttribute('aria-expanded', 'false'); if (restore && document.contains(a)) { a.focus({ preventScroll: true }); } }
  }
  function buildPop(a, idx) {
    var sec = a.closest('section[data-panel]');
    if (!sec) { return false; }
    var id = sec.getAttribute('data-panel'), kind = sec.getAttribute('data-kind');
    var title = ((sec.querySelector('h3') || {}).textContent || id).trim();
    var own = (idx.panels || {})[id] || {};
    var mine = (idx.terms || []).filter(function (t) { return (t.shownIn || []).indexOf(id) >= 0; });
    if (!pop) {
      pop = h('div', 'about-pop'); pop.setAttribute('role', 'dialog'); pop.tabIndex = -1; pop.hidden = true;
      document.body.appendChild(pop);
    }
    pop.textContent = '';
    pop.setAttribute('aria-label', 'About ' + title);
    pop.appendChild(h('h4', 'about-pop-title', title));
    if (own.about) { pop.appendChild(h('p', 'about-pop-means', own.about)); }
    (own.notes || []).forEach(function (n) { pop.appendChild(h('p', 'about-pop-note', n)); });
    if (mine.length) {
      var dl = h('dl', 'about-pop-terms');
      mine.forEach(function (t) {
        dl.appendChild(h('dt', null, (t.labels && t.labels[0]) || t.label || t.key));
        var dd = h('dd'); lines(t, dd); dl.appendChild(dd);
      });
      pop.appendChild(dl);
    }
    var links = h('p', 'about-pop-links');
    var more = h('button', 'about-more', 'About this page'); more.type = 'button';
    more.addEventListener('click', function () { closePop(false); api.reveal({ panel: id }); });
    links.appendChild(more);
    var guide = h('a', null, 'The ' + kind + ' panel'); guide.href = a.getAttribute('href');
    links.appendChild(guide);
    pop.appendChild(links);
    pop.hidden = false; popFor = a;
    a.setAttribute('aria-expanded', 'true');
    place(pop, a);
    pop.focus({ preventScroll: true });
    return true;
  }
  function togglePop(a) {
    if (visible(pop) && popFor === a) { closePop(true); return; }
    closePop(false); hideTip();
    api.ensure().then(function (idx) {
      if (!idx || !buildPop(a, idx)) { location.href = a.getAttribute('href'); }       // nothing to say: today's link
    });
  }
  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[data-about-help]');
    if (a) { e.preventDefault(); togglePop(a); return; }
    if (visible(pop) && !pop.contains(e.target)) { closePop(false); }
    if (pinned && tip && !tip.contains(e.target) && !glossOf(e)) { hideTip(); }
    var g = glossOf(e);
    if (g && pointer === 'touch') { if (pinned && tipFor === g) { hideTip(); } else { showTip(g, true); } }   // a tap pins the tooltip
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && visible(pop)) { e.preventDefault(); e.stopPropagation(); closePop(true); return; }
    if (e.key === 'Escape' && tipFor) { var f = tipFor; hideTip(); if (f.focus) { f.focus({ preventScroll: true }); } return; }
    var a = e.target.closest && e.target.closest('a[data-about-help]');
    if (a && e.key === ' ') { e.preventDefault(); togglePop(a); }
  }, true);
  document.addEventListener('focusin', function (e) {
    if (visible(pop) && !pop.contains(e.target) && e.target !== popFor) { closePop(false); }
  });

  // ---- which labels have an entry -----------------------------------------------------------------------------------
  function text(n) { return (n.textContent || '').replace(/\s+/g, ' ').trim(); }
  function mark(el, scope, labels) {
    var t = labels[text(el)];
    if (!t || el.hasAttribute('data-gloss')) { return; }
    el.classList.add('about-gloss');
    el.setAttribute('data-gloss', t.key);
    if (!el.hasAttribute('tabindex')) { el.setAttribute('tabindex', '0'); }
  }
  function decorate(idx) {
    terms = {};
    var scopes = {};
    ((idx && idx.terms) || []).forEach(function (t) {
      terms[t.key] = t;
      (t.shownIn || []).forEach(function (s) {
        var m = scopes[s] || (scopes[s] = {});
        (t.labels && t.labels.length ? t.labels : [t.label]).forEach(function (l) { m[l] = t; });
      });
    });
    if (scopes.strip) { document.querySelectorAll('dl.strip .strip-i dt').forEach(function (el) { mark(el, 'strip', scopes.strip); }); }
    document.querySelectorAll('section[data-panel]').forEach(function (sec) {
      var m = scopes[sec.getAttribute('data-panel')];
      if (m) { sec.querySelectorAll('.kv-item dt, dl.rows dt, thead th').forEach(function (el) { mark(el, sec.getAttribute('data-panel'), m); }); }
    });
  }

  document.querySelectorAll('section[data-panel] a.pnl-help[href^="/help/panel-kinds"]').forEach(function (a) {
    a.setAttribute('data-about-help', '');
    a.setAttribute('role', 'button'); a.setAttribute('aria-haspopup', 'dialog'); a.setAttribute('aria-expanded', 'false');
    a.setAttribute('title', 'About this panel');
  });
  document.addEventListener('drishti:about', function (e) { decorate(e.detail); });
  // The labels need the answer, so it is fetched once the page is idle (the drawer and the popovers share it).
  (window.requestIdleCallback || function (f) { return setTimeout(f, 200); })(function () { api.ensure(); });
})();
