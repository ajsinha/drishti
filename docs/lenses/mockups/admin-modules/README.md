# Admin → Modules: three mock-ups (for the owner's choice, before I8b builds it)

Shared content (all three): every module with id, title, version, origin, content hash (short), on/off switch with reason, parts placed per node,
problems (stale package, refused verify, need not met, library duplicate), each node's fingerprint and whether the nodes agree, "Download .dmod" and the
install/upgrade commands (`drishti.py module install`, `verify`, `restart`; nothing deploys from the browser), and a "module not installed" list for saved work.
Admin-only. Built later from Drishti Elements only (grid, chips, cards, inputs, tabs); these files use the vendored 2.4.0 tokens and chip CSS.
Sample data: 10 modules, 3 nodes (drishti-main, b, c); capitalmarkets stale, source-swapone refused, market-data/economy warnings, trading differs on drishti-c.

| | Layout | Strong | Weak |
|---|---|---|---|
| A | One dense grid, rows expand in place; nodes and install below | Everything in one scan; fewest clicks; best for 10-30 modules; one grid component | Per-node placement only as text; the cluster story is at the bottom |
| B | Master-detail: list left, detail with tabs right | Room for depth per module (parts x nodes, problems, install) | Two panes; the overview of all modules is narrow; more clicks to compare; weakest on a phone |
| C | Cluster-first: counts, nodes x modules matrix (cell = content hash booted), then detail | A disagreeing node or stale module is visible at once; matches the fingerprint model (content hash is the identity) | A matrix gets wide with many nodes (scrolls sideways); less room for per-module text |

Files: `A.html`/`A.png`/`A-phone.png`, `B...`, `C...` (PNG 1440x900 and 390 wide).

**Recommendation: C.** After i7a the question an admin asks is "did every node boot the same modules, and is anything stale or refused?", and C answers it first, in one
table, while still carrying the per-module detail and the install commands. If a plain list is preferred, A is the cheapest to build (one grid).
Build checklist for I8b: the matrix and list are one grid; chips for origin/problem; no bespoke widget; the switch is the existing per-module setting.
