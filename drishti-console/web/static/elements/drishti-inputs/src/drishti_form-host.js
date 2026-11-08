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
/* A form the server drew as a schema: an element with data-di-form and data-schema ({schema, value, options}) becomes a Form, embedded by
   default (its named fields submitted by the page's own <form>, or read by the page's script through the Form). The server builds the
   schema (core/form_kit.py), so a page has no controls of its own; its script asks formOf(host) for the Form, whichever runs first. An
   embedded Form stops its page form's submit while a field has a problem (checkOnSubmit: false leaves that to the page). */
import { Form } from './drishti_form.js';

/** The attribute that marks a form host. */
export const FORM_HOST_SELECTOR = '[data-di-form][data-schema]';

/**
 * The Form of a host the server drew (made at the first call; later calls answer the same Form).
 * @param {HTMLElement} host an element with data-schema: {schema, value, options}
 * @param {Object} [extra] options added by the page's script (onSubmit, widgets, check)
 * @returns {Form|null}
 */
export function formOf(host, extra) {
  if (!host) { return null; }
  if (host.diForm) { return host.diForm; }
  let spec;
  try { spec = JSON.parse(host.getAttribute('data-schema') || 'null'); } catch (e) { spec = null; }
  if (!spec || !spec.schema) { return null; }
  const opts = Object.assign({ embedded: true }, spec.options || {}, extra || {}, { schema: spec.schema, value: spec.value });
  const form = host.diForm = new Form(host, opts);
  const outer = opts.embedded && host.closest ? host.closest('form') : null;
  if (outer && opts.checkOnSubmit !== false) {               // the page's form is not sent while a field has a problem (a native control's required did that)
    outer.addEventListener('submit', (e) => { if (!form.validate().ok) { e.preventDefault(); e.stopImmediatePropagation(); form.focusField(Object.keys(form.errors)[0]); } }, true);
  }
  return form;
}

/**
 * Every form host under a root made a Form (drishti_app.js does it for each page, with upgradeFields).
 * @param {(Document|HTMLElement)} root
 * @returns {Form[]}
 */
export function upgradeForms(root) {
  if (!root || !root.querySelectorAll) { return []; }
  return Array.from(root.querySelectorAll(FORM_HOST_SELECTOR)).map((h) => formOf(h)).filter(Boolean);
}
