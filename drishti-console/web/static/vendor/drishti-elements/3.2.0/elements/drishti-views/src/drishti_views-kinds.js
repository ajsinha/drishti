/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The registry of panel kinds: a kind's painter draws a panel's data into the body of the frame drishti_views-panels.js made.
   The built-in kinds register here as they are written (VIEWS_DESIGN step 4 on); a kind with no painter is drawn as a titled panel
   that says so (the specification's "unknown is ignored"), never as a blank or a thrown error. */

/**
 * @typedef {Object} KindPainter
 * @property {function(HTMLElement, Object, Object): ?{update: function(Object): void, dispose: function(): void}} paint
 *   draws panel.data into the body (the element, the panel, the paint's context); answers what updates and disposes it, or nothing
 * @property {boolean} [chart] true for a kind whose drawing is a picture (it keeps a data table as its text alternative)
 */

const KINDS = new Map();

/**
 * Registers the painter of a kind (a built-in kind, or a host's own; the last registration of a name wins).
 * @param {string} name
 * @param {KindPainter} def
 * @returns {void}
 */
export function defineKind(name, def) {
  if (typeof name !== 'string' || !/^[a-z][a-z0-9-]{0,39}$/.test(name)) { throw new Error('defineKind: a kind is a lower-case word ("seat-map"), not ' + JSON.stringify(name)); }
  if (!def || typeof def.paint !== 'function') { throw new Error('defineKind: the kind ' + name + ' needs a paint(body, panel, ctx) function'); }
  KINDS.set(name, Object.freeze(Object.assign({}, def)));
}

/**
 * The painter of a kind, or null when none is registered.
 * @param {string} name
 * @returns {?KindPainter}
 */
export function kindOf(name) { return KINDS.get(name) || null; }

/**
 * The kinds that have a painter, in the order they were registered.
 * @returns {string[]}
 */
export function kinds() { return Array.from(KINDS.keys()); }
