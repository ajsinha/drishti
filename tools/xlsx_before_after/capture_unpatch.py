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
"""Removes what capture_patch.py added. Usage: python capture_unpatch.py <repo> (then delete the copied XlsxCapture.java files)."""
import re,sys,subprocess,pathlib
root=pathlib.Path(sys.argv[1])
CAP="com.ash.drishti.testkit.XlsxCapture.save("
files=subprocess.check_output(['grep','-rl','XlsxCapture','--include=*.java',str(root)]).decode().split()
for f in files:
    if f.endswith('XlsxCapture.java'): continue
    t=open(f).read()
    t=re.sub(r' com\.ash\.drishti\.testkit\.XlsxCapture\.save\(\w+\);','',t)
    while True:
        j=t.find(CAP)
        if j<0: break
        k=j+len(CAP);d=1;q=None
        while d:
            c=t[k]
            if q:
                if c=='\\': k+=1
                elif c==q: q=None
            elif c in '"\'': q=c
            elif c=='(': d+=1
            elif c==')': d-=1
            k+=1
        t=t[:j]+t[j+len(CAP):k-1]+t[k:]
    open(f,'w').write(t)
