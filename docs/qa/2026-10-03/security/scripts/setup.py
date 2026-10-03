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


from sec import *
adm = "qa-root"
def go(m, p, b=None):
    st, x = api(adm, m, p, b); log("setup", f"{m} {p}", "2xx", st); return st, x
go("PUT", "/api/v1/admin/role-definitions/qa-narrow", {"description": "qa: only counterparty and netting-set", "kinds": ["counterparty", "netting-set"], "author": False})
go("PUT", "/api/v1/admin/role-definitions/qa-masked", {"description": "qa: trade kinds, no raw (masked fields)", "kinds": ["trade", "counterparty", "netting-set"], "raw": False, "author": False})
go("PUT", "/api/v1/admin/role-definitions/qa-narrow-author", {"description": "qa: author, only counterparty", "kinds": ["counterparty"], "author": True})
for u, r in [("qa-admin", ["admin"]), ("qa-author", ["author"]), ("qa-author2", ["author"]), ("qa-approver", ["approver"]),
             ("qa-narrow", ["qa-narrow"]), ("qa-masked", ["qa-masked"]), ("qa-narrow-author", ["qa-narrow-author"]), ("qa-viewer", ["viewer"])]:
    go("POST", "/api/v1/admin/users", {"username": u, "displayName": u, "roles": r, "password": "qa-password-12345", "mustChangePassword": False})
