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

import re, pathlib, sys
root = pathlib.Path(sys.argv[1])
routes = set()
ann = re.compile(r'@(Request|Get|Post|Put|Delete|Patch)Mapping\s*(\((.*?)\))?\s*$', re.S)
for f in (root / 'drishti-server/src/main/java').rglob('*.java'):
    src = f.read_text()
    if 'Mapping' not in src:
        continue
    # class prefix: RequestMapping appearing before "class "
    cls_idx = src.find(' class ')
    head = src[:cls_idx]
    prefixes = ['']
    m = re.findall(r'@RequestMapping\(([^)]*)\)', head)
    if m:
        prefixes = re.findall(r'"(/[^"]*)"', m[-1]) or ['']
    body = src[cls_idx:]
    for mm in re.finditer(r'[@.](Request|Get|Post|Put|Delete|Patch)Mapping(\(([^)]*)\))?', body):
        args = mm.group(3) or ''
        paths = re.findall(r'"(/[^"]*)"', args) or ['']
        for p in prefixes:
            for q in paths:
                routes.add(p + q)
pats = []
for r in routes:
    rx = re.escape(r)
    rx = re.sub(r'\\\{[^}]*\\\}', r'[^/]+', rx)
    pats.append((r, re.compile('^' + rx + '/?$')))
seen = {}
def api_paths(d, line):
    out = re.findall(r'/api/v1(/[A-Za-z0-9_{}<>.\-/…*:]*)', line)
    if d.name == 'API_GUIDE.md':
        m = re.match(r'\| `?(GET|POST|PUT|DELETE|PATCH)`? \| `([^`]+)`', line)
        if m and not m.group(2).startswith('/api/v1') and not m.group(2).startswith('/actuator') and not m.group(2).startswith('/public'):
            out.append(m.group(2))
    return out
for d in list((root / 'docs').rglob('*.md')) + list((root / 'drishti-console/web/guides').rglob('*.md')) + [root / 'README.md']:
    for i, line in enumerate(d.read_text().splitlines(), 1):
        for p in api_paths(d, line):
            p = p.split('?')[0].rstrip('.,:)`*/…')
            if not p or '*' in p or '…' in p:
                continue
            full = '/api/v1' + p
            full = re.sub(r'<[^>]*>', 'X', full)
            if not any(rx.match(full) for _, rx in pats):
                seen.setdefault(full, []).append(f'{d.relative_to(root)}:{i}')
for k, v in sorted(seen.items()):
    print(k, v[:3], len(v))
print('ROUTES', len(routes), file=sys.stderr)
