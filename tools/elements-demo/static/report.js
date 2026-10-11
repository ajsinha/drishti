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
/* The host application's own code for the report page: its token provider, its sign-in buttons and a log of what <drishti-report> told it. */
const D = window.DrishtiElements;
const log = document.getElementById('log');
window.__events = [];

// the host's token provider: its own backend asks Drishti for a token that opens reports, for the signed-in user (server to server)
D.configure({
  tokenProvider: async () => {
    const r = await fetch('/api/drishti-token?scope=embed:report', { cache: 'no-store' });
    if (!r.ok) { throw new Error('not signed in'); }
    const b = await r.json();
    return { token: b.token, expiresAt: Date.now() + b.expiresIn * 1000 };
  }
});

for (const name of ['loaded', 'filter-changed', 'page-changed', 'error', 'masked', 'state', 'tick']) {
  document.addEventListener('drishti:' + name, (e) => {
    const detail = e.detail || {};
    window.__events.push({ name, id: e.target.id, detail });
    if (name === 'state' || name === 'tick') { return; }
    const li = document.createElement('li');
    li.textContent = name + ' ' + e.target.id + ' ' + JSON.stringify(name === 'loaded' ? { title: detail.title, page: detail.page } : name === 'error' ? { code: detail.code } : detail).slice(0, 160);
    log.append(li);
  });
}

async function who() {
  const me = await (await fetch('/api/me')).json();
  document.getElementById('who').textContent = me.user || 'nobody';
  return me.user;
}
document.querySelectorAll('[data-login]').forEach((b) => b.addEventListener('click', async () => {
  await fetch('/api/login', { method: 'POST', body: JSON.stringify({ user: b.dataset.login }) });
  await who();
  D.invalidateToken();
  document.querySelectorAll('drishti-report').forEach((el) => el.reload());
}));
who().then((user) => {
  if (!user) { document.querySelector('[data-login="viewer"]').click(); }
});
