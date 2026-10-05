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
/* Drawing a discussion (COLLABORATION.md, Discussion): threads and comments as DOM, from the server's JSON, for the Discussion tab of the
   side drawer (discussion.js). Everything the server sent is put in with textContent: a comment's text arrives already scrubbed for THIS
   reader (masked values as •••, {$.path} quotes filled from their view), and nothing here can turn it into markup. Buttons carry data-act;
   discussion.js handles them in one delegated listener. DrishtiThreadView.thread(t, ctx) / .comment(c, t, ctx); ctx = {me, admin, pageGen, pageAsOf}. */
(function () {
  'use strict';
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) { n.className = cls; }
    if (text != null) { n.textContent = text; }
    return n;
  }
  function btn(label, act, extra) {
    var b = el('button', 'fk disc-b', label);
    b.type = 'button'; b.setAttribute('data-act', act);
    if (extra) { Object.keys(extra).forEach(function (k) { b.setAttribute(k, extra[k]); }); }
    return b;
  }
  function when(iso) { return iso ? String(iso).slice(0, 16).replace('T', ' ') : ''; }
  function day(d) { var m = /^(\d{4})-(\d\d)-(\d\d)/.exec(d || ''); return m ? (+m[3]) + ' ' + MONTHS[+m[2] - 1] : (d || ''); }

  // the pin: which data the writer saw ("gen 1702 · 2 Oct"); live comments say so
  function pinText(pin) {
    if (!pin) { return ''; }
    return 'gen ' + pin.generation + (pin.businessDate ? ' · ' + day(pin.businessDate) : '') + (pin.live ? ' (live)' : '');
  }
  // whether the page shows other data than the comment saw (then *Open as it was* is offered)
  function differs(pin, ctx) {
    if (!pin) { return false; }
    var date = !pin.live && pin.businessDate && (ctx.pageAsOf === 'live' || (ctx.pageAsOf && ctx.pageAsOf !== pin.businessDate));
    return !!date || (pin.generation && ctx.pageGen && String(pin.generation) !== String(ctx.pageGen));
  }

  function parts(c) {
    var p = el('p', 'disc-text');
    (c.parts || [{ t: 'text', v: c.body || '' }]).forEach(function (x) {
      if (x.t === 'mention') {
        var m = el('span', 'disc-mention' + (x.target && x.target.indexOf('role:') === 0 ? ' role' : ''), x.v);
        m.setAttribute('aria-label', 'mention of ' + String(x.v).replace(/^@/, ''));
        p.appendChild(m);
      } else if (x.t === 'quote') {
        var q = el('button', 'disc-quote mono', x.v);
        q.type = 'button'; q.setAttribute('data-act', 'goto'); q.setAttribute('data-path', x.path || '');
        q.setAttribute('aria-label', 'value of ' + (x.path || 'a field') + ': ' + x.v + '. Show it on the page');
        p.appendChild(q);
      } else { p.appendChild(document.createTextNode(x.v)); }
    });
    return p;
  }

  function comment(c, t, ctx) {
    var live = !c.state || c.state === 'live';
    var li = el('li', 'disc-c' + (live ? '' : ' ' + c.state) + (c.mine ? ' mine' : ''));
    li.setAttribute('data-comment', c.id); li.setAttribute('data-revision', c.revision);
    var h = el('div', 'disc-ch');
    h.appendChild(el('b', null, c.authorName || c.author));
    var tm = el('time', 'mono', when(c.createdAt)); tm.setAttribute('datetime', c.createdAt || '');
    h.appendChild(tm);
    if (c.pin) { h.appendChild(el('span', 'disc-pin mono', pinText(c.pin))); }
    li.appendChild(h);
    if (c.state === 'retracted') {
      li.appendChild(el('p', 'disc-gone', 'Retracted by the author' + (c.editedAt ? ', ' + when(c.editedAt) : '')));
    } else if (c.state === 'hidden') {
      li.appendChild(el('p', 'disc-gone', 'Hidden by a moderator' + (c.stateReason ? ': ' + c.stateReason : '')));
    }
    if (live || c.body != null) { li.appendChild(parts(c)); }      // a moderator still reads what was hidden or retracted
    var tools = el('div', 'disc-tools');
    if (c.edited) { tools.appendChild(btn('(edited)', 'history', { title: 'Show the earlier versions' })); }
    if (c.pin && c.href && differs(c.pin, ctx)) {
      var a = el('a', 'fk disc-b', 'Open as it was'); a.href = c.href;
      a.title = 'Open this page as the writer saw it (' + pinText(c.pin) + ')';
      tools.appendChild(a);
    }
    if (c.editable) { tools.appendChild(btn('Edit', 'edit')); }
    if (c.mine && live) { tools.appendChild(btn('Retract', 'retract')); }
    if (ctx.admin && live) { tools.appendChild(btn('Hide', 'hide', { title: 'Hide this comment from readers (moderator)' })); }
    if (ctx.admin && c.state === 'hidden') { tools.appendChild(btn('Unhide', 'unhide')); }
    if (tools.children.length) { li.appendChild(tools); }
    return li;
  }

  function thread(t, ctx) {
    var d = el('details', 'disc-t ' + (t.state || 'open'));
    d.setAttribute('data-thread', t.id);
    if (t.state !== 'resolved') { d.open = true; }
    var s = el('summary');
    s.appendChild(el('span', 'disc-t-name', t.label || (t.anchor === 'panel' ? t.panel : t.anchor === 'field' ? t.path : 'Whole view')));
    s.appendChild(el('span', 'disc-t-meta', ' · ' + (t.anchor === 'entity' ? 'whole view' : t.anchor) + ' · ' + t.comments + (t.comments === 1 ? ' comment' : ' comments')
      + (t.state && t.state !== 'open' ? ' · ' + t.state : '')));
    d.appendChild(s);
    var ol = el('ol', 'disc-cs');
    (t.items || []).forEach(function (c) { ol.appendChild(comment(c, t, ctx)); });
    d.appendChild(ol);
    if (t.more) { d.appendChild(btn('Earlier comments are not shown', 'noop', { disabled: 'disabled' })); }
    var tools = el('div', 'disc-tools disc-ttools');
    if (t.state !== 'locked') { tools.appendChild(btn(t.state === 'resolved' ? 'Reopen' : 'Resolve', 'state', { 'data-to': t.state === 'resolved' ? 'open' : 'resolved' })); }
    if (ctx.admin) { tools.appendChild(btn(t.state === 'locked' ? 'Unlock' : 'Lock', 'state', { 'data-to': t.state === 'locked' ? 'open' : 'locked' })); }
    var f = btn(t.following ? 'Following' : 'Follow', 'follow', { 'aria-pressed': t.following ? 'true' : 'false' });
    tools.appendChild(f);
    if (t.following) { tools.appendChild(btn(t.muted ? 'Unmute' : 'Mute', 'mute', { 'aria-pressed': t.muted ? 'true' : 'false' })); }
    d.appendChild(tools);
    if (t.state === 'locked') { d.appendChild(el('p', 'disc-gone', 'Locked by a moderator: no new comments.')); }
    else if (ctx.canWrite) {
      var form = el('form', 'disc-reply'); form.setAttribute('data-reply', t.id);
      var lab = el('label', 'sr-only', 'Reply to ' + (t.label || 'this thread')); lab.setAttribute('for', 'discReply-' + t.id);
      var ta = el('textarea', 'studio-in'); ta.id = 'discReply-' + t.id; ta.rows = 2; ta.placeholder = 'Reply… @ to mention, { to quote a value'; ta.maxLength = ctx.maxText || 2000;
      var lst = el('ul', 'shr-list disc-opts'); lst.id = 'discReplyList-' + t.id; lst.hidden = true; lst.setAttribute('role', 'listbox'); lst.setAttribute('aria-label', 'Suggestions');
      var send = el('button', 'btn-pill btn-accent btn-sm-pill', 'Reply'); send.type = 'submit';
      var wrap = el('div', 'disc-box'); wrap.appendChild(ta); wrap.appendChild(lst);
      form.appendChild(lab); form.appendChild(wrap); form.appendChild(send);
      d.appendChild(form);
    }
    return d;
  }

  window.DrishtiThreadView = { thread: thread, comment: comment, el: el, btn: btn, when: when };
})();
