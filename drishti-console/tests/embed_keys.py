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

"""A stand-in for the server's embed signing key, for the console's tests: makes ES256 tokens the way ``EmbedKeys`` does."""
import base64
import json
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


class Signer:
    def __init__(self, kid="k1"):
        self.kid, self.key = kid, ec.generate_private_key(ec.SECP256R1())

    def jwks(self) -> dict:
        n = self.key.public_key().public_numbers()
        return {"keys": [{"kty": "EC", "crv": "P-256", "alg": "ES256", "use": "sig", "kid": self.kid,
                          "x": b64(n.x.to_bytes(32, "big")), "y": b64(n.y.to_bytes(32, "big"))}]}

    def token(self, user="alice", app="demo", exp=None, aud="http://console.test", origins=("http://127.0.0.1:17968",),
              typ="drishti-embed+jwt", kid=None, **extra) -> str:
        head = {"alg": "ES256", "typ": typ, "kid": kid or self.kid}
        claims = {"iss": aud, "aud": aud, "sub": user, "azp": app, "scope": "read", "origins": list(origins),
                  "iat": int(time.time()), "exp": exp if exp is not None else int(time.time()) + 300, "typ": typ, **extra}
        body = f"{b64(json.dumps(head).encode())}.{b64(json.dumps(claims).encode())}"
        r, s = decode_dss_signature(self.key.sign(body.encode(), ec.ECDSA(hashes.SHA256())))
        return f"{body}.{b64(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"
