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

    /** True when a host is {@code localhost} (or a name under it), a loopback address or a wildcard address. */
    public static boolean loopbackOrWildcard(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("0.0.0.0")) {
            return true;
        }
        if (h.contains(":")) {
            String compact = h.replaceAll("(^|:)0+(?=[0-9a-f])", "$1");
            return compact.equals("::") || compact.equals("::1") || compact.matches("(0:){7}[01]") || compact.equals("::0");
        }
        return false;
    }

    /** True when a URL's host is loopback or wildcard ({@link #loopbackOrWildcard(String)}); false for a URL that cannot be read. */
    public static boolean loopbackUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            if (host == null && u.getAuthority() != null) {
                host = u.getAuthority().replaceAll(":\\d+$", "");
            }
            return loopbackOrWildcard(host);
        } catch (IllegalArgumentException e) {
            return false;
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
        return "'" + url + "' is a loopback or wildcard address: " + what + " must be an address other machines can call ("
                + ip + "use " + suggestion(url) + ")";
    }
}
