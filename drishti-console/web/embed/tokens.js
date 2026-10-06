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
/* Tokens: asked of the host's provider (sync or async, a string or {token, expiresAt}), kept until shortly before they expire, and
   renewed once after a 401. When there is no provider, or it fails, the elements are told with `drishti:token-needed`. */
import { cfg } from './config.js';

export const tokenState = { token: null, exp: 0, life: 0, pending: null };
const listeners = new Set();
let providerSet;
const providerReady = new Promise((r) => { providerSet = r; });

export const providerChanged = () => providerSet();
/** Elements register here while connected: each gets `drishti:token-needed` when a token is missing or refused. */
export const onTokenNeeded = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };
export const tokenNeeded = (reason) => { for (const fn of [...listeners]) { try { fn(reason); } catch (e) { /* a host handler's failure is not ours */ } } };
export const invalidateToken = () => { tokenState.token = null; tokenState.exp = 0; tokenState.life = 0; };

function jwtExp(token) {
  try { return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).exp * 1000; } catch (e) { return 0; }
}
/** How early before expiry a token is replaced: 60 s, or 40 % of its life when that is shorter (a 30 s token is replaced at 18 s). */
const margin = () => Math.min(60000, (tokenState.life || 60000) * 0.4);
/** When the open channel should send its fresh token (a little earlier than the provider is asked again). */
export const renewalDelay = () => Math.max(1000, tokenState.exp - Date.now() - Math.min(45000, (tokenState.life || 45000) * 0.4));

export async function getToken(force) {
  // a host that sets its provider after the module has loaded (modules run in order, elements upgrade at once): wait briefly for it
  if (!cfg.tokenProvider) { await Promise.race([providerReady, new Promise((r) => setTimeout(r, 3000))]); }
  if (!cfg.tokenProvider) { return null; }
  if (!force && tokenState.token && Date.now() < tokenState.exp - margin()) { return tokenState.token; }
  if (!tokenState.pending) {
    tokenState.pending = Promise.resolve().then(() => cfg.tokenProvider()).then((r) => {
      const token = typeof r === 'string' ? r : r && r.token;
      if (!token) { throw new Error('the token provider returned no token'); }
      tokenState.token = token;
      tokenState.exp = (r && r.expiresAt) || jwtExp(token) || Date.now() + 300000;
      tokenState.life = Math.max(0, tokenState.exp - Date.now());
      return token;
    }).catch((e) => { tokenNeeded('provider-failed'); throw e; }).finally(() => { tokenState.pending = null; });
  }
  return tokenState.pending;
}

/** A fetch with the bearer; one new token on a 401. */
export async function authed(url, init, signal) {
  for (let attempt = 0; ; attempt++) {
    const token = await getToken(attempt > 0);
    const res = await fetch(url, Object.assign({}, init, { signal, cache: 'no-store',
      headers: Object.assign({}, init && init.headers, token ? { Authorization: 'Bearer ' + token } : {}) }));
    if (res.status === 401) {
      if (attempt === 0 && cfg.tokenProvider) { continue; }
      tokenNeeded(cfg.tokenProvider ? 'refused' : 'missing');
    }
    return res;
  }
}
