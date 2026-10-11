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
package com.ash.drishti.api.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The hosts that bypass the proxy: the {@code no-proxy} list, or the {@code NO_PROXY} environment variable. An entry is one of
 * <ul>
 *   <li>{@code *}: every host;
 *   <li>a host name ({@code intranet.example}), which also covers its sub-domains;
 *   <li>a suffix ({@code .corp.example} or {@code *.corp.example}), which covers the sub-domains (and, for both
 *       forms, the bare domain too, as curl does);
 *   <li>an address ({@code 10.1.2.3}) or a CIDR block ({@code 10.0.0.0/8}, {@code fd00::/8}), matched against a host that is an
 *       address literal (a host name is never resolved here: resolving would itself need the network).
 * </ul>
 * A port after the host ({@code intranet:8080}) is ignored. Matching is case-insensitive.
 */
public final class NoProxy {

    private final List<String> entries;
    private final List<String> names = new ArrayList<>();
    private final List<Cidr> blocks = new ArrayList<>();
    private final boolean all;

    public NoProxy(List<String> entries) {
        this.entries = List.copyOf(entries);
        boolean everything = false;
        for (String raw : entries) {
            String e = raw.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.equals("*")) {
                everything = true;
            } else if (e.contains("/") && Cidr.parse(e) instanceof Cidr c) {
                blocks.add(c);
            } else {
                if (e.startsWith("*.")) {
                    e = e.substring(1);
                }
                int colon = e.lastIndexOf(':');
                if (colon > 0 && e.indexOf(':') == colon) {
                    e = e.substring(0, colon);                // host:port
                }
                names.add(e);
            }
        }
        this.all = everything;
    }

    /** Splits a comma or whitespace separated list. */
    public static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        if (list != null) {
            for (String s : list.split("[,\\s]+")) {
                if (!s.isBlank()) {
                    out.add(s.strip());
                }
            }
        }
        return out;
    }

    public List<String> entries() {
        return entries;
    }

    /** True when requests to {@code host} go direct. */
    public boolean matches(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (all) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        for (String n : names) {
            if (n.startsWith(".") ? h.endsWith(n) || h.equals(n.substring(1)) : h.equals(n) || h.endsWith("." + n)) {
                return true;
            }
        }
        if (!blocks.isEmpty() && looksLikeAddress(h)) {
            try {
                byte[] addr = InetAddress.getByName(h).getAddress();     // an address literal: no lookup happens
                for (Cidr c : blocks) {
                    if (c.contains(addr)) {
                        return true;
                    }
                }
            } catch (UnknownHostException ignored) {
                // not an address after all
            }
        }
        return false;
    }

    private static boolean looksLikeAddress(String h) {
        return h.indexOf(':') >= 0 || h.matches("[0-9.]+");
    }

    /** An address block. */
    private record Cidr(byte[] base, int bits) {

        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            try {
                String addr = text.substring(0, slash);
                if (!looksLikeAddress(addr)) {
                    return null;
                }
                byte[] b = InetAddress.getByName(addr).getAddress();
                int bits = Integer.parseInt(text.substring(slash + 1));
                if (bits < 0 || bits > b.length * 8) {
                    throw new ProxyException("no-proxy: '" + text + "' is not a valid CIDR block");
                }
                return new Cidr(b, bits);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new ProxyException("no-proxy: '" + text + "' is not a valid CIDR block");
            }
        }

        boolean contains(byte[] addr) {
            if (addr.length != base.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != base[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (addr[full] & mask) == (base[full] & mask);
        }
    }
}
