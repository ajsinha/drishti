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

# Project Drishti QA: tiny logging forward proxy (scratch only) :18969 -> :18961, to see what the console really sends.
import http.server, urllib.request, sys, json
LOGF = sys.argv[1]
class H(http.server.BaseHTTPRequestHandler):
    def _do(self):
        n = int(self.headers.get("content-length") or 0); body = self.rfile.read(n) if n else None
        hd = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection", "content-length")}
        with open(LOGF, "a") as f:
            f.write(json.dumps({"m": self.command, "p": self.path, "auth": hd.get("Authorization", "")[-12:], "user": hd.get("X-Drishti-User"), "body": (body or b"")[:300].decode("utf8", "replace")}) + "\n")
        req = urllib.request.Request("http://127.0.0.1:18961" + self.path, data=body, method=self.command, headers=hd)
        try: r = urllib.request.urlopen(req)
        except urllib.error.HTTPError as e: r = e
        data = r.read(); self.send_response(r.status)
        for k, v in r.headers.items():
            if k.lower() not in ("transfer-encoding", "connection"): self.send_header(k, v)
        self.end_headers(); self.wfile.write(data)
    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _do
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", 18969), H).serve_forever()
