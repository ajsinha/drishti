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
/* Undo and redo for every designer: one command stack. A command is a step with a label ("Move a visual") that can be done and undone;
   steps made inside group() are one step; a step may merge into the one before it (typing, a drag's many moves). A designer whose
   truth is one JSON document (a report, a form) keeps snapshots instead: SnapshotHistory is the same stack over before/after copies of
   the document, so a host that changes its model in place gets undo without writing inverse operations. Nothing here touches a page. */
import { EventBus } from '../../drishti-core/src/drishti_index.js';

/**
 * A step of a designer.
 * @typedef {Object} Command
 * @property {string} label in words, for the menus and the announcement ("Move 2 items")
 * @property {function(): void} redo does the step (also the first time, through run())
 * @property {function(): void} undo takes it back
 * @property {string} [mergeKey] steps with the same key in a row (within mergeMs) become one step
 */

/**
 * The command stack. Events: change {canUndo, canRedo, undoLabel, redoLabel, size}, done {command, how: 'run'|'undo'|'redo'}.
 */
export class CommandStack {
  /**
   * @param {{limit: number, mergeMs: number, now: function(): number}} [o] limit: the steps kept (default 200); mergeMs: how close two
   *   steps with one mergeKey must be to merge (default 800 ms); now: the clock (tests)
   */
  constructor(o) {
    this.o = { limit: 200, mergeMs: 800, now: () => Date.now() };
    Object.keys(o || {}).forEach((k) => { if (o[k] !== undefined) { this.o[k] = o[k]; } });
    /** @type {Command[]} */
    this.done = [];
    /** @type {Command[]} */
    this.undone = [];
    this.events = new EventBus();
    this.batch = null;
    this.lastAt = 0;
    this.savedAt = 0;
  }

  /**
   * Listens to the stack's events.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * Does a command and keeps it (inside group(): as part of the group's step).
   * @param {Command} cmd
   * @returns {Command}
   */
  run(cmd) {
    cmd.redo();
    return this.push(cmd);
  }

  /**
   * Keeps a command that was already done.
   * @param {Command} cmd
   * @returns {Command}
   */
  push(cmd) {
    if (!cmd || typeof cmd.undo !== 'function' || typeof cmd.redo !== 'function') { throw new Error('a command has undo and redo'); }
    if (this.batch) { this.batch.push(cmd); return cmd; }
    const now = this.o.now(), last = this.done[this.done.length - 1];
    if (last && cmd.mergeKey && last.mergeKey === cmd.mergeKey && now - this.lastAt <= this.o.mergeMs) {
      const first = last, next = cmd;
      this.done[this.done.length - 1] = { label: next.label, mergeKey: next.mergeKey, redo: () => { first.redo(); next.redo(); }, undo: () => { next.undo(); first.undo(); } };
    } else {
      this.done.push(cmd);
      if (this.done.length > this.o.limit) { this.done.shift(); this.savedAt = Math.max(-1, this.savedAt - 1); }
    }
    this.lastAt = now;
    this.undone.length = 0;
    this.changed('run', cmd);
    return cmd;
  }

  /**
   * Runs fn with every command it makes kept as one step (nested groups join the outer one). A group with no command keeps nothing.
   * @param {string} label
   * @param {function(): *} fn
   * @returns {*} what fn returned
   */
  group(label, fn) {
    if (this.batch) { return fn(); }
    const list = this.batch = [];
    let out;
    try { out = fn(); } catch (e) { this.batch = null; list.slice().reverse().forEach((c) => c.undo()); throw e; }
    this.batch = null;
    if (list.length) {
      this.push({ label, redo: () => list.forEach((c) => c.redo()), undo: () => list.slice().reverse().forEach((c) => c.undo()) });
    }
    return out;
  }

  /** @returns {boolean} */
  canUndo() { return this.done.length > 0; }

  /** @returns {boolean} */
  canRedo() { return this.undone.length > 0; }

  /**
   * Takes back the last step.
   * @returns {?Command} the step, or null when there is none
   */
  undo() {
    const c = this.done.pop();
    if (!c) { return null; }
    c.undo();
    this.undone.push(c);
    this.lastAt = 0;
    this.changed('undo', c);
    return c;
  }

