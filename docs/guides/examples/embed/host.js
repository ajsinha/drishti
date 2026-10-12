/* The part every host page shares: tell Drishti Elements where a user's embed token comes from.
   Your own backend asks Drishti for it (server to server, RFC 8693, the application's secret never reaches the browser); the page only calls your backend.
   Here that backend is tools/elements-demo/server.py (GET /api/drishti-token); in your application it is one endpoint of your own. */
export const SCOPES = 'embed:view embed:about embed:report embed:search';

export function configureDrishti() {
  window.DrishtiElements.configure({
    tokenProvider: async () => {
      const res = await fetch('/api/drishti-token?scope=' + encodeURIComponent(SCOPES), { cache: 'no-store', credentials: 'same-origin' });
      if (!res.ok) { throw new Error('the host backend refused the token request (' + res.status + '): sign in first'); }
      const { token, expiresIn } = await res.json();
      return { token, expiresAt: Date.now() + expiresIn * 1000 };
    }
  });
}

/** Signs the demo user in (the demo host's own cookie; a real host already has its own sign-in). */
export async function demoSignIn(user = 'viewer') {
  const me = await (await fetch('/api/me', { credentials: 'same-origin' })).json();
  if (me.user === user) { return user; }
  await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user }), credentials: 'same-origin' });
  return user;
}

/** What every sample shows: the entity the search picked, and the report. */
export const START = { kind: 'trade', id: 'END-1000008', report: 'file:report:live-trading-desk' };
