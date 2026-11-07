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
/* The event bus: named events, listeners in the order they were added, "*" hears everything. A listener that throws does not stop the
   others (the error is reported once through onError, default console.error). Each component makes its own bus; `bus` is the one a
   page shares (a library and the page talking without knowing each other). */

/**
 * A listener: the event's payload and its name.
 * @callback Listener
 * @param {*} payload
 * @param {string} type
 * @returns {void}
 */

/**
 * An event bus.
 */
export class EventBus {
  /**
   * @param {{onError: function(Error, string): void}} [o] who hears a listener's error (default: console.error)
   */
  constructor(o) {
    /** @private */ this._on = new Map();
    /** @private */ this._onError = (o && o.onError) || ((e, type) => { if (typeof console !== 'undefined') { console.error('drishti bus: a listener of ' + type + ' failed', e); } });
  }

  /**
   * Listens to an event ("*": every event).
   * @param {string} type
   * @param {Listener} fn
   * @returns {function(): void} stops listening
   */
  on(type, fn) {
    if (typeof fn !== 'function') { throw new TypeError('EventBus.on: a listener is a function'); }
    if (!this._on.has(type)) { this._on.set(type, []); }
    this._on.get(type).push(fn);
    return () => this.off(type, fn);
  }

  /**
   * Listens to the next event of a type only.
   * @param {string} type
   * @param {Listener} fn
   * @returns {function(): void} stops listening
   */
  once(type, fn) {
    const off = this.on(type, (p, t) => { off(); fn(p, t); });
    return off;
  }

  /**
   * Stops a listener (or, without one, every listener of the type).
   * @param {string} type
   * @param {Listener} [fn]
   * @returns {void}
   */
  off(type, fn) {
    if (!fn) { this._on.delete(type); return; }
    const l = this._on.get(type);
    if (!l) { return; }
    const i = l.indexOf(fn);
    if (i >= 0) { l.splice(i, 1); }
    if (!l.length) { this._on.delete(type); }
  }

  /**
   * Tells every listener of the type, then every "*" listener.
   * @param {string} type
   * @param {*} [payload]
   * @returns {number} how many listeners heard it
   */
  emit(type, payload) {
    const l = (this._on.get(type) || []).slice().concat(type === '*' ? [] : (this._on.get('*') || []).slice());
    l.forEach((fn) => { try { fn(payload, type); } catch (e) { this._onError(e, type); } });
    return l.length;
  }

  /**
   * How many listeners a type has.
   * @param {string} type
   * @returns {number}
   */
  count(type) { return (this._on.get(type) || []).length; }

  /**
   * Forgets every listener.
   * @returns {void}
   */
  clear() { this._on.clear(); }
}

/**
 * A new bus.
 * @param {{onError: function(Error, string): void}} [o]
 * @returns {EventBus}
 */
export function createBus(o) { return new EventBus(o); }

/** The bus a page shares. */
export const bus = new EventBus();
