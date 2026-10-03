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

"""All 7 themes x desktop/phone on the workbench and Build pages: sideways scroll, contrast (WCAG AA), screenshots."""
import json, re, sys
from wb import *
HERE = __file__.rsplit("/", 1)[0]
THEMES = ["terminal", "light", "blue", "wallstreet", "green", "crimson", "crimson-dark"]
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

OVER = """()=>{const vw=document.documentElement.clientWidth; const bad=[]; document.querySelectorAll('body *').forEach(e=>{const r=e.getBoundingClientRect(); if(r.width&&r.right>vw+2&&getComputedStyle(e).position!=='fixed'&&!e.closest('[hidden],.visually-hidden,.dropdown-menu,.CodeMirror-scroll,.table-scroll,.wb-ghost')){bad.push((e.className||e.tagName).toString().slice(0,40)+':'+Math.round(r.right))}}); return {sw:document.documentElement.scrollWidth,cw:vw,wide:bad.slice(0,5),n:bad.length}}"""
p, b, ctx0, pg0 = start()
did = make_design(pg0, "QA themes").rsplit("/",1)[-1]; ctx0.close()
res = {}
for w in (1440, 390):
  for th in THEMES:
    ctx = b.new_context(viewport={"width": w, "height": 900}); ctx.add_init_script("try{localStorage.setItem('drishti.theme','%s')}catch(e){}" % th)
    pg = ctx.new_page(); login(pg, "author")
    for name, u, pre in (("design", f"/build/d/{did}", None), ("inspector-sel", f"/build/d/{did}", "sel"), ("yaml", f"/build/d/{did}?tab=yaml", None), ("new", "/build/new", None), ("mydesigns", "/build", None), ("review", "/build/reviews/P-000001", None)):
        pg.goto(BASE + u); pg.evaluate("(t)=>document.documentElement.setAttribute('data-theme',t)", th); pg.wait_for_timeout(1800)
        if pre == "sel": pg.click("[data-panel=details] .pnl-h"); pg.wait_for_timeout(500)
        o = pg.evaluate(OVER); low = pg.evaluate(JS); actual = pg.evaluate("()=>document.documentElement.getAttribute('data-theme')")
        res[f"{th}/{w}/{name}"] = {"over": o, "low": low, "theme": actual}
        worst = sorted(low, key=lambda x: x["ratio"])[:3]
        print("%-12s %4d %-14s theme=%-12s overflow=%s/%s n=%d low=%3d worst=%s" % (th, w, name, actual, o["sw"], o["cw"], o["n"], len(low), [(x["text"][:18], x["ratio"]) for x in worst]), flush=True)
        if name in ("design","inspector-sel") and th in ("terminal","light","crimson-dark","wallstreet"): shot(pg, f"s15-{th}-{w}-{name}")
    ctx.close()
json.dump(res, open(HERE + "/s15_themes.json", "w"), indent=1)
b.close()
