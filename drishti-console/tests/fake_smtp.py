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

"""A local SMTP server for tests: it takes every message on a port it picked itself and keeps it, so a test can read what the server mailed. No mail ever
leaves the machine. Minimal on purpose (HELO/EHLO, MAIL, RCPT, DATA, RSET, NOOP, QUIT; no TLS, no authentication)."""
from __future__ import annotations

import email
import email.policy
import socketserver
import threading


class _Handler(socketserver.StreamRequestHandler):
    def reply(self, line: str) -> None:
        self.wfile.write((line + "\r\n").encode())
        self.wfile.flush()

    def handle(self) -> None:
        self.reply("220 fake.smtp ESMTP ready")
        sender, recipients = "", []
        while True:
            raw = self.rfile.readline()
            if not raw:
                return
            line = raw.decode("utf-8", "replace").strip()
            verb = line.split(" ", 1)[0].upper()
            if verb in ("EHLO", "HELO"):
                self.reply("250-fake.smtp")
                self.reply("250 8BITMIME")
            elif verb == "MAIL":
                sender, recipients = line.split(":", 1)[1].strip().strip("<>"), []
                self.reply("250 ok")
            elif verb == "RCPT":
                recipients.append(line.split(":", 1)[1].strip().strip("<>"))
                self.reply("250 ok")
            elif verb == "DATA":
                self.reply("354 end with <CRLF>.<CRLF>")
                body = []
                while True:
                    part = self.rfile.readline()
                    if part in (b"\r\n.\r\n", b".\r\n") or part.rstrip(b"\r\n") == b".":
                        break
                    body.append(part[1:] if part.startswith(b"..") else part)
                self.server.keep(sender, recipients, b"".join(body))
                self.reply("250 queued")
            elif verb == "RSET":
                sender, recipients = "", []
                self.reply("250 ok")
            elif verb == "QUIT":
                self.reply("221 bye")
                return
            else:
                self.reply("250 ok")


class FakeSmtp(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.port = self.server_address[1]
        self.messages: list[dict] = []
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self.serve_forever, daemon=True)

    def keep(self, sender: str, recipients: list, raw: bytes) -> None:
        msg = email.message_from_bytes(raw, policy=email.policy.default)
        with self._lock:
            self.messages.append({"from": sender, "to": recipients, "message": msg, "raw": raw})

    def start(self) -> "FakeSmtp":
        self._thread.start()
        return self

    def stop(self) -> None:
        self.shutdown()
        self.server_close()

    def to(self, address: str) -> list[dict]:
        with self._lock:
            return [m for m in self.messages if address in m["to"]]

    @staticmethod
    def html(entry: dict) -> str:
        part = entry["message"].get_body(preferencelist=("html",))
        return part.get_content() if part else ""

    @staticmethod
    def attachments(entry: dict) -> list[tuple[str, bytes]]:
        return [(p.get_filename(), p.get_payload(decode=True)) for p in entry["message"].iter_attachments()]
