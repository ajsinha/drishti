/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.net.config;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A cluster member's real address (DRISHTI_NET.md section 4): members advertise themselves, and name their seeds, only by an address a
 * peer on another machine can call, never {@code localhost}, a loopback address ({@code 127.x.x.x}, {@code ::1}) or a wildcard one
 * ({@code 0.0.0.0}, {@code ::}). When node.yaml sets no {@code advertise-url}, the node advertises this machine's primary IPv4 address
 * with the server's port: the address of the interface the default route leaves by, else the first interface that is up and is not a
 * loopback, a container bridge or a virtual link.
 */
public final class HostAddress {

    /** Interfaces never chosen: container and VM bridges, virtual Ethernet pairs, tunnels. */
    private static final Pattern VIRTUAL = Pattern.compile("^(docker|br-|veth|virbr|vmnet|vboxnet|cni|flannel|cali|lxc|lxd|podman|tun|tap|zt|tailscale|wg|kube|vEthernet).*",
            Pattern.CASE_INSENSITIVE);
    private static volatile Optional<String> detected;

    private HostAddress() {
    }

    /** This machine's primary IPv4 address (detected once), or empty when it has none but loopback. */
    public static Optional<String> primary() {
        Optional<String> d = detected;
        if (d == null) {
            d = detect();
            detected = d;
        }
        return d;
    }

