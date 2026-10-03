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

"""Synthetic samples from plain JSON Schemas: every generated document must validate against its schema."""
import json, sys
from qa import *
from jsonschema import Draft7Validator, Draft202012Validator, FormatChecker
S = {}
S["basic07"] = (7, {"$schema":"http://json-schema.org/draft-07/schema#","type":"object","required":["id","qty"],"properties":{
  "id":{"type":"string","pattern":"^T-[0-9]{4}$"},"qty":{"type":"integer","minimum":1,"maximum":100,"multipleOf":5},
  "side":{"enum":["BUY","SELL"]},"px":{"type":"number","exclusiveMinimum":0,"exclusiveMaximum":10},
  "when":{"type":"string","format":"date-time"},"day":{"type":"string","format":"date"},"mail":{"type":"string","format":"email"},
  "uid":{"type":"string","format":"uuid"},"c":{"const":"fixed"},"tags":{"type":"array","items":{"type":"string"},"minItems":2,"maxItems":4,"uniqueItems":True},
  "nul":{"type":["string","null"]}}})
S["ref07"] = (7, {"$schema":"http://json-schema.org/draft-07/schema#","definitions":{"leg":{"type":"object","required":["rate"],"properties":{"rate":{"type":"number","minimum":0,"maximum":1},"ccy":{"type":"string","minLength":3,"maxLength":3}}}},
  "type":"object","required":["pay","rec"],"properties":{"pay":{"$ref":"#/definitions/leg"},"rec":{"$ref":"#/definitions/leg"},"legs":{"type":"array","items":{"$ref":"#/definitions/leg"}}}})
S["allOf"] = (7, {"type":"object","allOf":[{"properties":{"a":{"type":"integer","minimum":3}},"required":["a"]},{"properties":{"b":{"type":"string","minLength":2}},"required":["b"]}]})
S["oneOf"] = (7, {"type":"object","required":["v"],"properties":{"v":{"oneOf":[{"type":"integer","minimum":10},{"type":"string","pattern":"^x+$"}]}}})
S["anyOf"] = (7, {"type":"object","properties":{"v":{"anyOf":[{"type":"integer"},{"type":"boolean"}]}},"required":["v"]})
S["patternProps"] = (7, {"type":"object","patternProperties":{"^px_[A-Z]{3}$":{"type":"number","minimum":0}},"additionalProperties":False,"minProperties":2})
S["additional"] = (7, {"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":{"type":"integer"},"required":["a"]})
S["enumInt"] = (7, {"type":"object","properties":{"lvl":{"enum":[1,2,3]},"mix":{"enum":["a",1,None,True]}},"required":["lvl","mix"]})
S["rec2020"] = (2020, {"$schema":"https://json-schema.org/draft/2020-12/schema","$defs":{"node":{"type":"object","required":["name"],"properties":{"name":{"type":"string"},"children":{"type":"array","items":{"$ref":"#/$defs/node"}}}}},"$ref":"#/$defs/node"})
S["prefixItems"] = (2020, {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["pt"],"properties":{"pt":{"type":"array","prefixItems":[{"type":"number"},{"type":"string"}],"items":False,"minItems":2}}})
S["dependent"] = (2020, {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"dependentRequired":{"a":["b"]},"required":["a"]})
S["ifthen"] = (7, {"type":"object","properties":{"k":{"enum":["x","y"]},"v":{"type":"integer"}},"required":["k"],"if":{"properties":{"k":{"const":"x"}}},"then":{"required":["v"]}})
S["not"] = (7, {"type":"object","properties":{"v":{"type":"integer","not":{"enum":[0,1,2]}}},"required":["v"],"minProperties":1})
S["examples"] = (7, {"type":"object","properties":{"p":{"type":"string","examples":["EXAMPLE-1"],"pattern":"^EXAMPLE-"},"d":{"type":"integer","default":42}},"required":["p"]})
S["contains"] = (2020, {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"a":{"type":"array","contains":{"type":"integer","minimum":100},"items":{"type":"integer"},"minItems":1}},"required":["a"]})
S["bigbounds"] = (7, {"type":"object","properties":{"n":{"type":"integer","minimum":-9007199254740993,"maximum":9007199254740993},"s":{"type":"string","minLength":50,"maxLength":60},"arr":{"type":"array","minItems":10,"items":{"type":"integer","minimum":0,"maximum":0}}},"required":["n","s","arr"]})
S["remoteRef"] = (7, {"type":"object","properties":{"a":{"$ref":"http://example.invalid/x.json"}},"required":["a"]})

S["emptySchema"] = (7, {})
S["trueSchemaProp"] = (7, {"type":"object","properties":{"a":True,"b":False},"required":["a"]})
S["formats"] = (7, {"type":"object","required":["a","b","c","d","e"],"properties":{"a":{"type":"string","format":"ipv4"},"b":{"type":"string","format":"uri"},"c":{"type":"string","format":"hostname"},"d":{"type":"string","format":"time"},"e":{"type":"string","format":"date"}}})
V = {7: Draft7Validator, 2020: Draft202012Validator}
did = None
st, d = j("POST", "/api/v1/builder/designs", body={"name": "synth-qa", "kind": "qak"}); did = d["id"]
only = sys.argv[1:]
for n, (dr, sch) in S.items():
    if only and n not in only: continue
    st, o = j("POST", f"/api/v1/builder/designs/{did}/samples", body={"schema": sch, "count": 12})
    if st != 200: print(f"{n:14} POST {st} {str(o)[:160]}"); continue
    names = [s["name"] for s in o["samples"]]
    bad = 0; first = None; docs = []
    for nm in names:
        st2, doc = j("GET", f"/api/v1/builder/designs/{did}/samples/document?name={nm}")
        docs.append(doc)
    fc = FormatChecker()
    v = V[dr](sch, format_checker=fc) if n not in ("remoteRef",) else None
    if v:
        for dc in docs:
            e = next(iter(v.iter_errors(dc.get("document", dc) if isinstance(dc, dict) and "document" in dc and len(dc) <= 3 else dc)), None)
            if e: bad += 1; first = first or (e.message[:100], str(dc)[:150])
    print(f"{n:14} {len(names)} samples, invalid={bad} {first or ''}")
    [j("DELETE", f"/api/v1/builder/designs/{did}/samples?name={nm}") for nm in names]
j("DELETE", f"/api/v1/builder/designs/{did}")
