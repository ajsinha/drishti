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

"""S3 simulators for the tools' tests: moto's server (pure Python, run by the venv's own python, so it runs on the Windows QA
machine) and the MinIO server binary (Linux only), each on a free port in 18750-18759, plus the conditional-write probe and its
report line, the same as the Java side's com.ash.drishti.testkit.s3.

The MinIO binary comes from DRISHTI_MINIO_BIN (a path), the tools cache (DRISHTI_TOOLS_CACHE, default <repo>/tools/cache,
git-ignored) or a download from DRISHTI_MINIO_URL (default: the official release URL) checked against DRISHTI_MINIO_SHA256 or the
<url>.sha256sum beside it. MinIO no longer serves its community binaries (dl.min.io answers 410 Gone), so without one of those the
MinIO tests skip with that reason.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import os
import platform
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path

FIRST_PORT, LAST_PORT = 18750, 18759
MINIO_URL = "https://dl.min.io/server/minio/release/linux-{arch}/archive/minio.RELEASE.2025-04-22T22-12-26Z"
REPO = Path(__file__).resolve().parents[2]


class Skip(Exception):
    """The simulator cannot run here; the message says why (the test skips with it)."""


def free_port(taken: set[int] | None = None) -> int:
    for p in range(FIRST_PORT, LAST_PORT + 1):
        if taken and p in taken:
            continue
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", p))
                return p
            except OSError:
                continue
    raise RuntimeError(f"no free port left in {FIRST_PORT}-{LAST_PORT}")


@dataclass
class Endpoint:
    simulator: str
    url: str
    key: str
    secret: str

    def client(self):
        import boto3
        from botocore.config import Config
        return boto3.client("s3", endpoint_url=self.url, aws_access_key_id=self.key, aws_secret_access_key=self.secret,
                            region_name="us-east-1", config=Config(s3={"addressing_style": "path"}))

    def storage_options(self) -> dict[str, str]:
        """deltalake's (and lake publish's --storage-option) settings for this endpoint."""
        return {"AWS_ENDPOINT_URL": self.url, "AWS_ACCESS_KEY_ID": self.key, "AWS_SECRET_ACCESS_KEY": self.secret,
                "AWS_REGION": "us-east-1", "AWS_ALLOW_HTTP": "true"}

    def bucket(self, name: str):
        c = self.client()
        if name not in {b["Name"] for b in c.list_buckets().get("Buckets", [])}:
            c.create_bucket(Bucket=name)


@dataclass
class ConditionalWrites:
    simulator: str
    create_only: bool
    update_if_match: bool
    delete_if_match: bool
    atomic: bool

    def report(self) -> str:
        yes = lambda b: "covered" if b else "NOT honoured (skipped)"  # noqa: E731
        return (f"[S3 conditional writes] {self.simulator}: create-only {yes(self.create_only)}, update-if-match "
                f"{yes(self.update_if_match)}, delete-if-match {yes(self.delete_if_match)}, racing writers "
                + ("atomic (covered)" if self.atomic else "NOT atomic (skipped)"))

    def not_honoured(self, what: str) -> str:
        return f"{self.simulator} does not honour {what}; the Java in-process stand-in (FakeS3) covers it"


def _refused(call) -> bool:
    from botocore.exceptions import ClientError
    try:
        call()
        return False
    except ClientError as e:
        if e.response.get("ResponseMetadata", {}).get("HTTPStatusCode") in (409, 412):
            return True
        raise


def probe(e: Endpoint, bucket: str) -> ConditionalWrites:
    """Tries create-only, update-if-match, delete-if-match and racing creates on the bucket, and says which are honoured."""
    c, key = e.client(), f"probe/{uuid.uuid4()}"
    v1 = c.put_object(Bucket=bucket, Key=key, Body=b"one", IfNoneMatch="*")["ETag"]
    create = _refused(lambda: c.put_object(Bucket=bucket, Key=key, Body=b"again", IfNoneMatch="*"))
    v2 = c.put_object(Bucket=bucket, Key=key, Body=b"two", IfMatch=v1)["ETag"]
    update = _refused(lambda: c.put_object(Bucket=bucket, Key=key, Body=b"stale", IfMatch=v1))
    delete = _refused(lambda: c.delete_object(Bucket=bucket, Key=key, IfMatch=v1))
    if delete:
        delete = not _refused(lambda: c.delete_object(Bucket=bucket, Key=key, IfMatch=v2))
    c.delete_object(Bucket=bucket, Key=key)
    atomic = create
    for r in range(5 if atomic else 0):
        k = f"{key}-race-{r}"
        clients = [e.client() for _ in range(8)]
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            wins = sum(not w for w in pool.map(
                lambda cl: _refused(lambda: cl.put_object(Bucket=bucket, Key=k, Body=b"w", IfNoneMatch="*")), clients))
        c.delete_object(Bucket=bucket, Key=k)
        if wins != 1:
            atomic = False
            break
    return ConditionalWrites(e.simulator, create, update, delete, atomic)


class Moto:
    """moto's S3 server (pip install "moto[server]"), on a free port in 18750-18759, run by this same Python in a child process
    (`python -m moto.server`; no other executable) and stopped by PID. Not ThreadedMotoServer in this process: some deltalake
    calls (DeltaTable.is_deltatable) hold the GIL while they wait on S3, so a server thread in the same process never answers."""

    def __init__(self, log_dir: Path):
        try:
            import moto
            import moto.server  # noqa: F401
        except ImportError as e:
            raise Skip(f'moto is not installed ({e}): pip install "moto[server]==5.2.3" into the console venv') from e
        port = free_port()
        log_dir.mkdir(parents=True, exist_ok=True)
        self.log = open(log_dir / "moto.log", "wb")
        self.proc = subprocess.Popen([sys.executable, "-m", "moto.server", "-H", "127.0.0.1", "-p", str(port)],
                                     stdout=self.log, stderr=subprocess.STDOUT)
        self.endpoint = Endpoint(f"moto {moto.__version__} (python -m moto.server)", f"http://127.0.0.1:{port}", "testing", "testing")
        _wait_ready(self, self.endpoint.url + "/moto-api/", port)

    def close(self):
        _stop(self)


def _wait_ready(server, url: str, port: int):
    until = time.monotonic() + 30
    while time.monotonic() < until and server.proc.poll() is None:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            time.sleep(0.2)
    _stop(server)
    raise RuntimeError(f"{server.endpoint.simulator} did not become ready on port {port} (see {server.log.name})")


def _stop(server):
    """Stops the server by its PID."""
    try:
        os.kill(server.proc.pid, signal.SIGTERM)
        server.proc.wait(timeout=10)
    except ProcessLookupError:
        pass
    except subprocess.TimeoutExpired:
        server.proc.kill()
        server.proc.wait()
    server.log.close()


def tools_cache() -> Path:
    c = os.environ.get("DRISHTI_TOOLS_CACHE")
    return Path(c) if c else REPO / "tools" / "cache"


def minio_binary() -> Path:
    if os.environ.get("DRISHTI_MINIO_BIN"):
        p = Path(os.environ["DRISHTI_MINIO_BIN"])
        if not (p.is_file() and os.access(p, os.X_OK)):
            raise Skip(f"DRISHTI_MINIO_BIN is not an executable file: {p}")
        return p
    arch = "arm64" if platform.machine().lower() in ("aarch64", "arm64") else "amd64"
    url = os.environ.get("DRISHTI_MINIO_URL") or MINIO_URL.format(arch=arch)
    cached = tools_cache() / "minio" / url.rsplit("/", 1)[1]
    if cached.is_file() and os.access(cached, os.X_OK):
        return cached
    hint = "; set DRISHTI_MINIO_BIN, or DRISHTI_MINIO_URL and DRISHTI_MINIO_SHA256"
    try:
        expected = os.environ.get("DRISHTI_MINIO_SHA256")
        if not expected:
            with urllib.request.urlopen(url + ".sha256sum", timeout=15) as r:
                expected = r.read().decode().split()[0]
        cached.parent.mkdir(parents=True, exist_ok=True)
        part = cached.with_name(cached.name + ".part")
        sha = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=60) as r, open(part, "wb") as out:
            for chunk in iter(lambda: r.read(1 << 16), b""):
                sha.update(chunk)
                out.write(chunk)
    except urllib.error.HTTPError as e:
        raise Skip(f"the MinIO binary is not served at {url} (HTTP {e.code}){hint}") from e
    except OSError as e:
        raise Skip(f"cannot download MinIO (offline?): {e}{hint}") from e
    if sha.hexdigest().lower() != expected.lower():
        part.unlink(missing_ok=True)
        raise RuntimeError(f"the MinIO download from {url} has SHA-256 {sha.hexdigest()}, expected {expected}")
    part.chmod(0o755)
    part.replace(cached)
    return cached


class Minio:
    """The MinIO server binary (Linux only), its data in `data`, on a free port in 18750-18759; stopped by PID."""

    USER, PASSWORD = "drishti", "drishti-secret"

    def __init__(self, data: Path):
        if platform.system() != "Linux":
            raise Skip(f"MinIO tests run on Linux only (no native binaries on Windows): {platform.system()}")
        binary = minio_binary()
        data.mkdir(parents=True, exist_ok=True)
        port = free_port()
        console = free_port({port})
        env = dict(os.environ, MINIO_ROOT_USER=self.USER, MINIO_ROOT_PASSWORD=self.PASSWORD, MINIO_BROWSER="off")
        self.log = open(data.parent / f"{data.name}-minio.log", "wb")
        self.proc = subprocess.Popen([str(binary), "server", str(data), "--address", f"127.0.0.1:{port}",
                                      "--console-address", f"127.0.0.1:{console}"], env=env, stdout=self.log, stderr=subprocess.STDOUT)
        self.endpoint = Endpoint("MinIO (server binary)", f"http://127.0.0.1:{port}", self.USER, self.PASSWORD)
        _wait_ready(self, self.endpoint.url + "/minio/health/ready", port)

    def close(self):
        _stop(self)
