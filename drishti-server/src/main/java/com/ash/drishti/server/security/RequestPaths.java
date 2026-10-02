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
package com.ash.drishti.server.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * The path a request is routed on, and whether its request line spells that path canonically.
 *
 * <p>The servlet container decodes the request line, removes path parameters ({@code ;x}) and dot segments, and maps
 * filters and the dispatcher on the result; Spring matches handlers on the same decoded, parameter-free segments. A
 * security decision taken on the raw {@link HttpServletRequest#getRequestURI()} therefore sees a different path from
 * the one that is served ({@code /api/v1;x/packs} and {@code /api/%761/packs} are both served as {@code /api/v1/packs}),
 * which is how the token filter was once skipped (QA 2026-10-01, SEC-02). So filters decide on {@link #routed}, and
 * {@link PathGuard} refuses any request line under the guarded prefixes that is not canonical, so the two can never
 * disagree.
 */
public final class RequestPaths {

    private RequestPaths() {}

    /** The decoded, normalised path within the application that the container maps and the dispatcher serves. */
    public static String routed(HttpServletRequest req) {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? "/" : path;
    }

    /**
     * Why a raw path (the request URI without the context path and query) is not canonical, if it is not: it has a path
     * parameter ({@code ;}), an encoded character that never needs encoding (a letter, digit, {@code - . _ ~}) or one
     * that changes the structure ({@code / \}), a malformed escape, or a dot or empty segment. Encoded characters that
     * are data (a space, {@code ;}, {@code %}, {@code :}, non-ASCII letters in a name or an id) stay allowed.
     */
    public static Optional<String> nonCanonical(String rawPath) {
        if (rawPath.indexOf(';') >= 0) {
            return Optional.of("path parameters (;) are not accepted");
        }
        for (int i = rawPath.indexOf('%'); i >= 0; i = rawPath.indexOf('%', i + 1)) {
            int hi = i + 2 < rawPath.length() ? Character.digit(rawPath.charAt(i + 1), 16) : -1;
            int lo = hi >= 0 ? Character.digit(rawPath.charAt(i + 2), 16) : -1;
            if (lo < 0) {
                return Optional.of("malformed percent-encoding");
            }
            char c = (char) (hi * 16 + lo);
            if (isUnreserved(c) || c == '/' || c == '\\') {
                return Optional.of("'" + c + "' is not to be percent-encoded in a path");
            }
        }
        int end = rawPath.length();
        for (int start = 1; start <= end; ) {
            int slash = rawPath.indexOf('/', start);
            int segEnd = slash < 0 ? end : slash;
            String seg = rawPath.substring(start, segEnd);
            if (seg.equals(".") || seg.equals("..")) {
                return Optional.of("dot segments are not accepted in a path");
            }
            if (seg.isEmpty() && slash >= 0) {
                return Optional.of("empty segments (//) are not accepted in a path");
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return Optional.empty();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_'
                || c == '~';
    }
}
