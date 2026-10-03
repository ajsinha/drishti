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

from wb import *
import json
p, b, ctx, pg = start(); attach(pg, "s16")
make_design(pg, "QA a11y")
JS = """()=>{
 const name=e=>{let n=e.getAttribute('aria-label')||''; if(!n){const l=e.getAttribute('aria-labelledby'); if(l){const x=document.getElementById(l.split(' ')[0]); if(x)n=x.innerText}} if(!n&&e.id){const l=document.querySelector('label[for="'+e.id+'"]'); if(l)n=l.innerText} if(!n&&e.closest('label'))n=e.closest('label').innerText; if(!n)n=e.getAttribute('title')||''; if(!n&&['BUTTON','A','SUMMARY'].includes(e.tagName))n=e.innerText; return n.trim()};
 const vis=e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(e).visibility!=='hidden'};
 const un=[...document.querySelectorAll('button,a[href],input:not([type=hidden]),select,textarea,summary,[role=button],[role=tab],[role=treeitem],[role=option]')].filter(e=>!e.closest('[hidden]')&&vis(e)&&!name(e)).map(e=>e.tagName+'.'+(e.className||'').toString().slice(0,30)+'#'+e.id);
 const lm=['main','nav','aside','header','footer','section[aria-label],section[aria-labelledby]','[role=region]','[role=search]'].map(s=>s+':'+document.querySelectorAll(s).length);
 const hs=[...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].filter(vis).map(h=>h.tagName+':'+h.innerText.trim().slice(0,25));
 const lives=[...document.querySelectorAll('[aria-live],[role=status],[role=alert]')].map(e=>(e.getAttribute('role')||'')+'/'+(e.getAttribute('aria-live')||'')+'/'+(e.dataset&&Object.keys(e.dataset)[0]||e.className).toString().slice(0,20));
 const ids={}; document.querySelectorAll('[id]').forEach(e=>ids[e.id]=(ids[e.id]||0)+1); const dup=Object.keys(ids).filter(k=>ids[k]>1);
 const tabs=[...document.querySelectorAll('[role=tab]')].map(t=>t.innerText.trim().slice(0,14)+':'+t.getAttribute('aria-selected')+':'+t.tabIndex+':'+(document.getElementById(t.getAttribute('aria-controls'))?'ok':'MISSING'));
 const btn=[...document.querySelectorAll('button,a[href],input,select')].filter(vis).filter(e=>{const r=e.getBoundingClientRect();return r.width<24||r.height<24}).map(e=>(name(e)||e.tagName).slice(0,20)+' '+Math.round(e.getBoundingClientRect().width)+'x'+Math.round(e.getBoundingClientRect().height));
 return {unnamed:un,landmarks:lm,headings:hs,lives:lives,dupIds:dup,tabs:tabs,small:btn.slice(0,30),smallN:btn.length}}"""
r = pg.evaluate(JS)
for k,v in r.items(): print(k, ":", v)
# tree semantics
print("tree:", pg.evaluate("()=>[...document.querySelectorAll('[role=tree] [role=treeitem]')].slice(0,5).map(e=>[e.getAttribute('aria-level'),e.getAttribute('aria-expanded'),e.getAttribute('aria-selected'),e.tabIndex,e.innerText.replace(/\\n/g,' ').slice(0,30)])"))
print("canvas panel attrs:", pg.evaluate("()=>{const e=document.querySelector('[data-preview] .pnl');return [e.getAttribute('role'),e.getAttribute('aria-roledescription'),e.getAttribute('aria-label'),e.tabIndex]}"))
print("preview group:", pg.evaluate("()=>{const e=document.querySelector('[data-preview]');return [e.getAttribute('role'),e.getAttribute('aria-label')]}"))
print("handles aria-hidden (resize handles have keyboard alt):", pg.evaluate("()=>[...document.querySelectorAll('.lh-e,.lh-s')].slice(0,2).map(e=>e.getAttribute('aria-hidden'))"))
# live announcements: is the same text in both regions?
pg.locator("[data-palette] li[data-kind=gauge] button").click(); pg.wait_for_timeout(900)
print("say role:", pg.get_attribute("[data-say]","role"), "| say:", say(pg)[:60], "| live:", live(pg)[:60])
# tabs arrow keys
pg.focus("#wbTabDesign"); pg.keyboard.press("ArrowRight"); print("ArrowRight on tab ->", pg.evaluate("()=>document.activeElement.id"), pg.get_attribute("#wbTabYaml","aria-selected")); pg.keyboard.press("End"); print("End ->", pg.evaluate("()=>document.activeElement.id")); pg.keyboard.press("Home"); print("Home ->", pg.evaluate("()=>document.activeElement.id"))
# focus visible
pg.focus("[data-autodesign]"); print("focus ring btn:", pg.evaluate("()=>{const s=getComputedStyle(document.activeElement);return [s.outlineStyle,s.outlineWidth,s.outlineColor,s.boxShadow.slice(0,60)]}"))
pg.focus("[data-yaml-src], #wbTabYaml"); 
pg.focus("#wbFilter"); print("focus ring input:", pg.evaluate("()=>{const s=getComputedStyle(document.activeElement);return [s.outlineStyle,s.outlineWidth,s.boxShadow.slice(0,60)]}"))
pg.locator("[data-panel=details]").focus(); print("focus ring panel:", pg.evaluate("()=>{const s=getComputedStyle(document.activeElement);return [s.outlineStyle,s.outlineWidth,s.outlineColor,s.boxShadow.slice(0,60)]}"))
# dialogs/menus: File, Ship, palette — role, trap, esc restore
def trap(opener_sel, label):
    pg.focus(opener_sel); pg.keyboard.press("Enter"); pg.wait_for_timeout(500)
    dlg = pg.evaluate("()=>{const d=document.querySelector('[role=dialog]:not([hidden]),dialog[open],[aria-modal=true]');return d?[d.getAttribute('role')||d.tagName,d.getAttribute('aria-modal'),d.getAttribute('aria-label')||d.getAttribute('aria-labelledby')]:null}")
    seq=[]; 
    for i in range(14):
        pg.keyboard.press("Tab"); seq.append(pg.evaluate("()=>{const e=document.activeElement;return (e.getAttribute('aria-label')||e.innerText||e.tagName).slice(0,18)+(document.querySelector('[role=dialog]:not([hidden]),[aria-modal=true]')&&document.querySelector('[role=dialog]:not([hidden]),[aria-modal=true]').contains(e)?'':'  <OUTSIDE>')}"))
    outside = sum('OUTSIDE' in s for s in seq)
    pg.keyboard.press("Escape"); pg.wait_for_timeout(400)
    back = pg.evaluate("()=>(document.activeElement.getAttribute('aria-label')||document.activeElement.innerText||document.activeElement.tagName).slice(0,20)")
    print(f"[{label}] dialog={dlg} tab x14 outside={outside} seq={seq[:6]} | after Esc focus: {back}")
trap("[data-file-menu]", "File"); trap("[data-ship-menu]", "Ship"); trap("[data-palette-open]", "Commands")
trap("[data-add-menu]", "Add panel"); 
b.close()
