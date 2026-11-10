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
/* The one "Explain ›" control (VAR_GUIDE.md section 3.11): every figure of a measure with an explanation carries it, a guard test
   says where (drishti-console/tests/test_var_explain_everywhere.py). A link to the number's full page at Why it moved; a click (or E when
   it is focused) opens the drawer in place instead, through the Explain's own click handler (data-explain-trigger). */

const TITLE = 'Explain: the formula with its values, the inputs and lineage, why it moved, the workbook (E)';

/**
 * The control for a number's request.
 * @param {Document} doc
 * @param {Object} req the Explain request ({kind, measure, scope, ...})
 * @param {{cls?: string, text?: string, tab?: string, title?: string}} [o]
 * @param {function(Object, string): string} pageUrl the full page's address of a request at a tab
 * @returns {HTMLAnchorElement}
 */
export function explainLink(doc, req, o, pageUrl) {
  o = o || {};
  const a = doc.createElement('a');
  a.className = 'dx-link' + (o.cls ? ' ' + o.cls : '');
  a.textContent = o.text || 'Explain ›';
  a.href = pageUrl(req, o.tab || 'why');
  a.setAttribute('data-explain', JSON.stringify(req));
  a.setAttribute('data-explain-tab', o.tab || 'why');
  a.setAttribute('data-explain-trigger', '');
  a.setAttribute('data-explain-link', '');
  a.title = o.title || TITLE;
  return a;
}

/**
 * Each [data-explain-link] under root that carries its request in data-explain made the control (its class, link, text and title).
 * @param {ParentNode} root
 * @param {function(Object, string): string} pageUrl
 * @returns {void}
 */
export function upgradeLinks(root, pageUrl) {
  root.querySelectorAll('[data-explain-link][data-explain]:not(.dx-link)').forEach((el) => {
    let req = null;
    try { req = JSON.parse(el.getAttribute('data-explain') || '{}'); } catch (e) { req = null; }
    if (!(req && req.kind)) { return; }
    const tab = el.getAttribute('data-explain-tab') || 'why';
    el.classList.add('dx-link');
    el.setAttribute('data-explain-trigger', '');
    el.setAttribute('data-explain-tab', tab);
    if (el.tagName === 'A') { el.href = pageUrl(req, tab); }
    if (!el.textContent.trim()) { el.textContent = 'Explain ›'; }
    if (!el.title) { el.title = TITLE; }
  });
}

/**
 * A sentence of the commentary and, when it has one, the list that opens under it (further pages from the server, as they are asked for).
 * @param {Document} doc
 * @param {Element} host
 * @param {Object} n {text, list}
 * @returns {void}
 */
export function drawNote(doc, host, n) {
  const h = (tag, cls, text) => { const e = doc.createElement(tag); if (cls) { e.className = cls; } if (text !== undefined && text !== null) { e.textContent = text; } return e; };
  host.appendChild(h("p", "dx-note", n.text));
  var list = n.list;
  if (!list) return;
  var det = h("details", "dx-details"), t = h("table", "dx-table"), head = h("tr"), tb = h("tbody"), th = h("thead"), page = 0;
  det.appendChild(h("summary", null, list.summary));
  list.columns.forEach(function (c) { head.appendChild(h("th", null, c)); });
  th.appendChild(head);
  t.appendChild(th);
  t.appendChild(tb);
  det.appendChild(t);
  function add(rows) {
    rows.forEach(function (r) {
      var tr = h("tr");
      r.cells.forEach(function (c, i) {
        var td = h("td", "mono");
        if (i === 0 && r.href) { var a = h("a", null, c); a.href = r.href; td.appendChild(a); } else td.textContent = c;
        tr.appendChild(td);
      });
      tb.appendChild(tr);
    });
  }
  add(list.rows || []);
  if (list.moreHref) {
    var more = h("button", "dx-more", "Show more");
    more.type = "button";
    more.addEventListener("click", function () {
      page += 1;
      fetch(list.moreHref + "&page=" + page).then(function (r) { return r.json(); }).then(function (b) {
        add((b.rows || []).map(function (r) {
          return { cells: [r.position, (r.trades || []).map(function (x) { return x.trade + " (" + x.maturity + ")"; }).join(", ")],
            href: "/v/position/" + encodeURIComponent(r.position) + "?asOf=" + encodeURIComponent(b.businessDate) };
        }));
        more.hidden = !b.more;
      });
    });
    det.appendChild(more);
  }
  host.appendChild(det);
}
