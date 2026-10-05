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

import re, sys, pathlib, unicodedata, yaml
root = pathlib.Path(sys.argv[1])
docs = root / 'docs'
gdir = root / 'drishti-console/web/guides'
help_ = yaml.safe_load((root / 'drishti-console/config/help.yaml').read_text())
slug2path = {}
problems = []
for c in help_['categories']:
    for g in c['guides']:
        p = (docs / g['doc']).resolve() if g.get('doc') else gdir / f"{g['slug']}.md"
        slug2path[g['slug']] = p
        if not p.exists():
            problems.append(f"help.yaml: guide {g['slug']} -> missing {p}")
for k, v in help_.get('contextual', {}).items():
    if v not in slug2path:
        problems.append(f"help.yaml contextual {k}: unknown slug {v}")
for f in gdir.glob('*.md'):
    slug2path.setdefault(f.stem, f)

def heading_text(h):
    h = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', h)
    h = h.replace('`', '')
    h = re.sub(r'\{[:#.][^}]*\}\s*$', '', h)
    h = re.sub(r'<[^>]+>', '', h)
    h = h.replace('&amp;', '&')
    return h.strip()

def explicit_id(h):
    m = re.search(r'\{[^}]*#([\w-]+)[^}]*\}\s*$', h)
    return m.group(1) if m else None

def pm_slug(v):  # python-markdown toc default
    v = re.sub(r'\*\*|__|\*|_(?=\W)', '', v)
    v = unicodedata.normalize('NFKD', v).encode('ascii', 'ignore').decode('ascii')
    v = re.sub(r'[^\w\s-]', '', v).strip().lower()
    return re.sub(r'[-\s]+', '-', v)

def gh_slug(v):
    v = re.sub(r'\*\*|\*', '', v)
    v = v.lower()
    v = re.sub(r'[^\w\- ]', '', v, flags=re.U)
    return v.replace(' ', '-')

cache = {}
def anchors(p):
    if p in cache:
        return cache[p]
    pm, gh = set(), set()
    seen_pm, seen_gh = {}, {}
    infence = False
    for line in p.read_text(encoding='utf-8').splitlines():
        if line.lstrip().startswith('```'):
            infence = not infence
            continue
        if infence:
            continue
        m = re.match(r'^(#{1,6})\s+(.*?)\s*#*\s*$', line)
        if not m:
            continue
        raw = m.group(2)
        eid = explicit_id(raw)
        t = heading_text(raw)
        a = eid or pm_slug(t)
        if a in seen_pm:
            seen_pm[a] += 1; a = f"{a}_{seen_pm[a]}"
        else:
            seen_pm[a] = 0
        pm.add(a)
        b = gh_slug(t)
        if b in seen_gh:
            seen_gh[b] += 1; b = f"{b}-{seen_gh[b]}"
        else:
            seen_gh[b] = 0
        gh.add(b)
        if eid: gh.add(eid)
    for m in re.finditer(r'<a (?:name|id)="([^"]+)"', p.read_text(encoding='utf-8')):
        pm.add(m.group(1)); gh.add(m.group(1))
    cache[p] = (pm, gh)
    return cache[p]

link_re = re.compile(r'(?<!\!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
img_re = re.compile(r'!\[[^\]]*\]\(([^)\s]+)\)')
stats = {'checked': 0, 'external': 0, 'gh_only': []}
files = list(gdir.glob('*.md')) + list(docs.rglob('*.md')) + [root / 'README.md']
for f in files:
    text = f.read_text(encoding='utf-8')
    infence = False
    for i, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith('```'):
            infence = not infence; continue
        if infence: continue
        line_nc = re.sub(r'`[^`]*`', '', line)
        for m in img_re.finditer(line_nc):
            t = m.group(1)
            stats['checked'] += 1
            if t.startswith('/static/'):
                if not (root / 'drishti-console/web' / t.lstrip('/')).exists():
                    problems.append(f"{f.relative_to(root)}:{i}: image {t} missing")
            elif not re.match(r'https?:', t):
                if not (f.parent / t).resolve().exists():
                    problems.append(f"{f.relative_to(root)}:{i}: image {t} missing")
        for m in link_re.finditer(line_nc):
            t = m.group(1)
            stats['checked'] += 1
            if re.match(r'(https?|mailto):', t):
                stats['external'] += 1
                if not re.match(r'^(https?://[A-Za-z0-9.-]+\.[A-Za-z]{2,}(:\d+)?(/\S*)?|mailto:\S+@\S+)$', t):
                    problems.append(f"{f.relative_to(root)}:{i}: malformed external {t}")
                continue
            path, _, anchor = t.partition('#')
            if f.parent == gdir and path and not path.endswith('.md') and not path.startswith('/') and '/' not in path and '.' not in path:
                target = slug2path.get(path)
                if target is None:
                    problems.append(f"{f.relative_to(root)}:{i}: unknown guide slug '{path}'")
                    continue
            elif path.startswith('/'):
                if path.startswith('/help/'):
                    s = path[6:]
                    target = slug2path.get(s)
                    if target is None:
                        problems.append(f"{f.relative_to(root)}:{i}: unknown /help slug '{s}'")
                        continue
                else:
                    continue  # console route
            elif path == '':
                target = f
            else:
                target = (f.parent / path).resolve()
                if not target.exists():
                    problems.append(f"{f.relative_to(root)}:{i}: missing file {t}")
                    continue
            if anchor and target.suffix == '.md':
                pm, gh = anchors(target)
                if anchor in pm:
                    continue
                if anchor in gh:
                    stats['gh_only'].append(f"{f.relative_to(root)}:{i}: #{anchor} (GitHub-only slug; help centre id differs)")
                else:
                    problems.append(f"{f.relative_to(root)}:{i}: broken anchor {t}")
print('\n'.join(problems))
print('---- checked', stats['checked'], 'external', stats['external'], 'gh-only', len(stats['gh_only']))
print('\n'.join(stats['gh_only'][:60]))
