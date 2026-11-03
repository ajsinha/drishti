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

"""The Python Flight client (clients/python/drishti_flight.py) against a stand-in Flight service over TLS (a throw-away CA): the token at
the handshake and the session after it, a plan of two parts read from their nodes in parallel, a node down served by the next, a live
stream with its sequences and resume, refusals with Drishti's codes, pandas and polars. The real service is tested end to end in the
heavy run (FLIGHT_GUIDE.md, "Testing")."""
import datetime
import json
import sys
import threading
from pathlib import Path

import pytest

pa = pytest.importorskip("pyarrow")
fl = pytest.importorskip("pyarrow.flight")
crypto = pytest.importorskip("cryptography")

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "clients" / "python"))
from drishti_flight import Flight, FlightRefused, main  # noqa: E402

TOKEN = "drk_good"


def _pki(folder: Path):
    """A CA and a server certificate for localhost, as PEM files."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    import ipaddress

    now = datetime.datetime.now(datetime.timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "drishti-test-ca")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
          .not_valid_after(now + datetime.timedelta(days=2)).add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
          .sign(ca_key, hashes.SHA256()))
    key = ec.generate_private_key(ec.SECP256R1())
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])).issuer_name(ca_name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=2))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    (folder / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    cert_pem = cert.public_bytes(serialization.Encoding.PEM)
    key_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return cert_pem, key_pem


class _Auth(fl.ServerMiddlewareFactory):
    """The service's handshake: Basic with the token as the password, answered with a session; a session after that."""

    def __init__(self):
        self.sessions = {}
        self.seen = []

    def start_call(self, info, headers):
        auth = (headers.get("authorization") or [""])[0]
        obo = (headers.get("x-drishti-on-behalf-of") or [None])[0]
        if auth.startswith("Basic "):
            import base64
            token = base64.b64decode(auth[6:]).decode().split(":", 1)[1]
            if token != TOKEN:
                raise fl.FlightUnauthenticatedError("DRS-5601: the API token is unknown, revoked or expired")
            session = f"dfs_{len(self.sessions) + 1}"
            self.sessions[session] = obo
            self.seen.append(("token", obo))
            return _Session(session)
        if auth.startswith("Bearer ") and auth[7:] in self.sessions:
            self.seen.append(("session", auth[7:]))
            return None
        raise fl.FlightUnauthenticatedError("DRS-5601: no token: send authorization: Bearer <API token>")


class _NoHandshake(fl.ServerAuthHandler):
    """The handshake call itself does nothing: the headers carry the token (as the Java service's header authenticator does)."""

    def authenticate(self, outgoing, incoming):
        pass

    def is_valid(self, token):
        return b""


class _Session(fl.ServerMiddleware):
    def __init__(self, session):
        self.session = session

    def sending_headers(self):
        return {"authorization": f"Bearer {self.session}"}


def _table(start, n):
    return pa.table({"ID": pa.array(range(start, start + n), pa.int64()), "VALUE": pa.array([i * 1.5 for i in range(start, start + n)], pa.float64())})


class _Service(fl.FlightServerBase):
    """Two parts: part 0 on this node, part 1 named on a dead node first and this node second (a node down)."""

    def __init__(self, location, cert, key, auth):
        super().__init__(location, tls_certificates=[(cert, key)], middleware={"auth": auth}, auth_handler=_NoHandshake())
        self.loc = location
        self.requests = []

    def get_flight_info(self, context, descriptor):
        r = json.loads(descriptor.command)
        self.requests.append(r)
        if r["request"].get("kind") == "secret":
            raise fl.FlightUnauthorizedError("DRS-5602: this token lacks data:read:secret")
        here = fl.Location(f"grpc+tls://localhost:{self.port}")
        dead = fl.Location("grpc+tls://localhost:1")
        eps = [fl.FlightEndpoint(json.dumps(dict(r, part=0)).encode(), [here]),
               fl.FlightEndpoint(json.dumps(dict(r, part=1)).encode(), [dead, here])]
        return fl.FlightInfo(_table(0, 0).schema, descriptor, eps, 10, -1)

    def do_get(self, context, ticket):
        r = json.loads(ticket.ticket)
        if r.get("stream"):
            start = r.get("resume", 0)
            def batches():
                for seq in range(start + 1, 4):
                    yield _table(seq, 1).to_batches()[0], pa.py_buffer(seq.to_bytes(8, "little", signed=True))
            return fl.GeneratorStream(_table(0, 0).schema, batches())
        if r["request"].get("kind") == "big":
            raise fl.FlightServerError("DRS-5604: the read passed 6 rows, the limit of your role")
        return fl.RecordBatchStream(_table(r["part"] * 5, 5))

    def list_flights(self, context, criteria):
        yield fl.FlightInfo(_table(0, 0).schema, fl.FlightDescriptor.for_command(b'{"form":"kind","request":{"kind":"trade"}}'), [], -1, -1)

    def do_action(self, context, action):
        if action.type == "whoami":
            yield fl.Result(pa.py_buffer(b'{"person":"app","scopes":["data:read"]}'))


