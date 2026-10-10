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

"""Generates the logistics pack's sample entities. Run from the repository root:
python3 packs/logistics/tools/make_samples.py
"""
import json
import math
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "samples"
catalog = []


def put(kind, id_, doc, title, subtitle, walk=None, live=True, source="port-ops", gen=512):
    doc["_meta"] = {"source": source, "generation": gen, "live": live, **({"walk": walk} if walk else {})}
    d = OUT / kind
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{id_}.json").write_text(json.dumps(doc, indent=1) + "\n")
    catalog.append({"kind": kind, "id": id_, "title": title, "subtitle": subtitle})


ports = {"PORT-SGSIN": ("Singapore", "SG", 1.6, 5), "PORT-LKCMB": ("Colombo", "LK", 2.1, 9),
         "PORT-EGPSD": ("Port Said", "EG", 3.4, 14), "PORT-NLRTM": ("Rotterdam", "NL", 2.8, 11)}
for pid, (name, cc, idx, wait) in ports.items():
    put("port", pid, {"portId": pid, "name": name, "country": cc, "congestionIndex": idx, "berthWaitHours": wait,
                      "vesselsAtAnchor": wait * 2},
        pid, f"Port · {name}, {cc}", walk={"berthWaitHours": 1, "congestionIndex": 0.1})

put("vessel", "VSL-9811000", {"vesselId": "VSL-9811000", "name": "MSC Aurora", "imo": "9811000", "flag": "Panama",
                              "speedKnots": 17.4, "headingDeg": 292, "latitude": 12.91, "longitude": 53.44,
                              "nextPort": "PORT-EGPSD", "eta": "2026-10-02", "teuCapacity": 14000, "teuLoaded": 12180,
                              "utilisation": 0.87},
    "VSL-9811000", "Vessel · MSC Aurora · 14,000 TEU", walk={"speedKnots": 0.3, "latitude": 0.02, "longitude": 0.03})

readings = [{"hour": f"H{h}", "celsius": round(4.6 + 0.5 * math.sin(h / 3.2) + (0.9 if 14 <= h <= 16 else 0), 1)} for h in range(1, 25)]
put("container", "CTR-MSKU1234567", {"containerId": "CTR-MSKU1234567", "type": "40' reefer", "setpointC": 5.0,
                                     "currentC": 4.6, "humidityPct": 0.78, "alarms": 1, "powerOn": True,
                                     "shipment": "SHP-10042", "vessel": "VSL-9811000", "readings": readings},
    "CTR-MSKU1234567", "Container · 40' reefer · set 5.0 °C", walk={"currentC": 0.15})

put("shipment", "SHP-10042", {
    "shipmentId": "SHP-10042", "status": "In transit", "customer": {"id": "CUS-ACME", "name": "Acme Pharma BV"},
    "origin": "PORT-SGSIN", "destination": "PORT-NLRTM", "vessel": "VSL-9811000", "container": "CTR-MSKU1234567",
    "departed": "2026-09-12", "eta": "2026-10-09", "etaDelayHours": 6, "weightKg": 18400, "declaredValue": 1250000,
    "currency": "USD", "incoterm": "FOB", "commodity": "Pharmaceuticals (2–8 °C)",
    "route": [
        {"leg": 1, "from": "PORT-SGSIN", "to": "PORT-LKCMB", "mode": "Sea", "departed": "2026-09-12", "arrived": "2026-09-17", "carrier": "MSC"},
        {"leg": 2, "from": "PORT-LKCMB", "to": "PORT-EGPSD", "mode": "Sea", "departed": "2026-09-18", "arrived": None, "carrier": "MSC"},
        {"leg": 3, "from": "PORT-EGPSD", "to": "PORT-NLRTM", "mode": "Sea", "departed": None, "arrived": None, "carrier": "MSC"}],
    "milestones": [
        {"date": "2026-09-10", "event": "Booked", "location": "Singapore"},
        {"date": "2026-09-12", "event": "Loaded and departed", "location": "Singapore"},
        {"date": "2026-09-17", "event": "Transhipped", "location": "Colombo"},
        {"date": "2026-09-26", "event": "Temperature alarm cleared", "location": "Arabian Sea"},
        {"date": "2026-09-30", "event": "Delay: Suez queue (+6 h)", "location": "Gulf of Aden"}]},
    "SHP-10042", "Shipment · Singapore → Rotterdam · reefer · Acme Pharma", walk={"etaDelayHours": 1})
put("shipment", "SHP-10077", {"shipmentId": "SHP-10077", "status": "Delivered", "origin": "PORT-LKCMB", "destination": "PORT-SGSIN",
                              "departed": "2026-08-30", "eta": "2026-09-04", "etaDelayHours": 0, "weightKg": 9200,
                              "declaredValue": 310000, "currency": "USD", "incoterm": "CIF", "commodity": "Tea"},
    "SHP-10077", "Shipment · Colombo → Singapore · delivered (no Sutra match: inferred)", live=False)

(OUT / "catalog.json").write_text(json.dumps(catalog, indent=1) + "\n")
print(len(catalog), "entities")
