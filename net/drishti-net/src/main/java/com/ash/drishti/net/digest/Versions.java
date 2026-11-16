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
package com.ash.drishti.net.digest;

import java.util.Comparator;

/**
 * Pack versions in order: {@code 2.10.0} after {@code 2.9.1}, numbers compared as numbers part by part, a pre-release
 * ({@code 2.4.0-rc1}) before its release, anything else by its text. The newest version of a pack in the cluster is the one served.
 */
public final class Versions {

    public static final Comparator<String> ORDER = Versions::compare;

    private Versions() {
    }

    public static int compare(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        String[] xm = x.split("-", 2);
        String[] ym = y.split("-", 2);
        String[] xp = xm[0].split("\\.");
        String[] yp = ym[0].split("\\.");
        for (int i = 0; i < Math.max(xp.length, yp.length); i++) {
            String p = i < xp.length ? xp[i] : "0";
            String q = i < yp.length ? yp[i] : "0";
            int c;
            if (p.matches("\\d{1,18}") && q.matches("\\d{1,18}")) {
                c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            } else {
                c = p.compareTo(q);
            }
            if (c != 0) {
                return c;
            }
        }
        if (xm.length != ym.length) {
            return xm.length == 1 ? 1 : -1;                 // a release after its pre-release
        }
        return xm.length == 1 ? 0 : xm[1].compareTo(ym[1]);
    }
}
