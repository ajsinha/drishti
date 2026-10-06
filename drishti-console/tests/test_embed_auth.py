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

"""EmbedAuth (the console's own check of the server's embed tokens) and the token life of a live stream (StreamGuard):
docs/architecture/ELEMENTS.md, sections 6 and 7.2; build step 7."""
import asyncio
import json
import time

import pytest

from core.channel import ChannelSession
from core.embed import EmbedError
from core.embed_auth import EmbedAuth
from core.embed_stream import StreamGuard
from embed_keys import Signer

ORIGIN = "http://127.0.0.1:17968"
AUD = "http://console.test"


def auth_for(signer_box, **kw):
    calls = []

    async def keys():
        calls.append(1)
        return signer_box[0].jwks()

    kw.setdefault("audiences", [AUD])
    return EmbedAuth(keys, **kw), calls


def verify(auth, tok, origin=ORIGIN):
    return asyncio.run(auth.verify(tok, origin))


def refused(auth, tok, code, origin=ORIGIN):
    with pytest.raises(EmbedError) as e:
        verify(auth, tok, origin)
    assert e.value.code == code
    return e.value


def test_a_good_token_passes_and_its_claims_come_back():
    s = Signer()
    auth, _ = auth_for([s])
    claims = verify(auth, s.token(user="bob", app="crm"))
    assert claims["sub"] == "bob" and claims["azp"] == "crm"


def test_expired_wrong_audience_wrong_origin_and_tampering_are_refused():
    s = Signer()
    auth, _ = auth_for([s])
    refused(auth, s.token(exp=int(time.time()) - 60), "DRS-8001")
    refused(auth, s.token(aud="http://elsewhere.test"), "DRS-8001")
    assert refused(auth, s.token(), "DRS-8002", origin="http://evil.example").status == 403
    head, body, sig = s.token().split(".")
    refused(auth, f"{head}.{body[:-2]}xx.{sig}", "DRS-8001")                       # the claims were changed
    refused(auth, Signer().token(), "DRS-8001")                                      # a forged signature under a known kid
    refused(auth, s.token(typ="JWT"), "DRS-8001")
    refused(auth, "a.b.c", "DRS-8001")
    refused(auth, "", "DRS-8001")


def test_an_unknown_kid_is_refused_and_a_rotated_one_is_fetched_once():
    box = [Signer("k1")]
    auth, calls = auth_for(box, min_refetch=0.0)
    verify(auth, box[0].token())
    assert len(calls) == 1
    verify(auth, box[0].token())                                                    # cached
    assert len(calls) == 1
    box[0] = Signer("k2")                                                           # the server rotated its key
    verify(auth, box[0].token())
    assert len(calls) == 2
    refused(auth, Signer("k9").token(), "DRS-8001")                                  # a kid the server never published
    assert len(calls) == 3


def test_forged_key_ids_cannot_make_the_console_hammer_the_server():
    box = [Signer("k1")]
    auth, calls = auth_for(box, min_refetch=3600.0)
    verify(auth, box[0].token())
    for i in range(5):
        refused(auth, Signer(f"x{i}").token(), "DRS-8001")
    assert len(calls) == 1


def test_the_keys_are_refetched_after_their_ttl_and_an_unreachable_server_means_unknown_key():
    s = Signer()
    state = {"down": False, "n": 0}

    async def keys():
        state["n"] += 1
        if state["down"]:
            raise RuntimeError("server away")
        return s.jwks()

    auth = EmbedAuth(keys, ttl=0.0, min_refetch=0.0)
    verify(auth, s.token())
    verify(auth, s.token())
    assert state["n"] == 2                                                          # ttl 0: every call refetches
    state["down"] = True
    refused(EmbedAuth(keys, ttl=3600.0), s.token(), "DRS-8001")                     # never fetched, server down: unknown key


def test_no_audience_configured_accepts_what_the_server_issued():
    s = Signer()
    auth, _ = auth_for([s], audiences=[])
    verify(auth, s.token(aud="anything"))


# -- the token life of a stream ----------------------------------------------------------------------------------------------
class Idle:
    """A backend with no upstream: the guard's own frames are all we look at."""

    def __init__(self, valid=True):
        self.valid = valid

    async def _get(self, path, ident=None, **q):
        from core.backend import BackendError

        if not self.valid:
            raise BackendError(401, "DRS-5010", "the user behind the token is disabled")
        return {"ok": True}


class Who:
    user = "alice"


def claims(exp, user="alice", app="demo"):
    return {"sub": user, "azp": app, "exp": exp}


async def collect(session, into):
    async def never():
        return False

    async def go():
        async for chunk in session.events([], never):
            into.append(chunk)

    return asyncio.create_task(go())


def frames(chunks, event):
    return [c for c in chunks if c.startswith(f"event: {event}\n")]


def test_a_fresh_token_keeps_the_stream_open_and_silence_ends_it_with_8001():
    async def main():
        session = ChannelSession(Idle(), Who(), lambda e, d: d, limit=4, app="demo")
        guard = StreamGuard(session, claims(time.time() + 0.6), grace=0.8, recheck=0)
        guard.start()
        out = []
        task = await collect(session, out)
        await asyncio.sleep(0.2)
        assert frames(out, "channel") and not frames(out, "token")
        await asyncio.sleep(0.7)                                                      # the token has run out
        tokens = frames(out, "token")
        assert len(tokens) == 1 and json.loads(tokens[0].split("data: ")[1])["d"]["expiresIn"] == 0
        newid = Who()
        guard.refresh(newid, claims(time.time() + 1.2))                               # the element's fresh token
        assert session.me is newid and not frames(out, "gone")
        await asyncio.sleep(0.9)                                                      # still open: the stream was not dropped
        assert not frames(out, "gone") and not frames(out, "end")
        await asyncio.sleep(1.8)                                                      # expired again, nothing came: enforced
        gone = frames(out, "gone")
        assert gone and json.loads(gone[0].split("data: ")[1])["d"]["code"] == "DRS-8001"
        await asyncio.wait_for(task, 6)
        assert frames(out, "end")
        guard.stop()

    asyncio.run(main())


def test_a_fresh_token_of_another_user_or_application_or_not_newer_is_refused():
    async def main():
        session = ChannelSession(Idle(), Who(), lambda e, d: d, limit=4, app="demo")
        guard = StreamGuard(session, claims(time.time() + 100), recheck=0)
        for bad in (claims(time.time() + 200, user="mallory"), claims(time.time() + 200, app="other"), claims(time.time() + 50)):
            with pytest.raises(EmbedError) as e:
                guard.refresh(object(), bad)
            assert e.value.code == "DRS-8001"
        guard.refresh(object(), claims(time.time() + 200))

    asyncio.run(main())


def test_the_server_recheck_ends_a_stream_whose_user_is_gone():
    async def main():
        session = ChannelSession(Idle(valid=False), Who(), lambda e, d: d, limit=4, app="demo")
        guard = StreamGuard(session, claims(time.time() + 100), recheck=0.3)
        guard.start()
        out = []
        task = await collect(session, out)
        await asyncio.sleep(1.0)
        gone = frames(out, "gone")
        assert gone and json.loads(gone[0].split("data: ")[1])["d"]["code"] == "DRS-5010"
        await asyncio.wait_for(task, 6)
        guard.stop()

    asyncio.run(main())
