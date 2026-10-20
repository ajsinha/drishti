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

"""Whether a host is an address another machine can call: the rule of the server's HostAddress and tools/hostaddr.py, for the console's
discovered servers (core/cluster.py). Refused: localhost in any spelling (localhost., a name under it, ip6-localhost), a loopback, wildcard
or link-local address in any notation (127.0.1.1, 0, 2130706433, 0x7f000001, [::1], fe80::1), an address of this machine's container or
VM bridges (docker0's 172.17.0.1), and a name that resolves here to one of those (a machine's own host name is 127.0.1.1 on Debian and
Ubuntu). A name that cannot be resolved here is not refused. Stdlib only."""
from __future__ import annotations

import functools
import ipaddress
import re
import socket
import struct
import urllib.parse

VIRTUAL = re.compile(r"^(docker|br-|veth|virbr|vmnet|vboxnet|cni|flannel|cali|lxc|lxd|podman|tun|tap|zt|tailscale|wg|kube|vEthernet)", re.I)
_V4_ANY = re.compile(r"^(0x[0-9a-f]+|[0-9]+)(\.(0x[0-9a-f]+|[0-9]+)){0,3}$")


def inet_aton(h: str) -> ipaddress.IPv4Address | None:
    """An IPv4 address in any notation inet_aton reads: one to four parts, each decimal, 0x hex or 0 octal, the last filling the bytes
    left ('0' is 0.0.0.0, '2130706433' and '0x7f000001' are 127.0.0.1, '127.1' is 127.0.0.1); None when it is not one."""
    if not _V4_ANY.match(h):
        return None
    try:
        v = [int(x, 16) if x.startswith("0x") else int(x, 8) if len(x) > 1 and x.startswith("0") else int(x) for x in h.split(".")]
    except ValueError:
        return None
    n = len(v)
    if any(x > 255 for x in v[:-1]) or v[-1] >= 1 << (8 * (5 - n)):
        return None
    value = 0
    for i, x in enumerate(v[:-1]):
        value |= x << (8 * (3 - i))
    return ipaddress.IPv4Address(value | v[-1])


def _raw_ipv4(iface: str) -> str | None:
    try:
        import fcntl
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            packed = fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", iface[:15].encode()))
        return socket.inet_ntoa(packed[20:24])
    except (ImportError, OSError):
        return None


@functools.lru_cache(maxsize=1)
def bridge_addresses() -> dict[str, str]:
    """The IPv4 addresses of this machine's container and VM bridges, by address."""
    out: dict[str, str] = {}
    try:
        for _, name in socket.if_nameindex():
            if VIRTUAL.match(name):
                ip = _raw_ipv4(name)
                if ip:
                    out[ip] = name
    except (OSError, AttributeError):
        pass
    return out


def _bad(a: ipaddress.IPv4Address | ipaddress.IPv6Address) -> str | None:
    if a.is_loopback:
        return "loopback"
    if a.is_unspecified:
        return "wildcard"
    if a.is_link_local:
        return "link-local"
    bridge = bridge_addresses().get(str(a))
    return f"this machine's {bridge} bridge" if bridge else None


@functools.lru_cache(maxsize=256)
def _resolved(name: str) -> str | None:
    try:
        infos = socket.getaddrinfo(name, None)
    except (OSError, UnicodeError):
        return None
    for info in infos:
        try:
            a = ipaddress.ip_address(info[4][0].split("%")[0])
        except ValueError:
            continue
        why_not = _bad(a)
        if why_not:
            return f"{name} is {a} here: {why_not}"
    return None


def why(host: str | None) -> str | None:
    """What makes a host no address another machine can call, or None."""
    h = (host or "").strip().lower()
    if h.startswith("[") and h.endswith("]"):
        h = h[1:-1]
    h = h.rstrip(".")
    if not h:
        return None
    if h in ("localhost", "ip6-localhost", "ip6-loopback") or h.endswith(".localhost"):
        return "loopback"
    a4 = inet_aton(h)
    if a4 is not None:
        return _bad(a4)
    if ":" in h:
        try:
            return _bad(ipaddress.ip_address(h.split("%")[0]))
        except ValueError:
            return None
    return _resolved(h)


def unreachable_url(url: str | None) -> bool:
    """True when a URL's host is no address another machine can call."""
    if not url:
        return False
    try:
        return why(urllib.parse.urlsplit(str(url).strip()).hostname) is not None
    except ValueError:
        return False
