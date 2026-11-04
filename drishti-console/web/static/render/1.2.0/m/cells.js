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
/* Cells, fields and entity links: the macros cell_text, tone, fields and entity of _macros/panels.html, as DOM. */
import { h, str } from './dom.js';
import { toneClass } from './format.js';

const enc = (s) => encodeURIComponent(String(s == null ? '' : s));

/** The href of an entity: /v/<kind>/<id> on the configured server (the id's own slashes kept, as the console's route takes a path). */
export function entityHref(R, link) {
  return (R.server || '') + '/v/' + enc(link.kind) + '/' + String(link.id == null ? '' : link.id).split('/').map(enc).join('/');
}

/** An entity link (class lnk) with the macros' attributes, carrying its ref for in-place navigation. */
export function entityLink(R, link, label, cls = 'lnk') {
  return h('a', { class: cls, href: entityHref(R, link), 'data-r-link': 'entity', 'data-r-kind': link.kind, 'data-r-id': link.id }, label);
}

/** cell_text(c): an inactive value, an entity link, a bare http(s) URL as an outside link, or the text. */
export function cellText(R, c) {
  c = c || {};
  if (c.tone === 'inactive') {
    return [h('span', { class: 'lnk-off', title: 'Not active: an administrator turned this command off' }, str(c.text)), ' ',
      h('span', { class: 'lk-badge lk-off' }, 'not active')];
  }
  if (c.link) { return entityLink(R, c.link, str(c.text)); }
  const t = c.text;
  if (typeof t === 'string' && (t.startsWith('https://') || t.startsWith('http://')) && !t.includes(' ')) {
    // a page outside Drishti cannot be shown in place: by default a click is a drishti:navigate event for the host (nav.js);
    // a new tab only when the host asks for one (outsideLinks: 'new-window'), as the console's macros do
    return h('a', { class: 'lnk ext', href: t, target: R.outsideLinks === 'new-window' ? '_blank' : null, rel: 'noopener noreferrer nofollow', title: t, 'data-r-link': 'outside' }, t);
  }
  return str(t);
}

/** The macro's entity(text, link): a link when the link names a kind and an id, else the text. */
export function entity(R, label, link) {
  return link && link.kind && link.id ? entityLink(R, link, str(label)) : str(label);
}

/** fields(list, cols): a definition list of label and value, four columns in main and two in right. */
export function fields(R, list, cols = 4) {
  return h('dl', { class: 'kv kv-' + cols }, (list || []).map((f) => h('div', { class: 'kv-item' },
    h('dt', null, str(f.label)),
    h('dd', { class: 'mono' + toneClass(f), 'data-path': f.path || null }, cellText(R, f)))));
}
