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


"""A Drishti*net member's real address (docs/admin/NET.md): members advertise themselves, and name their seeds, only by an address another
machine can call, never localhost, a loopback address (127.x.x.x, ::1) or a wildcard one (0.0.0.0, ::). This machine's primary IPv4 address
is the address of the interface the default route leaves by, else the first interface that is up and is not a loopback, a container bridge
or a virtual link, as the server's HostAddress finds it. Used by `drishti.py net`, drishti-stack and the tests that run several nodes on one
machine."""
from __future__ import annotations

import functools
import ipaddress
import re
import socket
import struct
import urllib.parse
from pathlib import Path

VIRTUAL = re.compile(r"^(docker|br-|veth|virbr|vmnet|vboxnet|cni|flannel|cali|lxc|lxd|podman|tun|tap|zt|tailscale|wg|kube|vEthernet)", re.I)


def _usable(ip: str) -> bool:
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return a.version == 4 and not (a.is_loopback or a.is_unspecified or a.is_link_local or a.is_multicast)


def _ipv4_of(iface: str) -> str | None:
    """The IPv4 address of a Linux interface (SIOCGIFADDR), or None."""
    try:
        import fcntl
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            packed = fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", iface[:15].encode()))
        ip = socket.inet_ntoa(packed[20:24])
        return ip if _usable(ip) else None
    except (ImportError, OSError):
        return None


def _default_route_iface() -> str | None:
    route = Path("/proc/net/route")
    try:
        lines = route.read_text().splitlines()[1:]
    except OSError:
        return None
    best, metric = None, None
    for line in lines:
        f = line.split()
        if len(f) > 7 and f[1] == "00000000" and f[7] == "00000000" and not VIRTUAL.match(f[0]):
            if metric is None or int(f[6]) < metric:
                best, metric = f[0], int(f[6])
    return best


@functools.lru_cache(maxsize=1)
def primary_ipv4() -> str | None:
    """This machine's primary IPv4 address, or None when it has no address but loopback."""
    iface = _default_route_iface()
    if iface:
        ip = _ipv4_of(iface)
        if ip:
            return ip
    try:                                    # no packet is sent: connecting a UDP socket only picks the route
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            ip = s.getsockname()[0]
            if _usable(ip):
                return ip
    except OSError:
        pass
    try:
        for _, name in socket.if_nameindex():
            if not VIRTUAL.match(name) and name != "lo":
                ip = _ipv4_of(name)
                if ip:
                    return ip
    except (OSError, AttributeError):
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if _usable(info[4][0]):
                return info[4][0]
    except OSError:
        pass
    return None


def loopback_or_wildcard(host: str | None) -> bool:
    """True when a host is localhost (or a name under it), a loopback address or a wildcard address."""
    h = (host or "").strip().lower().strip("[]")
    if h in ("localhost", "") or h.endswith(".localhost"):
        return h != ""
    try:
        a = ipaddress.ip_address(h)
    except ValueError:
        return False
    return a.is_loopback or a.is_unspecified


def loopback_url(url: str | None) -> bool:
    """True when a URL's host is loopback or wildcard."""
    if not url:
        return False
    try:
        return loopback_or_wildcard(urllib.parse.urlsplit(str(url).strip()).hostname)
    except ValueError:
        return False


def suggestion(url: str) -> str:
    ip = primary_ipv4() or "<this machine's IP>"
    try:
        u = urllib.parse.urlsplit(str(url).strip())
        return f"{u.scheme or 'http'}://{ip}" + (f":{u.port}" if u.port else "")
    except ValueError:
        return f"http://{ip}:<port>"


def refusal(what: str, url: str) -> str:
    """The refusal of a loopback or wildcard address, naming the address to use instead (as the server's message)."""
    ip = primary_ipv4()
    seen = f"this machine's address is {ip}: " if ip else ""
    return f"'{url}' is a loopback or wildcard address: {what} must be an address other machines can call ({seen}use {suggestion(url)})"
