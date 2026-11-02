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
/*
 * Busy protection's calm message in place (docs/admin/OPERATIONS.md "Busy protection"; mock-up A). When the server asks new work to wait
 * (503, DRS-9501 to DRS-9505, Retry-After), the panel that asked says so where it is: what is busy, a countdown ring driven by the server's
 * Retry-After, Try now and Cancel, and it tries again by itself up to three times. Nothing else on the screen changes, and the console keeps
 * no list of waiting requests: the server decides, the page only counts down.
 *
 *   DrishtiBusy.problem(e)          the busy refusal in an error or a problem body, or null
 *   DrishtiBusy.render(host, p, o)  the message in host; o = { retry, cancel, auto (true), tries (3), cli }
 */
(function () {
  "use strict";
  var CODE = /^DRS-950[1-5]$/;
  var MAX_TRIES = 3;

  function num(v) { var n = parseInt(v, 10); return isNaN(n) || n < 1 ? 0 : n; }

  /** The busy refusal carried by an error (.body, .problem, the error itself) or a problem body; null when it is something else. */
  function problem(e) {
    if (!e) return null;
    var b = e.body || e.problem || e.extra || e;
    var code = b.code || e.code;
    if (!(b.busy || e.busy || CODE.test(String(code || "")))) return null;
    var detail = String(b.detail || e.detail || e.message || "").replace(/^DRS-\d{4}\s*/, "");
    return {
      code: code || "",
      title: b.title && !/^(busy|service unavailable)$/i.test(b.title) ? b.title : (detail.split(":")[0] || "The server is busy"),
      because: b.because || "",
      detail: detail,
      work: b.work || "",
      retryAfter: num(b.retryAfterSeconds) || num(e.retryAfter) || 30
    };
  }

  function el(tag, cls, text) {
    var x = document.createElement(tag);
    if (cls) x.className = cls;
    if (text !== undefined && text !== null) x.textContent = text;
    return x;
  }

  function clock(s) { return Math.floor(s / 60) + ":" + ("0" + (s % 60)).slice(-2); }

  function sentence(p) {
    var b = p.because ? p.because.charAt(0).toUpperCase() + p.because.slice(1) + "." : "";
    return b || p.detail;
  }

  /** The calm message in host; the countdown follows the server's Retry-After; tries again by itself up to three times. */
  function render(host, p, o) {
    o = o || {};
    if (!host) return;
    if (host.__busyTimer) clearInterval(host.__busyTimer);
    if (Date.now() - (host.__busyAt || 0) > 300000) host.__busyTries = 0;      // a refusal long after the last one starts afresh
    host.__busyAt = Date.now();
    var tries = host.__busyTries || 0;
    var auto = o.auto !== false && typeof o.retry === "function" && tries < (o.tries || MAX_TRIES);
    var total = p.retryAfter, left = total;
    host.textContent = "";
    var card = el("div", "busy-card");
    card.setAttribute("role", "status");
    card.setAttribute("aria-live", "polite");
    card.setAttribute("data-busy", p.code);
    var ring = el("div", "busy-ring");
    var ns = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 46 46");
    svg.setAttribute("aria-hidden", "true");
    var r = 19, len = 2 * Math.PI * r;
    [["busy-track", 0], ["busy-arc", 0]].forEach(function (c) {
      var ci = document.createElementNS(ns, "circle");
      ci.setAttribute("cx", "23"); ci.setAttribute("cy", "23"); ci.setAttribute("r", String(r));
      ci.setAttribute("class", c[0]);
      if (c[0] === "busy-arc") { ci.style.strokeDasharray = String(len); ci.style.strokeDashoffset = "0"; }
      svg.appendChild(ci);
    });
    var arc = svg.lastChild;
    var count = el("span", null, clock(left));
    ring.appendChild(svg);
    ring.appendChild(count);
    var body = el("div", "busy-body");
    body.appendChild(el("b", "busy-title", p.title));
    var text = el("p", "busy-text");
    body.appendChild(text);
    var actions = el("div", "busy-actions");
    var now = el("button", "btn-pill btn-accent", "Try now");
    now.type = "button";
    now.setAttribute("data-busy-try", "");
    actions.appendChild(now);
    var cancel = el("button", "btn-pill btn-ghost", "Cancel");
    cancel.type = "button";
    cancel.setAttribute("data-busy-cancel", "");
    if (typeof o.retry === "function") actions.appendChild(cancel); else now.hidden = true;
    if (o.cli) {
      var copy = el("button", "btn-pill btn-ghost", "Copy CLI line");
      copy.type = "button";
      copy.addEventListener("click", function () {
        if (navigator.clipboard) navigator.clipboard.writeText(o.cli).then(function () { copy.textContent = "Copied"; });
      });
      actions.appendChild(copy);
    }
    body.appendChild(actions);
    body.appendChild(el("small", "busy-ref", p.code + " · try again after " + total + " s"));
    card.appendChild(ring);
    card.appendChild(body);
    host.appendChild(card);

    function words() {
      text.textContent = "";
      text.appendChild(document.createTextNode(sentence(p) + " "));
      if (left > 0 && auto) {
        text.appendChild(document.createTextNode("We will try again in "));
        text.appendChild(el("b", null, left + " second" + (left === 1 ? "" : "s")));
        text.appendChild(document.createTextNode(o.keep ? "; " + o.keep : "."));
      } else if (left > 0) {
        text.appendChild(document.createTextNode("You can try again in "));
        text.appendChild(el("b", null, left + " second" + (left === 1 ? "" : "s")));
        text.appendChild(document.createTextNode("."));
      } else if (tries >= (o.tries || MAX_TRIES) && typeof o.retry === "function") {
        text.appendChild(document.createTextNode("We tried " + tries + " times and the server is still busy; try again when you are ready."));
      } else {
        text.appendChild(document.createTextNode("You can try again now."));
      }
      count.textContent = clock(Math.max(0, left));
      arc.style.strokeDashoffset = String(len * (1 - Math.max(0, left) / total));
    }
    function go() {
      clearInterval(host.__busyTimer);
      host.__busyTimer = null;
      host.__busyTries = tries + 1;
      now.disabled = true;
      cancel.disabled = true;
      text.textContent = "Trying again…";
      Promise.resolve(o.retry()).then(function () { if (!host.querySelector(".busy-card")) host.__busyTries = 0; });
    }
    now.addEventListener("click", function () { host.__busyTries = 0; tries = 0; go(); });
    cancel.addEventListener("click", function () {
      clearInterval(host.__busyTimer);
      host.__busyTimer = null;
      host.__busyTries = 0;
      if (typeof o.cancel === "function") { o.cancel(); return; }
      auto = false;
      left = 0;
      text.textContent = "Cancelled. Try now when you are ready.";
      cancel.hidden = true;
    });
    words();
    host.__busyTimer = setInterval(function () {
      if (!document.body.contains(card)) { clearInterval(host.__busyTimer); return; }
      left -= 1;
      if (left <= 0) {
        clearInterval(host.__busyTimer);
        host.__busyTimer = null;
        if (auto) { go(); return; }
        left = 0;
      }
      words();
    }, 1000);
  }

  /** Forgets the tries of host (a success). */
  function done(host) {
    if (!host) return;
    if (host.__busyTimer) clearInterval(host.__busyTimer);
    host.__busyTimer = null;
    host.__busyTries = 0;
  }

  window.DrishtiBusy = { problem: problem, render: render, done: done };
})();
