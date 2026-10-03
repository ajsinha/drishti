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


"""Pack load / registry / admin routes as non-admins, with method and path variants."""
from sec import *
routes = [("POST", "/api/v1/admin/packs/genomics/load"), ("POST", "/api/v1/admin/packs/genomics/unload"), ("PUT", "/api/v1/admin/packs/genomics"), ("GET", "/api/v1/admin/packs"),
          ("POST", "/api/v1/admin/packs/../packs/genomics/load"), ("POST", "/api/v1/admin/packs/genomics/load/"), ("POST", "/api/v1/admin/packs/%67enomics/load"), ("POST", "/api/v1/admin/packs/genomics;a=b/load"),
          ("POST", "/api/v1/admin/packs/GENOMICS/load"), ("POST", "/api/v1/admin/registry/install"), ("GET", "/api/v1/admin/registry"), ("POST", "/api/v1/admin/packs/..%2f..%2fetc/load"),
          ("POST", "/api/v1/admin/packs/x/load?name=genomics"), ("DELETE", "/api/v1/admin/packs/genomics"), ("PATCH", "/api/v1/admin/packs/genomics"), ("POST", "/api/v1/admin/users"), ("PUT", "/api/v1/admin/role-definitions/evil"),
          ("POST", "/api/v1/admin/packs/genomics/load", ), ("POST", "/api/v1/builder/designs/import")]
for u in [None, "qa-viewer", "qa-author", "qa-approver", "qa-narrow", "qa-masked"]:
    for m, p in routes:
        s, b = api(u, m, p, {"name": "genomics", "username": "x", "roles": ["admin"]})
        ok = s in (400, 401, 403, 404, 405, 415)
        if "builder/designs/import" in p: ok = s in (400, 401, 415, 201)
        log(f"admin route {u}", f"{m} {p}", "refused (401/403/404/405)", f"{s} {b[:70]}", "PASS" if ok else "FAIL")
# a token claiming the admin role in a header / query / body must not be believed
for hd in [{"X-Drishti-User": "qa-root"}, {"X-Drishti-Roles": "admin"}, {"X-Forwarded-User": "qa-root"}, {"X-Drishti-User": "qa-root", "X-Drishti-Role": "admin"}]:
    tok = mint("qa-viewer", ["viewer"])
    s, h, b = call("GET", SRV + "/api/v1/admin/users", tok, headers=hd); log("header spoof", f"GET admin/users {hd}", "403", s, "PASS" if s == 403 else "FAIL")
