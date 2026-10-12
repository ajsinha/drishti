#!/usr/bin/env python3
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

"""A mail catcher for trying subscriptions and alerts on your own machine (tutorial 11).

It accepts any mail on 127.0.0.1 and writes each message to a file in a folder; it sends nothing anywhere. No sign-in, no TLS: for a laptop only.

    python3 docs/tutorials/data/smtp-catcher.py [port] [folder]        # default: 1025, ./caught-mail

Point the server at it (SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=1025) and open the .eml files with any mail program, or read them with Python's `email` module."""
import asyncio
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1025
OUT = sys.argv[2] if len(sys.argv) > 2 else "caught-mail"
os.makedirs(OUT, exist_ok=True)
count = [len(os.listdir(OUT))]


async def session(reader, writer):
    writer.write(b"220 catcher ready\r\n")
    await writer.drain()
    data_mode, lines = False, []
    while True:
        line = await reader.readline()
        if not line:
            break
        if data_mode:
            if line == b".\r\n":
                count[0] += 1
                with open(os.path.join(OUT, "%03d.eml" % count[0]), "wb") as f:
                    f.write(b"".join(lines))
                print("caught mail", count[0], flush=True)
                lines, data_mode = [], False
                writer.write(b"250 queued\r\n")
            else:
                lines.append(line[1:] if line.startswith(b"..") else line)
                continue
        else:
            cmd = line.strip().upper()
            if cmd.startswith(b"EHLO"):
                writer.write(b"250-catcher\r\n250 8BITMIME\r\n")
            elif cmd.startswith(b"DATA"):
                data_mode = True
                writer.write(b"354 end with a dot\r\n")
            elif cmd.startswith(b"QUIT"):
                writer.write(b"221 bye\r\n")
                await writer.drain()
                break
            else:
                writer.write(b"250 ok\r\n")
        await writer.drain()
    writer.close()


async def main():
    server = await asyncio.start_server(session, "127.0.0.1", PORT)
    print(f"catching mail on 127.0.0.1:{PORT}, writing to {OUT}/", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
