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

from qa import *
import json
ROLES.update({'qa-admin':['admin'],'qa-author':['author'],'qa-author2':['author'],'qa-approver':['approver'],'qa-trader':['trader'],'qa-risk':['market-risk'],'qa-viewer':['viewer'],'qa-masked':['qa-masked']})
A='qa-author'
fx={}
def r(*a,**k):
    st,h,b=as_user(*a,**k); return st,b
st,b=r(A,'GET','/api/v1/views/trade/MX-20000001'); panels=[p['id'] for p in jl(b)['panels']]
st,b=r(A,'PUT','/api/v1/me/layouts/irs-fixfloat/trade',{'panels':[{'id':panels[0],'area':'main','span':8,'height':10}]}); print('layout',st,b[:120])
st,b=r(A,'PUT','/api/v1/me/pivots/search/trade',{'rows':['book'],'columns':['currency'],'values':[{'field':'mtm','agg':'sum'}]}); print('pivot',st,b[:120])
st,b=r(A,'PUT','/api/v1/me/pivots/panel/netting-set/trades',{'rows':['product'],'columns':[],'values':[{'field':'notional','agg':'sum'}]}); print('pivotpanel',st,b[:120])
st,b=r(A,'PUT','/api/v1/me/calc-snippets/s1',{'code':'print(1)','description':'d','kind':'trade'}); print('snippet',st,b[:120])
st,b=r(A,'PUT','/api/v1/me/workspaces/ws1',{'layout':'1x1','panes':[{'ref':{'kind':'trade','id':'MX-20000001'}}]}); print('ws',st,b[:120])
st,b=r(A,'POST','/api/v1/notes/trade/MX-20000001',{'body':'<script>alert(1)</script> note by author','path':'$.mtm'}); print('note',st,b[:200]); fx['note']=jl(b)['id'] if st==201 else None
st,b=r(A,'POST','/api/v1/me/tokens',{'name':'t1','days':5}); print('token',st,b[:200]); d=jl(b) or {}; fx['token_id']=(d.get('token') or {}).get('id'); fx['token_secret']=d.get('secret')
st,b=r(A,'GET','/api/v1/sutras/book/1/source'); src=b
st,b=r(A,'POST','/api/v1/sutras?note=qa',src+'\n# qa change\n',ctype='text/yaml'); print('proposal',st,b[:200]); fx['proposal']=(jl(b) or {}).get('proposal',{}).get('id')
st,b=r('qa-author2','POST','/api/v1/sutras?note=qa2',src+'\n# qa change 2\n',ctype='text/yaml'); print('proposal2',st,b[:200]); fx['proposal2']=(jl(b) or {}).get('proposal',{}).get('id')
st,b=r(A,'PUT','/api/v1/me/monitors/m1',{'entities':[{'kind':'trade','id':'MX-20000001'}]}); print('monitor',st,b[:100])
st,b=r(A,'PUT','/api/v1/me/alerts/rules/r1',{'kind':'trade','id':'MX-20000001','when':'$.mtm > 0'}); print('alert',st,b[:100])
json.dump(fx,open('fixtures.json','w')); print(fx)
