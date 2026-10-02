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

"""Runtime WCAG contrast of visible text, per theme, on a set of pages. Also screenshots each theme."""
import json, re, sys
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from pw import *
HERE = __file__.rsplit("/", 1)[0]
THEMES = ["terminal", "light", "blue", "wallstreet", "green", "crimson", "crimson-dark"]
PAGES = ["/", "/t", "/v/trade/MX-20000001", "/v/netting-set/NS-SUMMIT-NY", "/s?q=TRD%20where%20mtm%20%3E%201m%20limit%2020",
         "/studio", "/admin/users", "/help", "/help/quickstart", "/alerts", "/m", "/w", "/account", "/login", "/compare/trade/MX-20000002?a=2026-09-29&b=2026-09-30"]
JS = r"""() => {
 function rgb(s){const m=s.match(/rgba?\(([^)]+)\)/); if(!m) return null; const p=m[1].split(',').map(x=>parseFloat(x)); return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};}
 function lum(c){const f=v=>{v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4)};return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);}
 function blend(top,bot){const a=top.a; return {r:top.r*a+bot.r*(1-a),g:top.g*a+bot.g*(1-a),b:top.b*a+bot.b*(1-a),a:1};}
 function bgOf(e){const stack=[]; let n=e; while(n&&n.nodeType===1){const c=rgb(getComputedStyle(n).backgroundColor); if(c&&c.a>0){stack.push(c); if(c.a>=1) break;} n=n.parentElement;}
   let base={r:255,g:255,b:255,a:1}; if(stack.length&&stack[stack.length-1].a>=1) base=stack.pop(); else { const b=rgb(getComputedStyle(document.body).backgroundColor); if(b&&b.a>0) base=b; }
   while(stack.length) base=blend(stack.pop(),base); return base;}
 const out=[]; const seen=new Set();
 const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
 while(w.nextNode()){const t=w.currentNode; if(!t.textContent.trim()) continue; const e=t.parentElement; if(!e||seen.has(e)) continue; seen.add(e);
   const s=getComputedStyle(e); const r=e.getBoundingClientRect(); if(r.width===0||r.height===0||s.visibility==='hidden'||parseFloat(s.opacity)===0) continue;
   if(e.closest('svg,canvas,[aria-hidden=true]')) continue;
   let fg=rgb(s.color); if(!fg) continue; const bg=bgOf(e); if(fg.a<1) fg=blend(fg,bg);
   const L1=lum(fg),L2=lum(bg); const ratio=(Math.max(L1,L2)+0.05)/(Math.min(L1,L2)+0.05);
   const size=parseFloat(s.fontSize), bold=parseInt(s.fontWeight)>=700; const large=size>=24||(bold&&size>=18.66);
   const need=large?3:4.5; const disabled=e.closest('[disabled],.disabled,[aria-disabled=true]');
   if(ratio<need && !disabled) out.push({text:t.textContent.trim().slice(0,40),ratio:Math.round(ratio*100)/100,fg:s.color,bg:`rgb(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)})`,cls:(e.className&&typeof e.className==='string'?e.className:e.tagName).slice(0,40),size});
 }
 // placeholders
 for(const i of document.querySelectorAll('input[placeholder],textarea[placeholder]')){const r=i.getBoundingClientRect(); if(!r.width) continue; const s=getComputedStyle(i,'::placeholder'); const fg=rgb(s.color), bg=bgOf(i); if(!fg) continue; const f2=fg.a<1?blend(fg,bg):fg; const L1=lum(f2),L2=lum(bg); const ratio=(Math.max(L1,L2)+0.05)/(Math.min(L1,L2)+0.05); if(ratio<4.5) out.push({text:'placeholder:'+i.placeholder.slice(0,30),ratio:Math.round(ratio*100)/100,fg:s.color,cls:'::placeholder'});}
 return out;
}"""
res = {}
with sync_playwright() as p:
    b = p.chromium.launch()
    for th in THEMES:
        ctx = b.new_context(viewport={"width": 1600, "height": 1000})
        ctx.add_init_script("try{localStorage.setItem('drishti.theme','%s')}catch(e){}" % th)
        pg = ctx.new_page(); attach(pg, th)
        for u in PAGES:
            pg.goto(BASE + u); pg.wait_for_timeout(1500)
            actual = pg.evaluate("()=>document.documentElement.getAttribute('data-theme')")
            low = pg.evaluate(JS)
            res.setdefault(th, {})[u] = {"actual": actual, "low": low}
            shot(pg, "theme_%s_%s" % (th, re.sub(r"[^A-Za-z0-9]+", "_", u)[:40]))
            worst = sorted(low, key=lambda x: x["ratio"])[:3]
            print("%-13s %-45s theme=%-12s low=%3d worst=%s" % (th, u[:45], actual, len(low), [(w["text"][:20], w["ratio"]) for w in worst]), flush=True)
        ctx.close()
    b.close()
json.dump(res, open(HERE + "/contrast.json", "w"), indent=1)
dump_errs(HERE + "/errs_contrast.json")
