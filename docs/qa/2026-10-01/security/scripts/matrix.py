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
import json, urllib.parse as up
fx=json.load(open('fixtures.json'))
ROLES.update({'qa-admin':['admin'],'qa-author':['author'],'qa-author2':['author'],'qa-approver':['approver'],'qa-trader':['trader'],'qa-risk':['market-risk'],'qa-viewer':['viewer'],'qa-masked':['qa-masked']})
WHO=[None,'qa-viewer','qa-trader','qa-risk','qa-masked','qa-author','qa-approver','qa-admin','APITOKEN']
q=up.quote
E=[  # method, path, body, expectation (doc)
('GET','/api/v1/about',None,'any token'),
('GET','/api/v1/packs',None,'any token'),
('GET','/api/v1/me/packs',None,'any token'),
('GET','/api/v1/sources',None,'any token'),
('GET','/api/v1/sutras',None,'any token'),
('GET','/api/v1/sutras/irs-fixfloat/1',None,'any token'),
('GET','/api/v1/sutras/irs-fixfloat/1/source',None,'any token'),
('GET','/api/v1/sutras/problems',None,'any token'),
('GET','/api/v1/rachana/schema',None,'any token'),
('GET','/api/v1/business-date',None,'any token'),
('GET','/api/v1/health/live',None,'any token'),
('POST','/api/v1/command',{'text':'TRD MX-20000001'},'kind open (trade)'),
('GET','/api/v1/command/suggest?q=MX',None,'any; filtered'),
('GET','/api/v1/command/history',None,'any'),
('GET','/api/v1/command/aliases',None,'any'),
('GET','/api/v1/phrase?q='+q('trades over 1m'),None,'any'),
('GET','/api/v1/views/trade/MX-20000001',None,'kind trade'),
('GET','/api/v1/views/counterparty/CP-MERIDIAN-RE',None,'kind counterparty'),
('GET','/api/v1/views/netting-set/NS-MERIDIAN-RE-NY/panels/trades/records',None,'kind netting-set'),
('GET','/api/v1/entities/trade/MX-20000001/raw',None,'kind trade'),
('GET','/api/v1/history/trade/MX-20000001/diff',None,'kind trade'),
('GET','/api/v1/history/trade/MX-20000001/series?path=pnl1d',None,'kind trade'),
('GET','/api/v1/impact/counterparty/CP-MERIDIAN-RE',None,'kind counterparty'),
('GET','/api/v1/search?q='+q('TRD limit 1'),None,'kind trade'),
('GET','/api/v1/search?q='+q('CPTY limit 1'),None,'kind counterparty'),
('GET','/api/v1/search/csv?q='+q('TRD limit 1'),None,'kind trade'),
('GET','/api/v1/search/compare?from=2026-09-28&q='+q('TRD MX-20000001'),None,'kind trade'),
('GET','/api/v1/search/columns/trade?paths=book&limit=1',None,'calc + kind'),
('POST','/api/v1/search/pivot/trade',{'q':'TRD','rows':['book'],'columns':[],'values':[{'field':'notional','agg':'sum'}]},'kind trade'),
('POST','/api/v1/search/pivot/trade/drill',{'q':'TRD','rows':['book'],'columns':[],'values':[],'cell':{'rows':[],'columns':[]},'size':1},'kind trade'),
('POST','/api/v1/search/pivot/trade/values',{'q':'TRD','field':'book'},'kind trade'),
('GET','/api/v1/calc/settings',None,'any'),
('GET','/api/v1/me/calc-snippets',None,'calc'),
('PUT','/api/v1/me/calc-snippets/m-x',{'code':'1','kind':'trade'},'calc'),
('DELETE','/api/v1/me/calc-snippets/m-x',None,'calc'),
('GET','/api/v1/me/layouts',None,'any'),
('GET','/api/v1/me/layouts/irs-fixfloat/trade',None,'layout'),
('PUT','/api/v1/me/layouts/irs-fixfloat/trade',{'panels':[{'id':'terms','area':'main','span':6,'height':5}]},'layout + kind'),
('DELETE','/api/v1/me/layouts/irs-fixfloat/trade',None,'layout'),
('GET','/api/v1/me/layouts/irs-fixfloat/trade/promotion',None,'author+studio-save'),
('GET','/api/v1/me/pivots',None,'any'),
('GET','/api/v1/me/pivots/search/trade',None,'kind'),
('PUT','/api/v1/me/pivots/search/trade',{'rows':['book'],'columns':[],'values':[{'field':'notional','agg':'sum'}]},'kind'),
('DELETE','/api/v1/me/pivots/search/trade',None,'kind'),
('GET','/api/v1/me/pivots/panel/netting-set/trades/promotion',None,'author'),
('GET','/api/v1/me/settings',None,'any'),
('PATCH','/api/v1/me/settings',{'density':'compact'},'any'),
('GET','/api/v1/me/workspaces',None,'any'),
('GET','/api/v1/me/monitors',None,'any'),
('GET','/api/v1/me/alerts/rules',None,'any'),
('GET','/api/v1/me/alerts',None,'any'),
('GET','/api/v1/me/alerts/suggestions/trade',None,'any'),
('GET','/api/v1/me/reports',None,'any'),
('GET','/api/v1/me/tokens',None,'any'),
('GET','/api/v1/me/studio-tests/irs-fixfloat',None,'any?'),
('GET','/api/v1/workspaces/shared',None,'any'),
('GET','/api/v1/workspaces/shared/qa-author/ws1',None,'shared-with only'),
('GET','/api/v1/notes/trade/MX-20000001',None,'kind trade'),
('GET','/api/v1/studio/settings',None,'any'),
('POST','/api/v1/studio/preview',{'yaml':'rachana: 1','kind':'trade','id':'MX-20000001'},'kind (doc: authors use Studio)'),
('GET','/api/v1/studio/inferred/trade/MX-20000001',None,'kind'),
('GET','/api/v1/sutras/proposals',None,'any (visible)'),
('GET','/api/v1/sutras/proposals/'+str(fx['proposal']),None,'visible'),
('GET','/api/v1/sutras/book/history',None,'any'),
('GET','/api/v1/packs/trading/overview',None,'any'),
('GET','/api/v1/auth/me',None,'signed-in'),
('POST','/api/v1/auth/login',{'username':'qa-viewer','password':'QaPassw0rd-2026'},'service only'),
('POST','/api/v1/auth/oidc',{'idToken':'x','nonce':'y'},'service only'),
('GET','/api/v1/admin/users',None,'admin'),
('GET','/api/v1/admin/users/qa-viewer',None,'admin'),
('GET','/api/v1/admin/roles',None,'admin'),
('GET','/api/v1/admin/role-definitions',None,'admin'),
('GET','/api/v1/admin/role-definitions/qa-masked',None,'admin'),
('GET','/api/v1/admin/audit?limit=1',None,'admin'),
('GET','/api/v1/admin/status',None,'admin'),
('GET','/api/v1/admin/caches',None,'admin'),
('GET','/api/v1/admin/health',None,'admin'),
('GET','/api/v1/admin/access?limit=1',None,'admin'),
('GET','/api/v1/admin/access/stats',None,'admin'),
('GET','/api/v1/admin/tokens',None,'admin'),
('GET','/api/v1/admin/reports',None,'admin'),
('GET','/api/v1/admin/packs',None,'admin'),
('GET','/api/v1/admin/registry',None,'admin'),
]
WRITES_ADMIN=[  # non-admin only (admin run separately on throwaways)
('POST','/api/v1/admin/users',{'username':'qa-x','roles':['viewer'],'password':'QaPassw0rd-2026'},'admin'),
('PUT','/api/v1/admin/users/qa-viewer',{'displayName':'pwn','roles':['admin']},'admin'),
('POST','/api/v1/admin/users/qa-viewer/enabled',{'enabled':False},'admin'),
('POST','/api/v1/admin/users/qa-viewer/password',{'password':'Hijack12345x'},'admin'),
('DELETE','/api/v1/admin/users/qa-viewer',None,'admin'),
('PUT','/api/v1/admin/role-definitions/qa-evil',{'kinds':['*'],'raw':True},'admin'),
('DELETE','/api/v1/admin/role-definitions/qa-masked',None,'admin'),
('POST','/api/v1/admin/caches/engine/purge',None,'admin'),
('PUT','/api/v1/admin/packs/trading',{'enabled':False},'admin'),
('POST','/api/v1/admin/packs/trading/unload',None,'admin'),
('POST','/api/v1/admin/registry/x/1.0.0/install',None,'admin'),
('POST','/api/v1/admin/registry/x/rollback',None,'admin'),
('DELETE','/api/v1/admin/tokens/'+str(fx['token_id']),None,'admin'),
('DELETE','/api/v1/admin/reports/qa-author/none',None,'admin'),
('POST','/api/v1/sutras/proposals/'+str(fx['proposal'])+'/approve',{'comment':'x'},'approver/admin, not own'),
('POST','/api/v1/sutras/proposals/'+str(fx['proposal'])+'/reject',{'comment':'x'},'approver/admin'),
('POST','/api/v1/sutras/proposals/'+str(fx['proposal'])+'/withdraw',None,'author of proposal only'),
('POST','/api/v1/sutras?note=x','rachana: 1','author+studio-save'),
]
res={}
for (m,p,b,exp) in E+WRITES_ADMIN:
    row={}
    for u in WHO:
        if u=='qa-admin' and (m,p,b,exp) in WRITES_ADMIN: row[u]='skip'; continue
        if u=='APITOKEN':
            st,h,t=call(m,SRV+p,fx['token_secret'],b,ctype='text/yaml' if isinstance(b,str) else 'application/json')
        else:
            st,h,t=as_user(u,m,p,b,ctype='text/yaml' if isinstance(b,str) else 'application/json')
        code=(jl(t) or {}).get('code','') if isinstance(jl(t),dict) else ''
        row[u or 'anon']=f'{st}{(" "+code) if code else ""}'
    res[f'{m} {p}']={'exp':exp,'r':row}
    print(m,p,exp,'|',' '.join(f'{k}={v}' for k,v in row.items()))
json.dump(res,open('matrix.json','w'),indent=1)
