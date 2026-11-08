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
/* The designer's keys, one map for every canvas: what a key press on a selected item means. Two schemes of the arrows, as people
   already know them: "move" (a report page, layout mode: the arrows move the item, Shift+arrows size it) and "select" (the workbench:
   the arrows pick the next item, Alt+arrows move it, so a screen reader's arrows keep their meaning). The rest is common: undo, redo,
   copy, cut, paste, duplicate, delete, select all, group, z-order, Enter to edit, Escape to let go. A host may add or replace bindings. */
import { keymap } from '../../drishti-core/src/drishti_index.js';

/**
 * An action of a canvas key.
 * @typedef {Object} DesignerAction
 * @property {string} type move | size | select | undo | redo | copy | cut | paste | duplicate | delete | selectAll | group | ungroup |
 *   zorder | open | escape | natural | column
 * @property {number} [dx]
 * @property {number} [dy]
 * @property {string} [dir] select: prev, next, first, last, main, side
 * @property {string} [how] zorder: front, back, forward, backward
 * @property {string} [area] column: main or right
 */

const COMMON = {
  'Mod+Z': { type: 'undo' }, 'Mod+Y': { type: 'redo' }, 'Mod+Shift+Z': { type: 'redo' },
  'Mod+C': { type: 'copy' }, 'Mod+X': { type: 'cut' }, 'Mod+V': { type: 'paste' }, 'Mod+D': { type: 'duplicate' },
  'Delete': { type: 'delete' }, 'Backspace': { type: 'delete' }, 'Mod+A': { type: 'selectAll' },
  'Mod+G': { type: 'group' }, 'Mod+Shift+G': { type: 'ungroup' },
  'Mod+]': { type: 'zorder', how: 'forward' }, 'Mod+[': { type: 'zorder', how: 'backward' },
  'Mod+Shift+]': { type: 'zorder', how: 'front' }, 'Mod+Shift+[': { type: 'zorder', how: 'back' },
  'Enter': { type: 'open' }, 'F2': { type: 'open' }, 'Escape': { type: 'escape' }
};

const MOVE = {
  'ArrowLeft': { type: 'move', dx: -1, dy: 0 }, 'ArrowRight': { type: 'move', dx: 1, dy: 0 }, 'ArrowUp': { type: 'move', dx: 0, dy: -1 }, 'ArrowDown': { type: 'move', dx: 0, dy: 1 },
  'Shift+ArrowLeft': { type: 'size', dx: -1, dy: 0 }, 'Shift+ArrowRight': { type: 'size', dx: 1, dy: 0 }, 'Shift+ArrowUp': { type: 'size', dx: 0, dy: -1 }, 'Shift+ArrowDown': { type: 'size', dx: 0, dy: 1 },
  'Tab': { type: 'select', dir: 'next' }, 'Shift+Tab': { type: 'select', dir: 'prev' }
};

const SELECT = {
  'ArrowUp': { type: 'select', dir: 'prev' }, 'ArrowDown': { type: 'select', dir: 'next' }, 'ArrowLeft': { type: 'select', dir: 'main' }, 'ArrowRight': { type: 'select', dir: 'side' },
  'Home': { type: 'select', dir: 'first' }, 'End': { type: 'select', dir: 'last' },
  'Alt+ArrowUp': { type: 'move', dx: 0, dy: -1 }, 'Alt+ArrowDown': { type: 'move', dx: 0, dy: 1 },
  'Alt+ArrowLeft': { type: 'column', area: 'main' }, 'Alt+ArrowRight': { type: 'column', area: 'right' },
  'Shift+ArrowLeft': { type: 'size', dx: -1, dy: 0 }, 'Shift+ArrowRight': { type: 'size', dx: 1, dy: 0 }, 'Shift+ArrowUp': { type: 'size', dx: 0, dy: -1 }, 'Shift+ArrowDown': { type: 'size', dx: 0, dy: 1 },
  '-': { type: 'size', dx: -1, dy: 0 }, '=': { type: 'size', dx: 1, dy: 0 }, 'A': { type: 'natural' }, 'Space': { type: 'open' }
};

/** The schemes of the arrows. */
export const KEY_SCHEMES = Object.freeze({ move: MOVE, select: SELECT });

/**
 * The bindings of a scheme with the host's own on top (a binding set to null is removed).
 * @param {('move'|'select')} [scheme='move']
 * @param {Object<string, ?DesignerAction>} [extra]
 * @returns {Object<string, DesignerAction>}
 */
export function designerBindings(scheme, extra) {
  const out = Object.assign({}, COMMON, KEY_SCHEMES[scheme || 'move'] || MOVE);
  Object.keys(extra || {}).forEach((k) => { if (extra[k] === null) { delete out[k]; } else { out[k] = extra[k]; } });
  return out;
}

/**
 * The function that reads a key press as a designer action (undefined: not a designer key).
 * @param {('move'|'select')} [scheme='move']
 * @param {Object<string, ?DesignerAction>} [extra]
 * @param {{mac: boolean}} [o]
 * @returns {function(KeyboardEvent): (DesignerAction|undefined)}
 */
export function designerKeys(scheme, extra, o) {
  const map = keymap(designerBindings(scheme, extra), o);
  const plus = (scheme || 'move') === 'select' && !(extra && extra['+'] === null);
  return (e) => {
    if (plus && e.key === '+' && !e.ctrlKey && !e.metaKey && !e.altKey) { return { type: 'size', dx: 1, dy: 0 }; }      // '+' is Shift+= on most keyboards
    const a = map(e);
    return a ? Object.assign({}, a) : undefined;
  };
}

/**
 * The keys as a list for a help card: [{keys, does}].
 * @param {('move'|'select')} [scheme='move']
 * @returns {Array<{keys: string, does: string}>}
 */
export function keyHelp(scheme) {
  const words = { move: 'move', size: 'resize', select: 'pick another item', undo: 'undo', redo: 'redo', copy: 'copy', cut: 'cut', paste: 'paste', duplicate: 'duplicate',
    delete: 'delete', selectAll: 'select all', group: 'group', ungroup: 'ungroup', zorder: 'bring forward or send back', open: 'edit its properties', escape: 'let go',
    natural: 'its content\'s height', column: 'to the other column' };
  const b = designerBindings(scheme), seen = {};
  Object.keys(b).forEach((k) => { const w = words[b[k].type]; (seen[w] = seen[w] || []).push(k); });
  return Object.keys(seen).map((w) => ({ keys: seen[w].join(', '), does: w }));
}