    private static Optional<String> detect() {
        Optional<String> viaRoute = defaultRouteInterface().flatMap(HostAddress::ipv4Of);
        if (viaRoute.isPresent()) {
            return viaRoute;
        }
        try (DatagramSocket s = new DatagramSocket()) {        // no packet is sent: connecting a UDP socket only picks the route
            s.connect(new InetSocketAddress("192.0.2.1", 9));
            InetAddress a = s.getLocalAddress();
            if (a instanceof Inet4Address && usable(a) && NetworkInterface.getByInetAddress(a) != null
                    && !VIRTUAL.matcher(NetworkInterface.getByInetAddress(a).getName()).matches()) {
                return Optional.of(a.getHostAddress());
            }
        } catch (SocketException | RuntimeException e) {
            // no route: the interfaces are looked at
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual() && !ni.isPointToPoint() && !VIRTUAL.matcher(ni.getName()).matches()) {
                    Optional<String> a = ipv4Of(ni);
                    if (a.isPresent()) {
                        return a;
                    }
                }
            }
        } catch (SocketException e) {
            // none readable
        }
        return Optional.empty();
    }

    /** Linux: the interface of the default route (/proc/net/route, destination 0.0.0.0, lowest metric). */
    private static Optional<NetworkInterface> defaultRouteInterface() {
        Path route = Path.of("/proc/net/route");
        if (!Files.isReadable(route)) {
            return Optional.empty();
        }
        try {
            String best = null;
            long bestMetric = Long.MAX_VALUE;
            List<String> lines = Files.readAllLines(route);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 7 && "00000000".equals(f[1]) && "00000000".equals(f[7])) {
                    long metric = Long.parseLong(f[6]);
                    if (metric < bestMetric && !VIRTUAL.matcher(f[0]).matches()) {
                        best = f[0];
                        bestMetric = metric;
                    }
                }
            }
            return best == null ? Optional.empty() : Optional.ofNullable(NetworkInterface.getByName(best));
        } catch (Exception e) {   // NOPMD - any trouble reading the table: the next way is tried
            return Optional.empty();
        }
    }

    private static Optional<String> ipv4Of(NetworkInterface ni) {
        try {
            if (!ni.isUp() || ni.isLoopback()) {
                return Optional.empty();
            }
        } catch (SocketException e) {
            return Optional.empty();
        }
        return Collections.list(ni.getInetAddresses()).stream().filter(a -> a instanceof Inet4Address && usable(a))
                .map(InetAddress::getHostAddress).findFirst();
    }

    private static boolean usable(InetAddress a) {
        return !a.isLoopbackAddress() && !a.isAnyLocalAddress() && !a.isLinkLocalAddress() && !a.isMulticastAddress();
    }

    /**
     * True when a host is no address another machine can call: {@code localhost} in any spelling ({@code localhost.}, a name under it,
     * {@code ip6-localhost}), a loopback, wildcard or link-local address in any notation ({@code 127.0.1.1}, {@code 0}, {@code 2130706433},
     * {@code 0x7f000001}, {@code [::1]}, {@code fe80::1}), an address of one of this machine's container or VM bridges ({@code 172.17.0.1}
     * on docker0), or a name that resolves here to one of those (a machine's own host name is {@code 127.0.1.1} on Debian and Ubuntu).
     * A name that cannot be resolved here is not refused: another machine may know it. Resolutions are kept a minute (QA1-09).
     */
    public static boolean loopbackOrWildcard(String host) {
        return why(host).isPresent();
    }

    /** What makes a host unusable as an advertised address (see {@link #loopbackOrWildcard}), or empty when it is usable. */
    public static Optional<String> why(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);                            // "localhost." is localhost
        }
        if (h.isEmpty()) {
            return Optional.empty();
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.equals("ip6-localhost") || h.equals("ip6-loopback")) {
            return Optional.of("loopback");
        }
        Optional<byte[]> v4 = inetAton(h);
        try {
            if (v4.isPresent()) {
                return bad(InetAddress.getByAddress(v4.get()));
            }
            if (h.contains(":")) {
                if (!h.matches("[0-9a-f:.%a-z]+")) {
                    return Optional.empty();
                }
                return bad(InetAddress.getByName(h));                       // a literal: no lookup
            }
        } catch (java.net.UnknownHostException e) {
            return Optional.empty();
        }
        return resolved(h);
    }

    /** Why an address is unusable, or empty. */
    private static Optional<String> bad(InetAddress a) {
        if (a.isLoopbackAddress()) {
            return Optional.of("loopback");
        }
        if (a.isAnyLocalAddress()) {
            return Optional.of("wildcard");
        }
        if (a.isLinkLocalAddress()) {
            return Optional.of("link-local");
        }
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(a);
            if (ni != null && VIRTUAL.matcher(ni.getName()).matches()) {
                return Optional.of("this machine's " + ni.getName() + " bridge");
            }
        } catch (SocketException | RuntimeException e) {
            // cannot tell: not refused
        }
        return Optional.empty();
    }

    private record Resolution(long until, Optional<String> why) {}

    private static final java.util.concurrent.ConcurrentHashMap<String, Resolution> RESOLVED = new java.util.concurrent.ConcurrentHashMap<>();

    /** A name looked up here: unusable when any of its addresses is ({@code oryxpro} → 127.0.1.1). */
    private static Optional<String> resolved(String name) {
        long now = System.currentTimeMillis();
        Resolution r = RESOLVED.get(name);
        if (r != null && r.until() > now) {
            return r.why();
        }
        Optional<String> why = Optional.empty();
        try {
            for (InetAddress a : InetAddress.getAllByName(name)) {
                Optional<String> b = bad(a);
                if (b.isPresent()) {
                    why = Optional.of(name + " is " + a.getHostAddress() + " here: " + b.get());
                    break;
                }
            }
        } catch (java.net.UnknownHostException | RuntimeException e) {
            // unknown here: another machine may know it
        }
        if (RESOLVED.size() > 1000) {
            RESOLVED.clear();
        }
        RESOLVED.put(name, new Resolution(now + 60_000, why));
        return why;
    }

    /**
     * An IPv4 address in any notation {@code inet_aton} reads (and so the HTTP clients that call it): one to four parts, each decimal,
     * {@code 0x} hex or {@code 0} octal, the last filling the bytes left ({@code 0} is 0.0.0.0, {@code 2130706433} and {@code 0x7f000001}
     * are 127.0.0.1, {@code 127.1} is 127.0.0.1). Empty when the text is not one.
     */
    static Optional<byte[]> inetAton(String h) {
        if (!h.matches("(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+)){0,3}")) {
            return Optional.empty();
        }
        String[] parts = h.split("\\.");
        long[] v = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                String x = parts[i];
                v[i] = x.startsWith("0x") ? Long.parseLong(x.substring(2), 16) : x.length() > 1 && x.startsWith("0") ? Long.parseLong(x, 8)
                        : Long.parseLong(x);
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        long value = 0;
        int n = parts.length;
        for (int i = 0; i < n - 1; i++) {
            if (v[i] > 255) {
                return Optional.empty();
            }
            value |= v[i] << (8 * (3 - i));
        }
        long lastMax = (1L << (8 * (5 - n))) - 1;
        if (v[n - 1] > lastMax) {
            return Optional.empty();
        }
        value |= v[n - 1];
        return Optional.of(new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    /** True when a URL's host is loopback or wildcard ({@link #loopbackOrWildcard(String)}); false for a URL that cannot be read. */
    public static boolean loopbackUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return loopbackOrWildcard(hostOf(url));
    }

    /** A URL's host, or null when it cannot be read. */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            if (host == null && u.getAuthority() != null) {
                host = u.getAuthority().replaceAll("^[^@]*@", "").replaceAll(":\\d+$", "");
            }
            return host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The same address with this machine's real address as its host (port and path kept), for a refusal's message. */
    public static String suggestion(String url) {
        String ip = primary().orElse("<this machine's IP>");
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme() == null ? "http" : u.getScheme();
            return scheme + "://" + ip + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "http://" + ip + ":<port>";
        }
    }

    /** The refusal of a loopback or wildcard address, naming the address to use instead. */
    public static String refusal(String what, String url) {
        String ip = primary().map(a -> "this machine's address is " + a + ": ").orElse("");
        String why = why(hostOf(url)).map(w -> " (" + w + ")").orElse("");
        return "'" + url + "' is a loopback or wildcard address" + why + ": " + what + " must be an address other machines can call ("
                + ip + "use " + suggestion(url) + ")";
    }
}
