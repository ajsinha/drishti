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


_V4_ANY = re.compile(r"^(0x[0-9a-f]+|[0-9]+)(\.(0x[0-9a-f]+|[0-9]+)){0,3}$")


def inet_aton(h: str) -> ipaddress.IPv4Address | None:
    """An IPv4 address in any notation inet_aton reads (and so the HTTP clients that call it): one to four parts, each decimal, 0x hex or
    0 octal, the last filling the bytes left ('0' is 0.0.0.0, '2130706433' and '0x7f000001' are 127.0.0.1, '127.1' is 127.0.0.1)."""
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
    """The IPv4 addresses of this machine's container and VM bridges (docker0's 172.17.0.1), by address: never advertised."""
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
        return None                                        # unknown here: another machine may know it
    for info in infos:
        try:
            a = ipaddress.ip_address(info[4][0].split("%")[0])
        except ValueError:
            continue
        why = _bad(a)
        if why:
            return f"{name} is {a} here: {why}"
    return None


def why(host: str | None) -> str | None:
    """What makes a host no address another machine can call, or None: localhost in any spelling (localhost., a name under it,
    ip6-localhost), a loopback, wildcard or link-local address in any notation (127.0.1.1, 0, 2130706433, 0x7f000001, [::1], fe80::1), an
    address of this machine's container or VM bridges, or a name that resolves here to one of those (a machine's own host name is
    127.0.1.1 on Debian and Ubuntu). A name that cannot be resolved here is not refused. As the server's HostAddress."""
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


def loopback_or_wildcard(host: str | None) -> bool:
    """True when a host is no address another machine can call (see why)."""
    return why(host) is not None


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
    try:
        reason = why(urllib.parse.urlsplit(str(url).strip()).hostname)
    except ValueError:
        reason = None
    detail = f" ({reason})" if reason else ""
    return (f"'{url}' is a loopback or wildcard address{detail}: {what} must be an address other machines can call "
            f"({seen}use {suggestion(url)})")
