import re
import sys

src = open('node_modules/@kanaries/graphic-walker/dist/graphic-walker.es.js', encoding='utf-8').read()
for m in re.finditer(r'(?:const|var|let)\s+(\w+)\s*=\s*`', src):
    start = m.end()
    i = start
    while i < len(src):
        if src[i] == '\\':
            i += 2
            continue
        if src[i] == '`':
            break
        i += 1
    body = src[start:i]
    if len(body) > 800 and ('{' in body[:400]) and ('}' in body[:3000]) and (body.lstrip().startswith(('*', 'code', ':root', '.', '@', 'html', 'body', '/*'))):
        print(m.group(1), len(body), repr(body[:70]))
        if len(sys.argv) > 1 and m.group(1) == sys.argv[1]:
            open(sys.argv[2], 'w', encoding='utf-8').write(body)
