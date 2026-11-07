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
/* Keys: one way to name a key press ("Ctrl+Shift+ArrowLeft"), a key map from such names to actions, and the panel keys of the 12-column
   grid shared by layout mode (drishti_layout.js: the arrows move the panel) and the workbench canvas (build/drishti_canvas.js: the arrows
   select a panel and Alt+arrows move it, so a screen reader's and a plain Tab user's arrows keep their meaning). Everything here looks at
   the key and its modifiers only, never at the page, so it runs under Node. */

/** The workbench's scheme of the panel keys. */
export const WORKBENCH = 'workbench';
/** Layout mode's scheme of the panel keys. */
export const LAYOUT = 'layout';

/**
 * A panel key's meaning, or null:
 *   {type: 'span', delta}      width in columns             Shift+Left/Right, - and +
 *   {type: 'height', delta}    height in rows               Shift+Up/Down
 *   {type: 'natural'}          back to the content's height  A
 *   {type: 'move', step}       one place up (-1) or down (+1) in its column
 *   {type: 'column', area}     to the main or the side column
 *   {type: 'hide'}             layout mode: hide or show    H, Delete
 *   {type: 'remove'}           workbench: delete the panel  Delete
 *   {type: 'select', dir}      workbench: 'prev', 'next', 'main', 'side', 'first', 'last' (plain arrows, Home, End)
 * @param {{key: string, shiftKey: boolean, altKey: boolean, ctrlKey: boolean, metaKey: boolean}} e a KeyboardEvent (or its fields)
 * @param {string} scheme LAYOUT or WORKBENCH
 * @returns {?Object}
 */
export function interpretPanelKey(e, scheme) {
  const k = e.key, bench = scheme === WORKBENCH;
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
  const arrow = { ArrowUp: ['move', -1, 'prev'], ArrowDown: ['move', 1, 'next'], ArrowLeft: ['column', 'main', 'main'], ArrowRight: ['column', 'right', 'side'] }[k];
  if (arrow) {
    if (!bench || e.altKey) { return arrow[0] === 'move' ? { type: 'move', step: arrow[1] } : { type: 'column', area: arrow[1] }; }
    return { type: 'select', dir: arrow[2] };
  }
  if (bench && e.altKey) { return null; }
  if (bench && k === 'Home') { return { type: 'select', dir: 'first' }; }
  if (bench && k === 'End') { return { type: 'select', dir: 'last' }; }
  if (k === 'Delete' || (k === 'Backspace' && bench)) { return { type: bench ? 'remove' : 'hide' }; }
  if (!bench && (k === 'h' || k === 'H')) { return { type: 'hide' }; }
  return null;
}

/** The panel keys as the console's pages know them (window.Drishti.GridKeys). */
export const GridKeys = Object.freeze({ interpret: interpretPanelKey, WORKBENCH: WORKBENCH, LAYOUT: LAYOUT });

const ALIAS = { Esc: 'Escape', Spacebar: ' ', Left: 'ArrowLeft', Right: 'ArrowRight', Up: 'ArrowUp', Down: 'ArrowDown', Del: 'Delete' };
function keyPart(k) {
  k = ALIAS[k] || k;
  if (k === ' ') { return 'Space'; }
  return k.length === 1 ? k.toUpperCase() : k;
}

/**
 * A key press as one name: modifiers in the order Ctrl, Alt, Shift, Meta, then the key ("Ctrl+Shift+ArrowLeft", "Space", "A").
 * "Mod" in a key map stands for Ctrl, or Meta on a Mac.
 * @param {{key: string, ctrlKey: boolean, altKey: boolean, shiftKey: boolean, metaKey: boolean}} e
 * @returns {string}
 */
export function keyName(e) {
  const k = keyPart(e.key || '');
  const mods = [];
  if (e.ctrlKey && k !== 'Control') { mods.push('Ctrl'); }
  if (e.altKey && k !== 'Alt') { mods.push('Alt'); }
  if (e.shiftKey && k !== 'Shift') { mods.push('Shift'); }
  if (e.metaKey && k !== 'Meta') { mods.push('Meta'); }
  return mods.concat([k]).join('+');
}

/**
 * A name written by a person ("shift+left", "Mod+C") in keyName's form.
 * @param {string} name
 * @param {{mac: boolean}} [o] mac: Mod is Meta (default: Ctrl)
 * @returns {string}
 */
export function normalizeKey(name, o) {
  const parts = String(name).split('+').map((p) => p.trim()).filter(Boolean);
  const key = parts.pop() || '';
  const set = {};
  parts.forEach((p) => {
    const l = p.toLowerCase();
    if (l === 'mod') { set[o && o.mac ? 'Meta' : 'Ctrl'] = 1; }
    else if (l === 'ctrl' || l === 'control') { set.Ctrl = 1; }
    else if (l === 'alt' || l === 'option') { set.Alt = 1; }
    else if (l === 'shift') { set.Shift = 1; }
    else if (l === 'meta' || l === 'cmd' || l === 'command') { set.Meta = 1; }
    else { throw new Error('normalizeKey: no modifier ' + p + ' in ' + name); }
  });
  const lk = key.toLowerCase();
  const named = { left: 'ArrowLeft', right: 'ArrowRight', up: 'ArrowUp', down: 'ArrowDown', esc: 'Escape', escape: 'Escape', space: 'Space',
    enter: 'Enter', tab: 'Tab', home: 'Home', end: 'End', pageup: 'PageUp', pagedown: 'PageDown', delete: 'Delete', backspace: 'Backspace', f2: 'F2' };
  const k = named[lk] || keyPart(key);
  return ['Ctrl', 'Alt', 'Shift', 'Meta'].filter((m) => set[m]).concat([k]).join('+');
}

/**
 * A key map: names (in any case, "Mod" for Ctrl or Cmd) to actions; the returned function gives a key press's action, or undefined.
 * @param {Object<string, *>} bindings for example {'Mod+C': 'copy', 'Shift+ArrowDown': 'extendDown'}
 * @param {{mac: boolean}} [o]
 * @returns {function(KeyboardEvent): *}
 * @example const act = keymap({'Mod+A': 'selectAll'}); act({key: 'a', ctrlKey: true})   // 'selectAll'
 */
export function keymap(bindings, o) {
  const m = {};
  Object.keys(bindings).forEach((k) => { m[normalizeKey(k, o)] = bindings[k]; });
  return (e) => m[keyName(e)];
}
