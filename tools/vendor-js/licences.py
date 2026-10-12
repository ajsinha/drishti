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

"""Lists the packages inside a bundle (from an esbuild metafile) with their licences, and writes their licence texts into one file."""
import json
import os
import re
import sys

meta = json.load(open(sys.argv[1]))
out = sys.argv[2]
root = os.path.abspath('node_modules')
pkgs = {}
for path in meta['inputs']:
    m = re.search(r'node_modules/((?:@[^/]+/)?[^/]+)/', path)
    if m:
        pkgs.setdefault(m.group(1), None)
def closure(name, seen):
    p = os.path.join(root, name, 'package.json')
    if name in seen or not os.path.isfile(p):
        return
    seen.add(name)
    for d in (json.load(open(p)).get('dependencies') or {}):
        closure(d, seen)
seen = set()
for top in ():
    closure(top, seen)
for n in seen:
    pkgs.setdefault(n, None)
rows = []
texts = []
for name in sorted(pkgs):
    base = os.path.join(root, name)
    pj = json.load(open(os.path.join(base, 'package.json')))
    lic = pj.get('license') or (pj.get('licenses') or [{}])[0].get('type') or 'UNKNOWN'
    if isinstance(lic, dict):
        lic = lic.get('type', 'UNKNOWN')
    rows.append((name, pj.get('version', ''), lic))
    for f in ('LICENSE', 'LICENSE.md', 'LICENSE.txt', 'LICENCE', 'license', 'LICENSE-MIT'):
        p = os.path.join(base, f)
        if os.path.isfile(p):
            texts.append('=' * 78 + '\n%s %s (%s)\n' % (name, pj.get('version', ''), lic) + '=' * 78 + '\n' + open(p, errors='replace').read().strip() + '\n')
            break
    else:
        texts.append('=' * 78 + '\n%s %s (%s)\n' % (name, pj.get('version', ''), lic) + '=' * 78 + '\n(no licence file in the package; declared licence: %s)\n' % lic)
open(out, 'w').write('Licences of the packages bundled into walker.js (Graphic Walker 0.5.3 and what it needs).\n\n' + '\n'.join('%-40s %-12s %s' % r for r in rows) + '\n\n' + '\n'.join(texts))
from collections import Counter
print(Counter(r[2] for r in rows))
