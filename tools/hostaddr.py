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


"""A Drishti*net member's real address (docs/guides/OPERATIONS_GUIDE.md): members advertise themselves, and name their seeds, only by an address another
machine can call, never localhost, a loopback address (127.x.x.x, ::1) or a wildcard one (0.0.0.0, ::). This machine's primary IPv4 address
is the address of the interface the default route leaves by, else the first interface that is up and is not a loopback, a container bridge
or a virtual link, as the server's HostAddress finds it. Used by `drishti.py net`, drishti-stack and the tests that run several nodes on one
machine."""
from __future__ import annotations

import functools
import importlib.util
import ipaddress
import re
import socket
import struct
import urllib.parse
from pathlib import Path

_RULE = importlib.util.spec_from_file_location("drishti_hostrule", Path(__file__).resolve().parent.parent / "drishti-console" / "core" / "hostrule.py")
_hostrule = importlib.util.module_from_spec(_RULE)
_RULE.loader.exec_module(_hostrule)
VIRTUAL, inet_aton, bridge_addresses, why = _hostrule.VIRTUAL, _hostrule.inet_aton, _hostrule.bridge_addresses, _hostrule.why  # the one rule (the console's file)



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


_URL = re.compile(r"https?://(?:\[([0-9A-Fa-f:.]+)\]|([A-Za-z0-9.-]+))(?::([0-9]+))?(/\S*)?", re.ASCII)
_LABEL = re.compile(r"[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?", re.ASCII)
_QUAD_PART = re.compile(r"0|[1-9][0-9]{0,2}")


def url_problem(url: str | None) -> str | None:
    """What makes a URL no address at all, or None when it is one (QA1-202, QA1-206): http or https, then a host that is a bracketed IPv6
    literal, an IPv4 address in four decimal parts, or a host name (labels of letters, digits and dashes between dots, the last starting
    with a letter, no dot at the end), then an optional port 1 to 65535 and path. Word for word the server's HostAddress.urlProblem; both
    are tested on net/drishti-net/src/test/resources/net/advertise-urls.tsv
    (test_cli_net.py). Whether the host is one other machines can call is loopback_url."""
    u = "" if url is None else str(url)
    m = _URL.fullmatch(u)
    if not m:
        return f"'{u}' is not an http or https address with a host (https://host:port)"
    if m.group(1) is not None:
        try:
            if ":" not in m.group(1) or ipaddress.ip_address(m.group(1)).version != 6:
                raise ValueError(m.group(1))
        except ValueError:
            return f"'{m.group(1)}' is not an IPv6 address"
    else:
        h = m.group(2)
        labels = h.split(".")
        if h.endswith("."):
            return f"'{h}' ends with a dot: write the address without it"
        if re.fullmatch(r"[0-9.]+", h):
            if len(labels) != 4 or not all(_QUAD_PART.fullmatch(x) and int(x) <= 255 for x in labels):
                return f"'{h}' is not an IPv4 address: four numbers 0 to 255 (192.168.2.77)"
        elif not (labels[-1][:1].isalpha() and all(_LABEL.fullmatch(x) for x in labels)):
            return f"'{h}' is not a host name: letters, digits and dashes between dots, no dot at the end"
    port = m.group(3)
    if port is not None and (len(port) > 5 or not 1 <= int(port) <= 65535):
        return f"port {port} is not a port: the port must be 1 to 65535"
    return None


def callable_url(url: str | None) -> bool:
    """True when other machines can call a URL: it is an address (url_problem) and its host is not loopback or wildcard."""
    return url_problem(url) is None and not loopback_url(url)


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
