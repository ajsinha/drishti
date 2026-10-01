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
package com.ash.drishti.deltalake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts between the path strings Delta Kernel passes around and local file-system paths, on Linux, macOS and
 * Windows, as plain string work (so the Windows rules are tested on any machine).
 *
 * <p>Kernel's paths are Hadoop-style URI strings that are <em>not</em> percent-encoded: {@code file:/C:/lake/trade},
 * {@code file:/home/me/lake/trade}, {@code file://server/share/lake} (a Windows UNC share). The connector may also hand
 * a table's root as a plain path ({@code C:\lake\trade}, {@code /home/me/lake}) or as a {@code java.nio} URI with
 * percent-encoding ({@code file:///C:/My%20Lake/trade/}). Every form resolves to one canonical URI string, which is what
 * Kernel joins child paths onto, and back to a path the operating system opens.
 */
public final class LocalPaths {

    /** {@code C:}, {@code C:\…}, {@code C:/…}, also after a leading slash ({@code /C:/…}). */
    private static final Pattern DRIVE = Pattern.compile("^/?[A-Za-z]:([/\\\\].*)?$");

    /** A drive's root, {@code /C:/}, which keeps its slash. */
    private static final Pattern DRIVE_ROOT = Pattern.compile("^/?[A-Za-z]:/$");

    /** Whether this JVM runs on Windows. */
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private LocalPaths() {}

    /** Whether {@code path} names a local file: a {@code file:} URI, or a path without a scheme. */
    public static boolean isLocal(String path) {
        String p = path.trim();
        if (p.regionMatches(true, 0, "file:", 0, 5)) {
            return true;
        }
        if (DRIVE.matcher(p).matches()) {
            return true;                                         // C:\… is a drive, not a scheme "c"
        }
        int colon = p.indexOf(':');
        int slash = indexOfSlash(p);
        return colon < 0 || (slash >= 0 && slash < colon);
    }

    /** The scheme of a URI string in lower case ({@code s3a}, {@code file}), or {@code file} for a plain path. */
    public static String scheme(String path) {
        if (isLocal(path)) {
            return "file";
        }
        return path.substring(0, path.indexOf(':')).toLowerCase(Locale.ROOT);
    }

    /**
     * The operating-system path for {@code path}, with the separators of the platform: on Windows {@code C:\lake\t},
     * {@code \\server\share\lake}; elsewhere {@code /home/me/lake}. Relative paths stay relative. The input is not
     * percent-decoded (Kernel's paths are not encoded); see {@link #decodeUri(String)} for a {@code java.nio} URI.
     */
    public static String toLocal(String path, boolean windows) {
        String p = path.trim();
        if (p.regionMatches(true, 0, "file:", 0, 5)) {
            p = p.substring(5);
            if (p.startsWith("///")) {
                p = p.substring(2);                              // file:///C:/x and file:///home/x
            } else if (p.startsWith("//")) {
                String rest = p.substring(2);
                int slash = rest.indexOf('/');
                String authority = slash < 0 ? rest : rest.substring(0, slash);
                String tail = slash < 0 ? "/" : rest.substring(slash);
                if (authority.isEmpty() || authority.equalsIgnoreCase("localhost")) {
                    p = tail;
                } else if (DRIVE.matcher(authority).matches()) {
                    p = authority + tail;                        // file://C:/x (seen in hand-written roots)
                } else {
                    p = "//" + authority + tail;                 // a UNC share: file://server/share/x
                }
            }
        }
        if (windows) {
            if (DRIVE.matcher(p).matches() && p.startsWith("/")) {
                p = p.substring(1);                              // /C:/x to C:/x
            }
            p = p.replace('/', '\\');
            if (p.length() == 2 && p.charAt(1) == ':') {
                p = p + "\\";                                    // C: alone is the drive's current directory
            }
        }
        return p;
    }

    /**
     * The canonical URI string for an absolute local path: {@code file:/C:/lake/t}, {@code file://server/share/lake},
     * {@code file:/home/me/lake}. No trailing slash (except for a root), no percent-encoding.
     */
    public static String toUri(String absolutePath, boolean windows) {
        String p = absolutePath;
        if (windows) {
            p = p.replace('\\', '/');
        }
        if (p.startsWith("//") && windows) {
            return "file:" + stripTrailingSlash(p);              // //server/share/x
        }
        if (DRIVE.matcher(p).matches() && !p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.startsWith("/")) {
            throw new IllegalArgumentException("not an absolute path: " + absolutePath);
        }
        return "file:" + stripTrailingSlash(p);
    }

    /**
     * Decodes a percent-encoded {@code file:} URI, as {@code java.nio.file.Path#toUri()} writes it, to an unencoded one
     * ({@code file:///C:/My%20Lake/} to {@code file:/C:/My Lake/}). Anything else is returned as it is.
     */
    public static String decodeUri(String path) {
        if (!path.regionMatches(true, 0, "file:", 0, 5) || path.indexOf('%') < 0) {
            return path;
        }
        try {
            URI u = new URI(path);
            String authority = u.getRawAuthority() == null ? "" : u.getAuthority();
            return "file:" + (authority.isEmpty() ? "" : "//" + authority) + u.getPath();
        } catch (URISyntaxException e) {
            return path;                                         // not an encoded URI: a literal % in a name
        }
    }

    private static String stripTrailingSlash(String p) {
        String s = p;
        while (s.length() > 1 && s.endsWith("/") && !DRIVE_ROOT.matcher(s).matches()) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static int indexOfSlash(String p) {
        int a = p.indexOf('/');
        int b = p.indexOf('\\');
        return a < 0 ? b : b < 0 ? a : Math.min(a, b);
    }
}
