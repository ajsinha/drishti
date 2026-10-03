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
/* The keys that move and size a panel on the 12-column grid, one table for everyone who has panels to move: layout mode
 * (layout.js, where the arrows themselves move the panel) and the workbench canvas (build/canvas.js, where the arrows select a
 * panel and Alt+arrows move it, so a screen reader's and a plain Tab user's arrows keep their meaning).
 *
 *   DrishtiGridKeys.interpret(event, scheme) -> null or an action:
 *     {type: 'span', delta}      width in columns            Shift+Left/Right, - and +
 *     {type: 'height', delta}    height in rows              Shift+Up/Down
 *     {type: 'natural'}          back to the content's height A
 *     {type: 'move', step}       one place up (-1) or down (+1) in its column
 *     {type: 'column', area}     to the main or the side column
 *     {type: 'hide'}             layout mode: hide or show   H, Delete
 *     {type: 'remove'}           workbench: delete the panel Delete
 *     {type: 'select', dir}      workbench: 'prev', 'next', 'main', 'side', 'first', 'last' (plain arrows, Home, End)
 *
 * It looks at the key and the modifiers only, never at the page, so it runs under Node as well (tests). */
(function (root) {
  'use strict';
  var WORKBENCH = 'workbench';

  function interpret(e, scheme) {
    var k = e.key, bench = scheme === WORKBENCH;
    if (e.ctrlKey || e.metaKey) { return null; }
    if (e.shiftKey && !e.altKey) {
      if (k === 'ArrowLeft') { return { type: 'span', delta: -1 }; }
      if (k === 'ArrowRight') { return { type: 'span', delta: 1 }; }
      if (k === 'ArrowUp') { return { type: 'height', delta: -1 }; }
      if (k === 'ArrowDown') { return { type: 'height', delta: 1 }; }
    }
    if (e.shiftKey) { return null; }
    if (k === '-' || k === '_') { return { type: 'span', delta: -1 }; }
    if (k === '+' || k === '=') { return { type: 'span', delta: 1 }; }
    if (k === 'a' || k === 'A') { return { type: 'natural' }; }
    var arrow = { ArrowUp: ['move', -1, 'prev'], ArrowDown: ['move', 1, 'next'], ArrowLeft: ['column', 'main', 'main'], ArrowRight: ['column', 'right', 'side'] }[k];
    if (arrow) {
      if (!bench || e.altKey) { return arrow[0] === 'move' ? { type: 'move', step: arrow[1] } : { type: 'column', area: arrow[1] }; }
      return { type: 'select', dir: arrow[2] };
    }
    if (bench && e.altKey) { return null; }
    if (bench && k === 'Home') { return { type: 'select', dir: 'first' }; }
    if (bench && k === 'End') { return { type: 'select', dir: 'last' }; }
    if (k === 'Delete' || k === 'Backspace' && bench) { return { type: bench ? 'remove' : 'hide' }; }
    if (!bench && (k === 'h' || k === 'H')) { return { type: 'hide' }; }
    return null;
  }

  var api = { interpret: interpret, WORKBENCH: WORKBENCH, LAYOUT: 'layout' };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; } else { root.DrishtiGridKeys = api; }
})(typeof window !== 'undefined' ? window : this);
