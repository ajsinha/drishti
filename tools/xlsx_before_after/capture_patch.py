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
"""Before/after evidence for the workbooks: a TEMPORARY patch of the tests that makes every workbook a test builds also land in the folder named by
DRISHTI_XLSX_CAPTURE (testkit XlsxCapture.java is copied into the tree too). Run it on the old code, run the tests, undo it with capture_unpatch.py, do the
same on the new code, then compare the two folders with XlsxCompare.java. Never commit what it changes. Usage: python capture_patch.py <repo> XlsxCapture.java.
(The old code's writer entry point was XlsxWriter.write; the new one is ExportWorkbook.write: edit the two names below for the old tree.)"""
root=pathlib.Path(sys.argv[1])
CAP="com.ash.drishti.testkit.XlsxCapture.save"
def stmt_end(t,i):
    d=0;q=None
    while i<len(t):
        c=t[i]
        if q:
            if c=='\\': i+=1
            elif c==q: q=None
        elif c in '"\'': q=c
        elif c in '([{': d+=1
        elif c in ')]}': d-=1
        elif c==';' and d==0: return i
        i+=1
def wrap(t,call):
    out='';i=0
    while True:
        if call=='unzip(':
            m=re.compile(r'(?<=[=(] )unzip\(|(?<=\()unzip\(').search(t,i); j=m.start() if m else -1
        else:
            j=t.find(call,i)
        if j<0: return out+t[i:]
        k=j+len(call);d=1;q=None
        while d:
            c=t[k]
            if q:
                if c=='\\': k+=1
                elif c==q: q=None
            elif c in '"\'': q=c
            elif c=='(': d+=1
            elif c==')': d-=1
            k+=1
        out+=(t[i:j]+call+CAP+"("+t[j+len(call):k-1]+"))") if call=="unzip(" else (t[i:j]+CAP+"("+t[j:k]+")");i=k
def edit(rel,fn):
    p=root/rel;t=p.read_text();n=fn(t)
    if n==t: print("NOCHANGE",rel)
    p.write_text(n)
def after_bytes(t,names=r'byte\[\] (\w+) = '):
    out='';i=0
    for m in re.finditer(names,t):
        pass
    res=[];pos=0
    for m in re.finditer(names,t):
        e=stmt_end(t,m.end())
        res.append((e,m.group(1)))
    for e,v in reversed(res):
        t=t[:e+1]+" "+CAP+"("+v+");"+t[e+1:]
    return t
def scans(t):
    return re.sub(r'(\n[ \t]*)(assertThat\((?:XlsxFormulas|Formulas)\.scan\((\w+|write\([^;]*\))\)\)\.isEmpty\(\);)',lambda m:m.group(1)+CAP+"("+m.group(3)+");"+m.group(1)+m.group(2),t) if False else t
B='modules/banking/java/banking-service/src/test/java/com/ash/drishti/banking/'
K='kernel/drishti-server/src/test/java/com/ash/drishti/server/'
# helper returns
for f in ['var/VarPlaygroundWorkbookTest','var/PvarPlaygroundWorkbookTest','frtb/FrtbPlaygroundWorkbookTest','simm/SimmPlaygroundWorkbookTest']:
    edit(B+f+'.java',lambda t:re.sub(r'(static byte\[\] write\([^)]*\) \{.*?)return out\.toByteArray\(\);',lambda m:m.group(1)+'return '+CAP+'(out.toByteArray());',t,count=1,flags=re.S))
for f in ['var/VarPlaygroundApiTest','var/VarExplainApiTest','frtb/FrtbApiTest','simm/SimmApiTest','var/VarBasketSheetApiTest','var/VarRegulatoryApiTest']:
    edit(B+f+'.java',lambda t:after_bytes(t,r'byte\[\] (xlsx|x|one|blank) = '))
edit(K+'pivot/PivotApiTest.java',lambda t:after_bytes(t,r'byte\[\] (xlsx) = '))
edit(K+'dist/RunExportTest.java',lambda t:wrap(t,'ExportWorkbook.write('))
edit(K+'dist/ExportWritersTest.java',lambda t:wrap(t,'ExportWorkbook.write('))
edit(K+'dist/XlsxChartsTest.java',lambda t:wrap(t,'ExportWorkbook.write('))
edit('modules/banking/java/banking-service/src/test/java/com/ash/drishti/server/capacity/CapacityControllerTest.java',lambda t:wrap(t,'unzip('))
for f in ['dist/DistApiTest','dist/StatementApiTest','dist/StatementRowsTest']:
    edit(K+f+'.java',lambda t:wrap(t,'unzip('))
edit('compute/drishti-bicompute/src/test/java/com/ash/drishti/bicompute/PivotExportTest.java',lambda t:wrap(t,'unzip('))
import shutil
d=root/'compute/drishti-bicompute/src/test/java/com/ash/drishti/testkit';d.mkdir(parents=True,exist_ok=True)
shutil.copy(sys.argv[2],d/'XlsxCapture.java')