  /**
   * Does again the last step undo took back.
   * @returns {?Command}
   */
  redo() {
    const c = this.undone.pop();
    if (!c) { return null; }
    c.redo();
    this.done.push(c);
    this.lastAt = 0;
    this.changed('redo', c);
    return c;
  }

  /**
   * Forgets every step (a new document).
   * @returns {void}
   */
  clear() { this.done.length = 0; this.undone.length = 0; this.savedAt = 0; this.changed('clear', null); }

  /**
   * Marks the present step as the saved one (dirty() is false until the next step).
   * @returns {void}
   */
  markSaved() { this.savedAt = this.done.length; this.changed('saved', null); }

  /**
   * Whether there are steps since the last markSaved().
   * @returns {boolean}
   */
  dirty() { return this.done.length !== this.savedAt; }

  /**
   * What the undo and redo buttons show.
   * @returns {{canUndo: boolean, canRedo: boolean, undoLabel: string, redoLabel: string, size: number}}
   */
  state() {
    const u = this.done[this.done.length - 1], r = this.undone[this.undone.length - 1];
    return { canUndo: !!u, canRedo: !!r, undoLabel: u ? u.label : '', redoLabel: r ? r.label : '', size: this.done.length };
  }

  /** @private */
  changed(how, command) {
    if (command) { this.events.emit('done', { command, how }); }
    this.events.emit('change', this.state());
  }
}

/**
 * Undo and redo by snapshots of one JSON document: change(label, fn) copies the document before fn edits it in place, and a step
 * that changed nothing is not kept. The host reads the document from get() and is told after each step (onChange).
 */
export class SnapshotHistory {
  /**
   * @param {{get: function(): Object, set: function(Object, string): void, limit: number, mergeMs: number, now: function(): number}} o
   *   get: the document now; set(doc, how): the document becomes doc (after undo or redo; how is 'undo' or 'redo')
   */
  constructor(o) {
    if (!o || typeof o.get !== 'function' || typeof o.set !== 'function') { throw new Error('a snapshot history needs get and set'); }
    this.o = o;
    this.stack = new CommandStack({ limit: o.limit || 100, mergeMs: o.mergeMs, now: o.now });
  }

  /**
   * Listens to the stack's events (change, done).
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.stack.on(name, fn); }

  /**
   * One step: fn edits the document (the one get() returns) in place. Refused (false) when fn returns false or throws, or changed
   * nothing: the document is put back as it was.
   * @param {string} label
   * @param {function(Object): (boolean|void)} fn
   * @param {{mergeKey: string}} [o]
   * @returns {boolean} whether a step was kept
   */
  change(label, fn, o) {
    const before = JSON.stringify(this.o.get());
    let ok;
    try { ok = fn(this.o.get()); } catch (e) { this.o.set(JSON.parse(before), 'refused'); throw e; }
    const after = JSON.stringify(this.o.get());
    if (ok === false) { if (after !== before) { this.o.set(JSON.parse(before), 'refused'); } return false; }
    if (after === before) { return false; }
    this.record(label, before, after, o);
    return true;
  }

  /**
   * Keeps a step whose before and after are already known (the document as text, JSON).
   * @param {string} label
   * @param {string} before
   * @param {string} after
   * @param {{mergeKey: string}} [o]
   * @returns {void}
   */
  record(label, before, after, o) {
    this.stack.push({ label, mergeKey: o && o.mergeKey, redo: () => this.o.set(JSON.parse(after), 'redo'), undo: () => this.o.set(JSON.parse(before), 'undo') });
  }

  /** @returns {?Command} */
  undo() { return this.stack.undo(); }

  /** @returns {?Command} */
  redo() { return this.stack.redo(); }

  /** @returns {boolean} */
  canUndo() { return this.stack.canUndo(); }

  /** @returns {boolean} */
  canRedo() { return this.stack.canRedo(); }

  /** @returns {void} */
  clear() { this.stack.clear(); }
}