@pytest.fixture()
def service(tmp_path):
    cert, key = _pki(tmp_path)
    auth = _Auth()
    s = _Service("grpc+tls://localhost:0", cert, key, auth)
    t = threading.Thread(target=s.serve, daemon=True)
    t.start()
    yield s, auth, str(tmp_path / "ca.pem")
    s.shutdown()


def test_a_kind_is_read_part_by_part_in_parallel_and_a_node_down_is_served_by_the_next(service):
    s, auth, ca = service
    with Flight(f"grpc+tls://localhost:{s.port}", token=TOKEN, ca=ca) as f:
        t = f.kind("trade", paths=["ID", "VALUE"], where=["DESK = RATES"])
        assert t.num_rows == 10 and sorted(t.column("ID").to_pylist()) == list(range(10))
        assert s.requests[-1] == {"form": "kind", "request": {"kind": "trade", "paths": ["ID", "VALUE"], "where": ["DESK = RATES"]}}
        assert t.to_pandas()["VALUE"].sum() == pytest.approx(1.5 * 45)
        assert ("token", None) in auth.seen and any(k == "session" for k, _ in auth.seen)     # the token once, the session after it
        try:
            import polars as pl
        except ImportError:                                  # polars is optional: the client hands over a pyarrow.Table
            return
        assert pl.from_arrow(t).height == 10


def test_refusals_carry_drishtis_codes(service):
    s, auth, ca = service
    with pytest.raises(FlightRefused) as e:
        Flight(f"grpc+tls://localhost:{s.port}", token="drk_bad", ca=ca).whoami()
    assert e.value.code == "DRS-5601"
    with Flight(f"grpc+tls://localhost:{s.port}", token=TOKEN, ca=ca) as f:
        with pytest.raises(FlightRefused) as e:
            f.kind("secret")
        assert e.value.code == "DRS-5602"
        with pytest.raises(FlightRefused) as e:
            f.kind("big")
        assert e.value.code == "DRS-5604"


def test_a_live_stream_gives_each_sequence_and_resumes_after_the_one_held(service):
    s, auth, ca = service
    with Flight(f"grpc+tls://localhost:{s.port}", token=TOKEN, ca=ca, on_behalf_of="alice") as f:
        req = f.request("measure", "measure('var.scenarios', 'DESK:RATES')")
        assert [seq for seq, _ in f.stream(req)] == [1, 2, 3]
        assert [seq for seq, b in f.stream(req, resume=1)] == [2, 3]
    assert ("token", "alice") in auth.seen


def test_catalogue_whoami_and_the_command_line(service, capsys):
    s, auth, ca = service
    with Flight(f"grpc+tls://localhost:{s.port}", token=TOKEN, ca=ca) as f:
        assert f.catalogue() == [{"form": "kind", "request": {"kind": "trade"}}]
        assert f.whoami()["scopes"] == ["data:read"]
    assert main(["--url", f"grpc+tls://localhost:{s.port}", "--token", TOKEN, "--ca", ca, "kind", "trade", "--paths", "ID"]) == 0
    out = capsys.readouterr().out.splitlines()
    assert out[0] == '"ID","VALUE"' and len(out) == 11


def test_only_tls_and_a_token():
    with pytest.raises(ValueError, match="TLS only"):
        Flight("grpc+tcp://localhost:18815", token=TOKEN)
    with pytest.raises(ValueError, match="data:read"):
        Flight("grpc+tls://localhost:18815", token="")
