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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses an oversized request body with a clean {@code 413 application/problem+json} ({@code DRS-5005}) naming the limit
 * and the size, instead of letting the connection break mid-upload (S2-08). Limits are per path prefix
 * ({@code drishti.http.request-limits.rules}). A declared {@code Content-Length} over the limit is answered before the body
 * is read; a chunked body is read through a counting stream and answered 413 on overflow. Tomcat then drains a bounded
 * remainder ({@code server.tomcat.max-swallow-size}) so the client receives the answer rather than a reset.
 */
public final class RequestSizeFilter extends OncePerRequestFilter {

    private final List<RequestLimitProperties.Rule> rules;

    public RequestSizeFilter(List<RequestLimitProperties.Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long limit = limitFor(req.getRequestURI());
        if (limit <= 0) {
            chain.doFilter(req, res);
            return;
        }
        long declared = req.getContentLengthLong();
        if (declared > limit) {
            refuse(res, limit, declared, false);
            return;
        }
        Counting counting = new Counting(req, limit);
        try {
            chain.doFilter(counting, res);
        } catch (ServletException | IOException | RuntimeException e) {
            if (!counting.overflow || res.isCommitted()) {
                throw e;
            }
        }
        if (counting.overflow && !res.isCommitted()) {
            refuse(res, limit, counting.seen.get(), true);
        }
    }

    private long limitFor(String uri) {
        for (RequestLimitProperties.Rule r : rules) {
            if (r.prefix() != null && uri.startsWith(r.prefix()) && r.maxMb() != null) {
                return r.maxMb() * 1024L * 1024L;
            }
        }
        return 0;
    }

    private static void refuse(HttpServletResponse res, long limit, long size, boolean atLeast) throws IOException {
        res.reset();
        res.setStatus(413);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Connection", "close");
        String have = (atLeast ? "at least " : "") + mb(size);
        res.getWriter().write("{\"title\":\"payload too large\",\"status\":413,\"code\":\"" + com.ash.drishti.common.ErrorCode.PAYLOAD_TOO_LARGE.code()
                + "\",\"detail\":\"the request body is " + have + ", over the limit of " + mb(limit)
                + " (drishti.builder.max-total-mb)\",\"limitBytes\":" + limit + ",\"sizeBytes\":" + size + "}");
        res.flushBuffer();
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    /** The body, counted as it is read; reading past the limit raises an {@link IOException} and records the overflow. */
    private static final class Counting extends HttpServletRequestWrapper {
        private final long limit;
        volatile boolean overflow;
        final java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
        private ServletInputStream stream;

        Counting(HttpServletRequest req, long limit) {
            super(req);
            this.limit = limit;
        }

        @Override
        public synchronized ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Limited(super.getInputStream());
            }
            return stream;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    enc == null ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(enc)));
        }

        private final class Limited extends ServletInputStream {
            private final ServletInputStream in;

            Limited(ServletInputStream in) {
                this.in = in;
            }

            private void count(int n) throws IOException {
                if (n > 0) {
                    long total = seen.addAndGet(n);
                    if (total > limit) {
                        overflow = true;
                        throw new IOException("request body over the limit of " + limit + " bytes");
                    }
                }
            }

            @Override
            public int read() throws IOException {
                int b = in.read();
                count(b < 0 ? 0 : 1);
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                count(n);
                return n;
            }

            @Override
            public boolean isFinished() {
                return in.isFinished();
            }

            @Override
            public boolean isReady() {
                return in.isReady();
            }

            @Override
            public void setReadListener(ReadListener l) {
                in.setReadListener(l);
            }
        }
    }
}
