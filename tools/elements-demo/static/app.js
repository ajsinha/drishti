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
/* The host application's own code: its search box, recent list and date picker drive <drishti-view>. */
const D = window.DrishtiElements;
const $ = (s) => document.querySelector(s);
const main = $('#main'), side = $('#side'), q = $('#q'), recent = $('#recent');
const MNEMONIC = { trade: 'TRD', counterparty: 'CPTY' };       // the host's own habit: how it writes an entity in its search box
let recents = [{ kind: 'trade', id: 'END-1000008' }];

// the host's token provider: its own backend asks Drishti for a token for the signed-in user (server to server)
D.configure({
  tokenProvider: async () => {
    const r = await fetch('/api/drishti-token', { cache: 'no-store' });
    if (!r.ok) { throw new Error('not signed in'); }
    const b = await r.json();
    return { token: b.token, expiresAt: Date.now() + b.expiresIn * 1000 };
  }
});

function drawRecent() {
  recent.replaceChildren(...recents.map((e) => {
    const li = document.createElement('li'), b = document.createElement('button');
    b.type = 'button'; b.textContent = (MNEMONIC[e.kind] || e.kind) + ' ' + e.id; b.dataset.kind = e.kind; b.dataset.id = e.id;
    b.addEventListener('click', () => show(e.kind, e.id));
    li.append(b);
    return li;
  }));
}
function show(kind, id) {                          // the host decides what the main element shows
  q.value = (MNEMONIC[kind] || kind) + ' ' + id;
  main.setAttribute('kind', kind); main.setAttribute('entity', id);
  recents = [{ kind, id }, ...recents.filter((e) => !(e.kind === kind && e.id === id))].slice(0, 12);
  drawRecent();
}

$('#search').addEventListener('submit', async (e) => {
  e.preventDefault();
  const msg = $('#searchMsg');
  msg.textContent = '';
  try {
    const r = await D.resolve(q.value.trim());
    if (!r.ref) { msg.textContent = 'Not a single entity: ' + q.value; return; }
    show(r.ref.kind, r.ref.id);
  } catch (err) { msg.textContent = (err.code || 'error') + ' ' + (err.detail || err.message); }
});

main.addEventListener('drishti:navigate', (e) => {  // a linked entity in the view: the host takes it over, into its own search box
  e.preventDefault();
  show(e.detail.kind, e.detail.id);
});
$('#asof').addEventListener('change', (e) => { e.target.value ? main.setAttribute('as-of', e.target.value) : main.removeAttribute('as-of'); });
$('#live').addEventListener('click', () => { $('#asof').value = ''; main.removeAttribute('as-of'); });

const status = $('#status');
for (const el of [main, side]) {
  el.addEventListener('drishti:state', (e) => { el.dataset.hostState = e.detail.state; status.textContent = 'main: ' + (main.dataset.hostState || '') + ' · counterparty: ' + (side.dataset.hostState || ''); });
}

async function who() { const b = await (await fetch('/api/me')).json(); $('#who').textContent = b.user || 'nobody'; return b.user; }
document.querySelectorAll('[data-login]').forEach((b) => b.addEventListener('click', async () => {
  await fetch('/api/login', { method: 'POST', body: JSON.stringify({ user: b.dataset.login }) });
  D.invalidateToken();
  await who();
  main.reload(); side.reload();
}));
drawRecent();
who();
window.hostApp = { show };                          // for the tests
