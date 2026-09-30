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

"""The operational and non-financial risk pack: loss events, risk and control self-assessments, key risk indicators,
audit and regulatory issues, operational-risk scenarios, third-party risk, cyber incidents and SMA capital, per desk
and legal entity of the banking packs.

    python3 tools/packgen/oprisk/make.py            write packs/operational-risk
    python3 tools/packgen/oprisk/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/oprisk/make.py --lake data/delta
"""
from __future__ import annotations

import hashlib
import math
import random
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import make_data as BANK  # noqa: E402
import taxonomy as T  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Operational and non-financial risk"
AS_OF = date(2026, 9, 30)
EVENT_TYPES = ["Internal fraud", "External fraud", "Employment practices and workplace safety", "Clients, products and business practices",
               "Damage to physical assets", "Business disruption and system failures", "Execution, delivery and process management"]

KINDS = [
    Kind("loss-event", "LOSS", "LOSS-", "Operational loss event", G, "An operational loss: Basel event type, business line, gross loss, recoveries and its timeline.", "eventId",
         [("Event type", "$.eventType", None, None, False), ("Desk", "$.deskName", None, None, False), ("Gross loss", "$.grossLoss", "amount0", None, False),
          ("Recoveries", "$.recoveries", "amount0", None, False), ("Net loss", "$.netLoss", "amount0", None, True), ("Status", "$.status", None, "status", False),
          ("Occurred", "$.occurred", "date", None, False)],
         [P("kv", "details", "Event", "$.details", key="F2"), P("ladder", "timeline", "Timeline", "$.timeline",
            [("Date", "@.date", "date", None, False), ("Step", "@.step", None, None, False), ("By", "@.by", None, None, False)], key="F3")],
         links={"desk": ("desk", "Desk"), "legalEntity": ("legal-entity", "Legal entity"), "rootCauseControl": ("risk-control", "Failed control")},
         badge="fmt($.netLoss, 'compact')"),
    Kind("risk-control", "RCSA", "RCSA-", "Risk and control assessment", G, "A risk, its inherent rating, the controls against it and the residual rating (RCSA).", "assessmentId",
         [("Risk", "$.risk", None, None, False), ("Desk", "$.deskName", None, None, False), ("Inherent", "$.inherent", None, None, False),
          ("Control effectiveness", "$.effectiveness", None, "status", False), ("Residual", "$.residual", None, None, True), ("Next review", "$.nextReview", "date", None, False)],
         [P("table", "controls", "Controls", "$.controls", [("Control", "@.control", None, None, False), ("Type", "@.type", None, None, False),
            ("Frequency", "@.frequency", None, None, False), ("Last test", "@.lastTest", "date", None, False), ("Result", "@.result", None, "status", False)], key="F2")],
         links={"desk": ("desk", "Desk")}, badge="$.residual"),
    Kind("key-risk-indicator", "KRI", "KRI-", "Key risk indicator", G, "A metric watched against amber and red thresholds, with its recent history.", "kriId",
         [("Indicator", "$.name", None, None, False), ("Desk", "$.deskName", None, None, False), ("Value", "$.value", "price2", None, True),
          ("Amber", "$.amber", "price2", None, False), ("Red", "$.red", "price2", None, False), ("Status", "$.status", None, "status", False)],
         [P("line", "history", "Last 20 business days", "$.history", x="date", y="value", fmt="price2", key="F2"),
          P("kv", "definition", "Definition", "$.definition", area="right")],
         links={"desk": ("desk", "Desk")}, badge="$.status", live={"value": 0.3}),
    Kind("issue", "ISSUE", "ISSUE-", "Issue and action", G, "An audit, regulatory or self-identified issue, its owner, due date and actions.", "issueId",
         [("Title", "$.title", None, None, False), ("Source", "$.source", None, None, False), ("Severity", "$.severity", None, None, True),
          ("Owner", "$.owner", None, None, False), ("Due", "$.due", "date", None, False), ("Status", "$.status", None, "status", False)],
         [P("ladder", "actions", "Actions", "$.actions", [("Due", "@.due", "date", None, False), ("Action", "@.action", None, None, False),
            ("Status", "@.status", None, "status", False)], key="F2")],
         links={"desk": ("desk", "Desk"), "relatedLoss": ("loss-event", "Related loss")}),
    Kind("oprisk-scenario", "OPSCN", "OPSCN-", "Operational-risk scenario", G, "A severe-but-plausible scenario with frequency and severity estimates.", "scenarioId",
         [("Scenario", "$.name", None, None, False), ("Event type", "$.eventType", None, None, False), ("Frequency (1 in N years)", "$.returnPeriod", "amount0", None, False),
          ("Severity p50", "$.p50", "compact", None, False), ("Severity p99.9", "$.p999", "compact", None, True)],
         [P("hbar", "severity", "Severity by percentile", "$.percentiles", label="percentile", value="loss", fmt="compact", key="F2"),
          P("kv", "context", "Context", "$.context", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity")}),
    Kind("third-party", "VEND", "VEND-", "Third-party (vendor)", G, "An outsourced service provider: criticality, services, concentration and incidents.", "vendorId",
         [("Vendor", "$.name", None, None, False), ("Criticality", "$.criticality", None, None, True), ("Services", "$.serviceCount", "amount0", None, False),
          ("Annual spend", "$.annualSpend", "compact", None, False), ("Last assessed", "$.lastAssessed", "date", None, False), ("SLA breaches (12m)", "$.slaBreaches", "amount0", None, False)],
         [P("table", "services", "Services", "$.services", [("Service", "@.service", None, None, False), ("Desk", "@.desk", None, None, False),
            ("Exit plan", "@.exitPlan", None, None, False)], key="F2"),
          P("table", "incidents", "Incidents", "$.incidents", [("Date", "@.date", "date", None, False), ("Incident", "@.incident", None, None, False),
            ("Impact", "@.impact", None, "status", False)], key="F3")]),
    Kind("cyber-incident", "CYBER", "CYBER-", "Cyber incident", G, "A cyber-security incident: severity, systems, detection and containment times, data affected.", "incidentId",
         [("Incident", "$.title", None, None, False), ("Severity", "$.severity", None, None, True), ("Detected in (h)", "$.detectHours", "price2", None, False),
          ("Contained in (h)", "$.containHours", "price2", None, False), ("Records affected", "$.recordsAffected", "amount0", None, False),
          ("Status", "$.status", None, "status", False)],
         [P("ladder", "timeline", "Timeline", "$.timeline", [("At", "@.at", None, None, False), ("Step", "@.step", None, None, False)], key="F2"),
          P("kv", "systems", "Systems", "$.systems", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity"), "relatedLoss": ("loss-event", "Related loss")}),
    Kind("oprisk-capital", "OPCAP", "OPCAP-", "Operational-risk capital", G, "Basel III standardised approach (SMA): business indicator, loss component and capital.", "calcId",
         [("Entity", "$.legalEntityName", None, None, False), ("Business indicator", "$.bi", "compact", None, False), ("BI component", "$.bic", "compact", None, False),
          ("ILM", "$.ilm", "price2", None, False), ("Capital", "$.capital", "compact", None, True), ("RWA", "$.rwa", "compact", None, False)],
         [P("hbar", "components", "Business indicator components", "$.components", label="component", value="amount", fmt="compact", key="F2"),
          P("line", "losses", "Annual losses (10 years)", "$.annualLosses", x="year", y="loss", fmt="compact", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity")}),
]


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def build(bank: dict) -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    desks = list(bank["desk"].values())
    people = ["J. Lin", "A. Novak", "R. Mehta", "S. Okafor", "C. Brandt", "M. Duarte", "K. Sato"]
    controls_lib = [("Four-eyes check on trade capture", "Preventive", "Per trade"), ("Daily P&L reconciliation", "Detective", "Daily"),
                    ("Limit breach escalation", "Detective", "Real time"), ("Access recertification", "Preventive", "Quarterly"),
                    ("Confirmation matching", "Detective", "Daily"), ("Model validation", "Preventive", "Annual"), ("Payment release dual approval", "Preventive", "Per payment")]
    for d in desks:
        r = rng("rcsa" + d["deskId"])
        for i, risk in enumerate(["Trade capture error", "Unauthorised trading", "Model risk", "Settlement failure"]):
            aid = f"RCSA-{d['deskId'][5:]}-{i + 1}"
            ctrls = r.sample(controls_lib, 3)
            eff = r.choice(["Effective", "Effective", "Partially effective", "Ineffective"])
            inherent = r.choice(["High", "Medium", "High", "Critical"])
            residual = {"Effective": "Low", "Partially effective": "Medium", "Ineffective": inherent}[eff]
            docs["risk-control"][aid] = {"assessmentId": aid, "risk": risk, "deskName": d["name"], "inherent": inherent, "effectiveness": eff, "residual": residual,
                                         "nextReview": (AS_OF + timedelta(days=r.randint(20, 180))).isoformat(),
                                         "controls": [{"control": c, "type": t, "frequency": f, "lastTest": (AS_OF - timedelta(days=r.randint(5, 120))).isoformat(),
                                                       "result": r.choice(["Passed", "Passed", "Exceptions noted"])} for c, t, f in ctrls],
                                         "desk": d["deskId"], "_meta": {"source": "grc-platform", "generation": 1}}
        for i, (name, unit, lo, hi) in enumerate([("Trade breaks older than 2 days", "count", 0, 40), ("Failed settlements", "count", 0, 25),
                                                   ("Manual price overrides", "count", 0, 60), ("Access exceptions", "count", 0, 10)]):
            kid = f"KRI-{d['deskId'][5:]}-{i + 1}"
            amber, red = hi * 0.5, hi * 0.8
            hist, v = [], r.uniform(lo, hi * 0.6)
            day = AS_OF
            while len(hist) < 20:
                if day.weekday() < 5:
                    hist.append({"date": day.isoformat(), "value": round(v, 2)})
                    v = max(lo, v + r.gauss(0, hi * 0.06))
                day -= timedelta(days=1)
            hist.reverse()
            val = hist[-1]["value"]
            docs["key-risk-indicator"][kid] = {"kriId": kid, "name": name, "deskName": d["name"], "value": val, "amber": amber, "red": red,
                                               "status": "Red" if val >= red else "Amber" if val >= amber else "Green", "history": hist,
                                               "definition": {"unit": unit, "frequency": "Daily", "owner": r.choice(people), "source": "Operations data mart"},
                                               "desk": d["deskId"], "_meta": {"source": "grc-platform", "generation": 1, "live": True, "walk": {"value": 0.3}}}
    for i in range(24):
        r = rng(f"loss{i}")
        d = desks[i % len(desks)]
        et = EVENT_TYPES[i % len(EVENT_TYPES)] if i % 3 else "Execution, delivery and process management"
        gross = round(r.lognormvariate(12.5, 1.4), -2)
        rec = round(gross * r.choice([0, 0, 0.1, 0.35, 0.8]), -2)
        occ = AS_OF - timedelta(days=r.randint(10, 700))
        disc = occ + timedelta(days=r.randint(0, 60))
        booked = disc + timedelta(days=r.randint(1, 30))
        rc = [x for x in docs["risk-control"] if x.startswith(f"RCSA-{d['deskId'][5:]}-")]
        lid = f"LOSS-{2025 + (i % 2)}-{i + 1:04d}"
        docs["loss-event"][lid] = {"eventId": lid, "eventType": et, "deskName": d["name"], "grossLoss": gross, "recoveries": rec, "netLoss": gross - rec,
                                   "status": r.choice(["Closed", "Closed", "Open", "Under review"]), "occurred": occ.isoformat(),
                                   "details": {"businessLine": {"DESK-RATES": "Trading and sales", "DESK-FX": "Trading and sales", "DESK-MM": "Commercial banking",
                                                                "DESK-SFT": "Agency services"}.get(d["deskId"], "Trading and sales"),
                                               "rootCause": r.choice(["Manual keying error", "Stale reference data", "Vendor outage", "Phishing", "Missed corporate action"]),
                                               "boundaryWithCreditRisk": False, "reportedToRegulator": gross > 1e6},
                                   "timeline": [{"date": occ.isoformat(), "step": "Occurred", "by": "—"}, {"date": disc.isoformat(), "step": "Discovered", "by": r.choice(people)},
                                                {"date": booked.isoformat(), "step": "Booked to loss ledger", "by": "Finance"}],
                                   "desk": d["deskId"], "legalEntity": d["legalEntity"], "rootCauseControl": r.choice(rc),
                                   "_meta": {"source": "loss-database", "generation": 1}}
    losses = list(docs["loss-event"])
    for i in range(12):
        r = rng(f"issue{i}")
        d = desks[i % len(desks)]
        iid = f"ISSUE-{i + 101}"
        due = AS_OF + timedelta(days=r.randint(-30, 150))
        docs["issue"][iid] = {"issueId": iid, "title": r.choice(["Incomplete trade surveillance coverage", "Reconciliation breaks not escalated",
                                                                   "Privileged access not recertified", "Model inventory gaps", "Vendor exit plan missing"]),
                              "source": r.choice(["Internal audit", "Regulator (PRA)", "Regulator (Fed)", "Self-identified"]), "severity": r.choice(["High", "Medium", "Low"]),
                              "owner": r.choice(people), "due": due.isoformat(), "status": "Overdue" if due < AS_OF else r.choice(["Open", "In progress"]),
                              "actions": [{"due": (due - timedelta(days=30 * k)).isoformat(), "action": a, "status": r.choice(["Done", "Open"])}
                                          for k, a in enumerate(["Root-cause analysis", "Remediation design", "Implement and validate"], start=0)][::-1],
                              "desk": d["deskId"], "relatedLoss": r.choice(losses), "_meta": {"source": "grc-platform", "generation": 1}}
    for i, (name, et) in enumerate([("Rogue trading in a large book", "Internal fraud"), ("Prolonged outage of the core booking system", "Business disruption and system failures"),
                                    ("Ransomware on the settlement platform", "External fraud"), ("Mis-selling of structured products", "Clients, products and business practices"),
                                    ("Critical vendor failure (market data)", "Business disruption and system failures")]):
        r = rng(name)
        p50 = round(r.uniform(2, 40) * 1e6, -5)
        docs["oprisk-scenario"][f"OPSCN-{i + 1:02d}"] = {
            "scenarioId": f"OPSCN-{i + 1:02d}", "name": name, "eventType": et, "returnPeriod": r.choice([10, 25, 50, 100]), "p50": p50, "p999": round(p50 * r.uniform(8, 25), -5),
            "percentiles": [{"percentile": p, "loss": round(p50 * m, -5)} for p, m in [("p50", 1), ("p90", 3.2), ("p99", 7.5), ("p99.9", 15)]],
            "context": {"narrative": f"{name}: estimated in a workshop with first-line experts, calibrated to external loss data.",
                        "workshop": r.choice(["Rates", "Operations", "Technology"]), "lastReviewed": (AS_OF - timedelta(days=r.randint(30, 300))).isoformat(),
                        "externalData": "ORX consortium (illustrative)"},
            "legalEntity": r.choice(list(bank["legal-entity"])), "_meta": {"source": "grc-platform", "generation": 1}}
    for i, (name, crit) in enumerate([("Northwind Market Data Ltd", "Critical"), ("Bluepeak Cloud Services", "Critical"), ("Harbour Payments Processing", "Important"),
                                      ("Quill Document Services", "Standard"), ("Atlas Trade Surveillance", "Important")]):
        r = rng(name)
        docs["third-party"][f"VEND-{i + 1:03d}"] = {
            "vendorId": f"VEND-{i + 1:03d}", "name": name, "criticality": crit, "serviceCount": r.randint(1, 6), "annualSpend": round(r.uniform(0.5, 40) * 1e6, -4),
            "lastAssessed": (AS_OF - timedelta(days=r.randint(30, 400))).isoformat(), "slaBreaches": r.randint(0, 9),
            "services": [{"service": s, "desk": r.choice(desks)["name"], "exitPlan": r.choice(["Tested", "Documented", "Missing"])} for s in r.sample(
                ["Real-time prices", "Reference data", "Hosting", "Payment gateway", "Document storage", "Surveillance alerts"], 3)],
            "incidents": [{"date": (AS_OF - timedelta(days=r.randint(5, 360))).isoformat(), "incident": r.choice(["Latency above SLA", "Outage 45 min", "Late file delivery"]),
                           "impact": r.choice(["Minor", "Moderate", "Major"])} for _ in range(r.randint(1, 4))],
            "_meta": {"source": "vendor-management", "generation": 1}}
    for i in range(6):
        r = rng(f"cyber{i}")
        t0 = AS_OF - timedelta(days=r.randint(3, 300))
        det, con = round(r.uniform(0.2, 72), 1), round(r.uniform(1, 120), 1)
        cid = f"CYBER-{i + 1:03d}"
        docs["cyber-incident"][cid] = {
            "incidentId": cid, "title": r.choice(["Phishing campaign against traders", "Credential stuffing on client portal", "Malware on a jump host",
                                                  "DDoS on public website", "Misconfigured storage bucket"]),
            "severity": r.choice(["Sev 1", "Sev 2", "Sev 3"]), "detectHours": det, "containHours": con, "recordsAffected": r.choice([0, 0, 120, 4500]),
            "status": r.choice(["Closed", "Monitoring"]),
            "timeline": [{"at": f"{t0.isoformat()} 09:12", "step": "First alert (SOC)"}, {"at": f"{t0.isoformat()} +{det}h", "step": "Confirmed"},
                         {"at": f"{t0.isoformat()} +{con}h", "step": "Contained"}],
            "systems": {"affected": r.choice(["Email gateway", "Client portal", "Settlement platform"]), "ownerTeam": "Cyber defence", "externalReported": r.random() < 0.3},
            "legalEntity": r.choice(list(bank["legal-entity"])), "relatedLoss": r.choice(losses), "_meta": {"source": "soc", "generation": 1}}
    for le, led in bank["legal-entity"].items():
        r = rng("sma" + le)
        comps = [{"component": c, "amount": round(r.uniform(a, b) * 1e9, -6)} for c, a, b in
                 [("Interest, leases and dividends", 1.5, 6), ("Services", 1.0, 4), ("Financial (trading and banking book)", 0.8, 3)]]
        bi = sum(c["amount"] for c in comps)
        bic = 0.12 * min(bi, 1e9) + 0.15 * max(0, min(bi, 30e9) - 1e9) + 0.18 * max(0, bi - 30e9)
        yearly = [{"year": str(y), "loss": round(r.lognormvariate(17.5, 0.6), -5)} for y in range(2016, 2026)]
        lc = 15 * sum(x["loss"] for x in yearly) / len(yearly)
        ilm = math.log(math.e - 1 + (lc / bic) ** 0.8)
        docs["oprisk-capital"][f"OPCAP-{le[3:]}"] = {"calcId": f"OPCAP-{le[3:]}", "legalEntityName": led["name"], "bi": round(bi), "bic": round(bic), "ilm": round(ilm, 3),
                                                     "capital": round(bic * ilm), "rwa": round(bic * ilm * 12.5), "components": comps, "annualLosses": yearly,
                                                     "legalEntity": le, "_meta": {"source": "capital-engine", "generation": 1}}
    return docs


def spec(bank: dict) -> PB.PackSpec:
    return PB.PackSpec(
        name="operational-risk", title="Operational and non-financial risk", requires=["banking-core"], generator="tools/packgen/oprisk/make.py",
        description="Loss events, RCSA, key risk indicators, issues and actions, scenarios, third-party and cyber risk, and SMA operational-risk capital.",
        domains={"oprisk": KINDS},
        examples=[("LOSS LOSS-2025-0001", "Operational loss event · timeline and failed control"), ("KRI KRI-RATES-1", "Key risk indicator against its thresholds"),
                  ("RCSA RCSA-FX-2", "Risk and control self-assessment"), ("ISSUE ISSUE-101", "Issue and actions"),
                  ("VEND VEND-001", "Critical vendor · services, exit plans, incidents"), ("OPCAP OPCAP-LDN", "SMA operational-risk capital")],
        overview="Operational risk is organised by the banking-core desks and legal entities: losses, controls and indicators belong to desks, capital "
                 "and scenarios to legal entities. A loss links to the control that failed, and issues and cyber incidents link to the losses they caused.",
        external_ids={"desk": set(bank["desk"]), "legal-entity": set(bank["legal-entity"])},
        roles={"oprisk": {"kinds": [k.kind for k in KINDS] + ["desk", "legal-entity"], "raw": True}})


if __name__ == "__main__":
    bank = BANK.build()
    PB.main(spec(bank), build(bank), T.graph_fields())
