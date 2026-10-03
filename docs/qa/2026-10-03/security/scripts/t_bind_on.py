# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.


"""File binding ON: non-authors, path escapes, symlinks, overwriting another Sutra (governance bypass), cross-design clash."""
from sec import *
import os
P = "/api/v1/builder/designs"; W = open(".workdir").read().strip()
SUT = "rachana: 1\nsutra: qa-bound\nversion: 1\ndescription: x\nmatch: { kind: qabound, priority: 1 }\ntitle: { id: $.id }\npanels: []\n"
def mk(u, name="b"):
    s, b = api(u, "POST", P, {"name": name, "kind": "qabound", "sutra": SUT}); return jl(b)["id"]
D = mk("qa-author")
# 1. who may bind
for u in ["qa-viewer", "qa-narrow", "qa-masked", None]:
    d = mk(u) if u else None
    if u:
        s, b = api(u, "POST", f"{P}/{d}/bind", {"file": "np.yaml"}); log(f"bind non-author {u}", "POST bind", "403", f"{s} {b[:80]}", "PASS" if s == 403 else "FAIL")
s, b = api(None, "POST", f"{P}/{D}/bind", {"file": "np.yaml"}); log("bind anon", "POST bind", "401", s, "PASS" if s == 401 else "FAIL")
# 2. path escapes
cases = ["../secret_sibling.yaml", "../../etc/passwd.yaml", "a/../../secret_sibling.yaml", "sub/../../secret_sibling.yaml", "/etc/passwd.yaml", "/tmp/x.yaml", "..\\secret_sibling.yaml",
         "x.yaml\u0000.txt", "%2e%2e/secret_sibling.yaml", "..%2fsecret_sibling.yaml", "....//secret_sibling.yaml", "./../secret_sibling.yaml", "file:///etc/passwd.yaml",
         "linkdir/x.yaml", "linkdir/new.yaml", "linkdir/../secret.yaml", "x.txt", "noext", ".yaml", "a" * 300 + ".yaml", "sub/", "//etc/x.yaml", "C:\\x.yaml", "~/x.yaml", "$HOME/x.yaml", "con.yaml\n", "x.yaml ", "x.YAML"]
for c in cases:
    s, b = api("qa-author", "POST", f"{P}/{D}/bind", {"file": c})
    d = jl(b) or {}
    leak = "MARKER" in b or "root:" in b
    wrote_outside = any(os.path.exists(os.path.join(W, p)) for p in ["x.yaml", "new.yaml"]) and False
    bound = d.get("boundFile") if isinstance(d, dict) else None
    verdict = "FAIL" if leak else ("PASS" if s in (400, 403) else f"CHECK ({s} bound={bound})")
    log("bind path", f"file={c!r}", "400, nothing bound", f"{s} bound={bound} leak={leak} {b[:80] if s!=200 else ''}", verdict)
    if s == 200: api("qa-author", "DELETE", f"{P}/{D}/bind")
# 3. symlinked file inside the dir pointing outside (needs an admin to have planted the link)
s, b = api("qa-author", "POST", f"{P}/{D}/bind", {"file": "link.yaml"}); d = jl(b) or {}
log("bind symlink file -> outside", "file=link.yaml (symlink to <work>/outside/secret.yaml)", "refused (target outside Sutra dir)", f"{s} sutra-has-marker={'OUTSIDE-FILE-MARKER' in b}", "FAIL" if 'MARKER' in b else "PASS")
s, b = api("qa-author", "GET", f"{P}/{D}"); log("symlink read via GET design", "GET design after bind link.yaml", "no outside content", f"sutra-has-marker={'OUTSIDE-FILE-MARKER' in b}", "FAIL" if 'MARKER' in b else "PASS")
api("qa-author", "DELETE", f"{P}/{D}/bind")
s, b = api("qa-author", "POST", f"{P}/{D}/bind", {"file": "sub/inner.yaml"}); log("bind symlink inside dir -> existing.yaml", "file=sub/inner.yaml", "inside dir: allowed", f"{s}")
api("qa-author", "DELETE", f"{P}/{D}/bind")
# 4. overwrite another Sutra (existing.yaml, loaded as other-live) and governance bypass
D2 = mk("qa-author", "overwrite")
before = open(W + "/sutras/existing.yaml").read()
s, b = api("qa-author", "POST", f"{P}/{D2}/bind", {"file": "existing.yaml"}); log("bind onto another Sutra's file", "file=existing.yaml", "allowed? (author, dev setting)", f"{s} adopted other-live={'other-live' in b}")
evil = SUT.replace("qa-bound", "qa-bound-evil")
s, b = api("qa-author", "PATCH", f"{P}/{D2}", {"sutra": evil}); 
s, b = api("qa-author", "POST", f"{P}/{D2}/save-file"); log("save-file overwrites another Sutra file with governance ON", "POST save-file", "refused or proposal (review is on)", f"{s} {b[:80]}", "")
after = open(W + "/sutras/existing.yaml").read()
log("existing.yaml changed on disk", "cat existing.yaml", "unchanged if governance enforced", "CHANGED" if after != before else "unchanged", "FAIL (governance bypass)" if after != before else "PASS")
import time; time.sleep(3)
s, b = api("qa-viewer", "GET", "/api/v1/sutras"); names = [x["name"] for x in jl(b)]
log("evil Sutra live without review", "GET sutras (hot reload)", "qa-bound-evil absent unless approved", f"present={'qa-bound-evil' in names} other-live present={'other-live' in names}", "FAIL (live without approval)" if 'qa-bound-evil' in names else "PASS")
s, b = api("qa-author", "GET", "/api/v1/sutras/proposals"); log("proposal created for it?", "GET proposals", "a proposal exists for review", f"{[p['name'] for p in jl(b)['proposals']][-3:]}")
# 5. cross-design clash: two designs on the same file
D3 = mk("qa-author2", "clash")
s, b = api("qa-author2", "POST", f"{P}/{D3}/bind", {"file": "existing.yaml"}); log("second author binds same file", "POST bind", "any author may bind any file in dir", f"{s}")
s, b = api("qa-author2", "POST", f"{P}/{D3}/save-file"); log("second author saves over first's file", "save-file", "409 or allowed", f"{s} {b[:80]}")
# 6. tmp file left / symlink as .tmp sibling
os.system(f"ln -sfn {W}/outside/planted.txt {W}/sutras/tmpl.yaml.tmp; rm -f {W}/outside/planted.txt")
D4 = mk("qa-author", "tmplink")
s, b = api("qa-author", "POST", f"{P}/{D4}/bind", {"file": "tmpl.yaml"}); s2, b2 = api("qa-author", "POST", f"{P}/{D4}/save-file")
log("save-file .tmp symlink pre-planted", "link tmpl.yaml.tmp -> outside/planted.txt then save-file", "does not write outside", f"{s2} planted-outside-exists={os.path.exists(W+'/outside/planted.txt')}", "FAIL" if os.path.exists(W+'/outside/planted.txt') else "PASS")
# 7. save to a new nested path creates dirs
D5 = mk("qa-author", "nested"); api("qa-author", "POST", f"{P}/{D5}/bind", {"file": "deep/a/b/c.yaml"}); s, b = api("qa-author", "POST", f"{P}/{D5}/save-file")
log("nested new path", "deep/a/b/c.yaml", "created under dir", f"{s} exists={os.path.exists(W+'/sutras/deep/a/b/c.yaml')}")
# 8. sync reads file: an IDE edit is adopted; hostile huge file
s, b = api("qa-author", "GET", f"{P}/{D5}/sync"); log("sync", "GET sync", "200", f"{s} {b[:60]}")
