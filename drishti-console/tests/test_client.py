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


"""The Python client (clients/python/drishti_client.py) against a stand-in server: paths, the token, dates, errors."""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "clients" / "python"))
from drishti_client import Drishti, DrishtiError, main  # noqa: E402

SEEN = []


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        SEEN.append((self.path, self.headers.get("Authorization"), self.headers.get("X-Drishti-As-Of")))
        if self.headers.get("Authorization") != "Bearer drk_good":
            return self._send(401, {"code": "DRS-5010", "detail": "API token unknown, revoked or expired"})
        if self.path.startswith("/api/v1/entities/trade/T-1/raw"):
            return self._send(200, {"data": {"mtm": 12.5, "legs": [{"rate": 0.04}], "counterparty": {"name": "Meridian"}}})
        if self.path.startswith("/api/v1/search?"):
            return self._send(200, {"columns": ["$.productType"], "labels": {"$.productType": "Product type"},
                                    "rows": [{"ref": {"kind": "trade", "id": "T-9"}, "title": "T-9", "values": {"$.productType": "REVOLVER"}}]})
        return self._send(404, {"code": "DRS-1001", "detail": "no source holds it"})

    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture(scope="module")
def server():
    httpd = HTTPServer(("127.0.0.1", 0), Fake)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_port}"
    httpd.shutdown()


def test_the_client_reads_fields_documents_and_searches(server):
    d = Drishti(server, token="drk_good")
    assert d.field("trade", "T-1", "mtm") == 12.5
    assert d.field("trade", "T-1", "legs[0].rate") == 0.04
    assert d.field("trade", "T-1", "counterparty.name") == "Meridian"
    assert d.search("TRD productType=Revolver", as_of="2026-09-29") == [{"kind": "trade", "id": "T-9", "title": "T-9", "Product type": "REVOLVER"}]
    assert any(p.startswith("/api/v1/search?q=TRD+productType%3DRevolver") and a == "2026-09-29" for p, _, a in SEEN)


def test_the_client_says_what_the_server_refused(server, capsys):
    with pytest.raises(DrishtiError) as e:
        Drishti(server, token="drk_bad").document("trade", "T-1")
    assert e.value.status == 401 and e.value.code == "DRS-5010"
    with pytest.raises(DrishtiError) as e:
        Drishti(server, token="drk_good").document("trade", "NOPE")
    assert e.value.code == "DRS-1001"
    assert main(["--url", server, "--token", "drk_good", "search", "TRD productType=Revolver"]) == 0
    assert capsys.readouterr().out.splitlines()[0] == "kind,id,title,Product type"
